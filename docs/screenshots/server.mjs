// Static server for the built SPA + a stub of the REST API, for the screenshot run.
//
// Screenshots need the real UI, not a mock of it — every component, style and layout here
// is the compiled `src/main/webapp`. What is stubbed is the backend: standing up Kafka,
// Flink and a seeded cluster just to take a picture is neither reproducible nor something
// a documentation build can do. The responses come from fixtures.mjs and are shaped
// against the frontend's own TypeScript contracts.
//
// Any route not listed answers 404 rather than an empty 200: a page that silently renders
// its "nothing here" state would produce a screenshot that quietly lies about the product.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import * as F from './fixtures.mjs';

const [, , siteDir, portArg] = process.argv;
if (!siteDir) {
  console.error('usage: node server.mjs <built-spa-dir> [port]');
  process.exit(2);
}
const PORT = Number(portArg ?? 4173);

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.map': 'application/json',
};

/** Routes are matched in order; the first predicate that answers wins. */
const API = [
  [m => m === '/api/dashboard', () => F.dashboard],
  [m => m === '/api/config', () => F.config],
  [m => m === '/api/cluster', () => F.cluster],
  [m => m === '/api/cluster/configs', () => ({ configs: {} })],
  [m => m === '/api/query/init', () => F.queryInit],
  [m => m === '/api/query/jobs', () => F.dashboard.jobs],
  [m => m === '/api/query/run-sync', () => F.queryResult],
  [m => m === '/api/query/validate', () => ({ valid: true })],
  [m => m.startsWith('/api/query/schema/'), () => F.topicDetail.schema],
  [m => m === '/api/stream-flow', () => F.streamFlow],
  [m => m === '/api/audit/last', () => F.auditReport],
  [m => m === '/api/audit/history', () => F.auditHistory],
  [m => m.startsWith('/api/topic/') && m.endsWith('/search'), () => F.topicSearch],
  [m => m.startsWith('/api/topic/') && m.endsWith('/consumers'), () => ({
    topic: 'demo.orders.5.shipped', groups: [], groupsExamined: 0, groupsInCluster: 0,
    truncated: false, warnings: [],
  })],
  [m => m.startsWith('/api/topic/'), () => F.topicDetail],
  [m => m === '/api/metrics', () => []],
  [m => m === '/api/metrics/templates', () => []],
];

const send = (res, status, body, type) => {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(body);
};

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const pathname = decodeURIComponent(url.pathname);

  // The Stream Flow page streams its hops: it reads POST /api/stream-flow/stream with
  // fetch + a ReadableStream and only falls back to the whole-result endpoint when the
  // body is not readable. Emitting one `result` frame is enough to land the finished
  // graph — the progress frames only animate a bar nobody sees in a still image.
  if (pathname === '/api/stream-flow/stream') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' });
    res.write(`event: result\ndata: ${JSON.stringify(F.streamFlow)}\n\n`);
    return res.end();
  }

  if (pathname.startsWith('/api/')) {
    const route = API.find(([match]) => match(pathname));
    if (!route) {
      console.warn(`  ! unstubbed API route: ${req.method} ${pathname}`);
      return send(res, 404, JSON.stringify({ message: `no stub for ${pathname}` }), MIME['.json']);
    }
    return send(res, 200, JSON.stringify(route[1]()), MIME['.json']);
  }

  // Static asset, else the SPA shell — SpaController does the same thing in production,
  // and without it a deep link like /topic/demo.orders.5.shipped would 404.
  const file = path.join(siteDir, pathname);
  if (pathname !== '/' && fs.existsSync(file) && fs.statSync(file).isFile()) {
    return send(res, 200, fs.readFileSync(file), MIME[path.extname(file)] ?? 'application/octet-stream');
  }
  send(res, 200, fs.readFileSync(path.join(siteDir, 'index.html')), MIME['.html']);
});

server.listen(PORT, '127.0.0.1', () => console.log(`stub server on http://127.0.0.1:${PORT}`));
