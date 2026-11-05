# Step-by-step walkthrough

Written assuming you've never done any of this before. Work through the phases
in order — each one proves something works before the next depends on it. If a
phase fails, **stop there**. Moving on means debugging four things at once.

---

## What changed, and why

You gave four constraints that reshaped the design:

| Constraint | Consequence |
|---|---|
| Must run around the clock, no laptop | Everything lives on servers. Your laptop is only a test tool in Phase 1. |
| No always-on Linux machine | The nginx relay is gone. Replaced by a config request — see Phase 4. |
| Can't create a Snowflake user | Two routes to n8n auth, one needing an admin, one not. Phase 2b. |
| Snowflake + Dataiku both SSO, `DEVELOPMENT` database | Tables go in `DEVELOPMENT.ATTENDANCE`. No new Dataiku connection needed — yours already writes to Snowflake. |

**n8n is still the engine.** I briefly argued for moving to your laptop; that
was wrong once "around the clock" was on the table. n8n is the only piece you
have that's always on and reachable from the internet, which is exactly what a
device that pushes at 3am needs.

## The shape of it now

```
Horus H1  ──HTTPS──>  automations.example.org
(terminal)             (path rewrite: /iclock/* → /webhook/iclock/*)
                              │
                              ▼
                        n8n workflow          ← always on, no laptop
                              │
                              ▼
                  Snowflake DEVELOPMENT.ATTENDANCE
                              │
                              ▼
                    Dataiku web app dashboard
```

Nothing in that chain depends on a machine you own. A fingerprint at the
terminal becomes a row in Snowflake within seconds.

---

## Two unknowns that gate everything

Both are quick tests, and both should happen before you build anything.

**1. Can the terminal speak HTTPS?** `automations.example.org` is
HTTPS-only. Android-based ZK terminals usually manage it; older embedded ones
can't, and fail silently. Phase 1 answers this.

**2. Can n8n authenticate to Snowflake?** You can't create a user. Phase 2b has
two routes; one is a five-minute request to an admin, the other is a single SQL
statement you can try yourself right now.

If either comes back "no", tell me the exact error and we'll re-plan rather
than push on.

---

## Phase 0 — Access

- [ ] **Snowflake SSO login** with `CREATE TABLE` in `DEVELOPMENT` — confirmed,
      it's your sandbox.
- [ ] **n8n login** at `automations.example.org` that can create workflows
      and credentials.
- [ ] **Dataiku project** where you can create a web app, plus the name of the
      Snowflake connection your datasets already use
      (**Administration → Connections**). Not the database name.
- [ ] **Contact for whoever administers `automations.example.org`** — the
      proxy/DevOps owner. You need one small config change from them in
      Phase 4. Find out who that is now.
- [ ] **A Snowflake administrator's ear**, for Phase 2b Route A.
- [ ] **Physical access to the Horus H1.**

Phase 1 needs none of this.

---

## Phase 1 — Prove the terminal works (laptop, ~1 hour)

**Goal:** confirm the device pushes at all, see exactly what it sends, and find
out whether it can do HTTPS. Nothing is stored. This is a test rig, not part of
the final system.

This is the highest-value hour in the project. ZKTeco firmware varies and the
only way to know what *your* unit sends is to watch it.

### 1.1 Start the listener

PowerShell:

```powershell
cd C:\Users\dev\Documents\Cluade\zk-attendance
node tools/local_test_server.mjs
```

You should see `ADMS test server on http://0.0.0.0:8099`. Leave it running.

### 1.2 Check your own machine first

Second PowerShell window:

```powershell
cd C:\Users\dev\Documents\Cluade\zk-attendance
python tools/simulate_device.py --base http://127.0.0.1:8099
```

`6/6 steps succeeded` means the laptop side is fine, so any later failure is
the device or the network — not this.

### 1.3 Open the firewall

This is the **only** step in Phase 1 that needs administrator. Run PowerShell
as administrator (right-click → Run as administrator) and:

