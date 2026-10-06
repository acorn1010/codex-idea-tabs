/** Check session approval selection and expiry for both providers without any live account. */
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
  for (const provider of ['codex', 'claude']) {
    const page = await browser.newPage(); const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(provider => {
      const chat = { id: 'chat', provider, threadId: 'thread', title: 'Chat', cwd: '/repo', draft: '', items: [], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1, claudeSettingsSupported: true };
      const model = { id: 'default', model: 'default', displayName: 'Default', isDefault: true, supportsAutoMode: false, supportedReasoningEfforts: [] };
      const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: '/repo', settings: { permissions: 'ask', permissionRevision: 0, model: 'default', modelSelectionSaved: true }, models: [model], account: {}, sessions: [chat], chat };
      const emit = () => { chat.revision++; window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(snapshot) })); };
      window.__calls = []; window.__saved = 'ask';
      window.__expire = () => { snapshot.settings.permissions = 'ask'; snapshot.settings.permissionRevision++; emit(); };
      window.__codexSend = raw => {
        const call = JSON.parse(raw); const { id, method, params } = call; window.__calls.push(call);
        const reply = result => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result } }));
        if (method === 'permissionPreferences') {
          // Hold the reply so the test can send while this native request is still pending.
          window.__save = () => {
            snapshot.settings.permissions = params.permissions; snapshot.settings.permissionRevision++;
            if (params.permissions !== 'session') { window.__saved = params.permissions; }
            emit(); reply({}); delete window.__save;
          };
          return;
        }
        queueMicrotask(() => reply(method === 'ready' ? snapshot : method === 'context' ? { files: [], selection: '' } : { data: [] }));
      };
    }, provider);
    for (const width of [360, 900]) {
      await page.setViewportSize({ width, height: 760 }); await page.goto(`http://127.0.0.1:${server.address().port}`);
      const field = page.getByRole('textbox', { name: provider === 'claude' ? 'Message Claude' : 'Message Codex', exact: true });
      await field.waitFor();
      await page.getByRole('button', { name: 'Permission mode: Ask me', exact: true }).click();
      const option = page.getByRole('option', { name: /^Approve all for session/ });
      await option.scrollIntoViewIfNeeded(); await option.click();
      await page.waitForFunction(() => typeof window.__save === 'function');
      await field.fill('Check these changes'); await field.press('Enter');
      assert.equal(await page.evaluate(() => window.__calls.filter(call => call.method === 'send').length), 0, 'Sending must wait for the session choice to reach the backend');
      await page.evaluate(() => window.__save());
      await page.waitForFunction(() => window.__calls.some(call => call.method === 'send'));
      assert.equal(await page.evaluate(() => window.__calls.find(call => call.method === 'send').params.permissions), 'session');
      assert.equal(await page.evaluate(() => window.__saved), 'ask', 'Session approval must not change the saved default');
      await page.getByRole('button', { name: 'Permission mode: Approve all for session', exact: true }).click();
      await page.getByText('For this chat until reconnect or close. Questions still need your input.', { exact: true }).waitFor();
      await option.scrollIntoViewIfNeeded(); await page.screenshot({ path: `${output}/session-approvals-${provider}-${width}.png` });
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      await page.keyboard.press('Escape'); await page.evaluate(() => window.__expire());
      await page.getByRole('button', { name: 'Permission mode: Ask me', exact: true }).waitFor();
      await field.fill('After reconnect'); await field.press('Enter');
      await page.waitForFunction(() => window.__calls.filter(call => call.method === 'send').length === 2);
      assert.equal(await page.evaluate(() => window.__calls.filter(call => call.method === 'send').at(-1).params.permissions), 'ask');
    }
    assert.deepEqual(errors, []); await page.close();
  }
  console.log('Session approval UI: both providers, no classifier capability dependency, send ordering, unchanged defaults, expiry, and 360/900px layouts passed.');
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
