/** Read the actual GLM fixture chat through IDEA JCEF without sending another model turn. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const { native, findChat, close } = await connectNative();
try {
  const { page } = await findChat(chat => chat.cwd.includes('codex-glm-smoke-'));
  const state = await native(page, 'ready');
  assert.ok(state.models.some(model => model.isLocal && model.model === 'glm-flash-uncensored'));
  await page.getByRole('button', { name: 'Model: GLM 5.3 Flash (local)', exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: /Reasoning effort:/ }).count(), 0);
  assert.equal(await page.getByRole('button', { name: 'Sign in with ChatGPT', exact: true }).count(), 0);
  const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
  await field.fill('/status'); await page.getByRole('option', { name: /^Status/ }).click();
  const status = page.getByRole('region', { name: 'Session status' });
  await status.getByText('Local GLM uses your computer. No account quota applies.', { exact: true }).waitFor();
  mkdirSync('output/playwright', { recursive: true });
  await page.screenshot({ path: 'output/playwright/native-local-glm.png' });
  writeFileSync('output/playwright/native-local-glm.json', JSON.stringify({ model: state.settings.model, provider: state.chat.modelProvider, context: state.chat.tokenUsage?.modelContextWindow, status: state.chat.status }, null, 2));
  console.log('Actual IntelliJ JCEF local model controls passed.');
} finally { await close(); }
