import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { request } from './bridge';
import { Icon } from './icons';
import type { Attachment, Chat } from './types';

type Workspace = { path: string; name: string; branch: string; main: boolean; locked: boolean; missing: boolean; chats: number };
type Workspaces = { entries: Workspace[]; branches: string[]; current: string; base: string; suggestedName: string; error: string };
const rowStyle = 'flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs hover:bg-surface active:bg-line focus-visible:bg-surface focus-visible:outline-none';
const inputStyle = 'mt-1.5 w-full min-w-0 rounded-lg bg-input px-3 py-2 text-xs shadow-input focus:shadow-input-focus';

/** One small checkout control handles selection, branching and cleanup without another composer row. */
export function WorkspaceMenu({ chat, label, draft, attachments }: { chat: Chat; label?: string; draft: string; attachments: Attachment[] }) {
  const trigger = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<'list' | 'create' | 'remove'>('list');
  const [data, setData] = useState<Workspaces>();
  const [name, setName] = useState('');
  const [base, setBase] = useState('HEAD');
  const [includeChanges, setIncludeChanges] = useState(false);
  const [remove, setRemove] = useState<{ path: string; blocked: string; chats: number }>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [position, setPosition] = useState({ left: 12, bottom: 40, width: 320, maxHeight: 400 });
  const established = !!chat.threadId;
  const unavailable = chat.working || chat.requests.length > 0;
  const close = () => { setOpen(false); trigger.current?.focus(); };
  const refresh = async () => {
    const result = await request<Workspaces>('workspaces');
    setData(result); setName(result.suggestedName); setBase(result.base); return result;
  };
  useEffect(() => {
    if (!open) { return; }
    setMode('list'); setError(''); setBusy(true);
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
  useEffect(() => { if (mode === 'create') { panel.current?.querySelector<HTMLInputElement>('input')?.focus(); } else { panel.current?.focus(); } }, [mode]);
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
    <button ref={trigger} type="button" aria-label={`Workspace: ${label || chat.cwd}`} title={`${label || 'Workspace'}\n${chat.cwd}`} aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen(!open)} className="flex h-7 min-w-0 max-w-32 items-center gap-1 rounded-md px-1.5 text-[11px] text-muted hover:bg-raised hover:text-ink active:bg-line active:text-accent">
      <span className="shrink-0"><Icon name="branch" size={13} /></span><span className="truncate">{label || chat.cwd.split('/').pop() || 'Workspace'}</span><span className="shrink-0"><Icon name="down" size={10} /></span>
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
        <span className="flex-1 text-xs font-medium">{mode === 'create' ? 'New worktree' : mode === 'remove' ? 'Remove worktree' : 'Workspace'}</span>
        <button aria-label="Close workspace menu" onClick={close} className="rounded p-1 text-muted hover:bg-surface hover:text-ink active:bg-line"><Icon name="close" size={13} /></button>
      </div>
      {error && <p role="alert" className="mb-2 rounded-lg bg-red-400/10 px-2.5 py-2 text-xs text-red-400">{error}</p>}
      {mode === 'list' && <>
        <p className="break-all px-2.5 pb-2 text-[11px] text-muted">{chat.cwd}</p>
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
              {!workspace.main && <button disabled={busy} aria-label={`Remove worktree ${workspace.name}`} title="Remove worktree" onClick={() => { setBusy(true); setError(''); void request<{ path: string; blocked: string; chats: number }>('inspectWorktree', { path: workspace.path }).then((value) => { setRemove(value); setMode('remove'); }).catch((error: Error) => setError(error.message)).finally(() => setBusy(false)); }} className="mr-1 flex size-7 shrink-0 items-center justify-center rounded-md text-muted hover:bg-red-400/10 hover:text-red-400 active:bg-red-400/20"><Icon name="trash" size={13} /></button>}
            </div>;
          })}</div>
        </>}
        <div className="mt-2 flex flex-wrap gap-1 rounded-lg bg-surface p-1">
          <button disabled={busy || !data?.entries.length} onClick={() => void action('workspaceReview')} className={`${rowStyle} w-auto flex-1 justify-center whitespace-nowrap`}><Icon name="code" size={13} />Review</button>
          <button disabled={busy} onClick={() => void action('workspaceTerminal')} className={`${rowStyle} w-auto flex-1 justify-center whitespace-nowrap`}><Icon name="terminal" size={13} />Terminal</button>
          <button disabled={busy} onClick={() => void action('workspaceProject')} className={`${rowStyle} w-auto flex-1 justify-center whitespace-nowrap`} title="Open this checkout as a separate IDEA project"><Icon name="newTab" size={13} />IDEA</button>
        </div>
      </>}
      {mode === 'create' && <form className="space-y-3 px-2 pb-2" onSubmit={(event) => { event.preventDefault(); if (!busy) { void change({ create: true, name, base, includeChanges }); } }}>
        <label className="block text-[11px] text-muted">Name<input disabled={busy} required pattern="[a-z0-9][a-z0-9-]{0,47}" maxLength={48} aria-label="Worktree name" value={name} onChange={(event) => setName(event.target.value)} className={inputStyle} autoComplete="off" /></label>
        <label className="block text-[11px] text-muted">Start from<input disabled={busy} required aria-label="Starting branch or commit" value={base} list="workspace-branches" onChange={(event) => setBase(event.target.value)} className={inputStyle} autoComplete="off" /><datalist id="workspace-branches">{data?.branches.map((branch) => <option key={branch} value={branch} />)}</datalist></label>
        <label className="flex cursor-pointer items-start gap-2 rounded-lg py-1 text-xs text-ink"><input type="checkbox" disabled={busy} checked={includeChanges} onChange={(event) => setIncludeChanges(event.target.checked)} className="mt-0.5 accent-accent" /><span>Include current local changes<span className="mt-1 block text-[11px] leading-relaxed text-muted">Copies edits and untracked files. Ignored files stay here.</span></span></label>
        <p className="break-all text-[10px] leading-relaxed text-muted">Branch: codex/{name || '…'}<br />{data?.entries.find((entry) => entry.main)?.path}.worktrees/{name || '…'}</p>
        <button disabled={busy || unavailable || !name || !base} type="submit" className="flex w-full items-center justify-center gap-2 rounded-lg bg-accent px-3 py-2 text-xs font-medium text-composer enabled:hover:brightness-110 enabled:active:brightness-90">{busy ? 'Creating…' : established ? 'Create and continue' : 'Create worktree'}{established && <Icon name="newTab" size={13} />}</button>
      </form>}
      {mode === 'remove' && remove && <div className="space-y-3 px-2 pb-2 text-xs">
        <p className="break-all text-muted">{remove.path}</p>
        {remove.blocked ? <p role="status">{remove.blocked}</p> : <><p>Remove this directory? Its Git branch and {remove.chats || 'saved'} chat{remove.chats === 1 ? '' : 's'} will be kept.</p><p className="text-[11px] text-muted">Chats that used it will need another checkout before sending.</p>
          <button disabled={busy} onClick={() => { setBusy(true); setError(''); void request('removeWorktree', { path: remove.path }).then(refresh).then(() => setMode('list')).catch((error: Error) => setError(error.message)).finally(() => setBusy(false)); }} className="w-full rounded-lg bg-red-400/15 px-3 py-2 font-medium text-red-400 hover:bg-red-400/25 active:bg-red-400/35">{busy ? 'Removing…' : 'Remove directory'}</button></>}
      </div>}
    </div>, document.body)}
  </>;
}
