// Offline harness for parse-adms-request.js. Wraps the Code-node body in a
// function and feeds it the shapes a real Horus H1 produces.
//     node test-parser.mjs
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const code = readFileSync(join(here, 'parse-adms-request.js'), 'utf8');
const run  = new Function('$input', code);

function req({ method = 'POST', query = {}, body = '', asBinary = true }) {
  const item = { json: { method, query, params: {}, body: asBinary ? undefined : body } };
  if (asBinary) {
    item.binary = { data: { data: Buffer.from(body, 'utf8').toString('base64') } };
  }
  return { first: () => item };
}

let pass = 0, fail = 0;
const check = (label, cond, detail = '') => {
  if (cond) { pass++; console.log(`  ok   ${label}`); }
  else      { fail++; console.log(`  FAIL ${label}${detail ? '\n       ' + detail : ''}`); }
};

// ── 1. Handshake ───────────────────────────────────────────────────────────
console.log('\n1. GET /iclock/cdata handshake');
{
  const out = run(req({ method: 'GET', query: { SN: 'CJXH2340', options: 'all', pushver: '2.4.1' } }));
  const t = out[0].json.responseText;
  check('returns OPTIONS block, not "OK"', t.startsWith('GET OPTION FROM: CJXH2340'), JSON.stringify(t.slice(0, 40)));
  check('enables realtime push',           /Realtime=1/.test(t));
  check('enables user + fingerprint push', /TransFlag=.*EnrollUser.*EnrollFP/.test(t));
  check('no SQL for a handshake',          out[0].json.sqlCount === 0);
}

// ── 2. ATTLOG ──────────────────────────────────────────────────────────────
console.log('\n2. POST table=ATTLOG');
{
  const body = [
    '1001\t2026-08-24 08:31:07\t0\t1\t\t0\t0',
    '1002\t2026-08-24 08:33:44\t0\t15\t\t0\t0',
    '1001\t2026-08-24 08:31:07\t0\t1\t\t0\t0',   // duplicate inside the batch
    'garbage-line-with-no-tabs',
  ].join('\r\n');
  const out = run(req({ query: { SN: 'CJXH2340', table: 'ATTLOG', Stamp: '9999' }, body }));
  const m = out.find(o => /MERGE INTO .*ATTENDANCE_LOGS/.test(o.json.sql || ''));
  check('produced an ATTENDANCE_LOGS merge', !!m);
  check('deduped inside the batch (2 not 3)', out[0].json.accepted === 2, `accepted=${out[0].json.accepted}`);
  check('counted the malformed line as skipped', out[0].json.skipped === 1, `skipped=${out[0].json.skipped}`);
  check('response is "OK: 2"', out[0].json.responseText.trim() === 'OK: 2');
  check('log id is SN|PIN|TS', /'CJXH2340\|1001\|2026-08-24 08:31:07'/.test(m.json.sql));
  check('casts NULL-able columns', /c4::NUMBER\(3,0\)/.test(m.json.sql));
  check('raw capture emitted', out.some(o => /RAW_PUSH_LOG/.test(o.json.sql || '')));
  // Stock firmware leaves field 5 empty, so LOCATION must be NULL rather than
  // an empty string - otherwise "no location" and "a site literally named
  // nothing" become indistinguishable in Snowflake.
  check('empty field 5 becomes NULL LOCATION', /, NULL, 'CJXH2340'\)/.test(m.json.sql));
}

// ── 2b. ATTLOG from our app, which puts the site in field 5 ───────────────
console.log('\n2b. POST table=ATTLOG with a location');
{
  const body = [
    '171917\t2026-09-03 07:16:12\t0\t1\tMinna',
    '171917\t2026-09-03 07:16:19\t1\t1\tMinna',
  ].join('\r\n');
  const out = run(req({ query: { SN: 'C6-UNKNOWN', table: 'ATTLOG' }, body }));
  const m = out.find(o => /MERGE INTO .*ATTENDANCE_LOGS/.test(o.json.sql || ''));
  check('produced an ATTENDANCE_LOGS merge', !!m);
  check('accepted both punches', out[0].json.accepted === 2, `accepted=${out[0].json.accepted}`);
  check('carried the location', /'Minna'/.test(m.json.sql));
  check('writes LOCATION, not WORK_CODE', /AS LOCATION/.test(m.json.sql) && !/WORK_CODE/.test(m.json.sql));
  // Clock In and Clock Out at the same site, seconds apart, are two distinct
  // punches - the log id must separate them or one overwrites the other.
  check('distinct log ids for the two types', /07:16:12'/.test(m.json.sql) && /07:16:19'/.test(m.json.sql));
}

