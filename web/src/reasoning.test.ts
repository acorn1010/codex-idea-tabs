import { describe, expect, it } from 'vitest';
import { itemText } from './format';

describe('visible reasoning', () => {
  it('reads content strings when the summary is empty', () => {
    expect(itemText({ id: 'thinking', type: 'reasoning', summary: [], content: ['Inspect the state updates.', 'Check the resume response.'] })).toBe('Inspect the state updates.\n\nCheck the resume response.');
  });
  it('uses readable summaries without duplicating alternative content', () => {
    expect(itemText({ id: 'thinking', type: 'reasoning', summary: ['First check.', 'Second check.'], content: ['Alternative text'] })).toBe('First check.\n\nSecond check.');
  });
  it('falls back past blank summary parts', () => {
    expect(itemText({ id: 'thinking', type: 'reasoning', summary: [' ', ''], content: ['Visible text'] })).toBe('Visible text');
  });
  it('keeps streamed text and does not expose item metadata as text', () => {
    expect(itemText({ id: 'thinking', type: 'reasoning', text: 'Streaming now', summary: [], content: [] })).toBe('Streaming now');
    expect(itemText({ id: 'internal-id', type: 'reasoning', summary: [], content: [], encrypted_content: 'opaque-data' })).toBe('');
  });
  it('preserves user text and image handling', () => {
    expect(itemText({ id: 'user', type: 'userMessage', content: [{ type: 'text', text: 'Inspect this' }, { type: 'localImage', path: '/image.png' }, { type: 'text', text: 'Then fix it' }] })).toBe('Inspect this\nThen fix it');
  });
});
