// Measures how the SPA lays out at a given viewport, over the same stub-backed server the
// screenshots use.
//
// It exists because "the page has no mobile story" was an assertion nobody had measured, and a
// scoping decision made on an assertion is a guess. It reports, per page and per width: whether
// the document scrolls horizontally, which containers clip their content without a way to reach
// the rest, how many interactive targets are below the 24x24 CSS px of WCAG 2.5.8, and — on the
// SQL editor, where the question is sharpest — the width actually left to the Monaco editor.
//
// Numbers, not screenshots: a screenshot shows that something is wrong, a measurement says how
// wrong and at which width it stops being wrong. MOBILE-LAYOUT-SCOPE.md is written from this
// output, and re-running it is how that document is kept honest.
//
// Usage, with the fixture server already running (see README.md):
//   node server.mjs /tmp/kse-site 4173 &
//   node layout-probe.mjs http://127.0.0.1:4173
//   node layout-probe.mjs http://127.0.0.1:4173 --sweep   # editor width vs viewport width
//   node layout-probe.mjs http://127.0.0.1:4173 --detail  # names the containers that clip

import { createRequire } from 'node:module';

// Same resolution as capture.mjs: playwright is whatever the environment provides, and an
// explicit failure beats an opaque MODULE_NOT_FOUND.
const require = createRequire(import.meta.url);
let chromium;
try {
  ({ chromium } = require('playwright'));
} catch {
  console.error('playwright not found. Install it (npm i -g playwright) and ensure a Chromium build is available.');
  process.exit(2);
}

const [, , baseUrl = 'http://127.0.0.1:4173', mode] = process.argv;

const SQL = 'SELECT order_id, status FROM demo_orders_1_received LIMIT 50';

/** The screens an operator actually opens, reached by URL like the capture does. */
const PAGES = [
  { name: 'dashboard', url: '/' },
  { name: 'sql-editor', url: `/query?sql=${encodeURIComponent(SQL)}`, settleMs: 2500 },
  { name: 'topic-explorer', url: '/topic/demo.orders.5.shipped?mode=CONTAINS&q=ORD-1042&dir=NEWEST' },
  { name: 'stream-flow', url: '/stream-flow?key=ORD-1042&exact=1' },
  // Like the capture, reached by the query string a shared link carries — the selection replays
  // on open. Given longer to settle than the default: the page POSTs the model and only then
  // lays the graph out and fits it to the viewport, and measuring it mid-fit would report a
  // framing that never reaches the screen.
  {
    name: 'data-model',
    url: '/data-model?topics=' + encodeURIComponent(
      ['demo.customers', 'demo.orders.1.received',
       'demo.payments.authorized', 'demo.shipments.dispatched'].join(',')),
    settleMs: 2500,
  },
  { name: 'audit', url: '/audit' },
  { name: 'metrics', url: '/metrics' },
  { name: 'cluster', url: '/cluster' },
];

/*
 * Ceilings for `--check`, the mode CI runs. This is W6 from MOBILE-LAYOUT-SCOPE.md: without it,
 * every number in that document is a reading from whenever someone last remembered to take one.
 *
 * **What is gated, and what deliberately is not.** Two things are asserted hard: that no page
 * scrolls sideways at any width, and that no page carries more sub-24px targets than it does
 * today. Both come from element sizes set by utility classes, so they are the same on any
 * machine. Clipping and unreachability are *reported* and not gated, because they turn on text
 * metrics — the same string wraps differently under a different font stack, so a ceiling set
 * here would fail on a runner for a reason that has nothing to do with the change under test.
 * A gate with false positives is a gate people learn to re-run until it passes, which is worse
 * than no gate at all.
 *
 * The target ceilings are the measured maximum across the three viewports, so they are a "no
 * worse than today" line rather than a target. Lower them when a page improves — the point is
 * that the number cannot drift upward unnoticed.
 */
const TARGET_BUDGET = {
  'dashboard': 32,
  'sql-editor': 72,
  'topic-explorer': 11,
  'stream-flow': 20,
  'data-model': 9,
  'audit': 24,
  'metrics': 8,
  'cluster': 1,
};

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },   // iPhone 14 class
  { name: 'tablet', width: 768, height: 1024 }, // exactly the `md` breakpoint
  { name: 'desktop', width: 1440, height: 900 },
];

/** Widths for the sweep: either side of every Tailwind breakpoint the app uses. */
const SWEEP_WIDTHS = [390, 480, 640, 768, 900, 1024, 1180, 1280, 1440];

/*
 * Runs in the page. Everything here is measured from the rendered DOM — no assumption about
 * which element is which is made from the source.
 */
