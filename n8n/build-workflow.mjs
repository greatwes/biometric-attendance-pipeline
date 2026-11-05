// Regenerates adms-listener.workflow.json, embedding parse-adms-request.js
// verbatim into the Code node. Run after editing the parser:
//     node build-workflow.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const parserCode  = readFileSync(join(here, 'parse-adms-request.js'), 'utf8');
const pendingCode = readFileSync(join(here, 'queue-pending-response.js'), 'utf8');
const ackCode     = readFileSync(join(here, 'queue-ack-expand.js'), 'utf8');

// Authentication is the webhook node's built-in Header Auth, not the Code node
// in verify-secret.js. That file is kept for reference but is no longer wired
// in: this instance cannot read $env from a Code node - either the variable is
// not on the process or N8N_BLOCK_ENV_ACCESS_IN_NODE is set - so the guard
// threw "unset or too short" on every request and refused everything.
//
// Header Auth is the better mechanism anyway. It rejects at the edge before
// the workflow runs, and the secret lives in an encrypted n8n credential
// instead of an environment variable that a Code node has to be trusted with.
//
// The credential itself is deliberately not referenced here: a hardcoded
// credential id points at nothing in your instance and renders as an error
// that reads like a bigger problem than "pick one from the dropdown". After
// importing, set Authentication -> Header Auth on all four webhook nodes and
// select the X-ZK-Secret credential.
const WEBHOOK_AUTH = 'headerAuth';

// ── Shapes verified against a live export, 4 September 2026 ────────────────
// Everything below was previously a guess, and the guesses were wrong in ways
// that looked fine in the JSON and broken in the UI. See
// current-workflow.export.json for the source of truth.
//
// dataTableId is a RESOURCE LOCATOR object, not a table name. Passing the bare
// string 'zk_pending_sql' is what made the table dropdown render blank and
// unselectable - not project scoping and not the OAuth proxy, both of which
// were guessed at while debugging that for far too long.
//
// The id is instance-specific. That is the trade: a dangling id from another
// instance renders as an error, but a name renders as nothing at all, which is
// worse because it looks like the node is simply unconfigured.
const PROJECT_ID = 'XOPF8S5OUgPH3kAT';
const QUEUE_TABLE_ID = 'Udq1h8FqwPW9ZBmN';

const queueTable = () => ({
  __rl: true,
  value: QUEUE_TABLE_ID,
  mode: 'list',
  cachedResultName: 'zk_pending_sql',
  cachedResultUrl: `/projects/${PROJECT_ID}/datatables/${QUEUE_TABLE_ID}`,
});

// The column mapper needs this schema block alongside the values. Without it
// the mapping panel comes up empty even when every expression is correct.
const queueSchema = [
  { id: 'sql',       displayName: 'sql',       type: 'string' },
  { id: 'kind',      displayName: 'kind',      type: 'string' },
  { id: 'device_sn', displayName: 'device_sn', type: 'string' },
  { id: 'sql_index', displayName: 'sql_index', type: 'number' },
].map(c => ({
  ...c, required: false, defaultMatch: false, display: true,
  readOnly: false, removed: false,
}));

const RESPOND_TEXT = "={{ $('Parse ADMS Request').first().json.responseText }}";

