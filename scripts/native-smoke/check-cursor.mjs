/** Verify actual AWT cursor changes from physical mouse movement in the isolated IDEA window. */
import { connectNative } from './native-bridge.mjs';
import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

const { native, findChat, close } = await connectNative();
async function move(page, chatId, selector, attempt = 0) {
  const coords = await page.locator(selector).evaluate(element => {
    const r = element.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2, viewportWidth: innerWidth, css: getComputedStyle(element).cursor };
  });
  const id = `${Date.now()}-${selector}`;
  writeFileSync('logs/ui-probe-request.json', JSON.stringify({ id, chatId, ...coords }));
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    if (existsSync('logs/ui-probe-result.json')) {
      const result = JSON.parse(readFileSync('logs/ui-probe-result.json', 'utf8'));
      if (result.id === id) {
        assert.ok(!result.error, result.error);
        if (result.windows && (Math.abs(result.windows.x - result.mouseX) > 2 || Math.abs(result.windows.y - result.mouseY) > 2)) {
          assert.ok(attempt < 3, 'The mouse moved during the native probe. Run again while the mouse is idle.');
          return move(page, chatId, selector, attempt + 1);
        }
        return { ...coords, ...result };
      }
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('Native UI probe did not reply. Set -Dcodex.smoke.ui.probe=true.');
}
try {
  const first = await findChat(() => true);
  await native(first.page, 'openThread', { id: 'review' });
  const { page, chat } = await findChat(chat => chat.id === 'review');
  const results = [];
  results.push(await move(page, chat.id, 'textarea[aria-label="Message Codex"]'));
  results.push(await move(page, chat.id, 'button[aria-label="Attach files"]'));
  const message = page.locator('article[data-message-role="user"]').first();
  await message.hover();
  await message.getByRole('button', { name: 'Edit message', exact: true }).click();
  results.push(await move(page, chat.id, 'textarea[aria-label="Edit message text"]'));
  await message.getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.keyboard.press(process.platform === 'darwin' ? 'Meta+k' : 'Control+k');
  results.push(await move(page, chat.id, 'input[aria-label="Search conversations"]'));
  await page.keyboard.press('Escape');
  mkdirSync('output/playwright', { recursive: true });
  writeFileSync('output/playwright/native-cursor-result.json', JSON.stringify(results, null, 2));
  console.log(results);
  assert.equal(results[0].cursor, 2, 'Native text cursor is the I-beam');
  assert.equal(results[1].cursor, 12, 'Buttons use the native hand cursor');
  assert.equal(results[2].cursor, 2, 'The inline message editor uses the I-beam');
  assert.equal(results[3].cursor, 2, 'Search inside a chat tab uses the I-beam');
  if (process.platform === 'win32') {
    assert.equal(results[0].windows.text, true, 'Windows displays its actual I-beam cursor');
    assert.equal(results[1].windows.hand, true, 'Windows displays its actual hand cursor');
    assert.equal(results[2].windows.text, true, 'Windows displays its I-beam over the message editor');
    assert.equal(results[3].windows.text, true, 'Windows displays its I-beam over the chat search');
  }
} finally { await close(); }
