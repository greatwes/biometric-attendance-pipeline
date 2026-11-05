// ═══════════════════════════════════════════════════════════════════════════
//  GET /webhook/zk/pending  ·  n8n Code node
//  Sits between the Data table Get and the Respond node.
//  Language: JavaScript · Mode: "Run Once for All Items"
//
//  Shapes queued rows into the JSON dataiku/drain_queue.py expects.
// ═══════════════════════════════════════════════════════════════════════════

const rows = $input.all()
  .map(item => item.json)
  .filter(row => row && row.sql);

// Insertion order is load-bearing. The parser emits an EMPLOYEES placeholder
// merge before the BIOMETRIC_TEMPLATES row that references that PIN, so
// replaying them out of order writes a template for a person who does not
// exist yet. The Data table Get is configured to sort by id ascending, but
// sort again here: a mis-set option in the UI is invisible until it corrupts
// something, and this costs nothing.
rows.sort((a, b) => Number(a.id) - Number(b.id));

return [{
  json: {
    rows: rows.map(row => ({ id: row.id, sql: row.sql })),
    count: rows.length,
  },
}];
