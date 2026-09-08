import type { Json } from './types';

declare global { interface Window { __codexSend?: (message: string) => void; __codexNativeCursor?: boolean } }

let sequence = 0;
const pending = new Map<number, { resolve: (value: Json) => void; reject: (error: Error) => void; timeout: number }>();

/** Mirror cursor changes only when remote JCEF cannot update the native mouse cursor itself. */
export function installNativeCursor(): (() => void) | undefined {
  if (!window.__codexNativeCursor) { return; }
  let previous = '';
  const update = (target: EventTarget | null) => {
    const value = target instanceof Element ? getComputedStyle(target).cursor : 'default';
    if (value === previous) { return; }
    previous = value;
    window.__codexSend?.(JSON.stringify({ method: 'cursor', params: { value } }));
  };
  const over = (event: PointerEvent) => update(event.target);
  const out = (event: PointerEvent) => update(event.relatedTarget);
  document.addEventListener('pointerover', over);
  document.addEventListener('pointerout', out);
  return () => {
    document.removeEventListener('pointerover', over);
    document.removeEventListener('pointerout', out);
  };
}

window.addEventListener('codex-reply', (event) => {
  const { id, result, error } = (event as CustomEvent).detail;
  const request = pending.get(id);
  if (!request) { return; }
  window.clearTimeout(request.timeout);
  pending.delete(id);
  if (error) { request.reject(new Error(error)); } else { request.resolve(result || {}); }
});

/** Calls the native IDE bridge. Every operation reports failures to its initiating control. */
export function request<T = Json>(method: string, params: Json = {}): Promise<T> {
  return new Promise((resolve, reject) => {
    if (!window.__codexSend) { reject(new Error('Open this chat inside IntelliJ IDEA.')); return; }
    const id = ++sequence;
    const timeout = window.setTimeout(() => { pending.delete(id); reject(new Error('This request took too long. Check the Codex connection.')); }, 120_000);
    pending.set(id, { resolve: (value) => resolve(value as T), reject, timeout });
    window.__codexSend(JSON.stringify({ id, method, params }));
  });
}
