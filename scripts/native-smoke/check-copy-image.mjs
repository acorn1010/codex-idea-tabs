/** Check full-size clipboard payloads from inline images, thumbnails, and modal previews. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright'; mkdirSync(output, { recursive: true });
const server = createServer((request, response) => {
  const asset = ({ '/': 'index.html', '/app.js': 'app.js', '/index.css': 'index.css' })[request.url];
  if (!asset) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html');
  response.end(readFileSync(new URL(`../../web/dist/${asset}`, import.meta.url)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CODEX_SMOKE_CHROME || undefined, headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 360, height: 850 } });
  await page.addInitScript(() => {
    const canvas = document.createElement('canvas'); canvas.width = 640; canvas.height = 280;
    const context = canvas.getContext('2d'); context.fillStyle = '#293745'; context.fillRect(0, 0, 640, 280); context.fillStyle = '#6bc49a'; context.fillRect(100, 100, 40, 40);
    const url = canvas.toDataURL('image/png');
    const snapshot = { connection: 'connected', error: '', project: 'Copy image', cwd: '/project', settings: { model: '', effort: '', permissions: 'auto' }, models: [], account: {}, sessions: [], chat: {
      id: 'image', threadId: 'image', title: 'Image copy', cwd: '/project', draft: 'Keep this draft', draftAttachments: [{ name: 'image.png', path: '/image.png', mime: 'image/png', size: 100 }],
      items: [{ id: 'generated', type: 'imageGeneration', status: 'completed', savedPath: '/generated.png' }], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1,
    } };
    window.__copies = [];
    window.__codexSend = raw => {
      const { id, method, params } = JSON.parse(raw);
      if (method === 'copyImage') { window.__copies.push(params); }
      const result = method === 'ready' ? snapshot : method === 'readImage' ? { url } : {};
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result, error: method === 'copyImage' && window.__copyFails ? 'Clipboard is busy. Try again.' : undefined } })));
    };
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  const inline = page.getByRole('button', { name: 'Preview Generated image', exact: true });
  await inline.locator('img').evaluate(image => image.decode());
  await inline.click({ button: 'right' });
  const menu = page.getByRole('menu', { name: 'Image actions' });
  await menu.getByRole('menuitem', { name: 'Copy image', exact: true }).waitFor();
  await page.screenshot({ path: `${output}/copy-image-menu.png` });
  await menu.getByRole('menuitem', { name: 'Copy image', exact: true }).click();
  await menu.getByText('Image copied', { exact: true }).waitFor();
  await menu.waitFor({ state: 'detached' });
  await inline.click();
  const dialog = page.getByRole('dialog', { name: 'Generated image', exact: true });
  await dialog.locator('img').click({ button: 'right' });
  await menu.waitFor();
  assert.equal(await menu.evaluate(element => !!element.closest('dialog')), true, 'Menu must be interactive above the modal image');
  await page.keyboard.press('Escape');
  await menu.waitFor({ state: 'detached' });
  assert.equal(await dialog.isVisible(), true, 'Escape dismisses only the menu');
  await dialog.locator('img').click({ button: 'right' });
  await menu.getByRole('menuitem', { name: 'Copy image', exact: true }).click();
  await menu.getByText('Image copied', { exact: true }).waitFor();
  await menu.waitFor({ state: 'detached' });
  await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'detached' });
  const thumbnail = page.getByRole('button', { name: 'Preview image.png', exact: true });
  await thumbnail.click({ button: 'right' });
  await menu.getByRole('menuitem', { name: 'Copy image', exact: true }).click();
  await menu.getByText('Image copied', { exact: true }).waitFor();
  await menu.waitFor({ state: 'detached' });
  const pixels = await page.evaluate(async () => Promise.all(window.__copies.map(async ({ data }) => {
    const image = new Image(); image.src = 'data:image/png;base64,' + data; await image.decode();
    const canvas = document.createElement('canvas'); canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
    const context = canvas.getContext('2d'); context.drawImage(image, 0, 0);
    return { width: canvas.width, height: canvas.height, pixel: Array.from(context.getImageData(110, 110, 1, 1).data) };
  })));
  assert.equal(pixels.length, 3);
  for (const image of pixels) { assert.deepEqual(image, { width: 640, height: 280, pixel: [107, 196, 154, 255] }); }
  await page.evaluate(() => { window.__copyFails = true; });
  await thumbnail.click({ button: 'right' });
  await menu.getByRole('menuitem', { name: 'Copy image', exact: true }).click();
  await menu.getByRole('alert').waitFor();
  await page.evaluate(() => { window.__copyFails = false; });
  await menu.getByRole('menuitem', { name: 'Copy image', exact: true }).click();
  await menu.getByText('Image copied', { exact: true }).waitFor();
  assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue(), 'Keep this draft');
  const result = { inlineImage: true, expandedImage: true, thumbnail: true, fullResolutionAndPixels: true, escapeKeepsPreview: true, failureAndRetry: true, draftPreserved: true };
  writeFileSync(`${output}/copy-image-result.json`, JSON.stringify(result, null, 2)); console.log(result);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
