/** Check saved auto approval while Claude capabilities load, without a live account or model call. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const server = createServer((request, response) => {
  const asset = ({ '/': 'index.html', '/app.js': 'app.js', '/index.css': 'index.css' })[request.url];
  if (!asset) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html');
  response.end(readFileSync(new URL(`../../web/dist/${asset}`, import.meta.url)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CODEX_SMOKE_CHROME || undefined, headless: true });
try {
  for (const savedModel of ['', 'opus']) {
    const page = await browser.newPage(); const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(savedModel => {
      const chat = { id: 'new-chat', provider: 'claude', title: 'New chat', cwd: '/repo', draft: '', items: [], requests: [], working: false, revision: 1 };
      const snapshot = { connection: 'connecting', error: '', project: 'Project', cwd: '/repo', settings: { permissions: 'auto', permissionRevision: 0, model: savedModel, modelSelectionSaved: true }, models: [], account: {}, sessions: [], chat };
      const emit = () => { chat.revision++; window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(snapshot) })); };
      window.__sends = []; window.__preferences = [];
      window.__modelsReady = supported => {
        snapshot.connection = 'connected';
        snapshot.models = [{ id: savedModel || 'default', model: savedModel, displayName: 'Claude fixture', isDefault: !savedModel, supportsAutoMode: supported, supportedReasoningEfforts: [] }];
        emit();
      };
      window.__codexSend = raw => {
        const { id, method, params } = JSON.parse(raw);
        const reply = (result = {}) => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result } }));
        if (method === 'send') {
          window.__sends.push(params);
          // The native host waits for initialization before it applies these options.
          window.__finishSend = () => reply();
          return;
        }
        if (method === 'permissionPreferences') { window.__preferences.push(params); }
        queueMicrotask(() => reply(method === 'ready' ? snapshot : {}));
      };
    }, savedModel);
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    const field = page.getByRole('textbox', { name: 'Message Claude', exact: true });
    await page.getByRole('button', { name: 'Permission mode: Approve for me', exact: true }).waitFor();
    await field.fill('Start while Claude is connecting'); await field.press('Enter');
    await page.waitForFunction(() => window.__sends.length === 1);
    assert.equal(await page.evaluate(() => window.__sends[0].permissions), 'auto', 'A missing model list must not downgrade the saved auto preference');
    await page.evaluate(() => window.__modelsReady(true));
    await page.getByRole('button', { name: 'Permission mode: Approve for me', exact: true }).waitFor();
    await page.evaluate(() => window.__finishSend());
    await page.waitForFunction(() => document.querySelector('textarea').value === '');

    // A known unsupported model still uses Ask me, without replacing the saved choice.
    await page.evaluate(() => window.__modelsReady(false));
    await page.getByRole('button', { name: 'Permission mode: Ask me', exact: true }).waitFor();
    await field.fill('Use an unsupported model'); await field.press('Enter');
    await page.waitForFunction(() => window.__sends.length === 2);
    assert.equal(await page.evaluate(() => window.__sends[1].permissions), 'ask');
    await page.evaluate(() => window.__finishSend());
    await page.waitForFunction(() => document.querySelector('textarea').value === '');
    await page.evaluate(() => window.__modelsReady(true));
    await page.getByRole('button', { name: 'Permission mode: Approve for me', exact: true }).waitFor();
    await field.fill('Use the supported model again'); await field.press('Enter');
    await page.waitForFunction(() => window.__sends.length === 3);
    assert.equal(await page.evaluate(() => window.__sends[2].permissions), 'auto');
    assert.deepEqual(await page.evaluate(() => window.__preferences), [], 'Capability updates must not overwrite the saved permission preference');
    assert.deepEqual(errors, []); await page.close();
  }
  console.log('Approval startup: saved auto mode survives loading for default and explicit Claude models, with safe fallback for known unsupported models.');
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
