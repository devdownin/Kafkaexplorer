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
 * Les surfaces qui n'existent qu'après un geste, mesurées comme les pages.
 *
 * La sonde ne cliquait sur rien : elle photographiait huit pages au repos. Or les deux derniers
 * défauts de troncature vivaient l'un dans la carte de confirmation, l'autre dans la liste de
 * suggestions d'un combobox — deux surfaces qu'aucun chargement d'URL ne fait apparaître. La
 * colonne `unreachable` les aurait nommés tous les deux le jour où ils sont nés ; elle ne
 * regardait pas là. `MOBILE-LAYOUT-SCOPE.md` clôt d'ailleurs W8 sur « la sonde continue de
 * mesurer, donc un vrai cas ressortirait » : vrai des pages, faux de tout ce qui s'ouvre.
 *
 * Un état est mesuré **sur la page déjà chargée**, pas sur une nouvelle : un geste coûte
 * quelques centaines de millisecondes là où une navigation en coûte deux à quatre mille, et
 * `--check` tourne à chaque pull request — c'est ce qui rend l'ajout gratuit plutôt que de le
 * faire passer le budget de temps du job, la raison pour laquelle `CHECK_VIEWPORTS` a déjà dû
 * abandonner la tablette.
 *
 * `viewports` dit où l'état est atteignable, et ce n'est pas une commodité : un état qui ne
 * s'ouvre **pas** dans les largeurs qu'il déclare est un échec de `--check`, pas un silence.
 * Sans ça, un geste qui cesse de fonctionner ne se distingue plus d'un état qu'on a renoncé à
 * mesurer — exactement le trou muet que le garde de `TARGET_BUDGET` ferme pour les pages.
 * `close` ramène la page au repos, car les états d'une même page s'enchaînent sans rechargement.
 */
const ESCAPE = async page => { await page.keyboard.press('Escape'); await page.waitForTimeout(200); };

/* Un identifiant Kafka de longueur réaliste. Ni un cas limite ni une invention : c'est la forme
   `<domaine>.<flux>.<étape>.<version>` qu'une convention d'entreprise produit, et la longueur à
   partir de laquelle la carte de confirmation débordait. */
const LONG_NAME = 'acme.production.orders.shipped.enriched.consolidated.v2';

