/** Verify edited-image upload and replacement through the native IDEA and WSL app-server bridge. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const { native, findChat, close } = await connectNative();
const runId = Date.now();
try {
  const existing = await findChat(() => true);
  const threadId = `edit-images-${runId}`;
  await native(existing.page, 'openThread', { thread: { id: threadId, name: 'Edit image attachments', cwd: existing.state.cwd } });
  const { page, chat: source } = await findChat(chat => chat.threadId === threadId && chat.items.length > 0);
  const item = source.items.find(item => item.type === 'userMessage');
  const message = page.locator(`article[data-message-id="${item.id}"]`);
  await native(page, 'draft', { text: 'Keep this main composer draft.', attachments: [] });
  async function begin() {
    await native(page, 'openThread', { id: source.id });
    await message.hover();
    await message.getByRole('button', { name: 'Edit message', exact: true }).click();
    return message.getByRole('textbox', { name: 'Edit message text', exact: true });
  }
  async function replaceImage(field, filename) {
    await message.getByRole('button', { name: 'Remove preview.svg', exact: true }).click();
    await field.evaluate((field, filename) => {
      const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="160"><rect width="320" height="160" fill="#293745"/><circle cx="80" cy="80" r="45" fill="#6BC49A"/><text x="150" y="85" fill="white">Revised image</text></svg>';
      const data = new DataTransfer(); data.items.add(new File([svg], filename, { type: 'image/svg+xml' }));
      field.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
    }, filename);
    await message.getByRole('button', { name: `Preview ${filename}`, exact: true }).locator('img').evaluate(image => image.decode());
  }
  let field = await begin();
  const filename = `replacement-${runId}.svg`;
  await replaceImage(field, filename);
  await field.fill(`REVISED_IMAGE_${runId}`);
  mkdirSync('output/playwright', { recursive: true });
  await page.screenshot({ path: 'output/playwright/native-edit-images.png' });
  await field.press('Control+Enter');
  const revised = await findChat(chat => chat.id !== source.id && JSON.stringify(chat.items).includes(`REVISED_IMAGE_${runId}`));
  const sent = revised.chat.items.find(item => item.type === 'userMessage').content;
  const images = sent.filter(part => part.type === 'localImage' || part.type === 'image');
  assert.equal(images.length, 1);
  assert.ok(images[0].path.endsWith(filename));
  assert.ok(!JSON.stringify(sent).includes('/preview.svg'));
  const unchanged = (await native(page, 'ready')).chat;
  assert.deepEqual(unchanged.items, source.items);
  assert.equal(unchanged.draft, 'Keep this main composer draft.');

  field = await begin();
  await message.getByRole('button', { name: 'Remove preview.svg', exact: true }).click();
  await field.fill(`TEXT_ONLY_EDIT_${runId}`);
  await field.press('Control+Enter');
  const textOnly = await findChat(chat => JSON.stringify(chat.items).includes(`TEXT_ONLY_EDIT_${runId}`));
  assert.equal(textOnly.chat.items.find(item => item.type === 'userMessage').content.filter(part => part.type !== 'text').length, 0);

  field = await begin();
  const retryFile = `retry-image-${runId}.svg`;
  await replaceImage(field, retryFile);
  await field.fill(`FIXTURE_RETRY_EDIT_${runId}`);
  await field.press('Control+Enter');
  const failed = await findChat(chat => chat.error?.includes('Fixture send failed once') && chat.draft?.includes(`FIXTURE_RETRY_EDIT_${runId}`));
  assert.equal(failed.chat.draftInput.length, 1);
  assert.ok(failed.chat.draftInput[0].path.endsWith(retryFile));
  await failed.page.getByRole('button', { name: 'Send message', exact: true }).click();
  await failed.page.getByText('Message received in this conversation. The other tabs are unchanged.', { exact: true }).waitFor();
  const retried = (await native(failed.page, 'ready')).chat;
  assert.ok(JSON.stringify(retried.items).includes(retryFile));
  assert.ok(!JSON.stringify(retried.items).includes('/preview.svg'));
  const result = { nativeImageUpload: true, editedImageReplaced: true, allImagesRemovable: true, originalAndDraftPreserved: true, newTab: true, failedSendKeptEditedImages: true, retry: true };
  writeFileSync('output/playwright/native-edit-images-result.json', JSON.stringify(result, null, 2));
  console.log(result);
} finally { await close(); }
