import { describe, expect, it } from 'vitest';
import { analyzeContext, contextPaths, contextText } from './contextAnalysis';
import type { ContextBlock, ContextReport } from './contextAnalysis';

function block(id: string, text: string): ContextBlock { return { id, text, role: 'developer', label: 'Rules', kind: 'instructions', origin: 'Recorded item' }; }
const paragraph = 'Preserve the existing public behavior while making the requested change. Verify that the full output remains the same before accepting an optimization.';

describe('context analysis', () => {
  it('finds repeated paragraphs across and within blocks without dropping occurrences', () => {
    const result = analyzeContext([block('one', `${paragraph}\n\n${paragraph}`), block('two', `Different text\n\n${paragraph}`)]);
    expect(result.duplicates).toHaveLength(1);
    expect(result.duplicates[0].count).toBe(3);
    expect(result.duplicates[0].blockIds).toEqual(['one', 'two']);
    expect(result.repeatedCharacters).toBe(paragraph.length * 2);
  });
  it('ignores short common phrases and preserves meaningful code spacing', () => {
    expect(analyzeContext([block('one', 'All tests passed\n\n' + paragraph), block('two', 'All tests passed\n\n' + paragraph.replace('public behavior', 'public  behavior'))]).duplicates).toEqual([]);
  });
  it('uses text estimates and preserves evidence labels in export', () => {
    const report: ContextReport = { kind: 'startup', source: '/project', capturedAt: 0, blocks: [block('one', paragraph)], notices: ['Fresh snapshot, not the current chat'], compactions: 0 };
    expect(analyzeContext(report.blocks).estimatedTokens).toBe(Math.ceil(paragraph.length / 4));
    expect(contextText(report)).toContain('Fresh snapshot, not the current chat');
    expect(contextText(report)).toContain('Recorded item');
  });
  it('extracts mentioned paths from text and calls without treating URLs as local files', () => {
    expect(contextPaths({ ...block('one', 'Read /project/AGENTS.md and https://example.com/rules.md'), call: 'cat /project/.agents/skills/test/SKILL.md' })).toEqual(['/project/.agents/skills/test/SKILL.md', '/project/AGENTS.md']);
  });
});
