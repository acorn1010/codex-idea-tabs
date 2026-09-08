import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { request } from './bridge';
import { Icon } from './icons';
import { analyzeContext, contextPaths, contextText } from './contextAnalysis';
import type { ContextReport } from './contextAnalysis';

const categories = { all: 'All content', instructions: 'Rules and instructions', skills: 'Skills', messages: 'Messages', tools: 'Tools', files: 'Images', summary: 'Summaries' } as const;
const number = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });
const control = 'flex size-8 shrink-0 items-center justify-center rounded-md text-muted hover:bg-raised hover:text-ink active:bg-line disabled:cursor-default disabled:opacity-40';

/** A full-pane inspector preserves the chat and draft underneath, including in narrow editor splits. */
export function ContextInspector({ model, effort, status, onClose }: { model: string; effort: string; status?: string; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const searchField = useRef<HTMLInputElement>(null);
  const [view, setView] = useState<'recorded' | 'startup'>('recorded');
  const [reports, setReports] = useState<Partial<Record<'recorded' | 'startup', ContextReport>>>({});
  const [pending, setPending] = useState<Partial<Record<'recorded' | 'startup', boolean>>>({});
  const [errors, setErrors] = useState<Partial<Record<'recorded' | 'startup', string>>>({});
  const [actionError, setActionError] = useState('');
  const [copied, setCopied] = useState(false);
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<keyof typeof categories>('all');
  const [duplicatesOnly, setDuplicatesOnly] = useState(false);
  const [sort, setSort] = useState<'largest' | 'order'>('largest');
  const [selected, setSelected] = useState<string>();
  const [limit, setLimit] = useState(80);
  const alive = useRef(true);
  const fetching = useRef(new Set<string>());
  const report = reports[view];
  const analysis = useMemo(() => analyzeContext(report?.blocks || []), [report]);
  const repeatedParagraphs = useMemo(() => new Set(analysis.duplicates.map((entry) => entry.text)), [analysis]);
  const term = search.trim().toLowerCase();
  const filtered = useMemo(() => {
    const blocks = (report?.blocks || []).filter((block) => (category === 'all' || block.kind === category) && (!duplicatesOnly || analysis.duplicateBlocks.has(block.id)) && (!term || `${block.text}\n${block.call || ''}\n${block.label}\n${block.origin}`.toLowerCase().includes(term)));
    return sort === 'largest' ? [...blocks].sort((a, b) => b.text.length - a.text.length) : blocks;
  }, [report, category, duplicatesOnly, analysis, term, sort]);
  const block = filtered.find((entry) => entry.id === selected);
  const paths = useMemo(() => block ? contextPaths(block) : [], [block]);

  const load = async (target: 'recorded' | 'startup') => {
    if (fetching.current.has(target)) { return; }
    fetching.current.add(target);
    setPending((current) => ({ ...current, [target]: true }));
    setErrors((current) => ({ ...current, [target]: '' }));
    try {
      const result = await request<ContextReport>(target === 'startup' ? 'inspectStartup' : 'inspectContext', { model, effort });
      if (alive.current) { setReports((current) => ({ ...current, [target]: result })); }
    } catch (error) { if (alive.current) { setErrors((current) => ({ ...current, [target]: (error as Error).message })); } }
    finally { fetching.current.delete(target); if (alive.current) { setPending((current) => ({ ...current, [target]: false })); } }
  };
  useEffect(() => {
    alive.current = true;
    const previous = document.activeElement;
    const element = dialog.current;
    element?.showModal();
    searchField.current?.focus();
    void load('recorded');
    return () => { alive.current = false; element?.close(); if (previous instanceof HTMLElement) { previous.focus(); } };
  }, []);
  useEffect(() => { if (report) { searchField.current?.focus(); } }, [view, !!report]);
  useEffect(() => { setLimit(80); setSelected(undefined); }, [view, search, category, duplicatesOnly]);
  const action = async (method: string, params: Record<string, unknown>) => {
    setActionError('');
    try { await request(method, params); return true; }
    catch (error) { setActionError((error as Error).message); return false; }
  };
  const switchView = (next: 'recorded' | 'startup') => { setView(next); setCopied(false); };

  return createPortal(<dialog ref={dialog} aria-label="Context inspector" onCancel={onClose} onClose={onClose} className="@container fixed inset-0 m-0 h-full max-h-none w-full max-w-none bg-surface p-0 text-ink [--focus-color:var(--ink)] backdrop:bg-black/50" onDragOver={(event) => event.stopPropagation()} onDrop={(event) => { event.preventDefault(); event.stopPropagation(); }} onKeyDown={(event) => event.stopPropagation()}>
    <div className="flex h-full min-w-0 flex-col">
      <header className="flex shrink-0 items-center gap-2 px-3 pt-2 pb-1">
        <button type="button" className={control} title="Back to chat (Escape)" aria-label="Back to chat" onClick={onClose}><span className="rotate-180"><Icon name="arrow" /></span></button>
        <h1 className="min-w-0 flex-1 truncate text-sm font-medium">Context inspector</h1>
        {status === 'attention' ? <button className="rounded px-2 py-1 text-[11px] text-attention hover:bg-attention/10 active:bg-attention/20" onClick={onClose}>Needs you</button> : status === 'working' && <span title="Chat is working" className="flex shrink-0 items-center gap-1.5 text-[10px] text-muted"><span className="size-1.5 rounded-full bg-accent" /><span className="hidden @min-[440px]:inline">Chat is working</span></span>}
        <button type="button" className={control} aria-label="Refresh context" title={view === 'startup' ? 'Rebuild startup snapshot' : 'Refresh saved context'} disabled={pending[view] || view === 'startup' && !report} onClick={() => void load(view)}><Icon name="refresh" /></button>
        <button type="button" className={control} aria-label={copied ? 'JSON copied' : 'Copy context JSON'} title={copied ? 'JSON copied' : 'Copy context as JSON'} disabled={!report} onClick={() => { if (report) { void action('copy', { text: JSON.stringify(report, null, 2) }).then((ok) => setCopied(!!ok)); } }}><Icon name={copied ? 'check' : 'copy'} /></button>
        <button type="button" className={control} aria-label="Open context as text" title="Open this snapshot in a text editor" disabled={!report} onClick={() => { if (report) { void action('contextExport', { text: contextText(report) }); } }}><Icon name="file" /></button>
      </header>
      <div className="flex shrink-0 items-center gap-1 px-4 py-2" role="group" aria-label="Context source">
        <button type="button" aria-pressed={view === 'recorded'} className={`rounded-md px-3 py-1.5 text-xs ${view === 'recorded' ? 'bg-raised text-ink' : 'text-muted hover:bg-raised/60 active:bg-line'}`} onClick={() => switchView('recorded')}>Recorded</button>
        <button type="button" aria-pressed={view === 'startup'} className={`rounded-md px-3 py-1.5 text-xs ${view === 'startup' ? 'bg-raised text-ink' : 'text-muted hover:bg-raised/60 active:bg-line'}`} onClick={() => switchView('startup')}>Startup</button>
        {report && <span className="ml-auto text-right text-[10px] text-muted">{report.kind === 'transcript' ? 'Loaded transcript only' : view === 'startup' ? 'Fresh CLI input' : report.compactions ? `After ${report.compactions} compaction${report.compactions === 1 ? '' : 's'}` : 'Saved session'}</span>}
      </div>
      {(errors[view] || actionError) && <div role="alert" className="mx-4 mb-2 rounded-md bg-red-400/10 px-3 py-2 text-xs text-red-300">{errors[view] || actionError}<button className="ml-2 rounded px-1 underline hover:bg-red-400/15 active:bg-red-400/25" onClick={() => { setActionError(''); void load(view); }}>Retry</button></div>}
      {!report ? <div className="flex min-h-0 flex-1 flex-col items-start justify-center gap-3 px-6 pb-8">
        <Icon name="layers" size={26} />
        <h2 className="text-base font-medium">{pending[view] ? view === 'startup' ? 'Building startup input…' : 'Reading saved context…' : view === 'startup' ? 'Inspect the starting instructions' : 'No snapshot yet'}</h2>
        <p className="max-w-md text-xs leading-relaxed text-muted">{view === 'startup' ? 'Build a fresh snapshot of the rules, skill catalog, and environment Codex loads for this workspace. Your current chat and draft stay as they are.' : 'The inspector reads saved records on demand. It does not send a message or compact the chat.'}</p>
        {view === 'startup' && <button type="button" disabled={pending.startup} className="rounded-lg bg-ink px-3 py-2 text-xs font-medium text-composer enabled:hover:bg-accent enabled:active:bg-accent/75 disabled:cursor-default disabled:opacity-50" onClick={() => void load('startup')}>Build startup snapshot</button>}
      </div> : <>
        <div className="flex shrink-0 flex-wrap items-baseline gap-x-5 gap-y-1 px-4 pt-1 pb-2">
          <div title="Approximate text tokens: characters divided by four. Images, hidden content, and tool definitions are excluded."><strong className="text-xl font-medium tabular-nums">≈{number.format(analysis.estimatedTokens)}</strong><span className="ml-1.5 text-[10px] text-muted">text tokens</span></div>
          <span className="text-xs text-muted">{report.blocks.length} blocks</span>
          <button type="button" disabled={!analysis.duplicates.length} aria-pressed={duplicatesOnly} className={`rounded px-1.5 py-1 text-xs disabled:cursor-default ${duplicatesOnly ? 'bg-attention/15 text-attention hover:bg-attention/20 active:bg-attention/25' : analysis.duplicates.length ? 'text-attention hover:bg-attention/10 active:bg-attention/20' : 'text-muted'}`} onClick={() => setDuplicatesOnly((value) => !value)}>{analysis.duplicates.length} repeated passage{analysis.duplicates.length === 1 ? '' : 's'}</button>
          {pending[view] && <span role="status" className="text-[10px] text-muted">Refreshing…</span>}
        </div>
        <details className="mx-4 mb-3 shrink-0 text-[11px] text-muted">
          <summary className="cursor-pointer rounded py-1 hover:text-ink active:bg-raised">{view === 'startup' ? 'Startup snapshot, not this chat’s live context' : 'Reconstructed context · What is included'}</summary>
          <div className="max-h-32 space-y-2 overflow-y-auto rounded-lg bg-raised px-3 py-2 leading-relaxed">{report.notices.map((notice, index) => <p key={index}>{notice}</p>)}<p>Text tokens use characters ÷ 4. Repeated passages are exact paragraphs of at least 120 characters. Repetition may be intentional.</p><p className="break-all">Source: {report.source}</p><p>Captured {new Date(report.capturedAt).toLocaleTimeString()}{report.model ? ` · ${report.model}${report.effort ? ` / ${report.effort}` : ''}` : ''}</p></div>
        </details>
        <div className="mx-4 mb-2 flex shrink-0 items-center gap-2 rounded-lg bg-input px-2.5 shadow-input focus-within:shadow-input-focus">
          <Icon name="search" size={14} /><input ref={searchField} aria-label="Search context" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search text, rules, paths…" className="min-w-0 flex-1 cursor-text bg-transparent py-2 text-xs outline-none focus-visible:outline-none! placeholder:text-muted/60" />
          {search && <button className={control} aria-label="Clear context search" onClick={() => { setSearch(''); searchField.current?.focus(); }}><Icon name="close" size={12} /></button>}
        </div>
        <div className="mx-4 mb-3 flex shrink-0 items-center gap-2 text-[11px]">
          <select aria-label="Filter context category" value={category} onChange={(event) => setCategory(event.target.value as keyof typeof categories)} className="min-w-0 max-w-[55%] cursor-pointer rounded-md bg-raised px-2 py-1.5 outline-none hover:brightness-110 active:brightness-90">{Object.entries(categories).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
          <select aria-label="Sort context" value={sort} onChange={(event) => setSort(event.target.value as typeof sort)} className="min-w-0 cursor-pointer rounded-md bg-raised px-2 py-1.5 outline-none hover:brightness-110 active:brightness-90"><option value="largest">Largest first</option><option value="order">Recorded order</option></select>
          <span className="ml-auto shrink-0 text-muted">{filtered.length} shown</span>
        </div>
        <div className="flex min-h-0 flex-1 gap-3 px-3 pb-3">
          <div className={`${block ? 'hidden @min-[760px]:flex' : 'flex'} min-h-0 w-full flex-col overflow-y-auto @min-[760px]:w-2/5`} aria-label="Context blocks">
            {duplicatesOnly && <p className="px-2 pb-2 text-[11px] leading-relaxed text-attention">≈{number.format(Math.ceil(analysis.repeatedCharacters / 4))} text tokens in repeated copies. Review their purpose before removing any.</p>}
            {!filtered.length && <p className="px-2 py-8 text-xs text-muted">{report.blocks.length ? 'No blocks match these filters.' : 'No readable text was found in this snapshot.'}</p>}
            {filtered.slice(0, limit).map((entry) => <button key={entry.id} type="button" aria-pressed={selected === entry.id} onClick={() => setSelected(entry.id)} className={`mb-1 flex shrink-0 items-start gap-2 rounded-lg px-2.5 py-2.5 text-left ${selected === entry.id ? 'bg-raised' : 'hover:bg-raised/70 active:bg-line'}`}>
              <span className="min-w-0 flex-1"><span className="block truncate text-xs font-medium">{entry.label}</span><span className="mt-1 block truncate text-[10px] text-muted">{entry.text.replace(/\s+/g, ' ').slice(0, 120)}</span><span className="mt-1.5 flex gap-2 text-[9px] text-muted/70"><span>{entry.role || 'context'}</span>{analysis.duplicateBlocks.has(entry.id) && <span className="text-attention">Repeated text</span>}{entry.truncated && <span>Shortened</span>}</span></span>
              <span className="shrink-0 text-[10px] tabular-nums text-muted">≈{number.format(Math.ceil(entry.text.length / 4))}</span>
            </button>)}
            {filtered.length > limit && <button type="button" className="my-2 shrink-0 rounded-md px-3 py-2 text-xs text-muted hover:bg-raised active:bg-line" onClick={() => setLimit((value) => value + 80)}>Show more blocks</button>}
          </div>
          <section aria-label="Context block details" className={`${block ? 'flex' : 'hidden @min-[760px]:flex'} min-h-0 min-w-0 flex-1 flex-col rounded-lg bg-input`}>
            {block ? <>
              <div className="flex shrink-0 items-center gap-1 px-2 py-1.5">
                <button type="button" aria-label="Back to context blocks" onClick={() => setSelected(undefined)} className={`${control} @min-[760px]:hidden`}><span className="rotate-180"><Icon name="arrow" /></span></button>
                <span className="min-w-0 flex-1 truncate px-1 text-xs" title={block.label}>{block.label}</span>
                <button type="button" aria-label="Copy block text" title="Copy block text" className={control} onClick={() => void action('copy', { text: block.text })}><Icon name="copy" size={13} /></button>
              </div>
              <p className="shrink-0 px-3 pb-2 text-[10px] text-muted">{block.origin} · {number.format(block.text.length)} characters{block.truncated ? ' · Shortened' : ''}</p>
              {paths.length > 0 && <details className="mx-3 mb-2 shrink-0 text-[10px] text-muted"><summary className="cursor-pointer rounded py-1 hover:text-ink active:bg-raised">Paths mentioned ({paths.length})</summary><div className="max-h-28 space-y-1 overflow-y-auto py-1">{paths.map((path) => <button type="button" key={path} title={path} onClick={() => void action('openLink', { path })} className="block w-full truncate rounded px-1 py-1 text-left text-accent hover:bg-raised active:bg-line">{path}</button>)}<p className="py-1 leading-relaxed">A path mention does not prove that its contents were loaded.</p></div></details>}
              <div className="min-h-0 flex-1 overflow-auto px-3 pb-3" tabIndex={0}>
                {block.call && <details className="mb-3 text-[10px] text-muted"><summary className="cursor-pointer rounded py-1 hover:text-ink active:bg-raised">Related tool call</summary><pre className="mt-1 cursor-text whitespace-pre-wrap break-words">{block.call}</pre></details>}
                <pre className="cursor-text select-text whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed"><Highlighted text={block.text} term={term} repeated={duplicatesOnly ? repeatedParagraphs : undefined} /></pre>
              </div>
            </> : <div className="flex h-full items-center justify-center px-6 text-center text-xs text-muted">Choose a block to inspect its text.</div>}
          </section>
        </div>
      </>}
    </div>
  </dialog>, document.body);
}

function Highlighted({ text, term, repeated }: { text: string; term: string; repeated?: Set<string> }) {
  if (!term) { return repeated ? text.split(/(\n[\t ]*\n)/).map((part, index) => repeated.has(part.trim()) ? <mark key={index} className="rounded-sm bg-attention/25 text-ink">{part}</mark> : part) : text; }
  const lower = text.toLowerCase();
  const parts = [];
  let cursor = 0;
  let index = lower.indexOf(term);
  while (index >= 0 && parts.length < 400) {
    parts.push(text.slice(cursor, index), <mark key={index} className="rounded-sm bg-attention/25 text-ink">{text.slice(index, index + term.length)}</mark>);
    cursor = index + term.length;
    index = lower.indexOf(term, cursor);
  }
  parts.push(text.slice(cursor));
  return parts;
}
