import { useEffect, useId, useRef, useState } from 'react';
import type { KeyboardEvent, RefObject } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from './icons';

export type Skill = { name: string; path: string; description: string; shortDescription?: string; enabled: boolean; scope: string; interface?: { displayName?: string; shortDescription?: string } };
export type CommandName = 'review' | 'fast' | 'feedback' | 'goal' | 'ide-context' | 'init' | 'mcp' | 'memories' | 'model' | 'plan' | 'reasoning' | 'status' | 'context' | 'permissions' | 'new' | 'resume' | 'settings' | 'stop';
export type SlashItem = { id: string; name: string; label: string; description: string; icon: Parameters<typeof Icon>[0]['name']; scope?: string; disabled?: boolean } & ({ kind: 'command'; command: CommandName } | { kind: 'skill'; skill: Skill });

/** A single command token is distinct from paths, prose, or pasted multiline text. */
export function slashQuery(text: string): string | undefined {
  return /^\/[a-z0-9:_-]*[\t ]*$/i.test(text) ? text.trim().slice(1).toLowerCase() : undefined;
}

/** Keep typing focus in the composer and render compact actions outside its clipped input surface. */
export function useSlashCommands({ draft, field, items, loading, error, onRetry, onRun }: { draft: string; field: RefObject<HTMLTextAreaElement | null>; items: SlashItem[]; loading: boolean; error: string; onRetry: () => void; onRun: (item: SlashItem) => void }) {
  const id = useId();
  const menu = useRef<HTMLDivElement>(null);
  const [focused, setFocused] = useState(false);
  const [dismissed, setDismissed] = useState<string>();
  const [index, setIndex] = useState(0);
  const [position, setPosition] = useState({ left: 12, bottom: 0, width: 320, maxHeight: 280 });
  const query = slashQuery(draft);
  const matches = items.filter((item) => query !== undefined && (!query || item.name.toLowerCase().includes(query) || item.label.toLowerCase().includes(query))).sort((left, right) => Number(right.name.toLowerCase() === query) - Number(left.name.toLowerCase() === query));
  const open = focused && query !== undefined && dismissed !== draft;
  const selectedIndex = Math.min(index, matches.length - 1);
  const selected = matches[selectedIndex];
  useEffect(() => { setIndex(0); setDismissed(undefined); }, [draft]);
  useEffect(() => {
    if (!open || !field.current) { return; }
    const element = field.current;
    const place = () => {
      const rect = element.getBoundingClientRect();
      const width = Math.min(rect.width, window.innerWidth - 24);
      setPosition({ left: Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)), bottom: window.innerHeight - rect.top + 6, width, maxHeight: Math.max(60, Math.min(360, rect.top - 18)) });
    };
    place();
    const observer = new ResizeObserver(place);
    observer.observe(element);
    window.addEventListener('resize', place);
    return () => { observer.disconnect(); window.removeEventListener('resize', place); };
  }, [open, field]);
  useEffect(() => { if (open) { menu.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' }); } }, [open, selected?.id]);
  const choose = (item: SlashItem) => { if (!item.disabled) { setDismissed(draft); onRun(item); } };
  const keyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (!open || event.nativeEvent.isComposing) { return false; }
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setDismissed(draft); return true; }
    if (['ArrowDown', 'ArrowUp'].includes(event.key) && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
      event.preventDefault(); event.stopPropagation();
      if (matches.length) {
        let next = selectedIndex;
        for (let count = 0; count < matches.length; count++) {
          next = (next + (event.key === 'ArrowDown' ? 1 : -1) + matches.length) % matches.length;
          if (!matches[next].disabled) { break; }
        }
        setIndex(next);
      }
      return true;
    }
    if ((event.key === 'Enter' && (!event.shiftKey || event.ctrlKey || event.metaKey)) || (event.key === 'Tab' && !event.shiftKey)) {
      event.preventDefault(); event.stopPropagation();
      if (!event.repeat && selected) { choose(selected); }
      return true;
    }
    return false;
  };
  return {
    keyDown, setFocused,
    command: query !== undefined && dismissed !== draft && selected ? selected : items.find((item) => item.name.toLowerCase() === query),
    inputProps: { 'aria-autocomplete': 'list' as const, 'aria-controls': open ? id : undefined, 'aria-activedescendant': open && selected ? `${id}-${selectedIndex}` : undefined },
    menu: open && createPortal(<div ref={menu} id={id} role="listbox" aria-label="Slash commands" style={position} className="fixed z-60 overflow-y-auto rounded-xl bg-raised p-1 text-ink shadow-2xl" onMouseDown={(event) => event.preventDefault()}>
      {matches.map((item, current) => <div key={item.id}>
        {item.kind === 'skill' && matches[current - 1]?.kind !== 'skill' && <p className="px-2 py-1.5 text-[11px] text-muted">Skills</p>}
        <button id={`${id}-${current}`} role="option" aria-selected={selected?.id === item.id} aria-disabled={!!item.disabled} disabled={item.disabled} tabIndex={-1} type="button" title={`${item.label}: ${item.description}`} onClick={() => choose(item)} className={`flex h-6 w-full min-w-0 items-center gap-2 rounded-md px-2 text-left disabled:cursor-default disabled:opacity-45 ${selected?.id === item.id ? 'bg-line/60 enabled:hover:bg-line enabled:active:bg-input' : 'enabled:hover:bg-line/40 enabled:active:bg-input'}`}>
          <span className="shrink-0 text-muted"><Icon name={item.icon} size={13} /></span><span className="max-w-[55%] shrink-0 truncate text-[11px]">{item.label}</span><span className="min-w-0 flex-1 truncate text-[11px] text-muted">{item.description}</span>{item.scope && <span className="max-w-[20%] shrink-0 truncate text-[10px] text-muted">{item.scope}</span>}
        </button>
      </div>)}
      {loading && <p role="status" className="px-2 py-1.5 text-[10px] text-muted">Loading skills…</p>}
      {error && <p className="px-2 py-1.5 text-[10px] text-attention">{error}<button type="button" className="ml-2 rounded px-1 underline hover:bg-attention/10 active:bg-attention/20" onClick={onRetry}>Retry skills</button></p>}
      {!matches.length && !loading && <p className="px-2 py-3 text-xs text-muted">No matching commands or skills.</p>}
    </div>, document.body),
  };
}
