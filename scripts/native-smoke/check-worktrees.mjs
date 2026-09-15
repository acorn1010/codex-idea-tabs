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
    let failDiscard = true;
    const entries = [{ path: '/project', name: 'project', branch: 'master', main: true, chats: 6 }, { path: '/project.worktrees/shop-layout', name: 'shop-layout', branch: 'codex/shop-layout', chats: 2 }, { path: '/project.worktrees/dirty', name: 'dirty', branch: 'codex/dirty', chats: 1 }, { path: '/project.worktrees/completed', name: 'completed', branch: 'codex/completed', chats: 1 }];
    window.__codexSend = message => {
      const { id, method, params } = JSON.parse(message); window.__requests.push({ method, params });
      let result = {};
      if (method === 'ready') { result = snapshot; }
      if (method === 'workspaces') { result = { entries, current: chat.cwd, branches: ['master', 'develop'], base: 'master', suggestedName: 'shop-layout-a1b2', error: '' }; }
      if (method === 'changeWorkspace') { result = { id: 'new-fork' }; }
      if (method === 'inspectWorktree') { result = { path: params.path, chats: 2, blocked: !params.path.endsWith('shop-layout') ? '' : 'A chat is still working or waiting for your answer in this worktree.' }; }
      if (method === 'inspectWorktree' && params.path.endsWith('dirty')) { result.files = [{ path: 'src/edited.ts', status: 'Changed' }, { path: 'new.txt', status: 'Untracked' }, { path: '.local-cache/', status: 'Ignored' }]; }
      if (method === 'removeWorktree' && params.path.endsWith('dirty') && failDiscard) {
        failDiscard = false;
        queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, error: 'Git removal failed. Try again.' } })));
        return;
      }
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
    const start = menu.getByRole('combobox', { name: 'Starting branch or commit' });
    await menu.getByRole('button', { name: 'Show starting branches' }).click();
    assert.deepEqual(await menu.getByRole('option').allTextContents(), ['master', 'develop']);
    await page.screenshot({ path: `${output}/starting-branches-${width}.png` });
    await start.fill('dev');
    assert.deepEqual(await menu.getByRole('option').allTextContents(), ['develop']);
    await start.press('ArrowDown'); await start.press('Enter');
    assert.equal(await start.inputValue(), 'develop');
    assert.equal(await menu.getByRole('listbox').count(), 0);
    assert.equal(await page.evaluate(() => window.__requests.filter(value => value.method === 'changeWorkspace').length), 0);
    await start.fill('HEAD~2');
    await menu.getByRole('status').filter({ hasText: 'No matching branches' }).waitFor();
    await start.press('Escape'); assert.ok(await menu.isVisible());
    assert.equal(await start.inputValue(), 'HEAD~2');
    await start.press('ArrowUp'); await start.press('Enter');
    assert.equal(await start.inputValue(), 'develop');
    await menu.getByRole('checkbox').check();
    await page.screenshot({ path: `${output}/worktrees-create-${width}.png` });
    await menu.getByRole('button', { name: 'Create and continue', exact: true }).click(); await menu.waitFor({ state: 'detached' });
    const call = await page.evaluate(() => window.__requests.find(value => value.method === 'changeWorkspace'));
    assert.equal(call.params.name, 'shop-polish'); assert.equal(call.params.base, 'develop'); assert.equal(call.params.includeChanges, true); assert.equal(call.params.draft, 'Keep my current draft.');
    assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue(), 'Keep my current draft.');
    await picker.click(); await menu.getByRole('button', { name: 'Remove worktree shop-layout', exact: true }).click();
    await menu.getByRole('status').filter({ hasText: 'still working' }).waitFor(); assert.equal(await menu.getByRole('button', { name: 'Remove directory', exact: true }).count(), 0);
    await page.keyboard.press('Escape'); assert.equal(await picker.evaluate(element => element === document.activeElement), true);
    await picker.click(); await menu.getByRole('button', { name: 'Remove worktree dirty', exact: true }).click();
    await menu.getByRole('list', { name: 'Local changes and files to discard' }).waitFor();
    assert.equal(await menu.getByRole('listitem').count(), 3);
    await menu.getByText('.local-cache/', { exact: true }).waitFor();
    assert.ok(await menu.getByText(/permanently deleted/).isVisible());
    assert.equal(await menu.getByRole('button', { name: 'Remove directory', exact: true }).count(), 0);
    await page.screenshot({ path: `${output}/worktrees-discard-${width}.png` });
    await menu.getByRole('button', { name: 'Cancel', exact: true }).click();
    assert.equal(await page.evaluate(() => window.__requests.filter(value => value.method === 'removeWorktree').length), 0);
    await picker.click(); await menu.getByRole('button', { name: 'Remove worktree dirty', exact: true }).click();
    const discard = menu.getByRole('button', { name: 'Discard changes and remove', exact: true });
    await discard.click(); await menu.getByRole('alert').filter({ hasText: 'Git removal failed' }).waitFor();
    assert.ok(await discard.isEnabled());
    await discard.click();
    await menu.getByRole('button', { name: 'Remove worktree dirty', exact: true }).waitFor({ state: 'detached' });
    const discarded = await page.evaluate(() => window.__requests.filter(value => value.method === 'removeWorktree'));
    assert.equal(discarded.length, 2); assert.ok(discarded.every(call => call.params.discardChanges === true));
    await page.keyboard.press('Escape');
    await picker.click(); await menu.getByRole('button', { name: 'Remove worktree completed', exact: true }).click();
    await menu.getByText('codex/completed', { exact: true }).waitFor();
    assert.ok(await menu.getByText(/and all its commits will be kept/).isVisible());
    await page.screenshot({ path: `${output}/worktrees-remove-${width}.png` });
    await menu.getByRole('button', { name: 'Remove directory', exact: true }).click(); await menu.getByText('codex/completed', { exact: true }).waitFor({ state: 'detached' });
    assert.equal(await page.evaluate(() => window.__requests.filter(value => value.method === 'removeWorktree').at(-1).params.discardChanges), false);
    await menu.getByRole('button', { name: 'Review', exact: true }).click();
    assert.ok(await page.evaluate(() => window.__requests.some(value => value.method === 'workspaceReview')));
    results.push({ width, picker: true, create: true, draftKept: true, cleanupBlockedWhenBusy: true, cleanupConfirmation: true, explicitDiscard: true, cancelKeptFiles: true, retry: true, review: true, noOverflow: true, escapeRestoresFocus: true });
  }
  writeFileSync(`${output}/worktrees-browser-result.json`, JSON.stringify(results, null, 2)); console.log(results);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
