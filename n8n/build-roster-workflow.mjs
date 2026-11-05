// ═══════════════════════════════════════════════════════════════════════════
//  Generates roster.workflow.json - the roster sync half of the pipeline.
//      node build-roster-workflow.mjs
//
//  A SEPARATE workflow from the ADMS listener, on purpose. If a roster bug
//  takes a workflow down, attendance ingest must not go with it: punches are
//  the thing that can never stop, and they queue on the device only for as long
//  as the device survives. Separate workflows also mean this one can be
//  deactivated or edited mid-day without a single punch being lost.
//
//  Node shapes are copied from a verified live export (see
//  current-workflow.export.json), not guessed - with ONE exception, flagged
//  below on the upsert node.
// ═══════════════════════════════════════════════════════════════════════════
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const publishCode  = readFileSync(join(here, 'roster-publish-upsert.js'), 'utf8');
const responseCode = readFileSync(join(here, 'roster-response.js'), 'utf8');

const WEBHOOK_AUTH = 'headerAuth';

// Instance-specific, and deliberately included rather than left blank.
//
// A dangling credential id from another instance renders as an error. An
// ABSENT one renders as a webhook that quietly accepts anything, and these two
// endpoints can rewrite the roster of every terminal in the fleet. "Remember to
// attach the credential to both after importing" is not a control - it is a
// thing someone forgets once. Change the id if you move instances.
const HEADER_AUTH_CRED = {
  httpHeaderAuth: { id: 'iaGYiJpRuoQpE8cL', name: 'X-ZK-Secret' },
};

// Real ids, taken from the instance on 5 September 2026. Same project as
// zk_pending_sql, so the locator resolves on import and neither dataTable node
// needs picking by hand - which is worth doing, because picking the wrong table
// in one of two nodes fails silently rather than loudly.
const PROJECT_ID = 'XOPF8S5OUgPH3kAT';
const ROSTER_TABLE_ID = 'xjFpFkPxuQdvBa7K';

const rosterTable = () => ({
  __rl: true,
  value: ROSTER_TABLE_ID,
  mode: 'list',
  cachedResultName: 'zk_roster',
  cachedResultUrl: `/projects/${PROJECT_ID}/datatables/${ROSTER_TABLE_ID}`,
});

const rosterSchema = [
  { id: 'key',     displayName: 'key',     type: 'string' },
  { id: 'kind',    displayName: 'kind',    type: 'string' },
  { id: 'version', displayName: 'version', type: 'number' },
  { id: 'payload', displayName: 'payload', type: 'string' },
].map(c => ({
  ...c, required: false, defaultMatch: false, display: true,
  readOnly: false, removed: false,
}));

