import { describe, expect, it } from 'vitest';
import { chatMarkdown } from './chatMarkdown';

describe('whole chat Markdown', () => {
  it('preserves message Markdown, image references, commands and tool output', () => {
    const result = chatMarkdown({ title: 'Review #1', items: [
      { id: 'user', type: 'userMessage', content: [{ type: 'text', text: 'Please review **this**.' }, { type: 'localImage', path: '/tmp/screenshot (1).png' }] },
      { id: 'thinking', type: 'reasoning', summary: ['A visible summary.'] },
      { id: 'command', type: 'commandExecution', command: 'printf "```"', aggregatedOutput: '```\noutput', status: 'completed', exitCode: 0 },
      { id: 'tool', type: 'mcpToolCall', server: 'browser', tool: 'read', result: { content: [{ type: 'text', text: 'Page text' }] } },
      { id: 'answer', type: 'agentMessage', text: '## Result\n\n```ts\nconst value = 1;\n```' },
    ] });
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
    const result = chatMarkdown({ title: 'Chat', items: [{ id: 'empty', type: 'reasoning', summary: [] }], requests: [{ key: 'pending', method: 'item/tool/requestUserInput', questions: [{ id: 'q', header: '', question: 'Which layout?', options: [{ label: 'Compact' }] }] }] });
    expect(result).not.toContain('Thinking');
    expect(result).toContain('Which layout?');
    expect(result).toContain('Compact');
  });
});
