import { describe, expect, it } from 'vitest';
import { groupTranscript, toolFailed } from './format';
import type { Item } from './types';

describe('transcript activity', () => {
  it('groups large command batches without crossing messages or generated images', () => {
    const commands: Item[] = Array.from({ length: 100 }, (_, i) => ({ id: `command-${i}`, type: 'commandExecution' }));
    const user: Item = { id: 'user', type: 'userMessage' };
    const update: Item = { id: 'update', type: 'agentMessage' };
    const image: Item = { id: 'image', type: 'mcpToolCall', result: { content: [{ type: 'image', data: 'preview' }] } };
    const groups = groupTranscript([user, ...commands, update, commands[0], image, commands[1]]);
    expect(groups.map(group => group.kind)).toEqual(['item', 'activity', 'item', 'activity', 'item', 'activity']);
    expect(groups[1]).toEqual({ kind: 'activity', items: commands });
    expect(groups[4]).toEqual({ kind: 'item', item: image });
  });

  it('keeps thinking, searches and edits with their commands without changing the source items', () => {
    const items: Item[] = [{ id: 'thought', type: 'reasoning' }, { id: 'command', type: 'commandExecution' }, { id: 'files', type: 'fileChange' }, { id: 'search', type: 'webSearch' }];
    const before = JSON.stringify(items);
    expect(groupTranscript(items)).toEqual([{ kind: 'activity', items }]);
    expect(JSON.stringify(items)).toBe(before);
    expect(groupTranscript([])).toEqual([]);
  });

  it('marks nonzero command exits and declined tools as failures', () => {
    expect(toolFailed({ id: 'command', type: 'commandExecution', status: 'completed', exitCode: 1 })).toBe(true);
    expect(toolFailed({ id: 'command', type: 'commandExecution', status: 'completed', exitCode: 0 })).toBe(false);
    expect(toolFailed({ id: 'tool', type: 'mcpToolCall', status: 'declined' })).toBe(true);
  });
});
