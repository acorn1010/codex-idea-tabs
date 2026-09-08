import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { request } from './bridge';
import { Icon } from './icons';

/** Image menus copy the full decoded image, even when invoked on a small thumbnail. */
export function ImageMenu({ image, x, y, close }: { image: HTMLImageElement; x: number; y: number; close: () => void }) {
  const menu = useRef<HTMLDivElement>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState('');
  const [position, setPosition] = useState({ left: x, top: y });
  useEffect(() => {
    const previous = document.activeElement;
    const rect = menu.current!.getBoundingClientRect();
    setPosition({ left: Math.max(8, Math.min(x, innerWidth - rect.width - 8)), top: Math.max(8, Math.min(y, innerHeight - rect.height - 8)) });
    menu.current?.querySelector('button')?.focus({ preventScroll: true });
    const outside = (event: PointerEvent) => { if (!menu.current?.contains(event.target as Node)) { close(); } };
    document.addEventListener('pointerdown', outside);
    window.addEventListener('resize', close);
    return () => {
      document.removeEventListener('pointerdown', outside); window.removeEventListener('resize', close);
      if (previous instanceof HTMLElement && previous.isConnected) { previous.focus({ preventScroll: true }); }
    };
  }, []);
  useEffect(() => { if (copied) { const timer = window.setTimeout(close, 900); return () => window.clearTimeout(timer); } }, [copied]);
  const copy = async () => {
    if (busy) { return; }
    setBusy(true); setError('');
    try {
      await image.decode();
      const canvas = document.createElement('canvas');
      canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
      const context = canvas.getContext('2d');
      if (!context) { throw new Error('The image could not be copied.'); }
      context.drawImage(image, 0, 0);
      await request('copyImage', { data: canvas.toDataURL('image/png').split(',')[1] });
      setCopied(true);
    } catch (error) { setError((error as Error).message); }
    finally { setBusy(false); }
  };
  return createPortal(<div ref={menu} role="menu" aria-label="Image actions" style={position} className="fixed z-70 w-48 max-w-[calc(100vw-16px)] rounded-lg bg-raised p-1 text-ink shadow-xl" onContextMenu={(event) => { event.preventDefault(); event.stopPropagation(); }} onKeyDown={(event) => {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(); }
    if (event.key === 'Tab') { close(); }
  }}>
    <button type="button" role="menuitem" disabled={busy || copied} onClick={() => void copy()} className="flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-xs enabled:hover:bg-surface enabled:active:bg-line focus-visible:bg-surface focus-visible:outline-none"><Icon name={copied ? 'check' : 'copy'} size={14} />{copied ? 'Image copied' : busy ? 'Copying…' : 'Copy image'}</button>
    {error && <p role="alert" className="max-h-28 overflow-auto px-2.5 py-1.5 text-xs break-words text-red-400">{error}</p>}
  </div>, image.closest('dialog') || document.body);
}
