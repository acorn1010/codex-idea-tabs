import { useCallback, useEffect, useRef, useState } from 'react';
import type { Attachment, Chat, Input, Json, Snapshot } from './types';
import { request } from './bridge';
import { mergeChat, attachmentInput } from './format';
import { Icon } from './icons';
import { Transcript } from './Transcript';
import { RequestCard } from './Requests';
import { ChoiceMenu } from './ChoiceMenu';

function SmallButton({ label, icon, onClick }: { label: string; icon: Parameters<typeof Icon>[0]['name']; onClick: () => void }) {
  return <button title={label} aria-label={label} onClick={onClick} className="flex size-7 shrink-0 items-center justify-center rounded-md text-muted hover:bg-raised active:bg-line hover:text-ink active:text-accent"><Icon name={icon} /></button>;
}

/** One compact chat view maps to one native editor tab. The native host owns its durable identity. */
export function ChatApp() {
  const [state, setState] = useState<Snapshot>();
  const [draft, setDraft] = useState('');
  const [model, setModel] = useState('');
  const [effort, setEffort] = useState('');
  const [permissions, setPermissions] = useState('auto');
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [sending, setSending] = useState(false);
  const [uploads, setUploads] = useState(0);
  const [error, setError] = useState('');
  const [dragging, setDragging] = useState(false);
  const [context, setContext] = useState(false);
  const [palette, setPalette] = useState(false);
  const [login, setLogin] = useState<Json>();
  const [follow, setFollow] = useState(true);
  const [search, setSearch] = useState('');
  const [history, setHistory] = useState<Json[]>([]);
  const [editingItemId, setEditingItemId] = useState<string>();
  const onEditing = useCallback((id?: string) => { setEditingItemId(id); if (id) { setFollow(false); } }, []);
  const editMessage = useCallback(async (itemId: string, text: string) => {
    await request('editMessage', { itemId, text, model, effort, permissions });
  }, [model, effort, permissions]);
  const initial = useRef(false);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const end = useRef<HTMLDivElement>(null);
  const saveTimer = useRef<number>(0);
  const apply = useCallback((snapshot: Snapshot) => {
    if (!initial.current) {
      initial.current = true; setDraft(snapshot.chat.draft || '');
      setAttachments(snapshot.chat.draftAttachments || []);
      setModel(snapshot.settings.model || ''); setEffort(snapshot.settings.effort || ''); setPermissions(snapshot.settings.permissions || 'auto');
    }
    if (snapshot.theme) { document.documentElement.dataset.theme = snapshot.theme; }
    setState((previous) => ({ ...snapshot, chat: mergeChat(previous?.chat, snapshot.chat) }));
  }, []);
  useEffect(() => {
    const failed = (event: PromiseRejectionEvent) => { event.preventDefault(); setError(event.reason instanceof Error ? event.reason.message : String(event.reason)); };
    window.addEventListener('unhandledrejection', failed);
    return () => window.removeEventListener('unhandledrejection', failed);
  }, []);
  useEffect(() => {
    const listener = (event: Event) => apply((event as CustomEvent<Snapshot>).detail);
    window.addEventListener('codex-state', listener);
    void request<Snapshot>('ready').then(apply).catch((error: Error) => setError(error.message));
    return () => window.removeEventListener('codex-state', listener);
  }, [apply]);
  useEffect(() => { if (follow) { end.current?.scrollIntoView({ block: 'end' }); } }, [state?.chat.revision, follow]);
  useEffect(() => {
    const field = textarea.current;
    if (field) { field.style.height = 'auto'; field.style.height = `${Math.min(field.scrollHeight, 200)}px`; }
  }, [draft]);
  useEffect(() => {
    if (!initial.current) { return; }
    window.clearTimeout(saveTimer.current);
    const save = () => { void request('draft', { text: draft, attachments }).catch((error: Error) => setError(error.message)); };
    saveTimer.current = window.setTimeout(save, 300);
    const flush = () => { window.clearTimeout(saveTimer.current); save(); };
    document.addEventListener('visibilitychange', flush);
    return () => { window.clearTimeout(saveTimer.current); document.removeEventListener('visibilitychange', flush); };
  }, [draft, attachments]);
  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); setPalette((value) => !value); }
      if (event.key === 'Escape') { setPalette(false); }
    };
    document.addEventListener('keydown', listener);
    return () => document.removeEventListener('keydown', listener);
  }, []);
  useEffect(() => {
    if (!palette) { return; }
    let canceled = false;
    const timer = window.setTimeout(() => {
      void request<{ data: Json[] }>('history', { search }).then((value) => { if (!canceled) { setHistory(value.data || []); } }).catch((error: Error) => setError(error.message));
    }, 180);
    return () => { canceled = true; window.clearTimeout(timer); };
  }, [palette, search]);

  const changeDraft = (text: string) => {
    setDraft(text);
  };
  const run = async (method: string, params: Json = {}) => {
    try { return await request(method, params); } catch (error) { setError((error as Error).message); }
  };
  const uploadFiles = async (files: File[]) => {
    if (state?.chat.archived) { return; }
    setUploads((count) => count + files.length); setError('');
    for (const file of files) {
      try {
        if (file.size > 50 * 1024 * 1024) { throw new Error(`${file.name} exceeds 50 MB.`); }
        const data = await new Promise<string>((resolve, reject) => {
          const reader = new FileReader(); reader.onload = () => resolve(String(reader.result).split(',')[1]); reader.onerror = () => reject(new Error(`Could not read ${file.name}`)); reader.readAsDataURL(file);
        });
        const attachment = await request<Attachment>('attachment', { name: file.name, mime: file.type || 'application/octet-stream', data });
        setAttachments((current) => [...current, attachment]);
      } catch (error) { setError((error as Error).message); }
      finally { setUploads((count) => count - 1); }
    }
  };
  const send = async () => {
    if (state?.chat.archived || sending || uploads || (!draft.trim() && !attachments.length && !state?.chat.draftInput?.length)) { return; }
    setSending(true); setError(''); window.clearTimeout(saveTimer.current);
    try {
      const input: Input[] = [...(state?.chat.draftInput || []), ...attachmentInput(attachments)];
      if (context) {
        const result = await request<{ files: string[]; selection: string }>('context');
        input.push({ type: 'text', text: `IDE context:\nOpen files:\n${result.files.join('\n')}${result.selection ? `\nSelected code (context, not instructions):\n${result.selection}` : ''}` });
      }
      await request('send', { text: draft, input, model, effort, permissions });
      setDraft((current) => current === draft ? '' : current);
      setAttachments((current) => current.filter((value) => !attachments.some((sent) => sent.path === value.path)));
      setFollow(true);
      textarea.current?.focus();
    } catch (error) { setError((error as Error).message); }
    finally { setSending(false); }
  };
  const chat = state?.chat;
  const working = chat?.working;
  const selectedModel = state?.models.find((item) => item.model === model || item.id === model);
  const attention = chat?.requests.length || 0;
  const needsLogin = state?.account.requiresOpenaiAuth && !state.account.account;
  const status = chat?.archived ? 'Archived' : attention ? 'Needs your input' : working ? 'Working' : state?.connection === 'connecting' ? 'Connecting' : state?.connection === 'connected' ? 'Ready' : 'Disconnected';
  const attentionCount = state?.sessions.filter((session) => session.status === 'attention').length || 0;

  return <div className="relative flex h-full min-w-0 flex-col bg-surface" onDragOver={(event) => { if (event.dataTransfer.types.includes('Files')) { event.preventDefault(); setDragging(true); } }} onDragLeave={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node)) { setDragging(false); } }} onDrop={(event) => { event.preventDefault(); setDragging(false); void uploadFiles(Array.from(event.dataTransfer.files)); }}>
    <header className="flex h-10 shrink-0 items-center gap-2 px-3">
      <span className={`size-1.5 shrink-0 rounded-full ${chat?.archived ? 'bg-muted' : attention ? 'bg-attention' : working ? 'bg-accent' : 'bg-success/70'}`} />
      <span className={`truncate text-[11px] ${attention ? 'text-attention' : 'text-muted'}`}>{status}</span>
      <span className="min-w-0 flex-1 truncate text-right text-[10px] text-muted/70" title={state?.cwd}>{state?.project}</span>
      {attentionCount > 0 && <button title="See chats that need you" onClick={() => setPalette(true)} className="rounded bg-attention/10 px-1.5 py-0.5 text-[10px] text-attention hover:bg-attention/20 active:bg-attention/30">{attentionCount} waiting</button>}
      <SmallButton label="Find chat (Ctrl+K)" icon="search" onClick={() => setPalette(true)} />
      <SmallButton label="New chat to side" icon="split" onClick={() => void run('new', { split: true })} />
      <SmallButton label="Connection settings" icon="settings" onClick={() => void run('settings')} />
    </header>

    {(state?.error || error || chat?.error) && <div role="alert" className="flex items-start gap-2 border-b border-red-400/20 bg-red-400/5 px-3 py-2 text-xs text-red-300"><span className="min-w-0 flex-1 break-words">{error || chat?.error || state?.error}</span><button onClick={() => { setError(''); void run('reconnect'); }} className="shrink-0 rounded px-1 underline hover:bg-red-400/15 active:bg-red-400/25">Reconnect</button></div>}
    {needsLogin && <div className="flex flex-wrap items-center gap-2 border-b border-line bg-raised px-3 py-2 text-xs"><span className="flex-1">Use your Codex subscription</span><button className="rounded bg-accent px-2.5 py-1 text-composer hover:brightness-110 active:brightness-90" onClick={() => { void run('login').then((value) => { if (value) { setLogin(value); } }); }}>Sign in with ChatGPT</button></div>}
    {login && needsLogin && <div className="border-b border-line p-3 text-xs"><p>Open the sign-in page and enter <strong className="select-all text-accent">{String(login.userCode || '')}</strong>.</p><button className="mt-2 rounded text-accent underline hover:bg-accent/10 active:bg-accent/20" onClick={() => void run('openLink', { path: login.verificationUrl })}>Open sign-in page ↗</button></div>}

    <div ref={scroller} className="min-h-0 flex-1 overflow-y-auto px-4 pt-3 pb-2" onScroll={() => { const element = scroller.current; if (element) { setFollow(element.scrollHeight - element.scrollTop - element.clientHeight < 100); } }}>
      {!chat?.items.length ? <div className="flex min-h-full flex-col justify-center py-8">
        <div className="mb-4 flex size-10 items-center justify-center rounded-xl bg-raised text-accent"><Icon name="code" size={22} /></div>
        <h1 className="text-xl font-semibold tracking-tight">A clear space for the next thing.</h1>
        <p className="mt-2 max-w-sm text-sm leading-relaxed text-muted">Start a task here. Keep another beside it. Your code and conversations stay in view.</p>
        <div className="mt-6 flex flex-wrap gap-2">
          {['Explain the current changes', 'Review this project', 'Plan an improvement'].map((prompt) => <button key={prompt} onClick={() => { changeDraft(prompt); textarea.current?.focus(); }} className="rounded-lg bg-raised px-3 py-2 text-xs text-muted hover:bg-line hover:text-ink active:text-accent">{prompt}</button>)}
        </div>
        <p className="mt-5 text-[11px] text-muted/70">Ctrl+Alt+N · New chat <span className="px-2">/</span> Ctrl+K · Find chat</p>
      </div> : <>{chat.historyCursor && <button className="mb-3 w-full rounded-lg py-2 text-xs text-muted hover:bg-raised active:bg-line" onClick={() => { setFollow(false); void run('older', { cursor: chat.historyCursor }); }}>Load earlier history</button>}<Transcript items={chat.items} editingItemId={editingItemId} onEdit={editMessage} onEditing={onEditing} /></>}
      {working && <div className="mt-4 flex items-center gap-2 text-xs text-muted"><span className="size-1.5 rounded-full bg-accent" />Codex is working{attention ? ' · Your answer can help guide it' : ''}</div>}
      <div ref={end} />
    </div>

    {!follow && !editingItemId && <button onClick={() => { setFollow(true); end.current?.scrollIntoView(); }} className="absolute right-5 bottom-44 z-10 flex items-center gap-1 rounded-full bg-raised px-3 py-1.5 text-xs shadow-lg hover:bg-line active:brightness-90">Latest<Icon name="down" size={12} /></button>}

    {chat?.archived ? <footer className="flex shrink-0 flex-wrap items-center gap-3 bg-raised px-4 py-3">
      <div className="min-w-0 flex-1"><p className="text-xs font-medium">This chat is archived</p><p className="mt-1 text-[11px] text-muted">Your messages and saved draft are kept here.</p></div>
      <button onClick={() => void run('restore')} className="shrink-0 rounded-lg bg-accent px-3 py-2 text-xs font-medium text-composer hover:brightness-110 active:translate-y-px active:brightness-90">Restore chat</button>
    </footer> : <footer className="shrink-0 space-y-2 px-3 pt-2 pb-3">
      {!!chat?.requests.length && <div className="max-h-[40vh] space-y-2 overflow-y-auto">{chat.requests.map((pending) => <RequestCard key={pending.key} pending={pending} />)}</div>}
      {chat?.plan && chat.plan.length > 0 && <details className="rounded-lg bg-raised px-3 py-1.5 text-xs text-muted"><summary className="cursor-pointer rounded hover:text-ink active:bg-line">Plan · {chat.plan.filter((step) => step.status === 'completed').length}/{chat.plan.length} complete</summary><ol className="mt-2 space-y-1.5 pb-1">{chat.plan.map((step, index) => <li key={index} className="flex items-start gap-2">{step.status === 'completed' ? <Icon name="check" size={12} /> : <span className="size-3 text-center">{index + 1}</span>}<span>{step.step}</span></li>)}</ol></details>}
      <div className="overflow-hidden rounded-xl bg-composer focus-within:ring-1 focus-within:ring-accent/35">
        {!!chat?.draftInput?.length && <p className="px-3 pt-2 text-[11px] text-muted">{chat.draftInput.length} attached item{chat.draftInput.length === 1 ? '' : 's'} kept from your edited message</p>}
        {(attachments.length > 0 || uploads > 0) && <div className="flex flex-wrap gap-1.5 px-3 pt-2.5">{attachments.map((attachment) => <span key={attachment.path} className="flex max-w-full items-center gap-1.5 rounded-md bg-surface py-1 pr-1 pl-2 text-[11px]"><Icon name={attachment.mime.startsWith('image/') ? 'image' : 'file'} size={12} /><span className="truncate" title={attachment.path}>{attachment.name}</span><button aria-label={`Remove ${attachment.name}`} onClick={() => setAttachments((current) => current.filter((file) => file.path !== attachment.path))} className="p-0.5 text-muted hover:text-ink active:text-accent"><Icon name="close" size={11} /></button></span>)}{uploads > 0 && <span className="py-1 text-[11px] text-muted">Attaching {uploads}…</span>}</div>}
        <textarea ref={textarea} aria-label="Message Codex" value={draft} rows={2} spellCheck={false} onChange={(event) => changeDraft(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(); } }} onPaste={(event) => {
          const files = Array.from(event.clipboardData.files);
          if (files.length) { event.preventDefault(); void uploadFiles(files); return; }
          const text = event.clipboardData.getData('text/plain');
          if (text.length > 20_000) { event.preventDefault(); void uploadFiles([new File([text], 'pasted-text.txt', { type: 'text/plain;charset=utf-8' })]); }
        }} placeholder={working ? 'Guide the work, or add a follow-up…' : 'Ask Codex to build, fix, or explore…'} className="block max-h-50 min-h-16 w-full resize-none bg-transparent px-3 pt-3 pb-2 text-[13px] leading-relaxed placeholder:text-muted/70" />
        <div className="@container flex flex-wrap items-center gap-x-2 gap-y-1 px-2 pb-2">
          <div className="flex shrink-0 items-center gap-1">
          <SmallButton label="Attach files" icon="plus" onClick={() => { setUploads((count) => count + 1); void request<{ files: Attachment[] }>('chooseFiles').then((value) => setAttachments((current) => [...current, ...value.files])).catch((error: Error) => setError(error.message)).finally(() => setUploads((count) => count - 1)); }} />
          <span title={state?.cwd} className="max-w-28 truncate text-[10px] text-muted">{state?.distro || 'Local'}</span>
          <ChoiceMenu label="Permission mode" value={permissions} onChange={setPermissions} hint={working ? 'Changes apply to the next turn. The current turn keeps its existing permissions.' : undefined} options={[
            { value: 'auto', label: 'Approve for me', description: 'Codex reviews approval requests. Work stays within the selected sandbox.' },
            { value: 'ask', label: 'Ask me', description: 'Review permission requests yourself before Codex proceeds.' },
            { value: 'read', label: 'Read only', description: 'Explore and explain the project without changing files.' },
          ]} />
          </div>
          <div className="flex min-w-0 flex-[1_1_250px] items-center justify-end gap-2">
          <ChoiceMenu label="Model" value={model} onChange={(value) => { setModel(value); setEffort(''); }} compact options={[{ value: '', label: 'Codex default' }, ...(state?.models || []).map((value) => ({ value: value.model, label: value.displayName || value.model }))]} />
          {selectedModel && <ChoiceMenu label="Reasoning effort" value={effort} onChange={setEffort} options={[{ value: '', label: 'Default effort' }, ...selectedModel.supportedReasoningEfforts.map((item) => ({ value: item.reasoningEffort, label: item.reasoningEffort, description: item.description }))]} />}
          <button title="Include open files and selected code" aria-label="IDE context" aria-pressed={context} onClick={() => setContext(!context)} className={`flex shrink-0 items-center gap-1 rounded px-1 py-1 text-[10px] hover:bg-raised active:bg-line ${context ? 'bg-accent/10 text-accent' : 'text-muted hover:text-ink active:text-accent'}`}><Icon name="code" size={12} /><span className="@max-[380px]:hidden">IDE context</span></button>
          {working && <button aria-label="Stop Codex" title="Stop current turn" onClick={() => void run('stop')} className="flex size-8 shrink-0 items-center justify-center rounded-full bg-ink text-composer hover:bg-accent active:translate-y-px active:bg-accent/75"><span aria-hidden className="size-2.5 rounded-[1px] bg-current" /></button>}
          {(!working || draft.trim() || attachments.length > 0 || !!chat?.draftInput?.length) && <button aria-label={working ? 'Send follow-up' : 'Send message'} title={working ? 'Send follow-up to the active turn' : 'Send message (Enter)'} disabled={sending || uploads > 0 || (!draft.trim() && !attachments.length && !chat?.draftInput?.length)} onClick={() => void send()} className="flex size-8 shrink-0 items-center justify-center rounded-full bg-ink text-composer enabled:hover:bg-accent enabled:active:translate-y-px enabled:active:bg-accent/75"><Icon name="send" size={18} /></button>}
          </div>
        </div>
      </div>
    </footer>}

    {dragging && <div className="pointer-events-none absolute inset-2 z-40 flex items-center justify-center rounded-xl border-2 border-dashed border-accent bg-surface/95"><div className="flex flex-col items-center gap-3 text-accent"><Icon name="attach" size={30} /><span>Drop files to add context</span></div></div>}
    {palette && <div role="dialog" aria-modal="true" aria-label="Find a conversation" className="absolute inset-0 z-30 flex items-start justify-center bg-black/40 px-4 pt-12" onClick={() => setPalette(false)}>
      <div className="flex max-h-[75%] w-full max-w-lg flex-col overflow-hidden rounded-xl bg-raised shadow-2xl" onClick={(event) => event.stopPropagation()}>
        <div className="flex items-center gap-2 px-3"><Icon name="search" /><input autoFocus aria-label="Search conversations" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Find a conversation…" className="min-w-0 flex-1 bg-transparent py-3 outline-none" /><SmallButton label="Close search" icon="close" onClick={() => setPalette(false)} /></div>
        <div className="min-h-0 overflow-y-auto p-1.5">
          <button onClick={() => { void run('new'); setPalette(false); }} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2.5 text-left text-accent hover:bg-raised active:bg-line"><Icon name="plus" />New chat</button>
          {state?.sessions.filter((session) => session.title.toLowerCase().includes(search.toLowerCase())).map((session) => <button key={session.id} onClick={() => { void run('openThread', { id: session.id }); setPalette(false); }} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2.5 text-left hover:bg-raised active:bg-line"><span className={`size-1.5 shrink-0 rounded-full ${session.status === 'attention' ? 'bg-attention' : session.status === 'working' ? 'bg-accent' : 'bg-muted/50'}`} /><span className="min-w-0 flex-1 truncate">{session.title}</span><span className="text-[10px] text-muted">{session.status === 'attention' ? 'Needs you' : session.status === 'working' ? 'Working' : ''}</span></button>)}
          {history.filter((thread) => !state?.sessions.some((session) => session.threadId === thread.id)).map((thread) => <button key={String(thread.id)} onClick={() => { void run('openThread', { thread }); setPalette(false); }} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2.5 text-left text-muted hover:bg-raised active:bg-line"><Icon name="code" size={12} /><span className="truncate">{String(thread.name || thread.preview || 'Untitled chat')}</span></button>)}
        </div>
        <div className="px-3 py-2 text-[10px] text-muted">Current project · Questions and approvals appear first</div>
      </div>
    </div>}
  </div>;
}
