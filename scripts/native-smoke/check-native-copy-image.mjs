/** Verify the real IDEA image context menu and Windows image clipboard, including full-size pixels. */
import { connectNative } from './native-bridge.mjs';
import { mkdirSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';

const { findChat, close } = await connectNative();
try {
  const { page } = await findChat(chat => chat.id.startsWith('lifecycle-preview-') && chat.draftAttachments?.length === 1);
  const thumbnail = page.getByRole('button', { name: 'Preview preview.svg', exact: true });
  const expected = await thumbnail.locator('img').evaluate(async image => {
    await image.decode();
    const canvas = document.createElement('canvas'); canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
    const context = canvas.getContext('2d'); context.drawImage(image, 0, 0);
    const pixel = context.getImageData(115, 140, 1, 1).data;
    return { width: canvas.width, height: canvas.height, red: pixel[0], green: pixel[1], blue: pixel[2] };
  });
  await thumbnail.click({ button: 'right' });
  const menu = page.getByRole('menu', { name: 'Image actions' });
  await menu.getByRole('menuitem', { name: 'Copy image', exact: true }).click();
  await menu.getByText('Image copied', { exact: true }).waitFor();
  mkdirSync('output/playwright', { recursive: true });
  if (process.platform !== 'win32') { throw new Error('This clipboard check runs on Windows alongside IDEA'); }
  const clipboard = JSON.parse(execFileSync('powershell.exe', ['-NoProfile', '-STA', '-Command', `
    Add-Type -AssemblyName System.Windows.Forms
    $copiedImage = [System.Windows.Forms.Clipboard]::GetImage()
    if ($null -eq $copiedImage) { throw 'Windows clipboard does not contain an image' }
    try {
      $pixel = $copiedImage.GetPixel(115, 140)
      $copiedImage.Save((Join-Path (Get-Location) 'output/playwright/native-copied-image.png'), [System.Drawing.Imaging.ImageFormat]::Png)
      @{width=$copiedImage.Width;height=$copiedImage.Height;red=$pixel.R;green=$pixel.G;blue=$pixel.B} | ConvertTo-Json -Compress
    } finally { $copiedImage.Dispose() }
  `], { encoding: 'utf8' }).trim());
  assert.deepEqual(clipboard, expected);
  assert.equal(clipboard.width, 640, 'A 20px thumbnail copies the original 640px image');
  await menu.waitFor({ state: 'detached' });
  await thumbnail.click();
  const dialog = page.getByRole('dialog', { name: 'preview.svg', exact: true });
  await dialog.locator('img').click({ button: 'right' });
  await menu.waitFor();
  await page.screenshot({ path: 'output/playwright/native-copy-image-menu.png' });
  await menu.getByRole('menuitem', { name: 'Copy image', exact: true }).click();
  await menu.getByText('Image copied', { exact: true }).waitFor();
  await menu.waitFor({ state: 'detached' });
  await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'detached' });
  const result = { nativeContextMenu: true, nativePreviewMenu: true, windowsClipboardImage: true, fullResolution: true, pixelMatch: true, clipboard };
  writeFileSync('output/playwright/native-copy-image-result.json', JSON.stringify(result, null, 2)); console.log(result);
} finally { await close(); }
