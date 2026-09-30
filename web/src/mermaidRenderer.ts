import mermaid, { type MermaidConfig } from 'mermaid';

const maxTextSize = 20_000;
const configuration: MermaidConfig = {
  startOnLoad: false,
  securityLevel: 'strict',
  suppressErrorRendering: true,
  maxTextSize,
  maxEdges: 200,
  htmlLabels: false,
  fontFamily: 'Arial, sans-serif',
  altFontFamily: 'Arial, sans-serif',
  theme: 'base',
  themeCSS: '',
  dompurifyConfig: {},
  flowchart: { htmlLabels: false },
};
// Message directives must not change security, limits, HTML labels, or the chat's theme.
configuration.secure = ['secure', 'themeVariables', ...Object.keys(configuration)];
mermaid.initialize(configuration);
let sequence = 0;
let renderQueue = Promise.resolve();

type Diagram = { url: string; width: number; height: number; label: string };

/** Serialize theme configuration and rendering, then isolate the SVG in an inert image. */
export function renderMermaid(source: string, light: boolean, signal: AbortSignal): Promise<Diagram> {
  const operation = renderQueue.then(async () => {
    signal.throwIfAborted();
    if (source.length > maxTextSize) { throw new Error('This diagram is too large to preview.'); }
    mermaid.initialize({ ...configuration, themeVariables: {
      darkMode: !light,
      background: light ? '#ffffff' : '#2c2f34',
      primaryColor: light ? '#e7effa' : '#303f53',
      primaryTextColor: light ? '#292e38' : '#d9dce3',
      primaryBorderColor: light ? '#245eae' : '#8fbffa',
      lineColor: light ? '#636b78' : '#959ba8',
      secondaryColor: light ? '#eceef2' : '#292c31',
      tertiaryColor: light ? '#f7f8fa' : '#202226',
      fontSize: '14px',
    } });
    const container = document.createElement('div');
    container.setAttribute('aria-hidden', 'true');
    container.style.cssText = 'position:fixed;left:-100000px;top:0;width:1200px;visibility:hidden;pointer-events:none';
    document.body.append(container);
    try {
      const { svg } = await mermaid.render(`chat-diagram-${++sequence}`, source, container);
      signal.throwIfAborted();
      const document = new DOMParser().parseFromString(svg, 'image/svg+xml');
      const element = document.documentElement;
      const viewBox = element.getAttribute('viewBox')?.split(/[\s,]+/).map(Number);
      if (element.localName !== 'svg' || !viewBox || viewBox.length !== 4 || !viewBox.every(Number.isFinite) || viewBox[2] <= 0 || viewBox[3] <= 0) {
        throw new Error('This diagram could not be displayed.');
      }
      // Explicit dimensions keep SVG text readable when the expanded view scrolls.
      const [, , width, height] = viewBox;
      element.setAttribute('width', String(width));
      element.setAttribute('height', String(height));
      const title = element.querySelector('title')?.textContent;
      const description = element.querySelector('desc')?.textContent;
      const label = [title, description].filter(Boolean).join('. ') || 'Mermaid diagram';
      const url = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(new XMLSerializer().serializeToString(element))}`;
      return { url, width, height, label };
    } finally { container.remove(); }
  });
  renderQueue = operation.then(() => {}, () => {});
  return operation;
}
