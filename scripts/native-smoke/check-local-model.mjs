/** Check local GLM controls and switching back to cloud models without making model calls. */
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
    const chat = { id: 'local', provider: 'codex', threadId: '', title: 'Local GLM', cwd: '/project', draft: '', items: [], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1, tokenUsage: { last: { totalTokens: 9000 }, modelContextWindow: 93388 } };
    const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: '/project', settings: { model: 'glm-flash-uncensored', effort: '', modelSelectionSaved: true, permissions: 'ask', permissionRevision: 0, fast: true },
      models: [{ id: 'glm-flash-uncensored', model: 'glm-flash-uncensored', displayName: 'GLM 5.3 Flash (local)', isLocal: true, supportedReasoningEfforts: [] }, { id: 'cloud', model: 'cloud', displayName: 'Cloud model', isDefault: true, defaultReasoningEffort: 'high', supportedReasoningEfforts: [{ reasoningEffort: 'high', description: 'Think deeply' }], additionalSpeedTiers: ['fast'] }],
      account: { requiresOpenaiAuth: true }, sessions: [], chat };
    window.__requests = [];
    window.__codexSend = raw => {
      const { id, method, params } = JSON.parse(raw); window.__requests.push({ method, params });
      const result = method === 'ready' ? snapshot : method === 'skills' ? { data: [] } : method === 'accountLimits' ? { rateLimits: { primary: { usedPercent: 25 } } } : {};
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result: structuredClone(result) } })));
    };
  });
  for (const { width, theme } of [{ width: 360, theme: 'dark' }, { width: 900, theme: 'dark' }, { width: 360, theme: 'light' }, { width: 900, theme: 'light' }]) {
    await page.setViewportSize({ width, height: 720 }); await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.getByRole('button', { name: 'Model: GLM 5.3 Flash (local)', exact: true }).waitFor();
    await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
    assert.equal(await page.getByRole('button', { name: /Reasoning effort:/ }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Sign in with ChatGPT', exact: true }).count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Fast ×', exact: true }).count(), 0);
    await page.getByRole('button', { name: 'Permission mode: Ask me', exact: true }).click();
    assert.deepEqual(await page.getByRole('option').allTextContents(), ['Read onlyNo file changes', 'Ask meYou review requests', 'Approve for meAI reviews requests', 'Approve all for sessionNo review until reconnect or close']);
    await page.getByRole('option', { name: /^Approve for me/ }).click();
    assert.equal(await page.getByRole('button', { name: 'Permission mode: Approve for me', exact: true }).count(), 1, 'GLM must retain the selected automatic reviewer');
    assert.equal(await page.evaluate(() => window.__requests.filter(call => call.method === 'permissionPreferences').at(-1).params.permissions), 'auto');
    await page.getByRole('button', { name: 'Permission mode: Approve for me', exact: true }).click();
    await page.screenshot({ path: `${output}/local-glm-permissions-${theme}-${width}.png` });
    const selected = page.getByRole('option', { name: /^Approve for me/ });
    assert.equal(await selected.getAttribute('aria-selected'), 'true');
    await selected.press('Home');
    assert.equal(await page.getByRole('option', { name: /^Read only/ }).evaluate(element => element === document.activeElement), true);
    await page.keyboard.press('End');
    assert.equal(await page.getByRole('option', { name: /^Approve all for session/ }).evaluate(element => element === document.activeElement), true);
    await page.keyboard.press('Escape');
    const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
    await field.fill('/status'); await page.getByRole('option', { name: /^Status/ }).click();
    const status = page.getByRole('region', { name: 'Session status' });
    await status.getByText('Local GLM uses your computer. No account quota applies.', { exact: true }).waitFor();
    assert.ok((await status.textContent()).includes('/ 93,388'), 'The status panel must use the 96K harness allowance');
    assert.equal(await page.evaluate(() => window.__requests.filter(call => call.method === 'accountLimits').length), 0);
    assert.ok(await status.evaluate(element => element.scrollWidth <= element.clientWidth));
    await page.screenshot({ path: `${output}/local-glm-${theme}-${width}.png` });
    await status.getByRole('button', { name: 'Close', exact: true }).click();
    await field.fill('Test local turn'); await page.getByRole('button', { name: 'Send message', exact: true }).click();
    await page.waitForFunction(() => window.__requests.some(call => call.method === 'send'));
    const sent = await page.evaluate(() => window.__requests.find(call => call.method === 'send').params);
    assert.equal(sent.model, 'glm-flash-uncensored'); assert.equal(sent.permissions, 'auto'); assert.equal(sent.effort, ''); assert.equal(sent.fast, false);
    await page.getByRole('button', { name: 'Model: GLM 5.3 Flash (local)', exact: true }).click();
    await page.getByRole('option', { name: 'Cloud model', exact: true }).click();
    await page.getByRole('button', { name: /Reasoning effort:/ }).waitFor();
    await page.getByRole('button', { name: 'Permission mode: Approve for me', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Sign in with ChatGPT', exact: true }).waitFor();
    await field.fill('/status'); await page.getByRole('option', { name: /^Status/ }).click();
    await page.waitForFunction(() => window.__requests.some(call => call.method === 'accountLimits'));
    await status.getByText('75% left', { exact: true }).waitFor();
  }
  assert.deepEqual(errors, []);
  console.log('Local model UI passed: automatic review, keyboard navigation, both themes at 360/900 pixels, and cloud controls after switching.');
} finally { await browser.close(); server.close(); }
