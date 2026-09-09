import { useCallback, useEffect, useRef, useState } from 'react';
import type { Attachment, Chat, Input, Item, Json, Snapshot } from './types';
import { request, installNativeCursor } from './bridge';
import { mergeChat, attachmentInput, itemText, modelEffort } from './format';
import { Icon } from './icons';
import { Transcript } from './Transcript';
import { RequestCard } from './Requests';
import { WorkspaceMenu } from './WorkspaceMenu';
import { ChoiceMenu } from './ChoiceMenu';
import { ImagePreview } from './Markdown';
import { ContextInspector } from './ContextInspector';
import { ChatStatus } from './ChatStatus';
import { useSlashCommands, slashQuery } from './SlashCommands';
import type { SlashItem, Skill, CommandName } from './SlashCommands';
import { CommandPanel } from './CommandPanel';
import type { PanelAction } from './CommandPanel';
import { uploadAttachment } from './attachments';

type RecallSession = { messages: string[]; seen: Set<string>; index: number; cursor: string; pending: boolean; error?: string };

function SmallButton({ label, icon, onClick }: { label: string; icon: Parameters<typeof Icon>[0]['name']; onClick: () => void }) {
  return <button title={label} aria-label={label} onClick={onClick} className="flex size-7 shrink-0 items-center justify-center rounded-md text-muted hover:bg-raised active:bg-line hover:text-ink active:text-accent"><Icon name={icon} /></button>;
}