const STATES = {
  'sql-editor': [
    {
      // La confirmation du Window Assistant : c'est elle qui laissait un nom d'onglet sortir
      // de la carte. Sous `lg` l'éditeur affiche son avertissement de fenêtre étroite et le
      // volet n'est pas là, donc l'état ne se déclare qu'au bureau.
      name: 'confirm',
      viewports: ['desktop'],
      open: async page => {
        /* L'onglet est renommé avec un vrai nom de topic, parce que c'est *la donnée* qui fait
           le défaut : la confirmation cite le nom de l'onglet, et « Query 1 » ne débordera
           jamais de rien. Semé plutôt qu'ajouté aux fixtures — celles-ci sont calquées sur ce
           que `setup-demo.sh` sème, et les captures les partagent ; c'est le même procédé que
           `--sweep`, qui sème déjà `kse:query-layout`. Le rechargement est le prix de ce
           réalisme, et il n'est payé que par cet état. */
        await page.evaluate(name => localStorage.setItem('kse:tabs', JSON.stringify({
          tabs: [{ id: '1', name, sql: 'SELECT order_id FROM demo_orders_1_received LIMIT 50' }],
          activeTabId: '1',
        })), LONG_NAME);
        await page.reload({ waitUntil: 'networkidle' });
        await page.waitForTimeout(1500);
        const rail = page.getByRole('button', { name: 'Open the Window Assistant' });
        if (await rail.count()) await rail.first().click();
        await page.getByRole('button', { name: 'Replace editor content' }).click();
        await page.getByRole('dialog').waitFor({ timeout: 5000 });
      },
      close: ESCAPE,
    },
    {
      // L'aperçu du DDL : une modale qui rend du SQL généré, donc du texte long par nature.
      name: 'ddl',
      viewports: ['desktop'],
      open: async page => {
        await page.getByRole('button', { name: /^Preview the generated DDL for / }).first().click();
        await page.getByRole('dialog', { name: 'DDL preview' }).waitFor({ timeout: 5000 });
      },
      close: ESCAPE,
    },
  ],
  'metrics': [
    {
      // L'éditeur de métrique, la plus grande modale de l'application.
      name: 'editor',
      viewports: ['phone', 'tablet', 'desktop'],
      open: async page => {
        await page.getByRole('button', { name: 'Add metric' }).first().click();
        await page.getByRole('dialog').waitFor({ timeout: 5000 });
      },
      close: ESCAPE,
    },
    /*
     * Il y avait ici un état « liste de topics dépliée ». Il est retiré, et le mesurer est ce
     * qui l'a décidé : avec le catalogue de démo, ses options ne tronquent à aucune des trois
     * largeurs — la ligne rendait des nombres identiques à `metrics·editor` partout, donc une
     * ligne qui ne peut pas bouger. Allonger un nom de topic pour la faire parler supposerait
     * d'inventer des données que `setup-demo.sh` ne sème pas, or les fixtures sont calquées sur
     * lui et les captures les partagent. La règle qu'elle aurait gardée — une option tronquée
     * porte son nom complet — est épinglée par un test unitaire (`forms.test.tsx`), qui lui ne
     * dépend d'aucune donnée. Un état ne se justifie ici que s'il peut rapporter un défaut.
     */
  ],
  'dashboard': [
    {
      // La palette de commandes : la seule surface que l'on ouvre au clavier, et une liste de
      // noms de topics et de tables — le même contenu que celle du combobox.
      name: 'palette',
      viewports: ['desktop'],
      open: async page => {
        await page.keyboard.press('Control+k');
        await page.getByRole('dialog', { name: 'Command palette' }).waitFor({ timeout: 5000 });
      },
      close: ESCAPE,
    },
  ],
};

/** Le nom sous lequel un état est rapporté et budgété : `metrics·topic-list`. */
const stateName = (page, state) => `${page}\u00b7${state.name}`;

/** Tout ce qui est mesuré, pages et états — ce sur quoi `--check` exige un budget. */
const MEASURED = PAGES.flatMap(p => [p.name, ...(STATES[p.name] ?? []).map(s => stateName(p.name, s))]);

/*
 * Ceilings for `--check`, the mode CI runs. This is W6 from MOBILE-LAYOUT-SCOPE.md: without it,
 * every number in that document is a reading from whenever someone last remembered to take one.
 *
 * **What is gated, and what deliberately is not.** Three things are asserted hard: that no page
 * scrolls sideways at any width, that no page carries more sub-24px targets than it does today,
 * and that none carries more *unreachable* clipping. The first two come from element sizes set
 * by utility classes, so they are the same on any machine.
 *
 * The third used to be excluded with the other one, on the argument that clipping turns on text
 * metrics — the same string wraps differently under a different font stack, so a ceiling set
 * here would fail on a runner for a reason unrelated to the change under test, and a gate with
 * false positives is one people learn to re-run until it passes. **That argument was measured
 * and it splits the two columns rather than covering both.** Comparing this runner's output with
 * a developer machine's, on the same commit, over the 21 rows `--check` walks:
 *
 *     clipped       4 rows of 21 differ   (all four on the SQL editor: 2 here, 3 there)
 *     unreachable   0 rows of 21 differ
 *
 * Which is what the two columns *are*: `clipped` counts where a string happens to wrap, and
 * `unreachable` answers whether any path to the rest of it exists — a `title`, a scrollable
 * ancestor — which is a fact about the markup. So `clipped` stays reported and ungated, and
 * `unreachable` is gated. It is worth gating only because Monaco left the measurement first:
 * 7 of the 18 findings were the editor's own scroll layers, and a ceiling over that would have
 * capped noise instead of defects.
 *
 * Both sets of ceilings are the measured maximum across the viewports walked, so they are a "no
 * worse than today" line rather than a target. Lower them when a page improves — the point is
 * that the number cannot drift upward unnoticed.
 */
