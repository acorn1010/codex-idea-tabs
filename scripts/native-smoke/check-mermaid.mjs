/** Exercise Mermaid in the inline bundle and strict CSP used by JCEF, without either model CLI. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { createServer } from 'node:http';
import { readFileSync, mkdirSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright'; mkdirSync(output, { recursive: true });
const source = `flowchart LR
    P[Postgres logical CDC] --> K[Kafka]
    K --> B[Append-only Delta change log]
    B --> A[Databricks AUTO CDC]
    A --> T[Current-state tables]`;
const script = readFileSync(new URL('../../web/dist/app.js', import.meta.url), 'utf8').replaceAll('</script', '<\\/script');
const css = readFileSync(new URL('../../web/dist/index.css', import.meta.url), 'utf8');
const policy = "default-src 'none'; script-src 'nonce-mermaid-test'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'";
const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="${policy}"><style>${css}</style></head><body><div id="root"></div><script type="module" nonce="mermaid-test">${script}</script></body></html>`;
const server = createServer((_request, response) => { response.setHeader('Content-Type', 'text/html'); response.end(html); });
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CODEX_SMOKE_CHROME || undefined, headless: true });
try {
  const page = await browser.newPage();
  const errors = []; const requests = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (!request.isNavigationRequest() && !request.url().startsWith('data:')) { requests.push(request.url()); } });
  await page.addInitScript(({ source }) => {
    window.__copies = []; window.__violations = [];
    document.addEventListener('securitypolicyviolation', event => window.__violations.push(event.violatedDirective));
    const chat = { id: 'diagram', threadId: 'diagram', title: 'Diagram', cwd: '/project', draft: '', items: [], requests: [], status: 'idle', working: false, unread: false, pinned: false, revision: 1 };
    const snapshot = { connection: 'connected', error: '', theme: 'dark', project: 'Project', cwd: '/project', settings: { model: '', effort: '', permissions: 'auto' }, models: [], account: {}, sessions: [], chat };
    window.__show = (text, theme = 'dark', provider = 'codex', both = false) => {
      snapshot.theme = theme; chat.provider = provider; chat.revision++;
      chat.items = both ? [{ id: 'user', type: 'userMessage', text: source }, { id: 'answer', type: 'agentMessage', text }] : [{ id: 'answer', type: 'agentMessage', text }];
      window.dispatchEvent(new CustomEvent('codex-state', { detail: structuredClone(snapshot) }));
    };
    window.__codexSend = raw => {
      const { id, method, params } = JSON.parse(raw);
      if (method === 'copy') { window.__copies.push(params.text); }
      const result = method === 'ready' ? snapshot : method === 'workspaces' ? { entries: [], repositories: [], branches: [], current: '/project', repository: '', base: '', suggestedName: '', error: '' } : {};
      queueMicrotask(() => window.dispatchEvent(new CustomEvent('codex-reply', { detail: { id, result: structuredClone(result) } })));
    };
  }, { source });
  const answer = page.locator('[data-message-id="answer"]');
  const image = answer.locator('[data-mermaid] img');
  const show = (text, theme, provider, both) => page.evaluate(args => window.__show(...args), [text, theme, provider, both]);
  const waitImage = async () => { await image.waitFor(); await image.evaluate(img => img.decode()); };
  const svg = () => image.getAttribute('src').then(url => decodeURIComponent(url.split(',').slice(1).join(',')));
  for (const width of [360, 900]) {
    await page.setViewportSize({ width, height: 760 });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.getByRole('textbox', { name: 'Message Codex', exact: true }).waitFor();
    for (const theme of ['dark', 'light']) {
      await show(`The pipeline:\n\n\`\`\`mermaid\n${source}\n\`\`\`\n\nEach stage has one job.`, theme, theme === 'light' ? 'claude' : 'codex', true);
      await waitImage();
      await page.locator('[data-message-id="user"] [data-mermaid] img').waitFor();
      await page.locator('[data-message-id="user"] [data-mermaid] img').evaluate(img => img.decode());
      assert.equal(await page.locator('[data-mermaid]').count(), 2, 'Render fenced replies and unfenced pasted user diagrams');
      const rendered = await svg();
      assert.ok(!rendered.includes('<foreignObject'), 'Labels stay in SVG so image previews can display them');
      for (const label of ['Postgres', 'Kafka', 'Delta', 'Databricks', 'Current-state']) { assert.ok(rendered.includes(label)); }
      assert.ok(rendered.includes(theme === 'light' ? '#e7effa' : '#303f53'), 'Follow the IDE theme');
      await answer.getByText('Each stage has one job.', { exact: true }).waitFor();
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      if (width === 360) {
        assert.ok(await answer.getByRole('region', { name: 'Diagram', exact: true }).evaluate(element => element.scrollWidth > element.clientWidth));
        assert.ok(await image.evaluate(element => element.getBoundingClientRect().width >= element.naturalWidth * 0.79), 'Narrow previews keep labels readable');
      }
      await page.screenshot({ path: `${output}/mermaid-${theme}-${width}.png` });
      await answer.getByRole('button', { name: 'Source', exact: true }).click();
      assert.equal(await answer.locator('pre').innerText(), source);
      await answer.getByRole('button', { name: 'Copy diagram source', exact: true }).click();
      await page.waitForFunction(expected => window.__copies.at(-1) === expected, source);
      await answer.getByRole('button', { name: 'Diagram', exact: true }).click(); await waitImage();
      const expand = answer.getByRole('button', { name: 'Expand diagram', exact: true });
      await expand.click();
      const dialog = page.getByRole('dialog', { name: 'Diagram preview' }); await dialog.waitFor();
      const region = dialog.getByRole('region', { name: 'Expanded diagram' });
      assert.ok(await region.evaluate(element => element.querySelector('img').getBoundingClientRect().width > 500));
      await page.screenshot({ path: `${output}/mermaid-expanded-${theme}-${width}.png` });
      await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'detached' });
      assert.equal(await expand.evaluate(element => element === document.activeElement), true);
    }
    console.log({ width, fencedAndPasted: true, darkAndLight: true, bothProviders: true, copyAndExpand: true });
  }
  await show(`\`\`\`text\n${source}\n\`\`\``);
  await answer.getByRole('button', { name: 'Copy block', exact: true }).click();
  await page.waitForFunction(expected => window.__copies.at(-1) === expected, `${source}\n`);
  assert.equal(await answer.locator('[data-mermaid]').count(), 0);
  await show('```mermaid\nflowchart LR\nA[\n```');
  await answer.getByText('Could not render this diagram. Showing source.', { exact: true }).waitFor();
  assert.ok(await answer.getByRole('button', { name: 'Expand diagram', exact: true }).isDisabled());
  assert.equal(await answer.locator('pre').innerText(), 'flowchart LR\nA[');
  await show('```mermaid\nflowchart LR\nA[Old] --> B');
  await show('```mermaid\nflowchart LR\nA[Latest] --> B[Complete]\n```');
  await waitImage(); assert.ok((await svg()).includes('Latest')); assert.ok(!(await svg()).includes('Old'));
  // Switching themes on an existing diagram must not require changing its message.
  const darkUrl = await image.getAttribute('src');
  await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
  await page.waitForFunction(previous => document.querySelector('[data-mermaid] img')?.getAttribute('src') !== previous && !!document.querySelector('[data-mermaid] img'), darkUrl);
  await waitImage(); assert.ok((await svg()).includes('#e7effa'));
  await show('```mermaid\nsequenceDiagram\nAlice->>Bob: Hello\nBob-->>Alice: Ready\n```');
  await waitImage(); assert.ok((await svg()).includes('Alice'));
  await show('```mermaid\n%%{init: {"securityLevel":"loose","htmlLabels":true,"flowchart":{"htmlLabels":true},"themeCSS":"body { display:none }"}}%%\nflowchart LR\nA["<img src=\'https://example.invalid/probe\' onerror=\'window.__injected=true\'>"] --> B[Safe]\nclick B "https://example.invalid/click"\n```');
  await waitImage();
  assert.ok(!(await svg()).includes('<foreignObject'));
  assert.equal(await page.evaluate(() => window.__injected), undefined);
  assert.equal(await page.evaluate(() => getComputedStyle(document.body).display), 'block');
  await show(`\`\`\`mermaid\nflowchart LR\nA[${'x'.repeat(20_001)}]\n\`\`\``);
  await answer.getByText('Could not render this diagram. Showing source.', { exact: true }).waitFor();
  await show(`\`\`\`mermaid\nflowchart LR\n${Array.from({ length: 201 }, (_, index) => `n${index} --> n${index + 1}`).join('\n')}\n\`\`\``);
  await answer.getByText('Could not render this diagram. Showing source.', { exact: true }).waitFor();
  await show(`\`\`\`mermaid\n${source}\n\`\`\``); await waitImage();
  assert.equal(await page.locator('body > div[aria-hidden="true"]').count(), 0, 'Rendering containers are cleaned up after successes and failures');
  assert.deepEqual(errors, []);
  assert.deepEqual(requests, [], 'No runtime assets or external diagram URLs are fetched');
  assert.deepEqual(await page.evaluate(() => window.__violations), [], 'Rendering works without relaxing the JCEF CSP');
  console.log({ incompleteAndStreaming: true, preservedCodeBlocks: true, otherMermaidDiagrams: true, isolatedSvg: true, boundedInput: true, offlineStrictCsp: true });
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