// ── 3. USERINFO ────────────────────────────────────────────────────────────
console.log('\n3. POST table=USERINFO');
{
  const body = "PIN=1001\tName=Ada O'Brien\tPri=0\tPasswd=\tCard=0012345\tGrp=1\tTZ=0000";
  const out = run(req({ query: { SN: 'CJXH2340', table: 'USERINFO' }, body }));
  const m = out.find(o => /MERGE INTO .*EMPLOYEES/.test(o.json.sql || ''));
  check('produced an EMPLOYEES merge', !!m);
  check("escaped the apostrophe in O'Brien", /'Ada O''Brien'/.test(m.json.sql));
  check('COALESCEs so partial pushes do not blank names', /FULL_NAME  = COALESCE/.test(m.json.sql));
  // A stock device sends neither Dept nor Gender. The columns must still appear
  // in the MERGE (our app does send them) but must arrive NULL and be COALESCEd,
  // so a push from stock firmware cannot blank values another device supplied.
  check('COALESCEs DEPARTMENT rather than blanking it', /DEPARTMENT = COALESCE/.test(m.json.sql));
  check('COALESCEs GENDER rather than blanking it', /GENDER     = COALESCE/.test(m.json.sql));
  check('leaves DEPARTMENT NULL when the device omits it', /NULL, NULL, 'CJXH2340'/.test(m.json.sql));
}

// ── 3b. USERINFO from our own app, which does send Dept and Gender ─────────
console.log('\n3b. POST table=USERINFO with Dept and Gender');
{
  const body = "PIN=171917\tName=Mwangi Asha\tDept=Field Ops\tGender=Male";
  const out = run(req({ query: { SN: 'C6-UNKNOWN', table: 'USERINFO' }, body }));
  const m = out.find(o => /MERGE INTO .*EMPLOYEES/.test(o.json.sql || ''));
  check('produced an EMPLOYEES merge', !!m);
  check('carried the department', /'Field Ops'/.test(m.json.sql));
  check('carried the gender', /'Male'/.test(m.json.sql));
}

// ── 3c. Split names from our app ───────────────────────────────────────────
console.log('\n3c. POST table=USERINFO with FirstName and LastName');
{
  const body = "PIN=171917\tFirstName=Asha\tLastName=Mwangi\tDept=Field Ops\tGender=Male";
  const out = run(req({ query: { SN: 'C6-UNKNOWN', table: 'USERINFO' }, body }));
  const m = out.find(o => /MERGE INTO .*EMPLOYEES/.test(o.json.sql || ''));
  check('produced an EMPLOYEES merge', !!m);
  check('carried the first name', /'Asha'/.test(m.json.sql));
  check('carried the last name', /'Mwangi'/.test(m.json.sql));
  check('writes FIRST_NAME and LAST_NAME', /AS FIRST_NAME/.test(m.json.sql) && /AS LAST_NAME/.test(m.json.sql));
  // FULL_NAME has to stay populated: the Dataiku views and anything already
  // built against them still read it, and a stock device only ever sends it.
  check('still derives FULL_NAME', /'Asha Mwangi'/.test(m.json.sql));
}

// ── 3d. Stock firmware sends only Name= ───────────────────────────────────
console.log('\n3d. POST table=USERINFO with only Name= (stock firmware)');
{
  const body = "PIN=2002\tName=Ibrahim Alkali\tCard=0099887";
  const out = run(req({ query: { SN: 'CJXH2340', table: 'USERINFO' }, body }));
  const m = out.find(o => /MERGE INTO .*EMPLOYEES/.test(o.json.sql || ''));
  check('split the single name', /'Ibrahim'/.test(m.json.sql) && /'Alkali'/.test(m.json.sql));
  check('kept the original as FULL_NAME', /'Ibrahim Alkali'/.test(m.json.sql));
  // A one-word name must keep the whole string as the first name: the punch
  // messages greet people by first name, and an empty greeting is worse than
  // a missing surname.
  const single = run(req({ query: { SN: 'X', table: 'USERINFO' }, body: 'PIN=3003\tName=Chidi' }));
  const sm = single.find(o => /MERGE INTO .*EMPLOYEES/.test(o.json.sql || ''));
  check('one-word name becomes the first name', /'Chidi'/.test(sm.json.sql));
}

