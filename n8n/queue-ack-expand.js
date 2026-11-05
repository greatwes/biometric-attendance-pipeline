// ═══════════════════════════════════════════════════════════════════════════
//  POST /webhook/zk/ack  ·  n8n Code node
//  Sits between the guard and the Data table Delete.
//  Language: JavaScript · Mode: "Run Once for All Items"
//
//  Fans {"ids":[1,2,3]} out to one item per id, because the Delete node runs
//  once per input item and deletes what its condition matches.
// ═══════════════════════════════════════════════════════════════════════════

const first = $input.first().json;
// Depending on the webhook's body parsing the payload arrives either under
// .body or at the top level. Accept both rather than depending on which.
const body = first.body || first;

const ids = Array.isArray(body.ids) ? body.ids : [];

// n8n does not run a node that receives zero items, so an empty ack would
// leave the Respond node unreached and the caller's HTTP request hanging
// until it times out. One sentinel item keeps the branch alive; it carries a
// null id, which matches no row, so nothing is deleted.
if (!ids.length) {
  return [{ json: { id: null, noop: true } }];
}

return ids.map(id => ({ json: { id } }));
