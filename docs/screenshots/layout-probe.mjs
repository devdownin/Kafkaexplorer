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
  { name: 'audit', url: '/audit' },
  { name: 'metrics', url: '/metrics' },
  { name: 'cluster', url: '/cluster' },
];

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
  const clipped = [...document.querySelectorAll('*')]
    .filter(el => {
      if (el.clientWidth === 0 || el.scrollWidth <= el.clientWidth + 2) return false;
      const overflowX = getComputedStyle(el).overflowX;
      return overflowX === 'visible' || overflowX === 'hidden';
    })
    .slice(0, 8)
    .map(el => ({
      tag: el.tagName.toLowerCase(),
      cls: String(el.className).slice(0, 40),
      scrollW: el.scrollWidth,
      clientW: el.clientWidth,
    }));

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

const browser = await chromium.launch();

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
      try {
        await page.goto(baseUrl + p.url, { waitUntil: 'networkidle', timeout: 30_000 });
        await page.waitForTimeout(p.settleMs ?? 1200);
        const m = await page.evaluate(MEASURE);
        line = `  ${p.name.padEnd(15)} overflows=${String(m.bodyOverflows).padEnd(5)}`
          + ` clipped=${String(m.clipped.length).padEnd(2)}`
          + ` targets<24px=${m.tooSmall}/${m.targets}`
          + (m.monacoW === null ? '' : `  monaco=${m.monacoW}px`);
      } catch (e) {
        line = `  ${p.name.padEnd(15)} FAILED ${String(e).split('\n')[0].slice(0, 90)}`;
      }
      console.log(line);
      await page.close();
    }
  }
}

await browser.close();
