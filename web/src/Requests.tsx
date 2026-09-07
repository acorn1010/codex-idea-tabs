import { useState } from 'react';
import type { Json, Pending } from './types';
import { request } from './bridge';
import { Icon } from './icons';

/** Keep an explicit, actionable question above the composer until the user answers or dismisses it. */
export function RequestCard({ pending }: { pending: Pending }) {
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [form, setForm] = useState<Json>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState(true);
  const questions = pending.questions || [];
  const isQuestion = pending.method === 'item/tool/requestUserInput';
  const submit = async (decision?: string, dismiss = false) => {
    setBusy(true); setError('');
    try {
      await request('answer', { key: pending.key, decision, dismiss, content: form, answers: Object.fromEntries(questions.map((question) => [question.id, { answers: [answers[question.id] || ''] }])) });
    } catch (error) { setError((error as Error).message); setBusy(false); }
  };
  const fields = ((pending.requestedSchema as Json | undefined)?.properties || {}) as Record<string, { type?: string; title?: string; description?: string; enum?: string[] }>;
  return <section className="overflow-hidden rounded-xl border border-attention/35 bg-attention/5" aria-label={isQuestion ? 'Codex needs your input' : 'Approval needed'}>
    <button onClick={() => setExpanded(!expanded)} className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs font-medium text-attention hover:bg-attention/10 active:bg-attention/20" aria-expanded={expanded}>
      <span className="flex size-4 items-center justify-center rounded-full border border-attention text-[10px]">!</span>
      <span className="flex-1">{isQuestion ? 'Needs your input' : 'Approval needed'}</span>
      <span className="text-[10px] font-normal opacity-75">{pending.rpcId === undefined ? 'Work can continue' : 'Waiting for you'}</span><Icon name="down" size={13} />
    </button>
    {expanded && <div className="max-h-[38vh] space-y-3 overflow-y-auto border-t border-attention/15 px-3 pt-3 pb-2">
      {questions.map((question) => <fieldset key={question.id} className="space-y-2">
        <legend className="mb-2 leading-relaxed font-medium">{question.question}</legend>
        {question.options?.map((option) => <label key={option.label} className={`flex cursor-pointer items-start gap-2 rounded-lg border px-2.5 py-2 ${answers[question.id] === option.label ? 'border-attention/50 bg-attention/10' : 'border-line hover:bg-raised active:bg-line'}`}>
          <input type="radio" name={`${pending.key}-${question.id}`} value={option.label} checked={answers[question.id] === option.label} onChange={() => setAnswers({ ...answers, [question.id]: option.label })} className="mt-0.5 accent-[var(--attention)]" />
          <span><span className="text-xs">{option.label}</span>{option.description && <span className="mt-0.5 block text-[11px] leading-relaxed text-muted">{option.description}</span>}</span>
        </label>)}
        <input aria-label={`Answer: ${question.question}`} type={question.isSecret ? 'password' : 'text'} value={answers[question.id] || ''} onChange={(event) => setAnswers({ ...answers, [question.id]: event.target.value })} placeholder="Or write your own answer…" className="w-full rounded-md border border-line bg-composer px-2.5 py-2 text-xs" />
      </fieldset>)}
      {!isQuestion && <>
        <p className="text-xs leading-relaxed">{pending.reason || pending.message || 'Codex needs your permission to continue this action.'}</p>
        {pending.command && <pre className="max-h-32 overflow-auto rounded-lg bg-composer p-2 text-xs whitespace-pre-wrap">{pending.command}</pre>}
        {pending.permissions !== undefined && <pre className="max-h-32 overflow-auto rounded-lg bg-composer p-2 text-xs whitespace-pre-wrap">{JSON.stringify(pending.permissions, null, 2)}</pre>}
        {Object.entries(fields).map(([key, field]) => <label key={key} className="block space-y-1 text-xs"><span>{field.title || key}</span>
          {field.type === 'boolean' ? <input type="checkbox" checked={Boolean(form[key])} onChange={(event) => setForm({ ...form, [key]: event.target.checked })} className="ml-2" /> : field.enum ? <select value={String(form[key] || '')} onChange={(event) => setForm({ ...form, [key]: event.target.value })} className="block w-full rounded border border-line bg-composer p-2"><option value="">Choose…</option>{field.enum.map((value) => <option key={value}>{value}</option>)}</select> : <input className="block w-full rounded border border-line bg-composer p-2" value={String(form[key] || '')} onChange={(event) => setForm({ ...form, [key]: field.type === 'number' || field.type === 'integer' ? Number(event.target.value) : event.target.value })} />}
        </label>)}
      </>}
      {error && <p role="alert" className="text-xs text-red-400">{error}</p>}
      <div className="flex justify-end gap-2 pb-1">
        {isQuestion ? <>
          {pending.rpcId === undefined && <button disabled={busy} onClick={() => void submit(undefined, true)} className="rounded-md px-3 py-1.5 text-xs text-muted hover:bg-raised active:bg-line">Dismiss</button>}
          <button disabled={busy || questions.some((question) => !answers[question.id]?.trim())} onClick={() => void submit()} className="flex items-center gap-2 rounded-md bg-attention px-3 py-1.5 text-xs font-medium text-composer enabled:hover:brightness-110 enabled:active:brightness-90 enabled:active:translate-y-px">{busy ? 'Sending…' : 'Send answer'}<Icon name="arrow" size={13} /></button>
        </> : <>
          <button disabled={busy} onClick={() => void submit('decline')} className="rounded-md border border-line px-3 py-1.5 text-xs hover:bg-raised active:bg-line">Decline</button>
          <button disabled={busy} onClick={() => void submit('accept')} className="rounded-md bg-attention px-3 py-1.5 text-xs font-medium text-composer enabled:hover:brightness-110 enabled:active:brightness-90 enabled:active:translate-y-px">{busy ? 'Sending…' : 'Allow once'}</button>
        </>}
      </div>
    </div>}
  </section>;
}
