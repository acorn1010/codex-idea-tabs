import { useId, useState } from 'react';
import type { Json } from './types';

const rateFormat = new Intl.NumberFormat('en', { maximumFractionDigits: 1 });
const countFormat = new Intl.NumberFormat('en');
const secondsFormat = new Intl.NumberFormat('en', { maximumFractionDigits: 2 });

/** Show the measured rate and expandable details in the shared composer status row. */
export function GenerationSpeed({ speed }: { speed?: Json }) {
  const [expanded, setExpanded] = useState(false);
  const detailsId = useId();
  const rate = speed?.tokensPerSecond;
  const tokens = speed?.outputTokens;
  const duration = speed?.durationMs;
  if (typeof rate !== 'number' || !Number.isFinite(rate) || rate <= 0
    || typeof tokens !== 'number' || !Number.isFinite(tokens) || tokens <= 0
    || typeof duration !== 'number' || !Number.isFinite(duration) || duration <= 0) { return null; }
  return <>
    <button type="button" aria-expanded={expanded} aria-controls={detailsId} onClick={() => setExpanded(!expanded)} aria-label={`Generation speed: ${rateFormat.format(rate)} tokens per second, last response`} className="col-start-2 flex items-center justify-end gap-2 rounded-md px-2 py-1 text-[11px] whitespace-nowrap text-muted hover:bg-raised hover:text-ink active:bg-line focus-visible:outline-1 focus-visible:outline-accent">
      <span>Last response</span><span className="font-medium tabular-nums text-ink">~{rateFormat.format(rate)} tok/s</span>
    </button>
    {expanded && <div id={detailsId} className="col-span-2 max-w-80 space-y-1 justify-self-end rounded-lg bg-raised px-3 py-2 text-left text-[11px] text-muted">
      <p className="tabular-nums">{countFormat.format(tokens)} output tokens in {secondsFormat.format(duration / 1000)} s</p>
      <p>Approximate response speed. Tool runs and approval waits are excluded. Network delay can affect this value.</p>
      <p>Updates when token usage arrives. Counts may include reasoning and tool arguments.</p>
    </div>}
  </>;
}
