import { useEffect } from 'react';
import type { RefObject } from 'react';
import { request } from './bridge';
import type { Attachment } from './types';

type NativeDrop = { phase: 'over' | 'leave' | 'drop'; token: string; x: number; y: number; width: number; height: number };

/** Route native file drops to the composer or the inline editor under the pointer, including scaled displays. */
export function useNativeFileDrop(onDrop: (token: string) => void, onDrag: (over: boolean) => void, editor?: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const receive = (event: Event) => {
      const drop = (event as CustomEvent<NativeDrop>).detail;
      if (drop.phase === 'leave') { onDrag(false); return; }
      const x = drop.x * window.innerWidth / Math.max(1, drop.width);
      const y = drop.y * window.innerHeight / Math.max(1, drop.height);
      const target = document.elementFromPoint(x, y)?.closest('[data-message-editor]');
      const matches = editor ? target === editor.current : !target;
      onDrag(drop.phase === 'over' && matches);
      if (drop.phase === 'drop' && matches) { onDrop(drop.token); }
    };
    window.addEventListener('codex-file-drop', receive);
    return () => window.removeEventListener('codex-file-drop', receive);
  }, [onDrop, onDrag, editor]);
}

/** Consume a one-use native drop token. Only files from that user drop are readable by the host. */
export function uploadDroppedFiles(token: string, imagesOnly = false, discard = false) {
  return request<{ files: Attachment[]; errors: string[] }>('droppedFiles', { token, imagesOnly, discard });
}

/** Upload a browser file through Codex's file bridge so it is available in the backend filesystem. */
export async function uploadAttachment(file: File): Promise<Attachment> {
  if (file.size > 50 * 1024 * 1024) { throw new Error(`${file.name} exceeds 50 MB.`); }
  const data = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(',')[1]);
    reader.onerror = () => reject(new Error(`Could not read ${file.name}`));
    reader.readAsDataURL(file);
  });
  return request<Attachment>('attachment', { name: file.name, mime: file.type || 'application/octet-stream', data });
}
