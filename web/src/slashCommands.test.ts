import { describe, expect, it } from 'vitest';
import { slashQuery } from './SlashCommands';

describe('slash command recognition', () => {
  it('accepts a single command token, including an empty query and trailing spaces', () => {
    expect(slashQuery('/')).toBe('');
    expect(slashQuery('/STA')).toBe('sta');
    expect(slashQuery('/status  ')).toBe('status');
  });
  it('leaves paths, prose, arguments, and multiline messages alone', () => {
    for (const text of ['/home/acorn/project', '/status\n', '/status now', 'see /status', '  /status', '//comment', '/tmp/file.ts']) {
      expect(slashQuery(text)).toBeUndefined();
    }
  });
});
