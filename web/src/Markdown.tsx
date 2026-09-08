import { memo, useEffect, useRef, useState, type MouseEvent } from 'react';
import { createPortal } from 'react-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { request } from './bridge';
import { Icon } from './icons';
import { ImageMenu } from './ImageMenu';

/** Local images travel through the backend file bridge, never through Windows UNC URLs in the browser. */
export const ImagePreview = memo(function ImagePreview({ path, alt = 'Image', compact = false }: { path: string; alt?: string; compact?: boolean }) {
  const [url, setUrl] = useState(path.startsWith('data:image/') ? path : '');
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const [contextMenu, setContextMenu] = useState<{ image: HTMLImageElement; x: number; y: number }>();
  const imageMenu = (event: MouseEvent<HTMLElement>) => {
    const image = event.currentTarget instanceof HTMLImageElement ? event.currentTarget : event.currentTarget.querySelector('img');
    if (!image) { return; }
    event.preventDefault(); event.stopPropagation();
    const bounds = image.getBoundingClientRect();
    setContextMenu({ image, x: event.clientX || bounds.left + 12, y: event.clientY || bounds.top + 12 });
  };
  useEffect(() => {
    setError('');
    if (path.startsWith('data:image/')) { setUrl(path); return; }
    setUrl('');
    let canceled = false;
    request<{ url: string }>('readImage', { path }).then((value) => { if (!canceled) { setUrl(value.url); } }).catch((error: Error) => { if (!canceled) { setError(error.message); } });
    return () => { canceled = true; };
  }, [path]);
  const failed = () => { setUrl(''); setError('This image could not be decoded. Open it in the editor.'); };
  const openInEditor = () => { void request('openLink', { path }).catch((error: Error) => setError(error.message)); };
  return <span className={compact ? 'inline-flex min-w-0 max-w-full' : 'my-2 inline-flex max-w-full flex-col gap-1'}>
    {compact ? <button ref={trigger} onContextMenu={imageMenu} type="button" title={`Preview ${alt}`} aria-label={`Preview ${alt}`} onClick={() => setExpanded(true)} className="flex min-w-0 items-center gap-1.5 rounded-md py-1 pr-1.5 pl-1 text-[11px] hover:bg-raised active:bg-line">
      {url ? <img src={url} alt="" onError={failed} className="size-5 shrink-0 rounded-sm bg-composer object-cover" /> : <span className="flex size-5 shrink-0 items-center justify-center text-muted"><Icon name="image" size={14} /></span>}
      <span className="truncate">{alt}</span>
    </button> : url ? <button ref={trigger} onContextMenu={imageMenu} className="max-w-full overflow-hidden rounded-lg bg-composer hover:brightness-110 active:brightness-90" onClick={() => setExpanded(true)} aria-label={`Preview ${alt}`}><img src={url} alt={alt} loading="lazy" onError={failed} className="max-h-72 max-w-full object-contain" /></button> : <span className="rounded-lg bg-raised px-4 py-3 text-muted">{error || 'Loading image…'}</span>}
    {!compact && !path.startsWith('data:') && <button className="self-start text-xs text-accent hover:underline active:text-ink" onClick={openInEditor}>Open in editor ↗</button>}
    {!compact && url && error && <span role="alert" className="text-xs text-red-400">{error}</span>}
    {contextMenu && <ImageMenu key={`${contextMenu.x}-${contextMenu.y}`} {...contextMenu} close={() => setContextMenu(undefined)} />}
    {expanded && <ImageDialog imageMenu={imageMenu} returnFocus={trigger.current} url={url} alt={alt} error={error} close={() => setExpanded(false)} failed={failed} openInEditor={path.startsWith('data:') ? undefined : openInEditor} />}
  </span>;
});

function ImageDialog({ imageMenu, returnFocus, url, alt, error, close, failed, openInEditor }: { imageMenu: (event: MouseEvent<HTMLElement>) => void; returnFocus: HTMLButtonElement | null; url: string; alt: string; error: string; close: () => void; failed: () => void; openInEditor?: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    return () => { element?.close(); returnFocus?.focus(); };
  }, [returnFocus]);
  return createPortal(<dialog ref={dialog} aria-label={alt} onCancel={close} onClose={close} className="fixed inset-0 m-0 h-full max-h-none w-full max-w-none bg-surface/98 p-4 text-ink backdrop:bg-black/60">
    <div className="flex h-full flex-col gap-3">
      <div className="flex items-center gap-3"><span className="min-w-0 flex-1 truncate text-sm" title={alt}>{alt}</span><button type="button" className="flex size-8 shrink-0 items-center justify-center rounded-md hover:bg-raised active:bg-line" autoFocus aria-label="Close image preview" onClick={close}><Icon name="close" /></button></div>
      {url ? <img onContextMenu={imageMenu} src={url} alt={alt} onError={failed} className="min-h-0 flex-1 object-contain" /> : <div role={error ? 'alert' : 'status'} className="flex flex-1 items-center justify-center text-sm text-muted">{error || 'Loading image…'}</div>}
      {url && error && <p role="alert" className="text-xs text-red-400">{error}</p>}
      {openInEditor && <button type="button" onClick={openInEditor} className="self-start rounded px-2 py-1 text-xs text-accent hover:bg-raised active:bg-line">Open in editor ↗</button>}
    </div>
  </dialog>, document.body);
}

/** Render Markdown without raw HTML. Link clicks are handled by the IDE's file and browser actions. */
export const Markdown = memo(function Markdown({ text }: { text: string }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]} urlTransform={(url) => url} components={{
    p: ({ children }) => <p className="my-2 leading-relaxed break-words">{children}</p>,
    h1: ({ children }) => <h1 className="mt-5 mb-2 text-lg font-semibold">{children}</h1>,
    h2: ({ children }) => <h2 className="mt-4 mb-2 text-base font-semibold">{children}</h2>,
    h3: ({ children }) => <h3 className="mt-3 mb-1 font-semibold">{children}</h3>,
    ul: ({ children }) => <ul className="my-2 list-disc space-y-1 pl-5">{children}</ul>,
    ol: ({ children }) => <ol className="my-2 list-decimal space-y-1 pl-5">{children}</ol>,
    li: ({ children }) => <li className="pl-1 leading-relaxed">{children}</li>,
    blockquote: ({ children }) => <blockquote className="my-3 border-l-2 border-accent/50 pl-3 text-muted">{children}</blockquote>,
    pre: ({ children }) => <pre className="my-3 overflow-x-auto rounded-lg bg-composer p-3 text-xs leading-relaxed">{children}</pre>,
    code: ({ children, className }) => <code className={`${className || ''} rounded bg-raised px-1 py-0.5 text-[.92em]`}>{children}</code>,
    a: ({ href, children }) => <a href="#" className="text-accent underline decoration-accent/30 underline-offset-2 hover:decoration-accent active:bg-accent/15 active:text-ink" onClick={(event) => { event.preventDefault(); if (href) { void request('openLink', { path: href }); } }}>{children}</a>,
    img: ({ src, alt }) => typeof src === 'string' ? <ImagePreview path={src} alt={alt} /> : null,
    table: ({ children }) => <div className="my-3 overflow-x-auto rounded"><table className="w-full border-collapse text-left text-xs">{children}</table></div>,
    th: ({ children }) => <th className="border-b border-line bg-raised px-3 py-2 font-semibold">{children}</th>,
    td: ({ children }) => <td className="border-b border-line px-3 py-2 align-top">{children}</td>,
    hr: () => <hr className="my-4 border-line" />,
  }}>{text}</ReactMarkdown>;
});
