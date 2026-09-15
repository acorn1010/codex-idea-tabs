declare global { interface Window { __codexTextNavigation?: boolean } }

/** Translate native IDEA actions through Chromium's word and visual-line selection behavior. */
export function installTextNavigation(): (() => void) | undefined {
  if (!window.__codexTextNavigation) { return; }
  let composing = false;
  const textField = () => {
    const field = document.activeElement;
    return (field instanceof HTMLTextAreaElement || field instanceof HTMLInputElement)
      && !field.disabled && field.selectionStart !== null ? field : undefined;
  };
  const reportFocus = () => window.__codexSend?.(JSON.stringify({ method: 'textFocus', params: { focused: !!textField() && !composing } }));
  const blur = () => queueMicrotask(reportFocus);
  const compositionStart = () => { composing = true; reportFocus(); };
  const compositionEnd = () => { composing = false; reportFocus(); };
  const navigate = (event: Event) => {
    if (composing || !textField()) { return; }
    const actions: Record<string, ['backward' | 'forward', 'word' | 'lineboundary']> = {
      EditorPreviousWord: ['backward', 'word'],
      EditorNextWord: ['forward', 'word'],
      EditorLineStart: ['backward', 'lineboundary'],
      EditorLineEnd: ['forward', 'lineboundary'],
    };
    const id = (event as CustomEvent<string>).detail;
    if (typeof id !== 'string') { return; }
    const extend = id.endsWith('WithSelection');
    const action = actions[extend ? id.slice(0, -'WithSelection'.length) : id];
    if (!action) { return; }
    window.getSelection()?.modify(extend ? 'extend' : 'move', ...action);
  };
  document.addEventListener('focusin', reportFocus);
  document.addEventListener('focusout', blur);
  document.addEventListener('compositionstart', compositionStart);
  document.addEventListener('compositionend', compositionEnd);
  window.addEventListener('codex-text-navigation', navigate);
  reportFocus();
  return () => {
    document.removeEventListener('focusin', reportFocus);
    document.removeEventListener('focusout', blur);
    document.removeEventListener('compositionstart', compositionStart);
    document.removeEventListener('compositionend', compositionEnd);
    window.removeEventListener('codex-text-navigation', navigate);
    window.__codexSend?.(JSON.stringify({ method: 'textFocus', params: { focused: false } }));
  };
}