// ── 3e. Deletion is tri-state ─────────────────────────────────────────────
// The bug this guards against: collapsing "said nothing" and "said not
// deleted" together, so that every routine roster push from a terminal that
// has not yet pulled a deletion un-deletes the person an admin removed.
console.log('\n3e. USERINFO deletion is tri-state');
{
  const absent = run(req({ query: { SN: 'X', table: 'USERINFO' }, body: 'PIN=1\tName=A B' }));
  const am = absent.find(o => /MERGE INTO .*EMPLOYEES/.test(o.json.sql || '')).json.sql;
  check('absent Deleted leaves the tombstone alone',
        /WHEN s\.DELETED_FLAG IS NULL THEN t\.DELETED_AT/.test(am));
  check('absent Deleted sends NULL as the flag', /, NULL, 'X'\)/.test(am));

  const del = run(req({ query: { SN: 'X', table: 'USERINFO' }, body: 'PIN=1\tName=A B\tDeleted=1' }));
  const dm = del.find(o => /MERGE INTO .*EMPLOYEES/.test(o.json.sql || '')).json.sql;
  check('Deleted=1 sends flag 1', /, 1, 'X'\)/.test(dm));

  const restore = run(req({ query: { SN: 'X', table: 'USERINFO' }, body: 'PIN=1\tName=A B\tDeleted=0' }));
  const rm = restore.find(o => /MERGE INTO .*EMPLOYEES/.test(o.json.sql || '')).json.sql;
  check('Deleted=0 sends flag 0', /, 0, 'X'\)/.test(rm));
  check('flag 0 is the only path that clears it',
        /WHEN s\.DELETED_FLAG = 0\s+THEN NULL/.test(rm));
  // A repeated delete must not keep moving the timestamp forward, or every
  // push re-triggers an incremental pull on every other device.
  check('a repeated delete keeps the original timestamp',
        /ELSE COALESCE\(t\.DELETED_AT, SYSDATE\(\)\)/.test(dm));
  // SYSDATE() is UTC whatever the session timezone; CURRENT_TIMESTAMP() is
  // whatever the client happens to be set to, which on this account is US
  // Pacific. Mixing the two put half the schema eight hours out from the
  // device's own clock - and by seven hours instead, half the year.
  check('writes UTC, not session-local time',
        !/CURRENT_TIMESTAMP/.test(dm) && /SYSDATE\(\)/.test(dm));
}

// ── 3f. Departments ───────────────────────────────────────────────────────
console.log('\n3f. POST table=DEPARTMENT');
{
  const body = ['Name=Field Ops', 'Name=Finance', 'Name=Retired Team\tDeleted=1'].join('\r\n');
  const out = run(req({ query: { SN: 'C6-UNKNOWN', table: 'DEPARTMENT' }, body }));
  const m = out.find(o => /MERGE INTO .*DEPARTMENTS/.test(o.json.sql || ''));
  check('produced a DEPARTMENTS merge', !!m);
  check('accepted all three', out[0].json.accepted === 3, `accepted=${out[0].json.accepted}`);
  check('carried the names', /'Field Ops'/.test(m.json.sql) && /'Finance'/.test(m.json.sql));
  check('marked the removed one deleted', /'Retired Team', 1,/.test(m.json.sql));
  check('left the live ones untouched', /'Finance', NULL,/.test(m.json.sql));
}

// ── 4. BIODATA (lowercase Pin/Tmp, as some firmware sends) ────────────────
console.log('\n4. POST table=BIODATA with lowercase keys');
{
  const body = 'Pin=1001\tNo=0\tIndex=3\tValid=1\tDuress=0\tType=1\tFormat=0\tTmp=c2VjdXJlVGVtcGxhdGU=';
  const out = run(req({ query: { SN: 'CJXH2340', table: 'BIODATA' }, body }));
  const m = out.find(o => /MERGE INTO .*BIOMETRIC_TEMPLATES/.test(o.json.sql || ''));
  check('parsed despite Pin/Tmp casing', !!m);
  check('kept the base64 verbatim', /'c2VjdXJlVGVtcGxhdGU='/.test(m.json.sql));
  check('auto-creates the employee placeholder',
        out.some(o => /MERGE INTO .*EMPLOYEES/.test(o.json.sql || '') && /WHEN NOT MATCHED/.test(o.json.sql)));
}

