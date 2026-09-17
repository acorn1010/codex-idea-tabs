import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { request } from './bridge';
import { Icon } from './icons';
import type { Attachment, Chat } from './types';

type Workspace = { path: string; name: string; branch: string; main: boolean; locked: boolean; missing: boolean; chats: number };
type Repository = { path: string; name: string; project: boolean };
type Workspaces = { repositories?: Repository[]; repository?: string; projectPath?: string; sharedGuidanceFolder?: string; entries: Workspace[]; branches: string[]; current: string; base: string; suggestedName: string; error: string };
type Removal = { path: string; blocked: string; chats: number; files: { path: string; status: string }[] };
const rowStyle = 'flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs hover:bg-surface active:bg-line focus-visible:bg-surface focus-visible:outline-none';
const inputStyle = 'mt-1.5 w-full min-w-0 rounded-lg bg-input px-3 py-2 text-xs shadow-input focus:shadow-input-focus';

/** Keep branch suggestions inside JCEF instead of relying on a native datalist popup. */
function StartingBranch({ value, branches, disabled, onChange }: { value: string; branches: string[]; disabled: boolean; onChange: (value: string) => void }) {
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [active, setActive] = useState(-1);
  const matches = branches.filter(branch => branch.toLowerCase().includes(search.toLowerCase()));
  const choose = (branch: string) => { onChange(branch); setOpen(false); input.current?.focus(); };
  useEffect(() => { list.current?.querySelector('[data-active="true"]')?.scrollIntoView({ block: 'nearest' }); }, [active]);
  return <div onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) { setOpen(false); } }}>
    <label htmlFor={id} className="block text-[11px] text-muted">Start from</label>
    <div className="relative mt-1.5">
      <input ref={input} id={id} role="combobox" aria-label="Starting branch or commit" aria-expanded={open && !disabled} aria-controls={`${id}-branches`} aria-autocomplete="list" aria-activedescendant={open && active >= 0 && matches[active] ? `${id}-branch-${active}` : undefined}
        disabled={disabled} required value={value} autoComplete="off" className={`${inputStyle} mt-0 pr-8 text-xs text-ink`}
        onChange={event => { onChange(event.target.value); setSearch(event.target.value); setActive(-1); setOpen(true); }}
        onKeyDown={event => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault(); event.stopPropagation();
            if (!open) { setSearch(''); setActive(event.key === 'ArrowDown' ? 0 : branches.length - 1); setOpen(true); }
            else { setActive(current => matches.length ? (current + (event.key === 'ArrowDown' ? 1 : current < 0 ? 0 : -1) + matches.length) % matches.length : -1); }
          } else if (event.key === 'Enter' && open) {
            event.preventDefault(); event.stopPropagation();
            if (active >= 0 && matches[active]) { choose(matches[active]); } else { setOpen(false); }
          } else if (event.key === 'Escape' && open) {
            event.preventDefault(); event.stopPropagation(); setOpen(false);
          } else if (event.key === 'Tab') { setOpen(false); }
        }} />
      <button type="button" disabled={disabled} aria-label="Show starting branches" aria-expanded={open && !disabled} aria-controls={`${id}-branches`}
        onClick={() => { setOpen(!open); setSearch(''); setActive(-1); input.current?.focus(); }}
        className="absolute inset-y-0 right-0 flex w-8 items-center justify-center rounded-r-lg text-muted hover:bg-surface hover:text-ink"><Icon name="down" size={12} /></button>
    </div>
    {open && !disabled && <div ref={list} id={`${id}-branches`} role="listbox" aria-label="Starting branches" className="mt-1 max-h-40 overflow-y-auto rounded-lg bg-input p-1">
      {matches.map((branch, index) => <button type="button" role="option" id={`${id}-branch-${index}`} key={branch} tabIndex={-1} aria-selected={branch === value} data-active={index === active}
        onMouseDown={event => event.preventDefault()} onClick={() => choose(branch)}
        className={`${rowStyle} break-all ${index === active ? 'bg-surface text-accent' : 'text-ink'}`}>{branch}</button>)}
      {!matches.length && <p role="status" className="px-2.5 py-2 text-xs text-muted">No matching branches. You can still use a commit or Git reference.</p>}
    </div>}
  </div>;
}

