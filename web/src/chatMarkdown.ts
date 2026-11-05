import { itemText, toolContent, toolFailed, toolImagePaths, toolLabel } from './format';
import type { Chat, Json } from './types';

export type ChatCopyMode = 'conversation' | 'full';

/** Export messages by default, with optional activity, preserving Markdown and image references. */
export function chatMarkdown(chat: Pick<Chat, 'title' | 'items'> & Partial<Pick<Chat, 'requests' | 'provider'>>, mode: ChatCopyMode = 'conversation'): string {
  const sections = [`# ${heading(chat.title || 'Chat')}`];
  for (const item of chat.items) {
    const message = item.type === 'userMessage' || item.type === 'agentMessage';
    if (mode === 'conversation' && (!message || item.isMeta || item.isCompactSummary || item.isApiErrorMessage || item.promptSource === 'system' || item.turnOrigin === 'task_notification')) { continue; }
    const body = mode === 'conversation' ? conversationText(itemText(item)) : itemText(item);
    if (message) {
      const images = (item.content || []).filter((part): part is Json => typeof part !== 'string' && (part.type === 'image' || part.type === 'localImage'))
        .map(part => imageReference(String(part.path || part.url || ''), 'Attached image')).filter(Boolean);
      if (!body.trim() && !images.length) { continue; }
      sections.push([`## ${item.type === 'userMessage' ? 'User' : (item.historyProvider || chat.provider) === 'claude' ? 'Claude' : 'Codex'}`, body, ...images].filter(Boolean).join('\n\n'));
    } else if (item.type === 'reasoning') {
      if (body.trim()) { sections.push(`### Thinking\n\n${body}`); }
    } else if (item.type === 'commandExecution') {
      sections.push([`### Command${toolFailed(item) ? ' (failed)' : ''}`, item.command ? fence(item.command, 'shell') : '',
        item.aggregatedOutput || body ? fence(item.aggregatedOutput || body, 'text') : '',
        typeof item.exitCode === 'number' ? `Exit code: ${item.exitCode}` : ''].filter(Boolean).join('\n\n'));
    } else {
      sections.push([`### ${heading(toolLabel(item))}${toolFailed(item) ? ' (failed)' : ''}`, fence(toolContent(item), 'text'),
        ...toolImagePaths(item).map(path => imageReference(path, 'Generated image'))].filter(Boolean).join('\n\n'));
    }
  }
  for (const pending of mode === 'full' ? chat.requests || [] : []) {
    const questions = (pending.questions || []).map(question => [question.question, ...(question.options || []).map(option => `- ${option.label}`)].join('\n'));
    sections.push(['### Pending request', pending.reason || pending.message || '', pending.command ? fence(pending.command, 'shell') : '', ...questions].filter(Boolean).join('\n\n'));
  }
  return sections.join('\n\n---\n\n') + '\n';
}

/** Remove injected reminders while preserving the user's quoted code and Markdown. */
function conversationText(text: string): string {
  let marker = '\u0000codex-copy-code-';
  while (text.includes(marker)) { marker += '_'; }
  const code: string[] = [];
  const protectedText = text.replace(/(^[ \t]*(`{3,}|~{3,})[^\n]*\n[\s\S]*?^[ \t]*\2[`~]*[ \t]*(?:\n|$))|(`+)[^\n]*?\3/gm, value => `${marker}${code.push(value) - 1}\u0000`);
  let depth = 0, start = 0, end = 0;
  const parts: string[] = [];
  for (const match of protectedText.matchAll(/<\/?system-reminder(?:\s[^>]*)?>/gi)) {
    if (match[0].startsWith('</')) {
      if (depth > 0 && --depth === 0) { parts.push(protectedText.slice(end, start)); end = match.index + match[0].length; }
    } else {
      if (depth === 0) {
        // Inline XML in a sentence is part of the user's prompt, not an injected reminder block.
        if (protectedText.slice(protectedText.lastIndexOf('\n', match.index - 1) + 1, match.index).trim()) { continue; }
        start = match.index;
      }
      depth++;
    }
  }
  parts.push(protectedText.slice(end));
  return parts.join('').replace(/^\n+|\n+$/g, '').replace(new RegExp(`${marker}(\\d+)\\u0000`, 'g'), (_, index: string) => code[Number(index)]);
}

function heading(text: string): string {
  return text.replace(/[\r\n]+/g, ' ').replace(/[\\`*_[\]<>#]/g, '\\$&');
}

function fence(text: string, language: string): string {
  let length = 3;
  for (const match of text.matchAll(/`+/g)) { length = Math.max(length, match[0].length + 1); }
  const marker = '`'.repeat(length);
  return `${marker}${language}\n${text}\n${marker}`;
}

function imageReference(path: string, label: string): string {
  return path ? `![${label}](<${path.replace(/[\s<>]/g, character => encodeURIComponent(character))}>)` : '';
}
