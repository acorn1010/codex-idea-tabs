/** Check repository search and checkout choices at narrow and wide chat sizes without a model. */
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
    const chat = { id: 'repo-chat', threadId: '', title: 'New chat', cwd: '/project/client', draft: 'Keep this draft', draftAttachments: [], items: [], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1 };
    const repositories = ['client', 'server', ...Array.from({ length: 40 }, (_, i) => `service-${i}`)].map(name => ({ path: `/project/${name}`, name, project: false }));
    const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: chat.cwd, workspaceLabel: 'client · main', distro: '', settings: { model: '', effort: '', permissions: 'auto' }, models: [], account: {}, sessions: [], chat };
    window.__requests = [];
    window.__codexSend = message => {
      const { id, method, params } = JSON.parse(message); window.__requests.push({ method, params });
      let result = {};
      if (method === 'ready') { result = snapshot; }
      if (method === 'workspaces') {
        const repo = repositories.find(value => value.path === chat.cwd);
        result = { repositories, repository: repo?.path || '', projectPath: '/project', sharedGuidanceFolder: '/project/shared guidance with a long folder name', entries: repo ? [{ path: repo.path, name: repo.name, branch: 'main', main: true }] : [], current: chat.cwd, branches: repo ? ['main', `${repo.name}-only`] : [], base: 'main', suggestedName: 'new-task', error: '' };
      }
      if (method === 'changeWorkspace' && params.path) {
        chat.cwd = params.path; chat.revision++; snapshot.cwd = chat.cwd;
        snapshot.workspaceLabel = chat.cwd === '/project' ? 'Project folder' : `${chat.cwd.split('/').pop()} · main`;
        result = { id: chat.id };
        queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-state', { detail: snapshot })));
      }
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result } })));
    };
  });
  const results = [];
  for (const width of [360, 520, 900]) {
    await page.setViewportSize({ width, height: 850 }); await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const picker = page.getByRole('button', { name: /^Workspace:/ });
    const menu = page.getByRole('dialog', { name: 'Workspace', exact: true });
    await picker.click(); await menu.getByRole('button', { name: /^Shared guidance/ }).waitFor();
    await page.screenshot({ path: `${output}/shared-guidance-${width}.png` });
    await menu.getByRole('button', { name: /^Shared guidance/ }).click();
    assert.equal(await page.evaluate(() => window.__requests.at(-1).method), 'settings');
    await picker.click();
    await menu.getByRole('button', { name: 'Choose repository', exact: true }).click();
    const search = menu.getByRole('textbox', { name: 'Search repositories' });
    await search.fill('server');
    assert.equal(await menu.getByRole('button', { name: /^Use repository/ }).count(), 1);
    assert.ok(await menu.getByRole('button', { name: 'Use repository server', exact: true }).isVisible());
    const bounds = await menu.boundingBox(); assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= width && bounds.y >= 0);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: `${output}/repositories-search-${width}.png` });
    await search.fill('no-such-repository'); await menu.getByRole('status').filter({ hasText: 'No matching repositories' }).waitFor();
    await search.fill('server'); await menu.getByRole('button', { name: 'Use repository server', exact: true }).click();
    await menu.waitFor({ state: 'detached' });
    await page.getByRole('button', { name: 'Workspace: server · main', exact: true }).waitFor();
    assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue(), 'Keep this draft');
    await picker.click(); await menu.getByRole('button', { name: 'New worktree', exact: true }).click();
    const branches = await page.locator('#workspace-branches option').evaluateAll(options => options.map(option => option.value));
    assert.deepEqual(branches, ['main', 'server-only']);
    await page.screenshot({ path: `${output}/repositories-create-${width}.png` });
    await page.keyboard.press('Escape'); assert.equal(await picker.evaluate(element => element === document.activeElement), true);
    await picker.click(); await menu.getByRole('button', { name: /^Shared guidance/ }).waitFor();
    await menu.getByRole('button', { name: 'Choose repository', exact: true }).click();
    await menu.getByRole('button', { name: /Project folder/ }).click(); await menu.waitFor({ state: 'detached' });
    await picker.click(); await menu.getByText('Choose a repository to create a worktree.', { exact: true }).waitFor();
    assert.equal(await menu.getByRole('button', { name: 'New worktree', exact: true }).count(), 0);
    await menu.getByRole('button', { name: 'Choose repository', exact: true }).click();
    await page.screenshot({ path: `${output}/repositories-list-${width}.png` });
    results.push({ width, sharedGuidance: true, search: true, selectedRepository: true, branchesIsolated: true, draftKept: true, projectFolder: true, noOverflow: true });
  }
  writeFileSync(`${output}/repositories-browser-result.json`, JSON.stringify(results, null, 2)); console.log(results);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
