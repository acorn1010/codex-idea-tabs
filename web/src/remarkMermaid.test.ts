import { describe, expect, it } from 'vitest';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import { remarkMermaid } from './remarkMermaid';

const example = `flowchart LR
    P[Postgres logical CDC] --> K[Kafka]
    K --> B[Append-only Delta change log]
    B --> A[Databricks AUTO CDC]
    A --> T[Current-state tables]`;
function parse(value: string) {
  const parser = unified().use(remarkParse).use(remarkMermaid);
  return parser.runSync(parser.parse(value), { value });
}

describe('pasted Mermaid flowcharts', () => {
  it('recognizes the complete unfenced example and preserves its exact source', () => {
    expect(parse(example).children).toMatchObject([{ type: 'code', lang: 'mermaid', value: example }]);
  });
  it('keeps prose and multiple diagrams separate', () => {
    const children = parse(`Before.\n\n${example}\n\n**After.**\n\ngraph TD;\nA --> B`).children;
    expect(children.map((node) => node.type)).toEqual(['paragraph', 'code', 'paragraph', 'code']);
    expect(children[2]).toMatchObject({ children: [{ type: 'strong' }] });
  });
  it('leaves existing fences, indented code, and ordinary prose alone', () => {
    const fenced = parse(`\`\`\`mermaid\n${example}\n\`\`\``).children[0];
    expect(fenced).toMatchObject({ type: 'code', lang: 'mermaid', value: example });
    expect(parse(`\`\`\`text\n${example}\n\`\`\``).children[0]).toMatchObject({ type: 'code', lang: 'text' });
    expect(parse('    flowchart LR\n    A --> B').children[0]).toMatchObject({ type: 'code', lang: null });
    expect(parse('Use flowchart LR to show the direction.').children[0].type).toBe('paragraph');
    expect(parse('flowchart LR\nmeans left to right.').children[0].type).toBe('paragraph');
    expect(parse('`flowchart LR`\nA --> B').children[0].type).toBe('paragraph');
  });
  it('can switch to a diagram when streamed lines arrive, without losing incomplete source', () => {
    expect(parse('flowchart LR').children[0].type).toBe('paragraph');
    expect(parse('flowchart LR\n    A[').children[0]).toMatchObject({ type: 'code', value: 'flowchart LR\n    A[' });
    expect(parse('flowchart LR\nA[Start] --> B[Done]').children[0].type).toBe('code');
  });
  it('handles Windows line endings and all standard flowchart directions', () => {
    for (const direction of ['LR', 'RL', 'TB', 'TD', 'BT']) {
      const source = `flowchart ${direction}\r\n    A --> B`;
      expect(parse(source).children[0]).toMatchObject({ type: 'code', value: source });
    }
  });
});
