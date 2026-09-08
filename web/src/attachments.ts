import { request } from './bridge';
import type { Attachment } from './types';

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
