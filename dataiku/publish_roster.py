# ═══════════════════════════════════════════════════════════════════════════
#  Publish the roster from Snowflake into n8n  ·  Dataiku scenario step
#
#  The second half of the bridge. drain_queue.py carries device pushes INTO
#  Snowflake; this carries the roster back OUT, so a person enrolled on one
#  terminal is known to the others.
#
#  WHY IT IS SHAPED THIS WAY: the obvious design - device asks n8n, n8n runs a
#  SELECT - is impossible. n8n cannot reach Snowflake at all; that is the whole
#  reason the queue and this scenario exist. The only process holding a working
#  Snowflake credential is this one, so the roster has to be pushed out to n8n
#  and parked there for devices to collect. See SYNC-DESIGN.md section 3.2.
#
#  Add this as a SECOND Python step in the existing "Drain ZK attendance queue"
#  scenario, after the drain step. Order matters: draining first means an
#  enrolment pushed by a device this cycle is already in Snowflake and goes back
#  out in the same run, instead of waiting for the next one.
# ═══════════════════════════════════════════════════════════════════════════

import json
import logging

import dataiku
import requests
from dataiku.core.sql import SQLExecutor2

logger = logging.getLogger("zk.publish")

_v = dataiku.get_custom_variables()


def _need(name):
    """Read a variable, rejecting an unfilled placeholder. See drain_queue.py."""
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

TIMEOUT = 120

# Rows per POST. Templates are ~2-3 KB of base64 each and a person can have
# four, so a hundred people in one request is several megabytes - enough to hit
# a proxy body limit and fail in a way that looks like a network fault.
PAGE = 25

# ── The template switch ────────────────────────────────────────────────────
# Leave False until the portability test in SYNC-DESIGN.md section 5 has passed
# on real hardware.
#
# Publishing templates before that is the expensive mistake: every terminal
# would fill up with templates that may not match, staff enrolled elsewhere
# would silently fail to be recognised, and it reads as a broken sensor rather
# than a bad assumption. Everything else here - names, departments, deletions -
# is useful immediately and is not affected by that question.
INCLUDE_TEMPLATES = False


def _headers():
    return {"X-ZK-Secret": SECRET}


def fetch_roster():
    """Everything on the roster, tombstones included.

    Tombstones are the point, not an oversight: a device cannot learn about a
    deletion from a row's absence, because absence is indistinguishable from
    "not pulled yet". The deletion has to arrive as a row that says so.

    UPDATED_AT becomes the version. It is assigned by Snowflake, so device
    clocks - which are set by whoever is holding the terminal - never enter the
    decision about what is newer.
    """
    executor = SQLExecutor2(connection=CONNECTION)
    employees = executor.query_to_df(
        """
        SELECT DEVICE_PIN,
               COALESCE(FIRST_NAME, '')                      AS FIRST_NAME,
               COALESCE(LAST_NAME, '')                       AS LAST_NAME,
               COALESCE(FULL_NAME, '')                       AS FULL_NAME,
               COALESCE(DEPARTMENT, '')                      AS DEPARTMENT,
               COALESCE(GENDER, '')                          AS GENDER,
               IFF(DELETED_AT IS NULL, FALSE, TRUE)          AS DELETED,
               DATE_PART(EPOCH_MILLISECOND, UPDATED_AT)      AS VERSION
        FROM DEVELOPMENT.ATTENDANCE.EMPLOYEES
        ORDER BY UPDATED_AT
        """
    )

    templates = {}
    if INCLUDE_TEMPLATES:
        rows = executor.query_to_df(
            """
            SELECT EMPLOYEE_PIN, FINGER_INDEX, TEMPLATE_DATA
            FROM DEVELOPMENT.ATTENDANCE.BIOMETRIC_TEMPLATES
            WHERE IS_VALID AND TEMPLATE_DATA IS NOT NULL
            ORDER BY EMPLOYEE_PIN, FINGER_INDEX
            """
        )
        for _, r in rows.iterrows():
            templates.setdefault(str(r["EMPLOYEE_PIN"]), []).append({
                "fingerIndex": int(r["FINGER_INDEX"]),
                # Verbatim. Do not decode, re-encode or strip: it has to reach
                # the device byte-identical or the matcher will reject it.
                "template": r["TEMPLATE_DATA"],
            })

    out = []
    for _, r in employees.iterrows():
        pin = str(r["DEVICE_PIN"])
        out.append({
            "pin": pin,
            "firstName": r["FIRST_NAME"],
            "lastName": r["LAST_NAME"],
            "fullName": r["FULL_NAME"],
            "department": r["DEPARTMENT"],
            "gender": r["GENDER"],
            "deleted": bool(r["DELETED"]),
            "version": int(r["VERSION"]),
            "fingers": templates.get(pin, []),
        })
    return out


def fetch_departments():
    executor = SQLExecutor2(connection=CONNECTION)
    rows = executor.query_to_df(
        """
        SELECT NAME,
               IFF(DELETED_AT IS NULL, FALSE, TRUE)     AS DELETED,
               DATE_PART(EPOCH_MILLISECOND, UPDATED_AT) AS VERSION
        FROM DEVELOPMENT.ATTENDANCE.DEPARTMENTS
        ORDER BY UPDATED_AT
        """
    )
    return [
        {"name": r["NAME"], "deleted": bool(r["DELETED"]), "version": int(r["VERSION"])}
        for _, r in rows.iterrows()
    ]


def publish(path, key, rows):
    """POST in pages, failing loudly rather than half-publishing silently."""
    sent = 0
    for start in range(0, len(rows), PAGE):
        page = rows[start:start + PAGE]
        response = requests.post(
            N8N_URL + path,
            headers=_headers(),
            json={key: page},
            timeout=TIMEOUT,
        )
        response.raise_for_status()
        sent += len(page)
    return sent


def main():
    departments = fetch_departments()
    if departments:
        logger.info("published %d department(s)",
                    publish("/webhook/zk/roster-publish", "departments", departments))

    roster = fetch_roster()
    if not roster:
        logger.info("roster empty, nothing to publish")
        return

    with_templates = sum(1 for r in roster if r["fingers"])
    logger.info(
        "publishing %d employee(s), %d with templates (INCLUDE_TEMPLATES=%s)",
        len(roster), with_templates, INCLUDE_TEMPLATES,
    )
    logger.info("published %d employee(s)",
                publish("/webhook/zk/roster-publish", "employees", roster))


main()
