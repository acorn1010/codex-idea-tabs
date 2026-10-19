/** Check response speed above the composer with streamed chat snapshots and keyboard access. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { mkdirSync, readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

mkdirSync('output/playwright', { recursive: true });
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
    const snapshot = { providers: [{ id: 'codex', name: 'Codex' }, { id: 'claude', name: 'Claude' }, { id: 'api:routeway', name: 'Routeway' }], connection: 'connected', error: '', project: 'Project', cwd: '/project',
      settings: { model: 'local', effort: '', modelSelectionSaved: true, permissions: 'ask', permissionRevision: 0 },
      models: [{ id: 'local', model: 'local', displayName: 'Local model', isLocal: true, supportedReasoningEfforts: [] }],
      account: { requiresOpenaiAuth: false }, sessions: [],
      chat: { id: 'speed', provider: 'codex', threadId: 'fixture', title: 'Generation speed', cwd: '/project', draft: '', items: [], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1 },
    };
    window.__updateSpeedChat = patch => {
      Object.assign(snapshot.chat, patch);
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
      for (const provider of ['codex', 'claude', 'routeway']) {
        await page.setViewportSize({ width, height: 720 });
        await page.goto(`http://127.0.0.1:${server.address().port}/`);
        await page.getByRole('textbox', { name: 'Message Codex', exact: true }).waitFor();
        await page.evaluate(({ theme, provider }) => {
          document.documentElement.dataset.theme = theme;
          window.__updateSpeedChat({ provider: provider === 'claude' ? 'claude' : 'codex', apiConnectionId: provider === 'routeway' ? 'routeway' : '' });
        }, { theme, provider });
        const composer = page.getByRole('textbox', { name: `Message ${provider === 'routeway' ? 'Routeway' : provider === 'claude' ? 'Claude' : 'Codex'}`, exact: true });
        await composer.waitFor();
        const summary = page.locator('button[aria-label^="Generation speed:"]');
        assert.equal(await summary.count(), 0, 'Unreported speed must not show a made-up rate');
        await page.evaluate(() => window.__updateSpeedChat({ generationSpeed: { tokensPerSecond: 42, outputTokens: 840, durationMs: 20000 } }));
        await summary.waitFor();
        assert.equal(await summary.innerText(), 'Last response\n~42 tok/s');
        const box = await summary.boundingBox();
        const input = await composer.boundingBox();
        assert.ok(box.y + box.height <= input.y, 'Speed must appear above the message box');
        assert.ok(box.x >= 0 && box.x + box.width <= width, 'Speed must fit in narrow tabs');
        await page.evaluate(() => window.__updateSpeedChat({ working: true, status: 'working' }));
        const working = page.getByText(`${provider === 'routeway' ? 'Routeway' : provider === 'claude' ? 'Claude' : 'Codex'} is working`, { exact: true });
        await working.waitFor();
        const statusBox = await working.boundingBox();
        const speedBox = await summary.boundingBox();
        assert.ok(Math.abs(statusBox.y + statusBox.height / 2 - speedBox.y - speedBox.height / 2) < 3, 'Working status and speed must share one row');
        assert.ok(statusBox.x + statusBox.width <= speedBox.x, 'Status and speed must not overlap');
        await page.screenshot({ path: `output/playwright/generation-speed-${provider}-${theme}-${width}.png` });
        await summary.focus();
        await page.keyboard.press('Enter');
        assert.equal(await summary.getAttribute('aria-expanded'), 'true');
        const details = page.locator(`[id="${await summary.getAttribute('aria-controls')}"]`);
        await page.getByText('840 output tokens in 20 s', { exact: true }).waitFor();
        assert.ok((await details.innerText()).includes('Network delay can affect this value.'));
        assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Expanded speed details must not cause horizontal scrolling');
        if (width === 320) { await page.screenshot({ path: `output/playwright/generation-speed-expanded-${provider}-${theme}.png` }); }
        await page.keyboard.press('Enter');
        assert.equal(await summary.getAttribute('aria-expanded'), 'false');
        await details.waitFor({ state: 'detached' });
        await page.evaluate(() => window.__updateSpeedChat({ working: true, status: 'working', generationSpeed: undefined }));
        await summary.waitFor({ state: 'detached' });
        await page.evaluate(() => window.__updateSpeedChat({ generationSpeed: { tokensPerSecond: 12.54, outputTokens: 1254, durationMs: 100000 } }));
        await summary.waitFor();
        assert.ok((await summary.innerText()).includes('~12.5 tok/s'), 'New measurements must replace the old rate');
        for (const generationSpeed of [{ tokensPerSecond: 0, outputTokens: 2, durationMs: 500 }, { tokensPerSecond: NaN, outputTokens: 2, durationMs: 500 }, { tokensPerSecond: 10, outputTokens: 2 }, undefined]) {
          await page.evaluate(generationSpeed => window.__updateSpeedChat({ generationSpeed }), generationSpeed);
          await summary.waitFor({ state: 'detached' });
        }
      }
    }
  }
  assert.deepEqual(errors, []);
  console.log('Generation speed passed: shared working-status row, real counts, keyboard details, new-turn reset, updated samples, invalid values, Codex/Claude/Routeway, and 320/360/900 layouts in both themes.');
} finally { await browser.close(); server.close(); }
