// ═══════════════════════════════════════════════════════════════════════════
//  NOT IN USE as of 4 September 2026. Kept for reference only.
//
//  The n8n instance at automations.example.org cannot read $env from a
//  Code node - either ZK_SHARED_SECRET never reached the process or
//  N8N_BLOCK_ENV_ACCESS_IN_NODE is set - so line 59 below fired on every
//  request and refused everything, legitimate traffic included.
//
//  Authentication is now the webhook node's built-in Header Auth, configured
//  on all five webhooks against an X-ZK-Secret credential. That rejects at the
//  edge before the workflow runs and keeps the secret in encrypted credential
//  storage, which is better than this was. Only revive this file if you need a
//  check the webhook node cannot express - and confirm $env is readable first.
// ───────────────────────────────────────────────────────────────────────────
//  X-ZK-Secret guard  ·  n8n Code node
//  Place it IMMEDIATELY after the Webhook node, BEFORE parse-adms-request.js.
//  Language: JavaScript · Mode: "Run Once for All Items"
//
//  Without this node the webhook is an unauthenticated, publicly reachable
//  endpoint: automations.example.org/webhook/* has no login, so anyone who
//  guesses the path can inject attendance records - and once roster sync is
//  live, read and write fingerprint templates, which are special-category
//  personal data under NDPR and GDPR.
//
//  The app has always sent the header. Until this node exists it is decoration.
// ═══════════════════════════════════════════════════════════════════════════

// The expected value comes from the n8n environment, never from the workflow
// JSON - the JSON is committed to the repo and a secret in it is a secret
// published. Set ZK_SHARED_SECRET on the n8n instance, and the identical value
// in the app under Admin -> Settings -> Shared secret.
const expected = $env.ZK_SHARED_SECRET;

// n8n lower-cases incoming header names, but that is not guaranteed across
// versions or proxies, so match case-insensitively rather than trusting it.
function header(item, name) {
  const headers = (item && item.json && item.json.headers) || {};
  const wanted = name.toLowerCase();
  for (const key of Object.keys(headers)) {
    if (key.toLowerCase() === wanted) return headers[key];
  }
  return undefined;
}

// Constant-time comparison. A plain !== leaks how many leading characters were
// correct through response timing, which turns a 32-character secret into a
// character-at-a-time guessing game. Written by hand because the Code node
// sandbox only exposes Node built-ins when NODE_FUNCTION_ALLOW_BUILTIN is set,
// so require('crypto').timingSafeEqual cannot be relied on here.
function constantTimeEquals(a, b) {
  const left = String(a === undefined || a === null ? '' : a);
  const right = String(b === undefined || b === null ? '' : b);

  // Compare over a fixed length so the loop count does not reveal the lengths.
  const length = Math.max(left.length, right.length);
  let diff = left.length ^ right.length;
  for (let i = 0; i < length; i++) {
    diff |= left.charCodeAt(i % (left.length || 1)) ^
            right.charCodeAt(i % (right.length || 1));
  }
  return diff === 0 && left.length === right.length;
}

const items = $input.all();
const supplied = header(items[0], 'x-zk-secret');

// Fail closed. A missing ZK_SHARED_SECRET must reject everything rather than
// silently accept everything, which is what `!expected || ...` in the original
// sketch did on the happy path but is worth being explicit about: an instance
// that loses its env var must break loudly, not quietly stop authenticating.
if (!expected || String(expected).length < 16) {
  throw new Error(
    'ZK_SHARED_SECRET is unset or too short on this n8n instance - refusing all requests'
  );
}

if (!constantTimeEquals(supplied, expected)) {
  // Deliberately identical message for "missing" and "wrong". Distinguishing
  // them tells an attacker whether the header name is even right.
  throw new Error('rejected: bad or missing X-ZK-Secret');
}

return items;
