/** Verify the context tooltip inside the real IntelliJ JCEF chat. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

mkdirSync('output/playwright', { recursive: true });
const bridge = await connectNative();
try {
  const { page } = await bridge.findChat(chat => chat.tokenUsage?.last?.totalTokens === 23347 && chat.tokenUsage?.modelContextWindow === 93388);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const ring = page.getByRole('progressbar', { name: 'Context usage', exact: true });
  const tooltip = page.getByRole('tooltip');
  for (const theme of ['dark', 'light']) {
    await page.setViewportSize({ width: 360, height: 720 });
    await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
    await ring.hover();
    await tooltip.waitFor();
    assert.ok((await tooltip.innerText()).includes('25% used'));
    assert.ok((await tooltip.innerText()).includes('23,347 of 93,388 tokens used'));
    const box = await tooltip.boundingBox();
    assert.ok(box.x >= 0 && box.x + box.width <= 360);
    assert.ok(box.y >= 0 && box.y + box.height <= (await ring.boundingBox()).y);
    await page.screenshot({ path: `output/playwright/context-tooltip-native-${theme}.png` });
    await page.mouse.move(0, 0);
    await tooltip.waitFor({ state: 'hidden' });
    await ring.focus();
    await tooltip.waitFor();
    await page.keyboard.press('Escape');
    await tooltip.waitFor({ state: 'hidden' });
    await ring.evaluate(element => element.blur());
  }
  assert.deepEqual(errors, []);
  console.log('Native context tooltip passed: visible percent and exact tokens on hover and focus, Escape dismissal, and unclipped 360px layouts in both themes.');
} finally { await bridge.close(); }
