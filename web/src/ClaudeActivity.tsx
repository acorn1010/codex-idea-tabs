import { useEffect, useState } from 'react';
import type { ClaudeActivityState } from './types';
import { Icon } from './icons';

/** Keep background work visible beside the composer without polling or scanning the transcript. */
export function ClaudeActivity({ activity }: { activity?: ClaudeActivityState }) {
  const tasks = activity?.tasks ?? [];
  const wakeup = activity?.wakeup;
  const scheduled = wakeup?.scheduledFor !== undefined;
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    setNow(Date.now());
    const delay = (wakeup?.scheduledFor ?? 0) - Date.now();
    if (delay <= 0 || delay > 2_147_483_647) { return; }
    const timer = window.setTimeout(() => setNow(Date.now()), delay);
    return () => window.clearTimeout(timer);
  }, [wakeup?.scheduledFor]);
  if (!tasks.length && !scheduled) { return null; }
  const due = wakeup?.scheduledFor ? new Date(wakeup.scheduledFor) : undefined;
  const wakeupLabel = due ? `${due.getTime() <= now ? 'Waiting for wakeup' : 'Wakeup scheduled'} · ${due.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}` : 'Wakeup scheduled';
  return <section aria-label="Claude background activity" className="rounded-lg border border-accent/20 bg-accent/5 px-3 py-2 text-xs">
    <div role="status" className="flex flex-wrap items-center gap-x-4 gap-y-2 text-accent">
      {tasks.length > 0 && <span className="flex items-center gap-2"><Icon name="layers" size={14} /><span>{tasks.length} background task{tasks.length === 1 ? '' : 's'} running</span></span>}
      {scheduled && <span title={due?.toLocaleString()} className="flex items-center gap-2"><Icon name="clock" size={14} /><span>{wakeupLabel}</span></span>}
    </div>
    {tasks.length === 1 && <p className="mt-1.5 truncate text-muted" title={tasks[0].description}>{tasks[0].description}</p>}
    {tasks.length > 1 && <details className="mt-1.5 text-muted"><summary className="w-fit cursor-pointer rounded hover:text-ink">View tasks</summary><ul className="mt-2 max-h-28 space-y-1.5 overflow-y-auto">{tasks.map((task) => <li key={task.id} className="break-words">{task.description}</li>)}</ul></details>}
    {scheduled && wakeup?.reason && <p className="mt-1.5 truncate text-muted" title={wakeup.reason}>{wakeup.reason}</p>}
  </section>;
}