const nodes = [
  // ── Publish: Dataiku pushes the roster in ────────────────────────────────
  {
    id: 'wh-roster-publish',
    name: 'Roster Publish',
    type: 'n8n-nodes-base.webhook',
    typeVersion: 2.1,
    position: [-240, 260],
    webhookId: 'a1c0f6e2-0000-4000-8000-zkrosterpub0',
    parameters: {
      httpMethod: 'POST',
      path: 'zk/roster-publish',
      authentication: WEBHOOK_AUTH,
      responseMode: 'responseNode',
      options: { ipWhitelist: '' },
    },
    credentials: HEADER_AUTH_CRED,
    notes: 'Called by dataiku/publish_roster.py only. Never by a device.',
  },
  {
    id: 'code-roster-publish',
    name: 'Shape Roster Rows',
    type: 'n8n-nodes-base.code',
    typeVersion: 2,
    position: [20, 260],
    parameters: { mode: 'runOnceForAllItems', jsCode: publishCode },
  },
  {
    id: 'dt-roster-upsert',
    name: 'Upsert Roster',
    type: 'n8n-nodes-base.dataTable',
    typeVersion: 1,
    position: [280, 260],
    parameters: {
      operation: 'upsert',
      dataTableId: rosterTable(),

      // `eq`, and the whole feature turns on it.
      //
      // This block was missing from the first version of this file, so when the
      // node was opened in the UI n8n filled it in with its default comparison
      // - which is `neq`. That means "update every row whose key is NOT the one
      // I am writing", so the first record inserted and every record after it
      // overwrote the same row. Nine records published, one row in the table,
      // and no error anywhere: the workflow succeeded, Dataiku succeeded, and
      // eight people silently did not exist.
      //
      // matchingColumns below is not sufficient on its own. Both have to agree.
      filters: {
        conditions: [
          { keyName: 'key', condition: 'eq', keyValue: '={{ $json.key }}' },
        ],
      },
      columns: {
        mappingMode: 'defineBelow',
        value: {
          key:     '={{ $json.key }}',
          kind:    '={{ $json.kind }}',
          version: '={{ $json.version }}',
          payload: '={{ $json.payload }}',
        },
        // One row per person or department, replaced in place on every publish.
        matchingColumns: ['key'],
        schema: rosterSchema,
        attemptToConvertTypes: false,
        convertFieldsToString: false,
      },
      options: {},
    },
    alwaysOutputData: true,
    retryOnFail: true,
    maxTries: 2,
    waitBetweenTries: 1000,
  },
  {
    id: 'respond-roster-publish',
    name: 'Respond Publish',
    type: 'n8n-nodes-base.respondToWebhook',
    typeVersion: 1.5,
    position: [540, 260],
    parameters: {
      respondWith: 'json',
      responseBody: '={{ JSON.stringify({ ok: true }) }}',
      options: {},
    },
  },

  // ── Pull: a terminal asks what changed ───────────────────────────────────
  {
    id: 'wh-roster-pull',
    name: 'Roster Pull',
    type: 'n8n-nodes-base.webhook',
    typeVersion: 2.1,
    position: [-240, 480],
    webhookId: 'a1c0f6e2-0000-4000-8000-zkrosterget0',
    parameters: {
      httpMethod: 'GET',
      path: 'zk/roster',
      authentication: WEBHOOK_AUTH,
      responseMode: 'responseNode',
      options: { ipWhitelist: '' },
    },
    notes: 'Devices call this. roster-response.js reads ?since= from THIS node by name.',
    credentials: HEADER_AUTH_CRED,
  },
  {
    id: 'dt-roster-read',
    name: 'Read Roster',
    type: 'n8n-nodes-base.dataTable',
    typeVersion: 1,
    position: [20, 480],
    parameters: {
      operation: 'get',
      dataTableId: rosterTable(),
      // The whole roster, because filtering happens in the Code node: this node
      // has no sort and no comparison filter (confirmed against the live
      // export, where the equivalent Read Queue node has neither). Raise this if
      // the roster ever approaches it - a silently truncated roster would look
      // like people vanishing from terminals.
      limit: 2000,
    },
    // An empty table would otherwise emit zero items, n8n would skip every node
    // downstream, the Respond node would never fire, and the device's request
    // would hang until timeout - so "nothing has changed" would look like an
    // outage.
    alwaysOutputData: true,
  },
  {
    id: 'code-roster-response',
    name: 'Shape Roster Response',
    type: 'n8n-nodes-base.code',
    typeVersion: 2,
    position: [280, 480],
    parameters: { mode: 'runOnceForAllItems', jsCode: responseCode },
  },
  {
    id: 'respond-roster-pull',
    name: 'Respond Roster',
    type: 'n8n-nodes-base.respondToWebhook',
    typeVersion: 1.5,
    position: [540, 480],
    parameters: {
      respondWith: 'json',
      responseBody: '={{ JSON.stringify($json) }}',
      options: {},
    },
  },
];

const connections = {
  'Roster Publish':        { main: [[{ node: 'Shape Roster Rows', type: 'main', index: 0 }]] },
  'Shape Roster Rows':     { main: [[{ node: 'Upsert Roster', type: 'main', index: 0 }]] },
  'Upsert Roster':         { main: [[{ node: 'Respond Publish', type: 'main', index: 0 }]] },

  'Roster Pull':           { main: [[{ node: 'Read Roster', type: 'main', index: 0 }]] },
  'Read Roster':           { main: [[{ node: 'Shape Roster Response', type: 'main', index: 0 }]] },
  'Shape Roster Response': { main: [[{ node: 'Respond Roster', type: 'main', index: 0 }]] },
};

// Same guard as build-workflow.mjs. These two endpoints can rewrite the roster
// of every terminal in the fleet, so shipping one unauthenticated is worse than
// shipping nothing.
const unauthenticated = nodes.filter(
  n => n.type === 'n8n-nodes-base.webhook' &&
       (n.parameters.authentication !== 'headerAuth' || !n.credentials)
);
if (unauthenticated.length) {
  throw new Error(
    'refusing to write a workflow with unauthenticated webhooks: ' +
    unauthenticated.map(n => n.name).join(', ')
  );
}

const workflow = {
  name: 'ZK Roster Sync',
  nodes,
  connections,
  settings: { executionOrder: 'v1' },
};

const out = join(here, 'roster.workflow.json');
writeFileSync(out, JSON.stringify(workflow, null, 2));
console.log(
  `wrote roster.workflow.json  (${nodes.length} nodes, ` +
  `publish ${publishCode.length} bytes, response ${responseCode.length} bytes)`
);
