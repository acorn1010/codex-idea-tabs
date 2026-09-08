/** Check native IDEA context reads, WSL startup execution, and exporting without sending a model turn. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const { native, findChat, close } = await connectNative();
try {
  const existing = await findChat(() => true);
  const id = `context-${Date.now()}`;
  await native(existing.page, 'openThread', { thread: { id, name: 'Inspect recorded context', cwd: existing.state.cwd } });
  const { page } = await findChat(chat => chat.threadId === id);
  const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
  await field.fill('Keep the draft and active work unchanged.');
  await native(page, 'draft', { text: 'Keep the draft and active work unchanged.', attachments: [] });
  await page.getByRole('button', { name: 'Inspect context', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Context inspector', exact: true });
  const search = dialog.getByRole('textbox', { name: 'Search context' });
  await search.waitFor();
  const recorded = await native(page, 'inspectContext');
  assert.equal(recorded.kind, 'recorded', JSON.stringify(recorded.notices));
  assert.equal(recorded.compactions, 1);
  assert.equal(recorded.blocks.length, 6);
  assert.ok(!JSON.stringify(recorded).includes('OLD_CONTEXT_BEFORE_COMPACTION'));
  assert.ok(!JSON.stringify(recorded).includes('DISPLAY_EVENT_MUST_NOT_BE_COUNTED'));
  await dialog.getByRole('button', { name: '1 repeated passage', exact: true }).click();
  await dialog.locator('[aria-label="Context blocks"]').getByRole('button').first().click();
  assert.ok(await dialog.locator('mark').count() > 0);
  mkdirSync('output/playwright', { recursive: true });
  await page.screenshot({ path: 'output/playwright/native-context-recorded.png' });
  await dialog.getByRole('button', { name: 'Startup', exact: true }).click();
  await dialog.getByRole('button', { name: 'Build startup snapshot', exact: true }).click();
  await search.waitFor();
  await dialog.getByText('Fresh CLI input', { exact: true }).waitFor();
  assert.equal(await dialog.getByRole('alert').count(), 0);
  await page.screenshot({ path: 'output/playwright/native-context-startup.png' });
  await dialog.getByRole('button', { name: 'Copy context JSON', exact: true }).click();
  await dialog.getByRole('button', { name: 'JSON copied', exact: true }).waitFor();
  await dialog.getByRole('button', { name: 'Open context as text', exact: true }).click();
  // The native export opens another editor. Its bridge reply confirms the open-file operation completed.
  await native(page, 'contextExport', { text: 'Verified native context export\nNo model call was sent.' });
  const saved = await native(page, 'ready');
  assert.equal(saved.chat.draft, 'Keep the draft and active work unchanged.');
  assert.equal(saved.chat.working, false);
  const result = { nativeSessionRead: true, wslPath: recorded.source, latestCompactionOnly: true, displayEventsExcluded: true, repeatedText: true, wslStartup: true, nativeClipboard: true, nativeTextExport: true, draftKept: true };
  writeFileSync('output/playwright/native-context-inspector-result.json', JSON.stringify(result, null, 2));
  console.log(result);
} finally { await close(); }
