import { describe, expect, it } from 'vitest';
import { chatMarkdown } from './chatMarkdown';

describe('chat Markdown', () => {
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
