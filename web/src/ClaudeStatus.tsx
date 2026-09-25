import { useEffect, useState } from 'react';
import { request } from './bridge';
import type { Snapshot } from './types';

type Status = {
  context?: { totalTokens?: number; maxTokens?: number; categories?: { name: string; tokens: number }[]; memoryFiles?: { path?: string; name?: string }[] };
  contextError?: string;
  usage?: { input_tokens?: number; output_tokens?: number };
  cost?: number;
  rateLimit?: { status?: string; resetsAt?: number; rateLimitType?: string };
};
const number = new Intl.NumberFormat('en');

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
  return <section aria-label="Claude session status" className="max-h-[40vh] space-y-2 overflow-y-auto rounded-xl bg-raised px-3 py-2 text-xs">
    <header className="flex items-center gap-2 text-muted"><span className="flex-1">Claude status</span><button disabled={loading} className="rounded px-1 hover:bg-line" onClick={() => setRefresh((value) => value + 1)}>{loading ? 'Refreshing…' : 'Refresh'}</button><button className="rounded px-1 hover:bg-line" onClick={onClose}>Close</button></header>
    <p className="truncate select-all text-muted">Session: {state.chat.threadId || 'New chat'}</p>
    {data?.context?.totalTokens !== undefined && <p>Context: {number.format(data.context.totalTokens)}{data.context.maxTokens ? ` / ${number.format(data.context.maxTokens)} tokens` : ' tokens'}</p>}
    {data?.context?.categories?.map((category) => <div key={category.name} className="flex gap-3 text-muted"><span className="flex-1">{category.name}</span><span>{number.format(category.tokens)}</span></div>)}
    {!!data?.context?.memoryFiles?.length && <details><summary className="cursor-pointer">Loaded instructions</summary>{data.context.memoryFiles.map((file, index) => <p key={index} className="break-all py-1 text-muted">{file.path || file.name}</p>)}</details>}
    {data?.usage?.input_tokens !== undefined && <p>Last turn: {number.format(data.usage.input_tokens)} input, {number.format(data.usage.output_tokens || 0)} output tokens</p>}
    {typeof data?.cost === 'number' && <p>Reported session cost: ${data.cost.toFixed(4)} <span className="text-muted">(API-equivalent usage)</span></p>}
    {data?.rateLimit?.status && <p>Rate limit: {data.rateLimit.status}{data.rateLimit.resetsAt ? ` · resets ${new Date(data.rateLimit.resetsAt * 1000).toLocaleString()}` : ''}</p>}
    {!loading && !data?.rateLimit?.status && <p className="text-muted">Subscription limits have not been reported.</p>}
    {(error || data?.contextError) && <p role="alert" className="text-attention">{error || data?.contextError}</p>}
  </section>;
}
