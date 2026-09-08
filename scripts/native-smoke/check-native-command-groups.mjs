/** Verify that a real IDEA editor groups command history received from the fixture app server. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const { native, findChat, close } = await connectNative();
try {
  const existing = await findChat(() => true);
  const threadId = `activity-${Date.now()}`;
  await native(existing.page, 'openThread', { thread: { id: threadId, name: 'Grouped command check', cwd: existing.state.cwd } });
  const { page } = await findChat(chat => chat.threadId === threadId && chat.items.length > 20);
  const group = page.locator('[data-activity-group]');
  assert.equal(await group.count(), 1);
  const toggle = group.locator(':scope > button');
  assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
  assert.ok((await toggle.innerText()).includes('20 commands'));
  assert.ok((await toggle.innerText()).includes('1 failed'));
  assert.ok((await group.boundingBox()).height <= 40);
  mkdirSync('output/playwright', { recursive: true });
  await page.screenshot({ path: 'output/playwright/native-commands-collapsed.png' });
  await toggle.click();
  const details = group.getByRole('region', { name: 'Activity details' });
  assert.ok(await details.evaluate(element => element.scrollHeight > element.clientHeight && element.clientHeight <= 256));
  await details.getByRole('button', { name: 'npm run check -- workspace-7 Failed', exact: true }).click();
  await details.getByText('Check failed: missing import', { exact: true }).waitFor();
  await page.screenshot({ path: 'output/playwright/native-commands-expanded.png' });
  await toggle.focus();
  await page.keyboard.press('Enter');
  assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
  const result = { nativeHistory: true, twentyCommandsInOneRow: true, boundedExpansion: true, failureOutput: true, keyboard: true };
  writeFileSync('output/playwright/native-command-groups-result.json', JSON.stringify(result, null, 2));
  console.log(result);
} finally { await close(); }
