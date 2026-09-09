import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from './icons';

type Choice = { value: string; label: string; description?: string };

/** A keyboard-accessible composer menu keeps its selected label intact and opens outside the composer clip. */
export function ChoiceMenu({ label, value, options, onChange, compact = false, hint, openRequest = 0 }: { label: string; value: string; options: Choice[]; onChange: (value: string) => void; compact?: boolean; hint?: string; openRequest?: number }) {
  const [open, setOpen] = useState(false);
  useEffect(() => { if (openRequest) { setOpen(true); } }, [openRequest]);
  const [position, setPosition] = useState({ left: 0, bottom: 0, width: 260, height: 300 });
  const trigger = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const id = useId();
  const selected = options.find((option) => option.value === value) || options[0];
  const choose = (next: string) => { onChange(next); setOpen(false); trigger.current?.focus(); };
  useEffect(() => {
    if (!open) { return; }
    const place = () => {
      const rect = trigger.current!.getBoundingClientRect();
      const width = Math.min(280, window.innerWidth - 24);
      setPosition({ left: Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)), bottom: window.innerHeight - rect.top + 6, width, height: Math.max(80, Math.min(340, rect.top - 18)) });
    };
    place();
    const timer = window.setTimeout(() => menu.current?.querySelector<HTMLButtonElement>('[aria-selected="true"]')?.focus(), 0);
    const outside = (event: MouseEvent) => { if (!menu.current?.contains(event.target as Node) && !trigger.current?.contains(event.target as Node)) { setOpen(false); } };
    document.addEventListener('mousedown', outside);
    window.addEventListener('resize', place);
    return () => { window.clearTimeout(timer); document.removeEventListener('mousedown', outside); window.removeEventListener('resize', place); };
  }, [open]);
  return <>
    <button ref={trigger} aria-label={`${label}: ${selected?.label}`} aria-haspopup="listbox" aria-expanded={open} aria-controls={open ? id : undefined} title={selected?.label} onClick={() => setOpen(!open)} className={`flex h-7 items-center gap-1.5 rounded-md px-1.5 text-[11px] text-muted hover:bg-raised active:bg-line hover:text-ink active:text-accent ${compact ? 'min-w-0 max-w-40' : 'shrink-0 whitespace-nowrap'}`}>
      <span className={compact ? 'truncate' : ''}>{selected?.label}</span><span className="shrink-0"><Icon name="down" size={11} /></span>
    </button>
    {open && createPortal(<div ref={menu} id={id} role="listbox" aria-label={label} style={{ left: position.left, bottom: position.bottom, width: position.width, maxHeight: position.height }} className="fixed z-60 overflow-y-auto rounded-xl bg-raised p-1 shadow-2xl" onKeyDown={(event) => {
      const entries = Array.from(menu.current!.querySelectorAll<HTMLButtonElement>('[role="option"]'));
      const current = entries.indexOf(document.activeElement as HTMLButtonElement);
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setOpen(false); trigger.current?.focus(); }
      if (event.key === 'Tab') { setOpen(false); }
      if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
        event.preventDefault();
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? entries.length - 1 : (current + (event.key === 'ArrowDown' ? 1 : -1) + entries.length) % entries.length;
        entries[next]?.focus();
      }
    }}>{hint && <p className="px-3 py-2 text-[11px] leading-relaxed text-muted">{hint}</p>}{options.map((option) => <button role="option" aria-selected={option.value === value} key={option.value} onClick={() => choose(option.value)} className="flex w-full items-start gap-2 rounded-lg px-2.5 py-2 text-left hover:bg-surface active:bg-line focus:bg-surface focus:outline-none">
      <span className="mt-0.5 w-3 shrink-0 text-accent">{option.value === value && <Icon name="check" size={13} />}</span>
      <span className="min-w-0"><span className="block text-xs text-ink">{option.label}</span>{option.description && <span className="mt-1 block text-[11px] leading-relaxed text-muted">{option.description}</span>}</span>
    </button>)}</div>, document.body)}
  </>;
}
