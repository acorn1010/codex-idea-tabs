import type { Json } from './types';

declare global { interface Window { __codexSend?: (message: string) => void } }

let sequence = 0;
const pending = new Map<number, { resolve: (value: Json) => void; reject: (error: Error) => void; timeout: number }>();

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
