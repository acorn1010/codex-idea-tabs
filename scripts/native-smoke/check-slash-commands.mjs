/** Check real composer command navigation, local actions, and normal sending in narrow panes. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright';
mkdirSync(output, { recursive: true });
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
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    window.__calls = [];
    const saved = JSON.parse(sessionStorage.getItem('savedDraft') || '{}');
    window.__snapshot = { connection: 'connected', error: '', project: 'foony', cwd: '/project', distro: 'Ubuntu', workspaceLabel: 'master', settings: { model: 'gpt-6-astra', effort: 'xhigh', permissions: 'auto' }, models: [{ id: 'gpt-6-astra', model: 'gpt-6-astra', displayName: 'GPT-6 Astra', isDefault: true, serviceTiers: [{ id: 'priority', name: 'Fast', description: 'Faster responses, increased usage' }], supportedReasoningEfforts: [{ reasoningEffort: 'xhigh' }] }], account: { account: { type: 'chatgpt', planType: 'pro' } }, sessions: [], chat: { id: 'slash', threadId: 'thread-fixture', title: 'Composer commands', cwd: '/project', draft: '', items: [{ id: 'message', type: 'agentMessage', text: 'Type / to open chat commands.' }], requests: [], status: 'idle', working: false, revision: 1, tokenUsage: { last: { totalTokens: 167281 }, modelContextWindow: 258000 }, draftAttachments: [{ path: '/project/notes.txt', name: 'notes.txt', mime: 'text/plain', size: 5 }], ...saved } };
    const skills = [{ name: 'analytics-dashboard', path: '/personal/analytics/SKILL.md', description: 'Create spreadsheets with the Analytics Dashboard template', enabled: true, scope: 'user' }, { name: 'art-geometry-cleanup', path: '/project/.agents/skills/art/SKILL.md', description: 'Fix geometry problems in generated art and uneven frames', enabled: true, scope: 'repo' }, { name: 'disabled-skill', path: '/project/disabled/SKILL.md', description: 'Hidden', enabled: false, scope: 'repo' }];
    window.__codexSend = text => {
      const { id, method, params } = JSON.parse(text);
      window.__calls.push({ method, params });
      let result = {};
      let error;
      if (method === 'ready') { result = window.__snapshot; }
      if (method === 'draft') { sessionStorage.setItem('savedDraft', JSON.stringify({ draft: params.text, draftAttachments: params.attachments, draftSkills: params.skills || [] })); }
      if (method === 'composerPreferences') { Object.assign(window.__snapshot.settings, params); }
      if (method === 'history') { result = { data: [] }; }
      if (method === 'inspectContext') { result = { kind: 'transcript', source: 'Fixture', capturedAt: Date.now(), compactions: 0, notices: [], blocks: [] }; }
      if (method === 'skills') { if (window.__skillsError) { error = 'Fixture skill read failed'; } else { result = { data: [{ cwd: '/project', skills, errors: [] }] }; } }
      if (method === 'getGoal') { result = { goal: window.__goal || null }; }
      if (method === 'setGoal') { window.__goal = params; }
      if (method === 'clearGoal') { window.__goal = null; }
      if (method === 'feedback') { result = { threadId: 'fixture-feedback-receipt' }; }
      if (method === 'mcpStatus') { result = { data: [{ name: 'fixture-search', authStatus: 'notLoggedIn', tools: { search: {} } }] }; }
      if (method === 'context') { result = { files: ['/project/src/main.ts'], selection: '' }; }
      if (method === 'accountLimits') {
        if (window.__limitsError) { error = 'Fixture limit read failed'; }
        else { result = { rateLimits: { limitId: 'codex', primary: { usedPercent: 90, windowDurationMins: 300 } }, rateLimitsByLimitId: { codex: { limitId: 'codex', primary: { usedPercent: 25, windowDurationMins: 300 }, secondary: { usedPercent: 26, windowDurationMins: 10080, resetsAt: 1789430400 } } } }; }
      }
      setTimeout(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result, error } })), method === 'skills' ? 40 : 0);
    };
  });
  const results = [];
  for (const [width, height] of [[360, 435], [520, 700], [900, 850]]) {
    await page.setViewportSize({ width, height });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.evaluate(() => { sessionStorage.clear(); window.__codexSend = () => {}; });
    await page.reload();
    const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
    const menu = page.getByRole('listbox', { name: 'Slash commands', exact: true });
    const status = page.getByRole('region', { name: 'Session status', exact: true });
    async function command(name, key = 'Enter') { await field.fill(`/${name}`); await menu.waitFor(); await field.press(key); }
    await field.fill('/');
    await menu.getByRole('option', { name: /Analytics Dashboard/ }).waitFor();
    assert.equal(await menu.getByRole('option').count(), 19);
    assert.equal(await menu.getByRole('option', { name: /Disabled Skill/ }).count(), 0);
    assert.equal(await field.evaluate(element => document.activeElement === element), true);
    assert.ok(await menu.evaluate(element => { const rect = element.getBoundingClientRect(); return rect.x >= 0 && rect.y >= 0 && rect.right <= innerWidth && rect.bottom <= innerHeight; }));
    const option = menu.getByRole('option').first();
    const before = await option.boundingBox(); await option.hover();
    assert.deepEqual(await option.boundingBox(), before, 'Hover does not expand rows');
    assert.equal(before.height, 24);
    await page.screenshot({ path: `${output}/slash-menu-${width}.png` });
    await field.press('ArrowDown'); assert.ok((await menu.locator('[aria-selected="true"]').innerText()).startsWith('Fast'));
    await field.press('Escape'); assert.equal(await field.inputValue(), '/');
    await command('status');
    await status.getByText('75% left', { exact: true }).waitFor();
    assert.ok((await status.innerText()).includes('35% left'));
    assert.ok((await status.innerText()).includes('74% left'));
    assert.ok((await status.innerText()).includes('167,281 used / 258K'));
    assert.equal(await page.getByRole('dialog').count(), 0, 'Status is a small inline panel');
    assert.equal(await field.evaluate(element => document.activeElement === element), true);
    assert.ok((await status.boundingBox()).height <= 160);
    await page.screenshot({ path: `${output}/slash-status-${width}.png` });
    await field.fill('The status panel keeps the input usable.');
    assert.equal(await status.count(), 1);
    await field.press('Escape');
    assert.equal(await status.count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Remove notes.txt' }).count(), 1, `Attachment retained at ${width}`);
    for (const [name, label] of [['model', 'Model'], ['reasoning', 'Reasoning effort'], ['permissions', 'Permission mode']]) {
      await command(name, 'Tab');
      await page.getByRole('listbox', { name: label, exact: true }).waitFor();
      await page.keyboard.press('Escape');
    }
    await command('ide-context');
    assert.equal(await page.getByRole('button', { name: 'IDE context', exact: true }).getAttribute('aria-pressed'), 'true');
    await command('fast'); await page.getByRole('button', { name: 'Fast ×', exact: true }).waitFor();
    await command('plan'); await page.getByRole('button', { name: 'Plan ×', exact: true }).waitFor();
    assert.ok(await page.evaluate(() => window.__calls.some(call => call.method === 'composerPreferences' && call.params.fast && call.params.planMode)));
    await field.fill('/analytics');
    await menu.getByRole('option', { name: /Analytics Dashboard/ }).waitFor();
    await page.screenshot({ path: `${output}/slash-skills-${width}.png` });
    await menu.getByRole('option', { name: /Analytics Dashboard/ }).click();
    await page.getByRole('button', { name: 'Remove skill analytics-dashboard' }).waitFor();
    assert.equal(await field.inputValue(), '');
    assert.equal(await page.evaluate(() => window.__calls.filter(call => call.method === 'send').length), 0);
    await page.reload();
    await page.getByRole('button', { name: 'Remove skill analytics-dashboard' }).waitFor();
    await field.fill('Build an analytics view.'); await field.press('Control+Enter');
    await page.waitForFunction(() => window.__calls.some(call => call.method === 'send'));
    const sent = await page.evaluate(() => window.__calls.find(call => call.method === 'send').params);
    assert.ok(sent.input.some(input => input.type === 'skill' && input.path === '/personal/analytics/SKILL.md'));
    assert.equal(sent.text, 'Build an analytics view.');
    await page.getByRole('button', { name: 'Remove skill analytics-dashboard' }).waitFor({ state: 'detached' });
    await command('review');
    const review = page.getByRole('region', { name: 'Code review', exact: true });
    await review.getByLabel('Review target').selectOption('baseBranch');
    await review.getByLabel('Base branch').fill('main');
    await review.getByRole('button', { name: 'Start review' }).click();
    await page.waitForFunction(() => window.__calls.some(call => call.method === 'review'));
    assert.equal(await page.evaluate(() => window.__calls.find(call => call.method === 'review').params.target.branch), 'main');
    await command('goal');
    const goal = page.getByRole('region', { name: 'Goal', exact: true });
    await goal.getByLabel('Goal objective').fill('Finish the migration');
    await goal.getByLabel('Optional goal token budget').fill('18000');
    await goal.getByRole('button', { name: 'Set goal' }).click();
    await page.waitForFunction(() => window.__calls.some(call => call.method === 'setGoal'));
    assert.equal(await page.evaluate(() => window.__goal.tokenBudget), 18000);
    await command('goal'); await goal.getByRole('button', { name: 'Clear goal' }).click();
    await command('mcp');
    const mcp = page.getByRole('region', { name: 'MCP servers' });
    await mcp.getByText('fixture-search', { exact: true }).waitFor();
    await mcp.getByRole('button', { name: 'Close', exact: true }).click();
    await command('feedback');
    const feedback = page.getByRole('region', { name: 'Feedback', exact: true });
    await feedback.getByLabel('Feedback text').fill('A fixture-only feedback check.');
    await feedback.getByRole('button', { name: 'Send feedback' }).click();
    await feedback.getByText('Feedback sent · fixture-feedback-receipt', { exact: true }).waitFor();
    await feedback.getByRole('button', { name: 'Close', exact: true }).click();
    await command('memories');
    const inspector = page.getByRole('dialog', { name: 'Context inspector', exact: true });
    await inspector.getByRole('textbox', { name: 'Search context' }).waitFor();
    assert.equal(await inspector.getByRole('textbox', { name: 'Search context' }).inputValue(), 'memory');
    await page.keyboard.press('Escape');
    await command('init'); assert.ok((await field.inputValue()).includes('AGENTS.md'));
    await field.fill('/missing-command'); await field.press('Enter');
    assert.equal(await field.inputValue(), '/missing-command');
    await field.fill('/project/src/index.ts'); await field.press('Control+Enter');
    await page.waitForFunction(() => window.__calls.filter(call => call.method === 'send').length === 2);
    await page.evaluate(() => { window.__skillsError = true; });
    await field.fill('/'); await menu.getByRole('button', { name: 'Retry skills' }).waitFor();
    await page.evaluate(() => { window.__skillsError = false; });
    await menu.getByRole('button', { name: 'Retry skills' }).click();
    await menu.getByRole('option', { name: /Analytics Dashboard/ }).waitFor();
    await page.evaluate(() => { window.__limitsError = true; });
    await command('status'); await status.getByRole('alert').waitFor();
    await page.evaluate(() => { window.__limitsError = false; });
    await status.getByRole('button', { name: 'Retry', exact: true }).click();
    await status.getByText('75% left', { exact: true }).waitFor();
    await field.fill('/status'); await field.press('Shift+Enter'); assert.equal(await menu.count(), 0);
    await page.evaluate(() => sessionStorage.clear());
    results.push({ width, height, compactRows: true, compactStatus: true, skills: true, savedSkill: true, explicitSkillInput: true, commandActions: true, normalSending: true, retry: true });
  }
  assert.deepEqual(errors, []);
  writeFileSync(`${output}/slash-commands-result.json`, JSON.stringify(results, null, 2));
  console.log(results);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
