import { useEffect, useState } from 'react';
import { request } from './bridge';
import type { Snapshot } from './types';

type LimitWindow = { utilization?: number | null; resets_at?: string | null };

type Status = {
  context?: { totalTokens?: number; maxTokens?: number; categories?: { name: string; tokens: number }[]; memoryFiles?: { path?: string; name?: string }[] };
  contextError?: string;
  usage?: { input_tokens?: number; output_tokens?: number };
  cost?: number;
  rateLimit?: { status?: string; resetsAt?: number; rateLimitType?: string };
  rateLimits?: { five_hour?: LimitWindow | null; seven_day?: LimitWindow | null };
  rateLimitsAvailable?: boolean;
  rateLimitsError?: string;
};
const number = new Intl.NumberFormat('en');
const percent = new Intl.NumberFormat('en', { maximumFractionDigits: 1 });

function LimitBar({ label, window, loading }: { label: string; window?: LimitWindow | null; loading: boolean }) {
  const remaining = typeof window?.utilization === 'number' && Number.isFinite(window.utilization) ? Math.max(0, Math.min(100, 100 - window.utilization)) : undefined;
  const reset = window?.resets_at ? new Date(window.resets_at) : undefined;
  const color = remaining !== undefined && remaining <= 5 ? 'text-danger' : remaining !== undefined && remaining <= 20 ? 'text-attention' : 'text-accent';
  return <div className="min-w-0 space-y-1.5">
    <div className="flex items-center justify-between gap-3"><span className="font-medium">{label}</span><span className={remaining === undefined ? 'text-muted' : `font-medium tabular-nums ${color}`}>{remaining === undefined ? loading ? 'Loading…' : 'Not reported' : `${percent.format(remaining)}% left`}</span></div>
    <div className="h-1.5 overflow-hidden rounded-full bg-ink/10" role={remaining === undefined ? undefined : 'progressbar'} aria-label={remaining === undefined ? undefined : `${label} remaining`} aria-valuemin={remaining === undefined ? undefined : 0} aria-valuemax={remaining === undefined ? undefined : 100} aria-valuenow={remaining} aria-valuetext={remaining === undefined ? undefined : `${percent.format(remaining)}% remaining`}>
      {remaining !== undefined && <div className={`h-full rounded-full bg-current ${color}`} style={{ width: `${remaining}%` }} />}
    </div>
    {reset && Number.isFinite(reset.getTime()) && <p className="text-[10px] text-muted">Resets {reset.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })}</p>}
  </div>;
}

/** Show Claude's own context breakdown and usage without inferring subscription quota. */
export function ClaudeStatus({ state, onClose }: { state: Snapshot; onClose: () => void }) {
  const [data, setData] = useState<Status>();
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    let canceled = false;
    setLoading(true); setError('');
    void request<Status>('accountLimits').then((value) => { if (!canceled) { setData(value); } }).catch((error: Error) => { if (!canceled) { setError(error.message); } }).finally(() => { if (!canceled) { setLoading(false); } });
    return () => { canceled = true; };
  }, [state.chat.id, refresh]);
  return <section aria-label="Claude session status" className="@container/status max-h-[40vh] space-y-3 overflow-y-auto rounded-xl bg-raised px-3 py-2 text-xs">
    <header className="flex items-center gap-2 text-muted"><span className="flex-1">Claude status</span><button disabled={loading} className="rounded px-1 hover:bg-line" onClick={() => setRefresh((value) => value + 1)}>{loading ? 'Refreshing…' : 'Refresh'}</button><button className="rounded px-1 hover:bg-line" onClick={onClose}>Close</button></header>
    {data?.rateLimitsAvailable === false ? <p className="text-muted">Subscription limits are unavailable for this sign-in.</p> : <div className="grid gap-x-5 gap-y-3 @min-[480px]/status:grid-cols-2">
      <LimitBar label="5h limit" window={data?.rateLimits?.five_hour} loading={loading && !data} />
      <LimitBar label="Weekly limit" window={data?.rateLimits?.seven_day} loading={loading && !data} />
    </div>}
    {data?.rateLimitsError && <p role="alert" className="text-attention">{data.rateLimitsError}</p>}
    <p className="truncate select-all text-muted">Session: {state.chat.threadId || 'New chat'}</p>
    {data?.context?.totalTokens !== undefined && <p>Context: {number.format(data.context.totalTokens)}{data.context.maxTokens ? ` / ${number.format(data.context.maxTokens)} tokens` : ' tokens'}</p>}
    {data?.context?.categories?.map((category) => <div key={category.name} className="flex gap-3 text-muted"><span className="flex-1">{category.name}</span><span>{number.format(category.tokens)}</span></div>)}
    {!!data?.context?.memoryFiles?.length && <details><summary className="cursor-pointer">Loaded instructions</summary>{data.context.memoryFiles.map((file, index) => <p key={index} className="break-all py-1 text-muted">{file.path || file.name}</p>)}</details>}
    {data?.usage?.input_tokens !== undefined && <p>Last turn: {number.format(data.usage.input_tokens)} input, {number.format(data.usage.output_tokens || 0)} output tokens</p>}
    {typeof data?.cost === 'number' && <p>Reported session cost: ${data.cost.toFixed(4)} <span className="text-muted">(API-equivalent usage)</span></p>}
    {data?.rateLimit?.status && <p>Rate limit: {data.rateLimit.status}{data.rateLimit.resetsAt ? ` · resets ${new Date(data.rateLimit.resetsAt * 1000).toLocaleString()}` : ''}</p>}
    {(error || data?.contextError) && <p role="alert" className="text-attention">{error || data?.contextError}</p>}
  </section>;
}
