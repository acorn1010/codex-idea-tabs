/** Check local link failures, retry, and retained chat state without opening external pages. */
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
    const chat = { id: 'links', threadId: 'links', title: 'Links', cwd: '/repo', draft: 'Keep this draft', items: [
      { id: 'answer', type: 'agentMessage', text: '[Overview](/repo/output/index.html#overview)\n\n[Code](/repo/app.java#L12)\n\n[Missing page](/repo/output/a-long-missing-folder/missing.html#overview)\n\n[Website](https://example.com/page#overview)' },
      { id: 'change', type: 'fileChange', status: 'completed', changes: [{ path: '/repo/missing.java', kind: { type: 'update' }, diff: '+ changed' }] },
    ], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1 };
    const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: '/repo', settings: { permissions: 'ask' }, models: [], account: {}, sessions: [chat], chat };
    window.__calls = []; let failures = 0;
    window.__codexSend = raw => {
      const call = JSON.parse(raw); const { id, method, params } = call; window.__calls.push(call);
      const error = method === 'openLink' && (params.path === '/repo/missing.java' || params.path.includes('missing.html') && failures++ === 0) ? `File not found: ${params.path.split('#')[0]}` : undefined;
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, error, result: method === 'ready' ? snapshot : method === 'context' ? { files: [], selection: '' } : { data: [] } } })));
    };
  });
  for (const width of [360, 900]) {
    await page.setViewportSize({ width, height: 850 }); await page.goto(`http://127.0.0.1:${server.address().port}`);
    const field = page.getByRole('textbox', { name: 'Message Codex', exact: true }); await field.waitFor();
    await page.getByRole('link', { name: 'Overview', exact: true }).click();
    await page.getByRole('link', { name: 'Code', exact: true }).click();
    await page.getByRole('link', { name: 'Website', exact: true }).click();
    assert.deepEqual(await page.evaluate(() => window.__calls.filter(call => call.method === 'openLink').map(call => call.params.path)), ['/repo/output/index.html#overview', '/repo/app.java#L12', 'https://example.com/page#overview']);
    const missing = page.getByRole('link', { name: 'Missing page', exact: true }); await missing.click();
    const answer = page.locator('[data-message-id="answer"]'); await answer.getByRole('alert').waitFor();
    assert.equal(await page.getByRole('button', { name: 'Reconnect', exact: true }).count(), 0);
    assert.equal(await field.inputValue(), 'Keep this draft');
    assert.ok(await page.getByText('Ready', { exact: true }).isVisible());
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: `${output}/chat-link-error-${width}.png` });
    await answer.getByRole('button', { name: 'Dismiss', exact: true }).click();
    assert.equal(await answer.getByRole('alert').count(), 0);
    await missing.click(); await page.waitForFunction(() => window.__calls.filter(call => call.method === 'openLink' && call.params.path.includes('missing.html')).length === 2);
    assert.equal(await answer.getByRole('alert').count(), 0);
    const activity = page.locator('[data-activity-group="change"]'); await activity.locator(':scope > button').click();
    await activity.getByRole('link', { name: '/repo/missing.java', exact: true }).click(); await activity.getByRole('alert').waitFor();
    assert.equal(await page.evaluate(() => window.__calls.find(call => call.method === 'openLink' && call.params.path === '/repo/missing.java').params.source), true);
    assert.equal(await page.getByRole('button', { name: 'Reconnect', exact: true }).count(), 0);
    await field.press('Enter'); await page.waitForFunction(() => window.__calls.some(call => call.method === 'send'));
    assert.equal(await page.evaluate(() => window.__calls.find(call => call.method === 'send').params.text), 'Keep this draft');
    assert.equal(await page.evaluate(() => window.__calls.filter(call => call.method === 'reconnect').length), 0);
  }
  assert.deepEqual(errors, []); console.log('Chat links: anchors, source positions, local errors, dismiss, retry, file-change links, retained drafts, and continued sending passed at 360/900px.');
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
