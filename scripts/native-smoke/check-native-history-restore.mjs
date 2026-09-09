/** Verify the newest page, earlier pagination, and saved draft through a real IDEA restart. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const restored = process.argv.includes('--restored');
const { native, findChat, close } = await connectNative();
try {
  const { page, chat } = await findChat(chat => chat.threadId === 'review' && chat.items.length === 100 && chat.items.at(-1)?.id === 'restore-159');
  assert.equal(chat.items[0].id, 'restore-60');
  assert.ok(chat.historyCursor, 'Earlier history must remain available');
  const field = page.getByRole('textbox', { name: 'Message Codex', exact: true });
  if (restored) { assert.equal(await field.inputValue(), 'Keep this draft across restart'); }
  const latest = page.getByText('Latest reply 159', { exact: true });
  await latest.waitFor();
  await page.waitForFunction(() => {
    const element = [...document.querySelectorAll('p')].find(element => element.textContent === 'Latest reply 159');
    if (!element) { return false; }
    const parent = element.closest('.overflow-y-auto');
    const bounds = element.getBoundingClientRect(), viewport = parent?.getBoundingClientRect();
    return viewport && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom;
  });
  mkdirSync('output/playwright', { recursive: true });
  const phase = restored ? 'restored' : 'before-restart';
  await page.screenshot({ path: `output/playwright/history-${phase}.png` });
  const earlier = await native(page, 'older', { cursor: chat.historyCursor });
  assert.equal(earlier.data.length, 60);
  const full = await native(page, 'ready');
  assert.equal(full.chat.items.length, 160);
  full.chat.items.forEach((item, index) => assert.equal(item.id, `restore-${index}`));
  if (!restored) {
    await field.fill('Keep this draft across restart');
    await native(page, 'draft', { text: 'Keep this draft across restart', attachments: [] });
  }
  const result = { phase, newestPageInOrder: true, latestReplyVisible: true, earlierMessagesInOrder: true, fullHistoryItems: 160, draftRestored: restored };
  writeFileSync(`output/playwright/history-${phase}-result.json`, JSON.stringify(result, null, 2));
  console.log(result);
} finally { await close(); }