```powershell
New-NetFirewallRule -DisplayName "ZK ADMS test 8099" -Direction Inbound `
  -LocalPort 8099 -Protocol TCP -Action Allow -Profile Domain,Private
```

> **Then close that window.** Because this account is not a local admin,
> elevating starts the shell as a *different user* (`OAFAdmin`). Node and
> Python are per-user installs living in your own profile, so an elevated
> shell cannot see them — `node` reports "not recognized" exactly as if it
> were missing. If a prompt reads `C:\WINDOWS\system32>`, it is elevated;
> never run steps 1.1 or 1.2 there.

### 1.4 Point the terminal at your laptop

Confirm the laptop's current IP first — it is a DHCP address on Wi-Fi and
**changes**, so do not trust a value written down earlier:

```powershell
Get-NetIPAddress -AddressFamily IPv4 | Where-Object InterfaceAlias -eq 'Wi-Fi'
```

**Menu → Comm → Cloud Server Setting** (may be labelled *ADMS*):

| Setting | Value |
|---|---|
| Server Address | `10.40.96.115`  ← verify with the command above |
| Server Port | `8099` |
| Enable Proxy | Off |
| HTTPS / SSL | Off |

Save, then **Menu → System → Restart**.

### 1.5 Watch

Within a minute the listener window should print:

```
17:03:09  GET  /iclock/cdata?SN=CJXH2340&options=all&pushver=2.4.1
          route=handshake sn=CJXH2340 lines=0 accepted=0 skipped=0 sql=0
```

That's the device saying hello — **the milestone**. Now punch your finger and
watch for `route=ATTLOG`. Then enrol a new fingerprint and watch for
`route=BIODATA` or `route=OPERLOG`.

### 1.6 The HTTPS question

While the terminal is in your hands, find out whether it can do TLS at all.
Look through **Cloud Server Setting** for an `HTTPS`, `SSL` or `Use HTTPS`
toggle.

- **Toggle exists** → good sign. Note it and move on; you'll test it properly
  in Phase 5.
- **No toggle anywhere** → the device is almost certainly plain-HTTP only, and
  Option 3 in [`routing/README.md`](routing/README.md) becomes mandatory. Tell
  me now rather than in Phase 5.

While you're in the menu, also try typing `example.com/webhook` into Server
Address. If it accepts the slash, that's Option 1 in the routing doc and it
saves you the Phase 4 request entirely.

### If nothing appears in 1.5

In order — it's nearly always one of the first two:

1. **Same network?** Device IP under **Menu → Comm → Ethernet/WiFi** should
   start `10.143.69.` like your laptop. If not, they can't see each other.
2. **Corporate Wi-Fi blocks device-to-device traffic** ("client isolation").
   If both are on Wi-Fi, this is the likely cause. Plug the laptop into
   Ethernet, then re-check its IP with `Get-NetIPAddress` — it will have
   changed.
3. Firewall rule didn't apply — redo 1.3.
4. Typo in the server address — retype, reboot.

### Before leaving Phase 1

Save the listener output to a text file. You'll compare it against the parser
in Phase 3.6. Note the device's **serial number** (`SN=`).

---

## Phase 2 — Snowflake tables

1. Snowflake → **Worksheets → + Worksheet** (your normal SSO login).
2. Paste `sql/01_snowflake_schema.sql`, **Run All**. It targets `DEVELOPMENT`
   and creates the `ATTENDANCE` schema.
3. Paste `sql/02_dataiku_views.sql`, **Run All**. Change `'Africa/Lagos'` first
   if the terminal isn't in Nigeria.

**Check:**

```sql
SELECT COUNT(*) FROM DEVELOPMENT.ATTENDANCE.EMPLOYEES;
```

`0` is correct — the table exists and is empty.

---

## Phase 2b — Snowflake access for n8n

n8n needs a non-interactive way in. SSO can't provide one: it needs a browser
and a human, and there's neither at 3am. The n8n Snowflake credential supports
only **Password** and **Key Pair**.

Full detail in [`sql/00_snowflake_access.sql`](sql/00_snowflake_access.sql).
Three routes, in the order worth trying.

First, install the tester and confirm your basics are right:

```powershell
pip install "snowflake-connector-python[secure-local-storage]"
python tools/test_snowflake_auth.py --mode sso --account <acct> --user <you>
```

That opens your normal SSO browser login. If it passes, your account
identifier, role and warehouse are correct — reuse those exact values below. If
it fails, fix that before trying anything else.

### Route B — programmatic access token (try this first)

Snowflake's docs are explicit that a human user needs **no special privileges**
to create a token for themselves. No admin, no ticket.

```sql
SELECT CURRENT_USER(), CURRENT_ROLE();

