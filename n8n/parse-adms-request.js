// ═══════════════════════════════════════════════════════════════════════════
//  ZK ADMS push parser  ->  Snowflake MERGE statements
//  n8n Code node · Language: JavaScript · Mode: "Run Once for All Items"
//
//  Input : one item from the Webhook node (Raw Body = true)
//  Output: 0..N items, each { sql }, consumed by the Snowflake node, plus a
//          responseText that the Respond node echoes back to the device.
//
//  This file is the editable source of truth. It is embedded verbatim in
//  adms-listener.workflow.json - if you change one, change the other.
// ═══════════════════════════════════════════════════════════════════════════

// ── Configuration ──────────────────────────────────────────────────────────
const DB      = 'DEVELOPMENT';
const SCHEMA  = 'ATTENDANCE';
const FQ      = `${DB}.${SCHEMA}`;

// Device UTC offset in whole hours, sent back in the handshake so the terminal
// keeps its clock aligned. Lagos/WAT = 1, Kigali/CAT = 2, Nairobi/EAT = 3.
const DEVICE_TZ_OFFSET = 1;

// Mirror every request body into RAW_PUSH_LOG. Leave true while commissioning
// a new terminal - it is the only way to see what your firmware really sends.
// Turn off once the parser is confirmed; it roughly doubles write volume.
const CAPTURE_RAW = true;

// ── Small helpers ──────────────────────────────────────────────────────────

