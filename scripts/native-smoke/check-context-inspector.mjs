/** Exercise the context inspector against the bundled UI without sending model requests. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright';
mkdirSync(output, { recursive: true });
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
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.addInitScript(() => {
    const paragraph = 'Preserve the existing public behavior while making the requested change. Verify that the full output remains the same before accepting an optimization.';
    const snapshot = { connection: 'connected', error: '', project: 'Foony', cwd: '/project', distro: 'Ubuntu', settings: { model: 'gpt-6-astra', effort: 'xhigh', permissions: 'auto' }, models: [], account: { requiresOpenaiAuth: false }, sessions: [], chat: { id: 'context', threadId: 'context', title: 'Context inspection', cwd: '/project', draft: 'Keep this draft while I inspect the context.', items: [{ id: 'reply', type: 'agentMessage', text: 'The context inspector is available beside the connection settings.' }], requests: [], status: 'working', working: true, unread: false, pinned: false, revision: 1 } };
    const report = { kind: 'recorded', source: '/home/acorn/.codex/sessions/fixture.jsonl', capturedAt: Date.now(), compactions: 2, notices: ['Reconstructed from saved records. This is not an exact request capture.', 'Opaque content is omitted.'], blocks: [
      { id: 'rules', kind: 'instructions', role: 'developer', label: 'Project rules', text: '# Rules\n\n' + paragraph + '\n\nRead /project/AGENTS.md before changing code.', origin: 'Compaction replacement' },
      { id: 'skills', kind: 'skills', role: 'developer', label: 'Skill catalog', text: '### Available skills\n\n- Review at /project/.agents/skills/review/SKILL.md\n\n' + paragraph, origin: 'Recorded item' },
      { id: 'tool', kind: 'tools', role: 'tool', label: 'functions.exec result', call: 'cat /project/client/src/pages/ShopPage.tsx', text: '// UNIQUE_FILE_CONTENT\n' + Array.from({ length: 90 }, (_, index) => `const value${index} = ${index};`).join('\n'), origin: 'Recorded item · tool result' },
      { id: 'message', kind: 'messages', role: 'user', label: 'User message', text: 'Inspect the context to find duplicate instructions and unnecessary content.', origin: 'Recorded item' },
    ] };
    window.__requests = [];
    window.__codexSend = message => {
      const { id, method, params } = JSON.parse(message);
      window.__requests.push({ method, params });
      let result = method === 'ready' ? snapshot : method === 'inspectContext' ? report : {};
      let error;
      if (method === 'inspectStartup') {
        if (window.__startupFail) { error = 'Fixture startup could not be built. Try again.'; }
        else { result = { ...report, kind: 'startup', source: '/project', compactions: 0, model: params.model, effort: params.effort, notices: ['Fresh startup snapshot, not the current chat.'], blocks: report.blocks.slice(0, 2) }; }
      }
      if (method === 'inspectContext' && window.__manyBlocks) { result = { ...report, blocks: Array.from({ length: 500 }, (_, index) => ({ ...report.blocks[3], id: 'many-' + index, text: `Message ${index}` })) }; }
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result, error } })));
    };
  });
  const results = [];
  for (const width of [360, 520, 900]) {
    await page.setViewportSize({ width, height: 850 });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const trigger = page.getByRole('button', { name: 'Inspect context', exact: true });
    await trigger.waitFor();
    assert.ok(await trigger.evaluate(element => { const rect = element.getBoundingClientRect(); return rect.x >= 0 && rect.right <= innerWidth; }));
    assert.equal(await page.evaluate(() => window.__requests.filter(request => request.method.startsWith('inspect')).length), 0, 'Normal chats do not load inspection data');
    await trigger.click();
    const dialog = page.getByRole('dialog', { name: 'Context inspector', exact: true });
    const search = dialog.getByRole('textbox', { name: 'Search context' });
    await search.waitFor();
    assert.equal(await search.evaluate(element => document.activeElement === element), true);
    assert.equal(await search.evaluate(element => getComputedStyle(element).cursor), 'text');
    assert.equal(await search.evaluate(element => getComputedStyle(element).outlineStyle), 'none', 'Input focus uses the neutral inset rim');
    assert.ok(await dialog.locator('h1').evaluate(element => element.scrollWidth <= element.clientWidth), 'Inspector title is readable at narrow widths');
    assert.equal(await page.evaluate(() => window.__requests.filter(request => request.method === 'inspectContext').length), 1);
    assert.equal(await page.evaluate(() => window.__requests.filter(request => request.method === 'inspectStartup').length), 0);
    assert.ok(await dialog.locator('[title="Chat is working"]').isVisible());
    const blocks = dialog.locator('[aria-label="Context blocks"]');
    assert.ok((await blocks.getByRole('button').first().innerText()).includes('functions.exec result'), 'Largest blocks appear first');
    await page.screenshot({ path: `${output}/context-overview-${width}.png` });
    await search.fill('UNIQUE_FILE_CONTENT');
    assert.equal(await blocks.getByRole('button').count(), 1);
    await blocks.getByRole('button').first().click();
    const details = dialog.getByRole('region', { name: 'Context block details' });
    await details.locator('mark').waitFor();
    assert.equal(await details.locator('mark').innerText(), 'UNIQUE_FILE_CONTENT');
    await details.getByText('Paths mentioned (1)', { exact: true }).click();
    await details.getByRole('button', { name: '/project/client/src/pages/ShopPage.tsx', exact: true }).click();
    assert.ok(await page.evaluate(() => window.__requests.some(request => request.method === 'openLink' && request.params.path.endsWith('ShopPage.tsx'))));
    await page.screenshot({ path: `${output}/context-detail-${width}.png` });
    await search.fill('');
    await dialog.getByRole('button', { name: '1 repeated passage', exact: true }).click();
    assert.equal(await blocks.getByRole('button').count(), 2);
    await blocks.getByRole('button').first().click();
    assert.ok(await details.locator('mark').count() > 0, 'Repeated paragraphs are highlighted');
    await page.screenshot({ path: `${output}/context-duplicates-${width}.png` });
    await dialog.getByRole('button', { name: 'Copy context JSON', exact: true }).click();
    assert.ok(await page.evaluate(() => window.__requests.some(request => request.method === 'copy' && JSON.parse(request.params.text).kind === 'recorded')));
    await dialog.getByRole('button', { name: 'Open context as text', exact: true }).click();
    assert.ok(await page.evaluate(() => window.__requests.some(request => request.method === 'contextExport' && request.params.text.includes('not an exact request capture'))));
    await dialog.getByRole('button', { name: 'Startup', exact: true }).click();
    assert.equal(await page.evaluate(() => window.__requests.filter(request => request.method === 'inspectStartup').length), 0, 'Startup is an explicit action');
    await page.evaluate(() => { window.__startupFail = true; });
    await dialog.getByRole('button', { name: 'Build startup snapshot', exact: true }).click();
    await dialog.getByRole('alert').waitFor();
    await page.evaluate(() => { window.__startupFail = false; });
    await dialog.getByRole('button', { name: 'Retry', exact: true }).click();
    await search.waitFor();
    await dialog.getByText('Startup snapshot, not this chat’s live context', { exact: true }).waitFor();
    await page.keyboard.press('Escape');
    await dialog.waitFor({ state: 'detached' });
    assert.equal(await trigger.evaluate(element => document.activeElement === element), true, 'Escape returns to the inspector trigger');
    assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue(), 'Keep this draft while I inspect the context.');
    assert.equal(await page.evaluate(() => window.__requests.filter(request => ['send', 'stop', 'reconnect'].includes(request.method)).length), 0);
    results.push({ width, onDemand: true, largestFirst: true, search: true, repeatedParagraphs: true, fileLinks: true, export: true, startupRetry: true, focus: true, draftKept: true });
  }
  await page.evaluate(() => { window.__manyBlocks = true; });
  await page.getByRole('button', { name: 'Inspect context', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Context inspector', exact: true });
  await dialog.getByText('500 blocks', { exact: true }).waitFor();
  const list = dialog.locator('[aria-label="Context blocks"]');
  assert.equal(await list.getByRole('button').count(), 81, 'Large reports initially render 80 blocks and one pagination control');
  await list.getByRole('button', { name: 'Show more blocks', exact: true }).click();
  assert.equal(await list.getByRole('button').count(), 161);
  await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
  await page.screenshot({ path: `${output}/context-light-900.png` });
  assert.deepEqual(pageErrors, []);
  writeFileSync(`${output}/context-inspector-result.json`, JSON.stringify({ layouts: results, boundedRendering: true, pageErrors }, null, 2));
  console.log(results);
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