ALTER USER "<your login>" ADD PROGRAMMATIC ACCESS TOKEN N8N_ZK_ATTENDANCE
    ROLE_RESTRICTION = '<your role>'
    DAYS_TO_EXPIRY   = 365
    COMMENT          = 'n8n ZKTeco attendance ingest';
```

The token prints **once**. No other command will ever show it again — copy it
into your password manager immediately.

`DAYS_TO_EXPIRY` defaults to 15. Don't leave it there: an attendance system
that stops collecting silently, on a date nobody has in a calendar, is worse
than one that never started. Set it long and diary the renewal today.

Then test it:

```powershell
python tools/test_snowflake_auth.py --mode pat --account <acct> --user <you>
```

**One prerequisite you can't self-serve:** a human user must be covered by a
network policy to *authenticate* with a token, even though creating one is
free. If your org already applies one, you're fine. If not, the test says so
explicitly and you'll need an admin for that bit — or Route A.

The tester also checks something n8n can't tell you: whether the token works as
a **plain password**. n8n's Snowflake credential has no authenticator field, so
a token that only works with `authenticator=PROGRAMMATIC_ACCESS_TOKEN` is one
n8n can't use. The script says which case you're in.

### Route A — service account (best long-term; needs an admin)

Send your Snowflake administrator the marked block from
`sql/00_snowflake_access.sql` plus your public key. A dedicated identity with
narrow grants, revocable without touching your account. Worth requesting even
if Route B gets you running today.

### Route C — key pair on your own login (probably closed)

**Correcting what I told you earlier:** I said most accounts let you attach an
RSA key to your own login. That was wrong. Snowflake permits self-modification
of only `DEFAULT_WAREHOUSE`, `DEFAULT_NAMESPACE`, `DEFAULT_ROLE` and session
parameter defaults — `RSA_PUBLIC_KEY` isn't on the list, so it needs OWNERSHIP
on your own user object. Unusual under SSO provisioning.

Twenty seconds to be sure:

```sql
ALTER USER "<your login>" SET COMMENT = 'privilege probe';
```

Succeeds → you have OWNERSHIP, Route C is open, clear the comment afterwards.
`Insufficient privileges to operate on user` → closed. Use B or A.

### Generating a key pair (Routes A and C only — B needs no key)

Git Bash, not PowerShell:

```bash
mkdir -p ~/zk-keys && cd ~/zk-keys
openssl genrsa 2048 | openssl pkcs8 -topk8 -inform PEM -out zk_attendance_key.p8
openssl rsa -in zk_attendance_key.p8 -pubout -out zk_attendance_key.pub
grep -v "^-----" zk_attendance_key.pub | tr -d '\n'
```

That last line prints the public key for Snowflake. **The two formats differ
and people mix them up constantly:**

- **Snowflake** wants the *public* key, one line, no `-----BEGIN-----` markers.
- **n8n** wants the *entire* `.p8` file, markers and line breaks included.

The `.p8` file is a credential — never commit, email, or share-drive it.

Verify before touching n8n:

```powershell
python tools/test_snowflake_auth.py --mode keypair --account <acct> `
    --user ZK_ATTENDANCE_SVC_USER --key ~/zk-keys/zk_attendance_key.p8
```

