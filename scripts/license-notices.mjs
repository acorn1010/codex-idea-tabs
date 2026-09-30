/** Include each production dependency's license alongside the bundled UI. */
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const web = resolve(dirname(fileURLToPath(import.meta.url)), '../web');
const lock = JSON.parse(readFileSync(join(web, 'package-lock.json'), 'utf8'));
const notices = ['Third-party licenses for the Codex Tabs web UI.\n', 'Provider icons: OpenAI Blossom (https://openai.com/brand/) and the Claude mark from Anthropic’s Claude Code extension. These marks belong to OpenAI and Anthropic and identify the selected service.\n'];
for (const [path, entry] of Object.entries(lock.packages)) {
  if (!path || entry.dev) { continue; }
  const root = join(web, path);
  const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
  const files = readdirSync(root).filter(name => /^(license|licence|copying)(\.|$)/i.test(name));
  // These packages ship their complete MIT license in README.md instead of a LICENSE file.
  const readmeLicense = !files.length && ['fastdom', 'strictdom'].includes(pkg.name)
    ? readFileSync(join(root, 'README.md'), 'utf8').match(/^## License\r?\n([\s\S]*)/m)?.[1].trim()
    : undefined;
  if (!files.length && !readmeLicense) { throw new Error(`No license file found for ${pkg.name}`); }
  notices.push(`\n${pkg.name} ${pkg.version}\n${'='.repeat(60)}\n`);
  if (readmeLicense) { notices.push(readmeLicense); }
  for (const file of files) { notices.push(readFileSync(join(root, file), 'utf8')); }
}
writeFileSync(join(web, 'dist/THIRD_PARTY_LICENSES.txt'), notices.join('\n'));
