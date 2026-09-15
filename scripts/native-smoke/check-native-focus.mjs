/** Prove that new native tabs accept typing without clicking or focusing the message input. */
import { connectNative } from './native-bridge.mjs';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
const { native, findChat, close } = await connectNative();
async function nativeFocus(id) {
  const deadline = Date.now() + 10000;
  let state;
  while (Date.now() < deadline) {
    try { state = JSON.parse(readFileSync(process.env.CODEX_SMOKE_FOCUS_STATE || 'logs/focus-state.json', 'utf8')); } catch {}
    if (state?.chat === id && state.withinEditor) { return state; }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error(`Native keyboard focus missed the new chat: ${JSON.stringify(state)}`);
}
try {
  let source = await findChat(chat => !!chat.threadId);
  const results = [];
  for (const split of [false, true]) {
    const previous = JSON.parse(readFileSync(process.env.CODEX_SMOKE_FOCUS_STATE || 'logs/focus-state.json', 'utf8')).chat;
    await native(source.page, 'new', { split });
    let selected = previous;
    const deadline = Date.now() + 10000;
    while (selected === previous && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 100));
      try { selected = JSON.parse(readFileSync(process.env.CODEX_SMOKE_FOCUS_STATE || 'logs/focus-state.json', 'utf8')).chat; } catch {}
    }
    assert.notEqual(selected, previous, 'New chat must select a new native tab');
    const target = await findChat(chat => chat.id === selected);
    const field = target.page.getByRole('textbox', { name: 'Message Codex', exact: true });
    await target.page.waitForFunction(() => document.activeElement?.getAttribute('aria-label') === 'Message Codex');
    const focus = await nativeFocus(target.chat.id);
    await target.page.keyboard.type(split ? 'Split chat typing works' : 'New chat typing works');
    assert.equal(await field.inputValue(), split ? 'Split chat typing works' : 'New chat typing works');
    // A later state update must not pull focus away from another control.
    const workspace = target.page.getByRole('button', { name: /^Workspace:/ });
    await workspace.click();
    await target.page.getByRole('dialog', { name: 'Workspace', exact: true }).waitFor();
    await native(target.page, 'ready');
    assert.ok(!await field.evaluate(element => element === document.activeElement));
    await target.page.keyboard.press('Escape');
    results.push({ split, nativeOwner: focus.owner, composerFocused: true, typingWithoutClick: true, laterUpdatesKeepFocus: true });
    source = target;
  }
  mkdirSync('output/playwright', { recursive: true });
  writeFileSync('output/playwright/native-focus-result.json', JSON.stringify(results, null, 2)); console.log(results);
} finally { await close(); }
