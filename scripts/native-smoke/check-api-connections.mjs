/** Check custom provider and model menus, settings access, and draft retention without a model. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
const server = createServer((request, response) => {
  const asset = ({ '/': 'index.html', '/app.js': 'app.js', '/index.css': 'index.css' })[request.url];
  if (!asset) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html');
  response.end(readFileSync(new URL(`../../web/dist/${asset}`, import.meta.url)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CODEX_SMOKE_CHROME || undefined, headless: true });
mkdirSync('output/playwright', { recursive: true });
try {
  for (const width of [360, 900]) {
    const page = await browser.newPage({ viewport: { width, height: 700 } }); const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(() => {
      const model = id => ({ id, model: id, displayName: id, supportedReasoningEfforts: [], isDefault: true });
      const state = { providers: [{ id: 'codex', name: 'Codex' }, { id: 'claude', name: 'Claude' }, { id: 'api:custom-api', name: 'Test API' }], connectionRevision: 0,
        connection: 'connected', error: '', project: 'Fixture', cwd: '/tmp', settings: { model: 'gpt-test', effort: '', modelSelectionSaved: true, permissions: 'ask' }, models: [model('gpt-test')], account: {}, sessions: [],
        chat: { id: 'chat', provider: 'codex', threadId: '', title: 'New chat', cwd: '/tmp', draft: 'Keep my draft', items: [], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1 } };
      window.__requests = [];
      window.__publish = patch => { Object.assign(state, patch); state.chat.revision++; window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(state) })); };
      window.__codexSend = raw => {
        const { id, method, params } = JSON.parse(raw); window.__requests.push({ method, params }); let result = {};
        if (method === 'provider') {
          state.chat.provider = params.provider === 'claude' ? 'claude' : 'codex';
          state.chat.apiConnectionId = params.provider.startsWith('api:') ? 'custom-api' : '';
          state.models = state.chat.apiConnectionId ? [model('test-model'), model('another-model')] : [model('gpt-test')];
          state.models[0].isDefault = true; if (state.models[1]) { state.models[1].isDefault = false; }
          state.settings = { model: '', effort: '', modelSelectionSaved: true, permissions: 'ask' };
          window.__publish({}); result = state;
        } else if (method === 'ready') { result = state; }
        else if (method === 'skills') { result = { data: [] }; }
        else if (method === 'draft') { state.chat.draft = params.text; }
        else if (method === 'modelPreferences') { state.settings.model = params.model; }
        queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result: structuredClone(result) } })));
      };
    });
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.getByRole('button', { name: /^Provider:/ }).click();
    await page.getByRole('option', { name: 'Test API', exact: true }).click();
    await page.getByRole('button', { name: /^Provider:/ }).filter({ hasText: 'Test API' }).waitFor();
    assert.equal(await page.locator('textarea').inputValue(), 'Keep my draft');
    assert.equal(await page.getByRole('button', { name: /^Reasoning effort:/ }).count(), 0);
    await page.getByRole('button', { name: /^Model:/ }).click();
    await page.getByRole('option', { name: 'test-model', exact: true }).waitFor();
    await page.getByRole('option', { name: 'another-model', exact: true }).click();
    await page.waitForFunction(() => window.__requests.some(value => value.method === 'modelPreferences' && value.params.model === 'another-model'));
    await page.getByRole('button', { name: /^Permission mode:/ }).click();
    assert.equal(await page.getByRole('option').filter({ hasText: 'Approve for me' }).count(), 0);
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: /^Provider:/ }).click();
    await page.getByRole('option', { name: 'Manage connections…', exact: true }).click();
    await page.waitForFunction(() => window.__requests.some(value => value.method === 'settings'));
    assert.equal(await page.locator('textarea').inputValue(), 'Keep my draft');
    await page.evaluate(() => window.__publish({ settingsPending: true }));
    await page.getByRole('status').filter({ hasText: 'Connection changes' }).waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: `output/playwright/api-connections-${width}.png` });
    assert.deepEqual(errors, []); console.log({ width, providerMenu: true, models: true, settingsShortcut: true, draft: true, pendingNotice: true });
    await page.close();
  }
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
