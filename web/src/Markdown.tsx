import { memo, useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { request } from './bridge';
import { Icon } from './icons';

/** Local images travel through the backend file bridge, never through Windows UNC URLs in the browser. */
export const ImagePreview = memo(function ImagePreview({ path, alt = 'Image' }: { path: string; alt?: string }) {
  const [url, setUrl] = useState(path.startsWith('data:image/') ? path : '');
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState(false);
  useEffect(() => {
    if (path.startsWith('data:image/')) { return; }
    let canceled = false;
    request<{ url: string }>('readImage', { path }).then((value) => { if (!canceled) { setUrl(value.url); } }).catch((error: Error) => { if (!canceled) { setError(error.message); } });
    return () => { canceled = true; };
  }, [path]);
  return <span className="my-2 inline-flex max-w-full flex-col gap-1">
    {url ? <button className="max-w-full overflow-hidden rounded-lg bg-composer hover:brightness-110 active:brightness-90" onClick={() => setExpanded(true)} aria-label={`Preview ${alt}`}><img src={url} alt={alt} loading="lazy" onError={() => { setUrl(''); setError('This image could not be decoded. Open it in the editor.'); }} className="max-h-72 max-w-full object-contain" /></button> : <span className="rounded-lg bg-raised px-4 py-3 text-muted">{error || 'Loading image…'}</span>}
    {!path.startsWith('data:') && <button className="self-start text-xs text-accent hover:underline active:text-ink" onClick={() => request('openLink', { path }).catch((error: Error) => setError(error.message))}>Open in editor ↗</button>}
    {url && error && <span role="alert" className="text-xs text-red-400">{error}</span>}
    {expanded && <span role="dialog" aria-modal="true" aria-label={alt} className="fixed inset-0 z-50 flex flex-col bg-surface/98 p-4" onKeyDown={(event) => { if (event.key === 'Escape') { setExpanded(false); } }}>
      <span className="flex items-center justify-between pb-3"><span>{alt}</span><button className="flex size-8 items-center justify-center rounded-md hover:bg-raised active:bg-line" autoFocus aria-label="Close image preview" onClick={() => setExpanded(false)}><Icon name="close" /></button></span>
      <img src={url} alt={alt} className="min-h-0 flex-1 object-contain" />
    </span>}
  </span>;
});

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
