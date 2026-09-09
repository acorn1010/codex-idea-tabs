import { useEffect, useRef, useState } from 'react';
import { request } from './bridge';
import type { Json } from './types';

export type PanelAction = 'review' | 'goal' | 'feedback' | 'mcp';
const field = 'min-w-0 w-full rounded-lg bg-input px-3 py-2 text-xs shadow-input focus:shadow-input-focus cursor-text outline-none';
const submit = 'rounded-md bg-ink px-3 py-1.5 text-xs text-composer hover:bg-ink/85 active:bg-ink/70 disabled:opacity-40 disabled:cursor-default';

/** Command forms stay above the composer and send only after the user chooses their action. */
export function CommandPanel({ action, working, options, onClose }: { action: PanelAction; working: boolean; options: Json; onClose: () => void }) {
  const edited = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [text, setText] = useState('');
  const [budget, setBudget] = useState('');
  const [target, setTarget] = useState('uncommittedChanges');
  const [data, setData] = useState<Json>();
  const [receipt, setReceipt] = useState('');
  const load = async () => {
    if (action !== 'goal' && action !== 'mcp') { return; }
    setBusy(true); setError('');
    try {
      const value = await request(action === 'goal' ? 'getGoal' : 'mcpStatus');
      setData(value);
      if (action === 'goal' && value.goal && !edited.current) { const goal = value.goal as Json; setText(String(goal.objective || '')); setBudget(goal.tokenBudget ? String(goal.tokenBudget) : ''); }
    } catch (error) { setError((error as Error).message); }
    finally { setBusy(false); }
  };
  useEffect(() => { void load(); }, [action]);
  const perform = async (method: string, params: Json = {}) => {
    setBusy(true); setError('');
    try {
      const response = await request(method, params);
      if (action === 'feedback') { setReceipt(String(response.threadId || 'Sent')); }
      else { onClose(); }
    } catch (error) { setError((error as Error).message); }
    finally { setBusy(false); }
  };
  const servers = (data?.data || []) as { name: string; authStatus?: string; tools?: Record<string, unknown> }[];
  const title = { review: 'Code review', goal: 'Goal', feedback: 'Feedback', mcp: 'MCP servers' }[action];
  return <section aria-label={title} className="max-h-[45vh] overflow-y-auto rounded-xl bg-raised p-3 text-xs">
    <header className="mb-2 flex items-center gap-2"><span className="flex-1 font-medium">{title}</span><button type="button" className="rounded px-1 text-muted hover:bg-line hover:text-ink active:bg-input" onClick={onClose}>Close</button></header>
    {error && <p role="alert" className="mb-2 text-attention">{error}{(action === 'goal' || action === 'mcp') && <button className="ml-2 rounded px-1 underline hover:bg-attention/10 active:bg-attention/20" onClick={() => void load()}>Retry</button>}</p>}
    {action === 'mcp' ? <>{busy && <p role="status" className="text-muted">Loading servers…</p>}{servers.map(server => <div key={server.name} className="flex items-center gap-2 py-1.5"><span className="min-w-0 flex-1 truncate">{server.name}</span><span className="text-[10px] text-muted">{Object.keys(server.tools || {}).length} tools · {({ notLoggedIn: 'Not signed in', oAuth: 'Signed in', bearerToken: 'Token configured', unsupported: 'No sign-in support' })[server.authStatus || ''] || server.authStatus || 'Status unavailable'}</span></div>)}{data && !servers.length && <p className="text-muted">No MCP servers were reported.</p>}<button disabled={busy} className="mt-2 rounded px-2 py-1 text-muted hover:bg-line active:bg-input" onClick={() => void load()}>Refresh</button></> : receipt ? <p role="status" className="select-text break-all text-muted">Feedback sent · {receipt}</p> : <form className="space-y-2" onSubmit={(event) => {
      event.preventDefault();
      if (busy) { return; }
      if (action === 'review') { void perform('review', { ...options, target: target === 'baseBranch' ? { type: target, branch: text.trim() } : { type: target } }); }
      if (action === 'goal') { void perform('setGoal', { ...options, objective: text.trim(), ...(budget ? { tokenBudget: Number(budget) } : {}) }); }
      if (action === 'feedback') { void perform('feedback', { reason: text.trim() }); }
    }}>
      {action === 'review' ? <><select aria-label="Review target" value={target} onChange={(event) => setTarget(event.target.value)} className="w-full rounded-lg bg-input px-2 py-2 text-xs hover:brightness-110 active:brightness-90"><option value="uncommittedChanges">Uncommitted changes</option><option value="baseBranch">Compare against a branch</option></select>{target === 'baseBranch' && <input className={field} autoFocus aria-label="Base branch" placeholder="main" value={text} onChange={(event) => { edited.current = true; setText(event.target.value); }} required />}{working && <p className="text-muted">Wait for this turn to finish before starting a review.</p>}</> : <textarea className={`${field} resize-y`} rows={2} maxLength={4000} autoFocus aria-label={action === 'goal' ? 'Goal objective' : 'Feedback text'} placeholder={action === 'goal' ? 'What should Codex keep working toward?' : 'What went wrong, or what could work better?'} value={text} onChange={(event) => { edited.current = true; setText(event.target.value); }} required />}
      {action === 'goal' && <input className={field} aria-label="Optional goal token budget" type="number" min={1} step={1} placeholder="Token budget (optional)" value={budget} onChange={(event) => { edited.current = true; setBudget(event.target.value); }} />}
      {action === 'feedback' && <p className="text-[10px] text-muted">Send this feedback and the chat ID to OpenAI. Logs are excluded.</p>}
      <div className="flex justify-end gap-2">{action === 'goal' && !!data?.goal && <button type="button" disabled={busy} className="rounded px-2 py-1 text-muted hover:bg-line active:bg-input" onClick={() => void perform('clearGoal')}>Clear goal</button>}<button className={submit} disabled={busy || action === 'review' && working || action !== 'review' && !text.trim()}>{busy ? 'Working…' : action === 'review' ? 'Start review' : action === 'goal' ? 'Set goal' : 'Send feedback'}</button></div>
    </form>}
  </section>;
}