// ── 5. OPERLOG carrying USER + FP lines (the common real-world case) ──────
console.log('\n5. POST table=OPERLOG with tagged USER / FP lines');
{
  const body = [
    'USER PIN=2001\tName=Kwame Mensah\tPri=0\tCard=0099887',
    'FP PIN=2001\tFID=6\tSize=1024\tValid=1\tTMP=QUJDREVGRw==',
    'OPLOG 4\t1\t0\t0\t2026-08-24 09:00:00\t0',
  ].join('\r\n');
  const out = run(req({ query: { SN: 'CJXH2340', table: 'OPERLOG' }, body }));
  check('routed USER line to EMPLOYEES', out.some(o => /MERGE INTO .*EMPLOYEES/.test(o.json.sql || '')));
  check('routed FP line to BIOMETRIC_TEMPLATES', out.some(o => /BIOMETRIC_TEMPLATES/.test(o.json.sql || '')));
  check('ignored the OPLOG line', out[0].json.skipped === 1, `skipped=${out[0].json.skipped}`);
  check('accepted 2 records', out[0].json.accepted === 2, `accepted=${out[0].json.accepted}`);
}

// ── 6. Hostile / degenerate input ─────────────────────────────────────────
console.log('\n6. Degenerate input must not throw');
{
  for (const [label, body] of [
    ['empty body', ''],
    ['only newlines', '\r\n\r\n'],
    ['bad timestamp', '1001\tnot-a-date\t0\t1'],
    ['missing pin', '\t2026-08-24 08:00:00\t0\t1'],
    ['sql injection attempt', "1001\t2026-08-24 08:00:00\t0\t1\t'); DROP TABLE ATTENDANCE_LOGS; --"],
  ]) {
    try {
      const out = run(req({ query: { SN: 'X', table: 'ATTLOG' }, body }));
      const sql = out.map(o => o.json.sql || '').join('\n');
      const clean = !/DROP TABLE/i.test(sql.replace(/'[^']*'/g, "''"));
      check(`${label} handled`, Array.isArray(out) && clean);
    } catch (e) {
      check(`${label} handled`, false, e.message);
    }
  }
}

// ── 7. Form-urlencoded fallback (no binary, body as a single object key) ──
console.log('\n7. application/x-www-form-urlencoded fallback');
{
  const item = {
    json: {
      method: 'POST',
      query: { SN: 'CJXH2340', table: 'ATTLOG' },
      params: {},
      body: { '1001\t2026-08-24 10:00:00\t0\t1': '' },
    },
  };
  const out = run({ first: () => item });
  check('recovered the body from the object key', out[0].json.accepted === 1, `accepted=${out[0].json.accepted}`);
}

// ── 8. Webhook item with no `method` field (older n8n emits none) ─────────
// The real failure of 4 Sep 2026: the parser defaulted to GET, answered a
// punch upload with the handshake OPTIONS block and dropped the record. Every
// indicator stayed green - 200 to the device, SUCCESS from the worker, a
// successful n8n execution - because sqlCount 0 just takes the false branch.
console.log('\n8. POST whose item carries no method field');
{
  const body = '171917\t2026-09-04 10:22:15\t0\t1\tLapai';
  const item = {
    json: { query: { SN: 'C6-UNKNOWN', table: 'ATTLOG' }, params: {} },
    binary: { data: { data: Buffer.from(body, 'utf8').toString('base64') } },
  };
  const out = run({ first: () => item });
  check('did not fall back to the GET handshake',
        !/GET OPTION FROM/.test(out[0].json.responseText || ''),
        JSON.stringify((out[0].json.responseText || '').slice(0, 40)));
  check('produced SQL', out[0].json.sqlCount > 0, `sqlCount=${out[0].json.sqlCount}`);
  check('accepted the punch', out[0].json.accepted === 1, `accepted=${out[0].json.accepted}`);
  check('kept the location', out.some(o => /'Lapai'/.test(o.json.sql || '')));
}

// A bodyless, table-less request with no method is still a handshake.
console.log('\n8b. GET handshake with no method field');
{
  const item = { json: { query: { SN: 'C6-UNKNOWN', options: 'all' }, params: {} } };
  const out = run({ first: () => item });
  check('still answers with the OPTIONS block',
        (out[0].json.responseText || '').startsWith('GET OPTION FROM: C6-UNKNOWN'));
  check('no SQL for a handshake', out[0].json.sqlCount === 0);
}

console.log(`\n${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