const nodes = [
  {
    id: 'wh-cdata',
    name: 'ADMS cdata',
    type: 'n8n-nodes-base.webhook',
    typeVersion: 2.1,
    position: [-220, 300],
    webhookId: 'a1c0f6e2-0000-4000-8000-zkadmscdata0',
    parameters: {
      authentication: WEBHOOK_AUTH,
      multipleMethods: true,
      httpMethod: ['GET', 'POST'],
      path: 'iclock/cdata',
      responseMode: 'responseNode',
      options: {
        // The payload is tab-separated text, not JSON. Without rawBody n8n
        // tries to parse it and the body arrives mangled or empty.
        rawBody: true,
        // Left empty on purpose: the terminals are on mobile data and hotspots,
        // so their addresses change constantly and an IP allowlist would either
        // be wide open or lock out the field. Authentication is the Verify
        // Secret node below, not the network boundary.
        ipWhitelist: '',
      },
    },
    notes: 'GET = handshake (must return the OPTIONS block). POST = data push.',
  },
  {
    id: 'code-parse',
    name: 'Parse ADMS Request',
    type: 'n8n-nodes-base.code',
    typeVersion: 2,
    position: [20, 300],
    parameters: { mode: 'runOnceForAllItems', jsCode: parserCode },
  },
  {
    id: 'if-write',
    name: 'Needs DB Write?',
    type: 'n8n-nodes-base.if',
    typeVersion: 2.2,
    position: [260, 300],
    parameters: {
      conditions: {
        options: { caseSensitive: true, leftValue: '', typeValidation: 'loose', version: 2 },
        combinator: 'and',
        conditions: [
          {
            id: 'has-sql',
            operator: { type: 'number', operation: 'gt' },
            leftValue: '={{ $json.sqlCount }}',
            rightValue: 0,
          },
        ],
      },
      options: {},
    },
  },
  {
    // Was a Snowflake node. n8n cannot reach Snowflake: the developer's access
    // is SSO, PAT auth is refused without a network policy, and a key pair on
    // their own user needs OWNERSHIP. The only usable credential is the
    // per-user OAuth2 token inside the DSS Snowflake connection, which DSS
    // will not hand out - so n8n parks the statement here and the Dataiku
    // scenario in dataiku/drain_queue.py executes it. Swap this node back to
    // Snowflake the day a service account exists; nothing else changes.
    id: 'dt-queue',
    name: 'Queue for Dataiku',
    type: 'n8n-nodes-base.dataTable',
    typeVersion: 1,
    position: [500, 200],
    parameters: {
      // No `resource` and no `operation`: insert is this node's default, and
      // the working instance has neither key. Adding them was harmless here but
      // is exactly the kind of invented field that makes a diff untrustworthy.
      dataTableId: queueTable(),
      // The parser emits one item per statement, each carrying the full meta
      // block, so this node runs once per statement and one queue row is one
      // statement.
      columns: {
        mappingMode: 'defineBelow',
        value: {
          sql:       '={{ $json.sql }}',
          kind:      '={{ $json.route }}',
          device_sn: '={{ $json.deviceSn }}',
          sql_index: '={{ $json.sqlIndex }}',
        },
        matchingColumns: [],
        schema: queueSchema,
        attemptToConvertTypes: false,
        convertFieldsToString: false,
      },
      options: {},
    },
    // If the insert fails we deliberately let the workflow fail: n8n returns
    // 500, the device sees a non-OK response and re-sends the batch on its
    // next cycle. continueOnFail here would silently lose punches.
    alwaysOutputData: true,
    retryOnFail: true,
    maxTries: 2,
    waitBetweenTries: 1000,
    notes: 'Confirm the table and column mapping in the UI after import - see README-queue.md.',
  },
  {
    id: 'respond-ok',
    name: 'Respond to Device',
    type: 'n8n-nodes-base.respondToWebhook',
    typeVersion: 1.5,
    position: [760, 300],
    parameters: {
      respondWith: 'text',
      responseBody: RESPOND_TEXT,
      options: {
        responseHeaders: {
          entries: [{ name: 'Content-Type', value: 'text/plain; charset=utf-8' }],
        },
      },
    },
  },
  {
    id: 'wh-getrequest',
    name: 'ADMS getrequest',
    type: 'n8n-nodes-base.webhook',
    typeVersion: 2.1,
    position: [-220, 560],
    webhookId: 'a1c0f6e2-0000-4000-8000-zkadmsgetreq',
    parameters: {
      authentication: WEBHOOK_AUTH,
      httpMethod: 'GET',
      path: 'iclock/getrequest',
      responseMode: 'responseNode',
      options: { ipWhitelist: '' },
    },
    notes: 'Device polls this every Delay seconds for pending commands. "OK" = nothing queued.',
  },
  {
    id: 'wh-devicecmd',
    name: 'ADMS devicecmd',
    type: 'n8n-nodes-base.webhook',
    typeVersion: 2.1,
    position: [-220, 720],
    webhookId: 'a1c0f6e2-0000-4000-8000-zkadmsdevcmd',
    parameters: {
      authentication: WEBHOOK_AUTH,
      httpMethod: 'POST',
      path: 'iclock/devicecmd',
      responseMode: 'responseNode',
      options: { rawBody: true, ipWhitelist: '' },
    },
    notes: 'Command acknowledgement. Must return OK or the device retries the command forever.',
  },
  {
    id: 'respond-plain',
    name: 'Respond OK',
    type: 'n8n-nodes-base.respondToWebhook',
    typeVersion: 1.5,
    position: [260, 640],
    parameters: {
      respondWith: 'text',
      responseBody: 'OK\r\n',
      options: {
        responseHeaders: {
          entries: [{ name: 'Content-Type', value: 'text/plain; charset=utf-8' }],
        },
      },
    },
  },

  // ── Queue drain: the Dataiku scenario pulls from here ────────────────────
  // Pull, not push. DSS holds the only working Snowflake credential but
  // cannot be called into on a schedule we control, so it reaches out over
  // plain HTTP instead. No Dataiku write APIs involved.
  {
    id: 'wh-pending',
    name: 'Queue Pending',
    type: 'n8n-nodes-base.webhook',
    typeVersion: 2.1,
    position: [-220, 900],
    webhookId: 'a1c0f6e2-0000-4000-8000-zkqueuepend0',
    parameters: {
      authentication: WEBHOOK_AUTH,
      httpMethod: 'GET',
      path: 'zk/pending',
      responseMode: 'responseNode',
      options: { ipWhitelist: '' },
    },
    notes: 'Dataiku polls this. Same X-ZK-Secret credential as the device uses.',
  },
  {
    id: 'dt-pending',
    name: 'Read Queue',
    type: 'n8n-nodes-base.dataTable',
    typeVersion: 1,
    position: [260, 900],
    parameters: {
      operation: 'get',
      dataTableId: queueTable(),
      limit: 500,
      // NOTE: there is no sort option on this node. Earlier versions of this
      // file set options.orderBy/sortOrder and README-queue.md told you to
      // "sort by id ascending" in the UI - neither exists. Row order here is
      // whatever the node returns.
      //
      // That makes the re-sort in queue-pending-response.js load-bearing, not
      // the belt-and-braces check it was described as. Order matters because
      // the parser emits an EMPLOYEES placeholder before the
      // BIOMETRIC_TEMPLATES row that references that PIN; replayed out of
      // order, the template lands for a person who does not exist yet. Do not
      // remove that sort.
    },
    // Without this an empty queue emits zero items, n8n skips every node
    // downstream, the Respond node never fires and the scenario's request
    // hangs until timeout - so "queue is empty" would look like an outage.
    alwaysOutputData: true,
  },
  {
    id: 'code-pending',
    name: 'Shape Pending',
    type: 'n8n-nodes-base.code',
    typeVersion: 2,
    position: [500, 900],
    parameters: { mode: 'runOnceForAllItems', jsCode: pendingCode },
  },
  {
    id: 'respond-pending',
    name: 'Respond Pending',
    type: 'n8n-nodes-base.respondToWebhook',
    typeVersion: 1.5,
    position: [740, 900],
    parameters: { respondWith: 'json', responseBody: '={{ JSON.stringify($json) }}', options: {} },
  },

  {
    id: 'wh-ack',
    name: 'Queue Ack',
    type: 'n8n-nodes-base.webhook',
    typeVersion: 2.1,
    position: [-220, 1080],
    webhookId: 'a1c0f6e2-0000-4000-8000-zkqueueack00',
    parameters: {
      authentication: WEBHOOK_AUTH,
      httpMethod: 'POST',
      path: 'zk/ack',
      responseMode: 'responseNode',
      options: { ipWhitelist: '' },
    },
    notes: 'Dataiku calls this only after Snowflake accepted the rows.',
  },
  {
    id: 'code-ack',
    name: 'Expand Ids',
    type: 'n8n-nodes-base.code',
    typeVersion: 2,
    position: [260, 1080],
    parameters: { mode: 'runOnceForAllItems', jsCode: ackCode },
  },
  {
    id: 'dt-ack',
    name: 'Delete Acked',
    type: 'n8n-nodes-base.dataTable',
    typeVersion: 1,
    position: [500, 1080],
    parameters: {
      operation: 'deleteRows',
      dataTableId: queueTable(),
      // The live node carries only keyValue - keyName and condition were
      // invented. The key column is implied.
      filters: {
        conditions: [{ keyValue: '={{ $json.id }}' }],
      },
      options: {},
    },
    alwaysOutputData: true,
  },
  {
    id: 'respond-ack',
    name: 'Respond Ack',
    type: 'n8n-nodes-base.respondToWebhook',
    typeVersion: 1.5,
    position: [740, 1080],
    parameters: { respondWith: 'text', responseBody: 'OK', options: {} },
  },
];

