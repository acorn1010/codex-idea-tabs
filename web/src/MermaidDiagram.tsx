import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { request } from './bridge';
import { Icon } from './icons';
import { renderMermaid } from './mermaidRenderer';

type RenderedDiagram = Awaited<ReturnType<typeof renderMermaid>>;
type Result = { source: string; light: boolean; diagram?: RenderedDiagram; error?: string };
const minimumPreviewScale = 0.8;
const iconButton = 'flex size-7 shrink-0 items-center justify-center rounded-md text-muted enabled:hover:bg-raised enabled:hover:text-ink enabled:active:bg-line disabled:opacity-40';

/** Preview Mermaid offline, retaining source and copy controls while a streamed diagram is incomplete. */
export function MermaidDiagram({ source }: { source: string }) {
  const [light, setLight] = useState(document.documentElement.dataset.theme === 'light');
  const [result, setResult] = useState<Result>();
  const [showSource, setShowSource] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [copied, setCopied] = useState(false);
  const [copying, setCopying] = useState(false);
  const [copyError, setCopyError] = useState('');
  const trigger = useRef<HTMLButtonElement>(null);
  const current = result?.source === source && result.light === light ? result : undefined;
  const diagram = current?.diagram;
  useEffect(() => {
    const observer = new MutationObserver(() => setLight(document.documentElement.dataset.theme === 'light'));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      void renderMermaid(source, light, controller.signal).then((diagram) => {
        if (!controller.signal.aborted) { setResult({ source, light, diagram }); }
      }).catch(() => {
        if (!controller.signal.aborted) { setResult({ source, light, error: 'Could not render this diagram. Showing source.' }); }
      });
    }, 200);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [source, light]);
  useEffect(() => {
    setCopied(false); setCopyError('');
  }, [source]);
  useEffect(() => {
    if (!copied) { return; }
    const timer = window.setTimeout(() => setCopied(false), 1500);
    return () => window.clearTimeout(timer);
  }, [copied]);
  const copy = async () => {
    if (copying) { return; }
    setCopying(true); setCopyError('');
    try { await request('copy', { text: source }); setCopied(true); }
    catch (error) { setCopyError((error as Error).message); }
    finally { setCopying(false); }
  };
  const failed = () => setResult({ source, light, error: 'Could not display this diagram. Showing source.' });
  const copyLabel = copyError ? 'Copy failed. Try again' : copied ? 'Diagram source copied' : 'Copy diagram source';
  return <div data-code-block data-mermaid className="my-3 min-w-0 overflow-hidden rounded-lg border border-line bg-composer">
    <div className="flex flex-wrap items-center gap-1 border-b border-line px-2 py-1 text-xs">
      <span className="mr-auto px-1 text-muted">Mermaid</span>
      <button type="button" aria-pressed={!showSource} onClick={() => setShowSource(false)} className={`rounded px-2 py-1.5 hover:bg-raised active:bg-line ${!showSource ? 'bg-raised text-ink' : 'text-muted'}`}>Diagram</button>
      <button type="button" aria-pressed={showSource} onClick={() => setShowSource(true)} className={`rounded px-2 py-1.5 hover:bg-raised active:bg-line ${showSource ? 'bg-raised text-ink' : 'text-muted'}`}>Source</button>
      <button type="button" aria-label={copyLabel} title={copyError || copyLabel} disabled={copying} onClick={() => void copy()} className={iconButton}><Icon name={copied ? 'check' : 'copy'} size={14} /></button>
      <button ref={trigger} type="button" aria-label="Expand diagram" title="Expand diagram" disabled={!diagram} onClick={() => setExpanded(true)} className={iconButton}><Icon name="newTab" size={14} /></button>
    </div>
    {!showSource && diagram ? <div role="region" aria-label="Diagram" tabIndex={0} className="max-h-80 overflow-auto p-3"><img src={diagram.url} alt={diagram.label} width={diagram.width} height={diagram.height} onError={failed} style={{ minWidth: diagram.width * minimumPreviewScale }} className="mx-auto block h-auto max-w-full" /></div> : <>
      {!showSource && <p role="status" className="px-3 pt-3 text-xs text-muted">{current?.error || 'Rendering diagram…'}</p>}
      <pre className="m-0 overflow-x-auto p-3 text-xs leading-relaxed"><code>{source}</code></pre>
    </>}
    <span role="status" className="sr-only">{copyError ? `Copy failed: ${copyError}` : copied ? 'Diagram source copied' : ''}</span>
    {expanded && diagram && <DiagramDialog diagram={diagram} returnFocus={trigger.current} close={() => setExpanded(false)} failed={failed} />}
  </div>;
}

function DiagramDialog({ diagram, returnFocus, close, failed }: { diagram: RenderedDiagram; returnFocus: HTMLButtonElement | null; close: () => void; failed: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    return () => { element?.close(); returnFocus?.focus(); };
  }, [returnFocus]);
  return createPortal(<dialog ref={dialog} aria-label="Diagram preview" onCancel={close} onClose={close} className="fixed inset-0 m-0 h-full max-h-none w-full max-w-none bg-surface p-4 text-ink backdrop:bg-black/60">
    <div className="flex h-full flex-col gap-3">
      <div className="flex items-center gap-3"><span className="min-w-0 flex-1 truncate text-sm">Diagram</span><button type="button" autoFocus aria-label="Close diagram preview" onClick={close} className={iconButton}><Icon name="close" /></button></div>
      <div role="region" aria-label="Expanded diagram" tabIndex={0} className="min-h-0 flex-1 overflow-auto rounded-lg bg-composer p-3">
        <img src={diagram.url} alt={diagram.label} width={diagram.width} height={diagram.height} onError={failed} className="mx-auto block max-w-none" />
      </div>
    </div>
  </dialog>, document.body);
}
