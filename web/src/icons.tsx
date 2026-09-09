import type { CSSProperties } from 'react';

const paths = {
  review: 'M9 3h6l1 3h3v15H5V6h3zM9 11h6M9 15h4',
  bolt: 'm13 2-9 12h7l-1 8 10-13h-7z',
  feedback: 'M4 3h16v14H9l-5 4zM8 7h8M8 11h5',
  target: 'M21 12a9 9 0 1 1-9-9M17 12a5 5 0 1 1-5-5M12 12l9-9M17 3h4v4',
  plan: 'M9 6h12M9 12h12M9 18h12M3 5l1 1 2-2M3 11l1 1 2-2M3 17l1 1 2-2',
  reasoning: 'M12 4C8 0 3 4 5 8c-4 2-3 7 0 8-1 5 5 7 7 3M12 4c4-4 9 0 7 4 4 2 3 7 0 8 1 5-5 7-7 3zM7 8l3 2M17 8l-3 2M7 15l3-1M17 15l-3-1',
  status: 'M5 19a9 9 0 1 1 14 0M12 12l5-5M10 12a2 2 0 1 0 4 0 2 2 0 0 0-4 0',
  skill: 'm12 2 8 5v10l-8 5-8-5V7zM4 7l8 5 8-5M12 12v10M8 10l8-5',
  layers: 'm12 3 10 5-10 5L2 8zM2 12l10 5 10-5M2 16l10 5 10-5',
  branch: 'M6 3v12M9 18a3 3 0 1 1-6 0a3 3 0 0 1 6 0M21 6a3 3 0 1 1-6 0a3 3 0 0 1 6 0M18 9a9 9 0 0 1-9 9',
  terminal: 'M3 4h18v16H3zM6 8l4 4-4 4M13 16h5',
  trash: 'M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7',
  newTab: 'M14 3h7v7M21 3l-9 9M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-5',
  edit: 'm16 3 5 5M4 15 15.5 3.5a2.8 2.8 0 0 1 4 4L8 19l-5 2z',
  plus: 'M12 5v14M5 12h14', close: 'm6 6 12 12M6 18 18 6', send: 'M12 19V5m-6 6 6-6 6 6', stop: 'M7 7h10v10H7z',
  search: 'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0', settings: 'M10 2h4l.6 3.2L16 6l3-1 2 3.5-2.4 2.1v1.6l2.4 2.1-2 3.5-3-1-1.4.8L14 21h-4l-.6-3.4-1.4-.8-3 1-2-3.5 2.4-2.1v-1.6L3 8.5 5 5l3 1 1.4-.8zM15 11.5a3 3 0 1 0-6 0 3 3 0 1 0 6 0',
  split: 'M3 4h18v16H3zM12 4v16', check: 'm5 12 4 4L19 6', chevron: 'm9 5 7 7-7 7', down: 'm6 9 6 6 6-6',
  copy: 'M9 9h12v12H9zM15 9V3H3v12h6', file: 'M14 2H4v20h16V8zM14 2v6h6', image: 'M3 3h18v18H3zM3 17l6-6 4 4 3-3 5 5M16 7h.01',
  pin: 'm9 3 6 0 0 6 3 3v2H6v-2l3-3zM12 14v8', code: 'm8 5-6 7 6 7m8-14 6 7-6 7', shield: 'M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6z',
  refresh: 'M20 11a8 8 0 1 0-2 7M20 3v8h-8', arrow: 'M5 12h14m-6-6 6 6-6 6', attach: 'm8 12 6-6a3 3 0 0 1 4 4L9 19a5 5 0 0 1-7-7l10-10',
} as const;

/** Small, consistent icons keep narrow chat panes readable. */
export function Icon({ name, size = 15, style }: { name: keyof typeof paths; size?: number; style?: CSSProperties }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden style={style}><path d={paths[name]} /></svg>;
}
