/** Verify repository selection through an actual IDEA chat and the native bridge. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const expected = JSON.parse(readFileSync('logs/repositories-result.json', 'utf8'));
const { native, findChat, close } = await connectNative();
try {
  const { page } = await findChat(chat => chat.id === expected.fork);
  const picker = page.getByRole('button', { name: /^Workspace:/ });
  await picker.click(); const menu = page.getByRole('dialog', { name: 'Workspace', exact: true });
  await menu.getByRole('button', { name: 'Choose repository', exact: true }).click();
  await menu.getByRole('textbox', { name: 'Search repositories' }).fill('server');
  assert.equal(await menu.getByRole('button', { name: /^Use repository/ }).count(), 1);
  mkdirSync('output/playwright', { recursive: true });
  await page.screenshot({ path: 'output/playwright/native-repositories-search.png' });
  await menu.getByRole('button', { name: 'Use repository server', exact: true }).click();
  const target = await findChat(chat => chat.cwd === expected.server && chat.id !== expected.fork);
  assert.equal((await native(page, 'ready')).chat.cwd, expected.client);
  const choices = await native(target.page, 'workspaces');
  assert.equal(choices.repository, expected.server);
  assert.ok(choices.branches.includes('server-main'));
  assert.ok(!choices.branches.includes('client-main'));
  await target.page.getByRole('button', { name: /^Workspace:/ }).click();
  await target.page.getByRole('dialog', { name: 'Workspace', exact: true }).getByRole('button', { name: 'Continue in new worktree', exact: true }).waitFor();
  await target.page.screenshot({ path: 'output/playwright/native-repositories-selected.png' });
  const result = { picker: true, search: true, newNativeTab: true, originalKept: true, repository: choices.repository, branchesIsolated: true };
  writeFileSync('output/playwright/native-repositories-result.json', JSON.stringify(result, null, 2)); console.log(result);
} finally { await close(); }
