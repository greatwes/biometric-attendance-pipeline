// ═══════════════════════════════════════════════════════════════════════════
//  POST /webhook/zk/roster-publish  ·  n8n Code node
//  Mode: "Run Once for All Items". Sits between the Webhook and the Data table
//  Upsert node, which writes to `zk_roster`.
//
//  Called by dataiku/publish_roster.py, never by a device. It flattens what
//  Dataiku sends into one row per record so the Data table node can upsert them
//  keyed on `key`.
//
//  Devices read the same table through roster-response.js. Nothing here ever
//  touches Snowflake - it cannot; see SYNC-DESIGN.md section 3.2.
// ═══════════════════════════════════════════════════════════════════════════

const first = $input.first().json || {};
const body = first.body || first;

const employees = Array.isArray(body.employees) ? body.employees : [];
const departments = Array.isArray(body.departments) ? body.departments : [];

const rows = [];

for (const e of employees) {
  const pin = String(e.pin || '').trim();
  if (!pin) continue;

  // The whole record is stored as one JSON blob rather than as columns.
  // Deliberate: the roster shape will change again - it already gained split
  // names once - and n8n Data table columns are edited by hand in the UI, so
  // every added field would be a manual migration on a live instance. The
  // device parses this; n8n only needs to filter on `version` and key on
  // `key`, which are the two things kept as real columns.
  rows.push({
    key: 'emp:' + pin,
    kind: 'employee',
    version: Number(e.version) || 0,
    payload: JSON.stringify({
      pin,
      firstName: e.firstName || '',
      lastName: e.lastName || '',
      fullName: e.fullName || '',
      department: e.department || '',
      gender: e.gender || '',
      deleted: e.deleted === true,
      version: Number(e.version) || 0,
      fingers: Array.isArray(e.fingers) ? e.fingers : [],
    }),
  });
}

for (const d of departments) {
  const name = String(d.name || '').trim();
  if (!name) continue;
  rows.push({
    key: 'dept:' + name,
    kind: 'department',
    version: Number(d.version) || 0,
    payload: JSON.stringify({
      name,
      deleted: d.deleted === true,
      version: Number(d.version) || 0,
    }),
  });
}

// alwaysOutputData on the Data table node covers the empty case, but emit a
// sentinel anyway: n8n skips a node that receives zero items, which would leave
// the Respond node unreached and hang Dataiku until its timeout.
if (!rows.length) {
  return [{ json: { noop: true, count: 0 } }];
}

return rows.map(row => ({ json: row }));