---

## Phase 3 — The n8n workflow

### 3.1 Snowflake credential

**Credentials → Add credential → Snowflake**. Which authentication you pick
depends on which route worked in Phase 2b:

| | Route B (token) | Routes A / C (key pair) |
|---|---|---|
| Authentication | **Password** | **Key Pair** |
| Username | your own login | the service user, or your login |
| Password | the token from 2b | — |
| Private Key | — | the **whole** `.p8` file, BEGIN/END lines included |
| Passphrase | — | the one you chose |

Common to both:

| Field | Value |
|---|---|
| Account | your account identifier, e.g. `abc12345.eu-west-1` |
| Database | `DEVELOPMENT` |
| Schema | `ATTENDANCE` |
| Warehouse | `ZK_ATTENDANCE_WH` (Route A) or your usual one |
| Role | `ZK_ATTENDANCE_SVC` (Route A) or your usual role |

Use the exact account/role/warehouse values that passed
`test_snowflake_auth.py --mode sso`.

Name it `Snowflake - DEVELOPMENT`. Hit **Test** — don't continue until green.

**If Test fails:**
- *"JWT token is invalid"* — wrong account identifier, or the public key in
  Snowflake doesn't match this private key. Re-run `DESC USER` and compare.
- *"Cannot read private key"* — you pasted the stripped one-line version.
- *"Role not granted"* — the grants didn't run.

### 3.2 Import

**Workflows → ⋯ → Import from File** → `n8n/adms-listener.workflow.json`.

### 3.3 Wire it up

1. Open **Snowflake Write**, pick your credential from the empty dropdown.
   Confirm **Operation** is *Execute SQL Query* and the **Query** field is in
   **Expression** mode showing `{{ $json.sql }}`.
2. On each of the three Webhook nodes (**ADMS cdata**, **ADMS getrequest**,
   **ADMS devicecmd**), set **Options → IP(s) Allowlist** to your office's
   public egress IP. Get it by running `curl ifconfig.me` on any machine on
   the office network.

> **Why the allowlist is not optional.** `automations.example.org/webhook/*`
> is reachable from the open internet with no login — I verified this. Once
> this workflow is active, anyone who guesses the URL can inject attendance
> records or pull fingerprint templates. Without a relay there's no shared
> secret to fall back on, so the IP allowlist is the only control you have.

### 3.4 Activate

Toggle **Active**, top right.

### 3.5 Test

```powershell
python tools/simulate_device.py --base https://automations.example.org/webhook
```

Then in Snowflake:

```sql
SELECT * FROM DEVELOPMENT.ATTENDANCE.ATTENDANCE_LOGS ORDER BY RECEIVED_AT DESC LIMIT 10;
SELECT * FROM DEVELOPMENT.ATTENDANCE.RAW_PUSH_LOG   ORDER BY RECEIVED_AT DESC LIMIT 10;
```

Rows in both = the middle of the pipeline works.

If the allowlist is already set and you're testing from off-network, this will
be refused — expected. Test from the office, or add your address temporarily.

**If the workflow errors:** n8n → **Executions** → click the red one → click
the failed node. Snowflake errors are usually literal and usually a typo.

### 3.6 Compare against reality

Put your Phase 1 capture next to `SELECT RAW_BODY FROM RAW_PUSH_LOG`. Do the
field names match what `n8n/parse-adms-request.js` expects? Climbing `skipped=`
counts mean they don't. Send me the raw text and I'll adjust the parser.

---

## Phase 4 — Routing

**Goal:** let the terminal reach n8n. It can't by itself — it sends to
`/iclock/cdata` and n8n listens on `/webhook/iclock/cdata`.

Full detail in [`routing/README.md`](routing/README.md). The order:

1. **If Phase 1.6 showed the device accepts a path** — you're already done. Set
   Server Address to `automations.example.org/webhook`, port `443`, HTTPS
   on. Skip to Phase 5.
