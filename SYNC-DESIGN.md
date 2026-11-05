# Multi-device roster sync — design

**Status:** design only. Nothing here is built.
**Written:** 3 September 2026

Goal: a member of staff enrolled on one terminal can clock in on any other, and
an edit or deletion on one device reaches the rest.

---

## 1. What already exists

Push works in one direction and is already correct for attendance:

```
device ──POST /iclock/cdata?SN=..&table=ATTLOG|USERINFO|BIODATA──► n8n ──► Snowflake
```

`USERINFO` carries PIN, name, department and gender. `BIODATA` carries one
template per finger, with `FID` = finger index. Both are already sent by the app
and already parsed. **The push half of roster sync is done.**

What does not exist is the other direction. There is no endpoint a device can
call to ask "what has changed since I last looked?", and no code on the device
to apply the answer.

## 2. The hard problems, in order of how much they will hurt

### 2.1 Deletion needs tombstones — decide this before writing code

Right now `StaffListActivity` deletes locally. With sync, the next pull sees a
row on the server that the device lacks and **restores the person you just
deleted**. Deletion silently stops working.

A delete must therefore be a *write*, not an absence: `EMPLOYEES` needs a
`DELETED_AT TIMESTAMP_NTZ` (or a `STATUS` flag), the device must push it, and
pull must apply it by removing the local rows, the templates, and the matcher
entries.

This is cheap now and expensive later: `01_snowflake_schema.sql` has never been
run, so the column is free to add today. After go-live it is a migration plus a
backfill of unknown rows.

### 2.2 Last-write-wins needs a clock nobody trusts

`Employee.updatedAt` already exists and is set on every local edit. Two devices
editing the same person resolves by newest timestamp — but these are **device
clocks**, and the C6's clock is set by whoever holds it.

Options, in increasing order of effort:

- **Accept it.** Roster edits are rare and low-stakes; a wrong name for a day is
  survivable. Simplest, and probably right.
- **Server-assigned timestamps.** Snowflake stamps `UPDATED_AT` on arrival and
  the device stores what it is told. Removes device clocks from the decision
  entirely. Moderate work, clearly more correct.
- **Per-field merge.** Overkill for four fields.

Note this affects attendance too, but far less: punches carry the device clock
already and `logId` makes them idempotent, so a skewed clock produces a punch at
the wrong time rather than a lost or doubled one.

### 2.3 Templates are the sensitive part

Fingerprint templates are special-category personal data. Cross-device sync
means they leave the device, sit in Snowflake, and come back down to every other
terminal. Minimum bar before this is switched on:

- `X-ZK-Secret` enforced on **every** route — see `n8n/verify-secret.js`.
- HTTPS only. The app already refuses non-`https://` URLs.
- `REVOKE SELECT ON …BIOMETRIC_TEMPLATES FROM ROLE <analyst role>` — outstanding
  from HANDOFF §9.
- A stolen terminal is now a roster-wide exposure, not a one-device one. Worth
  deciding whether templates should be encrypted at rest on the device.

Assumption to verify on hardware, not to trust: **templates are portable between
C6 units.** Same sensor, same SDK, same algorithm version, so they should be —
but nothing has tested it. If they are not portable, the whole feature collapses
to "re-enrol per device" and it is better to discover that with two devices and
one finger than after a rollout.

### 2.4 Push before pull, always

A device with unsent local enrolments that pulls first will apply server state
over its own pending writes. The sync worker must complete its push phase and
only then pull. `SyncWorker` already pushes roster, then templates, then
punches; pull becomes a fourth phase at the end.

## 3. Proposed shape

> **Corrected 4 September 2026.** The original §3.2 below had n8n running a
> `SELECT` against Snowflake. **n8n cannot reach Snowflake at all** — that is
> the entire reason `zk_pending_sql` and the Dataiku drain exist, and it was
> settled in `snowflake-sso-service-account`. Any design where the device pulls
> *through* n8n straight from the warehouse is dead on arrival. The pipeline is
> write-only in that direction, so the roster has to be **pushed out of Dataiku
> into n8n**, and the device pulls from what Dataiku left there.

### 3.1 New endpoint

```
GET /webhook/zk/roster?since=<version>&sn=<device_sn>
X-ZK-Secret: <secret>

200 {
  "version": 1756900000000,
  "employees": [
    { "pin":"171917", "firstName":"…", "lastName":"…",
      "department":"…", "gender":"…",
      "version":1756899000000, "deleted":false,
      "fingers":[ { "fingerIndex":1, "template":"<base64>" } ] }
  ]
}
```

`since` makes it incremental — a full roster with four templates per person is
roughly 8 KB of base64 each, so a hundred staff is close to a megabyte. On a
field hotspot that is not something to re-download every fifteen minutes.

`version` is `EMPLOYEES.UPDATED_AT` as epoch millis, assigned by **Snowflake**,
not by any device. The device stores the highest it has seen and sends it back
as the next `since`. That keeps device clocks out of the decision entirely,
which §2.2 wanted anyway and which matters more here: a terminal whose clock is
a day fast would otherwise skip every change made in the meantime.

