/** Check the workspace picker, new-tab semantics and cleanup UI at narrow and wide chat sizes. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
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
  await page.addInitScript(() => {
    const chat = { id: 'worktree-source', threadId: 'worktree-source', title: 'Improve the shop', cwd: '/project', draft: 'Keep my current draft.', draftAttachments: [], items: [{ id: 'user', type: 'userMessage', content: [{ type: 'text', text: 'Improve the shop layout.' }] }, { id: 'answer', type: 'agentMessage', text: 'Ready to work in a separate checkout.' }], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1 };
    const snapshot = { connection: 'connected', error: '', project: 'Foony', cwd: '/project', workspaceLabel: 'master', distro: 'Ubuntu', settings: { model: '', effort: '', permissions: 'auto' }, models: [], account: {}, sessions: [], chat };
    window.__requests = [];
    const entries = [{ path: '/project', name: 'project', branch: 'master', main: true, chats: 6 }, { path: '/project.worktrees/shop-layout', name: 'shop-layout', branch: 'codex/shop-layout', chats: 2 }, { path: '/project.worktrees/completed', name: 'completed', branch: 'codex/completed', chats: 1 }];
    window.__codexSend = message => {
      const { id, method, params } = JSON.parse(message); window.__requests.push({ method, params });
      let result = {};
      if (method === 'ready') { result = snapshot; }
      if (method === 'workspaces') { result = { entries, current: chat.cwd, branches: ['master', 'develop'], base: 'master', suggestedName: 'shop-layout-a1b2', error: '' }; }
      if (method === 'changeWorkspace') { result = { id: 'new-fork' }; }
      if (method === 'inspectWorktree') { result = { path: params.path, chats: 2, blocked: params.path.endsWith('completed') ? '' : 'A chat is still working or waiting for your answer in this worktree.' }; }
      if (method === 'removeWorktree') { entries.splice(entries.findIndex(entry => entry.path === params.path), 1); }
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result } })));
    };
  });
  const results = [];
  for (const width of [360, 520, 900]) {
    await page.setViewportSize({ width, height: 850 }); await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const picker = page.getByRole('button', { name: 'Workspace: master', exact: true });
    await picker.click(); const menu = page.getByRole('dialog', { name: 'Workspace', exact: true });
    await menu.getByRole('button', { name: 'Continue in new worktree', exact: true }).waitFor();
    assert.ok(await menu.getByText('codex/shop-layout', { exact: true }).isVisible());
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    const bounds = await menu.boundingBox(); assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width && bounds.y >= 0);
    await page.screenshot({ path: `${output}/worktrees-picker-${width}.png` });
    await menu.getByRole('button', { name: 'Continue in new worktree', exact: true }).click();
    const name = menu.getByRole('textbox', { name: 'Worktree name', exact: true });
    assert.equal(await name.inputValue(), 'shop-layout-a1b2'); await name.fill('shop-polish');
    await menu.getByRole('combobox', { name: 'Starting branch or commit' }).fill('develop');
    await menu.getByRole('checkbox').check();
    await page.screenshot({ path: `${output}/worktrees-create-${width}.png` });
    await menu.getByRole('button', { name: 'Create and continue', exact: true }).click(); await menu.waitFor({ state: 'detached' });
    const call = await page.evaluate(() => window.__requests.find(value => value.method === 'changeWorkspace'));
    assert.equal(call.params.name, 'shop-polish'); assert.equal(call.params.base, 'develop'); assert.equal(call.params.includeChanges, true); assert.equal(call.params.draft, 'Keep my current draft.');
    assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue(), 'Keep my current draft.');
    await picker.click(); await menu.getByRole('button', { name: 'Remove worktree shop-layout', exact: true }).click();
    await menu.getByRole('status').filter({ hasText: 'still working' }).waitFor(); assert.equal(await menu.getByRole('button', { name: 'Remove directory', exact: true }).count(), 0);
    await page.keyboard.press('Escape'); assert.equal(await picker.evaluate(element => element === document.activeElement), true);
    await picker.click(); await menu.getByRole('button', { name: 'Remove worktree completed', exact: true }).click();
    await menu.getByRole('button', { name: 'Remove directory', exact: true }).click(); await menu.getByText('codex/completed', { exact: true }).waitFor({ state: 'detached' });
    await menu.getByRole('button', { name: 'Review', exact: true }).click();
    assert.ok(await page.evaluate(() => window.__requests.some(value => value.method === 'workspaceReview')));
    results.push({ width, picker: true, create: true, draftKept: true, cleanupBlockedWhenBusy: true, cleanupConfirmation: true, review: true, noOverflow: true, escapeRestoresFocus: true });
  }
  writeFileSync(`${output}/worktrees-browser-result.json`, JSON.stringify(results, null, 2)); console.log(results);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
