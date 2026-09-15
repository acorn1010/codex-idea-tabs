/** Exercise IDEA's registered keymap actions and JCEF bridge in a disposable profile. No OS key injection or model calls. */
import { connectNative } from './native-bridge.mjs';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';
const probeRoot = process.env.CODEX_SMOKE_PROBE_ROOT;
assert.ok(probeRoot, 'Set CODEX_SMOKE_PROBE_ROOT to the disposable profile path');
const baseline = process.argv.includes('--baseline');
const { native, findChat, close } = await connectNative();
try {
  const { page, chat } = await findChat(chat => chat.id === 'review');
  const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
  await field.fill('one two three\nfour five');
  await page.evaluate(() => {
    window.__navigationEvents = [];
    window.addEventListener('codex-text-navigation', event => window.__navigationEvents.push(event.detail));
  });
  const results = [];
  for (const [label, keyCode, modifiers, start, expected] of [
    ['Command+Left', 37, 256, 7, [4, 4]],
    ['Command+Right', 39, 256, 4, [7, 7]],
    ['Command+Shift+Left', 37, 320, 7, [4, 7]],
    ['Command+Shift+Right', 39, 320, 4, [4, 7]],
    ['Home', 36, 0, 7, [0, 0]],
    ['End', 35, 0, 4, [13, 13]],
    ['Shift+Home', 36, 64, 7, [0, 7]],
    ['Shift+End', 35, 64, 4, [4, 13]],
  ]) {
    await field.evaluate((element, start) => { element.focus(); element.setSelectionRange(start, start); }, start);
    const id = `text-navigation-${Date.now()}`;
    writeFileSync(`${probeRoot}/logs/ui-probe-request.json`, JSON.stringify({ id, chatId: chat.id, shortcut: 'text-navigation', keyCode, modifiers, x: 0, y: 0, viewportWidth: 1 }));
    let response;
    for (let i = 0; i < 100; i++) {
      try { response = JSON.parse(readFileSync(`${probeRoot}/logs/ui-probe-result.json`)); } catch {}
      if (response?.id === id) { break; }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    assert.equal(response?.id, id, 'Native test probe did not respond');
    assert.equal(response.error, undefined);
    await new Promise(resolve => setTimeout(resolve, 250));
    const actual = await field.evaluate(element => [element.selectionStart, element.selectionEnd]);
    results.push({ label, actual, expected, matchingActions: response.matchingActions });
    if (!baseline) { assert.equal(response.matchingActions, 1, label); assert.deepEqual(actual, expected, label); }
  }
  assert.equal(await field.inputValue(), 'one two three\nfour five');
  const events = await page.evaluate(() => window.__navigationEvents);
  if (!baseline) { assert.equal(events.length, 8, 'Each key must navigate exactly once'); }
  const output = { baseline, events, checks: results };
  mkdirSync('output/playwright', { recursive: true });
  writeFileSync(`output/playwright/text-navigation-native-${baseline ? 'before' : 'after'}.json`, JSON.stringify(output, null, 2));
  console.log(output);
} finally { await close(); }
