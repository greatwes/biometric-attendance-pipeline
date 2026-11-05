// ═══════════════════════════════════════════════════════════════════════════
//  GET /webhook/zk/roster?since=<version>&sn=<device_sn>  ·  n8n Code node
//  Mode: "Run Once for All Items". Sits between the Data table Get node
//  (reading `zk_roster`) and the Respond node.
//
//  This is what a terminal calls to ask "what has changed since I last looked?"
//  It reads only what dataiku/publish_roster.py parked in the Data table - n8n
//  never queries Snowflake, and a device never reaches it.
// ═══════════════════════════════════════════════════════════════════════════

const items = $input.all().map(i => i.json).filter(Boolean);

// Read `since` from the webhook node by name, NOT from $input.
//
// $input here is the Data table's rows; the webhook item is not in it. Hunting
// for an item carrying `query` - which this file did first - finds nothing,
// silently falls back to 0, and returns the entire roster on every single poll.
// Every device would then re-apply every row forever: correct output, endless
// work, and templates re-registered into the matcher on a loop.
let since = 0;
try {
  const q = $('Roster Pull').first().json.query || {};
  since = Number(q.since || q.Since || 0) || 0;
} catch (e) {
  // Node renamed or unreachable. 0 means "send everything", which is wasteful
  // but correct - better than dropping rows a device has not seen.
  since = 0;
}

const rows = items.filter(r => r && r.key && r.payload);

function parse(row) {
  try {
    const payload = typeof row.payload === 'string' ? JSON.parse(row.payload) : row.payload;
    return { version: Number(row.version) || 0, kind: row.kind, payload };
  } catch (e) {
    // A row that will not parse is skipped rather than thrown on. One corrupt
    // record must not stop every terminal in the fleet from syncing, and it is
    // visible in the count mismatch between `employees` and the table size.
    return null;
  }
}

const parsed = rows.map(parse).filter(Boolean);

// Strictly greater than: `since` is the highest version the device already
// holds, so returning it again would make every poll re-apply the same rows
// and re-register the same templates into the matcher for no reason.
const changed = parsed.filter(r => r.version > since);

const employees = changed
  .filter(r => r.kind === 'employee')
  .map(r => r.payload)
  // Ascending, so a device applying them in order ends on the newest state for
  // anyone who changed twice between polls.
  .sort((a, b) => (a.version || 0) - (b.version || 0));

const departments = changed
  .filter(r => r.kind === 'department')
  .map(r => r.payload)
  .sort((a, b) => (a.version || 0) - (b.version || 0));

// The watermark is the highest version AMONG THE ROWS BEING RETURNED, never
// the highest in the table.
//
// It used to be the table maximum, on the reasoning that a device receiving an
// empty page should still move forward. That reasoning was wrong, and it caused
// a real gap on 5 Sep 2026: a device polled while the table transiently held
// only one row (a broken upsert had overwritten the rest), received that single
// record, and took its version as the watermark. Every other record had a LOWER
// version, so `version > since` excluded all of them from then on - permanently,
// silently, with the pull reporting success every time.
//
// A watermark may only ever certify what was actually handed over. Staying put
// on an empty page costs nothing: with no rows above `since` the next poll
// simply returns empty again.
const version = changed.reduce((max, r) => (r.version > max ? r.version : max), since);

return [{
  json: {
    version,
    employees,
    departments,
    counts: {
      employees: employees.length,
      departments: departments.length,
      total: parsed.length,
    },
  },
}];