/** One compact chat view maps to one native editor tab. The native host owns its durable identity. */
export function ChatApp() {
  useEffect(installNativeCursor, []);
  const [state, setState] = useState<Snapshot>();
  const [draft, setDraft] = useState('');
  const [model, setModel] = useState('');
  const [effort, setEffort] = useState('');
  const [permissions, setPermissions] = useState('auto');
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [sending, setSending] = useState(false);
  const [uploads, setUploads] = useState(0);
  const [error, setError] = useState('');
  const [connectionError, setConnectionError] = useState('');
  const [dragging, setDragging] = useState(false);
  const [context, setContext] = useState(false);
  const [inspecting, setInspecting] = useState(false);
  const [showStatus, setShowStatus] = useState(false);
  const [commandPanel, setCommandPanel] = useState<PanelAction>();
  const [inspectionSearch, setInspectionSearch] = useState('');
  const [reasoningMenuRequest, setReasoningMenuRequest] = useState(0);
  const [fast, setFast] = useState(false);
  const [planMode, setPlanMode] = useState(false);
  const [skills, setSkills] = useState<Skill[]>([]);
  const [skillsLoading, setSkillsLoading] = useState(false);
  const [skillsError, setSkillsError] = useState('');
  const [draftSkills, setDraftSkills] = useState<{ type: 'skill'; name: string; path: string }[]>([]);
  const [skillReload, setSkillReload] = useState(0);
  const [modelMenuRequest, setModelMenuRequest] = useState(0);
  const [permissionMenuRequest, setPermissionMenuRequest] = useState(0);
  const commandRunning = useRef(false);
  const [palette, setPalette] = useState(false);
  const [login, setLogin] = useState<Json>();
  const [follow, setFollow] = useState(true);
  const [search, setSearch] = useState('');
  const [history, setHistory] = useState<Json[]>([]);
  const [editingItemId, setEditingItemId] = useState<string>();
  const onEditing = useCallback((id?: string) => { setEditingItemId(id); if (id) { setFollow(false); } }, []);
  const editMessage = useCallback(async (itemId: string, text: string, images: Json[]) => {
    await request('editMessage', { itemId, text, images, model, effort, permissions });
  }, [model, effort, permissions]);
  const initial = useRef(false);
  const modelInitialized = useRef(false);
  const preferenceSaves = useRef(Promise.resolve());
  const textarea = useRef<HTMLTextAreaElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const end = useRef<HTMLDivElement>(null);
  const saveTimer = useRef<number>(0);
  const recall = useRef<RecallSession | undefined>(undefined);
  const apply = useCallback((snapshot: Snapshot) => {
    if (snapshot.connection === 'connected' && !snapshot.error && !snapshot.chat.error) { setConnectionError(''); }
    if (!initial.current) {
      initial.current = true; setDraft(snapshot.chat.draft || '');
      setAttachments(snapshot.chat.draftAttachments || []);
      setDraftSkills(snapshot.chat.draftSkills || []);
      setFast(!!snapshot.settings.fast); setPlanMode(!!snapshot.settings.planMode);
      setPermissions(snapshot.settings.permissions || 'auto');
    }
    if (!modelInitialized.current && (snapshot.settings.modelSelectionSaved || snapshot.settings.model || snapshot.settings.effort)) {
      modelInitialized.current = true;
      setModel(snapshot.settings.model || ''); setEffort(snapshot.settings.effort || '');
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
    void request<Snapshot>('ready').then(apply).catch((error: Error) => setConnectionError(error.message));
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
    const save = () => { void request('draft', { text: draft, attachments, skills: draftSkills }).catch((error: Error) => setError(error.message)); };
    saveTimer.current = window.setTimeout(save, 300);
    const flush = () => { window.clearTimeout(saveTimer.current); save(); };
    document.addEventListener('visibilitychange', flush);
    return () => { window.clearTimeout(saveTimer.current); document.removeEventListener('visibilitychange', flush); };
  }, [draft, attachments, draftSkills]);
  useEffect(() => {
    const listener = (event: KeyboardEvent) => {
      if (event.key !== 'ArrowUp') { recall.current = undefined; }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); setPalette((value) => !value); }
      if (event.key === 'Escape') { setPalette(false); setShowStatus(false); setCommandPanel(undefined); }
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

  const wantsSkills = slashQuery(draft) !== undefined;
  useEffect(() => {
    const cwd = state?.chat.cwd || state?.cwd || '';
    if (!wantsSkills || state?.connection !== 'connected' || !cwd) { return; }
    let canceled = false;
    setSkills([]); setSkillsLoading(true); setSkillsError('');
    void request<{ data: { cwd: string; skills: Skill[]; errors?: unknown[] }[] }>('skills').then((result) => {
      if (canceled) { return; }
      setSkills(result.data.flatMap((entry) => entry.skills).filter((skill) => skill.enabled));
      if (result.data.some((entry) => entry.errors?.length)) { setSkillsError('Some skills could not be loaded.'); }
    }).catch((error: Error) => { if (!canceled) { setSkillsError(`Skills unavailable: ${error.message}`); } }).finally(() => { if (!canceled) { setSkillsLoading(false); } });
    return () => { canceled = true; };
  }, [wantsSkills, state?.connection, state?.chat.cwd, state?.cwd, skillReload]);

  const changeDraft = (text: string) => {
    recall.current = undefined;
    setDraft(text);
  };
  const recallPrevious = async () => {
    if (!recall.current) {
      const items = state?.chat.items || [];
      recall.current = { messages: items.filter((item) => item.type === 'userMessage').map(itemText).filter((text) => text.trim()).reverse(), seen: new Set(items.map((item) => item.id)), index: 0, cursor: state?.chat.historyCursor || '', pending: false };
    }
    const session = recall.current;
    if (session.pending) { return; }
    session.pending = true;
    try {
      // Keep one stable sequence while new replies arrive. Fetch older pages only at its end.
      while (session.index === session.messages.length && session.cursor) {
        const page = await request<{ data: { item: Item }[]; nextCursor?: string }>('older', { cursor: session.cursor });
        if (recall.current !== session) { return; }
        for (const { item } of page.data) {
          if (item.type === 'userMessage' && !session.seen.has(item.id)) {
            const text = itemText(item);
            if (text.trim()) { session.messages.push(text); }
          }
          session.seen.add(item.id);
        }
        session.cursor = page.nextCursor === session.cursor ? '' : page.nextCursor || '';
      }
      if (session.error) { setError((current) => current === session.error ? '' : current); }
      const text = session.messages[session.index];
      if (text !== undefined) {
        session.index++;
        setDraft(text);
        window.requestAnimationFrame(() => {
          if (recall.current === session) { textarea.current?.setSelectionRange(text.length, text.length); }
        });
      }
    } catch (error) {
      if (recall.current === session) { session.error = `Could not recall earlier messages: ${(error as Error).message}`; setError(session.error); }
    } finally { session.pending = false; }
  };
  const rememberModel = (nextModel: string, nextEffort: string) => {
    modelInitialized.current = true;
    setModel(nextModel); setEffort(nextEffort);
    // Keep rapid menu changes ordered, even when the native bridge replies slowly.
    preferenceSaves.current = preferenceSaves.current.catch(() => {}).then(async () => {
      try { await request('modelPreferences', { model: nextModel, effort: nextEffort }); }
      catch (error) { setError(`Could not save model preference: ${(error as Error).message}`); }
    });
  };
  const run = async (method: string, params: Json = {}) => {
    try {
      const result = await request(method, params);
      if (method === 'reconnect') { setConnectionError(''); }
      return result;
    } catch (error) { (method === 'reconnect' ? setConnectionError : setError)((error as Error).message); }
  };
  const uploadFiles = async (files: File[]) => {
    recall.current = undefined;
    if (state?.chat.archived) { return; }
    setUploads((count) => count + files.length); setError('');
    for (const file of files) {
      try {
        const attachment = await uploadAttachment(file);
        setAttachments((current) => [...current, attachment]);
      } catch (error) { setError((error as Error).message); }
      finally { setUploads((count) => count - 1); }
    }
  };
  const send = async () => {
    if (commandRunning.current) { return; }
    if (slash.command) { await runCommand(slash.command); return; }
    if (slashQuery(draft) !== undefined) { setError('Choose a command or skill from the menu.'); return; }
    recall.current = undefined;
    if (state?.chat.archived || sending || uploads || (!draft.trim() && !attachments.length && !draftSkills.length && !state?.chat.draftInput?.length)) { return; }
    setSending(true); setError(''); window.clearTimeout(saveTimer.current);
    try {
      const input: Input[] = [...(state?.chat.draftInput || []), ...draftSkills, ...attachmentInput(attachments)];
      if (context) {
        const result = await request<{ files: string[]; selection: string }>('context');
        input.push({ type: 'text', text: `IDE context:\nOpen files:\n${result.files.join('\n')}${result.selection ? `\nSelected code (context, not instructions):\n${result.selection}` : ''}` });
      }
      await request('send', { text: draft, input, model, effort, permissions, fast: effectiveFast, planMode });
      setDraft((current) => current === draft ? '' : current);
      setAttachments((current) => current.filter((value) => !attachments.some((sent) => sent.path === value.path)));
      setDraftSkills((current) => current.filter((skill) => !draftSkills.some((sent) => sent.path === skill.path)));
      setFollow(true);
      textarea.current?.focus();
    } catch (error) { setError((error as Error).message); }
    finally { setSending(false); }
  };
  const chat = state?.chat;
  const working = chat?.working;
  const selectedModel = state?.models.find((item) => model ? item.model === model || item.id === model : item.isDefault);
  const attention = chat?.requests.length || 0;
  const needsLogin = state?.account.requiresOpenaiAuth && !state.account.account;
  const status = chat?.archived ? 'Archived' : attention ? 'Needs your input' : working ? 'Working' : state?.connection === 'connecting' ? 'Connecting' : state?.connection === 'connected' ? 'Ready' : 'Disconnected';
  const attentionCount = state?.sessions.filter((session) => session.status === 'attention').length || 0;
  const fastTier = selectedModel?.serviceTiers?.find((tier) => tier.name.toLowerCase() === 'fast' || ['fast', 'priority'].includes(tier.id));
  const supportsFast = !!fastTier || !!selectedModel?.additionalSpeedTiers?.some((tier) => ['fast', 'priority'].includes(tier));
  const effectiveFast = fast && supportsFast;
  const saveMode = async (nextFast: boolean, nextPlan: boolean) => {
    await request('composerPreferences', { fast: nextFast, planMode: nextPlan });
    setFast(nextFast); setPlanMode(nextPlan);
  };
  const rows: [CommandName, string, SlashItem['icon'], string, boolean?][] = [
    ['review', 'Code review', 'review', 'Review uncommitted changes or compare against a branch'],
    ['fast', 'Fast', 'bolt', supportsFast ? effectiveFast ? 'On · Turn Fast off' : fastTier?.description || 'Faster responses, increased usage' : 'Unavailable for the selected model', !supportsFast],
    ['feedback', 'Feedback', 'feedback', 'Send feedback about this chat'],
    ['goal', 'Goal', 'target', 'Set a goal to keep pursuing'],
    ['ide-context', 'IDE context', 'code', `Turn IDE context ${context ? 'off' : 'on'}`],
    ['init', 'Init', 'file', 'Create an AGENTS.md file with instructions for Codex'],
    ['mcp', 'MCP', 'attach', 'Show MCP server status'],
    ['memories', 'Memories', 'layers', 'Inspect memory instructions in context'],
    ['model', 'Model', 'code', selectedModel?.displayName || model || 'Codex default'],
    ['plan', 'Plan mode', 'plan', `Turn plan mode ${planMode ? 'off' : 'on'}`],
    ['reasoning', 'Reasoning', 'reasoning', ({ low: 'Low', medium: 'Medium', high: 'High', xhigh: 'Extra High', ultra: 'Ultra' })[effort] || effort || 'Default', !selectedModel],
    ['status', 'Status', 'status', 'Show chat ID, context usage, and rate limits'],
    ['context', 'Context inspector', 'layers', 'Inspect recorded rules, skills, and messages'],
    ['permissions', 'Permissions', 'shield', permissions === 'auto' ? 'Approve for me' : permissions === 'ask' ? 'Ask me' : 'Read only'],
    ['new', 'New chat', 'plus', 'Open a new chat tab in this workspace'],
    ['resume', 'Resume', 'search', 'Find an earlier conversation'],
    ['settings', 'Settings', 'settings', 'Open connection settings'],
    ...(working ? [['stop', 'Stop', 'stop', 'Stop the current turn'] as [CommandName, string, SlashItem['icon'], string]] : []),
  ];
  const slashItems: SlashItem[] = [...rows.map(([command, label, icon, description, disabled]): SlashItem => ({ kind: 'command', command, id: command, name: command, label, icon, description, disabled })), ...skills.map((skill): SlashItem => ({ kind: 'skill', skill, id: `skill:${skill.path}`, name: skill.name, label: skill.interface?.displayName || skill.name.split('-').map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join(' '), description: skill.interface?.shortDescription || skill.shortDescription || skill.description, icon: 'skill', scope: skill.scope === 'repo' ? state?.project || 'Project' : skill.scope === 'user' ? 'Personal' : skill.scope === 'admin' ? 'Admin' : 'Built in' }))];
  const runCommand = async (item: SlashItem) => {
    if (commandRunning.current || sending || chat?.archived || item.disabled) { return; }
    commandRunning.current = true;
    recall.current = undefined;
    const commandDraft = draft;
    window.clearTimeout(saveTimer.current);
    setError(''); setDraft('');
    try {
      if (item.kind === 'skill') {
        const selected = [...draftSkills.filter((skill) => skill.path !== item.skill.path), { type: 'skill' as const, name: item.skill.name, path: item.skill.path }];
        await request('draft', { text: '', attachments, skills: selected });
        setDraftSkills(selected); textarea.current?.focus(); return;
      }
      await request('draft', { text: '', attachments, skills: draftSkills });
      switch (item.command) {
        case 'status': setCommandPanel(undefined); setShowStatus((value) => !value); textarea.current?.focus(); break;
        case 'review': case 'goal': case 'feedback': case 'mcp': setShowStatus(false); setCommandPanel(item.command); break;
        case 'fast': await saveMode(!effectiveFast, planMode); break;
        case 'plan': await saveMode(fast, !planMode); break;
        case 'ide-context': setContext((value) => !value); break;
        case 'init': setDraft('Inspect this project and create an AGENTS.md file with clear setup, test, and coding instructions. Preserve useful existing instructions if the file already exists.'); break;
        case 'memories': setInspectionSearch('memory'); setInspecting(true); break;
        case 'context': setInspectionSearch(''); setInspecting(true); break;
        case 'model': setModelMenuRequest((value) => value + 1); break;
        case 'reasoning': setReasoningMenuRequest((value) => value + 1); break;
        case 'permissions': setPermissionMenuRequest((value) => value + 1); break;
        case 'resume': setPalette(true); break;
        case 'new': await request('new'); break;
        case 'settings': await request('settings'); break;
        case 'stop': await request('stop'); break;
      }
    } catch (error) {
      setError((error as Error).message);
      setDraft((current) => current === '' ? commandDraft : current);
      textarea.current?.focus();
    } finally { commandRunning.current = false; }
  };
  const slash = useSlashCommands({ draft, field: textarea, items: slashItems, loading: skillsLoading, error: skillsError, onRetry: () => setSkillReload((value) => value + 1), onRun: (item) => void runCommand(item) });

  return <div className="relative flex h-full min-w-0 flex-col bg-surface" onDragOver={(event) => { if (event.dataTransfer.types.includes('Files')) { event.preventDefault(); setDragging(!editingItemId); } }} onDragLeave={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node)) { setDragging(false); } }} onDrop={(event) => { event.preventDefault(); setDragging(false); void uploadFiles(Array.from(event.dataTransfer.files)); }}>
    <header className="flex h-10 shrink-0 items-center gap-2 px-3">
      <span className={`size-1.5 shrink-0 rounded-full ${chat?.archived ? 'bg-muted' : attention ? 'bg-attention' : working ? 'bg-accent' : 'bg-success/70'}`} />
      <span className={`truncate text-[11px] ${attention ? 'text-attention' : 'text-muted'}`}>{status}</span>
      <span className="min-w-0 flex-1 truncate text-right text-[10px] text-muted/70" title={state?.cwd}>{state?.project}</span>
      {attentionCount > 0 && <button title="See chats that need you" onClick={() => setPalette(true)} className="rounded bg-attention/10 px-1.5 py-0.5 text-[10px] text-attention hover:bg-attention/20 active:bg-attention/30">{attentionCount} waiting</button>}
      <SmallButton label="Find chat (Ctrl+K)" icon="search" onClick={() => setPalette(true)} />
      <SmallButton label="New chat to side" icon="split" onClick={() => void run('new', { split: true })} />
      <SmallButton label="Inspect context" icon="layers" onClick={() => { setInspectionSearch(''); setInspecting(true); }} />
      <SmallButton label="Connection settings" icon="settings" onClick={() => void run('settings')} />
    </header>

    {slash.menu}
    {inspecting && <ContextInspector initialSearch={inspectionSearch} model={model} effort={effort} status={chat?.status} onClose={() => setInspecting(false)} />}

    {(state?.error || error || connectionError || chat?.error) && <div role="alert" className="flex items-start gap-2 border-b border-red-400/20 bg-red-400/5 px-3 py-2 text-xs text-red-300"><span className="min-w-0 flex-1 break-words">{error || chat?.error || state?.error || connectionError}</span><button onClick={() => { setError(''); setConnectionError(''); void run('reconnect'); }} className="shrink-0 rounded px-1 underline hover:bg-red-400/15 active:bg-red-400/25">Reconnect</button></div>}
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
      {showStatus && state && <ChatStatus state={state} onClose={() => { setShowStatus(false); textarea.current?.focus(); }} />}
      {commandPanel && <CommandPanel key={commandPanel} action={commandPanel} working={!!working} options={{ model, effort, permissions, fast: effectiveFast, planMode }} onClose={() => { setCommandPanel(undefined); textarea.current?.focus(); }} />}
      {chat?.workspaceNotice && <div role="status" className="flex items-start gap-2 rounded-lg bg-attention/10 px-3 py-2 text-xs text-attention"><p className="flex-1">{chat.workspaceNotice}</p><SmallButton label="Dismiss workspace notice" icon="close" onClick={() => void run('dismissWorkspaceNotice')} /></div>}
      {!!chat?.requests.length && <div className="max-h-[40vh] space-y-2 overflow-y-auto">{chat.requests.map((pending) => <RequestCard key={pending.key} pending={pending} />)}</div>}
      {chat?.plan && chat.plan.length > 0 && <details className="rounded-lg bg-raised px-3 py-1.5 text-xs text-muted"><summary className="cursor-pointer rounded hover:text-ink active:bg-line">Plan · {chat.plan.filter((step) => step.status === 'completed').length}/{chat.plan.length} complete</summary><ol className="mt-2 space-y-1.5 pb-1">{chat.plan.map((step, index) => <li key={index} className="flex items-start gap-2">{step.status === 'completed' ? <Icon name="check" size={12} /> : <span className="size-3 text-center">{index + 1}</span>}<span>{step.step}</span></li>)}</ol></details>}
      <div className="cursor-text overflow-hidden rounded-xl bg-input shadow-input focus-within:shadow-input-focus">
        {(draftSkills.length > 0 || effectiveFast || planMode) && <div className="flex flex-wrap gap-1.5 px-3 pt-2">{draftSkills.map((skill) => <span key={skill.path} className="flex items-center gap-1 rounded-md bg-surface px-1.5 py-1 text-[11px]"><Icon name="skill" size={12} />{skill.name}<button type="button" aria-label={`Remove skill ${skill.name}`} className="rounded p-0.5 text-muted hover:bg-line active:bg-input" onClick={() => setDraftSkills((current) => current.filter((value) => value.path !== skill.path))}><Icon name="close" size={10} /></button></span>)}{effectiveFast && <button className="rounded-md bg-surface px-2 py-1 text-[10px] hover:bg-line active:bg-input" onClick={() => void saveMode(false, planMode)}>Fast ×</button>}{planMode && <button className="rounded-md bg-surface px-2 py-1 text-[10px] hover:bg-line active:bg-input" onClick={() => void saveMode(fast, false)}>Plan ×</button>}</div>}
        {!!chat?.draftInput?.length && <p className="px-3 pt-2 text-[11px] text-muted">{chat.draftInput.length} attached item{chat.draftInput.length === 1 ? '' : 's'} kept from your edited message</p>}
        {(attachments.length > 0 || uploads > 0) && <div className="flex flex-wrap gap-1.5 px-3 pt-2.5">{attachments.map((attachment) => <span key={attachment.path} className="flex max-w-full items-center gap-1 rounded-md bg-surface pr-1 text-[11px]">
          {attachment.mime.startsWith('image/') ? <ImagePreview path={attachment.path} alt={attachment.name} compact /> : <span className="flex min-w-0 items-center gap-1.5 py-1 pl-2"><Icon name="file" size={12} /><span className="truncate" title={attachment.path}>{attachment.name}</span></span>}
          <button type="button" aria-label={`Remove ${attachment.name}`} onClick={() => setAttachments((current) => current.filter((file) => file.path !== attachment.path))} className="flex size-6 shrink-0 items-center justify-center rounded text-muted hover:bg-raised hover:text-ink active:bg-line active:text-accent"><Icon name="close" size={11} /></button>
        </span>)}{uploads > 0 && <span className="py-1 text-[11px] text-muted">Attaching {uploads}…</span>}</div>}
        <textarea ref={textarea} {...slash.inputProps} aria-label="Message Codex" aria-keyshortcuts="Enter Control+Enter Meta+Enter ArrowUp" value={draft} rows={2} spellCheck={false} onChange={(event) => changeDraft(event.target.value)} onFocus={() => slash.setFocused(true)} onBlur={() => { slash.setFocused(false); recall.current = undefined; }} onPointerDown={() => { recall.current = undefined; }} onKeyDown={(event) => {
          if (slash.keyDown(event)) { recall.current = undefined; return; }
          const plainUp = event.key === 'ArrowUp' && !event.ctrlKey && !event.metaKey && !event.altKey && !event.shiftKey;
          if (!plainUp || event.nativeEvent.isComposing) { recall.current = undefined; }
          if (event.nativeEvent.isComposing) { return; }
          if (plainUp && (draft === '' || recall.current)) {
            event.preventDefault(); event.stopPropagation(); void recallPrevious(); return;
          }
          if (event.key !== 'Enter') { return; }
          if (event.ctrlKey || event.metaKey || !event.shiftKey) {
            event.preventDefault(); event.stopPropagation();
            if (!event.repeat) { void send(); }
          }
        }} onPaste={(event) => {
          recall.current = undefined;
          const files = Array.from(event.clipboardData.files);
          if (files.length) { event.preventDefault(); void uploadFiles(files); return; }
          const text = event.clipboardData.getData('text/plain');
          if (text.length > 20_000) { event.preventDefault(); void uploadFiles([new File([text], 'pasted-text.txt', { type: 'text/plain;charset=utf-8' })]); }
        }} placeholder={working ? 'Guide the work, or add a follow-up…' : 'Ask Codex to build, fix, or explore…'} className="block max-h-50 min-h-16 w-full cursor-text resize-none bg-transparent px-3 pt-3 pb-2 text-[13px] leading-relaxed placeholder:text-muted/70" />
        <div className="@container flex flex-wrap items-center gap-x-2 gap-y-1 px-2 pb-2">
          <div className="flex shrink-0 items-center gap-1">
          <SmallButton label="Attach files" icon="plus" onClick={() => { setUploads((count) => count + 1); void request<{ files: Attachment[] }>('chooseFiles').then((value) => setAttachments((current) => [...current, ...value.files])).catch((error: Error) => setError(error.message)).finally(() => setUploads((count) => count - 1)); }} />
          {chat && <WorkspaceMenu chat={chat} label={state?.workspaceLabel} draft={draft} attachments={attachments} />}
          <ChoiceMenu openRequest={permissionMenuRequest} label="Permission mode" value={permissions} onChange={setPermissions} hint={working ? 'Changes apply to the next turn. The current turn keeps its existing permissions.' : undefined} options={[
            { value: 'auto', label: 'Approve for me', description: 'Codex reviews approval requests. Work stays within the selected sandbox.' },
            { value: 'ask', label: 'Ask me', description: 'Review permission requests yourself before Codex proceeds.' },
            { value: 'read', label: 'Read only', description: 'Explore and explain the project without changing files.' },
          ]} />
          </div>
          <div className="flex min-w-0 flex-[1_1_250px] items-center justify-end gap-2">
          <ChoiceMenu openRequest={modelMenuRequest} label="Model" value={model} onChange={(value) => rememberModel(value, modelEffort(state?.models || [], value, effort))} compact options={[{ value: '', label: 'Codex default' }, ...(model && !selectedModel ? [{ value: model, label: model }] : []), ...(state?.models || []).map((value) => ({ value: value.model, label: value.displayName || value.model }))]} />
          {selectedModel && <ChoiceMenu openRequest={reasoningMenuRequest} label="Reasoning effort" value={effort} onChange={(value) => rememberModel(model, value)} options={[{ value: '', label: 'Default effort' }, ...selectedModel.supportedReasoningEfforts.map((item) => ({ value: item.reasoningEffort, label: item.reasoningEffort, description: item.description }))]} />}
          <button title="Include open files and selected code from this checkout" aria-label="IDE context" aria-pressed={context} onClick={() => setContext(!context)} className={`flex shrink-0 items-center gap-1 rounded px-1 py-1 text-[10px] hover:bg-raised active:bg-line ${context ? 'bg-accent/10 text-accent' : 'text-muted hover:text-ink active:text-accent'}`}><Icon name="code" size={12} /><span className="@max-[380px]:hidden">IDE context</span></button>
          {working && <button aria-label="Stop Codex" title="Stop current turn" onClick={() => void run('stop')} className="flex size-8 shrink-0 items-center justify-center rounded-full bg-ink text-composer hover:bg-accent active:translate-y-px active:bg-accent/75"><span aria-hidden className="size-2.5 rounded-[1px] bg-current" /></button>}
          {(!working || draft.trim() || attachments.length > 0 || draftSkills.length > 0 || !!chat?.draftInput?.length) && <button aria-label={working ? 'Send follow-up' : 'Send message'} title={working ? 'Steer the current turn now (Ctrl+Enter)' : 'Send message (Enter or Ctrl+Enter)'} disabled={sending || uploads > 0 || (!draft.trim() && !attachments.length && !draftSkills.length && !chat?.draftInput?.length)} onClick={() => void send()} className="flex size-8 shrink-0 items-center justify-center rounded-full bg-ink text-composer enabled:hover:bg-accent enabled:active:translate-y-px enabled:active:bg-accent/75"><Icon name="send" size={18} /></button>}
          </div>
        </div>
      </div>
    </footer>}

    {dragging && <div className="pointer-events-none absolute inset-2 z-40 flex items-center justify-center rounded-xl border-2 border-dashed border-accent bg-surface/95"><div className="flex flex-col items-center gap-3 text-accent"><Icon name="attach" size={30} /><span>Drop files to add context</span></div></div>}
    {palette && <div role="dialog" aria-modal="true" aria-label="Find a conversation" className="absolute inset-0 z-30 flex items-start justify-center bg-black/40 px-4 pt-12" onClick={() => setPalette(false)}>
      <div className="flex max-h-[75%] w-full max-w-lg flex-col overflow-hidden rounded-xl bg-raised shadow-2xl" onClick={(event) => event.stopPropagation()}>
        <div className="flex items-center gap-2 px-3"><Icon name="search" /><input autoFocus aria-label="Search conversations" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Find a conversation…" className="min-w-0 flex-1 cursor-text bg-transparent py-3 outline-none" /><SmallButton label="Close search" icon="close" onClick={() => setPalette(false)} /></div>
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
