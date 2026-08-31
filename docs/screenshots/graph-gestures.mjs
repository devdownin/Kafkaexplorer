// Vérifie que les trois graphes SVG se déplacent, zooment et s'atteignent au clavier — sur un
// vrai navigateur, avec une vraie géométrie.
//
// Lineage, Stream Flow et Data Model partagent `useGraphViewport`. Ce hook n'a qu'un test
// unitaire, sur `zoomTransform`, et son commentaire dit pourquoi : le reste est de la plomberie
// d'événements pointeur au-dessus d'une mise en page que jsdom n'a pas. Un test qui y simulerait
// un glissé affirmerait que des mocks ont été appelés, pas qu'un graphe se déplace.
//
// D'où cette sonde, sur le modèle de `layout-probe.mjs` : même serveur à fixtures, même SPA
// compilée, même Chromium, et des affirmations plutôt que des images. Ce qu'elle couvre est
// précisément ce qui a déjà été cassé ici une fois — le passage aux événements *pointeur*, sans
// lequel une tablette ne peut pas déplacer un graphe, a dû être fait trois fois parce que le
// code existait en trois exemplaires.
//
// Usage, le serveur à fixtures déjà lancé (voir README.md) :
//   node server.mjs /tmp/kse-site 4173 &
//   node graph-gestures.mjs http://127.0.0.1:4173
//   node graph-gestures.mjs http://127.0.0.1:4173 --detail   # dit chaque transform lu

import { createRequire } from 'node:module';
import { failOnUnstubbedRoutes } from './unstubbed.mjs';

// Même résolution que capture.mjs et layout-probe.mjs.
const require = createRequire(import.meta.url);
let chromium;
try {
  ({ chromium } = require('playwright'));
} catch {
  console.error('playwright not found. Install it (npm i -g playwright) and ensure a Chromium build is available.');
  process.exit(2);
}

const [, , baseUrl = 'http://127.0.0.1:4173', mode] = process.argv;
const DETAIL = mode === '--detail';

/**
 * Les trois consommateurs du hook. `reset` dit ce que `0` doit faire, et c'est la seule
 * politique qui diffère réellement entre eux : Lineage revient à une origine fixe, les deux
 * autres recadrent sur le contenu — donc on ne peut pas y affirmer une valeur, seulement que
 * le cadrage a bougé et s'est stabilisé.
 */
const GRAPHS = [
  { name: 'lineage', url: '/lineage', reset: { x: 40, y: 20, scale: 1 } },
  { name: 'stream-flow', url: '/stream-flow?key=ORD-1042&exact=1', reset: 'fit' },
  {
    name: 'data-model',
    url: '/data-model?topics=' + encodeURIComponent(
      ['demo.customers', 'demo.orders.1.received',
       'demo.payments.authorized', 'demo.shipments.dispatched'].join(',')),
    reset: 'fit',
    settleMs: 2500,
  },
];

/**
 * Le canevas de graphe, et rien d'autre.
 *
 * `svg[tabindex="0"]` semblait suffire et ne l'est pas : la minicarte de Data Model est elle
 * aussi un `<svg>` focalisable, elle la précède dans le DOM, et surtout elle n'apparaît que
 * lorsque le graphe déborde de la vue — donc elle surgit *pendant* le glissé qu'on mesure, et
 * le sélecteur bascule dessus au milieu du test. `role="application"` distingue le canevas
 * (que les trois pages portent) de la minicarte (`role="button"`), et cette distinction est
 * sémantique plutôt que positionnelle : elle dit ce que la sonde vise.
 */
const CANVAS = 'svg[role="application"]';

const near = (a, b, tol = 0.5) => Math.abs(a - b) <= tol;

/** Lit `translate(x, y) scale(s)` sur le groupe que le hook pilote. */
async function readTransform(page) {
  return page.evaluate((sel) => {
    const svg = document.querySelector(sel);
    const g = svg && svg.querySelector(':scope > g[transform]');
    if (!g) return null;
    const m = /translate\(\s*([-\d.]+)[ ,]+([-\d.]+)\s*\)\s*scale\(\s*([-\d.]+)\s*\)/
      .exec(g.getAttribute('transform'));
    return m ? { x: +m[1], y: +m[2], scale: +m[3] } : null;
  }, CANVAS);
}

/** Attend que le transform change : React rend au tick suivant, un délai fixe est une loterie. */
async function waitForChange(page, before, whatFor) {
  try {
    await page.waitForFunction(
      ([sel, prev]) => {
        const svg = document.querySelector(sel);
        const g = svg && svg.querySelector(':scope > g[transform]');
        return !!g && g.getAttribute('transform') !== prev;
      },
      [CANVAS, before],
      { timeout: 3000 },
    );
  } catch {
    throw new Error(`le transform n'a pas bougé après ${whatFor}`);
  }
}

