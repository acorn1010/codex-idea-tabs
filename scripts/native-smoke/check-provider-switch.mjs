/** Check provider handoff controls in saved chats, preserving drafts and choosing the CLI's Opus model. */
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
    const chat = { id: 'same-tab', provider: 'codex', threadId: 'old-codex', title: 'Existing chat', cwd: '/repo/worktree', draft: 'Keep my draft', draftAttachments: [{ name: 'notes.txt', path: '/repo/notes.txt', mime: 'text/plain', size: 10 }], items: [{ id: 'user', type: 'userMessage', text: 'The original question' }, { id: 'answer', type: 'agentMessage', text: 'The Codex answer' }], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1 };
    const settings = { codex: { model: 'gpt-test', effort: 'high', modelSelectionSaved: true, permissions: 'auto' }, claude: { model: '', effort: '', modelSelectionSaved: true, permissions: 'ask' } };
    const savedPermissions = JSON.parse(localStorage.getItem('permissionPreferences') || '{}');
    for (const provider of ['codex', 'claude']) { if (savedPermissions[provider]) { settings[provider].permissions = savedPermissions[provider]; } }
    const models = { codex: [{ id: 'gpt-test', model: 'gpt-test', displayName: 'GPT test', supportedReasoningEfforts: [{ reasoningEffort: 'high' }] }], claude: [{ id: 'opus', model: 'opus', displayName: 'Opus 5.5', supportsAutoMode: true, supportedReasoningEfforts: [{ reasoningEffort: 'high' }, { reasoningEffort: 'xhigh' }, { reasoningEffort: 'max' }] }, { id: 'sonnet', model: 'sonnet', displayName: 'Sonnet 5.5', supportedReasoningEfforts: [] }] };
    const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: chat.cwd, settings: settings.codex, models: models.codex, account: {}, sessions: [], chat };
    window.__requests = []; window.__copies = [];
    const publish = () => { chat.revision++; window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(snapshot) })); };
    window.__state = patch => { Object.assign(chat, patch); publish(); };
    window.__codexSend = raw => {
      const { id, method, params } = JSON.parse(raw); window.__requests.push({ method, params });
      const reply = (result, error) => queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result: structuredClone(result), error } })));
      if (method === 'provider') {
        chat.providerSwitching = true; publish();
        window.__finishSwitch = error => {
          chat.providerSwitching = false;
          if (error) { publish(); reply({}, error); return; }
          chat.items = chat.items.map(item => ({ ...item, historyProvider: item.historyProvider || chat.provider }));
          chat.threadId = ''; chat.provider = params.provider; chat.claudeSettingsSupported = true;
          snapshot.settings = settings[params.provider]; snapshot.models = models[params.provider]; publish(); reply(snapshot);
        };
        return;
      }
      if (method === 'modelPreferences') { Object.assign(settings[chat.provider], params); }
      if (method === 'permissionPreferences') {
        settings[chat.provider].permissions = params.permissions;
        localStorage.setItem('permissionPreferences', JSON.stringify({ codex: settings.codex.permissions, claude: settings.claude.permissions }));
      }
      if (method === 'copy') { window.__copies.push(params.text); }
      if (method === 'send') { chat.threadId = 'new-session'; chat.items.push({ id: 'new-user', type: 'userMessage', text: params.text }); publish(); }
      reply(method === 'ready' ? snapshot : method === 'chatTranscript' ? chat : method === 'workspaces' ? { entries: [], repositories: [], branches: [], current: chat.cwd, repository: '', base: '', suggestedName: '', error: '' } : {});
    };
  });
  for (const width of [360, 900]) {
    await page.setViewportSize({ width, height: 760 }); await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const provider = page.getByRole('button', { name: /^Provider:/ });
    const choose = async name => { await provider.click(); await page.getByRole('option', { name, exact: true }).click(); };
    await provider.waitFor();
    assert.ok(await provider.isEnabled(), 'A saved chat can switch');
    for (const patch of [{ working: true }, { requests: [{ key: 'approval', method: 'item/tool/requestUserInput', questions: [] }] }, { claudeQueue: [{ id: 'queued', input: [], payload: { text: 'Queued' } }] }]) {
      await page.evaluate(patch => window.__state(patch), patch);
      await page.waitForFunction(() => document.querySelector('[aria-label^="Provider:"]')?.disabled);
      await page.evaluate(() => window.__state({ working: false, requests: [], claudeQueue: [] }));
      await page.waitForFunction(() => !document.querySelector('[aria-label^="Provider:"]')?.disabled);
    }
    await choose('Claude');
    assert.ok(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).isDisabled());
    assert.ok(await provider.isDisabled());
    await page.evaluate(() => window.__finishSwitch('Claude could not start.'));
    await page.getByRole('alert').filter({ hasText: 'Claude could not start.' }).waitFor();
    assert.equal(await provider.getAttribute('aria-label'), 'Provider: Codex');
    assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue(), 'Keep my draft');
    await choose('Claude'); await page.evaluate(() => window.__finishSwitch());
    const field = page.getByRole('textbox', { name: 'Message Claude', exact: true }); await field.waitFor();
    assert.equal(await field.inputValue(), 'Keep my draft');
    await page.getByText('notes.txt', { exact: true }).waitFor();
    await page.getByText('The original question', { exact: true }).waitFor();
    await page.getByText('The Codex answer', { exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Edit message', exact: true }).count(), 0, 'Carried messages cannot use the new provider history for edits');
    await page.getByRole('button', { name: 'Model: Claude default', exact: true }).click();
    await page.getByRole('option', { name: 'Opus 5.5', exact: true }).waitFor();
    await page.screenshot({ path: `${output}/claude-opus-model-${width}.png` });
    await page.getByRole('option', { name: 'Opus 5.5', exact: true }).click();
    await page.getByRole('button', { name: /Reasoning effort:/ }).click(); await page.getByRole('option', { name: 'xhigh', exact: true }).click();
    await page.getByRole('button', { name: 'Send message', exact: true }).click();
    await page.waitForFunction(() => window.__requests.some(call => call.method === 'send'));
    const sent = await page.evaluate(() => window.__requests.find(call => call.method === 'send').params);
    assert.equal(sent.model, 'opus'); assert.equal(sent.effort, 'xhigh'); assert.equal(sent.permissions, 'ask'); assert.equal(sent.text, 'Keep my draft');
    assert.ok(sent.input.some(part => part.text?.includes('/repo/notes.txt')));
    await page.getByRole('button', { name: 'Copy chat as Markdown', exact: true }).click(); await page.getByRole('menuitem', { name: 'Copy conversation', exact: true }).click();
    await page.waitForFunction(() => window.__copies.length === 1);
    assert.ok(await page.evaluate(() => window.__copies[0].includes('## Codex\n\nThe Codex answer')));
    await field.fill('Draft after switching');
    await choose('Codex'); await page.evaluate(() => window.__finishSwitch());
    await page.getByRole('button', { name: 'Model: GPT test', exact: true }).waitFor();
    assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue(), 'Draft after switching');
    await choose('Claude'); await page.evaluate(() => window.__finishSwitch());
    await page.getByRole('button', { name: 'Model: Opus 5.5', exact: true }).waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    assert.ok(!await page.evaluate(() => window.__requests.some(call => ['openThread', 'archive', 'workspace'].includes(call.method))));
    const choosePermissions = async label => {
      await page.getByRole('button', { name: /^Permission mode:/ }).click();
      await page.getByRole('option', { name: new RegExp('^' + label) }).click();
    };
    const chooseModel = async label => {
      await page.getByRole('button', { name: /^Model:/ }).click();
      await page.getByRole('option', { name: label, exact: true }).click();
    };
    await choosePermissions('Approve for me');
    await page.waitForFunction(() => JSON.parse(localStorage.getItem('permissionPreferences') || '{}').claude === 'auto');
    await chooseModel('Sonnet 5.5');
    await page.getByRole('button', { name: 'Permission mode: Ask me', exact: true }).waitFor();
    await field.fill('Unsupported model uses Ask me');
    await page.getByRole('button', { name: 'Send message', exact: true }).click();
    await page.waitForFunction(() => window.__requests.filter(call => call.method === 'send').length === 2);
    assert.equal(await page.evaluate(() => window.__requests.filter(call => call.method === 'send').at(-1).params.permissions), 'ask');
    assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('permissionPreferences')).claude), 'auto');
    await chooseModel('Opus 5.5');
    await page.getByRole('button', { name: 'Permission mode: Approve for me', exact: true }).waitFor();
    await choose('Codex'); await page.evaluate(() => window.__finishSwitch());
    await choosePermissions('Ask me');
    await page.waitForFunction(() => JSON.parse(localStorage.getItem('permissionPreferences') || '{}').codex === 'ask');
    await page.reload();
    await page.getByRole('button', { name: 'Permission mode: Ask me', exact: true }).waitFor();
    await choose('Claude'); await page.evaluate(() => window.__finishSwitch());
    await chooseModel('Opus 5.5');
    await page.getByRole('button', { name: 'Permission mode: Approve for me', exact: true }).waitFor();
    await page.evaluate(() => localStorage.removeItem('permissionPreferences'));
    console.log({ permissionSelectionSavedBeforeSend: true, independentProviderPreferences: true, permissionReload: true, unsupportedModelFallback: true, width, savedChatSwitching: true, busyAndQueueGuards: true, failedSwitchRetry: true, preservedHistoryDraftAndFiles: true, opusAndEffort: true, repeatedSwitches: true });
  }
  assert.deepEqual(errors, []);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
