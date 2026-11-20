// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
/*
 * CI: the Metrics page's forecast surfaces, in a real browser, against a running Explorer.
 *
 *   NODE_PATH="$(npm root -g)" node ci/demo-forecast-ui.mjs http://127.0.0.1:8080 [--expect id1,id2]
 *
 * `ci/demo-forecast.py` proves the backend produces forecasts; the Vitest suites prove the page
 * draws what it is handed. Neither proves the two meet — that the list's filter agrees with what
 * the server says is forecast, that the "breach predicted" count is the number of cards it keeps,
 * that the forecast panel draws the metric's own thresholds. This asks the page, not the API.
 *
 * It asserts relations, never timings. Whether "Warning expected in ~N min" is on screen depends
 * on where the demo's curve is in its three-hour cycle, so a check on the badge itself would fail
 * at some hours and pass at others — the one flake this must not be. What holds at every hour is
 * that the filter, its count and the badges say the same thing.
 */
import { createRequire } from 'node:module';

// ES modules ignore NODE_PATH, and playwright is installed globally, not a dependency here:
// require() honours it, which is how docs/screenshots/capture.mjs finds it too.
const { chromium } = createRequire(import.meta.url)('playwright');

const base = process.argv[2] ?? 'http://127.0.0.1:8080';
const expectArg = process.argv.indexOf('--expect');
const expected = expectArg > 0 ? process.argv[expectArg + 1].split(',').filter(Boolean) : [];

const failures = [];
const check = (ok, message) => {
  if (ok) console.log(`  ok  ${message}`);
  else { console.log(`  FAIL ${message}`); failures.push(message); }
};
const json = async path => (await fetch(base + path)).json();

const [metrics, status] = await Promise.all([json('/api/metrics'), json('/api/forecasts')]);
const forecastIds = new Set((status.series ?? []).map(s => s.metricId));
const listed = metrics.filter(m => forecastIds.has(m.id));
check(listed.length > 0, `the server lists a forecast for at least one of the ${metrics.length} metrics`);
for (const id of expected) check(forecastIds.has(id), `${id} has a forecast on the server`);

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  page.on('pageerror', error => check(false, `the page threw: ${error.message}`));
  await page.goto(`${base}/metrics`, { waitUntil: 'networkidle' });

  const filter = page.getByLabel('Filter by forecast');
  await filter.waitFor({ timeout: 30_000 });
  const shown = async () => {
    const text = await page.getByText(/\d+ \/ \d+ metrics/).first().textContent();
    const [, kept, total] = /(\d+) \/ (\d+) metrics/.exec(text);
    return { kept: Number(kept), total: Number(total) };
  };

  const all = await shown();
  check(all.total === metrics.length, `the caption counts every metric (${all.total} of ${metrics.length})`);

  await filter.selectOption('running');
  check((await shown()).kept === listed.length, `"Has a forecast" keeps the ${listed.length} the server lists`);
  for (const id of expected) {
    const name = metrics.find(m => m.id === id)?.name || id;
    check(await page.getByText(name, { exact: true }).first().isVisible(), `${name} is among them`);
  }

  await filter.selectOption('none');
  check((await shown()).kept === all.total - listed.length, '"No forecast" keeps the others');

  // The count in the option is the number of cards the filter keeps, and each of them carries the
  // badge that says why — the page's two readings of one function.
  await filter.selectOption('all');
  const label = await filter.locator('option[value="breach"]').textContent();
  const announced = Number(/\((\d+)\)/.exec(label)?.[1] ?? NaN);
  await filter.selectOption('breach');
  const breach = await shown();
  check(breach.kept === announced, `"Breach predicted (${announced})" keeps ${breach.kept} cards`);
  check(await page.getByLabel('Forecast threshold').count() === breach.kept,
    `each of those ${breach.kept} cards carries its predicted-threshold badge`);

  // The sort changes the caption and never the set.
  await filter.selectOption('all');
  await page.getByLabel('Sort by').selectOption('breach');
  const sorted = await shown();
  check(sorted.kept === all.total, 'sorting by the next predicted breach keeps every card');
  check(await page.getByText(/sorted by next predicted breach/).isVisible(), 'the caption says how the list is sorted');

  // The panel draws the metric's own thresholds, unless its forecast is a rate (the counter's
  // thresholds are never compared with it).
  const target = listed[0];
  const series = status.series.find(s => s.metricId === target.id);
  await page.getByRole('button', { name: new RegExp(`^Show the forecast of ${target.name || target.id}$`) }).click();
  await page.getByLabel('Forecast series').waitFor({ timeout: 30_000 });
  check((await page.getByLabel('Forecast series').textContent()).includes(target.name || target.id), 'the panel names the metric it shows');
  check(await page.getByText('Median forecast').isVisible() || await page.getByText('Baseline forecast').isVisible(),
    'the panel draws the forecast');
  const rate = series.result?.context?.profile?.transformation === 'COUNTER_RATE';
  if (!rate) {
    for (const [name, value] of [['warning', target.warningThreshold], ['critical', target.criticalThreshold]]) {
      if (value === null || value === undefined) continue;
      const drawn = await page.getByText(new RegExp(`^${name} `)).count();
      const named = await page.getByLabel('Thresholds off the chart scale').getByText(new RegExp(`${name} `)).count();
      check(drawn + named > 0, `the panel shows ${target.name || target.id}'s ${name} threshold (${value}), drawn or named at the edge`);
    }
  }
} finally {
  await browser.close();
}

if (failures.length) {
  console.error(`\n${failures.length} check(s) failed`);
  process.exit(1);
}
console.log('\nForecast UI checks passed');
