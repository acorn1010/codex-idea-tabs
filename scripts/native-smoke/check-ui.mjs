/** Exercise the real IDEA webview bridge against the isolated fixture process. */
import { chromium } from '../../web/node_modules/playwright-core/index.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const output = 'output/playwright';
mkdirSync(output, { recursive: true });
const browser = await chromium.connectOverCDP(process.env.CODEX_SMOKE_CDP || 'http://127.0.0.1:9226');
try {
  const chats = [];
  for (const page of browser.contexts().flatMap(context => context.pages())) {
    if (await page.getByRole('textbox', { name: 'Message Codex', exact: true }).count()) { chats.push(page); }
  }
  assert.equal(chats.length, 4, 'Expected four visible native chat panes');
  const pages = await Promise.all(chats.map(async page => ({ page, text: await page.locator('body').innerText() })));
  const working = pages.find(value => value.text.includes('Codex is working'))?.page;
  assert.ok(working, 'Working fixture pane is visible');
  writeFileSync(`${output}/native-accessibility.txt`, await working.locator('body').ariaSnapshot());
  const stop = working.getByRole('button', { name: 'Stop Codex', exact: true });
  const size = await stop.boundingBox();
  assert.ok(size.width >= 30 && size.height >= 30, 'Stop is a full-size control');
  assert.ok(await stop.locator('span').evaluate(element => element.getBoundingClientRect().width) >= 10, 'Stop square is readable');
  assert.equal(await working.getByRole('button', { name: 'Send follow-up', exact: true }).count(), 0, 'No redundant disabled Send button');
  const permission = working.getByRole('button', { name: 'Permission mode: Approve for me', exact: true });
  const color = async () => permission.evaluate(element => getComputedStyle(element).backgroundColor);
  await working.mouse.move(1, 1);
  const idle = await color();
  await permission.hover();
  const hover = await color();
  await working.mouse.down();
  const pressed = await color();
  await working.mouse.up();
  assert.notEqual(idle, hover, 'Permission button has a hover state');
  assert.notEqual(hover, pressed, 'Permission button has a pressed state');
  await working.getByRole('listbox', { name: 'Permission mode', exact: true }).waitFor();
  await working.screenshot({ path: `${output}/permission-menu.png` });
  await working.getByRole('option', { name: 'Ask me', exact: false }).click();
  await working.getByRole('button', { name: 'Permission mode: Ask me', exact: true }).click();
  await working.getByRole('option', { name: 'Approve for me', exact: false }).click();
  for (const page of chats) {
    assert.equal(await page.getByRole('alert').count(), 0, 'No error banner');
    for (const image of await page.locator('img').all()) {
      assert.equal(await image.evaluate(element => element.complete && element.naturalWidth > 0), true, 'Image decoded');
    }
  }
  const target = pages.find(value => value.text.includes('Where should new conversations open?'))?.page || chats[1];
  const field = target.getByRole('textbox', { name: 'Message Codex', exact: true });
  const restored = await field.inputValue() === 'Restore this draft with its attachment';
  if (!await target.getByRole('button', { name: 'Remove pasted-text.txt', exact: true }).count()) {
    await field.evaluate(element => {
      const data = new DataTransfer(); data.setData('text/plain', 'Large attachment ✓\n'.repeat(6000));
      element.dispatchEvent(new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: data }));
    });
    await target.getByRole('button', { name: 'Remove pasted-text.txt', exact: true }).waitFor();
  }
  await field.evaluate(element => {
    const data = new DataTransfer(); data.items.add(new File(['drop test'], 'dropped-context.txt', { type: 'text/plain' }));
    element.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: data }));
  });
  await target.getByRole('button', { name: 'Remove dropped-context.txt', exact: true }).waitFor();
  await target.getByRole('button', { name: 'Remove dropped-context.txt', exact: true }).click();
  await field.fill('Restore this draft with its attachment');
  await target.screenshot({ path: `${output}/attachment-draft.png` });
  const result = { panes: chats.length, restoredDraft: restored, permissionMenu: true, hover: true, pressed: true, stop: true, images: true, largePaste: true, fileDrop: true };
  writeFileSync(`${output}/native-result.json`, JSON.stringify(result, null, 2));
  console.log(result);
} finally { await browser.close(); }
