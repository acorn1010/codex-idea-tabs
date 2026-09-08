/** Verify WSL image reads and attachment preview controls in the real IDEA webview. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const { native, findChat, close } = await connectNative();
try {
  const { page, chat } = await findChat(chat => chat.id.startsWith('lifecycle-preview-'));
  const thumbnail = page.getByRole('button', { name: 'Preview preview.svg', exact: true });
  await thumbnail.locator('img').evaluate(image => image.decode());
  assert.equal((await thumbnail.locator('img').boundingBox()).width, 20);
  const draft = await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue();
  mkdirSync('output/playwright', { recursive: true });
  await page.screenshot({ path: 'output/playwright/native-attachment-thumbnail.png' });
  await thumbnail.click();
  const dialog = page.getByRole('dialog', { name: 'preview.svg', exact: true });
  await dialog.locator('img').evaluate(image => image.decode());
  assert.ok(await dialog.locator('img').evaluate(image => image.naturalWidth === 640));
  await page.screenshot({ path: 'output/playwright/native-attachment-preview.png' });
  await page.keyboard.press('Escape');
  await dialog.waitFor({ state: 'detached' });
  assert.equal(await thumbnail.evaluate(element => element === document.activeElement), true);
  const saved = await native(page, 'ready');
  assert.equal(saved.chat.draft, draft);
  assert.equal(saved.chat.draftAttachments.length, 1);
  const result = { chatId: chat.id, wslImageRead: true, thumbnail: true, preview: true, escapeReturnsFocus: true, draftAndAttachmentKept: true };
  writeFileSync('output/playwright/native-attachment-preview-result.json', JSON.stringify(result, null, 2));
  console.log(result);
} finally { await close(); }
