# Queue drain: n8n → Dataiku → Snowflake

**Why this shape.** n8n cannot reach Snowflake. SSO is interactive, PAT auth is
refused without a network policy, and a key pair on your own user needs
OWNERSHIP. The one working credential is the per-user OAuth2 token inside the
DSS Snowflake connection, and DSS will not hand it out — so the write has to
happen inside DSS.

DSS pulls rather than n8n pushing, because DSS can't be called into on a
schedule you control but is perfectly happy calling out over HTTP. No Dataiku
write APIs are involved.

```
device ──ADMS + X-ZK-Secret──► n8n ──► Data table  zk_pending_sql
                                            ▲
             Dataiku scenario, every 5 min ──┘  (runs as you)
                        │
                        └─► MERGE into DEVELOPMENT.ATTENDANCE ──► POST /zk/ack
```

**Accepted trade-off:** every row lands in Snowflake as `jane.doe`, and this
stops the day that account changes. It's a bridge until a service account
exists. Swapping back is a one-node change: replace **Queue for Dataiku** with a
Snowflake node using `{{ $json.sql }}`, and delete the two queue branches.

---

## 1. Create the Data table

In n8n → **Data tables** → new table named **`zk_pending_sql`** with four
columns, all **String** except the last:

| Column | Type | Holds |
|---|---|---|
| `sql` | String | one complete MERGE statement |
| `kind` | String | `ATTLOG` / `USERINFO` / `BIODATA` — for debugging |
| `device_sn` | String | which terminal it came from |
| `sql_index` | Number | position within one request |

n8n adds `id` and `createdAt` itself. `id` is what the ack deletes on — if your
version has no automatic `id`, add one and adjust **Delete Acked**.

## 2. Import and fix up the workflow

```bash
node build-workflow.mjs        # regenerates adms-listener.workflow.json
```

The three `dataTable` node shapes were **verified against a live export on
4 September 2026** and now match what actually works. They no longer need fixing
up by hand after import.

What that export corrected, for anyone who hits the same thing:

- `dataTableId` is a **resource locator object** (`__rl`, `mode`, `value`,
  `cachedResultName`), not a table name. Passing the bare string
  `'zk_pending_sql'` is what made the table dropdown render blank and
  unselectable — not project scoping, and not the OAuth proxy, both of which
  were guessed at while debugging it.
- The column mapper needs a `schema` array as well as the values, or the
  mapping panel comes up empty even when every expression is right.
- *Insert* has no `resource` and no `operation` key at all; it is the default.
- *Delete* is `operation: "deleteRows"` and its condition carries only
  `keyValue`.

**There is no sort option on the Get node.** Earlier versions of this file told
you to "sort by `id` ascending" in the UI, and `build-workflow.mjs` emitted
`options.orderBy`. Neither exists.

That matters more than it sounds. The parser emits an `EMPLOYEES` placeholder
before the `BIOMETRIC_TEMPLATES` row referencing that PIN, so replaying them out
of order writes a template for a person who does not exist yet. The re-sort in
`queue-pending-response.js` was described in that file as a belt-and-braces
check against a mis-set UI option. It is not — **it is the only thing ordering
these rows.** Do not remove it.

## 2b. Authentication: Header Auth, not the env var

`verify-secret.js` is no longer wired in. This instance cannot read `$env` from
a Code node — either the variable never reached the process or
`N8N_BLOCK_ENV_ACCESS_IN_NODE` is set — so the guard threw *"unset or too
short"* and refused every request, including legitimate ones.

Authentication is now the webhook node's built-in **Header Auth**, which is the
better mechanism regardless: it rejects at the edge before the workflow runs,
and the secret sits in an encrypted n8n credential rather than an environment
variable a Code node has to be trusted with.

Create one **Header Auth** credential — name `X-ZK-Secret`, value the shared
secret — and select it on **all five** webhook nodes:

`ADMS cdata` · `ADMS getrequest` · `ADMS devicecmd` · `Queue Pending` · `Queue Ack`

Miss one and that endpoint is publicly writable. `getrequest` and `devicecmd`
are the easy ones to skip, because they share a response node.

The credential is deliberately not referenced in the generated JSON: a
hardcoded credential id points at nothing in your instance and renders as an
error that reads worse than an empty dropdown. Selecting it is a manual step
after every import.

**Consequence for the device:** a rejected request is now `401`, not `500`.
`SyncWorker` treats 4xx as permanent, so a mismatched secret shows up as
**Failed** in Attendance Records rather than Pending — and **Resend failed** is
the recovery once the secret is fixed.

## 3. Dataiku scenario

Project → **Settings → Variables**:

```json
{ "zk_n8n_url": "https://automations.example.org",
  "zk_shared_secret": "PASTE_THE_SHARED_SECRET_HERE",
  "zk_connection": "Development" }
```

`zk_connection` is the **connection** name from Administration → Connections —
not the database. `Development` is correct for this project.

Do not leave angle brackets around a filled-in value. `<Development>` fails with
`Connection '<Development>' does not exist`, which reads like a missing
connection rather than a typo. Check with `repr()`, not `print()` — it shows
brackets and trailing whitespace that `print` hides:

```python
import dataiku
print(repr(dataiku.get_custom_variables().get("zk_connection")))
```

Project variables are readable by anyone with project access — weaker than n8n's
environment holding the same value. Worth knowing rather than discovering.

