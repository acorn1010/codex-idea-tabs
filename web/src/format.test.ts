import { describe, expect, it } from 'vitest';
import { attachmentInput, groupTranscript, mergeChat, modelEffort, toolContent, toolImagePaths } from './format';
import type { Chat, Item, Model } from './types';

describe('chat rendering and context', () => {
  it('shows Claude image-reader results outside collapsed activity', () => {
    const item: Item = { id: 'read-image', type: 'mcpToolCall', server: 'Claude', tool: 'Read', result: { content: [
      { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'PICTURE' } },
    ] } };
    expect(toolImagePaths(item)).toEqual(['data:image/png;base64,PICTURE']);
    expect(toolContent(item)).toBe('Image result');
    expect(groupTranscript([item])).toEqual([{ kind: 'item', item }]);
    expect(toolContent({ ...item, result: { content: [...(item.result as { content: object[] }).content, { type: 'text', text: 'Image dimensions' }] } })).toBe('Image dimensions');
    expect(toolImagePaths({ ...item, result: { content: [{ type: 'image', data: 'MCP', mimeType: 'image/webp' }] } })).toEqual(['data:image/webp;base64,MCP']);
    expect(toolImagePaths({ ...item, result: { content: [{ type: 'image', source: { type: 'base64', data: '' } }] } })).toEqual([]);
  });
  it('keeps compatible reasoning choices and preserves an explicit default', () => {
    const models = [{ id: 'model', model: 'model', defaultReasoningEffort: 'low', supportedReasoningEfforts: [{ reasoningEffort: 'low' }, { reasoningEffort: 'high' }] }] as Model[];
    expect(modelEffort(models, 'model', 'high')).toBe('high');
    expect(modelEffort(models, 'model', 'xhigh')).toBe('low');
    expect(modelEffort(models, 'model', '')).toBe('');
    expect(modelEffort(models, '', 'high')).toBe('');
  });
  it('keeps unchanged messages stable when a new token arrives', () => {
    const first = { id: '1', type: 'userMessage', text: 'Hi' };
    const old = { id: 'chat', items: [first, { id: '2', type: 'agentMessage', text: 'Hel' }] } as Chat;
    const next = { id: 'chat', partial: true, items: [{ id: '2', type: 'agentMessage', text: 'Hello' }] } as Chat;
    const merged = mergeChat(old, next);
    expect(merged.items[0]).toBe(first);
    expect(merged.items[1].text).toBe('Hello');
  });
  it('keeps images as image inputs and marks file content as untrusted context', () => {
    const result = attachmentInput([{ name: 'image.png', path: '/home/a/image.png', mime: 'image/png', size: 1 }, { name: 'rules.txt', path: '/home/a/rules.txt', mime: 'text/plain', size: 2 }]);
    expect(result[0]).toEqual({ type: 'localImage', path: '/home/a/image.png' });
    expect(result[1]).toMatchObject({ type: 'text', text: expect.stringContaining('document content') });
  });
});
