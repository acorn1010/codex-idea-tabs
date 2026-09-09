import { useEffect, useRef, useState } from 'react';
import { request } from './bridge';
import type { Snapshot } from './types';

type LimitWindow = { usedPercent: number; windowDurationMins?: number; resetsAt?: number };
type Limit = { limitId?: string; limitName?: string; primary?: LimitWindow; secondary?: LimitWindow };
type Limits = { rateLimits?: Limit; rateLimitsByLimitId?: Record<string, Limit> };
const number = new Intl.NumberFormat('en');
const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 0 });

/** A small status panel above the composer leaves the transcript and typing focus in place. */
export function ChatStatus({ state, onClose }: { state: Snapshot; onClose: () => void }) {
  const alive = useRef(true);
  const inFlight = useRef(false);
  const [limits, setLimits] = useState<Limits>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const connected = state.connection === 'connected';
  const refresh = async () => {
    if (inFlight.current || !connected) { return; }
    inFlight.current = true; setLoading(true); setError('');
    try {
      const result = await request<Limits>('accountLimits');
      if (alive.current) { setLimits(result); }
    } catch (error) { if (alive.current) { setError((error as Error).message); } }
    finally { inFlight.current = false; if (alive.current) { setLoading(false); } }
  };
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => { void refresh(); }, [connected]);
  const usage = state.chat.tokenUsage;
  const last = usage?.last as { totalTokens?: number } | undefined;
  const capacity = typeof usage?.modelContextWindow === 'number' && usage.modelContextWindow > 0 ? usage.modelContextWindow : undefined;
  const entries = Object.entries(limits?.rateLimitsByLimitId || {});
  const buckets = entries.length ? entries : limits?.rateLimits ? [[limits.rateLimits.limitId || 'codex', limits.rateLimits] as const] : [];
  return <section aria-label="Session status" className="@container/status max-h-[35vh] overflow-y-auto rounded-xl bg-raised px-3 py-2 text-[11px]">
    <header className="mb-2 flex items-center gap-2 text-muted"><span className="flex-1">Status</span><button type="button" disabled={loading || !connected} className="rounded px-1 hover:bg-line hover:text-ink active:bg-input disabled:opacity-40" onClick={() => void refresh()}>{loading ? 'Refreshing…' : 'Refresh'}</button><button type="button" className="rounded px-1 hover:bg-line hover:text-ink active:bg-input" onClick={onClose}>Close</button></header>
    <div className="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-3 gap-y-1.5 font-mono text-[10px]">
      <span className="text-muted">Session/Thread:</span><span className="truncate select-all font-semibold" title={state.chat.threadId}>{state.chat.threadId || 'New chat'}</span>
      <span className="text-muted">Context:</span><span className="min-w-0 truncate" title="Latest usage reported by Codex">{typeof last?.totalTokens === 'number' ? <>{capacity && <strong>{Math.max(0, Math.round((1 - last.totalTokens / capacity) * 100))}% left </strong>}<span className="text-muted">({number.format(last.totalTokens)} used{capacity ? ` / ${compact.format(capacity)}` : ''})</span></> : <span className="text-muted">Not reported yet</span>}</span>
      {buckets.flatMap(([id, bucket]) => [bucket.primary, bucket.secondary].map((window, index) => {
        if (!window || !Number.isFinite(window.usedPercent)) { return null; }
        const left = Math.max(0, Math.min(100, 100 - window.usedPercent));
        const minutes = window.windowDurationMins;
        const label = minutes ? minutes % 1440 === 0 ? `${minutes / 1440}d` : minutes % 60 === 0 ? `${minutes / 60}h` : `${minutes}m` : index ? 'Longer' : 'Current';
        return <div key={`${id}-${index}`} className="contents"><span className="text-muted" title={bucket.limitName || id}>{buckets.length > 1 ? `${bucket.limitName || id} ` : ''}{label} limit:</span><div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1"><span className="hidden h-2 min-w-12 flex-1 overflow-hidden bg-ink/10 @min-[480px]/status:block" aria-hidden><span className="block h-full bg-ink/80" style={{ width: `${left}%` }} /></span><span className={left <= 10 ? 'text-attention' : ''}><strong>{number.format(left)}% left</strong>{window.resetsAt && <span className="text-muted"> (resets {new Date(window.resetsAt * 1000).toLocaleString(undefined, { month: 'short', day: 'numeric', ...(minutes && minutes < 1440 ? { hour: 'numeric', minute: '2-digit' } as const : {}) })})</span>}</span></div></div>;
      }))}
    </div>
    {!connected ? <p className="mt-2 text-muted">Connect to Codex to read current limits.</p> : error ? <p role="alert" className="mt-2 text-attention">{error}<button className="ml-2 rounded px-1 underline hover:bg-attention/10 active:bg-attention/20" onClick={() => void refresh()}>Retry</button></p> : !loading && !buckets.length && <p className="mt-2 text-muted">No account limits were reported.</p>}
  </section>;
}
