/** Check archived-chat cleanup through the shared confirmation without deleting real files or chats. */
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
  await page.addInitScript(() => {
    const kind = new URLSearchParams(location.search).get('kind') || 'dirty';
    const path = kind === 'primary' ? '/project' : kind === 'folder' ? '/project/plain-folder' : '/project.worktrees/finished-task';
    const chat = { id: 'archived', threadId: 'archived', title: 'Finished task', cwd: path, draft: 'Keep this draft', items: [{ id: 'answer', type: 'agentMessage', text: 'The task is complete.' }], requests: [], status: 'idle', working: false, archived: true, unread: false, pinned: false, revision: 1 };
    const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: path, workspaceLabel: 'api · codex/finished-task', settings: { model: '', effort: '', permissions: 'auto' }, models: [], account: {}, sessions: [], chat };
    const entries = kind === 'folder' ? [] : [{ path: '/project', name: 'api', branch: 'main', main: true }, { path: '/project.worktrees/finished-task', name: 'finished-task', branch: 'codex/finished-task', main: false, missing: kind === 'missing' }];
    window.__requests = [];
    let fail = kind === 'dirty';
    let failLookup = kind === 'lookup-error';
    window.__codexSend = message => {
      const { id, method, params } = JSON.parse(message); window.__requests.push({ method, params });
      let result = {};
      if (method === 'ready') { result = snapshot; }
      if (method === 'workspaces' && failLookup) { failLookup = false; queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, error: 'Could not load workspaces' } }))); return; }
      if (method === 'workspaces') { result = { entries, repositories: [{ path: '/project', name: 'api' }], repository: '/project', current: path, branches: ['main'], base: 'main', suggestedName: 'new-task', error: '' }; }
      if (method === 'inspectWorktree') {
        result = { path, blocked: kind === 'busy' ? 'A chat is still working or waiting for your answer in this worktree.' : '', chats: 2,
          affectedChats: [{ id: 'archived', title: 'Finished task', archived: true, status: 'idle' }, { id: 'other', title: 'Check the deployment', archived: false, status: kind === 'busy' ? 'working' : 'idle' }],
          files: kind === 'dirty' ? [{ path: 'src/local-edit.ts', status: 'Changed' }, { path: 'node_modules/', status: 'Ignored' }] : [] };
      }
      if (method === 'removeWorktree') {
        if (fail) { fail = false; queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, error: 'Git removal failed. Try again.' } }))); return; }
        entries.splice(1, 1);
      }
      if (method === 'restore') { chat.archived = false; chat.revision++; window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(snapshot) })); }
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result: structuredClone(result) } })));
    };
  });
  const url = `http://127.0.0.1:${server.address().port}/`;
  for (const width of [360, 520, 900]) {
    await page.setViewportSize({ width, height: 720 });
    for (const kind of ['dirty', 'clean', 'busy', 'primary', 'missing', 'folder', 'lookup-error']) {
      await page.goto(`${url}?kind=${kind}`);
      await page.waitForFunction(() => window.__requests.some(call => call.method === 'workspaces'));
      const remove = page.getByRole('button', { name: 'Remove worktree…', exact: true });
      const restore = page.getByRole('button', { name: 'Restore chat', exact: true });
      assert.ok(await restore.isVisible());
      if (kind === 'lookup-error') { await page.getByRole('alert').filter({ hasText: 'Could not load workspaces' }).waitFor(); await page.getByRole('button', { name: 'Retry', exact: true }).click(); }
      if (['primary', 'missing', 'folder'].includes(kind)) { assert.equal(await remove.count(), 0); continue; }
      await remove.waitFor();
      await page.getByText('api · finished-task', { exact: true }).waitFor();
      if (kind === 'dirty') { await page.screenshot({ path: `${output}/archived-worktree-footer-${width}.png` }); }
      await remove.click();
      const dialog = page.getByRole('dialog', { name: 'Remove worktree', exact: true });
      await dialog.getByText('/project.worktrees/finished-task', { exact: true }).waitFor();
      await dialog.getByText('1 other chat uses this worktree', { exact: true }).click();
      assert.ok(await dialog.getByText('Check the deployment', { exact: true }).isVisible());
      assert.equal(await page.evaluate(() => window.__requests.filter(call => call.method === 'removeWorktree').length), 0);
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      const bounds = await dialog.boundingBox();
      assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width && bounds.y >= 0);
      if (kind === 'busy') {
        await dialog.getByRole('status').filter({ hasText: 'still working' }).waitFor();
        assert.equal(await dialog.getByRole('button', { name: /Remove directory|Discard changes and remove/ }).count(), 0);
        await page.keyboard.press('Escape');
        assert.equal(await remove.evaluate(element => element === document.activeElement), true);
        continue;
      }
      const confirmation = dialog.getByRole('button', { name: kind === 'dirty' ? 'Discard changes and remove' : 'Remove directory', exact: true });
      await confirmation.waitFor();
      await page.screenshot({ path: `${output}/archived-worktree-${kind}-${width}.png` });
      await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
      assert.equal(await page.evaluate(() => window.__requests.filter(call => call.method === 'removeWorktree').length), 0);
      assert.equal(await remove.evaluate(element => element === document.activeElement), true);
      await remove.click(); await confirmation.waitFor(); await confirmation.click();
      if (kind === 'dirty') {
        await dialog.getByRole('alert').filter({ hasText: 'Git removal failed' }).waitFor();
        assert.ok(await confirmation.isEnabled()); await confirmation.click();
      }
      await dialog.waitFor({ state: 'detached' });
      assert.equal(await remove.count(), 0);
      assert.ok(await restore.isVisible());
      await page.waitForFunction(() => document.activeElement?.textContent === 'Restore chat');
      assert.ok(await page.getByText('The task is complete.', { exact: true }).isVisible());
      const requests = await page.evaluate(() => window.__requests);
      assert.ok(requests.filter(call => call.method === 'removeWorktree').every(call => call.params.path === '/project.worktrees/finished-task' && call.params.discardChanges === (kind === 'dirty')));
      assert.equal(requests.filter(call => call.method === 'restore' || call.method === 'archive').length, 0, 'Removing a worktree never restores or archives chats');
      assert.equal(requests.filter(call => call.method === 'workspaces').length, kind === 'lookup-error' ? 2 : 1, 'Removal does not add full-project rescans');
      await restore.click();
      assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue(), 'Keep this draft');
    }
    console.log({ width, archivedCleanup: true, relatedChats: true, explicitDiscard: true, cancel: true, retry: true, activeBlocked: true, primaryAndMissingHidden: true, historyAndDraftKept: true });
  }
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