const MEASURE = () => {
  const widthOf = el => (el ? Math.round(el.getBoundingClientRect().width) : null);

  // The first <aside> is the shell's navigation drawer; the editor's schema browser is the one
  // carrying its own resize handle.
  const schemaBrowser = [...document.querySelectorAll('aside')]
    .find(a => a.querySelector('[aria-label="Resize the schema browser"]'));

  /*
   * A strip that scrolls is a deliberate choice, not a defect — only containers whose overflow-x
   * is `visible` or `hidden` are counted, i.e. those that spill or clip with no way to reach the
   * rest of their content.
   */
  /*
   * `sr-only` clips by construction — it is a 1px box whose whole purpose is to carry text to a
   * screen reader and not to the eye. Counting it as "content cut off" says the opposite of what
   * is true, so it is excluded before anything else.
   */
  const isScreenReaderOnly = el => String(el.className).split(/\s+/).includes('sr-only');

  /*
   * Whether the rest of a clipped element's content can still be reached, which is the only
   * question that matters: `truncate` plus a `title` carrying the full value is a deliberate
   * pattern this codebase uses everywhere (the exact number behind `1.2K`, the absolute date
   * behind a relative one), and so is a cell inside a strip that scrolls. What has neither is the
   * finding.
   */
  const reachability = el => {
    const title = el.getAttribute('title');
    if (title && title.trim().length > 0) return 'title';
    if (el.querySelector('[title]')) return 'title-inside';
    for (let a = el.parentElement; a; a = a.parentElement) {
      const ox = getComputedStyle(a).overflowX;
      if ((ox === 'auto' || ox === 'scroll') && a.scrollWidth > a.clientWidth + 2) return 'scrollable-ancestor';
      const at = a.getAttribute('title');
      if (at && at.trim().length > 0) return 'title-ancestor';
    }
    return 'none';
  };

  const clippedAll = [...document.querySelectorAll('*')]
    .filter(el => {
      if (el.clientWidth === 0 || el.scrollWidth <= el.clientWidth + 2) return false;
      if (isScreenReaderOnly(el)) return false;
      const overflowX = getComputedStyle(el).overflowX;
      return overflowX === 'visible' || overflowX === 'hidden';
    });
  /*
   * The count is the whole set; the sample is what a reader can act on. These used to be the same
   * array, cut to 8 before being counted — so every page reported "8 clipped" at every width, and
   * MOBILE-LAYOUT-SCOPE.md read that cap as a measurement. A number that is silently a ceiling is
   * worse than no number: it looked like a constant, which is exactly what invited the reading
   * "not a narrow-width regression".
   */
  const clippedCount = clippedAll.length;
  const describe = el => ({
    tag: el.tagName.toLowerCase(),
    cls: String(el.className).slice(0, 60),
    id: el.id || null,
    aria: el.getAttribute('aria-label'),
    reach: reachability(el),
    text: (el.textContent || '').trim().slice(0, 40),
    scrollW: el.scrollWidth,
    clientW: el.clientWidth,
  });
  const unreachable = clippedAll.filter(el => reachability(el) === 'none');
  const clipped = unreachable.slice(0, 8).map(describe);
  const clippedSample = clippedAll.slice(0, 4).map(describe);

  const targets = [...document.querySelectorAll('button, [role="button"], a, input, select')]
    .map(el => {
      const r = el.getBoundingClientRect();
      return {
        label: (el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 30),
        w: Math.round(r.width),
        h: Math.round(r.height),
      };
    })
    .filter(t => t.w > 0 && t.h > 0);
  const tooSmall = targets.filter(t => t.w < 24 || t.h < 24);

  return {
    winW: window.innerWidth,
    // The rule the whole app already respects: the body must never scroll sideways.
    bodyOverflows: document.documentElement.scrollWidth > window.innerWidth + 1,
    monacoW: widthOf(document.querySelector('.monaco-editor')),
    schemaBrowserW: widthOf(schemaBrowser),
    clipped,
    clippedSample,
    clippedCount,
    unreachableCount: unreachable.length,
    targets: targets.length,
    tooSmall: tooSmall.length,
    tooSmallSample: tooSmall.slice(0, 4).map(t => `${t.label} (${t.w}x${t.h})`),
  };
};

const newPage = (browser, width, height, touch, storage) => browser.newPage({
  viewport: { width, height },
  locale: 'en-GB',
  timezoneId: 'UTC',
  colorScheme: 'dark',
  hasTouch: touch,
  isMobile: touch && width < 500,
  storageState: storage,
});

/*
 * `CHROMIUM_PATH` points at a Chromium that is already there — the same escape hatch
 * `capture.mjs` carries, for the same failure. Playwright looks for its browsers under the
 * version *it* expects, so on an image that supplies the browser (`PLAYWRIGHT_BROWSERS_PATH`)
 * while playwright was installed separately, the two build numbers diverge and the launch fails
 * demanding a download the image forbids. This file launched bare and so could not run there at
 * all, which is the whole reason its numbers are supposed to be re-measured rather than trusted.
 * Unset, Playwright's own lookup is unchanged and CI needs nothing.
 */
