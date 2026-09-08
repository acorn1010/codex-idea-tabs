/** Exercise composer recall, normal cursor movement, and cancellation of slow history loads. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright';
mkdirSync(output, { recursive: true });
const server = createServer((request, response) => {
  const asset = ({ '/': 'index.html', '/app.js': 'app.js', '/index.css': 'index.css' })[request.url];
  if (!asset) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html');
  response.end(readFileSync(new URL(`../../web/dist/${asset}`, import.meta.url)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CODEX_SMOKE_CHROME || undefined, headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 520, height: 800 } });
  await page.addInitScript(() => {
    window.__requests = [];
    const older = { id: 'old', type: 'userMessage', content: [{ type: 'text', text: 'Oldest message' }] };
    const middle = { id: 'middle', type: 'userMessage', content: [{ type: 'text', text: 'Middle message' }] };
    window.__snapshot = { connection: 'connected', error: '', project: 'Recall check', cwd: '/project', distro: '', settings: { model: '', effort: '', permissions: 'auto' }, models: [], account: {}, sessions: [], chat: {
      id: 'recall', threadId: 'recall', title: 'Message recall', cwd: '/project', draft: '', historyCursor: 'tools', draftAttachments: [{ name: 'keep.txt', path: '/project/keep.txt', mime: 'text/plain', size: 5 }],
      items: [middle, { id: 'assistant', type: 'agentMessage', text: 'Assistant text must not enter recall.' }, { id: 'latest', type: 'userMessage', content: [{ type: 'text', text: 'Newest message\nSecond line' }] }, { id: 'attachment', type: 'userMessage', content: [{ type: 'localImage', path: '/image.png' }] }, { id: 'empty', type: 'userMessage', content: [{ type: 'text', text: '  ' }] }],
      requests: [], status: 'working', working: true, unread: false, pinned: false, revision: 1,
    } };
    window.__publish = () => window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(window.__snapshot) }));
    window.__codexSend = message => {
      const { id, method, params } = JSON.parse(message);
      window.__requests.push({ method, params });
      let result = method === 'ready' ? window.__snapshot : method === 'readImage' ? { url: 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg"/>' } : {};
      const error = method === 'older' && window.__rejectOlder ? 'History unavailable' : undefined;
      if (method === 'older') {
        result = params.cursor === 'tools' ? { data: [{ item: { id: 'tool', type: 'commandExecution', command: 'echo tool' } }], nextCursor: 'oldest' } : { data: [{ item: middle }, { item: older }], nextCursor: null };
      }
      const reply = () => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result, error } }));
      if (method === 'older' && window.__deferOlder) { window.__releaseOlder = reply; }
      else { queueMicrotask(reply); }
    };
  });
  const start = () => page.goto(`http://127.0.0.1:${server.address().port}/`);
  const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
  const value = async expected => {
    await page.waitForFunction(expected => document.querySelector('textarea[aria-label="Message Codex"]').value === expected, expected);
  };
  await start();
  await field.press('ArrowUp'); await value('Newest message\nSecond line');
  assert.equal(await field.evaluate(element => element.selectionStart), 'Newest message\nSecond line'.length, 'Caret starts at the end');
  // New streamed messages must not shift the position of an in-progress recall sequence.
  await page.evaluate(() => { window.__snapshot.chat.items.push({ id: 'live', type: 'userMessage', text: 'New message while recalling' }); window.__publish(); });
  await field.press('ArrowUp'); await value('Middle message');
  await field.press('ArrowUp'); await value('Oldest message');
  await field.press('ArrowUp'); await value('Oldest message');
  assert.deepEqual(await page.evaluate(() => window.__requests.filter(request => request.method === 'older').map(request => request.params.cursor)), ['tools', 'oldest']);
  assert.equal(await page.getByRole('button', { name: 'Remove keep.txt' }).count(), 1, 'Recall keeps current attachments');
  await page.screenshot({ path: `${output}/message-recall-oldest.png` });
  assert.equal(await page.evaluate(() => window.__requests.filter(request => request.method === 'send').length), 0, 'Recall never sends');

  for (const key of ['ArrowLeft', 'ArrowRight', 'ArrowDown', 'Escape', 'Home', 'End', 'Shift', 'Control']) {
    await start(); await field.press('ArrowUp'); await value('Newest message\nSecond line');
    await field.press(key); await field.press('ArrowUp');
    assert.equal(await field.inputValue(), 'Newest message\nSecond line', `${key} stops recall`);
  }
  await start(); await field.press('ArrowUp'); await field.press('x'); await field.press('ArrowUp');
  assert.equal(await field.inputValue(), 'Newest message\nSecond linex', 'Typing edits the recalled message');
  await field.fill('My existing draft\nSecond line'); await field.press('ArrowUp');
  assert.equal(await field.inputValue(), 'My existing draft\nSecond line', 'Up does not replace an existing draft');
  await field.fill(''); await field.press('Shift+ArrowUp'); assert.equal(await field.inputValue(), '', 'Modified Up keeps native selection behavior');
  await field.dispatchEvent('keydown', { key: 'ArrowUp', isComposing: true }); assert.equal(await field.inputValue(), '', 'IME composition cannot trigger recall');
  await field.press('ArrowUp'); await page.getByRole('button', { name: 'Stop Codex' }).focus(); await field.focus(); await field.press('ArrowUp');
  assert.equal(await field.inputValue(), 'Newest message\nSecond line', 'Leaving the composer ends recall');

  await start(); await field.press('ArrowUp'); await field.press('ArrowUp');
  await page.evaluate(() => { window.__deferOlder = true; });
  await field.press('ArrowUp');
  await page.waitForFunction(() => Boolean(window.__releaseOlder));
  await field.press('ArrowUp');
  assert.equal(await page.evaluate(() => window.__requests.filter(request => request.method === 'older').length), 1, 'Repeated Up shares the pending history request');
  await field.press('!');
  await page.evaluate(() => window.__releaseOlder());
  await page.waitForTimeout(100);
  assert.equal(await field.inputValue(), 'Middle message!', 'A late history reply cannot overwrite typing');

  await start(); await page.evaluate(() => { window.__rejectOlder = true; });
  await field.press('ArrowUp'); await field.press('ArrowUp'); await field.press('ArrowUp');
  await page.getByRole('alert').filter({ hasText: 'History unavailable' }).waitFor();
  assert.equal(await field.inputValue(), 'Middle message', 'History failure keeps the recalled draft');
  await page.evaluate(() => { window.__rejectOlder = false; });
  await field.press('ArrowUp'); await value('Oldest message');
  await page.getByRole('alert').waitFor({ state: 'detached' });

  await start(); await field.press('ArrowUp'); await field.press('Control+Enter'); await value('');
  const sends = await page.evaluate(() => window.__requests.filter(request => request.method === 'send'));
  assert.equal(sends.length, 1); assert.equal(sends[0].params.text, 'Newest message\nSecond line');
  const result = { newestToOldest: true, pagedHistory: true, duplicatesSkipped: true, stableDuringStreaming: true, oldestStops: true, anyOtherKeyStops: true, editableDraft: true, draftProtected: true, modifiersAndIme: true, lateHistoryCanceled: true, historyRetry: true, attachmentsKept: true, ctrlEnterPreserved: true };
  writeFileSync(`${output}/message-recall-result.json`, JSON.stringify(result, null, 2));
  console.log(result);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
