/** Check scoped approvals using choices emitted by the Java conversation tests. No real approval is granted. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const fixtures = JSON.parse(readFileSync(new URL('../../core/build/approval-fixtures.json', import.meta.url)));
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
  await page.addInitScript(fixtures => {
    const chat = { id: 'approval-test', threadId: 'approval-test', title: 'Approval test', cwd: '/project', draft: 'Keep this draft.', items: [], requests: [], status: 'attention', working: true, unread: false, pinned: false, revision: 1 };
    const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: '/project', distro: '', settings: { model: '', effort: '', permissions: 'ask' }, models: [], account: {}, sessions: [], chat };
    window.__requests = []; window.__failAnswer = false;
    window.__showApproval = (index, override = {}) => {
      chat.requests = [{ ...fixtures[index], reason: `Approval fixture ${index}`, ...override, key: String(++chat.revision) }];
      window.dispatchEvent(new CustomEvent('codex-state', { detail: snapshot }));
    };
    window.__codexSend = message => {
      const { id, method, params } = JSON.parse(message); window.__requests.push({ method, params });
      const error = method === 'answer' && window.__failAnswer ? 'Test connection interrupted' : undefined;
      const result = method === 'ready' ? snapshot : {};
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result, error } })));
    };
  }, fixtures);
  const results = [];
  for (const width of [360, 520, 900]) {
    await page.setViewportSize({ width, height: 850 }); await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.getByRole('textbox', { name: 'Message Codex', exact: true }).waitFor();
    for (const [index, label] of [[0, 'Always allow'], [1, 'Allow for session'], [2, 'Allow for session']]) {
      await page.evaluate(index => window.__showApproval(index), index);
      const card = page.getByRole('region', { name: 'Approval needed' });
      await card.getByText(`Approval fixture ${index}`, { exact: true }).waitFor();
      await card.getByRole('button', { name: label, exact: true }).waitFor();
      const buttonBounds = await card.getByRole('button', { name: label, exact: true }).boundingBox();
      const cardBounds = await card.boundingBox();
      assert.ok(buttonBounds.y >= cardBounds.y && buttonBounds.y + buttonBounds.height <= cardBounds.y + cardBounds.height, 'Approval actions must be visible without scrolling');
      if (index !== 0) { assert.equal(await card.getByRole('button', { name: 'Always allow', exact: true }).count(), 0); }
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      await page.screenshot({ path: `${output}/approvals-${index}-${width}.png` });
      await card.getByRole('button', { name: label, exact: true }).click();
      const answer = await page.evaluate(() => window.__requests.filter(request => request.method === 'answer').at(-1));
      assert.deepEqual(answer.params.decision, fixtures[index].approvalChoices.find(choice => choice.label === label).decision);
    }
    // Once and decline stay separate from session or persistent approval.
    for (const label of ['Allow once', 'Decline']) {
      await page.evaluate(() => window.__showApproval(0));
      await page.getByRole('button', { name: label, exact: true }).click();
      const answer = await page.evaluate(() => window.__requests.filter(request => request.method === 'answer').at(-1));
      assert.equal(answer.params.decision, label === 'Allow once' ? 'accept' : 'decline');
    }
    // Retry a failed answer without losing the approval or draft.
    await page.evaluate(() => { window.__failAnswer = true; window.__showApproval(0); });
    await page.getByRole('button', { name: 'Always allow', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: 'Test connection interrupted' }).waitFor();
    assert.ok(await page.getByRole('button', { name: 'Always allow', exact: true }).isEnabled());
    await page.evaluate(() => { window.__failAnswer = false; });
    await page.getByRole('button', { name: 'Always allow', exact: true }).click();
    assert.equal(await page.getByRole('textbox', { name: 'Message Codex', exact: true }).inputValue(), 'Keep this draft.');
    // Explicitly restricted choices and MCP forms must never gain an Always allow button.
    await page.evaluate(() => window.__showApproval(0, { approvalChoices: [{ label: 'Decline', decision: 'decline', description: '' }] }));
    await page.getByRole('button', { name: 'Always allow', exact: true }).waitFor({ state: 'detached' });
    await page.evaluate(() => window.__showApproval(0, { method: 'mcpServer/elicitation/request', approvalChoices: undefined, requestedSchema: { properties: {} } }));
    await page.getByRole('button', { name: 'Always allow', exact: true }).waitFor({ state: 'detached' });
    results.push({ width, persistentCommand: true, fileSession: true, permissionSession: true, onceAndDecline: true, retry: true, draftKept: true, noOverflow: true });
  }
  writeFileSync(`${output}/approvals-result.json`, JSON.stringify(results, null, 2)); console.log(results);
} finally { await browser.close(); server.close(); }
