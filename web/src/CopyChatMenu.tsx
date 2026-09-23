import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { request } from './bridge';
import { chatMarkdown } from './chatMarkdown';
import type { ChatCopyMode } from './chatMarkdown';
import type { Chat } from './types';
import { Icon } from './icons';

/** Offer a messages-only copy first, with full activity available as a separate action. */
export function CopyChatMenu({ disabled, onError }: { disabled: boolean; onError: (message: string) => void }) {
  const [open, setOpen] = useState(false);
  const [copyState, setCopyState] = useState<'idle' | 'copying' | 'copied'>('idle');
  const [position, setPosition] = useState({ left: 0, top: 0, width: 280 });
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const copyTimer = useRef<number>(0);
  const id = useId();
  useEffect(() => () => window.clearTimeout(copyTimer.current), []);
  useEffect(() => {
    if (!open) { return; }
    const place = () => {
      const rect = trigger.current!.getBoundingClientRect();
      const width = Math.min(280, window.innerWidth - 24);
      setPosition({ left: Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12)), top: rect.bottom + 6, width });
    };
    place();
    menu.current?.querySelector<HTMLButtonElement>('button')?.focus();
    const outside = (event: MouseEvent) => {
      if (!menu.current?.contains(event.target as Node) && !trigger.current?.contains(event.target as Node)) { setOpen(false); }
    };
    document.addEventListener('mousedown', outside);
    window.addEventListener('resize', place);
    return () => { document.removeEventListener('mousedown', outside); window.removeEventListener('resize', place); };
  }, [open]);
  const close = () => { setOpen(false); trigger.current?.focus(); };
  const copy = async (mode: ChatCopyMode) => {
    if (copyState === 'copying') { return; }
    close(); window.clearTimeout(copyTimer.current); setCopyState('copying'); onError('');
    try {
      const transcript = await request<Pick<Chat, 'title' | 'items' | 'requests'>>('chatTranscript');
      await request('copy', { text: chatMarkdown(transcript, mode) });
      setCopyState('copied'); copyTimer.current = window.setTimeout(() => setCopyState('idle'), 1500);
    } catch (error) { setCopyState('idle'); onError(`Could not copy chat: ${(error as Error).message}`); }
  };
  return <>
    <button ref={trigger} aria-label="Copy chat as Markdown" aria-haspopup="menu" aria-expanded={open} aria-controls={open ? id : undefined} disabled={disabled || copyState === 'copying'} title={copyState === 'copying' ? 'Copying chat…' : copyState === 'copied' ? 'Chat copied' : 'Copy chat as Markdown'} onClick={() => setOpen(!open)} onKeyDown={(event) => {
      if (event.key === 'ArrowDown') { event.preventDefault(); setOpen(true); }
    }} className="flex size-7 shrink-0 items-center justify-center rounded-md text-muted enabled:hover:bg-raised enabled:active:bg-line enabled:hover:text-ink enabled:active:text-accent disabled:opacity-40"><Icon name={copyState === 'copied' ? 'check' : 'copy'} /></button>
    <span role="status" className="sr-only">{copyState === 'copying' ? 'Copying chat' : copyState === 'copied' ? 'Chat copied' : ''}</span>
    {open && createPortal(<div ref={menu} id={id} role="menu" aria-label="Copy chat" style={position} className="fixed z-60 max-h-[calc(100vh-60px)] overflow-y-auto rounded-xl bg-raised p-1 shadow-2xl" onKeyDown={(event) => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); }
      if (event.key === 'Tab') { close(); }
      if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
        event.preventDefault();
        const entries = Array.from(menu.current!.querySelectorAll<HTMLButtonElement>('[role="menuitem"]'));
        const current = entries.indexOf(document.activeElement as HTMLButtonElement);
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? entries.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + entries.length) % entries.length;
        entries[next]?.focus();
      }
    }}>
      <button role="menuitem" tabIndex={-1} aria-label="Copy conversation" onClick={() => void copy('conversation')} className="block w-full rounded-lg px-2.5 py-2 text-left hover:bg-surface active:bg-line focus:bg-surface focus:outline-none">
        <span className="flex items-center justify-between gap-3 text-xs text-ink">Copy conversation<span className="text-[10px] text-muted">Default</span></span>
        <span className="mt-1 block text-[11px] text-muted">User messages and Codex replies</span>
      </button>
      <button role="menuitem" tabIndex={-1} aria-label="Copy full chat" onClick={() => void copy('full')} className="block w-full rounded-lg px-2.5 py-2 text-left hover:bg-surface active:bg-line focus:bg-surface focus:outline-none">
        <span className="block text-xs text-ink">Copy full chat</span>
        <span className="mt-1 block text-[11px] text-muted">Messages, thinking, and tool activity</span>
      </button>
    </div>, document.body)}
  </>;
}
