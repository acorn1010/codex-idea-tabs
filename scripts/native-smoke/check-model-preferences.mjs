/** Check immediate model preference saving, new tabs, and restored IDEA settings without a model call. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const { native, findChat, close } = await connectNative();
const restored = process.env.CODEX_SMOKE_RESTORED === 'true' || process.argv.includes('--restored');
const expectedInitial = restored ? ['fixture-model', 'high'] : ['gpt-6-astra', 'xhigh'];
async function choose(page, label, value) {
  await page.getByRole('button', { name: new RegExp(`^${label}:`) }).click();
  await page.getByRole('option', { name: value, exact: true }).click();
}
async function saved(page, model, effort) {
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    const state = await native(page, 'ready');
    if (state.settings.model === model && state.settings.effort === effort) {
      assert.equal(state.settings.modelSelectionSaved, true);
      return state;
    }
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(`Selection was not saved before sending: ${model}, ${effort}`);
}
async function newTab(page) {
  const before = new Set((await native(page, 'ready')).sessions.map(chat => chat.id));
  await native(page, 'new');
  return findChat(chat => !before.has(chat.id));
}
try {
  const current = await findChat(() => true);
  await native(current.page, 'openThread', { id: 'review' });
  const { page, state } = await findChat(chat => chat.id === 'review');
  assert.deepEqual([state.settings.model, state.settings.effort], expectedInitial, 'Initial selection comes from the default or saved IDEA settings');
  await choose(page, 'Model', 'Test model');
  await choose(page, 'Reasoning effort', 'high high');
  await saved(page, 'fixture-model', 'high');
  const next = await newTab(page);
  await next.page.getByRole('button', { name: 'Model: Test model', exact: true }).waitFor();
  await next.page.getByRole('button', { name: 'Reasoning effort: high', exact: true }).waitFor();
  await choose(next.page, 'Model', 'GPT-6-Astra');
  await saved(next.page, 'gpt-6-astra', 'high');
  await choose(next.page, 'Reasoning effort', 'xhigh xhigh');
  await saved(next.page, 'gpt-6-astra', 'xhigh');
  await choose(next.page, 'Model', 'Test model');
  await saved(next.page, 'fixture-model', 'low');
  await choose(next.page, 'Model', 'Codex default');
  await saved(next.page, '', '');
  const defaults = await newTab(next.page);
  await defaults.page.getByRole('button', { name: 'Model: Codex default', exact: true }).waitFor();
  await choose(defaults.page, 'Model', 'Test model');
  await choose(defaults.page, 'Reasoning effort', 'high high');
  await saved(defaults.page, 'fixture-model', 'high');
  assert.equal(defaults.chat.items.length, 0, 'No message was required to save the selection');
  await native(page, 'send', { text: 'Fixture check: this old tab must not replace the latest model preference.', model: 'gpt-6-astra', effort: 'xhigh', permissions: 'read' });
  await saved(defaults.page, 'fixture-model', 'high');
  const result = { restored, immediateSave: true, newTabs: true, compatibleEffortKept: true, unsupportedEffortFallback: true, explicitCodexDefault: true, otherTabSendPreservesChoice: true };
  mkdirSync('output/playwright', { recursive: true });
  writeFileSync(`output/playwright/model-preferences-${result.restored ? 'restored' : 'initial'}.json`, JSON.stringify(result, null, 2));
  console.log(result);
} finally { await close(); }
