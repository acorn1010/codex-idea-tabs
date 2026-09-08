/** A context block carries its evidence source so recorded text is never presented as a request capture. */
export type ContextBlock = { id: string; kind: 'instructions' | 'skills' | 'messages' | 'tools' | 'files' | 'summary'; label: string; role: string; text: string; origin: string; turnId?: string; call?: string; truncated?: boolean };
/** Reports are fetched only when the inspector is opened or refreshed. */
export type ContextReport = { kind: 'recorded' | 'startup' | 'transcript'; source: string; capturedAt: number; blocks: ContextBlock[]; notices: string[]; compactions: number; model?: string; effort?: string };
/** Duplicate passages are review candidates, not instructions to delete repeated content. */
export type ContextDuplicate = { text: string; count: number; blockIds: string[]; repeatedCharacters: number };

/** Compare complete paragraphs while preserving code indentation and internal whitespace. */
export function analyzeContext(blocks: readonly ContextBlock[]) {
  const paragraphs = new Map<string, { count: number; blockIds: Set<string> }>();
  let characters = 0;
  for (const block of blocks) {
    characters += block.text.length;
    for (const paragraph of block.text.replace(/\r\n/g, '\n').split(/\n[\t ]*\n/)) {
      const text = paragraph.trim();
      if (text.length < 120) { continue; }
      const found = paragraphs.get(text) || { count: 0, blockIds: new Set<string>() };
      found.count++;
      found.blockIds.add(block.id);
      paragraphs.set(text, found);
    }
  }
  const duplicates: ContextDuplicate[] = [...paragraphs].filter(([, value]) => value.count > 1).map(([text, value]) => ({ text, count: value.count, blockIds: [...value.blockIds], repeatedCharacters: text.length * (value.count - 1) })).sort((a, b) => b.repeatedCharacters - a.repeatedCharacters);
  return { characters, estimatedTokens: Math.ceil(characters / 4), duplicates, repeatedCharacters: duplicates.reduce((total, value) => total + value.repeatedCharacters, 0), duplicateBlocks: new Set(duplicates.flatMap((value) => value.blockIds)) };
}

/** Export readable text with scope and source labels intact. */
export function contextText(report: ContextReport) {
  return ['Codex context inspection', `View: ${report.kind}`, `Source: ${report.source}`, `Captured: ${new Date(report.capturedAt).toISOString()}`, ...report.notices, '', ...report.blocks.map((block) => `=== ${block.label} | ${block.role} | ${block.origin}${block.truncated ? ' | shortened' : ''} ===\n${block.call ? `Related call:\n${block.call}\n\n` : ''}${block.text}`)].join('\n\n');
}

/** Find paths mentioned in a block. A mention alone does not prove that a file was read. */
export function contextPaths(block: ContextBlock) {
  const text = `${block.call || ''}\n${block.text}`;
  const localText = text.replace(/\bhttps?:\/\/[^\s<>"'`]+/g, '');
  const matches = localText.match(/(?:[A-Za-z]:[\\/]|\/{1,2}|\.{1,2}\/|\.agents\/)[^\s<>"'`()[\]{},;]+\.(?:md|mdx|tsx?|jsx?|css|json|toml|yaml|yml|java|kt|py|go|rs|txt|png|jpe?g|svg|webp)(?=$|[\s<>"'`()[\]{},;:]|\\n)/g) || [];
  return [...new Set(matches)].slice(0, 24);
}
