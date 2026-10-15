/** Keep loop recovery distinct from connection recovery in the bundled composer. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
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
    const snapshot = { connection: 'connected', error: '', project: 'Fixture', cwd: '/fixture',
      settings: { model: 'glm-flash-uncensored', effort: '', modelSelectionSaved: true, permissions: 'ask', permissionRevision: 0 },
      models: [{ id: 'glm-flash-uncensored', model: 'glm-flash-uncensored', displayName: 'GLM 5.3 Flash (local)', isLocal: true, supportedReasoningEfforts: [] }],
      account: {}, sessions: [], chat: { id: 'guard', provider: 'codex', threadId: 'fixture', title: 'Loop guard', cwd: '/fixture', draft: '', items: [], requests: [], status: 'error', working: false, unread: false, pinned: false, revision: 1, errorKind: 'loopGuardStopped', error: 'Loop guard stopped this turn. Send a new instruction to try a different approach.' },
    };
    window.__setError = (kind, message, rootError = '') => {
      snapshot.chat.errorKind = kind;
      snapshot.chat.error = message;
      snapshot.error = rootError;
      snapshot.chat.revision += 1;
      window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(snapshot) }));
    };
    window.__codexSend = raw => {
      const { id, method } = JSON.parse(raw);
      const result = method === 'ready' ? snapshot : method === 'skills' ? { data: [] } : {};
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result: structuredClone(result) } })));
    };
  });
  await page.setViewportSize({ width: 360, height: 720 });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.getByText(/^Loop guard stopped this turn/).waitFor();
  assert.equal(await page.getByRole('button', { name: 'Reconnect', exact: true }).count(), 0, 'A stopped loop is not a connection failure');
  assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).isEnabled(), true);
  await page.evaluate(() => window.__setError('loopGuard', 'Loop guard: change your approach.'));
  await page.getByText('Loop guard: change your approach.', { exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: 'Reconnect', exact: true }).count(), 0);
  for (const [kind, message, rootError] of [ ['connection', 'Connection lost', ''], ['operation', 'Loop guard: could not interrupt the disconnected server', ''], ['loopGuardStopped', 'Loop guard stopped this turn', 'The app-server disconnected'] ]) {
    await page.evaluate(([kind, message, rootError]) => window.__setError(kind, message, rootError), [kind, message, rootError]);
    await page.getByRole('button', { name: 'Reconnect', exact: true }).waitFor();
  }
  assert.deepEqual(errors, []);
  console.log('Loop guard notice passed: resume guidance for loops, editable composer, and Reconnect retained for real connection failures.');
} finally { await browser.close(); server.close(); }
