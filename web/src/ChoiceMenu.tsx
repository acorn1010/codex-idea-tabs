import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from './icons';

type Choice = { value: string; label: string; shortLabel?: string; description?: string; icon?: ReactNode };

/** A keyboard-accessible composer menu keeps full labels in its popup while its trigger adapts to the composer width. */
export function ChoiceMenu({ label, value, options, onChange, compact = false, hint, icon, openRequest = 0, placement = 'above', disabled = false, menuWidth = 280 }: { label: string; value: string; options: Choice[]; onChange: (value: string) => void; compact?: boolean; hint?: string; openRequest?: number; placement?: 'above' | 'below'; disabled?: boolean; menuWidth?: number; icon?: Parameters<typeof Icon>[0]['name'] }) {
  const [open, setOpen] = useState(false);
  useEffect(() => { if (openRequest) { setOpen(true); } }, [openRequest]);
  const [position, setPosition] = useState({ left: 0, top: 0, bottom: 0, width: 260, height: 300 });
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const id = useId();
  const selected = options.find((option) => option.value === value) || options[0];
  const hasIcons = options.some((option) => option.icon);
  const choose = (next: string) => { onChange(next); setOpen(false); trigger.current?.focus(); };
  useLayoutEffect(() => {
    if (!open) { return; }
    const place = () => {
      const rect = trigger.current!.getBoundingClientRect();
      const width = Math.min(menuWidth, window.innerWidth - 24);
      setPosition({ left: Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)), top: rect.bottom + 6, bottom: window.innerHeight - rect.top + 6, width, height: Math.max(80, Math.min(340, placement === 'above' ? rect.top - 18 : window.innerHeight - rect.bottom - 18)) });
    };
    place();
    const timer = window.setTimeout(() => menu.current?.querySelector<HTMLButtonElement>('[aria-selected="true"]')?.focus(), 0);
    const outside = (event: MouseEvent) => { if (!menu.current?.contains(event.target as Node) && !trigger.current?.contains(event.target as Node)) { setOpen(false); } };
    document.addEventListener('mousedown', outside);
    window.addEventListener('resize', place);
    return () => { window.clearTimeout(timer); document.removeEventListener('mousedown', outside); window.removeEventListener('resize', place); };
  }, [open, placement, menuWidth]);
  return <>
    <button ref={trigger} disabled={disabled} aria-label={`${label}: ${selected?.label}`} aria-haspopup="listbox" aria-expanded={open} aria-controls={open ? id : undefined} title={`${label}: ${selected?.label}`} onClick={() => setOpen(!open)} className={`flex h-7 min-w-7 items-center justify-center gap-1 rounded-md px-1 text-[11px] text-muted hover:bg-raised active:bg-line hover:text-ink active:text-accent ${compact ? 'max-w-40' : 'shrink-0 whitespace-nowrap'}`}>
      {selected?.icon && <span className="mr-0.5 flex shrink-0 items-center [&>svg]:size-3.5">{selected.icon}</span>}
      {icon && <span className="hidden @max-[380px]/composer:block"><Icon name={icon} size={14} /></span>}
      <span className={`${compact ? 'truncate' : ''} ${selected?.shortLabel ? '@max-[640px]/composer:hidden' : ''}`}>{selected?.label}</span>
      {selected?.shortLabel && <span className={`hidden @max-[640px]/composer:block ${icon ? '@max-[380px]/composer:hidden' : ''}`}>{selected.shortLabel}</span>}
      <span className={`shrink-0 ${icon ? '@max-[380px]/composer:hidden' : ''}`}><Icon name="down" size={11} /></span>
    </button>
    {open && createPortal(<div ref={menu} id={id} role="listbox" aria-label={label} style={{ left: position.left, ...(placement === 'above' ? { bottom: position.bottom } : { top: position.top }), width: position.width, maxHeight: position.height }} className={`fixed z-60 overflow-y-auto rounded-xl bg-raised shadow-2xl ${hasIcons ? 'space-y-1 p-1.5 ring-1 ring-line' : 'p-1'}`} onKeyDown={(event) => {
      const entries = Array.from(menu.current!.querySelectorAll<HTMLButtonElement>('[role="option"]'));
      const current = entries.indexOf(document.activeElement as HTMLButtonElement);
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setOpen(false); trigger.current?.focus(); }
      if (event.key === 'Tab') { setOpen(false); }
      if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
        event.preventDefault();
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? entries.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + entries.length) % entries.length;
        entries[next]?.focus();
      }
    }}>{hint && <p className="px-3 py-2 text-[11px] leading-relaxed text-muted">{hint}</p>}{options.map((option) => <button role="option" aria-selected={option.value === value} key={option.value} onClick={() => choose(option.value)} className={`flex w-full rounded-lg text-left hover:bg-surface active:bg-line ${hasIcons ? 'min-h-10 items-center gap-3 px-3 py-2 aria-selected:bg-surface/70 focus:outline-none! focus-visible:ring-1 focus-visible:ring-inset focus-visible:ring-accent/60' : 'items-start gap-2 px-2.5 py-2 focus:bg-surface focus:outline-none'}`}>
      {hasIcons ? <span className="flex size-5 shrink-0 items-center justify-center">{option.icon}</span> : <span className="mt-0.5 w-3 shrink-0 text-accent">{option.value === value && <Icon name="check" size={13} />}</span>}
      <span className="min-w-0 flex-1"><span className={`block text-xs text-ink ${hasIcons ? 'font-medium' : ''}`}>{option.label}</span>{option.description && <span className="mt-1 block text-[11px] leading-relaxed text-muted">{option.description}</span>}</span>
      {hasIcons && <span className="flex size-4 shrink-0 items-center justify-center text-accent">{option.value === value && <Icon name="check" size={14} />}</span>}
    </button>)}</div>, document.body)}
  </>;
}