const UNREACHABLE_BUDGET = {
  'dashboard': 0,
  'dashboard·palette': 0,
  'sql-editor': 3,
  'sql-editor·confirm': 0,
  'sql-editor·ddl': 0,
  'topic-explorer': 2,
  'stream-flow': 0,
  'data-model': 0,
  'audit': 0,
  'metrics': 0,
  'metrics·editor': 2,
  'cluster': 2,
};

const TARGET_BUDGET = {
  'dashboard': 5,
  'dashboard·palette': 6,
  'sql-editor': 39,
  'sql-editor·confirm': 36,
  'sql-editor·ddl': 37,
  'topic-explorer': 3,
  'stream-flow': 19,
  'data-model': 7,
  'audit': 23,
  'metrics': 8,
  'metrics·editor': 9,
  'cluster': 1,
};

/*
 * `--check` measures the two extremes only. The full three-viewport run is what a person runs by
 * hand; the gate runs on every pull request, and each viewport is eight page loads waited out to
 * `networkidle` — the capture step alone is ~4 minutes for eight, so three viewports put this job
 * past its budget the first time it ran. Phone and desktop are kept because they are where the
 * two gated properties actually differ: horizontal overflow appears at the narrow end, and the
 * highest target count is at one end or the other (`stream-flow` peaks at desktop, `data-model` at
 * phone). Tablet is a third reading of numbers that, as the table in MOBILE-LAYOUT-SCOPE.md says,
 * barely move — so it costs a third of the runtime to confirm what the extremes already bound.
 */
