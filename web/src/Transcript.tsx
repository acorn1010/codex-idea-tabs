import { memo, useEffect, useId, useMemo, useRef, useState } from 'react';
import type { Attachment, Item, Json } from './types';
import { uploadAttachment, uploadDroppedFiles, useNativeFileDrop } from './attachments';
import { groupTranscript, itemText, toolFailed, toolImagePaths, toolLabel } from './format';
import { Markdown, ImagePreview } from './Markdown';
import { Icon } from './icons';
import { request } from './bridge';

type EditMessage = (itemId: string, text: string, images: Json[]) => Promise<void>;

const Message = memo(function Message({ item, editing, onEdit, onEditing }: { item: Item; editing: boolean; onEdit: EditMessage; onEditing: (id?: string) => void }) {
  const [copied, setCopied] = useState(false);
  const editButton = useRef<HTMLButtonElement>(null);
  const text = itemText(item);
  const user = item.type === 'userMessage';
  const cancel = () => { onEditing(); window.requestAnimationFrame(() => editButton.current?.focus()); };
  const copy = () => { void request('copy', { text }).then(() => { setCopied(true); window.setTimeout(() => setCopied(false), 1500); }); };
  return <article data-message-id={item.id} data-message-role={user ? 'user' : 'assistant'} className={`group relative min-w-0 ${user ? `ml-auto max-w-[92%] ${editing ? 'w-full' : ''}` : 'w-full'}`}>
    <div className={user ? 'rounded-xl bg-raised px-3.5 py-1.5' : ''}>
      {editing ? <MessageEditor item={item} onEdit={onEdit} onClose={cancel} /> : <>
        <Markdown text={text} />
        {(item.content || []).filter(isImage).map((part, index) => <ImagePreview key={index} path={String(part.path || part.url || '')} alt="Attached image" />)}
      </>}
    </div>
    {user && !editing && <div className="pointer-events-none absolute right-1 bottom-1 flex items-center gap-1 rounded-md bg-composer p-0.5 text-muted opacity-0 shadow-sm group-hover:pointer-events-auto group-hover:opacity-100 focus-within:pointer-events-auto focus-within:opacity-100">
      <button ref={editButton} title="Edit message and continue in a new tab" aria-label="Edit message" onClick={() => onEditing(item.id)} className="flex size-7 items-center justify-center rounded-md hover:bg-raised hover:text-ink active:bg-line active:text-accent"><Icon name="edit" size={14} /></button>
      {text && <button title="Copy message" aria-label="Copy message" onClick={copy} className="flex size-7 items-center justify-center rounded-md hover:bg-raised hover:text-ink active:bg-line active:text-accent"><Icon name={copied ? 'check' : 'copy'} size={14} /></button>}
    </div>}
    {!user && text && <div className="group-has-[[data-code-block]:hover]:hidden group-has-[[data-code-block]:focus-within]:hidden pointer-events-none absolute right-1 bottom-1 rounded-md bg-surface p-0.5 text-muted opacity-0 group-hover:pointer-events-auto group-hover:opacity-100 focus-within:pointer-events-auto focus-within:opacity-100"><button title="Copy response" aria-label="Copy response" onClick={copy} className="flex size-7 items-center justify-center rounded-md hover:bg-raised hover:text-ink active:bg-line active:text-accent"><Icon name={copied ? 'check' : 'copy'} size={14} /></button></div>}
  </article>;
});

function isImage(part: Json | string): part is Json { return typeof part !== 'string' && (part.type === 'localImage' || part.type === 'image'); }

