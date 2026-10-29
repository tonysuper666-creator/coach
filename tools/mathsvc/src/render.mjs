// Coach mathsvc 渲染核心：TeX → SVG（MathJax 3.2.2，fontCache:'none'）
// 只依赖 mathjax-full，不读网络、不写文件。
import { mathjax } from 'mathjax-full/js/mathjax.js';
import { TeX } from 'mathjax-full/js/input/tex.js';
import { SVG } from 'mathjax-full/js/output/svg.js';
import { liteAdaptor } from 'mathjax-full/js/adaptors/liteAdaptor.js';
import { RegisterHTMLHandler } from 'mathjax-full/js/handlers/html.js';
import { AllPackages } from 'mathjax-full/js/input/tex/AllPackages.js';
import { createHash } from 'node:crypto';

export const MATHJAX_VERSION = '3.2.2';
export const FONT_CACHE = 'none';

// MathJax v3 的 SVG 输出以 1000 单位 = 1em 绘字；viewBox 即精确尺寸来源。
const UNITS_PER_EM = 1000;

// 客户端 MathSvgPainter 只画这几种子集（DESIGN §7.4）
export const ELEMENT_ALLOW = ['svg', 'g', 'path', 'rect', 'text'];

const adaptor = liteAdaptor();
RegisterHTMLHandler(adaptor);

let lastParseErrors = [];

const texInput = new TeX({
  packages: AllPackages,
  inlineMath: [['$', '$']],
  displayMath: [['$$', '$$']],
  processEscapes: false,
  formatError: (jax, err) => {
    lastParseErrors.push({ id: err.id ?? 'TeX', message: String(err.message ?? err) });
    return jax.formatError(err);
  },
});
const svgOutput = new SVG({ fontCache: FONT_CACHE });

// Undefined control sequences are NOT errors in MathJax: noundefined 把它们画成红字，
// 静默降级成看似正常的公式。这里自己查宏表，把「不支持的宏」显式报出来。
const macroHandler = texInput.parseOptions.handlers.get('macro');
const envHandler = texInput.parseOptions.handlers.get('environment');
const BEGIN_ENV = /\\begin\s*\{([^{}]+)\}/g;

/** 逐个读出 TeX 控制序列：`\\` 是换行不是命令，`\,` 这类控制符号不查宏表。 */
function controlSequenceNames(tex) {
  const names = [];
  for (let index = 0; index < tex.length; index += 1) {
    if (tex[index] !== '\\') continue;
    const next = tex[index + 1] ?? '';
    if (!/[A-Za-z]/u.test(next)) {
      index += 1; // \ 或控制符号，整体跳过
      continue;
    }
    let end = index + 1;
    while (end < tex.length && /[A-Za-z]/u.test(tex[end])) end += 1;
    names.push(tex.slice(index + 1, end));
    index = end - 1;
  }
  return names;
}

function unsupportedSymbols(tex) {
  const unknown = [];
  for (const name of controlSequenceNames(tex)) {
    if (macroHandler?.lookup(name) === null && !unknown.includes(name)) unknown.push(name);
  }
  for (const match of tex.matchAll(BEGIN_ENV)) {
    const name = match[1].trim();
    if (envHandler?.lookup(name) === null && !unknown.includes(`begin{${name}}`)) unknown.push(`begin{${name}}`);
  }
  return unknown;
}

const htmlDocument = mathjax.document('', {
  InputJax: texInput,
  OutputJax: svgOutput,
});

const DROP_ATTRIBUTE = /\s(?:data-[\w:.-]+|aria-[\w:.-]+|role|focusable)(?:="[^"]*")?/g;
const elementCensus = new Map();

export function resetElementCensus() {
  elementCensus.clear();
}

export function elementCounts() {
  return Object.fromEntries([...elementCensus.entries()].sort((a, b) => b[1] - a[1]));
}

export function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

function round(value) {
  return Math.round(value * 1000) / 1000;
}

function countElements(svg) {
  const tags = new Set();
  for (const match of svg.matchAll(/<([a-zA-Z][\w:-]*)/g)) tags.add(match[1].toLowerCase());
  for (const tag of tags) elementCensus.set(tag, (elementCensus.get(tag) ?? 0) + 1);
  return [...tags].filter((tag) => !ELEMENT_ALLOW.includes(tag));
}

