/** Exercise edited-message image selection, paste/drop, removal, cancellation, and upload races. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright';
mkdirSync(output, { recursive: true });
const server = createServer((request, response) => {
  const asset = ({ '/': 'index.html', '/app.js': 'app.js', '/index.css': 'index.css' })[request.url.split('?')[0]];
  if (!asset) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html');
  response.end(readFileSync(new URL(`../../web/dist/${asset}`, import.meta.url)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CODEX_SMOKE_CHROME || undefined, headless: true });
try {
  const page = await browser.newPage();
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="280"><rect width="640" height="280" rx="24" fill="#293745"/><circle cx="115" cy="140" r="60" fill="#62aef7"/><text x="210" y="151" font-family="sans-serif" font-size="28" fill="#e6edf3">Image preview works</text></svg>';
  await page.addInitScript(({ svg }) => {
    const snapshot = { connection: 'connected', error: '', project: 'Codex Tabs', cwd: '/project', distro: 'Ubuntu', settings: { model: '', effort: '', permissions: 'auto' }, models: [], account: { requiresOpenaiAuth: false }, sessions: [], chat: {
      id: 'edit', threadId: 'edit', title: 'Edit images', cwd: '/project', draft: 'Keep the main composer draft.', draftAttachments: [],
      items: [{ id: 'message', type: 'userMessage', content: [{ type: 'text', text: 'Please review the original image.' }, { type: 'localImage', path: '/project/original.png' }] }, { id: 'reply', type: 'agentMessage', text: 'Ready for your revisions.' }],
      requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1,
    } };
    window.__requests = [];
    window.__svg = svg;
    window.__codexSend = message => {
      const { id, method, params } = JSON.parse(message);
      window.__requests.push({ method, params });
      const attachment = { name: params.name || 'selected.png', path: `/project/${params.name || 'selected.png'}`, mime: 'image/png', size: 200 };
      const result = method === 'ready' ? snapshot : method === 'readImage' ? { url: 'data:image/svg+xml;base64,' + btoa(svg) } : method === 'chooseImages' ? { files: [attachment] } : method === 'attachment' ? attachment : {};
      const reply = () => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result, error: method === 'editMessage' && window.__rejectEdit ? 'Could not create the revised chat. Try again.' : undefined } }));
      if (method === 'attachment' && window.__delayUpload) { window.__completeUpload = reply; }
      else { queueMicrotask(reply); }
    };
  }, { svg });
  async function startEdit() {
    const message = page.locator('article[data-message-id="message"]');
    await message.hover();
    await message.getByRole('button', { name: 'Edit message', exact: true }).click();
    return message;
  }
  async function pasteImage(message, name = 'pasted.png') {
    await message.getByRole('textbox', { name: 'Edit message text', exact: true }).evaluate((field, name) => {
      const data = new DataTransfer(); data.items.add(new File([window.__svg], name, { type: 'image/png' }));
      field.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
    }, name);
  }
  const results = [];
  for (const width of [360, 520, 900]) {
    await page.setViewportSize({ width, height: 850 });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const message = await startEdit();
    await message.getByRole('button', { name: 'Preview original.png', exact: true }).locator('img').evaluate(image => image.decode());
    await message.getByRole('button', { name: 'Attach images', exact: true }).click();
    await message.getByRole('button', { name: 'Remove selected.png', exact: true }).waitFor();
    await message.getByRole('button', { name: 'Remove original.png', exact: true }).click();
    await message.getByRole('button', { name: 'Preview selected.png', exact: true }).click();
    await page.getByRole('dialog', { name: 'selected.png', exact: true }).waitFor();
    await page.keyboard.press('Escape');
    await page.getByRole('dialog').waitFor({ state: 'detached' });
    assert.ok(await message.getByRole('textbox', { name: 'Edit message text', exact: true }).isVisible(), 'Escape from preview does not cancel editing');
    await pasteImage(message);
    await message.getByRole('button', { name: 'Remove pasted.png', exact: true }).waitFor();
    await message.locator('[data-message-editor]').evaluate(editor => {
      const data = new DataTransfer(); data.items.add(new File([window.__svg], 'dropped.png', { type: 'image/png' }));
      editor.dispatchEvent(new DragEvent('dragover', { dataTransfer: data, bubbles: true, cancelable: true }));
      editor.dispatchEvent(new DragEvent('drop', { dataTransfer: data, bubbles: true, cancelable: true }));
    });
    await message.getByRole('button', { name: 'Remove dropped.png', exact: true }).waitFor();
    assert.equal(await page.locator('footer').getByRole('button', { name: /^Remove / }).count(), 0, 'Images must not enter the main composer');
    assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue(), 'Keep the main composer draft.');
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: `${output}/edit-images-${width}.png` });
    await message.getByRole('button', { name: 'Cancel', exact: true }).click();
    await startEdit();
    assert.equal(await message.getByRole('button', { name: /^Remove / }).count(), 1, 'Cancel discards added images');
    await message.getByRole('button', { name: 'Remove original.png', exact: true }).click();
    const field = message.getByRole('textbox', { name: 'Edit message text', exact: true });
    await field.fill('');
    assert.equal(await message.getByRole('button', { name: 'Edit and resend', exact: true }).isDisabled(), true, 'An empty message cannot be sent');
    await message.getByRole('button', { name: 'Attach images', exact: true }).click();
    await message.getByRole('button', { name: 'Remove selected.png', exact: true }).waitFor();
    await page.evaluate(() => { window.__rejectEdit = true; });
    await field.press('Control+Enter');
    await message.getByRole('alert').waitFor();
    assert.equal(await message.getByRole('button', { name: 'Remove selected.png', exact: true }).count(), 1, 'A failed edit keeps its image');
    await page.evaluate(() => { window.__rejectEdit = false; });
    await field.press('Control+Enter');
    await field.waitFor({ state: 'detached' });
    const edits = await page.evaluate(() => window.__requests.filter(request => request.method === 'editMessage'));
    assert.equal(edits.length, 2);
    assert.deepEqual(edits[1].params.images, [{ type: 'localImage', path: '/project/selected.png' }]);
    assert.equal(edits[1].params.text, '', 'Image-only edits are supported');
    results.push({ width, picker: true, paste: true, drop: true, remove: true, preview: true, cancel: true, imageOnly: true, failedEditKept: true, mainComposerUntouched: true, noOverflow: true });
  }
  await startEdit();
  const message = page.locator('article[data-message-id="message"]');
  await page.evaluate(() => { window.__delayUpload = true; });
  await pasteImage(message, 'slow.png');
  await page.waitForFunction(() => typeof window.__completeUpload === 'function');
  assert.equal(await message.getByRole('button', { name: 'Edit and resend', exact: true }).isDisabled(), true);
  const editCount = await page.evaluate(() => window.__requests.filter(request => request.method === 'editMessage').length);
  await message.getByRole('textbox', { name: 'Edit message text', exact: true }).press('Control+Enter');
  assert.equal(await page.evaluate(() => window.__requests.filter(request => request.method === 'editMessage').length), editCount, 'Do not resend during upload');
  await message.getByRole('button', { name: 'Cancel', exact: true }).click();
  await startEdit();
  await page.evaluate(() => window.__completeUpload());
  assert.equal(await message.getByRole('button', { name: 'Remove slow.png', exact: true }).count(), 0, 'A canceled upload cannot enter another edit');
  assert.equal(await message.getByRole('button', { name: 'Remove original.png', exact: true }).count(), 1);
  writeFileSync(`${output}/edit-images-result.json`, JSON.stringify({ layouts: results, uploadBlocksSubmit: true, canceledUploadIgnored: true }, null, 2));
  console.log(results);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
