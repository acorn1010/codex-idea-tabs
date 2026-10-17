/** Check provider selection, Claude controls, and Markdown export without starting either CLI. */
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
    const chat = { id: 'provider', provider: 'codex', threadId: '', title: 'Chat', cwd: '/project', draft: 'Keep this draft', items: [], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1 };
    const codex = { model: 'gpt-test', effort: 'high', modelSelectionSaved: true, permissions: 'auto' };
    const claude = { model: 'sonnet', effort: '', modelSelectionSaved: true, permissions: 'ask' };
    const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: '/project', settings: codex, models: [], account: {}, sessions: [], chat };
    window.__requests = []; window.__copies = [];
    window.__limits = { rateLimitsAvailable: true, rateLimits: { five_hour: { utilization: 25, resets_at: '2026-10-01T04:00:00Z' }, seven_day: { utilization: 64, resets_at: '2026-10-05T04:00:00Z' } } };
    const publish = () => { chat.revision++; window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(snapshot) })); };
    window.__upgrade = () => {
      chat.claudeSettingsSupported = true;
      snapshot.models = [{ id: 'sonnet', model: 'sonnet', displayName: 'Sonnet', supportedReasoningEfforts: [{ reasoningEffort: 'high', description: 'Think deeply' }], supportsAutoMode: true, additionalSpeedTiers: ['fast'] }]; publish();
    };
    window.__question = () => {
      chat.requests = [{ key: 'q', rpcId: 'q', method: 'item/tool/requestUserInput', questions: [{ id: '0', question: 'Which sections?', multiSelect: true, options: [{ label: 'Introduction' }, { label: 'Conclusion' }] }] }]; publish();
    };
    window.__finishTurn = () => { chat.working = false; chat.status = 'idle'; publish(); };
    window.__codexSend = raw => {
      const { id, method, params } = JSON.parse(raw); window.__requests.push({ method, params });
      const reply = result => queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result: structuredClone(result) } })));
      if (method === 'provider') {
        chat.provider = params.provider; snapshot.settings = params.provider === 'claude' ? claude : codex;
        snapshot.models = params.provider === 'claude' ? [{ id: 'sonnet', model: 'sonnet', displayName: 'Sonnet', supportedReasoningEfforts: [] }] : [];
        publish(); reply(snapshot); return;
      }
      if ((method === 'steerQueued' || (method === 'send' && params.steer)) && window.__failSteer) {
        queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, error: 'Steering failed. Try again.' } }))); return;
      }
      if (method === 'send' && chat.working && !params.steer) {
        chat.claudeQueue = [...(chat.claudeQueue || []), { id: `queued-${id}`, payload: params, input: params.input || [] }]; publish(); reply({ queued: true }); return;
      }
      if (method === 'stop') { chat.working = false; chat.claudeQueuePaused = true; publish(); }
      if (method === 'steerQueued') { chat.claudeQueue = chat.claudeQueue.filter(entry => entry.id !== params.id); publish(); }
      if (method === 'cancelQueued') { chat.claudeQueue = chat.claudeQueue.filter(entry => entry.id !== params.id); publish(); }
      if (method === 'resumeQueued') { chat.claudeQueue = []; chat.working = true; publish(); }
      if (method === 'answer') { chat.requests = []; publish(); }
      if (method === 'searchChats') { reply({ data: [], pullRequestSearch: false }); return; }
      if (method === 'skills') { reply({ data: [{ cwd: '/project', skills: [{ name: 'goal', path: 'claude-command:goal', description: 'Set, inspect, or clear a goal', nativeCommand: true, enabled: true, scope: 'user' }, { name: 'explain', path: 'claude-command:explain', description: 'Explain changes', nativeCommand: true, enabled: true, scope: 'user' }, { name: 'repo-guide', path: '/shared/.agents/skills/repo-guide/SKILL.md', description: 'Shared project rules', enabled: true, scope: 'repo' }] }] }); return; }
      if (method === 'accountLimits') { reply({ context: { totalTokens: 512, maxTokens: 200000, categories: [{ name: 'Messages', tokens: 512 }], memoryFiles: [{ path: '/project/CLAUDE.md' }] }, cost: 0.0123, usage: { input_tokens: 90, output_tokens: 10 }, rateLimit: { status: 'allowed' }, ...window.__limits }); return; }
      if (method === 'mcpStatus') { reply({ data: [{ name: 'My server', authStatus: 'connected', tools: { read: {} } }] }); return; }
      if (method === 'send') {
        chat.threadId = 'saved'; chat.working = true; chat.status = 'working';
        chat.items = [{ id: 'question', type: 'userMessage', text: params.text }, { id: 'response', type: 'agentMessage', text: 'Claude reply' }]; publish();
      }
      if (method === 'copy') { window.__copies.push(params.text); }
      reply(method === 'ready' ? snapshot : method === 'chatTranscript' ? chat : method === 'history' ? { data: [{ id: 'terminal-session', name: 'Terminal chat', provider: 'claude', cwd: '/project' }] } : method === 'workspaces' ? { entries: [], repositories: [], branches: [], current: '/project', repository: '', base: '', suggestedName: '', error: '' } : {});
    };
  });
  for (const width of [360, 900]) {
    await page.setViewportSize({ width, height: 720 }); await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.getByRole('button', { name: 'Provider: Codex', exact: true }).click();
    const menu = page.getByRole('listbox', { name: 'Provider', exact: true }); await menu.waitFor();
    const bounds = await menu.boundingBox(); assert.ok(bounds.y >= 0 && bounds.y + bounds.height < 720);
    await page.screenshot({ path: `${output}/provider-menu-${width}.png` });
    await page.getByRole('option', { name: 'Claude', exact: true }).click();
    const field = page.getByRole('textbox', { name: 'Message Claude', exact: true }); await field.waitFor();
    await page.getByRole('button', { name: 'Provider: Claude', exact: true }).click();
    await page.getByRole('option', { name: 'Codex', exact: true }).click();
    await page.getByRole('button', { name: 'Model: gpt-test', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Permission mode: Approve for me', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Provider: Codex', exact: true }).click();
    await page.getByRole('option', { name: 'Claude', exact: true }).click(); await field.waitFor();
    assert.equal(await field.inputValue(), 'Keep this draft');
    await page.getByRole('button', { name: 'Model: Sonnet', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: 'Inspect context', exact: true }).count(), 1);
    assert.equal(await page.getByRole('button', { name: /Reasoning effort:/ }).count(), 0);
    await page.getByRole('button', { name: 'Permission mode: Ask me', exact: true }).click();
    assert.equal(await page.getByRole('option', { name: /Approve for me/ }).count(), 0);
    await page.getByRole('option', { name: /Accept edits/ }).waitFor(); await page.getByRole('option', { name: /^Ask me/ }).press('Escape');
    await page.getByRole('listbox', { name: 'Permission mode' }).waitFor({ state: 'detached' });
    await field.fill('/');
    await page.getByRole('listbox', { name: 'Slash commands' }).waitFor();
    assert.equal(await page.getByRole('option', { name: /^Goal/ }).count(), 1);
    await page.getByRole('option', { name: /^Goal/ }).click();
    const goalPanel = page.getByRole('region', { name: 'Goal', exact: true });
    await goalPanel.waitFor();
    assert.equal(await goalPanel.getByRole('spinbutton', { name: 'Optional goal token budget' }).count(), 0);
    await goalPanel.getByText('Stop ends the current turn. Your goal stays active until it is met or cleared.', { exact: true }).waitFor();
    await goalPanel.getByRole('button', { name: 'Check status', exact: true }).click();
    await page.waitForFunction(() => window.__requests.some(call => call.method === 'send' && call.params.text === '/goal'));
    await page.evaluate(() => window.__finishTurn());
    await field.fill('/goal'); await page.getByRole('option', { name: /^Goal/ }).click();
    await goalPanel.getByRole('button', { name: 'Clear goal', exact: true }).click();
    await page.waitForFunction(() => window.__requests.some(call => call.method === 'send' && call.params.text === '/goal clear'));
    await page.evaluate(() => window.__finishTurn());
    await field.fill('/goal'); await page.getByRole('option', { name: /^Goal/ }).click();
    await goalPanel.getByRole('textbox', { name: 'Goal objective' }).fill('All tests pass. Stop if user input is needed.');
    await goalPanel.getByRole('button', { name: 'Set goal', exact: true }).click();
    await page.waitForFunction(() => window.__requests.some(call => call.method === 'send' && call.params.text === '/goal All tests pass. Stop if user input is needed.'));
    await field.fill('/goal'); await page.getByRole('option', { name: /^Goal/ }).click();
    assert.ok(await goalPanel.getByRole('button', { name: 'Clear goal', exact: true }).isDisabled());
    assert.ok(await goalPanel.getByRole('button', { name: 'Check status', exact: true }).isDisabled());
    await goalPanel.getByRole('button', { name: 'Stop turn', exact: true }).click();
    await goalPanel.getByRole('button', { name: 'Clear goal', exact: true }).click();
    await page.waitForFunction(() => window.__requests.filter(call => call.method === 'send' && call.params.text === '/goal clear').length === 2);
    assert.equal(await page.evaluate(() => window.__requests.filter(call => ['getGoal', 'setGoal', 'clearGoal'].includes(call.method)).length), 0);
    await page.evaluate(() => window.__finishTurn());
    await field.fill('/goal'); await page.getByRole('option', { name: /^Goal/ }).click();
    await page.screenshot({ path: `${output}/claude-goal-${width}.png` });
    assert.ok(await goalPanel.evaluate(element => element.scrollWidth <= element.clientWidth));
    await goalPanel.getByRole('button', { name: 'Close', exact: true }).click();
    await field.fill('/');
    await page.waitForFunction(() => window.__requests.some(call => call.method === 'skills'));
    await page.getByRole('option', { name: /Repo Guide/ }).waitFor();
    await page.keyboard.press('Escape'); await field.fill('Hello Claude');
    await page.getByRole('button', { name: 'Send message', exact: true }).click();
    await page.getByRole('button', { name: 'Stop Claude', exact: true }).waitFor();
    assert.ok(await page.getByRole('button', { name: 'Provider: Claude', exact: true }).isDisabled());
    assert.equal(await page.getByRole('button', { name: 'Edit message', exact: true }).count(), 0);
    await field.fill('Next prompt'); assert.ok(await page.getByRole('button', { name: 'Send follow-up', exact: true }).isEnabled());
    await page.getByRole('button', { name: 'Send follow-up', exact: true }).click();
    await page.getByRole('region', { name: 'Queued messages' }).waitFor();
    const queued = page.getByRole('region', { name: 'Queued messages' });
    await field.fill('Steer from the keyboard');
    const sendsBefore = await page.evaluate(() => window.__requests.filter(call => call.method === 'send').length);
    await field.dispatchEvent('keydown', { key: 'Enter', shiftKey: true, repeat: true, bubbles: true });
    await field.dispatchEvent('keydown', { key: 'Enter', shiftKey: true, isComposing: true, bubbles: true });
    assert.equal(await page.evaluate(() => window.__requests.filter(call => call.method === 'send').length), sendsBefore);
    await page.evaluate(() => { window.__failSteer = true; });
    await field.press('Shift+Enter');
    await page.getByText('Steering failed. Try again.', { exact: true }).waitFor();
    assert.equal(await field.inputValue(), 'Steer from the keyboard');
    await page.evaluate(() => { window.__failSteer = false; });
    await field.press('Shift+Enter');
    await page.waitForFunction(() => window.__requests.filter(call => call.method === 'send' && call.params.text === 'Steer from the keyboard' && call.params.steer).length === 2);
    await page.waitForFunction(() => document.querySelector('textarea').value === '');
    assert.equal(await field.inputValue(), '');
    assert.equal(await queued.getByRole('button', { name: 'Steer queued message' }).count(), 1);
    await field.fill('Another queued message'); await field.press('Enter');
    await page.waitForFunction(() => window.__requests.some(call => call.method === 'send' && call.params.text === 'Another queued message'));
    const selected = queued.getByText('Another queued message', { exact: true }).locator('..');
    const steerButton = selected.getByRole('button', { name: 'Steer queued message' });
    const removeButton = selected.getByRole('button', { name: 'Remove queued message' });
    await steerButton.waitFor();
    assert.ok((await steerButton.boundingBox()).x < (await removeButton.boundingBox()).x);
    await field.fill('Keep my draft');
    await page.evaluate(() => { window.__failSteer = true; });
    await steerButton.click(); await page.getByText('Steering failed. Try again.', { exact: true }).waitFor();
    assert.equal(await queued.getByRole('button', { name: 'Steer queued message' }).count(), 2);
    await page.evaluate(() => { window.__failSteer = false; });
    await steerButton.click(); await selected.waitFor({ state: 'detached' });
    await page.getByText('Steering failed. Try again.', { exact: true }).waitFor({ state: 'detached' });
    await queued.getByText('Next prompt', { exact: true }).waitFor();
    assert.equal(await field.inputValue(), 'Keep my draft');
    await page.screenshot({ path: `${output}/claude-steering-${width}.png` });
    assert.ok(await queued.evaluate(element => element.scrollWidth <= element.clientWidth));
    await page.getByRole('button', { name: 'Stop Claude', exact: true }).click();
    await field.press('Shift+Enter'); assert.equal(await field.inputValue(), 'Keep my draft\n');
    await field.fill('');
    await page.getByRole('button', { name: 'Send queued', exact: true }).click();
    await page.getByRole('region', { name: 'Queued messages' }).waitFor({ state: 'detached' });
    const sent = await page.evaluate(() => window.__requests.find(call => call.method === 'send').params);
    assert.equal(sent.model, 'sonnet'); assert.equal(sent.permissions, 'ask'); assert.equal(sent.effort, '');
    await page.evaluate(() => window.__finishTurn());
    await page.getByRole('button', { name: 'Copy chat as Markdown', exact: true }).click();
    await page.getByRole('menuitem', { name: 'Copy conversation', exact: true }).click();
    await page.waitForFunction(() => window.__copies.length === 1);
    assert.ok(await page.evaluate(() => window.__copies[0].includes('## Claude\n\nClaude reply')));
    await page.evaluate(() => window.__upgrade());
    await page.getByRole('button', { name: 'Edit message', exact: true }).waitFor();
    await page.getByRole('button', { name: /Reasoning effort:/ }).click();
    await page.getByRole('option', { name: /^high/ }).click();
    await page.getByRole('button', { name: 'Permission mode: Ask me', exact: true }).click();
    await page.getByRole('option', { name: /Approve for me/ }).click();
    await field.fill('/fast'); await page.getByRole('option', { name: /^Fast/ }).click();
    await page.getByRole('button', { name: 'Fast ×', exact: true }).waitFor();
    await field.fill('/status'); await page.getByRole('option', { name: /^Status/ }).click();
    const status = page.getByRole('region', { name: 'Claude session status' });
    await page.getByText('Context: 512 / 200,000 tokens', { exact: true }).waitFor();
    const fiveHour = status.getByRole('progressbar', { name: '5h limit remaining' });
    const weekly = status.getByRole('progressbar', { name: 'Weekly limit remaining' });
    assert.equal(await fiveHour.getAttribute('aria-valuenow'), '75');
    assert.equal(await weekly.getAttribute('aria-valuenow'), '36');
    assert.equal(await status.getByText(/^Resets /).count(), 2);
    for (const theme of ['dark', 'light']) {
      await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
      for (const bar of [fiveHour, weekly]) {
        const bounds = await bar.boundingBox(); assert.ok(bounds.width > 100 && bounds.height >= 6);
      }
      assert.ok(await status.evaluate(element => element.scrollWidth <= element.clientWidth));
      await page.screenshot({ path: `${output}/claude-limits-${theme}-${width}.png` });
    }
    await page.evaluate(() => { window.__limits.rateLimits = { five_hour: { utilization: 0 }, seven_day: { utilization: 125 } }; });
    await status.getByRole('button', { name: 'Refresh', exact: true }).click();
    await status.getByText('100% left', { exact: true }).waitFor();
    assert.equal(await fiveHour.getAttribute('aria-valuenow'), '100');
    assert.equal(await weekly.getAttribute('aria-valuenow'), '0');
    await page.evaluate(() => { window.__limits.rateLimits = { five_hour: { utilization: null, resets_at: 'invalid' }, seven_day: null }; });
    await status.getByRole('button', { name: 'Refresh', exact: true }).click();
    await fiveHour.waitFor({ state: 'detached' });
    assert.equal(await status.getByRole('progressbar').count(), 0);
    assert.equal(await status.getByText('Not reported', { exact: true }).count(), 2);
    assert.equal(await status.getByText(/Invalid Date/).count(), 0);
    await page.evaluate(() => { window.__limits = { rateLimitsAvailable: false }; });
    await status.getByRole('button', { name: 'Refresh', exact: true }).click();
    await status.getByText('Subscription limits are unavailable for this sign-in.', { exact: true }).waitFor();
    await page.evaluate(() => { window.__limits = { rateLimitsError: 'Could not read subscription limits. Update Claude Code.' }; });
    await status.getByRole('button', { name: 'Refresh', exact: true }).click();
    await status.getByRole('alert').getByText(/Could not read subscription limits/).waitFor();
    assert.equal(await status.getByRole('progressbar').count(), 0);
    await status.getByText('Context: 512 / 200,000 tokens', { exact: true }).waitFor();
    await status.getByRole('button', { name: 'Close', exact: true }).click();
    await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
    await field.fill('/mcp'); await page.getByRole('option', { name: /^MCP/ }).click();
    await page.getByText('My server', { exact: true }).waitFor();
    await page.getByRole('region', { name: 'MCP servers' }).getByRole('button', { name: 'Close', exact: true }).click();
    await field.fill('/explain'); await page.getByRole('option', { name: /^Explain/ }).click();
    await page.waitForFunction(() => window.__requests.some(call => call.method === 'send' && call.params.text === '/explain' && call.params.effort === 'high' && call.params.fast && call.params.permissions === 'auto'));
    await page.evaluate(() => { window.__finishTurn(); window.__question(); });
    await page.getByRole('checkbox', { name: 'Introduction', exact: true }).check();
    await page.getByRole('checkbox', { name: 'Conclusion', exact: true }).check();
    await page.getByRole('textbox', { name: 'Answer: Which sections?', exact: true }).fill('Appendix');
    await page.getByRole('button', { name: 'Send answer', exact: true }).click();
    await page.waitForFunction(() => window.__requests.some(call => call.method === 'answer'));
    assert.deepEqual(await page.evaluate(() => window.__requests.find(call => call.method === 'answer').params.answers['0'].answers), ['Introduction', 'Conclusion', 'Appendix']);
    await field.fill('/resume'); await page.getByRole('option', { name: /^Resume/ }).click();
    await page.getByRole('button', { name: 'Terminal chat', exact: true }).click();
    await page.waitForFunction(() => window.__requests.some(call => call.method === 'openThread' && call.params.thread.provider === 'claude'));
    await page.keyboard.press('Escape');
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: `${output}/claude-chat-${width}.png` });
    console.log({ width, providerSelection: true, claudeSteering: true, steeringRetries: true, retainedDraft: true, correctModelsAndPermissions: true, claudeGoalControls: true, claudeMarkdown: true, subscriptionLimitBars: true, refreshAndUnavailableLimits: true });
  }
  assert.deepEqual(errors, []);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