// Authentication happens on the webhook nodes themselves now, so each path
// runs straight from its webhook. The poll endpoints still need their own
// terminal response: sending getrequest through the parser would answer it
// with a cdata handshake body instead of "OK", and the device then retries its
// command forever.
const connections = {
  // multipleMethods gives this node ONE OUTPUT PER METHOD, in the order they
  // are listed in httpMethod: output 0 = GET, output 1 = POST. Both must be
  // connected. Wiring only output 0 - which this file did until 4 Sep 2026 -
  // passes the GET handshake through and silently drops every POST, so the
  // device gets its OPTIONS block, believes the server is healthy, and then
  // has every punch upload stop dead at the webhook node. The execution log
  // shows "ADMS cdata" succeeding on its own with nothing downstream, which
  // reads like the workflow ended normally rather than like a broken wire.
  'ADMS cdata':        { main: [
                          [{ node: 'Parse ADMS Request', type: 'main', index: 0 }],  // GET
                          [{ node: 'Parse ADMS Request', type: 'main', index: 0 }],  // POST
                        ] },
  'Parse ADMS Request':{ main: [[{ node: 'Needs DB Write?',      type: 'main', index: 0 }]] },
  'Needs DB Write?':   { main: [
                          [{ node: 'Queue for Dataiku',  type: 'main', index: 0 }],  // true
                          [{ node: 'Respond to Device',  type: 'main', index: 0 }],  // false (handshake)
                        ] },
  'Queue for Dataiku': { main: [[{ node: 'Respond to Device', type: 'main', index: 0 }]] },

  'ADMS getrequest':   { main: [[{ node: 'Respond OK', type: 'main', index: 0 }]] },
  'ADMS devicecmd':    { main: [[{ node: 'Respond OK', type: 'main', index: 0 }]] },

  // Queue drain.
  'Queue Pending':     { main: [[{ node: 'Read Queue',      type: 'main', index: 0 }]] },
  'Read Queue':        { main: [[{ node: 'Shape Pending',   type: 'main', index: 0 }]] },
  'Shape Pending':     { main: [[{ node: 'Respond Pending', type: 'main', index: 0 }]] },

  'Queue Ack':         { main: [[{ node: 'Expand Ids',   type: 'main', index: 0 }]] },
  'Expand Ids':        { main: [[{ node: 'Delete Acked', type: 'main', index: 0 }]] },
  'Delete Acked':      { main: [[{ node: 'Respond Ack',  type: 'main', index: 0 }]] },
};

