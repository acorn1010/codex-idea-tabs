/** Press physical Up keys in IDEA to verify recall survives native editor key handling. */
import { connectNative } from './native-bridge.mjs';
import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const { native, findChat, close } = await connectNative();
try {
  const existing = await findChat(() => true);
  await native(existing.page, 'openThread', { thread: { id: 'review', name: 'Review session recovery', cwd: existing.state.cwd } });
  const { page, chat } = await findChat(chat => chat.threadId === 'review' && chat.items.filter(item => item.type === 'userMessage').length >= 3);
  const messages = chat.items.filter(item => item.type === 'userMessage').map(item => item.text || (item.content || []).filter(part => part.type === 'text').map(part => part.text).join('\n')).filter(text => text.trim()).reverse();
  const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
  const savedDraft = await field.inputValue();
  try {
    await field.fill('');
    const point = await field.evaluate(element => {
      const bounds = element.getBoundingClientRect();
      return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2, viewportWidth: innerWidth };
    });
    const up = async focus => {
      const id = `recall-${Date.now()}`;
      writeFileSync('logs/ui-probe-request.json', JSON.stringify({ id, chatId: chat.id, ...point, shortcut: 'up', focus }));
      const deadline = Date.now() + 10000;
      while (Date.now() < deadline) {
        if (existsSync('logs/ui-probe-result.json')) {
          const response = JSON.parse(readFileSync('logs/ui-probe-result.json', 'utf8'));
          if (response.id === id) { assert.ok(!response.error, response.error); return; }
        }
        await new Promise(resolve => setTimeout(resolve, 50));
      }
      throw new Error('Native Up probe timed out');
    };
    for (let index = 0; index < messages.length; index++) {
      await up(index === 0);
      assert.equal(await field.inputValue(), messages[index], `Native Up recalled message ${index + 1}`);
    }
    await up(false);
    assert.equal(await field.inputValue(), messages.at(-1), 'Recall stops at the oldest message');
    await field.press('ArrowLeft');
    await up(false);
    assert.equal(await field.inputValue(), messages.at(-1));
    await field.fill(''); await up(true);
    await field.press('ArrowLeft'); await up(false);
    assert.equal(await field.inputValue(), messages[0], 'Another key ends recall before reaching the oldest message');
    mkdirSync('output/playwright', { recursive: true });
    await page.screenshot({ path: 'output/playwright/native-message-recall.png' });
    const result = { physicalUp: true, messagesRecalled: messages.length, newestToOldest: true, stopsAtOldest: true, otherKeyStops: true };
    writeFileSync('output/playwright/native-message-recall-result.json', JSON.stringify(result, null, 2));
    console.log(result);
  } finally {
    await field.fill(savedDraft);
    await native(page, 'draft', { text: savedDraft });
  }
} finally { await close(); }