const browser = await chromium.launch(
  process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});

/** Filled by the page walk below, and read by `--check` once the browser is closed. */
const checkResults = [];

if (mode === '--sweep') {
  /*
   * The editor against the width it is given, in two states: as it opens, and tuned as far as a
   * user can tune it today (window assistant folded, schema browser dragged to its minimum). The
   * gap between the two columns is the part a preference cannot fix.
   */
  console.log('width  state    monaco  schemaBrowser');
  for (const width of SWEEP_WIDTHS) {
    for (const state of ['default', 'tuned']) {
      const page = await newPage(browser, width, 900, width < 1024);
      if (state === 'tuned') {
        await page.addInitScript(() => localStorage.setItem(
          'kse:query-layout',
          JSON.stringify({ splitPercent: 55, sidebarWidth: 200, assistantOpen: false }),
        ));
      }
      await page.goto(`${baseUrl}/query?sql=${encodeURIComponent(SQL)}`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(2000);
      const m = await page.evaluate(MEASURE);
      console.log(String(width).padEnd(6), state.padEnd(8), String(m.monacoW).padEnd(7), m.schemaBrowserW);
      await page.close();
    }
  }
} else {
  for (const vp of VIEWPORTS) {
    console.log(`\n===== ${vp.name} (${vp.width}x${vp.height}) =====`);
    for (const p of PAGES) {
      const page = await newPage(browser, vp.width, vp.height, vp.name !== 'desktop');
      let line;
      let m = null;
      try {
        await page.goto(baseUrl + p.url, { waitUntil: 'networkidle', timeout: 30_000 });
        await page.waitForTimeout(p.settleMs ?? 1200);
        m = await page.evaluate(MEASURE);
        line = `  ${p.name.padEnd(15)} overflows=${String(m.bodyOverflows).padEnd(5)}`
          + ` clipped=${String(m.clippedCount).padEnd(3)}`
          + ` unreachable=${String(m.unreachableCount).padEnd(2)}`
          + ` targets<24px=${m.tooSmall}/${m.targets}`
          + (m.monacoW === null ? '' : `  monaco=${m.monacoW}px`);
        checkResults.push({
          page: p.name, viewport: vp.name, failed: null,
          bodyOverflows: m.bodyOverflows, tooSmall: m.tooSmall,
        });
      } catch (e) {
        const reason = String(e).split('\n')[0].slice(0, 90);
        line = `  ${p.name.padEnd(15)} FAILED ${reason}`;
        checkResults.push({ page: p.name, viewport: vp.name, failed: reason });
      }
      console.log(line);
      if (mode === '--detail' && m) {
        // What is actually clipping, so W7 is a reading rather than another counting exercise.
        // The unreachable ones first — they are the only ones that are a defect.
        for (const c of m.clipped) {
          console.log(`      UNREACHABLE ${c.tag}${c.id ? '#' + c.id : ''} `
            + `${c.aria ? '[' + c.aria + '] ' : ''}${c.scrollW}>${c.clientW} `
            + `"${c.text}" .${c.cls}`);
        }
        for (const c of (m.clippedSample || [])) {
          console.log(`      ${c.reach.padEnd(19)} ${c.tag} ${c.scrollW}>${c.clientW} .${c.cls}`);
        }
      }
      await page.close();
    }
  }
}

await browser.close();

if (mode === '--check') {
  const failures = [];

  // A page added to PAGES without a budget would be measured and never gated — the silent gap
  // this mode exists to close.
  for (const p of PAGES) {
    if (!(p.name in TARGET_BUDGET)) {
      failures.push(`${p.name}: no entry in TARGET_BUDGET — add one (see the comment above it)`);
    }
  }

  for (const r of checkResults) {
    if (r.failed) {
      failures.push(`${r.page} @ ${r.viewport}: could not be measured — ${r.failed}`);
      continue;
    }
    if (r.bodyOverflows) {
      failures.push(`${r.page} @ ${r.viewport}: the document scrolls sideways`);
    }
    const budget = TARGET_BUDGET[r.page];
    if (budget !== undefined && r.tooSmall > budget) {
      failures.push(`${r.page} @ ${r.viewport}: ${r.tooSmall} targets under 24x24, budget ${budget}`);
    }
  }

  if (failures.length > 0) {
    console.error('\nLayout check failed:');
    for (const f of failures) console.error(`  - ${f}`);
    console.error('\nIf the change is deliberate, update TARGET_BUDGET in this file and the '
      + 'tables in MOBILE-LAYOUT-SCOPE.md from a fresh run.');
    process.exit(1);
  }
  console.log('\nLayout check passed: nothing scrolls sideways, no page exceeds its target budget.');
}
