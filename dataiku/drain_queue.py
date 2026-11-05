# ═══════════════════════════════════════════════════════════════════════════
#  Drain the n8n queue into Snowflake  ·  Dataiku scenario step (Python)
#
#  Runs on the Design/Automation node every few minutes. Pulls rows that n8n
#  parked, executes them against Snowflake through the DSS connection, then
#  acks so n8n can drop them.
#
#  WHY THIS EXISTS: n8n cannot reach Snowflake. The developer's access is SSO,
#  PAT auth is refused without a network policy, and a key pair on their own
#  user needs OWNERSHIP. The only working credential is the per-user OAuth2 token
#  held by the DSS Snowflake connection - and DSS will not hand that out, so
#  the write has to happen in here.
#
#  KNOWN AND ACCEPTED (see snowflake-sso-service-account memory):
#  every row lands in Snowflake as jane.doe, and this stops working the
#  day that account changes. This is a bridge until a service account exists.
# ═══════════════════════════════════════════════════════════════════════════

import logging

import dataiku
import requests
from dataiku.core.sql import SQLExecutor2

logger = logging.getLogger("zk.drain")

# ── Configuration ──────────────────────────────────────────────────────────
# Project variables, not literals: the shared secret must not sit in a code
# editor that every project contributor can read. Settings -> Variables.
#
#   { "zk_n8n_url": "https://automations.example.org",
#     "zk_shared_secret": "…", "zk_connection": "SNOWFLAKE" }
#
# Dataiku project variables are not a secrets manager - they are readable by
# anyone with project access. That is weaker than the n8n environment holding
# the same value, and worth knowing rather than discovering.
_v = dataiku.get_custom_variables()


def _need(name):
    """Read a variable, rejecting an unfilled placeholder.

    A value still wrapped in angle brackets is the single most likely mistake
    here, because the setup doc used <...> placeholders. Left in place it
    surfaces as "Connection '<Development>' does not exist", which reads like a
    missing connection rather than a typo and sends you into Administration ->
    Connections looking for a fault that is not there. The secret is worse: a
    bracketed value fails as a 401 with nothing pointing at the cause.
    """
    value = str(_v.get(name, "")).strip()
    if not value:
        raise ValueError("project variable %s is not set" % name)
    if value.startswith("<") and value.endswith(">"):
        raise ValueError(
            "project variable %s is still a placeholder (%r) - remove the "
            "angle brackets" % (name, value)
        )
    return value


N8N_URL    = _need("zk_n8n_url").rstrip("/")
SECRET     = _need("zk_shared_secret")
CONNECTION = _need("zk_connection")

# One scenario run drains at most this many rows. A cap means a weekend of
# backlog arrives over several runs instead of one 20-minute transaction that
# times out and retries forever.
BATCH_LIMIT = 500
TIMEOUT = 60

# Statements per transaction. Small enough that a failure replays cheaply,
# large enough that one connection covers a normal drain instead of 18.
CHUNK_SIZE = 50


def _headers():
    return {"X-ZK-Secret": SECRET}


def fetch_pending():
    r = requests.get(
        N8N_URL + "/webhook/zk/pending",
        headers=_headers(),
        params={"limit": BATCH_LIMIT},
        timeout=TIMEOUT,
    )
    # A 500 here is usually the guard rejecting us, which means the secret in
    # project variables and the one in n8n's environment have drifted apart.
    r.raise_for_status()
    return r.json().get("rows", [])


def ack(ids):
    r = requests.post(
        N8N_URL + "/webhook/zk/ack",
        headers=_headers(),
        json={"ids": ids},
        timeout=TIMEOUT,
    )
    r.raise_for_status()


def drain(executor, chunk):
    """Run one chunk's statements in a single committed transaction.

    The DML goes in pre_queries with an explicit BEGIN/COMMIT around it, and
    the visible query is a throwaway SELECT. This is not a stylistic choice:
    query_to_df() opens a connection per call and Dataiku's SQL connections are
    not in autocommit, so DML passed as the main query is rolled back when the
    connection closes. On 4 Sep 2026 that produced a scenario that ran to
    completion, logged every MERGE as successful, and left every table empty -
    while each statement sat for exactly 307s, Snowflake's LOCK_TIMEOUT, queued
    behind the lock the previous abandoned transaction was still holding.
    Successful statements plus empty tables plus a 300-second stall is the
    signature of a missing commit.

    One transaction per chunk also makes the ack honest: the batch either lands
    whole or not at all, so there is no partial state to reason about.
    """
    executor.query_to_df(
        "SELECT 1 AS drained",
        pre_queries=["BEGIN"] + [row["sql"] for row in chunk] + ["COMMIT"],
    )


def main():
    rows = fetch_pending()
    if not rows:
        logger.info("queue empty")
        return

    executor = SQLExecutor2(connection=CONNECTION)

    # Chunked rather than one giant transaction: a chunk that fails is replayed
    # whole, and every statement the parser emits is a MERGE on a business key,
    # so replay is a no-op. Order is preserved within a chunk because the
    # parser emits an EMPLOYEES placeholder before the BIOMETRIC_TEMPLATES row
    # that references that PIN.
    total = 0
    for start in range(0, len(rows), CHUNK_SIZE):
        chunk = rows[start:start + CHUNK_SIZE]
        try:
            drain(executor, chunk)
        except Exception:
            logger.exception(
                "chunk starting at row %d failed and was rolled back; %d row(s) "
                "committed so far, the rest stay queued for the next run",
                start + 1, total,
            )
            raise
        ack([row["id"] for row in chunk])
        total += len(chunk)

    logger.info("drained %d row(s)", total)


main()
