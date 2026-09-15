/** Verify IDEA navigation actions use Chromium's selection behavior without editing or sending text. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';
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
  await page.addInitScript(() => {
    window.__codexTextNavigation = true;
    window.__requests = [];
    const chat = { id: 'navigation', threadId: 'navigation', title: 'Navigation', cwd: '/project', draft: 'one two three\nfour five', items: [{ id: 'old', type: 'userMessage', content: [{ type: 'text', text: 'one two three\nfour five' }] }], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1 };
    const state = { connection: 'connected', error: '', project: 'Project', cwd: chat.cwd, distro: '', settings: { model: '', effort: '', permissions: 'ask' }, models: [], account: {}, sessions: [], chat };
    window.__codexSend = raw => {
      const message = JSON.parse(raw); window.__requests.push(message);
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id: message.id, result: message.method === 'ready' ? state : {} } })));
    };
  });
  const results = [];
  for (const width of [360, 900]) {
    await page.setViewportSize({ width, height: 850 });
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
    await field.waitFor(); await field.focus();
    async function move(action, start, end = start, direction = 'none') {
      return field.evaluate((element, { action, start, end, direction }) => {
        element.focus(); element.setSelectionRange(start, end, direction);
        window.dispatchEvent(new CustomEvent('codex-text-navigation', { detail: action }));
        return [element.selectionStart, element.selectionEnd];
      }, { action, start, end, direction });
    }
    assert.deepEqual(await move('EditorPreviousWord', 7), [4, 4]);
    assert.deepEqual(await move('EditorNextWord', 4), [7, 7]);
    assert.deepEqual(await move('EditorPreviousWordWithSelection', 7), [4, 7]);
    assert.deepEqual(await move('EditorNextWordWithSelection', 4), [4, 7]);
    assert.deepEqual(await move('EditorLineStart', 7), [0, 0]);
    assert.deepEqual(await move('EditorLineEnd', 4), [13, 13]);
    assert.deepEqual(await move('EditorLineStartWithSelection', 7), [0, 7]);
    assert.deepEqual(await move('EditorLineEndWithSelection', 4), [4, 13]);
    assert.deepEqual(await move('EditorLineStart', 19), [14, 14]);
    assert.deepEqual(await move('EditorPreviousWordWithSelection', 4, 7, 'backward'), [0, 7]);
    assert.deepEqual(await move('EditorNextWordWithSelection', 4, 7, 'backward'), [7, 7]);
    await field.dispatchEvent('compositionstart');
    assert.deepEqual(await move('EditorPreviousWord', 7), [7, 7]);
    await field.dispatchEvent('compositionend');
    assert.deepEqual(await move('UnknownAction', 7), [7, 7]);
    assert.equal(await field.inputValue(), 'one two three\nfour five');
    await page.getByRole('button', { name: 'Edit message', exact: true }).focus();
    await page.keyboard.press('Enter');
    const edit = page.getByRole('textbox', { name: 'Edit message text', exact: true });
    await edit.waitFor();
    const editSelection = await edit.evaluate(element => {
      element.focus(); element.setSelectionRange(7, 7);
      window.dispatchEvent(new CustomEvent('codex-text-navigation', { detail: 'EditorPreviousWordWithSelection' }));
      return [element.selectionStart, element.selectionEnd];
    });
    assert.deepEqual(editSelection, [4, 7]);
    const cancel = page.getByRole('button', { name: 'Cancel', exact: true });
    await cancel.focus();
    await page.evaluate(() => window.dispatchEvent(new CustomEvent('codex-text-navigation', { detail: 'EditorPreviousWord' })));
    assert.ok(await cancel.evaluate(element => element === document.activeElement));
    assert.equal(await page.evaluate(() => window.__requests.filter(request => ['send', 'editMessage'].includes(request.method)).length), 0);
    results.push({ width, wordMovement: true, lineMovement: true, selection: true, inlineEdit: true, composingKept: true, draftKept: true, noMessagesSent: true });
  }
  mkdirSync('output/playwright', { recursive: true });
  writeFileSync('output/playwright/text-navigation-result.json', JSON.stringify(results, null, 2)); console.log(results);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
