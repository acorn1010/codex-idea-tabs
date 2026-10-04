/** Check local PR search, archived results, stale replies, and narrow layouts without backend calls. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
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
  const page = await browser.newPage(); const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    const chat = { id: 'current', provider: 'codex', threadId: 'thread', title: 'Current chat', cwd: '/repo', draft: '', items: [], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1 };
    const snapshot = { connection: 'disconnected', error: '', project: 'Project', cwd: '/repo', settings: { permissions: 'ask' }, models: [], account: {}, sessions: [chat], chat };
    const results = [{ ...chat, id: 'creator', title: 'Create the query fix', matchedPullRequest: 'github.com/acme/api#8123' }, { ...chat, id: 'archived-review', provider: 'claude', title: 'Review the query fix', archived: true, matchedPullRequest: 'github.com/acme/worker#8123' }];
    window.__calls = [];
    window.__codexSend = raw => {
      const call = JSON.parse(raw); const { id, method, params } = call; window.__calls.push(call);
      const reply = result => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result } }));
      if (method === 'searchChats') {
        const result = { pullRequestSearch: true, data: params.search.includes('8123') ? results : [] };
        if (params.search === '#9000') { window.__lateReply = () => reply({ ...result, data: [{ ...chat, title: 'Stale result' }] }); return; }
        queueMicrotask(() => reply(result)); return;
      }
      queueMicrotask(() => reply(method === 'ready' ? snapshot : { data: [] }));
    };
  });
  for (const width of [360, 900]) {
    await page.setViewportSize({ width, height: 740 }); await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.getByRole('textbox', { name: 'Message Codex', exact: true }).waitFor();
    await page.keyboard.press('Control+k');
    const search = page.getByRole('textbox', { name: 'Search conversations', exact: true });
    await search.fill('#8123');
    await page.getByRole('button', { name: /Review the query fix.*Archived/ }).waitFor();
    await page.screenshot({ path: `${output}/pr-search-${width}.png` });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.equal(await page.evaluate(() => window.__calls.filter(call => call.method === 'history').length), 0, 'PR search must work offline without invoking backend history');
    await search.fill('#9000'); await page.waitForFunction(() => typeof window.__lateReply === 'function');
    await search.fill('#812'); await page.getByText('No saved chats mention this PR.', { exact: true }).waitFor();
    await page.evaluate(() => window.__lateReply());
    assert.equal(await page.getByText('Stale result', { exact: true }).count(), 0);
    await search.fill('https://github.com/acme/api/pull/8123');
    await page.getByRole('button', { name: /Review the query fix.*Archived/ }).click();
    await page.waitForFunction(() => window.__calls.some(call => call.method === 'openThread'));
    const opened = await page.evaluate(() => window.__calls.find(call => call.method === 'openThread').params);
    assert.deepEqual(opened, { id: 'archived-review' }, 'Open the saved archived chat without importing or restoring it');
  }
  assert.deepEqual(errors, []); console.log('PR search: offline local results, archives, stale replies, open saved chat, and 360/900px layouts passed.');
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