// Snowflake honours backslash escapes inside string literals by default, so a
// literal backslash has to be doubled as well as the quote. Base64 templates
// contain neither, but staff names have contained both.
function lit(v) {
  if (v === null || v === undefined || v === '') return 'NULL';
  return "'" + String(v).replace(/\\/g, '\\\\').replace(/'/g, "''") + "'";
}

function num(v) {
  const n = parseInt(String(v === null || v === undefined ? '' : v).trim(), 10);
  return Number.isFinite(n) ? String(n) : 'NULL';
}

// Devices emit "2026-08-24 08:31:07"; a few firmwares use a T separator and
// some drop the seconds. Anything that doesn't match is rejected rather than
// coerced, because a wrong timestamp is worse than a missing punch.
function normaliseTs(v) {
  const m = String(v || '').trim()
    .match(/^(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if (!m) return null;
  const p = (s, w) => String(s).padStart(w, '0');
  return `${m[1]}-${p(m[2],2)}-${p(m[3],2)} ${p(m[4],2)}:${m[5]}:${m[6] || '00'}`;
}

// Every non-ATTLOG payload is TAB-joined "Key=Value" pairs. Keys are upper-cased
// so that Tmp/TMP, Pin/PIN and the other per-firmware casing differences all
// land in the same bucket.
function kv(line) {
  const out = {};
  for (const part of line.split('\t')) {
    const eq = part.indexOf('=');
    if (eq === -1) continue;              // stray or truncated field - skip it
    out[part.slice(0, eq).trim().toUpperCase()] = part.slice(eq + 1);
  }
  return out;
}

function pick(o, ...keys) {
  for (const k of keys) {
    if (o[k] !== undefined && o[k] !== '') return o[k];
  }
  return null;
}

// ── Extract the request ────────────────────────────────────────────────────
const inItem  = $input.first();
const j       = inItem.json || {};
const query   = j.query || {};
const params  = j.params || {};

// With "Raw Body" enabled the payload arrives as binary. Fall back through the
// other shapes n8n can produce: a plain string when Content-Type is text/plain,
// or - when the device sends application/x-www-form-urlencoded, which some ZK
// firmware does - an object whose single *key* is the entire body.
let raw = '';
const bin = inItem.binary;
if (bin && bin.data && bin.data.data) {
  raw = Buffer.from(bin.data.data, 'base64').toString('utf8');
} else if (typeof j.body === 'string') {
  raw = j.body;
} else if (j.body && typeof j.body === 'object') {
  const keys = Object.keys(j.body);
  raw = keys.length === 1 && j.body[keys[0]] === '' ? keys[0] : JSON.stringify(j.body);
}

const deviceSn   = String(pick(query, 'SN', 'sn') || '').trim();
const tableParam = String(query.table || query.TABLE || '').toUpperCase();
const endpoint   = String(params.endpoint || 'cdata').toLowerCase();

// Do NOT simply default a missing method to GET. Not every n8n version puts
// `method` on the webhook item - older ones emit only headers/params/query/
// body - and defaulting to GET makes the parser answer a POST with the
// handshake OPTIONS block and silently discard the payload. That failure is
// invisible from every angle: the device gets a 200 and believes the server is
// healthy, the worker reports SUCCESS, and n8n marks the execution successful
// because sqlCount 0 simply takes the false branch. It cost an afternoon.
//
// A push always carries a body and a table parameter; a handshake carries
// neither. Infer from that when the field is absent, and only then fall back.
const declaredMethod = j.method || j.httpMethod ||
  (j.headers && (j.headers['x-http-method-override'] || j.headers['X-HTTP-Method-Override']));
const method = String(
  declaredMethod || ((raw.trim() || tableParam) ? 'POST' : 'GET')
).toUpperCase();

const sqls = [];

// ── Non-cdata endpoints ────────────────────────────────────────────────────
// In the n8n workflow these have their own Webhook nodes and never reach this
// code. The guard exists so the parser is still correct when it is driven by
// tools/local_test_server.mjs or any other single-endpoint host - answering a
// getrequest poll with an OPTIONS block would make the device re-run its
// whole configuration handshake every ten seconds.
if (endpoint === 'getrequest' || endpoint === 'devicecmd' || endpoint === 'ping') {
  return [{
    json: { route: endpoint, deviceSn, responseText: 'OK\r\n', sqlCount: 0 },
  }];
}

// ── Handshake: GET /iclock/cdata ───────────────────────────────────────────
// This is the single most important response in the whole protocol. The device
// is asking "what do you want from me?". Replying "OK" tells it "nothing", and
// it will then sit there forever pushing no data at all.
//
// TransFlag is the bitmask-as-wordlist of what to push. Realtime=1 makes
// punches arrive within seconds instead of waiting for TransTimes.
if (method === 'GET') {
  const options = [
    `GET OPTION FROM: ${deviceSn}`,
    'Stamp=9999',
    'OpStamp=0',
    'ErrorDelay=30',
    'Delay=10',
    'TransTimes=00:00;14:00',
    'TransInterval=1',
    'TransFlag=TransData AttLog OpLog AttLog EnrollUser ChgUser EnrollFP ChgFP UserPic',
    `TimeZone=${DEVICE_TZ_OFFSET}`,
    'Realtime=1',
    'Encrypt=0',
  ].join('\r\n') + '\r\n';

  return [{
    json: {
      route: 'handshake',
      deviceSn,
      responseText: options,
      sqlCount: 0,
    },
  }];
}

// ── Raw capture ────────────────────────────────────────────────────────────
if (CAPTURE_RAW) {
  const qs = Object.entries(query).map(([k, v]) => `${k}=${v}`).join('&');
  sqls.push(
    `INSERT INTO ${FQ}.RAW_PUSH_LOG (DEVICE_SN, HTTP_METHOD, TABLE_PARAM, QUERY_STRING, RAW_BODY, RECEIVED_AT) ` +
    `SELECT ${lit(deviceSn)}, ${lit(method)}, ${lit(tableParam)}, ${lit(qs.slice(0, 4000))}, ${lit(raw.slice(0, 1000000))}, SYSDATE()`
  );
}

// ── Split into records ─────────────────────────────────────────────────────
const lines = raw.split(/\r?\n/).map(s => s.trim()).filter(Boolean);

// A line may carry its own type tag, which overrides the ?table= parameter.
// This matters because many firmwares push users and fingerprints inside
// table=OPERLOG as "USER PIN=..." / "FP PIN=..." rather than using the
// dedicated USERINFO and BIODATA tables at all.
const TAG = /^(USER|USERINFO|FP|BIODATA|OPLOG|ATTLOG|DEPARTMENT|DEPT)\s+/i;

// Deduplicate inside the batch. A MERGE whose source contains two rows with
// the same key fails outright with "Duplicate row detected during DML action",
// and devices genuinely do repeat lines within one push.
const users = new Map();
const bios  = new Map();
const punches = new Map();
const depts = new Map();

let skipped = 0;

for (const line of lines) {
  const m    = line.match(TAG);
  const tag  = m ? m[1].toUpperCase() : '';
  const body = m ? line.slice(m[0].length) : line;
  const kind = tag || tableParam;

  try {
    if (kind === 'ATTLOG') {
      // ATTLOG is positional, not Key=Value:
      //   PIN \t Timestamp \t State \t VerifyMode \t WorkCode \t Rsv1 \t Rsv2
      //
      // Field 5 carries the SITE, not a work code. Our app is the only client
      // that reaches this endpoint - the C6 never pushed ADMS natively, which
      // is why the app exists at all - and it puts the device's configured
      // location there precisely because the slot was otherwise always empty.
      // Keeping the row shape identical to a real terminal is what lets this
      // parser and the MERGE stay unchanged in shape.
      //
      // If a genuine ZKTeco terminal is ever pointed at this endpoint, that
      // device WILL put a work code in this field and it will land in LOCATION.
      // Decide then; today there is no such device.
      const f   = body.split('\t');
      const pin = (f[0] || '').trim();
      const ts  = normaliseTs(f[1]);
      if (!pin || !ts) { skipped++; continue; }
      const logId = `${deviceSn}|${pin}|${ts}`;
      punches.set(logId, {
        logId, pin, ts,
        punchType:  f[2],
        verifyMode: f[3],
        location:   (f[4] || '').trim() || null,
      });

    } else if (kind === 'USER' || kind === 'USERINFO') {
      const r   = kv(body);
      const pin = String(pick(r, 'PIN', 'PIN2', 'USERID') || '').trim();
      if (!pin) { skipped++; continue; }

      // Our app sends FirstName= and LastName=; a stock ZK terminal only knows
      // Name=. Accept both and derive whichever is missing, so the two firmware
      // families can push the same roster without one blanking the other's
      // fields. Splitting on the FIRST space matches the device-side migration
      // in AppDatabase.MIGRATION_3_4 - keep the two in step.
      const whole = pick(r, 'NAME');
      let first = pick(r, 'FIRSTNAME', 'FNAME', 'GIVENNAME');
      let last  = pick(r, 'LASTNAME', 'LNAME', 'SURNAME');
      if (!first && !last && whole) {
        const t   = String(whole).trim();
        const gap = t.indexOf(' ');
        first = gap > 0 ? t.slice(0, gap) : t;
        last  = gap > 0 ? t.slice(gap + 1).trim() : null;
      }
      const full = whole || [first, last].filter(Boolean).join(' ') || null;

      // Tri-state, and it has to stay tri-state. Absent means "this push says
      // nothing about deletion" - which is what every stock device and every
      // partial update sends - and must leave the tombstone exactly as it is.
      // Only an explicit 0 restores somebody. Collapsing absent and 0 together
      // would make every routine roster push un-delete anybody a colleague
      // removed on another terminal.
      const rawDeleted = pick(r, 'DELETED', 'DELETEDAT', 'ISDELETED');
      let deletedFlag = null;
      if (rawDeleted !== null) {
        const s = String(rawDeleted).trim().toLowerCase();
        deletedFlag = (s === '0' || s === 'false' || s === '') ? 0 : 1;
      }

      users.set(pin, {
        pin,
        name: full,
        first,
        last,
        card: pick(r, 'CARD', 'CARDNO'),
        pri:  pick(r, 'PRI', 'PRIVILEGE'),
        // Sent by our app as extra KEY=value pairs. USERINFO is key-value, not
        // positional, so adding these cannot break a device that omits them -
        // they simply arrive as null and COALESCE leaves the stored value.
        dept:   pick(r, 'DEPT', 'DEPARTMENT'),
        gender: pick(r, 'GENDER', 'SEX'),
        deletedFlag,
      });

    } else if (kind === 'DEPARTMENT' || kind === 'DEPT') {
      // The admin-managed department list. Same tri-state deletion rule as
      // USERINFO above.
      const r    = kv(body);
      const name = String(pick(r, 'NAME', 'DEPT', 'DEPARTMENT') || '').trim();
      if (!name) { skipped++; continue; }
      const rawDeleted = pick(r, 'DELETED', 'DELETEDAT', 'ISDELETED');
      let deletedFlag = null;
      if (rawDeleted !== null) {
        const s = String(rawDeleted).trim().toLowerCase();
        deletedFlag = (s === '0' || s === 'false' || s === '') ? 0 : 1;
      }
      depts.set(name, { name, deletedFlag });

    } else if (kind === 'FP' || kind === 'BIODATA') {
      const r   = kv(body);
      const pin = String(pick(r, 'PIN', 'PIN2', 'USERID') || '').trim();
      const tpl = pick(r, 'TMP', 'TEMPLATE', 'TEMPLATEDATA');
      if (!pin || !tpl) { skipped++; continue; }
      const idx = num(pick(r, 'FID', 'INDEX', 'NO', 'FINGERID'));
      const key = `${pin}|${idx}`;
      bios.set(key, {
        pin,
        idx,
        tpl,
        type:  pick(r, 'TYPE'),
        size:  pick(r, 'SIZE'),
        valid: pick(r, 'VALID'),
      });

    } else {
      // OPLOG and anything unrecognised: captured in RAW_PUSH_LOG, not parsed.
      skipped++;
    }
  } catch (e) {
    // One malformed line must never cost us the rest of the batch - if we
    // throw here the device gets a 500 and re-sends everything forever.
    skipped++;
  }
}

// ── Build the MERGEs ───────────────────────────────────────────────────────
// One multi-row MERGE per table rather than one statement per record.
// Snowflake charges warehouse time per statement and handles tiny single-row
// DML badly; a 60-punch batch should be one round trip, not sixty.

if (punches.size) {
  const rows = [...punches.values()].map(p =>
    `(${lit(p.logId)}, ${lit(p.pin)}, ${lit(p.ts)}, ${num(p.punchType)}, ${num(p.verifyMode)}, ${lit(p.location)}, ${lit(deviceSn)})`
  ).join(',\n         ');

  sqls.push(
// Explicit casts matter: if every row of a column is NULL, Snowflake cannot
// infer its type from the VALUES list and the MERGE fails at parse time.
// LOCATION is NULL for any punch taken before a site was configured on the
// device, and PUNCH_TYPE is routinely all one value.
//
// Still INSERT-only on match: a re-sent batch must not rewrite a punch that is
// already recorded. LOG_ID is the only thing preventing duplicates - Snowflake
// enforces no uniqueness - so the idempotency lives entirely in this ON clause.
`MERGE INTO ${FQ}.ATTENDANCE_LOGS t
USING (
  SELECT c1::VARCHAR                 AS LOG_ID,
         c2::VARCHAR                 AS EMPLOYEE_PIN,
         TO_TIMESTAMP_NTZ(c3::VARCHAR) AS PUNCH_TS,
         c4::NUMBER(3,0)             AS PUNCH_TYPE,
         c5::NUMBER(3,0)             AS VERIFY_MODE,
         c6::VARCHAR                 AS LOCATION,
         c7::VARCHAR                 AS DEVICE_SN
  FROM (VALUES ${rows}
       ) AS v(c1, c2, c3, c4, c5, c6, c7)
) s
ON t.LOG_ID = s.LOG_ID
WHEN NOT MATCHED THEN INSERT
  (LOG_ID, EMPLOYEE_PIN, PUNCH_TS, PUNCH_TYPE, VERIFY_MODE, LOCATION, DEVICE_SN, RECEIVED_AT)
  VALUES (s.LOG_ID, s.EMPLOYEE_PIN, s.PUNCH_TS, s.PUNCH_TYPE, s.VERIFY_MODE, s.LOCATION, s.DEVICE_SN, SYSDATE())`
  );
}

if (users.size) {
  const rows = [...users.values()].map(u =>
    `(${lit(u.pin)}, ${lit(u.first)}, ${lit(u.last)}, ${lit(u.name)}, ${lit(u.card)}, ` +
    `${num(u.pri)}, ${lit(u.dept)}, ${lit(u.gender)}, ` +
    `${u.deletedFlag === null ? 'NULL' : String(u.deletedFlag)}, ${lit(deviceSn)})`
  ).join(',\n         ');

  // COALESCE on update: a partial push (a card change with no Name field)
  // must not blank out a name we already hold. That now covers DEPARTMENT and
  // GENDER too - a device that does not send them leaves the stored values
  // alone rather than clearing them, which matters once several devices push
  // the same roster.
  sqls.push(
`MERGE INTO ${FQ}.EMPLOYEES t
USING (
  SELECT c1::VARCHAR     AS DEVICE_PIN,
         c2::VARCHAR     AS FIRST_NAME,
         c3::VARCHAR     AS LAST_NAME,
         c4::VARCHAR     AS FULL_NAME,
         c5::VARCHAR     AS CARD_NO,
         c6::NUMBER(3,0) AS PRIVILEGE,
         c7::VARCHAR     AS DEPARTMENT,
         c8::VARCHAR     AS GENDER,
         c9::NUMBER(1,0) AS DELETED_FLAG,
         c10::VARCHAR    AS DEVICE_SN
  FROM (VALUES ${rows}
       ) AS v(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10)
) s
ON t.DEVICE_PIN = s.DEVICE_PIN
WHEN MATCHED THEN UPDATE SET
  FIRST_NAME = COALESCE(s.FIRST_NAME, t.FIRST_NAME),
  LAST_NAME  = COALESCE(s.LAST_NAME,  t.LAST_NAME),
  FULL_NAME  = COALESCE(s.FULL_NAME,  t.FULL_NAME),
  CARD_NO    = COALESCE(s.CARD_NO,    t.CARD_NO),
  PRIVILEGE  = COALESCE(s.PRIVILEGE,  t.PRIVILEGE),
  DEPARTMENT = COALESCE(s.DEPARTMENT, t.DEPARTMENT),
  GENDER     = COALESCE(s.GENDER,     t.GENDER),
  DEVICE_SN  = s.DEVICE_SN,
  -- Tri-state, and the three cases are all different on purpose.
  --
  -- NULL means this push says nothing about deletion, which is what a stock
  -- terminal and every partial update send: leave the tombstone alone. A
  -- terminal that has not yet pulled a deletion keeps pushing that person's
  -- roster row, and clearing the tombstone there would resurrect somebody an
  -- admin removed - silently, on whichever device synced last.
  --
  -- 0 is an explicit restore, which only our app sends and only when an admin
  -- asks for it. 1 is a deletion; COALESCE keeps the ORIGINAL deletion time so
  -- a re-sent batch does not keep moving it forward and re-triggering pulls.
  DELETED_AT = CASE
                 WHEN s.DELETED_FLAG IS NULL THEN t.DELETED_AT
                 WHEN s.DELETED_FLAG = 0     THEN NULL
                 ELSE COALESCE(t.DELETED_AT, SYSDATE())
               END,
  UPDATED_AT = SYSDATE()
WHEN NOT MATCHED THEN INSERT
  (DEVICE_PIN, FIRST_NAME, LAST_NAME, FULL_NAME, CARD_NO, PRIVILEGE,
   DEPARTMENT, GENDER, STATUS, DELETED_AT, DEVICE_SN, CREATED_AT, UPDATED_AT)
  VALUES (s.DEVICE_PIN, s.FIRST_NAME, s.LAST_NAME, s.FULL_NAME, s.CARD_NO, s.PRIVILEGE,
          s.DEPARTMENT, s.GENDER, 'ACTIVE',
          IFF(s.DELETED_FLAG = 1, SYSDATE(), NULL),
          s.DEVICE_SN, SYSDATE(), SYSDATE())`
  );
}

// Departments before employees would be tidier, but the order of `sqls` is the
// order they execute in and EMPLOYEES only references DEPARTMENTS through an
// unenforced constraint, so it does not matter. What does matter is that this
// runs at all: without it a department created on one terminal never leaves it.
if (depts.size) {
  const rows = [...depts.values()].map(d =>
    `(${lit(d.name)}, ${d.deletedFlag === null ? 'NULL' : String(d.deletedFlag)}, ${lit(deviceSn)})`
  ).join(',\n         ');

  sqls.push(
`MERGE INTO ${FQ}.DEPARTMENTS t
USING (
  SELECT c1::VARCHAR     AS NAME,
         c2::NUMBER(1,0) AS DELETED_FLAG,
         c3::VARCHAR     AS DEVICE_SN
  FROM (VALUES ${rows}
       ) AS v(c1, c2, c3)
) s
ON t.NAME = s.NAME
WHEN MATCHED THEN UPDATE SET
  DELETED_AT = CASE
                 WHEN s.DELETED_FLAG IS NULL THEN t.DELETED_AT
                 WHEN s.DELETED_FLAG = 0     THEN NULL
                 ELSE COALESCE(t.DELETED_AT, SYSDATE())
               END,
  DEVICE_SN  = s.DEVICE_SN,
  UPDATED_AT = SYSDATE()
WHEN NOT MATCHED THEN INSERT (NAME, DELETED_AT, DEVICE_SN, CREATED_AT, UPDATED_AT)
  VALUES (s.NAME, IFF(s.DELETED_FLAG = 1, SYSDATE(), NULL), s.DEVICE_SN, SYSDATE(), SYSDATE())`
  );
}

if (bios.size) {
  const rows = [...bios.values()].map(b =>
    `(${lit(b.pin)}, ${b.idx === 'NULL' ? 0 : b.idx}, ${lit(b.tpl)}, ${lit(b.type)}, ${num(b.size)}, ${b.valid === '0' ? 'FALSE' : 'TRUE'}, ${lit(deviceSn)})`
  ).join(',\n         ');

  sqls.push(
`MERGE INTO ${FQ}.BIOMETRIC_TEMPLATES t
USING (
  SELECT c1::VARCHAR      AS EMPLOYEE_PIN,
         c2::NUMBER(2,0)  AS FINGER_INDEX,
         c3::VARCHAR      AS TEMPLATE_DATA,
         c4::VARCHAR      AS TEMPLATE_TYPE,
         c5::NUMBER(10,0) AS TEMPLATE_SIZE,
         c6::BOOLEAN      AS IS_VALID,
         c7::VARCHAR      AS DEVICE_SN
  FROM (VALUES ${rows}
       ) AS v(c1, c2, c3, c4, c5, c6, c7)
) s
ON t.EMPLOYEE_PIN = s.EMPLOYEE_PIN AND t.FINGER_INDEX = s.FINGER_INDEX
WHEN MATCHED THEN UPDATE SET
  TEMPLATE_DATA = s.TEMPLATE_DATA,
  TEMPLATE_TYPE = s.TEMPLATE_TYPE,
  TEMPLATE_SIZE = s.TEMPLATE_SIZE,
  IS_VALID      = s.IS_VALID,
  DEVICE_SN     = s.DEVICE_SN,
  UPDATED_AT    = SYSDATE()
WHEN NOT MATCHED THEN INSERT
  (EMPLOYEE_PIN, FINGER_INDEX, TEMPLATE_DATA, TEMPLATE_TYPE, TEMPLATE_SIZE, IS_VALID, DEVICE_SN, UPDATED_AT)
  VALUES (s.EMPLOYEE_PIN, s.FINGER_INDEX, s.TEMPLATE_DATA, s.TEMPLATE_TYPE, s.TEMPLATE_SIZE, s.IS_VALID, s.DEVICE_SN, SYSDATE())`
  );

  // A PIN can push fingerprints before its USERINFO record arrives. Create a
  // placeholder so the roster shows the person instead of losing them.
  const pins = [...new Set([...bios.values()].map(b => b.pin))]
    .map(p => `(${lit(p)})`).join(', ');
  sqls.push(
`MERGE INTO ${FQ}.EMPLOYEES t
USING (SELECT c1::VARCHAR AS DEVICE_PIN FROM (VALUES ${pins}) AS v(c1)) s
ON t.DEVICE_PIN = s.DEVICE_PIN
WHEN NOT MATCHED THEN INSERT (DEVICE_PIN, STATUS, DEVICE_SN, CREATED_AT, UPDATED_AT)
  VALUES (s.DEVICE_PIN, 'ACTIVE', ${lit(deviceSn)}, SYSDATE(), SYSDATE())`
  );
}

// ── Response ───────────────────────────────────────────────────────────────
// "OK: <n>" where n is the record count is what ZK expects for a data push.
// The device treats anything else as a failure and re-sends the batch.
const accepted = punches.size + users.size + bios.size + depts.size;
const responseText = `OK: ${accepted}\r\n`;

const meta = {
  route: tableParam || 'unknown',
  deviceSn,
  accepted,
  skipped,
  lines: lines.length,
  responseText,
  sqlCount: sqls.length,
};

if (!sqls.length) {
  return [{ json: { ...meta, sql: null } }];
}

return sqls.map((sql, i) => ({ json: { ...meta, sql, sqlIndex: i } }));
