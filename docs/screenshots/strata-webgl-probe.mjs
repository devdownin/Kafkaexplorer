// Real Chromium/WebGL interaction probe for Kafka Strata. Uses the built JAR's SPA
// and the same contract-backed fixture server as the documentation screenshots.
// Usage: NODE_PATH="$(npm root -g)" node strata-webgl-probe.mjs http://127.0.0.1:4176
import { createRequire } from 'node:module';
import { failOnUnstubbedRoutes } from './unstubbed.mjs';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const baseUrl = process.argv[2] ?? 'http://127.0.0.1:4176';
const browser = await chromium.launch({
  ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}),
  args: ['--enable-webgl', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
});
let failure = null;
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 });
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto(baseUrl + '/strata?sv.mode=compare&sv.topic=demo.orders.1.received' +
    '&sv.peer=demo.orders.2.validated&sv.bucket=4&sv.quality=economy', { waitUntil: 'networkidle' });
  await page.getByRole('heading', { name: 'Kafka Strata 3D' }).waitFor();
  const canvas = page.locator('div[role="img"][aria-label^="Interactive Kafka topic activity"] canvas');
  await canvas.waitFor({ timeout: 15_000 });
  const glReady = await canvas.evaluate(element => {
    const gl = element.getContext('webgl2');
    return !!gl && gl.isContextLost() === false && gl.drawingBufferWidth > 0;
  });
  if (!glReady) throw new Error('Three.js canvas has no live WebGL2 context');
  if (await page.getByText(/3D rendering unavailable or context lost/).count()) {
    throw new Error('WebGL fallback displayed despite a live context');
  }
  if (await page.locator('#strata-time-lens').inputValue() !== '4') {
    throw new Error('Shared time bucket was not restored');
  }
  const peer = page.getByRole('combobox', { name: 'Topic B to compare' });
  if (await peer.inputValue() !== 'demo.orders.2.validated') {
    throw new Error('Shared comparison peer was not restored');
  }

  // Pause pulsing so pixel differences can only come from the camera gesture.
  await page.getByRole('button', { name: 'Pause motion' }).click();
  await page.waitForTimeout(180);
  const before = await canvas.screenshot();
  const box = await canvas.boundingBox();
  if (!box) throw new Error('WebGL canvas has no measurable bounds');
  const x = box.x + box.width * 0.65, y = box.y + box.height * 0.55;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + 90, y + 45, { steps: 6 });
  await page.mouse.up();
  await page.waitForTimeout(180);
  const after = await canvas.screenshot();
  if (before.equals(after)) throw new Error('Orbit gesture produced no new rendered frame');

  await page.getByRole('combobox', { name: '3D render quality' }).selectOption('detail');
  await page.locator('section[aria-label="Spatial view presets"]')
    .getByRole('button', { name: 'Copy view link' }).click();
  const shared = page.url();
  if (!shared.includes('sv.quality=detail') || !shared.includes('sv.peer=demo.orders.2.validated')) {
    throw new Error('The shareable view URL did not encode visible controls');
  }
  await page.reload({ waitUntil: 'networkidle' });
  if (await page.getByRole('combobox', { name: '3D render quality' }).inputValue() !== 'detail') {
    throw new Error('Render quality failed to restore from the shared URL');
  }
  if (await page.getByRole('combobox', { name: 'Topic B to compare' }).inputValue() !== 'demo.orders.2.validated') {
    throw new Error('Comparison failed to restore from the shared URL');
  }

  await page.locator('section[aria-label="Spatial view presets"]')
    .getByRole('button', { name: 'Focus selected' }).click();
  await page.getByText(/Focused exploration · demo.orders.1.received/).waitFor({ timeout: 10_000 });
  await page.getByText(/3\/3 partitions measured/).first().waitFor({ timeout: 10_000 });
  await page.locator('section[aria-label="Spatial view presets"]')
    .getByRole('button', { name: 'Overview' }).click();
  if (await page.getByText(/Focused exploration · demo.orders.1.received/).count()) {
    throw new Error('Overview did not clear the focused view');
  }
  // Lot C regression: keyboard-only navigation, layer menu and narrow viewport.
  // The application-wide Ctrl/⌘+K palette remains owned by Layout.
  await page.evaluate(() => {
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
  });
  await page.keyboard.press('?');
  await page.getByRole('dialog', { name: 'Keyboard navigation' }).waitFor();
  await page.keyboard.press('Escape');
  if (await page.getByRole('dialog', { name: 'Keyboard navigation' }).count()) {
    throw new Error('Keyboard help did not close on Escape');
  }
  await page.keyboard.press('/');
  const filter = page.getByRole('textbox', { name: 'Filter topics' });
  if (!await filter.evaluate(element => element === document.activeElement)) {
    throw new Error('Topic search did not receive focus from the / shortcut');
  }
  await filter.fill('');
  await page.getByRole('button', { name: /Layers ·/ }).click();
  const layerMenu = page.locator('#strata-layer-options');
  await layerMenu.waitFor();
  await layerMenu.getByRole('checkbox', { name: /Lineage/ }).uncheck();
  if (await layerMenu.getByRole('checkbox', { name: /Lineage/ }).isChecked()) {
    throw new Error('Declared-lineage layer could not be disabled');
  }
  await page.keyboard.press('Escape');
  if (await layerMenu.count()) throw new Error('Layer menu did not close on Escape');

  await page.setViewportSize({ width: 390, height: 844 });
  const bounds = await page.locator('[data-testid="strata-workspace"]').boundingBox();
  if (!bounds || bounds.width > 390 + 3) {
    throw new Error('Spatial workspace overflows the mobile viewport');
  }
  await page.getByRole('region', { name: 'Temporal lens' }).waitFor();
  await page.getByRole('button', { name: 'Show inspector' }).click();
  await page.getByRole('region', { name: 'Topic inspector and comparison' }).waitFor();
  if (pageErrors.length) throw new Error('Uncaught browser errors: ' + pageErrors.join('; '));
  console.log('Strata WebGL: context, orbit, URL round-trip, partitions, keyboard, layers and mobile layout passed.');
} catch (error) {
  failure = error;
} finally {
  await browser.close();
}
await failOnUnstubbedRoutes(baseUrl);
if (failure) throw failure;
