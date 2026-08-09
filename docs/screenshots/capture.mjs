// Drives Chromium over the stub-backed SPA and writes the documentation screenshots.
//
// Each screen is reached by URL rather than by clicking through the app: the pages already
// round-trip their whole state through the query string (a search, a trace, a SQL tab are
// all shareable links, and a shared link re-runs itself on open). Driving them that way
// means the capture exercises the same code path a user's shared link does, and it does
// not break the day a button moves.
//
// Usage: node capture.mjs <base-url> <out-dir>

import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

// playwright is not a dependency of this project — it is whatever the environment provides
// (the CI image, or a global install). Resolving it explicitly keeps the failure legible
// instead of an opaque MODULE_NOT_FOUND.
const require = createRequire(import.meta.url);
let chromium;
try {
  ({ chromium } = require('playwright'));
} catch {
  console.error('playwright not found. Install it (npm i -g playwright) and ensure a Chromium build is available.');
  process.exit(2);
}

const [, , baseUrl = 'http://127.0.0.1:4173', outDir = 'img'] = process.argv;

const VIEWPORT = { width: 1440, height: 900 };
const SCALE = 2;

const SQL = `-- Orders shipped above 50 EUR.
SELECT order_id, customer_id, status, amount_cents, currency
FROM demo_orders_5_shipped
WHERE amount_cents > 5000
LIMIT 50`;

/**
 * `anchor` scrolls the app's internal viewport so that element is in view before the shot.
 * Layout scrolls a container, not the window, so window.scrollTo would do nothing.
 */
const SCREENS = [
  {
    name: 'dashboard',
    url: '/',
    settle: page => page.getByText('TOTAL TOPICS').first().waitFor(),
  },
  {
    name: 'topic-explorer',
    // Runs the search on open, so the shot shows hits and their highlighting rather than
    // an empty form.
    url: '/topic/demo.orders.5.shipped?mode=CONTAINS&q=ORD-1042&dir=NEWEST',
    settle: page => page.getByText('demo.orders.5.shipped').first().waitFor(),
  },
  {
    name: 'sql-editor',
    url: `/query?sql=${encodeURIComponent(SQL)}`,
    settle: async page => {
      // Waiting on the catalogue rather than on the editor: the schema browser loads it on
      // mount, and "Engine connected" is the last thing that call turns on. This used to
      // need a click on the refresh control — the page had no mount-time fetch and opened
      // on "Engine offline · 0 tables · 0 topics" over a healthy engine.
      await page.getByText('Engine connected').waitFor();
      // Monaco mounts asynchronously; the run button is live before it has painted.
      await page.waitForTimeout(1200);
      await page.getByRole('button', { name: /run query/i }).click();
      await page.getByText('ORD-1042').first().waitFor();
    },
  },
  {
    name: 'stream-flow',
    url: '/stream-flow?key=ORD-1042&exact=1',
    // Node labels are truncated inside the SVG ("demo.orders.6.deliv…"), so the graph is
    // not a reliable anchor — the run's own completion notice is.
    settle: page => page.getByText(/Trace complete/).first().waitFor(),
    // The criteria panel takes a third of the width, and the graph fits its chain to what
    // is left — with the panel open every node is scaled down to illegibility. Collapsing
    // it is a real, persisted UI state (the rail keeps the traced key on screen), and it
    // is the state in which the graph is worth photographing.
    storage: { 'kse:flow-panel': '0', 'kse:flow-evidence': '52' },
  },
  {
    name: 'audit',
    url: '/audit',
    settle: page => page.getByText('HEALTH SCORE').first().waitFor(),
    // Past the checks panel and the past-runs card, onto the KPI row and the graded topics
    // table — the result of an audit, which is what the screen is about.
    scroll: 640,
  },
  {
    name: 'cluster',
    url: '/cluster',
    settle: page => page.getByText('KRaft Controller Quorum').first().waitFor(),
  },
];

/**
 * Scrolls the app's own scroll container by `px`.
 *
 * A wheel event over the content area, not `window.scrollTo`: Layout scrolls an inner
 * container, so the window never moves. Anchoring on a label was tried first and is not
 * worth the fragility — the labels are uppercased by CSS (the DOM says "Total Topics"
 * where the screen says "TOTAL TOPICS"), and several of them repeat across cards.
 */
async function scrollBy(page, px) {
  await page.mouse.move(VIEWPORT.width / 2, VIEWPORT.height / 2);
  await page.mouse.wheel(0, px);
  await page.waitForTimeout(400);
}

const failures = [];

const browser = await chromium.launch();
fs.mkdirSync(outDir, { recursive: true });

for (const screen of SCREENS) {
  const page = await browser.newPage({
    viewport: VIEWPORT,
    deviceScaleFactor: SCALE,
    // Fixes relative dates ("3 min ago") and any locale-dependent formatting, so a re-run
    // does not produce a different image from identical data.
    locale: 'en-GB',
    timezoneId: 'UTC',
    colorScheme: 'dark',
  });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

  // Seeded before any app script runs — these are persisted layout preferences the page
  // reads at first render, so setting them afterwards would need a reload anyway.
  if (screen.storage) {
    await page.addInitScript(entries => {
      for (const [k, v] of entries) localStorage.setItem(k, v);
    }, Object.entries(screen.storage));
  }

  try {
    await page.goto(baseUrl + screen.url, { waitUntil: 'networkidle', timeout: 30_000 });
    await screen.settle(page);
    if (screen.scroll) await scrollBy(page, screen.scroll);
    // Let transitions and the graph layout settle; a half-drawn edge is worse than a wait.
    await page.waitForTimeout(700);
    const file = path.join(outDir, `${screen.name}.png`);
    await page.screenshot({ path: file });
    const kb = Math.round(fs.statSync(file).size / 1024);
    console.log(`✓ ${screen.name}  ${kb} kB${errors.length ? `  (${errors.length} console error(s))` : ''}`);
  } catch (e) {
    // A screen that could not be reached must fail the run: silently shipping five of six
    // screenshots means the sixth quietly disappears from the documentation.
    failures.push(`${screen.name}: ${String(e).split('\n')[0]}`);
    console.error(`✗ ${screen.name}: ${String(e).split('\n')[0]}`);
  } finally {
    await page.close();
  }
}

await browser.close();

if (failures.length) {
  console.error(`\n${failures.length} screen(s) failed:\n  ${failures.join('\n  ')}`);
  process.exit(1);
}
console.log(`\nAll ${SCREENS.length} screens captured into ${outDir}/`);