// Attach Header Auth to every webhook, mechanically.
//
// This file used to leave credentials off entirely, on the reasoning that a
// dangling credential id renders as a confusing error. That reasoning was
// wrong, and dangerously so: an ABSENT credential renders as a webhook that
// quietly accepts anything, and these five are on the public internet where
// they can inject attendance records. A visible error is strictly better than
// a silent hole.
//
// Done as a map over the node list rather than a line on each node, because
// "remember to add it to all five" is precisely the kind of instruction that
// gets followed four times.
const HEADER_AUTH_CRED = {
  httpHeaderAuth: { id: 'iaGYiJpRuoQpE8cL', name: 'X-ZK-Secret' },
};

for (const node of nodes) {
  if (node.type === 'n8n-nodes-base.webhook') {
    node.credentials = HEADER_AUTH_CRED;
  }
}

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
  name: 'ZK ADMS Listener (Horus H1)',
  nodes,
  connections,
  settings: {
    executionOrder: 'v1',
    // Every punch creates an execution. At ~200 staff that is thousands of
    // rows a day in the n8n execution log; keep only failures.
    saveDataSuccessExecution: 'none',
    saveDataErrorExecution: 'all',
    saveManualExecutions: true,
    executionTimeout: 30,
    timezone: 'Africa/Lagos',
  },
};

writeFileSync(
  join(here, 'adms-listener.workflow.json'),
  JSON.stringify(workflow, null, 2) + '\n',
  'utf8'
);
console.log('wrote adms-listener.workflow.json  (%d nodes, parser %d bytes)',
  nodes.length, parserCode.length);
