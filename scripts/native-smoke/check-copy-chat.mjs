/** Check both Markdown copy choices and keyboard defaults without changing the system clipboard. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright'; mkdirSync(output, { recursive: true });
const server = createServer((request, response) => {
  const asset = ({ '/': 'index.html', '/app.js': 'app.js', '/index.css': 'index.css' })[request.url.split('?')[0]];
  if (!asset) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html');
  response.end(readFileSync(new URL(`../../web/dist/${asset}`, import.meta.url)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CODEX_SMOKE_CHROME || undefined, headless: true });
try {
  const page = await browser.newPage();
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    const mode = new URLSearchParams(location.search).get('mode');
    const items = [{ id: 'latest', type: 'agentMessage', text: '**Latest reply** with Markdown.' }];
    const chat = { id: 'copy', threadId: mode === 'empty' ? '' : 'copy', title: 'Whole chat', cwd: '/project', draft: 'Unsent draft',
      items: mode === 'empty' ? [] : items, requests: [], status: 'idle', working: false, archived: mode === 'archived', unread: false, pinned: false, revision: 1, historyCursor: 'earlier' };
    const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: '/project', settings: { model: '', effort: '', permissions: 'auto' }, models: [], account: {}, sessions: [], chat };
    window.__requests = []; window.__copies = [];
    window.__codexSend = raw => {
      const { id, method, params } = JSON.parse(raw); window.__requests.push({ method, params });
      const reply = (result, error) => queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result, error } })));
      if (method === 'chatTranscript') {
        window.__finishCopy = error => reply({ title: chat.title, items: [
          { id: 'first', type: 'userMessage', content: [{ type: 'text', text: 'Oldest message from history.' }] },
          { id: 'command', type: 'commandExecution', command: 'git status', aggregatedOutput: 'working tree clean', exitCode: 0 }, ...items,
        ], requests: [] }, error);
        return;
      }
      if (method === 'copy') { if (window.__clipboardFails) { reply({}, 'Clipboard is busy.'); return; } window.__copies.push(params.text); }
      reply(method === 'ready' ? snapshot : method === 'workspaces' ? { entries: [], repositories: [], branches: [], current: '/project', repository: '', base: '', suggestedName: '', error: '' } : {});
    };
  });
  const url = `http://127.0.0.1:${server.address().port}/`;
  const copy = page.getByRole('button', { name: 'Copy chat as Markdown', exact: true });
  const menu = page.getByRole('menu', { name: 'Copy chat', exact: true });
  const chooseConversation = async () => {
    await copy.click();
    await page.waitForFunction(() => document.activeElement?.getAttribute('aria-label') === 'Copy conversation');
    await page.keyboard.press('Enter');
  };
  for (const width of [360, 900]) {
    await page.setViewportSize({ width, height: 720 });
    for (const mode of ['active', 'archived']) {
      await page.goto(`${url}?mode=${mode}`);
      await copy.waitFor();
      assert.equal(await page.getByRole('button', { name: 'Find chat (Ctrl+K)', exact: true }).count(), 0);
      assert.equal(await page.getByRole('button', { name: 'New chat to side', exact: true }).count(), 0);
      assert.ok(await page.getByRole('button', { name: 'Inspect context', exact: true }).isVisible());
      assert.ok(await copy.isEnabled());
      assert.equal(await page.getByText('Oldest message from history.', { exact: true }).count(), 0);
      const before = await page.getByRole('button', { name: 'Load earlier history', exact: true }).locator('..').innerText();
      await copy.click();
      await menu.waitFor();
      assert.deepEqual(await menu.getByRole('menuitem').evaluateAll(items => items.map(item => item.getAttribute('aria-label'))), ['Copy conversation', 'Copy full chat']);
      await page.waitForFunction(() => document.activeElement?.getAttribute('aria-label') === 'Copy conversation');
      const bounds = await menu.boundingBox();
      assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width && bounds.y >= 0 && bounds.y + bounds.height <= 720);
      await page.screenshot({ path: `${output}/copy-chat-menu-${mode}-${width}.png` });
      await page.keyboard.press('ArrowDown');
      assert.equal(await menu.getByRole('menuitem', { name: 'Copy full chat', exact: true }).evaluate(element => element === document.activeElement), true);
      await page.keyboard.press('Home');
      assert.equal(await menu.getByRole('menuitem', { name: 'Copy conversation', exact: true }).evaluate(element => element === document.activeElement), true);
      await page.keyboard.press('End');
      await page.keyboard.press('ArrowUp');
      assert.equal(await menu.getByRole('menuitem', { name: 'Copy conversation', exact: true }).evaluate(element => element === document.activeElement), true);
      await page.keyboard.press('Escape');
      await menu.waitFor({ state: 'detached' });
      assert.equal(await copy.evaluate(element => element === document.activeElement), true);
      await copy.press('ArrowDown'); await menu.waitFor();
      await page.keyboard.press('Tab'); await menu.waitFor({ state: 'detached' });
      assert.equal(await page.getByRole('button', { name: 'Inspect context', exact: true }).evaluate(element => element === document.activeElement), true);
      await copy.click(); await menu.waitFor();
      await page.getByText('Project', { exact: true }).click();
      await menu.waitFor({ state: 'detached' });
      assert.equal(await page.evaluate(() => window.__requests.filter(call => call.method === 'chatTranscript').length), 0, 'Opening or dismissing the menu does not copy');
      await chooseConversation();
      await page.waitForFunction(() => window.__finishCopy !== undefined);
      assert.ok(await copy.isDisabled());
      assert.equal(await copy.getAttribute('title'), 'Copying chat…');
      await page.evaluate(() => window.__finishCopy());
      await page.waitForFunction(() => window.__copies.length === 1);
      await page.getByTitle('Chat copied', { exact: true }).waitFor();
      assert.equal(await copy.getAttribute('title'), 'Chat copied');
      const text = await page.evaluate(() => window.__copies[0]);
      assert.ok(text.includes('# Whole chat'));
      assert.ok(text.includes('## User\n\nOldest message from history.'));
      assert.ok(!text.includes('git status'));
      assert.ok(!text.includes('working tree clean'));
      assert.ok(text.includes('## Codex\n\n**Latest reply** with Markdown.'));
      assert.ok(text.indexOf('Oldest message') < text.indexOf('Latest reply'));
      assert.ok(!text.includes('Unsent draft'));
      assert.equal(await page.getByRole('button', { name: 'Load earlier history', exact: true }).locator('..').innerText(), before, 'Copy does not change the visible transcript');
      if (mode === 'active') { assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue(), 'Unsent draft'); }
      else { assert.ok(await page.getByRole('button', { name: 'Restore chat', exact: true }).isVisible()); }
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      await page.screenshot({ path: `${output}/copy-chat-${mode}-${width}.png` });
      await copy.click(); await menu.getByRole('menuitem', { name: 'Copy full chat', exact: true }).click();
      await page.evaluate(() => window.__finishCopy());
      await page.waitForFunction(() => window.__copies.length === 2);
      await page.getByTitle('Chat copied', { exact: true }).waitFor();
      const full = await page.evaluate(() => window.__copies[1]);
      assert.ok(full.includes('```shell\ngit status\n```'));
      assert.ok(full.includes('working tree clean'));
      assert.ok(full.includes('Oldest message from history.'));
      assert.ok(full.includes('Latest reply'));
      // A history failure must leave the clipboard untouched and allow a retry.
      await chooseConversation(); await page.evaluate(() => window.__finishCopy('History is unavailable.'));
      await page.getByRole('alert').filter({ hasText: 'Could not copy chat: History is unavailable.' }).waitFor();
      assert.ok(await copy.isEnabled());
      assert.equal(await page.evaluate(() => window.__copies.length), 2);
      await chooseConversation(); await page.evaluate(() => { window.__clipboardFails = true; window.__finishCopy(); });
      await page.getByRole('alert').filter({ hasText: 'Could not copy chat: Clipboard is busy.' }).waitFor();
      assert.ok(await copy.isEnabled());
      assert.equal(await page.evaluate(() => window.__copies.length), 2);
      await chooseConversation(); await page.evaluate(() => { window.__clipboardFails = false; window.__finishCopy(); });
      await page.waitForFunction(() => window.__copies.length === 3);
      await page.getByTitle('Chat copied', { exact: true }).waitFor();
      assert.ok(!await page.evaluate(() => window.__copies[2].includes('working tree clean')), 'Conversation remains the default after copying full activity');
      assert.equal(await page.getByRole('alert').count(), 0);
      const methods = await page.evaluate(() => window.__requests.map(call => call.method));
      assert.equal(methods.filter(method => ['restore', 'archive', 'older', 'send'].includes(method)).length, 0);
    }
    await page.goto(`${url}?mode=empty`); await copy.waitFor();
    assert.ok(await copy.isDisabled(), 'There is nothing to copy from a new empty chat');
    console.log({ width, conversationDefault: true, fullMarkdown: true, keyboardAndDismissal: true, archived: true, unchangedHistoryAndDraft: true, failureAndRetry: true });
  }
  assert.deepEqual(errors, []);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
