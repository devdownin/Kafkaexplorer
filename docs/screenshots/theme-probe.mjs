// SPDX-License-Identifier: AGPL-3.0-or-later
// Visual regression probe for both spatial themes across the four layout breakpoints.
// Run after starting the existing stub-backed SPA (see docs/screenshots/README.md):
//   node theme-probe.mjs http://127.0.0.1:4173 [output-directory]
// Without an output directory this is a headless layout/a11y smoke check only.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
let chromium;
try { ({ chromium } = require('playwright')); }
catch { console.error('Playwright unavailable. Install or expose the existing CI Playwright runtime.'); process.exit(2); }

const [, , baseUrl = 'http://127.0.0.1:4173', screenshotDir] = process.argv;
const viewports = [
  { name: 'phone', width: 375, height: 812 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1024, height: 768 },
  { name: 'wide', width: 1440, height: 900 },
];
const pages = [
  { name: 'dashboard', url: '/', anchor: 'h1' },
  { name: 'metrics', url: '/metrics', anchor: 'h1' },
  { name: 'query', url: '/query', anchor: '#kse-editor-pane' },
  { name: 'data-model', url: '/data-model', anchor: '.kse-model-canvas' },
];
const browser = await chromium.launch(process.env.CHROMIUM_PATH
  ? { executablePath: process.env.CHROMIUM_PATH }
  : {});
const failures = [];
if (screenshotDir) fs.mkdirSync(screenshotDir, { recursive: true });

for (const theme of ['light', 'dark']) {
  for (const viewport of viewports) {
    for (const route of pages) {
      const page = await browser.newPage({
        viewport: { width: viewport.width, height: viewport.height },
        colorScheme: theme,
        reducedMotion: 'reduce',
      });
      try {
        await page.addInitScript(value => localStorage.setItem('kse.theme', value), theme);
        await page.goto(baseUrl + route.url, { waitUntil: 'domcontentloaded', timeout: 30000 });
        await page.locator('.kse-header').waitFor({ timeout: 15000 });
        await page.locator(route.anchor).first().waitFor({ timeout: 15000 });
        await page.waitForTimeout(200);
        const state = await page.evaluate(() => {
          const root = document.documentElement;
          const header = document.querySelector('.kse-header');
          const sidebar = document.querySelector('.kse-sidebar');
          const buttons = [...document.querySelectorAll('.kse-page-actions button')];
          const overflow = root.scrollWidth > window.innerWidth + 1;
          const offscreenActions = buttons.some(button => {
            const box = button.getBoundingClientRect();
            return box.width > 0 && (box.right > window.innerWidth + 1 || box.left < -1);
          });
          return {
            theme: root.dataset.theme,
            overflow,
            offscreenActions,
            headerMaterial: header && getComputedStyle(header).backgroundColor,
            sidebarMaterial: sidebar && getComputedStyle(sidebar).backgroundColor,
            hasThemeSwitch: Boolean(document.querySelector('button[aria-label^="Switch to "]')),
          };
        });
        const label = `${theme}/${viewport.name}/${route.name}`;
        console.log(label, JSON.stringify(state));
        if (state.theme !== theme) failures.push(`${label}: theme was not applied`);
        if (!state.hasThemeSwitch) failures.push(`${label}: inaccessible theme switch`);
        if (state.overflow) failures.push(`${label}: horizontal document overflow`);
        if (state.offscreenActions) failures.push(`${label}: page action clipped horizontally`);
        if (screenshotDir) {
          await page.screenshot({
            path: path.join(screenshotDir, `${theme}-${viewport.name}-${route.name}.png`),
            fullPage: false,
            animations: 'disabled',
          });
        }
      } catch (error) {
        failures.push(`${theme}/${viewport.name}/${route.name}: ${String(error).split('\n')[0]}`);
      } finally { await page.close(); }
    }
  }
}
await browser.close();
if (failures.length) {
  console.error('Spatial theme probe failures:\n' + failures.join('\n'));
  process.exitCode = 1;
} else {
  console.log('Spatial theme probe: all 32 page/theme/viewport combinations passed.');
}
