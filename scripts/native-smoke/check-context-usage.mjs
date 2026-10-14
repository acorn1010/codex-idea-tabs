/** Check the composer context ring through live chat snapshots in the bundled UI. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { mkdirSync, readFileSync } from 'node:fs';
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
  page.setDefaultTimeout(10_000);
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: '/project',
      settings: { model: 'glm-flash-uncensored', effort: '', modelSelectionSaved: true, permissions: 'ask', permissionRevision: 0 },
      models: [
        { id: 'glm-flash-uncensored', model: 'glm-flash-uncensored', displayName: 'GLM 5.3 Flash (local)', isLocal: true, supportedReasoningEfforts: [] },
        { id: 'cloud', model: 'cloud', displayName: 'GPT-6-Astra', defaultReasoningEffort: 'high', supportedReasoningEfforts: [{ reasoningEffort: 'high', description: 'Think deeply' }] },
      ], account: { requiresOpenaiAuth: false }, sessions: [],
      chat: { id: 'context-ring', provider: 'codex', threadId: 'fixture', title: 'Context ring', cwd: '/project', draft: '', items: [], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1,
        tokenUsage: { total: { totalTokens: 1736000 }, last: { totalTokens: 23347 }, modelContextWindow: 93388 } },
    };
    window.__updateUsage = usage => {
      snapshot.chat.tokenUsage = usage;
      snapshot.chat.revision += 1;
      window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(snapshot) }));
    };
    window.__codexSend = raw => {
      const { id, method } = JSON.parse(raw);
      const result = method === 'ready' ? snapshot : method === 'skills' ? { data: [] } : {};
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result: structuredClone(result) } })));
    };
  });
  for (const theme of ['dark', 'light']) {
    for (const width of [320, 360, 900]) {
      await page.setViewportSize({ width, height: 720 });
      await page.goto(`http://127.0.0.1:${server.address().port}/`);
      await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, theme);
      const ring = page.getByRole('progressbar', { name: 'Context usage', exact: true });
      await ring.waitFor();
      assert.equal(await ring.getAttribute('aria-valuenow'), '25');
      assert.equal(await ring.getAttribute('title'), 'Context usage: 23,347 / 93,388 tokens (25% used)');
      await ring.hover();
      await ring.focus();
      assert.equal(await ring.evaluate(element => element === document.activeElement), true);
      await ring.evaluate(element => element.blur());
      const arc = ring.locator('circle').nth(1);
      assert.equal(await arc.getAttribute('pathLength'), '100');
      assert.equal(await arc.getAttribute('stroke-dasharray'), '25 100');
      const model = page.getByRole('button', { name: 'Model: GLM 5.3 Flash (local)', exact: true });
      const modelRect = await model.boundingBox();
      const ringRect = await ring.boundingBox();
      assert.ok(ringRect.x >= modelRect.x + modelRect.width, 'The ring must sit immediately to the right of the model');
      assert.ok(ringRect.x + ringRect.width <= width, 'The ring must fit in a narrow tab');
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'The composer must not cause horizontal scrolling');
      await page.screenshot({ path: `${output}/context-ring-${theme}-${width}.png` });
      for (const used of [0, 84049, 93388, 120000, 9000]) {
        await page.evaluate(used => window.__updateUsage({ total: { totalTokens: 1736000 }, last: { totalTokens: used }, modelContextWindow: 93388 }), used);
        const expected = Math.min(100, used / 93388 * 100);
        await page.waitForFunction(expected => Number(document.querySelector('[role="progressbar"][aria-label="Context usage"]').getAttribute('aria-valuenow')) === expected, expected);
        assert.ok((await ring.getAttribute('title')).includes(new Intl.NumberFormat('en').format(used)), 'Hover text must retain the exact usage');
        assert.equal(await ring.locator('circle').count(), used > 0 ? 2 : 1);
      }
      assert.equal(await ring.getAttribute('title'), 'Context usage: 9,000 / 93,388 tokens (9.6% used)', 'Compaction must lower the ring even with a large lifetime token total');
      await model.click();
      await page.getByRole('option', { name: 'GPT-6-Astra', exact: true }).click();
      await page.evaluate(() => window.__updateUsage({ last: { totalTokens: 30000 }, modelContextWindow: 200000 }));
      await page.waitForFunction(() => document.querySelector('[role="progressbar"][aria-label="Context usage"]').getAttribute('aria-valuenow') === '15');
      assert.equal(await ring.getAttribute('title'), 'Context usage: 30,000 / 200,000 tokens (15% used)');
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Cloud effort controls must still fit beside the ring');
      for (const usage of [undefined, { last: { totalTokens: 9000 } }, { last: { totalTokens: -1 }, modelContextWindow: 93388 }, { last: { totalTokens: NaN }, modelContextWindow: 93388 }]) {
        await page.evaluate(usage => window.__updateUsage(usage), usage);
        await page.waitForFunction(() => !document.querySelector('[role="progressbar"][aria-label="Context usage"]').hasAttribute('aria-valuenow'));
        assert.equal(await ring.locator('circle').count(), 1, 'Unknown usage must not imply a zero-percent reading');
      }
    }
  }
  assert.deepEqual(errors, []);
  console.log('Context ring passed: exact tooltip, SVG fill, live updates and compaction, cloud/local models, unknown usage, keyboard focus, and 320/360/900 layouts in both themes.');
} finally { await browser.close(); server.close(); }
