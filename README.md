# Staff Attendance — ZKTeco Horus H1 → n8n → Snowflake → Dataiku

An unattended IoT-to-warehouse data pipeline: a biometric fingerprint terminal
pushes punches over the ADMS protocol to a webhook, which lands them in a cloud
warehouse through idempotent writes, feeding a read-only dashboard. No laptop
or on-prem server is in the path at any point — everything between the device
and the warehouse has to keep working through reboots, retries and a 3am punch
with nobody watching. This README is written as the setup/runbook it started
as, including the open items that were still outstanding when the project was
paused; the "Notes on the design" and "Open blockers" sections below are the
most interesting part for anyone evaluating the engineering.

## Architecture as built

```
┌──────────────┐   HTTPS
│  Horus H1    │──────────────────┐
│  terminal    │                  ▼
└──────────────┘         ┌──────────────────────────────┐
                         │ automations.example.org  │
                         │ edge proxy rewrites          │
                         │ /iclock/* → /webhook/iclock/*│
                         └────────┬─────────────────────┘
                                  ▼
                         ┌──────────────────┐
                         │  n8n workflow    │  parse → MERGE
                         │  (always on)     │
                         └────────┬─────────┘
                                  │ Snowflake node, key-pair auth
                                  ▼
                         ┌──────────────────┐
                         │   Snowflake      │  EMPLOYEES / BIOMETRIC_TEMPLATES
                         │  DEVELOPMENT.    │  ATTENDANCE_LOGS / RAW_PUSH_LOG
                         │   ATTENDANCE     │
                         └────────┬─────────┘
                                  │ SQLExecutor2 (read-only)
                                  ▼
                         ┌──────────────────┐
                         │ Dataiku web app  │  dashboard
                         └──────────────────┘
```

Data flows one way. Nothing in the chain depends on a workstation — the design
has to survive a 3am punch on a Saturday. The Dataiku backend never writes and
never talks to the device; it cannot, and the reasons are in the notes below.

The LAN relay that earlier versions of this design used is gone, replaced by a
one-line rewrite rule on the proxy that already fronts n8n. See
[`routing/README.md`](routing/README.md) for that and its two fallbacks.

## File layout

```
zk-attendance/
├── SETUP-WALKTHROUGH.md            start here if this is unfamiliar ground
├── sql/
│   ├── 00_snowflake_access.sql     two routes to non-interactive Snowflake auth
│   ├── 01_snowflake_schema.sql     tables — run once
│   └── 02_dataiku_views.sql        reporting views the dashboard reads
├── n8n/
│   ├── parse-adms-request.js       the parser (editable source of truth)
│   ├── build-workflow.mjs          regenerates the workflow JSON from it
│   ├── adms-listener.workflow.json import this into n8n
│   ├── test-parser.mjs             28 offline tests — run before deploying
│   ├── roster-publish-upsert.js    multi-device roster sync — publish side
│   ├── roster-response.js          multi-device roster sync — device read side
│   ├── build-roster-workflow.mjs   regenerates roster.workflow.json
│   ├── roster.workflow.json        import this into n8n
│   └── README-queue.md             the n8n → Dataiku → Snowflake queue drain
├── routing/
│   ├── README.md                   three ways to get the device to n8n
│   └── iclock-rewrite.conf         the rewrite rule / standalone middlebox
├── dataiku-webapp/
│   ├── backend.py                  → Dataiku web app "Python" tab
│   ├── index.html                  → "HTML" tab
│   ├── app.js                      → "JS" tab
│   └── styles.css                  → "CSS" tab
└── tools/
    ├── simulate_device.py          fake terminal, no hardware needed
    ├── local_test_server.mjs       run the parser standalone, print SQL
    └── test_snowflake_auth.py      test a credential outside n8n
```

## Current status

| Component | State |
|---|---|
| Parser | Tested, 28/28 passing |
| End-to-end ADMS contract | Verified against the simulator |
| Snowflake DDL + MERGEs | Written, **not yet run against a warehouse** |
| n8n workflow JSON | Generated, **not yet imported** (see blocker below) |
| Dataiku web app | Written, **not yet deployed** |

---

## Setup

### 1. Snowflake

Run in a worksheet under your own (SSO) login, after editing the database name
at the top of each file:

```
sql/01_snowflake_schema.sql     tables
sql/02_dataiku_views.sql        reporting views
```

Then give n8n a non-interactive way in. SSO can't serve that — it needs a
browser and a human, and there's neither when a night-shift worker clocks in.
`sql/00_snowflake_access.sql` lays out both routes:

- **Route B — programmatic access token.** Try first. Snowflake requires no
  special privilege for a human user to mint one for themselves, and it works
  in n8n's Password field. One prerequisite you can't self-serve: a human user
  must be covered by a network policy to authenticate with a token.
- **Route A — service account.** A Snowflake admin creates a `TYPE = SERVICE`
  user with RSA key-pair auth, scoped to this schema with no `DELETE` and no
  `DROP`, plus an XSMALL `ZK_ATTENDANCE_WH` on a 60-second auto-suspend. Best
  long-term; worth requesting even if Route B unblocks you today.
- **Route C — key pair on your own login.** Usually closed: `RSA_PUBLIC_KEY`
  is not among the properties Snowflake lets a user self-modify, so it needs
  OWNERSHIP on your own user object.

Verify any of them before touching n8n:

```bash
pip install "snowflake-connector-python[secure-local-storage]"
python tools/test_snowflake_auth.py --mode sso     --account <acct> --user <you>
python tools/test_snowflake_auth.py --mode pat     --account <acct> --user <you>
python tools/test_snowflake_auth.py --mode keypair --account <acct> --user <svc> --key <path>
```

That isolates the credential from n8n — a failure there is the credential, a
pass there plus a failure in n8n is n8n. For tokens it also reports whether the
token authenticates as a **plain password**, which decides whether n8n can use
it at all: the n8n credential has no authenticator field.

Lock down the biometric table. It is special-category personal data:

```sql
REVOKE SELECT ON DEVELOPMENT.ATTENDANCE.BIOMETRIC_TEMPLATES FROM ROLE <general analyst role>;
GRANT  SELECT ON DEVELOPMENT.ATTENDANCE.BIOMETRIC_TEMPLATES TO   ROLE HR_BIOMETRIC_ADMIN;
```

### 2. n8n

1. Snowflake credential: **Credentials → New → Snowflake**, with
   **Authentication → Key Pair** and whichever identity Route A or B in
   `sql/00_snowflake_access.sql` produced. Name it `Snowflake - DEVELOPMENT`.
   Password auth would also work, but interactive SSO will not — the n8n
   Snowflake credential offers only Password and Key Pair, and an unattended
   workflow has no browser to complete an SSO flow in.
2. **Workflows → Import from File** → `n8n/adms-listener.workflow.json`.
3. Open the **Snowflake Write** node, select the credential, and confirm the
   **Query** field is in *Expression* mode showing `{{ $json.sql }}`.
   The node is pinned to `typeVersion: 1` on purpose — asking for `1.1` on an
   n8n that ships `1` makes the editor report the node as *not installed*.
4. On each of the three Webhook nodes fill in **Options → IP(s) Allowlist**
   with your office's public egress IP (`curl ifconfig.me` from that network).
   Without a middlebox there is no shared secret the device can carry, so this
   is the only access control on a publicly-reachable endpoint.
   If you end up on routing Option 3, add **Header Auth** as well — a middlebox
   *can* attach a secret.
5. Activate the workflow. Copy the production webhook URLs.

To change the parser afterwards, edit `n8n/parse-adms-request.js`, then:

```bash
node n8n/test-parser.mjs && node n8n/build-workflow.mjs
```

and re-import. Do not edit the JSON by hand.

### 3. Routing

The device sends to `/iclock/cdata`; n8n listens on `/webhook/iclock/cdata`.
Close that gap with the cheapest option that works — full detail and the
argument to give your proxy administrator are in
[`routing/README.md`](routing/README.md):

1. The device accepts a path in its server address (test it — costs 2 minutes).
2. One rewrite rule on the proxy already fronting n8n.
3. A cheap cloud VM or Pi running `routing/iclock-rewrite.conf`.

### 4. Device

On the Horus H1: **Menu → Comm → Cloud Server Setting** (labelled *ADMS* on
some firmware).

| Setting | Value |
|---|---|
| Server Mode | ADMS / Domain-name off |
| Server Address | `automations.example.org` (+ `/webhook` on option 1) |
| Server Port | `443` |
| Enable Proxy | Off |
| HTTPS | **On** |

Then **Comm → Ethernet/WiFi** to confirm it has an address, and reboot the
terminal. It should hit `/iclock/cdata` within a minute.

### 5. Verify before trusting it

