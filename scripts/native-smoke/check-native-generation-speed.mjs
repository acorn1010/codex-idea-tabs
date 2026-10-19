/** Verify the output-rate sample from real backend notifications in the IntelliJ chat editor. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

mkdirSync('output/playwright', { recursive: true });
const bridge = await connectNative();
try {
  const { page, chat } = await bridge.findChat(chat => chat.generationSpeed?.outputTokens === 100);
  const rate = chat.generationSpeed;
  assert.ok(rate.durationMs >= 900 && rate.durationMs <= 2000);
  assert.ok(Math.abs(rate.tokensPerSecond - 100000 / rate.durationMs) < 0.001);
  for (const theme of ['dark', 'light']) {
    await page.setViewportSize({ width: 360, height: 720 });
    await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
    const summary = page.locator('button[aria-label^="Generation speed:"]');
    await summary.waitFor();
    assert.ok((await summary.innerText()).includes(`~${new Intl.NumberFormat('en', { maximumFractionDigits: 1 }).format(rate.tokensPerSecond)} tok/s`));
    const box = await summary.boundingBox();
    const input = await page.getByRole('textbox', { name: 'Message Codex', exact: true }).boundingBox();
    assert.ok(box.y + box.height <= input.y && box.x >= 0 && box.x + box.width <= 360);
    const working = page.getByText('Codex is working', { exact: true });
    await working.waitFor();
    const statusBox = await working.boundingBox();
    assert.ok(Math.abs(statusBox.y + statusBox.height / 2 - box.y - box.height / 2) < 3, 'Native working status and speed must share a row');
    assert.ok(statusBox.x + statusBox.width <= box.x);
    await summary.focus();
    await page.keyboard.press('Enter');
    assert.ok((await summary.locator('..').innerText()).includes('100 output tokens'));
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    await page.screenshot({ path: `output/playwright/generation-speed-native-${theme}.png` });
    await page.keyboard.press('Enter');
  }
  await page.getByRole('button', { name: 'Stop Codex', exact: true }).click();
  await page.getByText('Codex is working', { exact: true }).waitFor({ state: 'hidden' });
  await page.locator('button[aria-label^="Generation speed:"]').waitFor();
  console.log('Native generation speed passed: real event timing, excluded tool wait, reported tokens, shared working-status row, keyboard details, stopping back to idle, and both themes.');
} finally { await bridge.close(); }
