/** Exercise Claude background status without launching tools or using an account. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright'; mkdirSync(output, { recursive: true });
const server = createServer((request, response) => {
  const asset = ({ '/': 'index.html', '/app.js': 'app.js', '/index.css': 'index.css' })[request.url];
  if (!asset) { response.writeHead(404).end(); return; }
  response.setHeader('Content-Type', asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html');
  response.end(readFileSync(new URL(`../../web/dist/${asset}`, import.meta.url)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CODEX_SMOKE_CHROME || undefined, headless: true });
try {
  for (const width of [360, 900]) {
    for (const theme of ['dark', 'light']) {
      const page = await browser.newPage({ viewport: { width, height: 700 } });
      const errors = []; page.on('pageerror', error => errors.push(error.message));
      await page.addInitScript(theme => {
        const chat = { id: 'chat', provider: 'claude', threadId: 'thread', title: 'Build checks', cwd: '/repo', draft: '', items: [{ id: 'reply', type: 'agentMessage', text: 'The images are building. I’ll check the results when the tasks finish.' }], requests: [], status: 'working', working: false, unread: false, pinned: false, revision: 1,
          claudeActivity: { tasks: [{ id: 'build', description: 'Build the JSON and raw images' }, { id: 'test', description: 'Run integration tests with value checks' }], wakeup: { scheduledFor: Date.now() + 30 * 60 * 1000, reason: 'Check back while the validations run.' } } };
        const snapshot = { connection: 'connected', error: '', project: 'Project', cwd: '/repo', settings: { permissions: 'ask' }, models: [], account: {}, sessions: [chat], chat };
        window.__setChat = patch => { Object.assign(chat, patch); chat.revision++; window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(snapshot) })); };
        window.__codexSend = raw => {
          const { id, method } = JSON.parse(raw);
          queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result: method === 'ready' ? snapshot : method === 'context' ? { files: [], selection: '' } : { data: [] } } })));
        };
        window.addEventListener('DOMContentLoaded', () => { document.documentElement.dataset.theme = theme; });
      }, theme);
      await page.goto(`http://127.0.0.1:${server.address().port}`);
      const activity = page.getByRole('region', { name: 'Claude background activity', exact: true });
      await activity.getByText('2 background tasks running', { exact: true }).waitFor();
      await activity.locator('summary').click();
      await activity.getByText('Run integration tests with value checks', { exact: true }).waitFor();
      assert.equal(await page.getByRole('button', { name: 'Stop Claude', exact: true }).count(), 0, 'Background work must not claim Stop can interrupt a foreground reply');
      const field = page.getByRole('textbox', { name: 'Message Claude', exact: true });
      await field.fill('A follow-up while these run');
      assert.equal(await page.getByRole('button', { name: 'Send message', exact: true }).isEnabled(), true);
      assert.equal(await activity.evaluate(el => el.scrollWidth <= el.clientWidth), true, 'Activity must fit narrow panes');
      const box = await activity.boundingBox(); const input = await field.boundingBox();
      assert.ok(box.y + box.height <= input.y, 'Activity stays above the composer');
      await page.screenshot({ path: `${output}/claude-background-${theme}-${width}.png` });
      await page.evaluate(() => window.__setChat({ claudeActivity: { tasks: [], wakeup: { scheduledFor: Date.now() + 100 } } }));
      await activity.getByText(/^Waiting for wakeup/).waitFor();
      await page.evaluate(() => window.__setChat({ claudeActivity: { tasks: [], wakeup: {} }, working: true }));
      await activity.waitFor({ state: 'detached' });
      await page.getByRole('button', { name: 'Stop Claude', exact: true }).waitFor();
      assert.equal(await field.inputValue(), 'A follow-up while these run', 'Task changes must preserve a draft');
      await page.evaluate(() => window.__setChat({ claudeActivity: {}, working: false }));
      await page.locator('header').getByText('Ready', { exact: true }).waitFor();
      assert.deepEqual(errors, []);
      await page.close();
    }
  }
  console.log('Claude activity UI passed at 360px and 900px in dark and light themes, including scheduled wakeup, resumed reply, task details, draft, and send state.');
} finally { await browser.close(); server.close(); }
