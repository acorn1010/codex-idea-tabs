/** Check compact activity groups, streaming updates, and bounded expansion in real browser panes. */
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
  const image = 'data:image/svg+xml;base64,' + Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="180" height="40"><rect width="180" height="40" fill="#293745"/></svg>').toString('base64');
  await page.addInitScript(({ image }) => {
    const completed = Array.from({ length: 12 }, (_, i) => ({ id: `command-${i}`, type: 'commandExecution', command: `npm run check -- workspace-${i}`, status: 'completed', exitCode: 0, aggregatedOutput: `Check ${i} passed` }));
    const snapshot = { connection: 'connected', error: '', project: 'Codex Tabs', cwd: '/project', distro: 'Ubuntu', settings: { model: '', effort: '', permissions: 'auto' }, models: [], account: { requiresOpenaiAuth: false }, sessions: [], chat: { id: 'groups', threadId: 'groups', title: 'Grouped commands', cwd: '/project', draft: '', items: [
      { id: 'user', type: 'userMessage', content: [{ type: 'text', text: 'Check the project and compare the results.' }] },
      { id: 'reasoning', type: 'reasoning', summary: ['Check each workspace.'] },
      ...completed,
      { id: 'files', type: 'fileChange', status: 'completed', changes: [{ path: 'src/preview.ts' }] },
      { id: 'search', type: 'webSearch', status: 'completed' },
      { id: 'update', type: 'agentMessage', text: 'The workspace checks passed. I am running the final build and tests.' },
      { id: 'live-a', type: 'commandExecution', command: 'npm run build', status: 'inProgress', aggregatedOutput: Array.from({ length: 120 }, (_, i) => `Build step ${i}`).join('\n') },
      { id: 'live-b', type: 'commandExecution', command: 'npm test', status: 'inProgress', aggregatedOutput: 'Running tests' },
      { id: 'image', type: 'imageGeneration', savedPath: image, status: 'completed' },
      { id: 'failed', type: 'commandExecution', command: 'npm run lint', status: 'completed', exitCode: 1, aggregatedOutput: 'Lint failed: missing import' },
      { id: 'after', type: 'agentMessage', text: 'The lint check needs a fix.' },
    ], requests: [], status: 'working', working: true, unread: false, pinned: false, revision: 1 } };
    window.__groupFixture = snapshot;
    window.__codexSend = message => {
      const { id, method } = JSON.parse(message);
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result: method === 'ready' ? structuredClone(snapshot) : {} } })));
    };
  }, { image });
  const results = [];
  for (const width of [360, 520, 900]) {
    await page.setViewportSize({ width, height: 920 });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.getByText('The lint check needs a fix.', { exact: true }).waitFor();
    const groups = page.locator('[data-activity-group]');
    assert.equal(await groups.count(), 3, 'Consecutive activity collapses into groups separated by messages and visible images');
    const first = groups.nth(0);
    const toggle = first.locator(':scope > button');
    assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
    assert.ok((await toggle.innerText()).includes('12 commands'));
    assert.ok((await first.boundingBox()).height <= 40, 'A completed batch uses a single compact row');
    assert.equal(await first.getByRole('region').count(), 0, 'Collapsed output is not mounted');
    assert.ok((await groups.nth(1).innerText()).includes('2 running'));
    assert.ok((await groups.nth(2).innerText()).includes('1 failed'), 'A nonzero exit code remains visible while collapsed');
    assert.equal(await page.getByAltText('Generated image').count(), 1, 'Generated images stay outside collapsed activity');
    await page.screenshot({ path: `${output}/commands-collapsed-${width}.png` });
    const failure = groups.nth(2);
    await failure.locator(':scope > button').click();
    await failure.getByText('Lint failed: missing import', { exact: true }).waitFor({ timeout: 1000 });
    await failure.locator(':scope > button').click();
    await toggle.scrollIntoViewIfNeeded();
    await page.mouse.move(0, 0);
    const appearance = () => toggle.evaluate(element => getComputedStyle(element).backgroundColor);
    const idle = await appearance();
    await toggle.hover();
    const hover = await appearance();
    await page.mouse.down();
    const active = await appearance();
    await page.mouse.up();
    assert.notEqual(idle, hover);
    assert.notEqual(hover, active);
    const details = first.getByRole('region', { name: 'Activity details' });
    await details.waitFor();
    assert.ok(await details.evaluate(element => element.scrollHeight > element.clientHeight && element.clientHeight <= 256), 'Expanded batches scroll within a bounded area');
    await first.getByRole('button', { name: 'npm run check -- workspace-0', exact: true }).click();
    await first.getByText('Check 0 passed', { exact: true }).waitFor();
    await details.evaluate(element => { element.scrollTop = element.scrollHeight; });
    await first.getByRole('button', { name: 'src/preview.ts', exact: true }).waitFor();
    await page.screenshot({ path: `${output}/commands-expanded-${width}.png` });
    await toggle.focus();
    await page.keyboard.press('Space');
    assert.equal(await toggle.getAttribute('aria-expanded'), 'false', 'Keyboard can collapse a batch');
    const live = groups.nth(1);
    await live.locator(':scope > button').click();
    await live.getByRole('button', { name: 'npm run build', exact: true }).click();
    const liveDetails = live.getByRole('region');
    await liveDetails.evaluate(element => { element.scrollTop = 80; });
    const before = await liveDetails.evaluate(element => element.scrollTop);
    await page.evaluate(() => {
      const state = window.__groupFixture;
      const index = state.chat.items.findIndex(item => item.id === 'image');
      state.chat.items.splice(index, 0, { id: 'live-c', type: 'commandExecution', command: 'npm run typecheck', status: 'inProgress' });
      state.chat.items.find(item => item.id === 'live-a').aggregatedOutput += '\nNew streamed output';
      state.chat.revision++;
      window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(state) }));
    });
    await live.getByText('3 running', { exact: true }).waitFor();
    assert.equal(await live.locator(':scope > button').getAttribute('aria-expanded'), 'true', 'Appending commands keeps the group expanded');
    assert.equal(await liveDetails.evaluate(element => element.scrollTop), before, 'Streaming does not reset the details scroll position');
    assert.ok((await live.locator('pre').innerText()).includes('New streamed output'));
    await page.evaluate(() => {
      const state = window.__groupFixture;
      for (const item of state.chat.items) {
        if (item.status === 'inProgress') { item.status = 'completed'; item.exitCode = 0; }
      }
      state.chat.revision++;
      window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(state) }));
    });
    await page.waitForFunction(() => !document.querySelectorAll('[data-activity-group]')[1].textContent.includes('running'));
    assert.equal(await live.locator(':scope > button').getAttribute('aria-expanded'), 'true');
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'No horizontal page overflow');
    results.push({ width, grouped: true, boundedExpansion: true, keyboard: true, hoverAndActive: true, streaming: true, visibleFailures: true, visibleImages: true });
  }
  writeFileSync(`${output}/command-groups-result.json`, JSON.stringify(results, null, 2));
  console.log(results);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