function parseViewBox(svg) {
  const match = /viewBox="([^"]+)"/.exec(svg);
  if (!match) return null;
  const [x, y, width, height] = match[1].trim().split(/\s+/).map(Number);
  if (![x, y, width, height].every(Number.isFinite)) return null;
  return { x, y, width, height };
}

/** 尺寸改写为 em：* width/height 换成 em，style 里的 vertical-align 换成 em。 */
function applyEmBox(svg, box) {
  return svg
    .replace(/\swidth="[^"]*"/, ` width="${round(box.w)}em"`)
    .replace(/\sheight="[^"]*"/, ` height="${round(box.h + box.d)}em"`)
    .replace(/\sstyle="[^"]*"/, ` style="vertical-align: -${round(box.d)}em"`);
}

const ANSI = /\u001b\[[0-9;]*m/g;

function merrorText(svg) {
  const match = /<merror[\s\S]*?<text[^>]*>([\s\S]*?)<\/text>/.exec(svg);
  if (!match) return null;
  return match[1].replace(/<[^>]+>/g, '').replace(ANSI, '').replace(/\s+/g, ' ').trim();
}

/**
 * 渲染一条公式。
 * @param {{tex: string, display?: boolean}} request
 * @returns {{ok: true, sha: string, svg: string, w: number, h: number, d: number}
 *          | {ok: false, sha: string, error: {code: string, message: string, macro?: string}}}
 */
export function renderTeX(request) {
  const tex = String(request.tex ?? '');
  const display = Boolean(request.display);
  const sha = sha256(tex);
  if (!tex.trim()) {
    return { ok: false, sha, error: { code: 'tex', message: 'empty tex' } };
  }

  const unsupported = unsupportedSymbols(tex);
  if (unsupported.length > 0) {
    return {
      ok: false,
      sha,
      error: {
        code: 'unsupported-macro',
        message: `MathJax ${MATHJAX_VERSION} 不认识：${unsupported.map((name) => `\\${name}`).join(', ')}`,
        macros: unsupported,
      },
    };
  }

  lastParseErrors = [];
  let container;
  try {
    container = htmlDocument.convert(tex, { display, em: 16, ex: 8, containerWidth: 80 * 16 });
  } catch (error) {
    return { ok: false, sha, error: { code: 'tex', message: String(error?.message ?? error).slice(0, 400) } };
  }

  const raw = adaptor.outerHTML(container);
  const errorText = merrorText(raw);
  if (errorText || lastParseErrors.length > 0) {
    const message = errorText ?? lastParseErrors[0].message;
    return { ok: false, sha, error: { code: 'tex', message: message.slice(0, 400), id: lastParseErrors[0]?.id } };
  }

  const svgMatch = /<svg[\s\S]*<\/svg>/.exec(raw);
  if (!svgMatch) {
    return { ok: false, sha, error: { code: 'tex', message: 'svg output missing' } };
  }
  const svg = svgMatch[0].replace(DROP_ATTRIBUTE, '');
  const outside = countElements(svg);
  const viewBox = parseViewBox(svg);
  if (!viewBox) {
    return { ok: false, sha, error: { code: 'tex', message: 'no viewBox in svg output' } };
  }
  // 客户端 Painter 只画 svg/g/path/rect/text。出现别的元素（如 \cancel 的 <line>）
  // 就整条退成源码 chip，不让 Painter 收到画不出来的 SVG（ADR-0005 退路）。
  if (outside.length > 0) {
    return {
      ok: false,
      sha,
      error: {
        code: 'unsupported-output',
        message: `SVG 含客户端子集外元素：${outside.join(', ')}`,
        elements: outside,
      },
    };
  }

  const w = viewBox.width / UNITS_PER_EM;
  const totalHeight = viewBox.height / UNITS_PER_EM;
  const d = -viewBox.y / UNITS_PER_EM;
  const box = { w, h: Math.max(0, totalHeight - d), d };
  return { ok: true, sha, svg: applyEmBox(svg, box), w: round(box.w), h: round(box.h), d: round(box.d) };
}

/** 只做元素种类审计，不返回 SVG（给统计脚本用）。 */
export function auditTeX(tex, display) {
  const result = renderTeX({ tex, display });
  if (!result.ok) return { ok: false, error: result.error };
  const tags = new Set();
  for (const match of result.svg.matchAll(/<([a-zA-Z][\w:-]*)/g)) tags.add(match[1].toLowerCase());
  return { ok: true, tags: [...tags].sort(), w: result.w, h: result.h, d: result.d, svg: result.svg };
}
