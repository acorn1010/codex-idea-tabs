/** Send Ctrl+Enter through native IDEA key handling and verify steering reaches the active turn. */
import { connectNative } from './native-bridge.mjs';
import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const { native, findChat, close } = await connectNative();
try {
  const existing = await findChat(() => true);
  const threadId = `steer-${Date.now()}`;
  await native(existing.page, 'openThread', { thread: { id: threadId, name: 'Immediate steer shortcut', cwd: existing.state.cwd } });
  const { page, chat } = await findChat(chat => chat.threadId === threadId && chat.working);
  const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
  const text = `STEER_SHORTCUT_${Date.now()}`;
  await field.fill(text);
  await page.evaluate(() => {
    window.__shortcutEvents = [];
    document.addEventListener('keydown', event => window.__shortcutEvents.push({ key: event.key, ctrl: event.ctrlKey, target: event.target.getAttribute('aria-label') }), true);
  });
  const point = await field.evaluate(element => {
    const bounds = element.getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2, viewportWidth: innerWidth };
  });
  const id = `shortcut-${Date.now()}`;
  writeFileSync('logs/ui-probe-request.json', JSON.stringify({ id, chatId: chat.id, ...point, shortcut: 'ctrl-enter' }));
  const deadline = Date.now() + 10000;
  let result;
  while (Date.now() < deadline) {
    if (existsSync('logs/ui-probe-result.json')) {
      const response = JSON.parse(readFileSync('logs/ui-probe-result.json', 'utf8'));
      if (response.id === id) { assert.ok(!response.error, response.error); break; }
    }
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  while (Date.now() < deadline) {
    const state = await native(page, 'ready');
    const sent = state.chat.items.filter(item => item.type === 'userMessage' && JSON.stringify(item.content).includes(text));
    if (sent.length) {
      assert.equal(sent.length, 1, 'The shortcut sends exactly once');
      assert.equal(state.chat.turnId, 'fixture-build', 'Steering keeps the original turn active');
      assert.equal(state.chat.working, true);
      assert.equal(await field.inputValue(), '', 'The sent draft clears');
      result = { nativeCtrlEnter: true, steeredActiveTurn: true, exactlyOnce: true, draftCleared: true };
      break;
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  mkdirSync('output/playwright', { recursive: true });
  await page.screenshot({ path: 'output/playwright/steer-shortcut.png' });
  assert.ok(result, `Ctrl+Enter did not steer the active turn. Draft: ${await field.inputValue()}. Keys: ${JSON.stringify(await page.evaluate(() => window.__shortcutEvents))}`);
  writeFileSync('output/playwright/steer-shortcut-result.json', JSON.stringify(result, null, 2));
  console.log(result);
} finally { await close(); }
