/** Connect browser checks to native IDEA chats, including webviews opened after the test starts. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';

export async function connectNative() {
  const endpoint = process.env.CODEX_SMOKE_CDP || 'http://127.0.0.1:9228';
  let browser = await chromium.connectOverCDP(endpoint);
  const connections = [browser];
  async function native(page, method, params = {}) {
    return page.evaluate(({ method, params }) => new Promise((resolve, reject) => {
      const id = window.__editSmokeSequence = (window.__editSmokeSequence || -1000) - 1;
      const listener = event => {
        if (event.detail.id !== id) { return; }
        window.removeEventListener('codex-reply', listener);
        clearTimeout(timeout);
        if (event.detail.error) { reject(new Error(event.detail.error)); } else { resolve(event.detail.result); }
      };
      const timeout = setTimeout(() => { window.removeEventListener('codex-reply', listener); reject(new Error(`Native ${method} timed out`)); }, 30000);
      window.addEventListener('codex-reply', listener);
      window.__codexSend(JSON.stringify({ id, method, params }));
    }), { method, params });
  }
  async function findChat(predicate) {
    const deadline = Date.now() + 60000;
    let refreshed = 0;
    while (Date.now() < deadline) {
      // JCEF does not reliably announce newly created native webviews to an existing CDP connection.
      if (Date.now() - refreshed > 2000) {
        browser = await chromium.connectOverCDP(endpoint);
        connections.push(browser);
        refreshed = Date.now();
      }
      for (const page of browser.contexts().flatMap(context => context.pages())) {
        if (!await page.getByRole('textbox', { name: 'Message Codex', exact: true }).count()) { continue; }
        const state = await native(page, 'ready');
        if (predicate(state.chat)) { return { page, chat: state.chat, state }; }
      }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error('Expected native chat did not open');
  }
  return { native, findChat, close: () => Promise.all(connections.map(connection => connection.close())) };
}
