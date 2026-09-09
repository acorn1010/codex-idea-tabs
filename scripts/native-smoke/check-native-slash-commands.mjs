/** Verify command and skill actions through real IDEA JCEF and the deterministic app-server fixture. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const { native, findChat, close } = await connectNative();
try {
  const existing = await findChat(() => true);
  assert.ok(existing.state.settings.binary.endsWith('/scripts/fake-codex.py'), 'Run only against the deterministic fixture');
  // Restored tabs can connect before test startup applies its fixture binary setting.
  await native(existing.page, 'reconnect');
  const limits = await native(existing.page, 'accountLimits');
  assert.equal(limits.rateLimits?.primary?.usedPercent, 25, `Expected fixture limits: ${JSON.stringify(limits)}`);
  const id = `slash-${Date.now()}`;
  await native(existing.page, 'openThread', { thread: { id, name: 'Slash command checks', cwd: existing.state.cwd } });
  const { page } = await findChat(chat => chat.threadId === id);
  await page.evaluate(() => {
    const send = window.__codexSend;
    window.__slashCalls = [];
    window.__codexSend = value => { window.__slashCalls.push(JSON.parse(value)); send(value); };
  });
  const before = await native(page, 'ready');
  const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
  const menu = page.getByRole('listbox', { name: 'Slash commands', exact: true });
  async function command(name, key = 'Enter') { await field.fill(`/${name}`); await menu.waitFor(); await field.press(key); }
  await field.fill('/');
  await menu.getByRole('option', { name: /Analytics Dashboard/ }).waitFor();
  assert.equal(await menu.getByRole('option').count(), 19);
  await field.press('ArrowDown');
  assert.ok((await menu.locator('[aria-selected="true"]').innerText()).startsWith('Fast'));
  mkdirSync('output/playwright', { recursive: true });
  await page.screenshot({ path: 'output/playwright/native-slash-menu.png' });
  await field.press('Escape'); assert.equal(await field.inputValue(), '/');
  await command('status');
  const status = page.getByRole('region', { name: 'Session status', exact: true });
  await status.getByText('75% left', { exact: true }).waitFor();
  assert.ok((await status.innerText()).includes('90% left'));
  assert.equal(await page.getByRole('dialog').count(), 0);
  assert.equal(await field.evaluate(element => document.activeElement === element), true);
  await page.screenshot({ path: 'output/playwright/native-slash-status.png' });
  await field.press('Escape');
  for (const [name, label] of [['model', 'Model'], ['reasoning', 'Reasoning effort'], ['permissions', 'Permission mode']]) {
    await command(name, 'Tab');
    await page.getByRole('listbox', { name: label, exact: true }).waitFor();
    await page.keyboard.press('Escape');
  }
  await command('context', 'Control+Enter');
  await page.getByRole('dialog', { name: 'Context inspector', exact: true }).waitFor();
  await page.keyboard.press('Escape');
  await field.fill('/analytics');
  await menu.getByRole('option', { name: /Analytics Dashboard/ }).waitFor();
  await page.screenshot({ path: 'output/playwright/native-slash-skills.png' });
  await field.press('Enter');
  await page.getByRole('button', { name: 'Remove skill analytics-dashboard' }).waitFor();
  const selected = await native(page, 'ready');
  assert.equal(selected.chat.draftSkills[0].name, 'analytics-dashboard');
  assert.equal(selected.chat.draft, '');
  assert.deepEqual(selected.chat.items.map(item => item.id), before.chat.items.map(item => item.id));
  assert.equal(await page.evaluate(() => window.__slashCalls.filter(call => ['send', 'stop'].includes(call.method)).length), 0);
  await field.fill('Use the selected skill for this fixture-only task.');
  await field.press('Control+Enter');
  await page.waitForFunction(() => window.__slashCalls.some(call => call.method === 'send'));
  assert.ok(await page.evaluate(() => window.__slashCalls.find(call => call.method === 'send').params.input.some(input => input.type === 'skill' && input.name === 'analytics-dashboard')));
  await page.getByRole('button', { name: 'Remove skill analytics-dashboard' }).waitFor({ state: 'detached' });
  await command('goal');
  const goal = page.getByRole('region', { name: 'Goal', exact: true });
  await goal.getByLabel('Goal objective').fill('Finish the fixture check');
  await goal.getByLabel('Optional goal token budget').fill('18000');
  await goal.getByRole('button', { name: 'Set goal' }).click();
  await goal.waitFor({ state: 'detached' });
  assert.equal((await native(page, 'getGoal')).goal.tokenBudget, 18000);
  await command('goal'); await goal.getByRole('button', { name: 'Clear goal' }).click();
  await goal.waitFor({ state: 'detached' });
  assert.ok(!(await native(page, 'getGoal')).goal);
  await command('mcp');
  const mcp = page.getByRole('region', { name: 'MCP servers' });
  await mcp.getByText('fixture-search', { exact: true }).waitFor();
  await mcp.getByRole('button', { name: 'Close', exact: true }).click();
  await field.fill('');
  await native(page, 'draft', { text: '', attachments: [], skills: [] });
  const result = { nativeMenu: true, keyboardNavigation: true, statusLimitsFromRpc: true, modelPicker: true, reasoningPicker: true, permissionPicker: true, contextInspector: true, selectionDoesNotSend: true, draftSaved: true, skillsFromRpc: true, explicitSkillInput: true, goalsFromRpc: true, mcpFromRpc: true };
  writeFileSync('output/playwright/native-slash-commands-result.json', JSON.stringify(result, null, 2));
  console.log(result);
} finally { await close(); }
