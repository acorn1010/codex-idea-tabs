import type { Root } from 'mdast';
import type { VFile } from 'vfile';

const flowchartHeader = /^(?:flowchart|graph)\s+(?:LR|RL|TB|TD|BT)\s*;?\s*$/;
const diagramLine = /^(?:\s+\S|\s*(?:%%|subgraph\b|end\b|direction\b|classDef\b|class\b|style\b|linkStyle\b|click\b|[^\s]+\s*(?:--|==|-\.|~~~|\[|\(|\{)))/;

/** Recognize pasted flowchart paragraphs without changing stored messages or existing code fences. */
export function remarkMermaid() {
  return (tree: Root, file: VFile) => {
    const source = String(file);
    tree.children = tree.children.map((node) => {
      if (node.type !== 'paragraph' || node.position?.start.offset === undefined || node.position.end.offset === undefined) { return node; }
      const text = source.slice(node.position.start.offset, node.position.end.offset);
      const [header, ...lines] = text.split(/\r?\n/);
      if (!flowchartHeader.test(header) || !lines.length || !lines.every((line) => diagramLine.test(line))) { return node; }
      return { type: 'code', lang: 'mermaid', value: text, position: node.position };
    });
    return tree;
  };
}