```bash
# parser alone, no n8n, no Snowflake
node tools/local_test_server.mjs
python tools/simulate_device.py --base http://127.0.0.1:8099

# the full path into n8n and Snowflake (run from the office network,
# or the IP allowlist will refuse you)
python tools/simulate_device.py --base https://automations.example.org/webhook --punches 20
```

Then in Snowflake:

```sql
SELECT * FROM DEVELOPMENT.ATTENDANCE.RAW_PUSH_LOG ORDER BY RECEIVED_AT DESC LIMIT 20;
```

`RAW_PUSH_LOG` is the point of the whole commissioning exercise — it shows what
your firmware actually sends. Once the real terminal has pushed users,
fingerprints and punches, read those rows and confirm the field names match
what the parser expects. Then set `CAPTURE_RAW = false` at the top of
`parse-adms-request.js` and redeploy.

### 6. Dataiku web app

1. **New Web App → Standard (HTML/CSS/JS)**, name it *Staff Attendance*.
2. **Settings → Python backend → Enabled**.
3. Paste each file into its matching tab.
4. **Settings → Configuration**:
   ```json
   {
     "snowflake_connection": "<your connection name>",
     "schema": "DEVELOPMENT.ATTENDANCE",
     "device_timezone": "Africa/Lagos",
     "cache_ttl_seconds": 10
   }
   ```
   `snowflake_connection` must match the connection name in
   **Administration → Connections** — the one your Dataiku datasets already
   write through — not the database name.

   Check that connection's authentication mode before you build. If it uses
   **OAuth with per-user credentials**, this dashboard will work for you and
   fail for everyone else you share it with: a web app backend is one shared
   process and cannot borrow a viewer's SSO session. Have it pointed at a
   global-credential or key-pair connection instead, restricted to this project.
   Test by asking a colleague to open the page — that's the only way this
   surfaces.
5. Start the backend, then open `/api/health` — it returns the connection name
   on success and the Snowflake error on failure.

---

## Multi-device roster sync

Attendance push is one-way (device → Snowflake); roster sync is the other half —
letting staff enrolled on one terminal clock in on any other, so a name,
department, or fingerprint edit on one device reaches the rest without a
person re-enrolling anywhere.

```
Dataiku (publish_roster.py) ──POST /webhook/zk/roster-publish──► n8n ──► Data table `zk_roster`
                                                                               ▲
                                              device ──GET /webhook/zk/roster──┘  "what changed since <version>?"
```

Deliberately asymmetric: **n8n never touches Snowflake for this.** Dataiku
reads the roster and publishes it; n8n only stores and re-serves the latest
version through a Data table, and devices poll it the same way the ADMS
protocol already has them poll for commands. Each record is stored as one
JSON blob rather than typed columns — the roster shape has already changed
once (it gained split first/last names), and a Data table's columns are a
manual, by-hand migration on a live instance; a device parsing a JSON blob is
not. `version` is a Snowflake-assigned timestamp, not a device clock —
deliberately, since two terminals' clocks can't be trusted to agree on which
edit happened last. See [`SYNC-DESIGN.md`](SYNC-DESIGN.md) for the full design,
including the open question on whether fingerprint templates are portable
between terminals at all (untested, and the rollout plan gates on it).

## Notes on the design

**The handshake is not a ping.** `GET /iclock/cdata` is the device asking what
to send. Returning `OK` — as the original spec had it — tells it "nothing", and
it goes silent forever while looking perfectly healthy. It must return the
`GET OPTION FROM:` block; `TransFlag` is what enables user/fingerprint/punch
push and `Realtime=1` is what makes punches arrive in seconds.
`simulate_device.py` fails loudly on this specific mistake.

**Why routing needs a config change.** The device has `/iclock/cdata` hardcoded
and offers no setting to add a prefix; n8n serves webhooks under `/webhook/`.
Something in front of n8n has to rewrite the path. The tempting shortcut —
setting `N8N_ENDPOINT_WEBHOOK=iclock` so n8n serves `/iclock/*` directly —
would change the URL of **every other workflow on
`automations.example.org`**. Don't.