const transformAttr = (page) => page.evaluate(
  (sel) => document.querySelector(sel)?.querySelector(':scope > g[transform]')?.getAttribute('transform'),
  CANVAS);

/**
 * Un point du canevas où l'appui déplace vraiment le graphe.
 *
 * Lineage et Data Model ignorent délibérément un appui sur `[data-node]` — un glissé qui démarre
 * sur une table doit la sélectionner, pas déplacer la vue — donc presser au hasard mesurerait
 * parfois le contraire de ce qu'on croit. La minicarte de Data Model, elle, est un SVG *frère* et
 * non un enfant : `svg.contains` l'écarte déjà, et il n'y a pas de garde à ajouter pour elle.
 */
async function backgroundPoint(page) {
  return page.evaluate((sel) => {
    const svg = document.querySelector(sel);
    const r = svg.getBoundingClientRect();
    // Balaye une grille depuis les bords, où les nœuds sont les plus rares.
    for (const fy of [0.9, 0.1, 0.8, 0.2, 0.5]) {
      for (const fx of [0.06, 0.94, 0.5, 0.25, 0.75]) {
        const x = r.left + r.width * fx;
        const y = r.top + r.height * fy;
        const el = document.elementFromPoint(x, y);
        if (el && svg.contains(el)
            && !el.closest('[data-node]') && !el.closest('a,button,[role="button"]')) {
          return { x, y };
        }
      }
    }
    return null;
  }, CANVAS);
}

const results = [];
function check(graph, name, fn) {
  return Promise.resolve()
    .then(fn)
    .then(() => { results.push({ graph, name, ok: true }); })
    .catch((e) => { results.push({ graph, name, ok: false, why: e.message }); });
}

