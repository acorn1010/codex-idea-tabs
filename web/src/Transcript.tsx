import { memo, useState } from 'react';
import type { Item, Json } from './types';
import { itemText, toolLabel } from './format';
import { Markdown, ImagePreview } from './Markdown';
import { Icon } from './icons';
import { request } from './bridge';

const Message = memo(function Message({ item }: { item: Item }) {
  const [copied, setCopied] = useState(false);
  const text = itemText(item);
  const user = item.type === 'userMessage';
  const copy = () => { void request('copy', { text }).then(() => { setCopied(true); window.setTimeout(() => setCopied(false), 1500); }); };
  return <article className={`group min-w-0 ${user ? 'ml-auto max-w-[92%]' : 'w-full'}`}>
    <div className={user ? 'rounded-xl bg-raised px-3.5 py-1.5' : ''}>
      <Markdown text={text} />
      {(item.content || []).filter((part) => part.type === 'localImage' || part.type === 'image').map((part, index) => <ImagePreview key={index} path={String(part.path || part.url || '')} alt="Attached image" />)}
    </div>
    {!user && text && <div className="mt-1 flex h-5 items-center gap-3 text-muted opacity-0 group-hover:opacity-100 focus-within:opacity-100"><button title="Copy response" aria-label="Copy response" onClick={copy} className="hover:text-ink active:text-accent"><Icon name={copied ? 'check' : 'copy'} size={13} /></button></div>}
  </article>;
});

const Tool = memo(function Tool({ item }: { item: Item }) {
  const [open, setOpen] = useState(false);
  const label = toolLabel(item);
  const failed = item.status === 'failed' || item.status === 'declined';
  const content = item.aggregatedOutput || itemText(item) || JSON.stringify(item.result || item.arguments || item, null, 2);
  const result = item.result as Json | undefined;
  const imagePath = typeof item.savedPath === 'string' ? item.savedPath : typeof item.path === 'string' && /image/i.test(item.type) ? item.path : typeof result?.path === 'string' && /image/i.test(item.type) ? result.path : item.type === 'imageGeneration' && typeof item.result === 'string' && item.result.length > 100 ? `data:image/png;base64,${item.result}` : '';
  const images = (Array.isArray(result?.content) ? result.content : []) as Json[];
  return <div className="min-w-0 text-xs">
    <button onClick={() => setOpen(!open)} aria-expanded={open} className={`flex w-full items-center gap-2 rounded-md py-1.5 text-left hover:bg-raised active:bg-line ${failed ? 'text-red-400' : 'text-muted hover:text-ink active:text-accent'}`}>
      <Icon name="chevron" size={12} style={{ transform: open ? 'rotate(90deg)' : undefined }} />
      <Icon name={item.type === 'fileChange' ? 'file' : item.type === 'webSearch' ? 'search' : 'code'} size={13} />
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {item.status === 'completed' && <Icon name="check" size={12} />}
      {failed && <span>Failed</span>}
    </button>
    {item.type === 'fileChange' && item.changes?.map((change, index) => <button key={index} className="ml-6 flex max-w-[calc(100%-1.5rem)] items-center gap-2 py-1 text-accent hover:underline active:text-ink" onClick={() => void request('openLink', { path: String(change.path || '') })}><Icon name="file" size={12} /><span className="truncate">{String(change.path || '')}</span></button>)}
    {imagePath && <ImagePreview path={imagePath} alt="Generated image" />}
    {images.filter((part) => part.type === 'image' && part.data).map((part, index) => <ImagePreview key={index} path={`data:${part.mimeType || 'image/png'};base64,${part.data}`} alt="Generated image" />)}
    {open && <pre className="mt-1 max-h-60 overflow-auto rounded-lg bg-composer p-3 text-[11px] leading-relaxed whitespace-pre-wrap break-words">{content}</pre>}
  </div>;
});

/** Render a bounded window initially so returning to a long conversation does not build thousands of DOM nodes. */
export function Transcript({ items }: { items: Item[] }) {
  const [limit, setLimit] = useState(80);
  const visible = items.slice(-limit);
  return <div className="space-y-3">
    {items.length > limit && <button className="mb-3 w-full rounded-lg py-2 text-xs text-muted hover:bg-raised active:bg-line" onClick={() => setLimit(limit + 80)}>Show earlier messages ({items.length - limit})</button>}
    {visible.map((item) => item.type === 'userMessage' || item.type === 'agentMessage' ? <Message key={item.id} item={item} /> : <Tool key={item.id} item={item} />)}
  </div>;
}
