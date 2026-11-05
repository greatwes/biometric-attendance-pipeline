// Runs parse-adms-request.js behind a real HTTP server, exactly as the n8n
// workflow would, but printing the generated SQL instead of executing it.
//
//     node tools/local_test_server.mjs
//     python tools/simulate_device.py --base http://127.0.0.1:8099
//
// Use this to separate "is my parser right?" from "is n8n/Snowflake right?".
// You can also point the real terminal at it during commissioning to see the
// raw traffic without writing anything to the warehouse.
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const code = readFileSync(join(here, '..', 'n8n', 'parse-adms-request.js'), 'utf8');
const parse = new Function('$input', code);

const PORT = Number(process.env.PORT || 8099);
const SHOW_SQL = process.env.SHOW_SQL !== '0';

createServer((req, res) => {
  const chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => {
    const raw = Buffer.concat(chunks);
    const url = new URL(req.url, 'http://localhost');
    const query = Object.fromEntries(url.searchParams.entries());

    // Mirror the n8n Webhook node with Raw Body enabled.
    const item = {
      // n8n gives the parser its endpoint via the route params; here it comes
      // from the path, so /iclock/getrequest is answered as getrequest rather
      // than being mistaken for a cdata handshake.
      json: {
        method: req.method,
        query,
        params: { endpoint: url.pathname.split('/').filter(Boolean).pop() || 'cdata' },
        headers: req.headers,
      },
      binary: raw.length ? { data: { data: raw.toString('base64') } } : undefined,
    };

    let out;
    try {
      out = parse({ first: () => item });
    } catch (e) {
      console.error(`\n!! parser threw on ${req.method} ${req.url}\n   ${e.stack}\n`);
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      return res.end('ERROR\r\n');
    }

    const meta = out[0].json;
    const stamp = new Date().toISOString().slice(11, 19);
    console.log(
      `${stamp}  ${req.method.padEnd(4)} ${url.pathname}${url.search}\n` +
      `          route=${meta.route} sn=${meta.deviceSn || '-'} ` +
      `lines=${meta.lines ?? 0} accepted=${meta.accepted ?? 0} ` +
      `skipped=${meta.skipped ?? 0} sql=${meta.sqlCount}`
    );

    if (SHOW_SQL) {
      for (const o of out) {
        if (!o.json.sql) continue;
        const first = o.json.sql.split('\n')[0];
        console.log(`          -> ${first.slice(0, 110)}${first.length > 110 ? '…' : ''}`);
      }
    }

    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(meta.responseText);
  });
}).listen(PORT, () => {
  console.log(`\nADMS test server on http://0.0.0.0:${PORT}`);
  console.log(`Point the device's Cloud Server at this host:${PORT}, or run:`);
  console.log(`  python tools/simulate_device.py --base http://127.0.0.1:${PORT}\n`);
});