New scenario **"Drain ZK attendance queue"** → trigger every 5 minutes → one
**Execute Python** step containing `dataiku/drain_queue.py`.

## 4. Verify

```bash
curl -s -H "X-ZK-Secret: $SECRET" \
  "https://automations.example.org/webhook/zk/pending" | head -c 400
```

- `{"rows":[],"count":0}` — reachable, authenticated, queue empty.
- `401` — the Header Auth credential and the caller's secret differ.
- `404` — the workflow isn't **Active**. n8n only registers `/webhook/<path>`
  on activation; before that only the test URL exists, and only while you are
  watching for a test event.
- `500` — the workflow ran and threw. Open **Executions** and read the red
  node; the message is never in the HTTP response.
- Hangs — **Read Queue** lost `alwaysOutputData`; an empty result emits zero
  items, n8n skips everything downstream and the Respond node never fires.

Then punch on the device and run the scenario by hand before trusting the timer.

## The commit trap

`SQLExecutor2.query_to_df()` opens a connection per call, and Dataiku's SQL
connections are **not in autocommit**. DML passed as the main query is rolled
back when the connection closes.

On 4 Sep 2026 that gave a scenario that completed with exit 0, logged every
MERGE as executed, acked the queue — and left every table empty. Each statement
also stalled for exactly **307 seconds**, Snowflake's `LOCK_TIMEOUT`, queued
behind the lock the previous abandoned transaction still held.

**Successful statements + empty tables + a ~300s stall per statement = a missing
commit.** `drain_queue.py` now wraps each chunk in explicit `BEGIN`/`COMMIT`
passed through `pre_queries`. If you ever rewrite that call, keep the commit.

## 5. Roster pull (phase 2, partly built)

The other direction, so a person enrolled on one terminal is known to the rest.

**The design constraint that decides everything here:** n8n cannot reach
Snowflake. The obvious shape — device asks n8n, n8n runs a SELECT — is
impossible, and `SYNC-DESIGN.md` said to build exactly that until it was
corrected on 4 Sep 2026. The roster has to be pushed *out* of Dataiku into n8n,
and devices collect it from there.

```
                    ┌─ drain_queue.py   : Data table ──► Snowflake  (running)
Dataiku scenario ───┤
                    └─ publish_roster.py: Snowflake ──► Data table  (new step)

device ──GET /zk/roster?since=…──► n8n ──► reads zk_roster
```

### Data table `zk_roster`

| Column | Type | Holds |
|---|---|---|
| `key` | String | `emp:<pin>` or `dept:<name>` — the upsert key |
| `kind` | String | `employee` / `department` |
| `version` | Number | Snowflake `UPDATED_AT` as epoch millis |
| `payload` | String | the whole record as JSON |

The record is one JSON blob rather than columns on purpose. The roster shape
will change again — it just gained split names — and Data table columns are
edited by hand in the UI, so every new field would be a manual migration on a
live instance. Only `key` and `version` need to be real columns, because they
are the only two n8n itself filters on.

`version` comes from Snowflake, never from a device. Terminal clocks are set by
whoever is holding the terminal, and one that is a day fast would otherwise skip
every change made in the meantime.

### Importing it

```bash
node build-roster-workflow.mjs      # writes roster.workflow.json
```

A **separate workflow** from the ADMS listener, deliberately. If a roster bug
takes a workflow down, attendance ingest must not go with it — punches are the
thing that can never stop. Separate also means the roster can be deactivated or
edited mid-day without losing a punch.

1. Create the `zk_roster` Data table with the four columns above.
2. Import `roster.workflow.json`.
3. Open **Upsert Roster** and **Read Roster** and pick `zk_roster` in the table
   dropdown — the generated locator is deliberately empty because the table has
   no id until you create it.
4. On **Upsert Roster**, confirm the operation is *Upsert* and the matching
   column is `key`. **This is the one field in the file that was inferred rather
   than observed** — the live export contains insert, get and delete nodes but no
   upsert, so its shape is a pattern-match. If it is wrong the symptom is
   duplicate rows per publish, not lost data.
5. Activate. Header Auth is already attached to both webhooks by the generator.

Seven authenticated webhooks total across the two workflows. The credential id
is baked into both generators: a dangling id renders as a visible error, but an
absent one renders as a webhook that quietly accepts anything — and these two can
rewrite the roster of every terminal you own.

### Still outstanding

- **`INCLUDE_TEMPLATES = False`** in `publish_roster.py`, and it stays false
  until the portability test in `SYNC-DESIGN.md` §5 passes on two units.
  Deferred 4 Sep 2026 — only one C6 available. Names, departments, gender and
  deletions all sync without it; **fingerprints do not**, so staff enrol their
  fingers on each terminal they use.
- The device-side pull is built but **not installed**, because it 404s until the
  workflow above is active.

## Failure behaviour

- **Snowflake rejects a statement** — the scenario acks what landed, raises, and
  leaves the rest queued. It does not skip past the failure, because replaying
  out of order is worse than stopping.
- **Scenario crashes mid-batch** — un-acked rows replay next run. Every
  statement is a MERGE on a business key, so replay is a no-op.
- **n8n unreachable** — the device queues locally and retries; nothing is lost.
- **Dataiku down for a weekend** — rows accumulate in the Data table and drain
  500 at a time once it's back.