Earlier drafts solved this with an nginx relay on the LAN, which also gave a
place to terminate plain HTTP and attach a shared secret. With no always-on
machine available that's gone, and two things follow: the terminal must be able
to speak HTTPS itself (verify early — some ZK units can't, and fail silently),
and there is no secret to attach, so the n8n IP allowlist becomes the only
access control on a publicly-reachable endpoint.

**Why n8n rather than a small local service.** With "around the clock, no
laptop" as a hard requirement, n8n is the only component available that is both
always on and reachable from the internet. A workstation-hosted listener is
simpler to build and cannot meet the requirement — it sleeps, it moves, and its
IP changes.

**Snowflake enforces no constraints.** `PRIMARY KEY` and `UNIQUE` are metadata
only; they will not stop a duplicate. Since ADMS devices re-send any batch they
didn't see a clean `OK` for, every write is a `MERGE` on a deterministic key
(`DEVICE_SN|PIN|TIMESTAMP` for punches). Duplicates are also collapsed *inside*
each batch before the SQL is built, because a `MERGE` whose source has two rows
with the same key fails outright.

**One statement per push, not per punch.** Snowflake bills warehouse time per
statement and handles single-row DML badly. A 60-punch batch produces one
multi-row `MERGE`.

**The device has no department field.** ZK `USERINFO` simply doesn't carry one,
so the parser never writes `DEPARTMENT` and the column stays NULL until you
join HR master data to it. Expecting the terminal to supply it will waste a day.

**`PUNCH_TYPE` is not a direction.** The device's State field reads `0` for both
in and out on most Horus units. The dashboard derives "first in" from the
ordering of the day's punches instead.

**No late/absent figures.** You said there are no lateness rules, so none are
invented. The fourth card says "Not Seen Today", not "Absent" — a terminal that
lost its network for an hour produces exactly the same signal as someone who
stayed home, and labelling that "Absent" on a screen managers act on is a
payroll dispute waiting to happen. If shift rules get agreed, add them to
`V_DAILY_PRESENCE`, not to the web app.

**Custom CSS, not the Tailwind CDN.** Dataiku instances are commonly deployed
without outbound internet, and a CDN `<script>` that silently fails leaves an
unstyled page with no error to diagnose. Everything is self-contained.

**Two injection surfaces, both handled.** `SQLExecutor2` has no bind-parameter
API and the n8n Snowflake node's `queryReplacement` splits on commas (which
breaks on a name like "Smith, John"), so both paths build escaped literals
instead. Snowflake honours backslash escapes inside string literals, so the
escaper doubles backslashes as well as quotes — escaping only the quote leaves
a trailing-backslash injection open. Employee names also reach the browser, and
they come from free text typed at the terminal, so the frontend escapes them
rather than interpolating HTML.

---

## Open blockers

1. **Workflow import must be done by hand.** `automations.example.org` sits
   behind an OAuth proxy, and the n8n management API is inside it — both
   `/healthz` and `/api/v1/*` answer `303 → /oauth/authorize`, so the workflow
   could not be deployed programmatically.

   The important half of this was checked and came back fine:

   | Path | Result |
   |---|---|
   | `/webhook/iclock/cdata?SN=TEST` | **404** — proxy passes it through, n8n has no such route *yet* |
   | `/healthz` | 303 → OAuth |
   | `/api/v1/workflows` | 303 → OAuth |

   A `404` rather than a `303` on the webhook path means the proxy has a bypass
   for `/webhook/*`. The architecture works — the device's push will reach n8n.

   The flip side: that endpoint is reachable **unauthenticated from the public
   internet**. Once the workflow is active, anyone who guesses the URL can
   inject attendance records or harvest fingerprint templates. With no
   middlebox there is no shared secret to attach, so the IP allowlist in step 2
   is the only thing standing in front of it.

2. **Can the Horus H1 speak HTTPS?** Unverified, and it gates the whole
   routing design — both of the cheap options require it, because
   `automations.example.org` is HTTPS-only. Android-based ZK terminals
   usually can; older embedded ones can't and fail silently. Phase 1 of the
   walkthrough tests the device over plain HTTP first precisely so this can be
   isolated. If it turns out to be no, routing Option 3 becomes mandatory.

3. **Can n8n authenticate to Snowflake?** You can't create a user, so this
   depends on either a Snowflake admin running Route A, or your account
   permitting Route B (`ALTER USER <self> SET RSA_PUBLIC_KEY`). Both are
   untested. If both are refused, the design needs re-planning, not patching.

4. **Snowflake DDL and MERGEs have not been executed against a real warehouse.**
   The SQL is reviewed and the generated statements are shape-correct, but
   nothing here has round-tripped through Snowflake yet.

5. **Device timezone is assumed to be `Africa/Lagos` (UTC+1).** It appears in
   three places — `DEVICE_TZ_OFFSET` in the parser, `DEVICE_TODAY()` in the
   views, and `device_timezone` in the web app config. Change all three
   together if the terminal is not in Nigeria.