### 3.2 Getting the roster into n8n

Dataiku already runs every few minutes and already holds the only working
Snowflake credential. It gains a second scenario step that publishes:

```
                    ┌─ drain_queue.py   : n8n Data table ──► Snowflake   (exists)
Dataiku scenario ───┤
                    └─ publish_roster.py: Snowflake ──► n8n Data table   (new)

device ──GET /zk/roster?since=…──► n8n ──► reads that table
```

`publish_roster.py` selects rows where `UPDATED_AT > <last published>`, including
tombstoned ones so deletions propagate, and POSTs them to
`/webhook/zk/roster-publish`, which upserts into a Data table `zk_roster` keyed
on PIN.

Two consequences worth stating plainly:

- **A device never talks to Snowflake, and Snowflake never talks to a device.**
  Everything crosses at n8n. That is a security property worth keeping.
- **The roster a device pulls is as fresh as the last Dataiku run**, not live.
  With a 5-minute trigger, an enrolment on terminal A can take up to about ten
  minutes to be usable on terminal B: one cycle to drain A's push into
  Snowflake, one to publish it back out. That is fine for a roster and would not
  be fine for attendance, which is why punches stay push-only (§3.4).

### 3.2b Templates are a separable phase

Publish employees, departments and tombstones first, **without** templates. That
half is independently useful — names resolve on every terminal, deletions
propagate, the department list converges, and SF ID collisions become
detectable — and none of it depends on the untested assumption in §2.3.

Templates are the part that fails if a template enrolled on one C6 does not
match on another. Keeping them a separate flag on the publisher means the test
in §4 can invalidate that one piece without wasting the rest.

### 3.3 Device side

- `RosterSyncWorker`, or a fourth phase in `SyncWorker`, on the existing
  WorkManager schedule with a network constraint.
- Apply inside one Room transaction: upsert employees, replace that person's
  fingerprints, then `Biometrics.reloadTemplates()` **once** at the end.
- Skip rows where the local `updatedAt` is newer and `pendingUpload` is set —
  our own edit has not been pushed yet and must not be overwritten.
- Apply `deletedAt` by doing exactly what `StaffListActivity.delete()` does,
  including unregistering from the matcher.

### 3.4 Where it must not touch attendance

Punches stay push-only. Pulling attendance back down would serve no purpose on a
terminal and would put every site's movements on every device.

## 4. Sequence

1. **`verify-secret.js` into the workflow, secret set on both sides.** Nothing
   else starts until the endpoint rejects unauthenticated requests.
2. **Prove push works at all.** Set the shared secret, watch the queued punches
   drain to `Sent`. Nothing has ever left this device; that has to work before
   two-way sync is meaningful.
3. **Add `DELETED_AT`** to the schema while it is still free.
4. **Test template portability** across two C6 units — see §5. This gates the
   template half of the pull, and nothing else.
5. Build the roster publish and pull **without templates**, which is useful on
   its own and is not gated on step 4.
6. Add templates to the publisher, only if step 4 passed.

Steps 1–3 are each small, and each one can invalidate the step after it.

## 5. The portability test

Twenty minutes, two C6 units, one finger. Do it before step 6, not after.

**Why it cannot be skipped or reasoned around.** Same sensor, same SDK, same
algorithm version, so templates *should* be portable — but ZKFinger templates
can encode sensor-specific calibration, and nothing here has tested it. The
failure mode if it does not hold is quiet and awful: templates sync, the matcher
accepts them without complaint, and staff enrolled on another terminal simply
are not recognised. That reads as a broken sensor, not a design fault, and it
would be found by field staff rather than by you.

**Procedure.**

1. Enrol one person on device A. Confirm they can clock in on A.
2. Let the pipeline carry them to Snowflake, then check the template arrived:
   ```sql
   SELECT EMPLOYEE_PIN, FINGER_INDEX, LENGTH(TEMPLATE_DATA) AS B64_LEN, DEVICE_SN
   FROM DEVELOPMENT.ATTENDANCE.BIOMETRIC_TEMPLATES
   WHERE EMPLOYEE_PIN = '<their SF ID>';
   ```
   Four rows, each a couple of thousand characters.
3. On device B — which must **not** have that person enrolled — get the same
   template into the matcher. Until the pull exists, the honest way to do this
   is a temporary debug action that reads the base64 from Snowflake and calls
   `FingerprintEngine.register`.
4. Have the same person present the same finger on B.

**Pass:** B recognises them, with a match score in the same range A produces.
**Fail, and stop:** B does not recognise them, or the score sits near the
threshold. A borderline score is a failure, not a pass — it means the template
is being partially understood, and that degrades into false rejections in the
field where fingers are dusty and wet.

If it fails, templates stay per-device: staff enrol once per terminal they use,
and the roster pull still carries names, departments and deletions.
