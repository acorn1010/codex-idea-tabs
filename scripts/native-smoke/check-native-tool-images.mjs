/** Verify image-reader results and preview controls through the native backend and JCEF. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const { native, findChat, close } = await connectNative();
try {
  const { page } = await findChat(chat => chat.title === 'Claude image reader fixture');
  const preview = page.getByRole('button', { name: 'Preview Generated image', exact: true });
  await preview.waitFor();
  await preview.locator('img').evaluate(image => image.decode());
  assert.deepEqual(await preview.locator('img').evaluate(image => [image.naturalWidth, image.naturalHeight]), [2000, 333]);
  assert.equal(await page.getByText('[Image: original 2160x360', { exact: false }).count(), 0);
  await preview.click();
  const dialog = page.getByRole('dialog', { name: 'Generated image', exact: true });
  await dialog.locator('img').evaluate(image => image.decode());
  mkdirSync('output/playwright', { recursive: true });
  await page.screenshot({ path: 'output/playwright/native-tool-image-expanded.png' });
  await page.keyboard.press('Escape');
  await dialog.waitFor({ state: 'detached' });
  assert.equal(await preview.evaluate(element => element === document.activeElement), true);
  const state = await native(page, 'ready');
  assert.equal(state.chat.items.some(item => item.id === 'image-note-frame'), false);
  await page.getByRole('button', { name: 'Claude · Read', exact: true }).click();
  assert.equal(await page.locator('pre').filter({ hasText: 'Image result' }).count(), 1);
  assert.equal(await page.locator('pre').filter({ hasText: 'base64' }).count(), 0);
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.screenshot({ path: 'output/playwright/native-tool-image-inline.png' });
  const result = { nativeBackend: true, decodedImage: true, preview: true, restoredImage: true, resizeNoteHidden: true, encodedBytesHidden: true, focusRestored: true };
  writeFileSync('output/playwright/native-tool-images-result.json', JSON.stringify(result, null, 2));
  console.log(result);
} finally { await close(); }
