/** Check attachment thumbnails, the image viewer, and recovered connection errors in a real browser. */
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
  const image = 'data:image/svg+xml;base64,' + Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="640" height="280"><rect width="640" height="280" rx="24" fill="#293745"/><circle cx="115" cy="140" r="60" fill="#62aef7"/><text x="210" y="151" font-family="sans-serif" font-size="28" fill="#e6edf3">Image preview works</text></svg>').toString('base64');
  await page.addInitScript(({ image }) => {
    window.__snapshot = { connection: 'connected', error: '', project: 'Codex Tabs', cwd: '/project', distro: 'Ubuntu', settings: { model: 'gpt-6-astra', effort: 'xhigh', permissions: 'auto' }, models: [], account: { requiresOpenaiAuth: false }, sessions: [], chat: {
      id: 'preview', threadId: 'preview', title: 'Preview an attached image', cwd: '/project', draft: 'Check this attachment before sending.',
      draftAttachments: [{ name: 'image.png', path: '/project/image.png', mime: 'image/png' }, { name: 'A very long attached image filename that must not overflow the composer.png', path: '/project/long.png', mime: 'image/png' }, { name: 'missing.png', path: '/project/missing.png', mime: 'image/png' }],
      items: [{ id: 'user', type: 'userMessage', content: [{ type: 'text', text: 'Can you check this image?' }, { type: 'localImage', path: '/project/transcript.png' }] }, { id: 'reply', type: 'agentMessage', text: 'The attached image is ready for review.' }],
      requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1,
    } };
    window.__requests = [];
    window.__publish = () => window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(window.__snapshot) }));
    window.__codexSend = message => {
      const { id, method, params } = JSON.parse(message);
      window.__requests.push({ method, params });
      const result = method === 'ready' ? window.__snapshot : method === 'readImage' ? { url: image } : {};
      const error = method === 'readImage' && params.path.endsWith('missing.png') ? 'The image file could not be found.' : method === 'ready' && location.search.includes('ready-failure') ? 'Opening chat needs a retry' : method === 'send' ? 'Send failed. Your draft is kept.' : undefined;
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result, error } })));
    };
  }, { image });
  const results = [];
  for (const width of [360, 520, 900]) {
    await page.setViewportSize({ width, height: 850 });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const thumbnail = page.getByRole('button', { name: 'Preview image.png', exact: true });
    await thumbnail.locator('img').evaluate(image => image.decode());
    assert.equal((await thumbnail.locator('img').boundingBox()).width, 20);
    assert.equal((await thumbnail.locator('img').boundingBox()).height, 20);
    const composer = page.getByRole('textbox', { name: 'Message Codex', exact: true });
    const draft = await composer.inputValue();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `No overflow at ${width}`);
    await thumbnail.hover();
    await page.screenshot({ path: `${output}/attachment-thumbnail-${width}.png` });
    await thumbnail.click();
    const dialog = page.getByRole('dialog', { name: 'image.png', exact: true });
    await dialog.waitFor();
    await dialog.locator('img').evaluate(image => image.decode());
    const bounds = await dialog.locator('img').boundingBox();
    assert.ok(bounds.width > 200 && bounds.width <= width && bounds.height > 200);
    assert.equal(await page.getByRole('button', { name: 'Close image preview' }).evaluate(element => element === document.activeElement), true);
    await page.screenshot({ path: `${output}/attachment-preview-${width}.png` });
    await page.keyboard.press('Escape');
    await dialog.waitFor({ state: 'detached' });
    assert.equal(await thumbnail.evaluate(element => element === document.activeElement), true, 'Escape returns focus to thumbnail');
    assert.equal(await composer.inputValue(), draft, 'Preview does not change draft');
    await page.getByRole('button', { name: 'Remove image.png', exact: true }).click();
    await thumbnail.waitFor({ state: 'detached' });
    assert.equal(await page.getByRole('dialog').count(), 0, 'Removing an attachment does not open preview');
    await page.getByRole('button', { name: 'Preview missing.png', exact: true }).click();
    const missing = page.getByRole('dialog', { name: 'missing.png', exact: true });
    await missing.getByRole('alert').waitFor();
    await missing.getByRole('button', { name: 'Open in editor', exact: false }).click();
    assert.ok(await page.evaluate(() => window.__requests.some(request => request.method === 'openLink' && request.params.path === '/project/missing.png')));
    await missing.getByRole('button', { name: 'Close image preview' }).click();
    const attached = page.locator('article[data-message-id="user"]').getByRole('button', { name: /^Preview / });
    await attached.click();
    await page.getByRole('dialog').waitFor();
    await page.keyboard.press('Escape');
    await page.getByRole('dialog').waitFor({ state: 'detached' });
    results.push({ width, thumbnail: true, preview: true, focusRestored: true, missingFileFallback: true, transcriptPreview: true, draftKept: true, noOverflow: true });
  }
  await page.goto(`http://127.0.0.1:${server.address().port}/?ready-failure`);
  await page.getByRole('alert').filter({ hasText: 'Opening chat needs a retry' }).waitFor();
  await page.evaluate(() => window.__publish());
  await page.getByRole('alert').waitFor({ state: 'detached' });
  await page.getByRole('textbox', { name: 'Message Codex', exact: true }).press('Control+Enter');
  await page.getByRole('alert').filter({ hasText: 'Send failed' }).waitFor();
  await page.evaluate(() => window.__publish());
  assert.ok(await page.getByRole('alert').filter({ hasText: 'Send failed' }).isVisible(), 'A healthy connection must not hide failed sends');
  writeFileSync(`${output}/attachment-preview-result.json`, JSON.stringify({ layouts: results, recoveredConnectionErrorCleared: true, operationErrorKept: true }, null, 2));
  console.log(results);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
