/** Check the bundled message editor at real browser viewport sizes, independently of JCEF capture limits. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright';
mkdirSync(output, { recursive: true });
const server = createServer((request, response) => {
  const path = request.url.split('?')[0];
  const asset = ({ '/': 'index.html', '/app.js': 'app.js', '/index.css': 'index.css' })[path];
  if (!asset) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html');
  response.end(readFileSync(new URL(`../../web/dist/${asset}`, import.meta.url)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CODEX_SMOKE_CHROME || undefined, headless: true });
try {
  const page = await browser.newPage();
  const image = 'data:image/svg+xml;base64,' + Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="320" height="80"><rect width="320" height="80" fill="#293745"/><text x="16" y="46" font-family="sans-serif" fill="#d9dce3">Attached image stays with the edit</text></svg>').toString('base64');
  await page.addInitScript(({ image }) => {
    const snapshot = { connection: 'connected', error: '', project: 'Codex Tabs', cwd: '/project', distro: 'Ubuntu', settings: { model: '', effort: '', permissions: 'auto' }, models: [], account: { requiresOpenaiAuth: false }, sessions: [], chat: { id: 'layout', threadId: 'layout', title: 'Message editing', cwd: '/project', draft: '', items: [
      { id: 'first', type: 'userMessage', content: [{ type: 'text', text: 'Make the chat workflow easy to use.' }] },
      { id: 'reply', type: 'agentMessage', text: 'You can edit a previous message and continue in a new tab. Your original conversation stays available.' },
      { id: 'edit', type: 'userMessage', content: [{ type: 'text', text: 'Please improve the tab restoration experience.' }, { type: 'image', url: image }] },
    ], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1 } };
    window.__editRequests = [];
    window.__codexSend = message => {
      const { id, method, params } = JSON.parse(message);
      window.__editRequests.push({ method, params });
      const result = method === 'ready' ? snapshot : {};
      const error = method === 'editMessage' && window.__rejectEdit ? 'Could not create the revised chat. Try again.' : undefined;
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result, error } })));
    };
  }, { image });
  const results = [];
  for (const width of [360, 520, 720]) {
    await page.setViewportSize({ width, height: 850 });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const message = page.locator('article[data-message-id="edit"]');
    await message.locator('img').evaluate(image => image.decode());
    const idleHeight = (await message.boundingBox()).height;
    await message.hover();
    assert.equal((await message.boundingBox()).height, idleHeight, 'Hover does not reserve or add an action row');
    assert.equal(await message.locator(':scope > div').last().evaluate(element => getComputedStyle(element).position), 'absolute');
    await page.screenshot({ path: `${output}/message-actions-${width}.png` });
    await message.getByRole('button', { name: 'Edit message', exact: true }).click();
    const field = message.getByRole('textbox', { name: 'Edit message text', exact: true });
    assert.equal(await field.evaluate(element => getComputedStyle(element).cursor), 'text');
    assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).evaluate(element => getComputedStyle(element).cursor), 'text');
    await field.fill('Keep the original conversation, but improve how restored tabs open.\nPreserve this attached image too.');
    assert.equal(await message.getByText('Earlier follow-ups', { exact: false }).count(), 0);
    assert.ok(await message.getByRole('button', { name: 'Edit and resend', exact: true }).getAttribute('title').then(title => title.includes('new tab')));
    assert.equal(await page.getByRole('button', { name: 'Latest', exact: true }).count(), 0, 'Latest cannot cover edit controls');
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `No horizontal overflow at ${width}`);
    for (const name of ['Cancel', 'Edit and resend']) {
      const button = message.getByRole('button', { name, exact: true });
      assert.ok(await button.evaluate(element => {
        const rect = element.getBoundingClientRect();
        return rect.x >= 0 && rect.right <= innerWidth && element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2));
      }), `${name} is visible and unobstructed at ${width}`);
    }
    assert.ok(await message.locator('img').evaluate(image => image.complete && image.naturalWidth > 0));
    await page.screenshot({ path: `${output}/message-edit-layout-${width}.png` });
    await page.evaluate(() => { window.__rejectEdit = true; });
    await field.press('Control+Enter');
    await message.getByRole('alert').waitFor();
    assert.ok((await field.inputValue()).includes('Preserve this attached image'));
    await page.evaluate(() => { window.__rejectEdit = false; });
    await field.press('Control+Enter');
    await field.waitFor({ state: 'detached' });
    const requests = await page.evaluate(() => window.__editRequests.filter(request => request.method === 'editMessage'));
    assert.equal(requests.length, 2, 'One request per keyboard submission');
    assert.equal(requests[1].params.itemId, 'edit');
    assert.ok(requests[1].params.text.includes('\n'));
    const composer = page.getByRole('textbox', { name: 'Message Codex', exact: true });
    await composer.focus();
    const lighting = await composer.evaluate(element => {
      const box = getComputedStyle(element.parentElement);
      return { background: box.backgroundColor, gradient: box.backgroundImage, shadow: box.boxShadow, cursor: box.cursor };
    });
    assert.equal(lighting.background, 'rgb(23, 25, 29)', 'Composer is darker than the surrounding chat');
    assert.equal(lighting.gradient, 'none', 'The input uses a solid fill and keeps only its rim lighting');
    assert.ok(lighting.shadow.includes('inset'));
    assert.equal(lighting.cursor, 'text', 'Blank input space also uses the I-beam cursor');
    await page.screenshot({ path: `${output}/composer-focused-${width}.png` });
    results.push({ width, noOverflow: true, controlsUnobstructed: true, image: true, failedEditPreserved: true, keyboardSubmit: true, compactHoverActions: true, textCursors: true });
  }
  await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
  await page.screenshot({ path: `${output}/composer-light-720.png` });
  writeFileSync(`${output}/message-edit-layout-result.json`, JSON.stringify(results, null, 2));
  console.log(results);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
