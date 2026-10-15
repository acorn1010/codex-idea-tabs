/** Verify the native guard notice and recovery through the actual IntelliJ composer. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright';
mkdirSync(output, { recursive: true });
const bridge = await connectNative();
try {
  const { page, chat } = await bridge.findChat(chat => chat.error?.startsWith('Loop guard stopped'));
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const notice = page.getByText(/^Loop guard stopped this turn/);
  await notice.waitFor();
  assert.equal(await page.getByRole('button', { name: 'Reconnect', exact: true }).count(), 0);
  await page.getByRole('button', { name: 'Stop Codex', exact: true }).waitFor({ state: 'hidden' });
  const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
  assert.equal(await field.isEnabled(), true);
  for (const theme of ['dark', 'light']) {
    await page.setViewportSize({ width: 360, height: 720 });
    await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    await page.screenshot({ path: `${output}/loop-guard-native-${theme}-360.png` });
  }
  await field.fill('MODEL_SWITCH_FIXTURE');
  await page.getByRole('button', { name: 'Send message', exact: true }).click();
  await notice.waitFor({ state: 'hidden' });
  const state = await bridge.native(page, 'ready');
  assert.equal(state.chat.id, chat.id);
  assert.equal(state.chat.error, '');
  assert.deepEqual(state.chat.localLoopGuard || {}, {});
  assert.deepEqual(errors, []);
  console.log('Native loop guard UI passed: visible stop reason, retained chat, narrow layouts in both themes, and recovery through the composer.');
} finally { await bridge.close(); }