const CHECK_VIEWPORTS = ['phone', 'desktop'];

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

  /*
   * Les panneaux d'infobulle **fermés** sont retirés de la mise en page le temps de la mesure.
   *
   * `Tooltip` garde son contenu monté en permanence — c'est délibéré, et documenté : sans ça
   * `aria-describedby` pointerait par moments vers un élément absent. Fermé, le panneau est
   * seulement transparent, donc son texte continue de compter dans le `scrollWidth` de ce qui
   * l'entoure : la sonde le lisait comme du contenu tronqué et inatteignable. Elle a compté
   * jusqu'à vingt « unreachable » sur le Topic Explorer, tous des infobulles fermées — c'est-à-dire
   * du contenu qui s'affiche à la demande, l'exact contraire de ce que cette colonne prétend
   * mesurer. Or c'est le nombre sur lequel W7 a conclu « rien ne cache de contenu inatteignable » :
   * la conclusion tenait, le chiffre qui l'étayait non, et le prochain lecteur serait parti
   * chercher vingt défauts qui n'existent pas.
   *
   * `display: none` plutôt qu'un filtre a posteriori : c'est le navigateur qui refait la mise en
   * page, donc les `scrollWidth` obtenus sont ceux du contenu réel, y compris pour les ancêtres —
   * qu'une règle « ignorer l'élément qui contient une infobulle » n'aurait pas nettoyés. Restauré
   * juste après, même si la page est jetable : une mesure qui laisse la page dans un autre état
   * que celui qu'elle décrit est une mesure qu'on ne peut pas enchaîner.
   */
  const hiddenTooltips = [...document.querySelectorAll('[role="tooltip"]')]
    .filter(panel => getComputedStyle(panel).opacity === '0')
    .map(panel => {
      const previous = panel.style.display;
      panel.style.display = 'none';
      return { panel, previous };
    });

  /*
   * L'intérieur de Monaco est hors mesure.
   *
   * L'éditeur gère son propre défilement sur une surface synthétique : `.monaco-scrollable-element`
   * annonce un `scrollWidth` de **16 777 216** px, et `.overflow-guard` rogne par construction.
   * La colonne prétend nommer « du contenu coupé dont le reste n'est atteignable nulle part » ;
   * un éditeur de texte qui défile est l'exact contraire. Mesuré sur `main` : **7 des 18
   * `unreachable` de la sonde étaient ces couches-là**, soit 40 % d'un nombre dont la moitié du
   * reste est du vrai. C'est ce qui interdisait de verrouiller cette colonne — on aurait plafonné
   * du bruit. La règle porte sur l'éditeur entier plutôt que sur une classe : Monaco empile
   * plusieurs couches, et un rognage à l'intérieur de son rendu est un défaut de Monaco, pas
   * du code de cette application.
   */
  const isEditorInternal = el => el.closest('.monaco-editor') !== null;

  const clippedAll = [...document.querySelectorAll('*')]
    .filter(el => {
      if (el.clientWidth === 0 || el.scrollWidth <= el.clientWidth + 2) return false;
      if (isScreenReaderOnly(el)) return false;
      if (isEditorInternal(el)) return false;
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
  /*
   * Seul le plus profond de chaque chaîne est retenu.
   *
   * Un conteneur ne « rogne » le plus souvent que parce que son enfant le fait : le même défaut
   * ressortait trois ou quatre fois, sous les classes d'une pile de `div` de mise en page qui ne
   * disent rien de ce qui est coupé. Comme l'échantillon est plafonné à huit et pris dans
   * l'ordre du DOM, ces doublons chassaient les vraies trouvailles — et une modale, rendue en
   * fin de document, tombait toujours après la coupe. C'est exactement le regroupement que
   * `tooSmallGroups` fait pour les cibles trop petites : le compte dit qu'il y a un problème,
   * l'élément le plus profond dit lequel. Le *compte*, lui, reste celui de l'ensemble.
   */
  const unreachable = clippedAll.filter(el => reachability(el) === 'none');
  const innermost = unreachable.filter(el => !unreachable.some(o => o !== el && el.contains(o)));
  const clipped = innermost.slice(0, 8).map(describe);
  const clippedSample = clippedAll.slice(0, 4).map(describe);

  /* Restauré seulement ici : `reachability` et `describe` relisent la mise en page, donc rendre
     les panneaux avant eux leur ferait décrire un écran différent de celui qui a été filtré. */
  hiddenTooltips.forEach(({ panel, previous }) => { panel.style.display = previous; });

  const targets = [...document.querySelectorAll('button, [role="button"], a, input, select')]
    .map(el => {
      const r = el.getBoundingClientRect();
      return {
        tag: el.tagName.toLowerCase(),
        cls: String(el.className).slice(0, 70),
        label: (el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 30),
        w: Math.round(r.width),
        h: Math.round(r.height),
      };
    })
    .filter(t => t.w > 0 && t.h > 0);
  const tooSmall = targets.filter(t => t.w < 24 || t.h < 24);

  /*
   * Groupé par contrôle — tag, taille rendue et classes — parce que c'est ce que le compte cache.
   * « 42 cibles trop petites » sur le modèle de données était **un seul contrôle** répété quarante
   * fois, et c'est en le voyant que le correctif est devenu une modification de composant plutôt
   * que quarante retouches. Un compte dit qu'il y a un problème ; un regroupement dit lequel.
   */
  const byControl = new Map();
  for (const t of tooSmall) {
    const key = `${t.tag}|${t.w}x${t.h}|${t.cls}`;
    if (!byControl.has(key)) byControl.set(key, { ...t, n: 0, labels: [] });
    const g = byControl.get(key);
    g.n += 1;
    if (t.label && g.labels.length < 2 && !g.labels.includes(t.label)) g.labels.push(t.label);
  }
  const tooSmallGroups = [...byControl.values()].sort((a, b) => b.n - a.n).slice(0, 8);

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
    tooSmallGroups,
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
  const walked = mode === '--check'
    ? VIEWPORTS.filter(v => CHECK_VIEWPORTS.includes(v.name))
    : VIEWPORTS;
  for (const vp of walked) {
    console.log(`\n===== ${vp.name} (${vp.width}x${vp.height}) =====`);
    for (const p of PAGES) {
      const page = await newPage(browser, vp.width, vp.height, vp.name !== 'desktop');
      /* Une mesure, son compte rendu et sa ligne de `--check` : la page au repos et chacun de
         ses états ouverts passent exactement par là, sinon un état serait mesuré autrement que
         ce qu'il prétend comparer. */
      const measured = [];
      const record = async (name, m) => {
        measured.push({ name, m });
        console.log(`  ${name.padEnd(21)} overflows=${String(m.bodyOverflows).padEnd(5)}`
          + ` clipped=${String(m.clippedCount).padEnd(3)}`
          + ` unreachable=${String(m.unreachableCount).padEnd(2)}`
          + ` targets<24px=${m.tooSmall}/${m.targets}`
          + (m.monacoW === null ? '' : `  monaco=${m.monacoW}px`));
        checkResults.push({
          page: name, viewport: vp.name, failed: null,
          bodyOverflows: m.bodyOverflows, tooSmall: m.tooSmall,
          unreachable: m.unreachableCount,
        });
      };

      try {
        await page.goto(baseUrl + p.url, { waitUntil: 'networkidle', timeout: 30_000 });
        await page.waitForTimeout(p.settleMs ?? 1200);
        await record(p.name, await page.evaluate(MEASURE));

        /* Les états s'ouvrent sur cette page-ci, pas sur une nouvelle — voir STATES. Chacun se
           referme derrière lui : ils s'enchaînent, et une modale restée ouverte ferait décrire
           au suivant un écran qui n'est pas le sien. */
        for (const state of (STATES[p.name] ?? [])) {
          const name = stateName(p.name, state);
          if (!state.viewports.includes(vp.name)) continue;
          try {
            await state.open(page);
            await page.waitForTimeout(state.settleMs ?? 500);
            await record(name, await page.evaluate(MEASURE));
          } catch (e) {
            /* Un état déclaré pour cette largeur et qui ne s'ouvre pas est un échec, pas un
               silence : c'est ainsi qu'un geste cassé se distingue d'un état abandonné. */
            const reason = String(e).split('\n')[0].slice(0, 90);
            console.log(`  ${name.padEnd(21)} FAILED ${reason}`);
            checkResults.push({ page: name, viewport: vp.name, failed: reason });
          }
          try { await state.close(page); } catch { /* la page est jetable */ }
        }
      } catch (e) {
        const reason = String(e).split('\n')[0].slice(0, 90);
        console.log(`  ${p.name.padEnd(21)} FAILED ${reason}`);
        checkResults.push({ page: p.name, viewport: vp.name, failed: reason });
      }

      for (const { name, m } of measured) {
      if (mode === '--detail' && m) {
        console.log(`    — ${name}`);
        // Which control is undersized, and how many of it there are. W5 is a triage, and a
        // triage cannot be done against a count — see `tooSmallGroups` for why.
        for (const g of (m.tooSmallGroups || [])) {
          console.log(`      x${String(g.n).padEnd(3)} <${g.tag}> ${g.w}x${g.h} `
            + `${g.labels.join(' / ').slice(0, 46)}`);
          console.log(`           .${g.cls}`);
        }
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
  for (const name of MEASURED) {
    if (!(name in TARGET_BUDGET)) {
      failures.push(`${name}: no entry in TARGET_BUDGET — add one (see the comment above it)`);
    }
    if (!(name in UNREACHABLE_BUDGET)) {
      failures.push(`${name}: no entry in UNREACHABLE_BUDGET — add one (see the comment above it)`);
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
    const unreachableBudget = UNREACHABLE_BUDGET[r.page];
    if (unreachableBudget !== undefined && r.unreachable > unreachableBudget) {
      failures.push(`${r.page} @ ${r.viewport}: ${r.unreachable} clipped elements with no way to `
        + `reach the rest, budget ${unreachableBudget} — run --detail to see which`);
    }
  }

  if (failures.length > 0) {
    console.error('\nLayout check failed:');
    for (const f of failures) console.error(`  - ${f}`);
    console.error('\nIf the change is deliberate, update TARGET_BUDGET / UNREACHABLE_BUDGET in '
      + 'this file and the tables in MOBILE-LAYOUT-SCOPE.md from a fresh run.');
    process.exit(1);
  }
  console.log('\nLayout check passed: nothing scrolls sideways, and no page exceeds its target '
    + 'or unreachable budget.');
}