/** One small checkout control handles selection, branching and cleanup without another composer row. */
export function WorkspaceMenu({ chat, label, draft, attachments }: { chat: Chat; label?: string; draft: string; attachments: Attachment[] }) {
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<'list' | 'create' | 'remove' | 'repositories'>('list');
  const [data, setData] = useState<Workspaces>();
  const [name, setName] = useState('');
  const [repositorySearch, setRepositorySearch] = useState('');
  const [base, setBase] = useState('HEAD');
  const [includeChanges, setIncludeChanges] = useState(false);
  const [remove, setRemove] = useState<Removal & { branch: string }>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [position, setPosition] = useState({ left: 12, bottom: 40, width: 320, maxHeight: 400 });
  const repositories = data?.repositories || [];
  const selectedRepository = repositories.find(repository => repository.path === data?.repository);
  const hasRepositoryChoice = repositories.length > 1 || (!!data?.projectPath && repositories[0]?.path !== data.projectPath);
  const visibleRepositories = repositories.filter(repository => `${repository.name} ${repository.path}`.toLowerCase().includes(repositorySearch.trim().toLowerCase()));
  const compactLabel = label?.replace(/(^| · )codex\//, '$1');
  const established = !!chat.threadId;
  const unavailable = chat.working || chat.requests.length > 0;
  const close = () => { setOpen(false); trigger.current?.focus(); };
  const refresh = async () => {
    const result = await request<Workspaces>('workspaces');
    setData(result); setName(result.suggestedName); setBase(result.base); return result;
  };
  useEffect(() => {
    if (!open) { return; }
    setMode('list'); setError(''); setRepositorySearch(''); setBusy(true);
    void refresh().catch((error: Error) => setError(error.message)).finally(() => setBusy(false));
    const place = () => {
      const rect = trigger.current!.getBoundingClientRect();
      const width = Math.min(360, window.innerWidth - 24);
      setPosition({ left: Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)), bottom: window.innerHeight - rect.top + 6, width, maxHeight: Math.max(120, rect.top - 18) });
    };
    place();
    const outside = (event: MouseEvent) => { if (!panel.current?.contains(event.target as Node) && !trigger.current?.contains(event.target as Node)) { setOpen(false); } };
    document.addEventListener('mousedown', outside); window.addEventListener('resize', place);
    const timer = window.setTimeout(() => panel.current?.focus(), 0);
    return () => { window.clearTimeout(timer); document.removeEventListener('mousedown', outside); window.removeEventListener('resize', place); };
  }, [open]);
  useEffect(() => { if (mode === 'create' || mode === 'repositories') { panel.current?.querySelector<HTMLInputElement>('input')?.focus(); } else { panel.current?.focus(); } }, [mode]);
  const change = async (params: object) => {
    setBusy(true); setError('');
    try { await request('changeWorkspace', { ...params, draft, attachments }); close(); }
    catch (error) { setError((error as Error).message); }
    finally { setBusy(false); }
  };
  const action = async (method: string) => {
    setBusy(true); setError('');
    try { await request(method); close(); }
    catch (error) { setError((error as Error).message); }
    finally { setBusy(false); }
  };
  return <>
    <button ref={trigger} type="button" aria-label={`Workspace: ${label || chat.cwd}`} title={`${label || 'Workspace'}\n${chat.cwd}`} aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen(!open)} className="flex h-7 min-w-7 max-w-32 items-center justify-center gap-1 rounded-md px-1 @min-[640px]/composer:max-w-56 @min-[900px]/composer:max-w-96 text-[11px] text-muted hover:bg-raised hover:text-ink active:bg-line active:text-accent">
      <span className="shrink-0"><Icon name="branch" size={13} /></span><span className="truncate @max-[400px]/composer:hidden">{compactLabel || chat.cwd.split('/').pop() || 'Workspace'}</span><span className="shrink-0 @max-[400px]/composer:hidden"><Icon name="down" size={10} /></span>
    </button>
    {open && createPortal(<div ref={panel} role="dialog" aria-label="Workspace" tabIndex={-1} style={position} className="fixed z-60 overflow-y-auto rounded-xl bg-raised p-2 shadow-2xl outline-none" onKeyDown={(event) => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); }
      if (event.key === 'Tab') {
        const controls = Array.from(panel.current!.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), select:not(:disabled)'));
        const first = controls[0], last = controls[controls.length - 1];
        if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }
    }}>
      <div className="flex items-center gap-2 px-2 pb-2 pt-1">
        {mode !== 'list' && <button aria-label="Back to workspaces" onClick={() => { setMode('list'); setError(''); }} className="rounded p-1 text-muted hover:bg-surface hover:text-ink active:bg-line"><span className="block rotate-180"><Icon name="arrow" size={14} /></span></button>}
        <span className="flex-1 text-xs font-medium">{mode === 'create' ? 'New worktree' : mode === 'remove' ? 'Remove worktree' : mode === 'repositories' ? 'Choose repository' : 'Workspace'}</span>
        <button aria-label="Close workspace menu" onClick={close} className="rounded p-1 text-muted hover:bg-surface hover:text-ink active:bg-line"><Icon name="close" size={13} /></button>
      </div>
      {error && <p role="alert" className="mb-2 rounded-lg bg-red-400/10 px-2.5 py-2 text-xs text-red-400">{error}</p>}
      {mode === 'list' && <>
        {!!data?.sharedGuidanceFolder && <button type="button" onClick={() => void action('settings')} className={`${rowStyle} text-muted`} title={data.sharedGuidanceFolder}>
          <span className="min-w-0"><span className="block text-[11px]">Shared guidance</span><span className="block truncate text-[10px]">{data.sharedGuidanceFolder}</span></span>
        </button>}
        {hasRepositoryChoice && <button disabled={busy || unavailable} aria-label="Choose repository" onClick={() => setMode('repositories')} className={`${rowStyle} mb-1 min-w-0`}>
          <span className="shrink-0"><Icon name="branch" size={14} /></span>
          <span className="min-w-0 flex-1 text-left"><span className="block text-[10px] text-muted">Repository</span><span className="block truncate font-medium">{selectedRepository?.name || 'Project folder'}</span></span><Icon name="down" size={12} />
        </button>}
        <p className="break-all px-2.5 pb-2 text-[11px] text-muted">{chat.cwd}</p>
        {data && !data.repository && data.projectPath && <p className="px-2.5 pb-2 text-xs text-muted">Choose a repository to create a worktree.</p>}
        {data?.error && <p className="px-2.5 pb-2 text-xs text-muted">{data.error}</p>}
        {busy && <p role="status" className="px-2.5 py-2 text-xs text-muted">Loading…</p>}
        {!!data?.entries.length && <>
          <button disabled={busy || unavailable} title={unavailable ? 'Let this chat finish before changing its worktree' : undefined} onClick={() => { setMode('create'); setIncludeChanges(false); }} className={`${rowStyle} text-accent`}><Icon name="plus" size={14} /><span className="flex-1">{established ? 'Continue in new worktree' : 'New worktree'}</span>{established && <Icon name="newTab" size={13} />}</button>
          {established && <p className="px-2.5 py-1.5 text-[11px] text-muted">Another checkout opens in a new chat tab.</p>}
          <div className="my-1 max-h-52 overflow-y-auto" aria-label="Available worktrees">{data.entries.map((workspace) => {
            const selected = chat.cwd === workspace.path || chat.cwd.startsWith(`${workspace.path}/`);
            return <div key={workspace.path} className="flex items-center gap-1">
              <button disabled={busy || unavailable || workspace.missing} aria-current={selected ? 'true' : undefined} title={workspace.path} onClick={() => selected ? close() : void change({ path: workspace.path })} className={`${rowStyle} min-w-0 flex-1`}>
                <span className={`w-4 shrink-0 ${selected ? 'text-accent' : 'text-muted'}`}><Icon name={selected ? 'check' : 'branch'} size={13} /></span>
                <span className="min-w-0 flex-1"><span className="block truncate">{workspace.branch || workspace.name}</span><span className="block truncate text-[10px] text-muted">{workspace.main ? 'Primary checkout' : workspace.name}{workspace.missing ? ' · Missing' : workspace.locked ? ' · Locked' : ''}{workspace.chats ? ` · ${workspace.chats} chat${workspace.chats === 1 ? '' : 's'}` : ''}</span></span>
                {established && !selected && <Icon name="newTab" size={12} />}
              </button>
              {!workspace.main && <button disabled={busy} aria-label={`Remove worktree ${workspace.name}`} title="Remove worktree" onClick={() => { setBusy(true); setError(''); void request<Removal>('inspectWorktree', { path: workspace.path }).then((value) => { setRemove({ ...value, files: value.files || [], branch: workspace.branch }); setMode('remove'); }).catch((error: Error) => setError(error.message)).finally(() => setBusy(false)); }} className="mr-1 flex size-7 shrink-0 items-center justify-center rounded-md text-muted hover:bg-red-400/10 hover:text-red-400 active:bg-red-400/20"><Icon name="trash" size={13} /></button>}
            </div>;
          })}</div>
        </>}
        <div className="mt-2 flex flex-wrap gap-1 rounded-lg bg-surface p-1">
          <button disabled={busy || !data?.entries.length} onClick={() => void action('workspaceReview')} className={`${rowStyle} w-auto flex-1 justify-center whitespace-nowrap`}><Icon name="code" size={13} />Review</button>
          <button disabled={busy} onClick={() => void action('workspaceTerminal')} className={`${rowStyle} w-auto flex-1 justify-center whitespace-nowrap`}><Icon name="terminal" size={13} />Terminal</button>
          <button disabled={busy} onClick={() => void action('workspaceProject')} className={`${rowStyle} w-auto flex-1 justify-center whitespace-nowrap`} title="Open this checkout as a separate IDEA project"><Icon name="newTab" size={13} />IDEA</button>
        </div>
      </>}
      {mode === 'repositories' && <div className="px-1 pb-1">
        <input aria-label="Search repositories" placeholder="Search repositories…" value={repositorySearch} onChange={event => setRepositorySearch(event.target.value)} className={`${inputStyle} mb-2 w-full`} />
        <div className="max-h-64 overflow-y-auto" aria-label="Repositories">
          {data?.projectPath && !repositories.some(repository => repository.project) && !repositorySearch.trim() && <button disabled={busy || unavailable} aria-current={chat.cwd === data.projectPath ? 'true' : undefined} onClick={() => chat.cwd === data.projectPath ? close() : void change({ path: data.projectPath })} className={rowStyle}>
            <span className="min-w-0 text-left"><span className="block font-medium">Project folder</span><span className="block truncate text-[10px] text-muted">Current IDEA working directory</span></span>
          </button>}
          {visibleRepositories.map(repository => <button key={repository.path} disabled={busy || unavailable} aria-label={`Use repository ${repository.name}`} aria-current={data?.repository === repository.path ? 'true' : undefined} title={repository.path} onClick={() => chat.cwd === repository.path ? close() : void change({ path: repository.path })} className={rowStyle}>
            <span className={`w-4 shrink-0 ${data?.repository === repository.path ? 'text-accent' : 'text-muted'}`}><Icon name={data?.repository === repository.path ? 'check' : 'branch'} size={13} /></span>
            <span className="min-w-0 flex-1 text-left"><span className="block truncate font-medium">{repository.name}</span><span className="block truncate text-[10px] text-muted">{repository.project ? 'Project root' : repository.path}</span></span>
          </button>)}
          {!visibleRepositories.length && !!repositorySearch.trim() && <p role="status" className="px-2.5 py-3 text-xs text-muted">No matching repositories</p>}
        </div>
        {established && <p className="px-2.5 pt-2 text-[11px] text-muted">Another checkout opens in a new chat tab. Your draft comes with it.</p>}
      </div>}
      {mode === 'create' && <form className="space-y-3 px-2 pb-2" onSubmit={(event) => { event.preventDefault(); if (!busy) { void change({ create: true, name, base, includeChanges }); } }}>
        {selectedRepository && <p className="truncate text-xs font-medium" title={selectedRepository.path}>{selectedRepository.name}</p>}
        <label className="block text-[11px] text-muted">Name<input disabled={busy} required pattern="[a-z0-9][a-z0-9-]{0,47}" maxLength={48} aria-label="Worktree name" value={name} onChange={(event) => setName(event.target.value)} className={inputStyle} autoComplete="off" /></label>
        <StartingBranch value={base} branches={data?.branches || []} disabled={busy} onChange={setBase} />
        <label className="flex cursor-pointer items-start gap-2 rounded-lg py-1 text-xs text-ink"><input type="checkbox" disabled={busy} checked={includeChanges} onChange={(event) => setIncludeChanges(event.target.checked)} className="mt-0.5 accent-accent" /><span>Include current local changes<span className="mt-1 block text-[11px] leading-relaxed text-muted">Copies edits and untracked files. Ignored files stay here.</span></span></label>
        <p className="break-all text-[10px] leading-relaxed text-muted">Branch: codex/{name || '…'}<br />{data?.entries.find((entry) => entry.main)?.path}.worktrees/{name || '…'}</p>
        <button disabled={busy || unavailable || !name || !base} type="submit" className="flex w-full items-center justify-center gap-2 rounded-lg bg-accent px-3 py-2 text-xs font-medium text-composer enabled:hover:brightness-110 enabled:active:brightness-90">{busy ? 'Creating…' : established ? 'Create and continue' : 'Create worktree'}{established && <Icon name="newTab" size={13} />}</button>
      </form>}
      {mode === 'remove' && remove && <div className="space-y-3 px-2 pb-2 text-xs">
        <p className="break-all text-muted">{remove.path}</p>
        {remove.blocked ? <p role="status">{remove.blocked}</p> : <><p>Remove this worktree folder and its files? {remove.branch ? <>The local branch <strong className="break-all font-medium text-ink">{remove.branch}</strong> and all its commits will be kept. </> : ''}{remove.chats || 'Saved'} chat{remove.chats === 1 ? '' : 's'} will also be kept.</p><p className="text-[11px] text-muted">Chats that used it will need another checkout before sending.</p>
          {remove.files.length > 0 && <>
            <p className="text-red-400">These local changes and files will be permanently deleted. They are not saved by keeping the branch.</p>
            <ul aria-label="Local changes and files to discard" className="max-h-40 space-y-1 overflow-y-auto rounded-lg bg-input p-2">
              {remove.files.slice(0, 100).map((file, index) => <li key={index} className="flex items-start gap-2"><span className="w-16 shrink-0 text-[10px] text-muted">{file.status}</span><span className="min-w-0 whitespace-pre-wrap break-all text-[11px]">{file.path}</span></li>)}
            </ul>
            {remove.files.length > 100 && <p className="text-[11px] text-muted">And {remove.files.length - 100} more entries. All local files in this worktree will be deleted.</p>}
          </>}
          <div className="flex flex-wrap gap-2">
            <button type="button" disabled={busy} onClick={close} className="flex-1 rounded-lg bg-surface px-3 py-2 hover:bg-line">Cancel</button>
            <button type="button" disabled={busy} onClick={() => { setBusy(true); setError(''); void request('removeWorktree', { path: remove.path, discardChanges: remove.files.length > 0 }).then(() => { setData(current => current ? { ...current, entries: current.entries.filter(entry => entry.path !== remove.path) } : current); setMode('list'); }).catch((error: Error) => setError(error.message)).finally(() => setBusy(false)); }} className="flex-auto rounded-lg bg-red-400/15 px-3 py-2 font-medium text-red-400 hover:bg-red-400/25 active:bg-red-400/35">{busy ? 'Removing…' : remove.files.length ? 'Discard changes and remove' : 'Remove directory'}</button>
          </div></>}
      </div>}
    </div>, document.body)}
  </>;
}
