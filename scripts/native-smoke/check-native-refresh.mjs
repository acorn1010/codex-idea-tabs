/** Check bounded asynchronous refreshes in four real JCEF panes using fixture drafts. */
import assert from 'node:assert/strict';
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { connectNative } from './native-bridge.mjs';

const browser = await chromium.connectOverCDP(process.env.CODEX_SMOKE_CDP || 'http://127.0.0.1:9228');
const { native, close } = await connectNative();
try {
  const pages = browser.contexts().flatMap(context => context.pages());
  assert.equal(pages.length, 4, 'Use the isolated four-pane smoke profile');
  const ids = [];
  for (const page of pages) {
    await page.evaluate(() => {
      window.__refreshStates = [];
      window.addEventListener('codex-state', event => {
        const chat = event.detail.chat;
        window.__refreshStates.push({ id: chat.id, revision: chat.revision, draft: chat.draft });
      });
    });
    ids.push((await native(page, 'ready')).chat.id);
  }
  for (let round = 0; round < 30; round++) {
    await Promise.all(pages.map((page, index) => native(page, 'draft', { text: `Refresh fixture ${round} ${index}`, attachments: [], skills: [] })));
  }
  for (let index = 0; index < pages.length; index++) {
    const page = pages[index];
    await page.waitForFunction(expected => window.__refreshStates.at(-1)?.draft === expected, `Refresh fixture 29 ${index}`);
    const states = await page.evaluate(() => window.__refreshStates);
    assert.ok(states.length > 0);
    states.forEach((state, offset) => {
      assert.equal(state.id, ids[index]);
      if (offset > 0) { assert.ok(state.revision >= states[offset - 1].revision, 'A late refresh replaced newer state'); }
    });
  }
  console.log(JSON.stringify({ panes: 4, draftChanges: 120, latestDraftsPublished: true, revisionsInOrder: true }));
} finally { await close(); await browser.close(); }
