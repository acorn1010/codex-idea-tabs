import { memo, useEffect, useRef, useState } from 'react';
import type { Item, Json } from './types';
import { itemText, toolLabel } from './format';
import { Markdown, ImagePreview } from './Markdown';
import { Icon } from './icons';
import { request } from './bridge';

type EditMessage = (itemId: string, text: string) => Promise<void>;

const Message = memo(function Message({ item, editing, onEdit, onEditing }: { item: Item; editing: boolean; onEdit: EditMessage; onEditing: (id?: string) => void }) {
  const [copied, setCopied] = useState(false);
  const [draft, setDraft] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const field = useRef<HTMLTextAreaElement>(null);
  const editButton = useRef<HTMLButtonElement>(null);
  const text = itemText(item);
  const user = item.type === 'userMessage';
  const retained = (item.content || []).filter((part) => part.type !== 'text');
  useEffect(() => {
    if (editing) { field.current?.focus(); }
  }, [editing]);
  useEffect(() => {
    if (field.current) { field.current.style.height = 'auto'; field.current.style.height = `${Math.min(320, field.current.scrollHeight)}px`; }
  }, [editing, draft]);
  const cancel = () => { onEditing(); setError(''); window.requestAnimationFrame(() => editButton.current?.focus()); };
  const save = async () => {
    if (saving || (!draft.trim() && !retained.length)) { return; }
    setSaving(true); setError('');
    try { await onEdit(item.id, draft); onEditing(); }
    catch (error) { setError((error as Error).message); }
    finally { setSaving(false); }
  };
  const copy = () => { void request('copy', { text }).then(() => { setCopied(true); window.setTimeout(() => setCopied(false), 1500); }); };
  return <article data-message-id={item.id} data-message-role={user ? 'user' : 'assistant'} className={`group relative min-w-0 ${user ? `ml-auto max-w-[92%] ${editing ? 'w-full' : ''}` : 'w-full'}`}>
    <div className={user ? 'rounded-xl bg-raised px-3.5 py-1.5' : ''}>
      {editing ? <div className="flex min-w-0 flex-col gap-2 py-1.5">
        <textarea ref={field} aria-label="Edit message text" value={draft} disabled={saving} rows={3} spellCheck={false} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => {
          if (event.nativeEvent.isComposing) { return; }
          if (event.key === 'Escape' && !saving) { event.preventDefault(); event.stopPropagation(); cancel(); }
          if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); void save(); }
        }} className="block max-h-80 min-h-20 w-full cursor-text resize-y rounded-md bg-composer px-2.5 py-2 text-[13px] leading-relaxed focus-visible:ring-1 focus-visible:ring-accent/50" />
        {error && <p role="alert" className="break-words text-xs text-red-400">{error}</p>}
        <div className="flex flex-wrap items-center justify-end gap-2">
          <button disabled={saving} onClick={cancel} className="rounded-md px-2.5 py-1.5 text-xs text-muted enabled:hover:bg-line enabled:hover:text-ink enabled:active:bg-surface">Cancel</button>
          <button disabled={saving || (!draft.trim() && !retained.length)} title="Continue in a new tab and keep the original chat (Ctrl+Enter or ⌘Enter)" onClick={() => void save()} className="flex items-center gap-1.5 rounded-md bg-accent px-2.5 py-1.5 text-xs font-medium text-surface enabled:hover:brightness-110 enabled:active:brightness-90"><Icon name="newTab" size={14} />{saving ? 'Opening chat…' : 'Edit and resend'}</button>
        </div>
      </div> : <Markdown text={text} />}
      {(item.content || []).filter((part) => part.type === 'localImage' || part.type === 'image').map((part, index) => <ImagePreview key={index} path={String(part.path || part.url || '')} alt="Attached image" />)}
    </div>
    {user && !editing && <div className="pointer-events-none absolute right-1 bottom-1 flex items-center gap-1 rounded-md bg-composer p-0.5 text-muted opacity-0 shadow-sm group-hover:pointer-events-auto group-hover:opacity-100 focus-within:pointer-events-auto focus-within:opacity-100">
      <button ref={editButton} title="Edit message and continue in a new tab" aria-label="Edit message" onClick={() => { setDraft(text); setError(''); onEditing(item.id); }} className="flex size-7 items-center justify-center rounded-md hover:bg-raised hover:text-ink active:bg-line active:text-accent"><Icon name="edit" size={14} /></button>
      {text && <button title="Copy message" aria-label="Copy message" onClick={copy} className="flex size-7 items-center justify-center rounded-md hover:bg-raised hover:text-ink active:bg-line active:text-accent"><Icon name={copied ? 'check' : 'copy'} size={14} /></button>}
    </div>}
    {!user && text && <div className="pointer-events-none absolute right-1 bottom-1 rounded-md bg-surface p-0.5 text-muted opacity-0 group-hover:pointer-events-auto group-hover:opacity-100 focus-within:pointer-events-auto focus-within:opacity-100"><button title="Copy response" aria-label="Copy response" onClick={copy} className="flex size-7 items-center justify-center rounded-md hover:bg-raised hover:text-ink active:bg-line active:text-accent"><Icon name={copied ? 'check' : 'copy'} size={14} /></button></div>}
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
export function Transcript({ items, editingItemId, onEdit, onEditing }: { items: Item[]; editingItemId?: string; onEdit: EditMessage; onEditing: (id?: string) => void }) {
  const [limit, setLimit] = useState(80);
  const visible = items.slice(-limit);
  return <div className="space-y-3">
    {items.length > limit && <button className="mb-3 w-full rounded-lg py-2 text-xs text-muted hover:bg-raised active:bg-line" onClick={() => setLimit(limit + 80)}>Show earlier messages ({items.length - limit})</button>}
    {visible.map((item) => item.type === 'userMessage' || item.type === 'agentMessage' ? <Message key={item.id} item={item} editing={editingItemId === item.id} onEdit={onEdit} onEditing={onEditing} /> : <Tool key={item.id} item={item} />)}
  </div>;
}
