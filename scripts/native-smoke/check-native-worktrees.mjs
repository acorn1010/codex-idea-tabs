/** Exercise the workspace picker and native checkout actions through a real IDEA webview. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const { native, findChat, close } = await connectNative();
try {
  const expected = JSON.parse(readFileSync('logs/worktrees-result.json', 'utf8')).fork;
  const { page, chat } = await findChat(chat => chat.id === expected && chat.items.length > 0);
  const workspaces = await native(page, 'workspaces');
  assert.ok(workspaces.entries.some(workspace => workspace.path === chat.cwd));
  const button = page.getByRole('button', { name: /^Workspace:/ });
  await button.click();
  const menu = page.getByRole('dialog', { name: 'Workspace', exact: true });
  await menu.getByRole('button', { name: 'Continue in new worktree', exact: true }).waitFor();
  assert.ok(await menu.getByText(chat.cwd, { exact: true }).isVisible());
  mkdirSync('output/playwright', { recursive: true });
  await page.screenshot({ path: 'output/playwright/native-worktrees-picker.png' });
  await menu.getByRole('button', { name: 'Close workspace menu', exact: true }).click();
  const primaryFile = workspaces.entries.find(workspace => workspace.main).path + '/layout.txt';
  const worktreeFile = chat.cwd + '/layout.txt';
  await native(page, 'openLink', { path: primaryFile });
  await native(page, 'openLink', { path: worktreeFile });
  const context = await native(page, 'context');
  assert.ok(context.files.includes(worktreeFile));
  assert.ok(!context.files.includes(primaryFile), 'Automatic context included another checkout');
  await native(page, 'openThread', { id: chat.id });
  await native(page, 'workspaceReview');
  await native(page, 'workspaceTerminal');
  await native(page, 'workspaceProject');
  const inspection = await native(page, 'inspectWorktree', { path: chat.cwd });
  assert.ok(inspection.blocked.includes('another IDEA project'), inspection.blocked);
  const result = { contextLimitedToWorktree: true, nativeProjectOpened: true, openProjectRemovalBlocked: true, pickerInNativeEditor: true, worktreeListed: true, nativeReviewOpened: true, nativeTerminalOpened: true, cwd: chat.cwd };
  writeFileSync('output/playwright/native-worktrees-ui-result.json', JSON.stringify(result, null, 2));
  console.log(result);
} finally { await close(); }
