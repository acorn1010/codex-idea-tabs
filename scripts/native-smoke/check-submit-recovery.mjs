/** Check pending sends, native errors, timeout recovery, and draft retention for both providers. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright'; mkdirSync(output, { recursive: true });
const server = createServer((request, response) => {
  const asset = ({ '/': 'index.html', '/app.js': 'app.js', '/index.css': 'index.css' })[request.url?.split('?')[0]];
  if (!asset) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html');
  response.end(readFileSync(new URL(`../../web/dist/${asset}`, import.meta.url)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CODEX_SMOKE_CHROME || undefined, headless: true });
try {
  for (const provider of ['claude', 'codex']) {
    const page = await browser.newPage(); const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.clock.install();
    await page.addInitScript(() => {
      const provider = new URLSearchParams(location.search).get('provider');
      const chat = { id: 'submit-check', provider, cwd: '/repo', draft: 'Keep this draft', draftAttachments: [{ name: 'notes.txt', path: '/repo/notes.txt', mime: 'text/plain', size: 10 }], items: [], requests: [], working: false, revision: 1 };
      const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: '/repo', settings: { permissions: 'ask' }, models: [], account: {}, sessions: [], chat };
      window.__sends = []; window.__failBridge = false;
      window.__codexSend = raw => {
        const { id, method, params } = JSON.parse(raw);
        const reply = (result = {}, error) => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result, error } }));
        if (method === 'send') {
          window.__sends.push(params);
          if (window.__failBridge) { throw new Error('IntelliJ could not receive this request.'); }
          window.__finishSend = error => reply({}, error);
          return;
        }
        queueMicrotask(() => reply(method === 'ready' ? snapshot : {}));
      };
    });
    for (const width of [360, 900]) {
      await page.setViewportSize({ width, height: 760 });
      await page.goto(`http://127.0.0.1:${server.address().port}/?provider=${provider}`);
      const field = page.getByRole('textbox', { name: `Message ${provider === 'claude' ? 'Claude' : 'Codex'}`, exact: true });
      const send = page.getByRole('button', { name: 'Send message', exact: true });
      const choice = page.getByRole('button', { name: /^Provider:/ });
      await field.waitFor(); await send.click();
      await page.getByRole('status').filter({ hasText: 'Sending message' }).waitFor();
      assert.ok(await send.isDisabled());
      assert.equal(await field.inputValue(), 'Keep this draft');
      await field.press('Enter');
      assert.equal(await page.evaluate(() => window.__sends.length), 1, 'Repeated Enter must not duplicate a pending send');
      await field.fill('Draft edited while sending');
      await page.screenshot({ path: `${output}/submit-pending-${provider}-${width}.png` });
      await page.evaluate(() => window.__finishSend('Claude did not finish preparing this message. Your message was not sent.'));
      await page.getByRole('alert').filter({ hasText: 'Your message was not sent' }).waitFor();
      assert.ok(await send.isEnabled()); assert.ok(await choice.isEnabled());
      assert.equal(await field.inputValue(), 'Draft edited while sending');
      await page.getByText('notes.txt', { exact: true }).waitFor();
      await send.click();
      await page.waitForFunction(() => window.__sends.length === 2);
      await page.clock.fastForward(120_001);
      await page.getByRole('alert').filter({ hasText: 'The chat did not confirm this message' }).waitFor();
      assert.ok(await send.isEnabled()); assert.ok(await choice.isEnabled());
      await field.fill('Draft after timeout');
      await page.evaluate(() => window.__finishSend());
      assert.equal(await field.inputValue(), 'Draft after timeout', 'A late reply must not erase an edited draft');
      assert.equal(await page.evaluate(() => window.__sends.length), 2, 'Timeout must not resend automatically');
      await page.evaluate(() => { window.__failBridge = true; }); await send.click();
      await page.getByRole('alert').filter({ hasText: 'IntelliJ could not receive' }).waitFor();
      assert.ok(await send.isEnabled());
      await page.evaluate(() => { window.__failBridge = false; });
      await send.click(); await page.waitForFunction(() => window.__sends.length === 4);
      assert.equal(await page.evaluate(() => window.__sends.at(-1).text), 'Draft after timeout');
      await page.evaluate(() => window.__finishSend());
      await page.waitForFunction(() => document.querySelector('textarea').value === '');
      assert.equal(await page.getByText('notes.txt', { exact: true }).count(), 0);
      assert.ok(await choice.isEnabled());
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      console.log({ provider, width, pendingVisible: true, failurePreservesDraftAndAttachments: true, timeoutUnlocksSubmit: true, noAutomaticRetry: true, lateReplyIgnored: true, explicitRetry: true });
    }
    assert.deepEqual(errors, []); await page.close();
  }
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
