import { describe, expect, it } from 'vitest';
import { chatMarkdown } from './chatMarkdown';

describe('chat Markdown', () => {
  it('removes generated context and reminders without dropping the actual prompts and useful replies', () => {
    const chat = { provider: 'claude' as const, title: 'Readable chat', items: [
      { id: 'summary', type: 'userMessage', isCompactSummary: true, text: 'This session is being continued from a previous conversation that ran out of context.' },
      { id: 'meta', type: 'userMessage', isMeta: true, text: 'Injected instructions' },
      { id: 'task', type: 'userMessage', promptSource: 'system', text: 'Background task notification' },
      { id: 'user', type: 'userMessage', text: '<system-reminder>SECRET_METADATA</system-reminder>Actual **question**.\n\n<system-reminder>More metadata</system-reminder>' },
      { id: 'empty', type: 'userMessage', text: '<system-reminder>Only metadata</system-reminder>' },
      { id: 'reply', type: 'agentMessage', text: 'Useful answer.\n<system-reminder>INTERNAL_NOTE <system-reminder>nested</system-reminder></system-reminder>' },
    ] };
    const copied = chatMarkdown(chat);
    expect(copied).toContain('Actual **question**.'); expect(copied).toContain('Useful answer.');
    for (const text of ['system-reminder', 'SECRET_METADATA', 'INTERNAL_NOTE', 'continued', 'Injected instructions', 'Only metadata', 'Background task notification']) { expect(copied).not.toContain(text); }
    expect(copied.match(/## User/g)).toHaveLength(1);
    expect(chatMarkdown(chat, 'full')).toContain('SECRET_METADATA');
    expect(chatMarkdown(chat, 'full')).toContain('This session is being continued');
  });
  it('preserves literal reminder tags in quoted code and actual user discussion of continuation text', () => {
    const prompt = 'Explain `<system-reminder>example</system-reminder>`.\n\n```xml\n<system-reminder>literal code</system-reminder>\n````\n\nThis session is being continued: what does that mean?';
    const copied = chatMarkdown({ title: 'Examples', items: [{ id: 'u', type: 'userMessage', text: prompt }] });
    expect(copied).toContain(prompt);
    const inline = 'Explain <system-reminder>literal XML</system-reminder> in this sentence.';
    expect(chatMarkdown({ title: 'Inline', items: [{ id: 'u', type: 'userMessage', text: inline }] })).toContain(inline);
    expect(chatMarkdown({ title: 'Tags', items: [{ id: 'u', type: 'userMessage', text: 'What is <system-reminder> used for?' }] })).toContain('What is <system-reminder> used for?');
  });

  it('keeps the author of each reply after repeated provider switches', () => {
    const chat = { provider: 'claude' as const, title: 'Mixed chat', items: [
      { id: 'old', type: 'agentMessage', historyProvider: 'codex', text: 'Original answer' },
      { id: 'middle', type: 'agentMessage', historyProvider: 'claude', text: 'Previous Claude answer' },
      { id: 'current', type: 'agentMessage', text: 'Current answer' },
    ] };
    for (const mode of ['conversation', 'full'] as const) {
      const markdown = chatMarkdown(chat, mode);
      expect(markdown).toContain('## Codex\n\nOriginal answer');
      expect(markdown).toContain('## Claude\n\nPrevious Claude answer');
      expect(markdown).toContain('## Claude\n\nCurrent answer');
    }
  });
  it('labels Claude replies in both export modes', () => {
    const chat = { provider: 'claude' as const, title: 'Claude chat', items: [{ id: 'reply', type: 'agentMessage', text: 'Hello' }] };
    expect(chatMarkdown(chat)).toContain('## Claude\n\nHello');
    expect(chatMarkdown(chat, 'full')).toContain('## Claude\n\nHello');
  });
  it('defaults to only user and agent messages while preserving their Markdown and images', () => {
    const result = chatMarkdown({ title: 'Conversation', items: [
      { id: 'user', type: 'userMessage', content: [{ type: 'text', text: '**Question**' }, { type: 'localImage', path: '/tmp/image.png' }] },
      { id: 'reasoning', type: 'reasoning', summary: ['THINKING_MARKER'] },
      { id: 'command', type: 'commandExecution', command: 'COMMAND_MARKER', aggregatedOutput: 'OUTPUT_MARKER' },
      { id: 'tool', type: 'mcpToolCall', result: { text: 'TOOL_MARKER' } },
      { id: 'change', type: 'fileChange', changes: [{ path: 'FILE_MARKER' }] },
      { id: 'progress', type: 'agentMessage', text: 'Checking now.' },
      { id: 'answer', type: 'agentMessage', text: '```ts\nconst result = 1;\n```' },
    ], requests: [{ key: 'request', method: 'approval', message: 'APPROVAL_MARKER' }] });
    expect(result).toBe('# Conversation\n\n---\n\n## User\n\n**Question**\n\n![Attached image](</tmp/image.png>)\n\n---\n\n## Codex\n\nChecking now.\n\n---\n\n## Codex\n\n```ts\nconst result = 1;\n```\n');
  });
  it('preserves message Markdown, image references, commands and tool output', () => {
    const result = chatMarkdown({ title: 'Review #1', items: [
      { id: 'user', type: 'userMessage', content: [{ type: 'text', text: 'Please review **this**.' }, { type: 'localImage', path: '/tmp/screenshot (1).png' }] },
      { id: 'thinking', type: 'reasoning', summary: ['A visible summary.'] },
      { id: 'command', type: 'commandExecution', command: 'printf "```"', aggregatedOutput: '```\noutput', status: 'completed', exitCode: 0 },
      { id: 'tool', type: 'mcpToolCall', server: 'browser', tool: 'read', result: { content: [{ type: 'text', text: 'Page text' }] } },
      { id: 'answer', type: 'agentMessage', text: '## Result\n\n```ts\nconst value = 1;\n```' },
    ] }, 'full');
    expect(result).toContain('# Review \\#1');
    expect(result).toContain('## User\n\nPlease review **this**.');
    expect(result).toContain('![Attached image](</tmp/screenshot%20(1).png>)');
    expect(result).toContain('### Thinking\n\nA visible summary.');
    expect(result).toContain('````shell\nprintf "```"\n````');
    expect(result).toContain('````text\n```\noutput\n````');
    expect(result).toContain('Page text');
    expect(result).toContain('## Codex\n\n## Result\n\n```ts\nconst value = 1;\n```');
  });
  it('skips empty thinking and includes visible questions without unsent drafts', () => {
    const result = chatMarkdown({ title: 'Chat', items: [{ id: 'empty', type: 'reasoning', summary: [] }], requests: [{ key: 'pending', method: 'item/tool/requestUserInput', questions: [{ id: 'q', header: '', question: 'Which layout?', options: [{ label: 'Compact' }] }] }] }, 'full');
    expect(result).not.toContain('Thinking');
    expect(result).toContain('Which layout?');
    expect(result).toContain('Compact');
  });
});