2. **Otherwise, ask the proxy administrator** for one rewrite rule:
   `/iclock/*` → `/webhook/iclock/*`. The snippet is in
   [`routing/iclock-rewrite.conf`](routing/iclock-rewrite.conf).

   The argument that makes this an easy yes: `/webhook/*` is *already* exempt
   from the OAuth redirect on that host — it returns `404`, not the `303` that
   `/healthz` and `/api/v1/*` return. The bypass rule exists; this extends it
   to one more prefix.
3. **If both fail** — a cheap cloud VM or a Raspberry Pi running the same
   config. Option 3 in the routing doc. Not your laptop: it sleeps, moves, and
   changes IP.

---

## Phase 5 — Point the terminal at production

**Menu → Comm → Cloud Server Setting:**

| Setting | Value |
|---|---|
| Server Address | `automations.example.org` (or with `/webhook` if Option 1) |
| Server Port | `443` |
| HTTPS / SSL | **On** |

Reboot the terminal. Watch n8n → **Executions**. Have someone punch in, then
check Snowflake.

**If nothing arrives and Phase 3.5 worked**, it's the device's TLS. That's the
HTTPS risk from 1.6 landing — go to Option 3 in the routing doc, which puts a
middlebox in front that terminates plain HTTP on the device side.

---

## Phase 6 — The dashboard

### 6.1 Build

1. **+ New → Web App → Standard (HTML/CSS/JS)**, name it *Staff Attendance*.
2. **Settings → Python backend → Enabled**, **Save**.
3. Paste each file into its matching tab: `index.html` → HTML, `app.js` → JS,
   `styles.css` → CSS, `backend.py` → Python.
4. **Settings → Configuration**:
   ```json
   {
     "snowflake_connection": "<your connection name>",
     "schema": "DEVELOPMENT.ATTENDANCE",
     "device_timezone": "Africa/Lagos",
     "cache_ttl_seconds": 10
   }
   ```
   Use the connection name from **Administration → Connections** — the one your
   Dataiku datasets already write through.
5. **Start backend**, wait for green, **View web app**.

**Red error bar** → it's printing the real Snowflake error, usually the
connection name. **Unstyled page** → CSS went in the wrong tab.

### 6.2 Test it as someone else

Ask a colleague to open the URL while you watch. If it works for you and fails
for them, your Snowflake connection uses **per-user OAuth credentials** — a web
app backend is one shared process and can't borrow a viewer's SSO session. Ask
your Dataiku admin for a connection using global credentials or a key pair,
restricted to this project.

This only shows up when a second person looks, so don't skip it.

---

## Phase 7 — Lock down

Once real data is flowing:

- [ ] Set `CAPTURE_RAW = false` at the top of `n8n/parse-adms-request.js`, run
      `node n8n/build-workflow.mjs`, re-import. Halves the write volume.
- [ ] Confirm the IP allowlist is set on all three Webhook nodes.
- [ ] Restrict the fingerprint table — biometric data is legally sensitive:
      ```sql
      REVOKE SELECT ON DEVELOPMENT.ATTENDANCE.BIOMETRIC_TEMPLATES
          FROM ROLE <your general analyst role>;
      ```
- [ ] If on Route B, open the Route A request so this stops depending on your
      personal login.

---

## When something breaks

Work left to right, stop at the first thing that's wrong:

| Symptom | Look at |
|---|---|
| Dashboard shows nothing | `/api/health` on the web app |
| Dashboard empty, health OK | `SELECT COUNT(*) FROM ATTENDANCE_LOGS` |
| No new rows in Snowflake | n8n → Executions, look for red |
| n8n has no executions | The device isn't reaching it — Phase 4/5 |
| Worked, then stopped | Did the office public IP change? Check the allowlist |

The single most useful diagnostic anywhere in the chain:

```powershell
python tools/simulate_device.py --base <the thing you suspect>
```

It tells you whether the problem is upstream or downstream of that point.