async function run() {
  const browser = await chromium.launch(
    process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  for (const graph of GRAPHS) {
    await page.goto(baseUrl + graph.url, { waitUntil: 'networkidle' });
    await page.waitForSelector(CANVAS, { timeout: 10_000 });
    await page.waitForTimeout(graph.settleMs ?? 1200);

    const start = await readTransform(page);
    if (!start) {
      results.push({ graph: graph.name, name: 'canevas présent', ok: false, why: 'aucun groupe transform' });
      continue;
    }
    if (DETAIL) console.log(`  ${graph.name} départ ${JSON.stringify(start)}`);

    // ── Le doigt et la souris ────────────────────────────────────────────────
    // `touch-action: none` est ce qui permet à une tablette de déplacer le graphe au lieu de
    // faire défiler la page sous le geste. C'est une propriété calculée, donc vérifiable ;
    // le glissé lui-même est piloté en pointeur souris, qui emprunte exactement le même
    // gestionnaire React.
    await check(graph.name, 'touch-action neutralisé', async () => {
      const ta = await page.evaluate(
        (sel) => getComputedStyle(document.querySelector(sel)).touchAction, CANVAS);
      if (ta !== 'none') throw new Error(`touch-action vaut "${ta}", la page défilerait sous le geste`);
    });

    await check(graph.name, 'le pan suit le pointeur', async () => {
      const pt = await backgroundPoint(page);
      if (!pt) throw new Error('aucun point de fond trouvé sur le canevas');
      const before = await readTransform(page);
      const attr = await transformAttr(page);
      const [dx, dy] = [120, -80];
      await page.mouse.move(pt.x, pt.y);
      await page.mouse.down();
      await page.mouse.move(pt.x + dx / 2, pt.y + dy / 2);
      await page.mouse.move(pt.x + dx, pt.y + dy);
      await waitForChange(page, attr, 'un glissé');
      await page.mouse.up();
      const after = await readTransform(page);
      // `panBy` ajoute les pixels bruts : le déplacement suit le pointeur quel que soit le zoom.
      if (!near(after.x, before.x + dx) || !near(after.y, before.y + dy)) {
        throw new Error(`attendu +(${dx}, ${dy}) depuis (${before.x}, ${before.y}), obtenu (${after.x}, ${after.y})`);
      }
      if (!near(after.scale, before.scale, 1e-6)) throw new Error('un glissé a changé l\'échelle');
    });

    // ── La molette ───────────────────────────────────────────────────────────
    await check(graph.name, 'le zoom reste ancré sous le curseur', async () => {
      const pt = await backgroundPoint(page);
      const box = await page.evaluate(
        (sel) => { const r = document.querySelector(sel).getBoundingClientRect();
                   return { left: r.left, top: r.top }; }, CANVAS);
      const before = await readTransform(page);
      const attr = await transformAttr(page);
      // Le point du graphe actuellement dessiné sous le curseur, en coordonnées du canevas.
      const px = pt.x - box.left, py = pt.y - box.top;
      const gx = (px - before.x) / before.scale;
      const gy = (py - before.y) / before.scale;
      await page.mouse.move(pt.x, pt.y);
      await page.mouse.wheel(0, -240);
      await waitForChange(page, attr, 'une molette');
      const after = await readTransform(page);
      if (after.scale <= before.scale) throw new Error('la molette vers le haut n\'a pas agrandi');
      // L'invariant : ce même point du graphe doit rester sous le curseur.
      const nx = after.x + gx * after.scale;
      const ny = after.y + gy * after.scale;
      if (!near(nx, px, 1.5) || !near(ny, py, 1.5)) {
        throw new Error(`le point ancré a glissé de (${(nx - px).toFixed(1)}, ${(ny - py).toFixed(1)}) px`);
      }
    });

    // ── Le clavier ───────────────────────────────────────────────────────────
    // Sans lui, pan et zoom n'existent qu'à la souris, et la moitié d'une chaîne un peu longue
    // est hors d'atteinte pour qui n'en a pas.
    await check(graph.name, 'les flèches déplacent, Maj amplifie', async () => {
      await page.focus(CANVAS);
      const before = await readTransform(page);
      let attr = await transformAttr(page);
      await page.keyboard.press('ArrowLeft');
      await waitForChange(page, attr, 'ArrowLeft');
      const step = await readTransform(page);
      if (!near(step.x, before.x + 48)) throw new Error(`ArrowLeft attendu +48, obtenu ${step.x - before.x}`);

      attr = await transformAttr(page);
      await page.keyboard.press('Shift+ArrowRight');
      await waitForChange(page, attr, 'Maj+ArrowRight');
      const big = await readTransform(page);
      if (!near(big.x, step.x - 160)) throw new Error(`Maj+ArrowRight attendu -160, obtenu ${big.x - step.x}`);
    });

    await check(graph.name, '+ et - zooment depuis le centre', async () => {
      await page.focus(CANVAS);
      const before = await readTransform(page);
      let attr = await transformAttr(page);
      await page.keyboard.press('+');
      await waitForChange(page, attr, '+');
      const up = await readTransform(page);
      if (up.scale <= before.scale) throw new Error('« + » n\'a pas agrandi');

      attr = await transformAttr(page);
      await page.keyboard.press('-');
      await waitForChange(page, attr, '-');
      const down = await readTransform(page);
      if (down.scale >= up.scale) throw new Error('« - » n\'a pas réduit');
    });

    await check(graph.name, '0 rend le cadrage à la page', async () => {
      await page.focus(CANVAS);
      const attr = await transformAttr(page);
      await page.keyboard.press('0');
      await waitForChange(page, attr, '0');
      const after = await readTransform(page);
      if (graph.reset === 'fit') {
        // Un recadrage : on ne peut pas affirmer une valeur, seulement qu'il ramène le contenu
        // dans la vue — donc une échelle utilisable et une origine qui n'est pas partie au loin.
        if (!(after.scale > 0.05 && after.scale <= 4)) throw new Error(`échelle hors de portée : ${after.scale}`);
        if (Math.abs(after.x) > 5000 || Math.abs(after.y) > 5000) {
          throw new Error(`origine hors du canevas après recadrage : (${after.x}, ${after.y})`);
        }
      } else {
        const r = graph.reset;
        if (!near(after.x, r.x) || !near(after.y, r.y) || !near(after.scale, r.scale, 1e-6)) {
          throw new Error(`attendu ${JSON.stringify(r)}, obtenu ${JSON.stringify(after)}`);
        }
      }
    });

    if (DETAIL) console.log(`  ${graph.name} fin    ${JSON.stringify(await readTransform(page))}`);
  }

  await browser.close();
  // Whatever this run produced describes an incomplete page if anything went unstubbed, so the
  // question is asked before any verdict is reached. See unstubbed.mjs.
  await failOnUnstubbedRoutes(baseUrl);
}

await run();

const failed = results.filter(r => !r.ok);
let current = '';
for (const r of results) {
  if (r.graph !== current) { current = r.graph; console.log(`\n${current}`); }
  console.log(`  ${r.ok ? 'ok  ' : 'FAIL'}  ${r.name}${r.ok ? '' : `\n          ${r.why}`}`);
}
console.log(`\n${results.length - failed.length}/${results.length} vérifications passent`);
if (failed.length) {
  console.error(`\n${failed.length} échec(s) — les gestes des graphes ont régressé.`);
  process.exit(1);
}
