/** Verify rate-limit recovery and Stop through the actual IntelliJ browser and service. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

mkdirSync('output/playwright', { recursive: true });
const bridge = await connectNative();
try {
  const { page } = await bridge.findChat(chat => chat.title === 'API retry fixture');
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.getByRole('button', { name: 'Stop Routeway', exact: true }).click();
  const input = page.getByRole('textbox', { name: 'Message Routeway', exact: true });
  await input.fill('FIXTURE_API_RATE_LIMIT');
  await page.getByRole('button', { name: 'Send message', exact: true }).click();
  const notice = page.getByText(/^Routeway is rate limited/);
  await notice.waitFor();
  assert.equal(await page.getByRole('button', { name: 'Reconnect', exact: true }).count(), 0);
  for (const theme of ['dark', 'light']) {
    for (const width of [360, 900]) {
      await page.setViewportSize({ width, height: 720 });
      await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
      assert.ok((await notice.innerText()).includes('(attempt 1)'));
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
      await page.screenshot({ path: `output/playwright/api-retry-native-${theme}-${width}.png` });
    }
  }
  await page.getByRole('button', { name: 'Stop Routeway', exact: true }).click();
  await notice.waitFor({ state: 'hidden' });
  const stopped = (await bridge.native(page, 'ready')).chat;
  assert.equal(stopped.working, false);
  await page.waitForTimeout(5500);
  assert.equal((await bridge.native(page, 'ready')).chat.items.length, stopped.items.length, 'Cancelled retry still produced a response');
  await input.fill('FIXTURE_API_RATE_LIMIT');
  await page.getByRole('button', { name: 'Send message', exact: true }).click();
  await notice.waitFor();
  await input.fill('Unsent draft remains');
  await notice.waitFor({ state: 'hidden', timeout: 12000 });
  const recovered = (await bridge.native(page, 'ready')).chat;
  assert.equal(recovered.error, '');
  assert.equal(recovered.working, false);
  assert.equal(await input.inputValue(), 'Unsent draft remains');
  assert.equal(recovered.items.length, stopped.items.length + 2, 'Recovery must add only one user message and one answer');
  assert.deepEqual(errors, []);
  console.log('Native API retry passed: increasing wait state, countdown, Stop, automatic recovery, retained draft, one user message, no reconnect button, and both themes at 360/900px.');
} finally { await bridge.close(); }