function MessageEditor({ item, onEdit, onClose }: { item: Item; onEdit: EditMessage; onClose: () => void }) {
  const [draft, setDraft] = useState(() => itemText(item));
  const [images, setImages] = useState(() => (item.content || []).filter(isImage));
  const [saving, setSaving] = useState(false);
  const [uploads, setUploads] = useState(0);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState('');
  const field = useRef<HTMLTextAreaElement>(null);
  const editor = useRef<HTMLDivElement>(null);
  const live = useRef(true);
  const pending = useRef(0);
  const submitting = useRef(false);
  const retained = (item.content || []).filter((part) => typeof part !== 'string' && part.type !== 'text' && !isImage(part));
  useEffect(() => { live.current = true; field.current?.focus(); return () => { live.current = false; }; }, []);
  useEffect(() => {
    if (field.current) { field.current.style.height = 'auto'; field.current.style.height = `${Math.min(320, field.current.scrollHeight)}px`; }
  }, [draft]);
  const add = (attachment: Attachment) => {
    if (live.current) { setImages((current) => [...current, { type: 'localImage', path: attachment.path }]); }
  };
  const attach = async (operation: () => Promise<void>) => {
    if (submitting.current) { return; }
    pending.current++; setUploads(pending.current); setError('');
    try { await operation(); }
    catch (error) { if (live.current) { setError((error as Error).message); } }
    finally { pending.current--; if (live.current) { setUploads(pending.current); } }
  };
  const upload = (files: File[]) => attach(async () => {
    for (const file of files) {
      if (!live.current) { return; }
      if (!file.type.startsWith('image/') && !/\.(png|jpe?g|gif|webp|svg|bmp|avif)$/i.test(file.name)) {
        setError(`${file.name} is not an image.`); continue;
      }
      try { add(await uploadAttachment(file)); }
      catch (error) { if (live.current) { setError((error as Error).message); } }
    }
  });
  useNativeFileDrop((token) => { void attach(async () => {
    const result = await uploadDroppedFiles(token, true);
    result.files.forEach(add);
    if (live.current && result.errors.length) { setError(result.errors.join(' ')); }
  }); }, (over) => setDragging(over && !saving), editor);
  const save = async () => {
    if (submitting.current || pending.current || (!draft.trim() && !retained.length && !images.length)) { return; }
    submitting.current = true; setSaving(true); setError('');
    try { await onEdit(item.id, draft, images); if (live.current) { onClose(); } }
    catch (error) { if (live.current) { setError((error as Error).message); } }
    finally { submitting.current = false; if (live.current) { setSaving(false); } }
  };
  return <div ref={editor} data-message-editor className={`flex min-w-0 flex-col gap-2 rounded-lg py-1.5 ${dragging ? 'bg-accent/5 ring-1 ring-accent/40' : ''}`}
    onDragOver={(event) => { if (event.dataTransfer.types.includes('Files')) { event.preventDefault(); event.stopPropagation(); event.dataTransfer.dropEffect = saving ? 'none' : 'copy'; setDragging(!saving); } }}
    onDragLeave={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node)) { setDragging(false); } }}
    onDrop={(event) => { if (event.dataTransfer.files.length) { event.preventDefault(); event.stopPropagation(); setDragging(false); void upload(Array.from(event.dataTransfer.files)); } }}>
    <textarea ref={field} aria-label="Edit message text" value={draft} disabled={saving} rows={3} spellCheck={false} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => {
      if (event.nativeEvent.isComposing) { return; }
      if (event.key === 'Escape' && !saving) { event.preventDefault(); event.stopPropagation(); onClose(); }
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) { event.preventDefault(); event.stopPropagation(); if (!event.repeat) { void save(); } }
    }} onPaste={(event) => {
      if (event.clipboardData.files.length) { event.preventDefault(); event.stopPropagation(); void upload(Array.from(event.clipboardData.files)); }
    }} className="block max-h-80 min-h-20 w-full cursor-text resize-y rounded-md bg-composer px-2.5 py-2 text-[13px] leading-relaxed focus-visible:ring-1 focus-visible:ring-accent/50" />
    {(images.length > 0 || uploads > 0) && <div className="flex max-h-40 flex-wrap gap-1.5 overflow-y-auto" aria-label="Images in edited message">
      {images.map((part, index) => {
        const path = String(part.path || part.url || '');
        const name = path.startsWith('data:') ? `Image ${index + 1}` : path.split(/[\\/]/).pop()?.split(/[?#]/)[0] || `Image ${index + 1}`;
        return <span key={`${index}-${path}`} className="flex max-w-full items-center gap-1 rounded-md bg-surface pr-1 text-[11px]">
          <ImagePreview path={path} alt={name} compact />
          <button type="button" disabled={saving} aria-label={`Remove ${name}`} title={`Remove ${name}`} onClick={() => setImages((current) => current.filter((_, position) => position !== index))} className="flex size-6 shrink-0 items-center justify-center rounded text-muted enabled:hover:bg-line enabled:hover:text-ink enabled:active:bg-composer"><Icon name="close" size={11} /></button>
        </span>;
      })}
      {uploads > 0 && <span role="status" className="self-center text-[11px] text-muted">Attaching…</span>}
    </div>}
    {error && <p role="alert" className="break-words text-xs text-red-400">{error}</p>}
    <div className="flex flex-wrap items-center justify-end gap-2">
      <button type="button" disabled={saving} aria-label="Attach images" title="Attach images, or paste or drop them into this editor" onClick={() => void attach(async () => { const result = await request<{ files: Attachment[] }>('chooseImages'); result.files.forEach(add); })} className="mr-auto flex size-7 items-center justify-center rounded-md text-muted enabled:hover:bg-line enabled:hover:text-ink enabled:active:bg-surface"><Icon name="plus" size={16} /></button>
      <button type="button" disabled={saving} onClick={onClose} className="rounded-md px-2.5 py-1.5 text-xs text-muted enabled:hover:bg-line enabled:hover:text-ink enabled:active:bg-surface">Cancel</button>
      <button type="button" disabled={saving || uploads > 0 || (!draft.trim() && !retained.length && !images.length)} title="Continue in a new tab and keep the original chat (Ctrl+Enter or ⌘Enter)" onClick={() => void save()} className="flex items-center gap-1.5 rounded-md bg-accent px-2.5 py-1.5 text-xs font-medium text-surface enabled:hover:brightness-110 enabled:active:brightness-90"><Icon name="newTab" size={14} />{saving ? 'Opening chat…' : 'Edit and resend'}</button>
    </div>
  </div>;
}

const Tool = memo(function Tool({ item, contained = false, detailsOnly = false }: { item: Item; contained?: boolean; detailsOnly?: boolean }) {
  const [open, setOpen] = useState(detailsOnly);
  const label = toolLabel(item);
  const failed = toolFailed(item);
  const reasoning = item.type === 'reasoning';
  const content = reasoning ? itemText(item) : item.aggregatedOutput || itemText(item) || JSON.stringify(item.result || item.arguments || item, null, 2);
  return <div className="min-w-0 text-xs">
    {!detailsOnly && <button onClick={() => setOpen(!open)} aria-expanded={open} title={label} className={`flex w-full items-center gap-2 rounded-md px-1.5 py-1.5 text-left hover:bg-raised active:bg-line ${failed ? 'text-red-400' : 'text-muted hover:text-ink active:text-accent'}`}>
      <Icon name="chevron" size={12} style={{ transform: open ? 'rotate(90deg)' : undefined }} />
      <Icon name={item.type === 'fileChange' ? 'file' : item.type === 'webSearch' ? 'search' : 'code'} size={13} />
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {item.status === 'completed' && !failed && <Icon name="check" size={12} />}
      {failed && <span>Failed</span>}
    </button>}
    {item.type === 'fileChange' && item.changes?.map((change, index) => <button key={index} className="ml-6 flex max-w-[calc(100%-1.5rem)] items-center gap-2 py-1 text-accent hover:underline active:text-ink" onClick={() => void request('openLink', { path: String(change.path || '') })}><Icon name="file" size={12} /><span className="truncate">{String(change.path || '')}</span></button>)}
    {toolImagePaths(item).map((path, index) => <ImagePreview key={index} path={path} alt="Generated image" />)}
    {(open || detailsOnly) && (reasoning ? <div className={`mt-1 overflow-auto px-3 py-1 text-xs text-ink ${contained ? '' : 'max-h-60'}`}>
      {content.trim() ? <Markdown text={content} /> : <p className="py-2 text-muted">No thinking text was provided.</p>}
    </div> : <pre className={`mt-1 overflow-auto rounded-lg bg-input p-3 text-[11px] leading-relaxed whitespace-pre-wrap break-words ${contained ? '' : 'max-h-60'}`}>{content}</pre>)}
  </div>;
});

function activityLabel(items: Item[]): string {
  const counts = new Map<string, number>();
  const files = new Set<string>();
  for (const item of items) {
    counts.set(item.type, (counts.get(item.type) || 0) + 1);
    if (item.type === 'fileChange') {
      if (item.changes?.length) { item.changes.forEach((change) => files.add(String(change.path || item.id))); }
      else { files.add(item.id); }
    }
  }
  const labels: string[] = [];
  const add = (count: number, singular: string, plural: string) => { if (count) { labels.push(`${count} ${count === 1 ? singular : plural}`); } };
  add(counts.get('commandExecution') || 0, 'command', 'commands');
  add(files.size, 'file changed', 'files changed');
  add(counts.get('webSearch') || 0, 'web search', 'web searches');
  add(counts.get('mcpToolCall') || 0, 'tool call', 'tool calls');
  const known = new Set(['commandExecution', 'fileChange', 'webSearch', 'mcpToolCall', 'reasoning']);
  add(items.filter((item) => !known.has(item.type)).length, 'other action', 'other actions');
  return labels.join(' · ') || 'Thinking';
}

const ActivityGroup = memo(function ActivityGroup({ items }: { items: Item[] }) {
  const [open, setOpen] = useState(false);
  const detailsId = useId();
  const label = items.length === 1 ? toolLabel(items[0]) : activityLabel(items);
  const running = items.filter((item) => item.status === 'inProgress').length;
  const failed = items.filter(toolFailed).length;
  return <section data-activity-group={items[0].id} className="min-w-0">
    <button onClick={() => setOpen(!open)} aria-expanded={open} aria-controls={detailsId} title={label} className="flex w-full items-center gap-1.5 rounded-lg px-1.5 py-1.5 text-left text-xs text-muted hover:bg-raised hover:text-ink active:bg-line active:text-ink">
      <Icon name="chevron" size={12} style={{ transform: open ? 'rotate(90deg)' : undefined }} />
      <Icon name={items.some((item) => item.type === 'fileChange') ? 'edit' : 'code'} size={13} />
      <span className="min-w-0 flex-1 truncate">{label}</span>
      {running > 0 && <span className="flex shrink-0 items-center gap-1.5 text-accent"><span aria-hidden className="size-1.5 rounded-full bg-current" />{running} running</span>}
      {failed > 0 && <span className="shrink-0 text-red-400">{failed} failed</span>}
    </button>
    {open && <div id={detailsId} role="region" aria-label="Activity details" tabIndex={0} className="mt-1 max-h-[min(16rem,40vh)] space-y-0.5 overflow-y-auto overscroll-contain rounded-lg bg-raised/40 p-1">
      {items.map((item) => <Tool key={item.id} item={item} contained detailsOnly={items.length === 1} />)}
    </div>}
  </section>;
});

/** Bound the rendered transcript by groups so large command batches stay intact and collapsed. */
export function Transcript({ items, editingItemId, onEdit, onEditing }: { items: Item[]; editingItemId?: string; onEdit: EditMessage; onEditing: (id?: string) => void }) {
  const [limit, setLimit] = useState(80);
  const groups = useMemo(() => groupTranscript(items), [items]);
  const visible = groups.slice(-limit);
  return <div className="space-y-3">
    {groups.length > limit && <button className="mb-3 w-full rounded-lg py-2 text-xs text-muted hover:bg-raised active:bg-line" onClick={() => setLimit(limit + 80)}>Show earlier activity ({groups.length - limit})</button>}
    {visible.map((group) => {
      if (group.kind === 'activity') { return <ActivityGroup key={group.items[0].id} items={group.items} />; }
      const item = group.item;
      return item.type === 'userMessage' || item.type === 'agentMessage' ? <Message key={item.id} item={item} editing={editingItemId === item.id} onEdit={onEdit} onEditing={onEditing} /> : <Tool key={item.id} item={item} />;
    })}
  </div>;
}
