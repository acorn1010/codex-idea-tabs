/** Check readable thinking details and live updates in headless browser panes without starting IDEA. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright';
mkdirSync(output, { recursive: true });
const server = createServer((request, response) => {
  const asset = ({ '/': 'index.html', '/app.js': 'app.js', '/index.css': 'index.css' })[request.url];
  if (!asset) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html');
  response.end(readFileSync(new URL(`../../web/dist/${asset}`, import.meta.url)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CODEX_SMOKE_CHROME || undefined, headless: true });
try {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    const snapshot = { connection: 'connected', error: '', project: 'Codex Tabs', cwd: '/project', distro: 'Ubuntu', settings: { model: '', effort: '', permissions: 'auto' }, models: [], account: { requiresOpenaiAuth: false }, sessions: [], chat: { id: 'thinking', threadId: 'thinking', title: 'Readable thinking', cwd: '/project', draft: '', items: [
      { id: 'user', type: 'userMessage', content: [{ type: 'text', text: 'Check why thinking tasks appear idle.' }] },
      { id: 'summary', type: 'reasoning', summary: ['**Trace the state updates.**', 'Check whether the resume reply arrives after live activity.'] },
      { id: 'update', type: 'agentMessage', text: 'I found the state update path.' },
      { id: 'content', type: 'reasoning', summary: [], content: ['Compare the active turn IDs.', 'Keep older events from replacing the current turn.'] },
      { id: 'update-2', type: 'agentMessage', text: 'The regression now passes.' },
      { id: 'empty-mixed', type: 'reasoning', summary: [], content: [] },
      { id: 'command', type: 'commandExecution', command: 'npm test', status: 'completed', aggregatedOutput: 'Tests passed.' },
      { id: 'update-3', type: 'agentMessage', text: 'Checking the next event.' },
      { id: 'rs_internal_id', type: 'reasoning', summary: [], content: [] },
    ], requests: [], status: 'working', working: true, unread: false, pinned: false, revision: 1 } };
    window.__thinkingFixture = snapshot;
    window.__codexSend = message => {
      const { id, method } = JSON.parse(message);
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result: method === 'ready' ? structuredClone(snapshot) : {} } })));
    };
  });
  const results = [];
  for (const width of [360, 520, 900]) {
    await page.setViewportSize({ width, height: 960 });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const summary = page.locator('[data-activity-group="summary"]');
    await summary.locator(':scope > button').click();
    assert.equal(await summary.locator('strong').innerText(), 'Trace the state updates.');
    assert.ok((await summary.innerText()).includes('Check whether the resume reply arrives after live activity.'));
    assert.equal(await summary.locator('pre').count(), 0, 'Thinking uses readable text instead of a JSON code block');
    const content = page.locator('[data-activity-group="content"]');
    await content.locator(':scope > button').click();
    assert.ok((await content.innerText()).includes('Compare the active turn IDs.'));
    assert.equal(await content.locator('p').count(), 2, 'Reasoning content parts stay separate paragraphs');
    const empty = page.locator('[data-activity-group="rs_internal_id"]');
    assert.equal(await empty.locator('button').count(), 0, 'Empty reasoning must not offer an expansion that has no content');
    assert.equal((await empty.innerText()).trim(), 'Thinking');
    const mixed = page.locator('[data-activity-group="empty-mixed"]');
    await mixed.locator(':scope > button').click();
    assert.equal(await mixed.getByRole('button', { name: 'Thinking', exact: true }).count(), 0, 'Mixed groups omit empty thinking details');
    await mixed.getByRole('button', { name: 'npm test', exact: true }).click();
    await mixed.getByText('Tests passed.', { exact: true }).waitFor();
    assert.ok(!(await empty.innerText()).includes('rs_internal_id'));
    assert.ok(!(await empty.innerText()).includes('"summary"'));
    await page.screenshot({ path: `${output}/thinking-empty-${width}.png` });
    await page.evaluate(() => {
      const snapshot = window.__thinkingFixture;
      snapshot.chat.items.at(-1).text = '**Check the completion event.**\n\nNew reasoning arrives while this view is open.';
      snapshot.chat.revision++;
      window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(snapshot) }));
    });
    await empty.locator(':scope > button').waitFor();
    assert.equal(await empty.locator(':scope > button').getAttribute('aria-expanded'), 'false');
    await empty.locator(':scope > button').click();
    await empty.getByText('Check the completion event.', { exact: true }).waitFor();
    assert.equal(await empty.locator(':scope > button').getAttribute('aria-expanded'), 'true');
    assert.equal(await empty.getByText('No thinking text was provided.', { exact: true }).count(), 0);
    assert.equal(await empty.locator('strong').innerText(), 'Check the completion event.');
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.screenshot({ path: `${output}/thinking-streaming-${width}.png` });
    await empty.locator(':scope > button').focus();
    await page.keyboard.press('Space');
    assert.equal(await empty.locator(':scope > button').getAttribute('aria-expanded'), 'false');
    results.push({ width, summary: true, content: true, noMetadataDump: true, emptyRowsDoNotExpand: true, mixedGroupsOmitEmptyDetails: true, liveUpdates: true, keyboard: true, noOverflow: true });
  }
  assert.deepEqual(errors, []);
  writeFileSync(`${output}/reasoning-result.json`, JSON.stringify(results, null, 2));
  console.log(results);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
