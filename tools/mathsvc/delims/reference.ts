// Coach `$` 分词参考实现（DESIGN §7.4 / ADR-0005 决定 4）
//
// 职责：
//   1. 定界识别：Pandoc 的 `$` 规则 + `$$…$$` + `\(…\)` / `\[…\]`，代码块、行内代码、`\$` 原样保留。
//   2. bare-math 修复：只对 source:'model'（模型生成文本）跑；资料原文（source:'library'）不跑。
//   3. 修掉 SC bare-equation-policy 的已知缺陷：`<br>` 等行内 HTML 标签会被包进 `$…$`。
//
// 输出是语言无关的片段序列，Kotlin（MathSvgPainter 那座桥）按 delims/cases.json 移植后逐条对齐。
//
// 设计约束（与 Kotlin 版一致，逐条可复现）：
//   - 不猜：任何定界符找不到合法闭符，就保留原文，并记一条 unresolved。
//   - 行内 `$…$` 不跨空行（Pandoc 行为）；`\(…\)` 不跨换行。
//   - 代码块 / 行内代码 / `\$` 里的 `$` 永远不是定界符。

export type Segment =
  | { text: string; start?: number; end?: number }
  | { math: string; display: boolean; start?: number; end?: number };

export type MathSource = 'model' | 'library';

export interface TokenizeOptions {
  /** model = 模型生成（跑 bare-math 修复）；library = 资料原文（只做定界识别）。 */
  source?: MathSource;
  /** 覆盖默认：source==='model' 时为 true。 */
  bareMath?: boolean;
}

export interface UnresolvedDelimiter {
  delimiter: '$' | '$$' | '\\(' | '\\[';
  index: number;
  reason: string;
}

export interface TokenizeResult {
  segments: Segment[];
  unresolved: UnresolvedDelimiter[];
}

const isSpace = (ch: string | undefined): boolean => ch === undefined || /\s/u.test(ch);
const isDigit = (ch: string | undefined): boolean => ch !== undefined && /[0-9]/u.test(ch);

/** 行内 HTML 标签：裸文本里的 `<br>` / `<b>` 这类，必须当作硬边界。 */
const INLINE_HTML_TAG = /^<\/?[A-Za-z][^<>\n]*>/u;
const FENCE = /^(?: {0,3})(`{3,}|~{3,})/u;

function lineStartOf(text: string, index: number): number {
  const previousNewline = text.lastIndexOf('\n', index - 1);
  return previousNewline === -1 ? 0 : previousNewline + 1;
}

/**
 * Pandoc 的非贪婪闭符搜索：Pandoc 的正文是 `(\\.|[^$\n])*?\S` —— 里面不允许出现未转义的 `$`。
 * 所以「往后遇到的第一个未转义 `$`」就是唯一候选：它合法才是公式，不合法就整句不成公式
 * （这正是「$930 billion ... $9.3\times10^{11}$」里货币不会和后面的公式粘在一起的原因）。
 * 合法 = 前面不是空白、后面不是数字、且不跨行。
 */
function findClosingDollar(text: string, from: number): number {
  let cursor = from;
  while (cursor < text.length) {
    const character = text[cursor];
    if (character === '\n') return -1;
    if (character === '\\') {
      cursor += 2;
      continue;
    }
    if (character === '$') {
      return !isSpace(text[cursor - 1]) && !isDigit(text[cursor + 1]) ? cursor : -1;
    }
    cursor += 1;
  }
  return -1;
}

/** 行内公式正文的两条硬拒（比 Pandoc 更保守，用来挡住 OCR 行与里外不一的中文）：
 *  1. 正文里不该有裸 HTML 标签（`<br>` 那种），有就是两条货币被强行配对；
 *  2. 中文只能出现在 `\text{…}` 里，裸中文说明这不是公式。 */
function validInlineMathBody(body: string): boolean {
  if (/<\/?[A-Za-z][^<>]*>/u.test(body)) return false;
  const withoutText = body.replace(/\\(?:text|operatorname|mathrm|textbf|textit)\s*\{[^{}]*\}/gu, '');
  return !/\p{Script=Han}/u.test(withoutText);
}

function findLiteral(text: string, needle: string, from: number): number {
  let cursor = from;
  while (cursor <= text.length - needle.length) {
    if (text.startsWith(needle, cursor)) {
      const backslashes = countTrailingBackslashes(text, cursor);
      if (backslashes % 2 === 0) return cursor;
    }
    if (text.startsWith('\n\n', cursor)) return -1;
    cursor += 1;
  }
  return -1;
}

function countTrailingBackslashes(text: string, index: number): number {
  let count = 0;
  let cursor = index - 1;
  while (cursor >= 0 && text[cursor] === '\\') {
    count += 1;
    cursor -= 1;
  }
  return count;
}

interface RawScan {
  segments: Segment[];
  unresolved: UnresolvedDelimiter[];
}

function scan(text: string): RawScan {
  const segments: Segment[] = [];
  const unresolved: UnresolvedDelimiter[] = [];
  let buffer = '';
  let bufferStart = 0;
  let cursor = 0;

  const flush = (): void => {
    if (buffer.length > 0) {
      segments.push({ text: buffer, start: bufferStart, end: bufferStart + buffer.length });
      buffer = '';
    }
  };

  const append = (chunk: string): void => {
    if (buffer.length === 0) bufferStart = cursor;
    buffer += chunk;
  };

  while (cursor < text.length) {
    const character = text[cursor];

    // 围栏代码块：整块原样
    if (character === '`' || character === '~') {
      const atLineStart = cursor === lineStartOf(text, cursor);
      const fence = atLineStart ? FENCE.exec(text.slice(cursor)) : null;
      if (fence) {
        const marker = fence[1];
        const lineEnd = text.indexOf('\n', cursor);
        const afterInfo = lineEnd === -1 ? text.length : lineEnd + 1;
        let close = afterInfo;
        let blockEnd = text.length;
        while (close < text.length) {
          const closeLineEnd = text.indexOf('\n', close);
          const line = text.slice(close, closeLineEnd === -1 ? text.length : closeLineEnd).trimEnd();
          if (line.startsWith(marker[0].repeat(marker.length)) && /^(`{3,}|~{3,})$/u.test(line)) {
            blockEnd = closeLineEnd === -1 ? text.length : closeLineEnd;
            break;
          }
          close = closeLineEnd === -1 ? text.length : closeLineEnd + 1;
        }
        append(text.slice(cursor, blockEnd));
        cursor = blockEnd;
        continue;
      }
    }

    // 行内代码：同长度反引号闭合
    if (character === '`') {
      let run = 0;
      while (text[cursor + run] === '`') run += 1;
      const marker = '`'.repeat(run);
      const close = text.indexOf(marker, cursor + run);
      if (close !== -1 && !text.slice(cursor + run, close).includes('\n\n')) {
        append(text.slice(cursor, close + run));
        cursor = close + run;
        continue;
      }
      append(text.slice(cursor, cursor + run));
      cursor += run;
      continue;
    }

    if (character === '\\') {
      const next = text[cursor + 1];
      if (next === '(' || next === '[') {
        const closing = next === '(' ? '\\)' : '\\]';
        const newline = text.indexOf('\n', cursor + 2);
        const limit = newline === -1 ? text.length : newline;
        const close = findLiteral(text.slice(0, limit), closing, cursor + 2);
        const body = close === -1 ? '' : text.slice(cursor + 2, close);
        if (close !== -1 && body.trim().length > 0) {
          flush();
          segments.push({ math: body.trim(), display: next === '[', start: cursor, end: close + 2 });
          cursor = close + 2;
          continue;
        }
        unresolved.push({
          delimiter: next === '(' ? '\\(' : '\\[',
          index: cursor,
          reason: 'unclosed delimiter',
        });
        append(text.slice(cursor, cursor + 2));
        cursor += 2;
        continue;
      }
      // 转义的 $、\ 等：原样保留，不当定界符
      append(text.slice(cursor, cursor + 2));
      cursor += 2;
      continue;
    }

    if (character === '$') {
      if (text[cursor + 1] === '$') {
        const close = findLiteral(text, '$$', cursor + 2);
        const body = close === -1 ? '' : text.slice(cursor + 2, close);
        if (close !== -1 && body.trim().length > 0) {
          flush();
          segments.push({ math: body.trim(), display: true, start: cursor, end: close + 2 });
          cursor = close + 2;
          continue;
        }
        unresolved.push({ delimiter: '$$', index: cursor, reason: 'unclosed display math' });
        append('$$');
        cursor += 2;
        continue;
      }
      // Pandoc 规则：开 $ 后必须紧跟非空白
      if (isSpace(text[cursor + 1])) {
        append('$');
        cursor += 1;
        continue;
      }
      const close = findClosingDollar(text, cursor + 1);
      if (close === -1) {
        unresolved.push({ delimiter: '$', index: cursor, reason: 'no legal closing $ (currency or stray dollar)' });
        append('$');
        cursor += 1;
        continue;
      }
      const body = text.slice(cursor + 1, close);
      if (!validInlineMathBody(body)) {
        unresolved.push({ delimiter: '$', index: cursor, reason: 'body contains raw HTML tag or bare Chinese' });
        append('$');
        cursor += 1;
        continue;
      }
      flush();
      segments.push({ math: body, display: false, start: cursor, end: close + 1 });
      cursor = close + 1;
      continue;
    }

    append(character);
    cursor += 1;
  }

  flush();
  return { segments, unresolved };
}

// ---------------------------------------------------------------------------
// bare-math 修复（只对模型生成文本）
// ---------------------------------------------------------------------------

const BARE_MATH_WORDS = new Set([
  'ATE', 'ATT', 'Cov', 'E', 'FOC', 'GDP', 'IV', 'MRS', 'MRT', 'OLS', 'Pr', 'SE', 'Var',
]);

const GREEK = '\\p{Script=Greek}';

/** `- x_i:` 这种记号表头；只包「看起来就是数学」的表头。 */
function wrapNotationListHeads(part: string): string {
  const head = /^(\s*[-*+]\s+)([^:：\n]+?)(\s*)([:：])/gmu;
  return part.replace(head, (whole: string, bullet: string, value: string, spacing: string, colon: string) => {
    const trimmed = value.trim();
    if (!trimmed || trimmed.length > 220 || trimmed.includes('$') || trimmed.includes('`')) return whole;
    if (/\p{Script=Han}/u.test(trimmed)) return whole;
    const single = /^[A-Za-z]|^[\p{Script=Greek}]/u.test(trimmed) && trimmed.length === 1;
    const list = new RegExp(`^[A-Za-z${GREEK}](?:\\s*,\\s*[A-Za-z${GREEK}])+$`, 'u').test(trimmed);
    const scripted = /[_^=≤≥≈≠]/u.test(trimmed);
    return single || list || scripted ? `${bullet}$${trimmed}$${spacing}${colon}` : whole;
  });
}

/** `E[Y]=10`、`p_1≈0.3` 这类期望/比较式。 */
function wrapExpectationAndComparison(part: string): string {
  const withExpectation = part.replace(
    new RegExp(`(E\\s*\\[[^\\]\\n]+\\]\\s*(?:=|≈|≠|≤|≥|<|>)\\s*[-+]?(?:\\d+(?:\\.\\d+)?|[A-Za-z${GREEK}][A-Za-z0-9]*(?:_(?:\\{[^{}\\n]+\\}|[A-Za-z0-9${GREEK}]))?))`, 'gu'),
    (_whole: string, equation: string) => `$${equation.trim()}$`,
  );
  return withExpectation.replace(
    new RegExp(`([A-Za-z${GREEK}][A-Za-z0-9]*(?:_(?:\\{[^{}\\n]+\\}|[A-Za-z0-9${GREEK}]+))?\\s*(?:≈|≠|≤|≥|<|>)\\s*[-+−]?\\d+(?:\\.\\d+)?)`, 'gu'),
    (_whole: string, comparison: string) => `$${braceMultiLetterScripts(comparison.trim())}$`,
  );
}

function scriptSuffixEnd(text: string, start: number): number | null {
  const marker = text[start];
  if (marker !== '_' && marker !== '^') return null;
  const valueStart = start + 1;
  if (text[valueStart] === '{') {
    const close = text.indexOf('}', valueStart + 1);
    if (close < 0 || close - valueStart > 42) return null;
    const content = text.slice(valueStart + 1, close);
    return content && new RegExp(`^(?:[A-Za-z0-9,${GREEK}])+$`, 'u').test(content) ? close + 1 : null;
  }
  if (/^[A-Za-z0-9]/u.test(text[valueStart] ?? '') || new RegExp(`^[${GREEK}]`, 'u').test(text[valueStart] ?? '')) {
    return valueStart + 1;
  }
  return null;
}

function mathIdentifierEnd(text: string, start: number): number | null {
  const greek = new RegExp(`^[${GREEK}]`, 'u').exec(text.slice(start));
  let end: number;
  if (greek) {
    end = start + greek[0].length;
  } else {
    const latin = /^[A-Za-z]+/u.exec(text.slice(start));
    const word = latin?.[0] ?? '';
    if (!word || (word.length > 1 && !BARE_MATH_WORDS.has(word))) return null;
    end = start + word.length;
  }
  for (;;) {
    const suffixEnd = scriptSuffixEnd(text, end);
    if (suffixEnd === null) return end;
    end = suffixEnd;
  }
}

/**
 * 等号右边能吃到哪儿。与 SC 的 boundedEquationRightHandSideEnd 同一套规则，
 * 差别是：`<` `>` 只当比较运算符，遇到行内 HTML 标签直接停（SC 的 `<br>` 缺陷来源）。
 */
function equationRightHandSideEnd(text: string, start: number): number | null {
  const closingFor: Record<string, string> = { '(': ')', '[': ']', '{': '}' };
  const openings = new Set(Object.keys(closingFor));
  const closings = new Set(Object.values(closingFor));
  const stack: string[] = [];
  const maximumEnd = Math.min(text.length, start + 400);
  let cursor = start;
  let lastSafeEnd: number | null = null;
  let sawAtom = false;
  let lastToken: 'atom' | 'operator' | 'open' | 'comma' | 'close' | null = null;

  while (cursor < maximumEnd) {
    const character = text[cursor] ?? '';
    if (character === '\n' || character === '\r') break;
    if (/\s/u.test(character)) {
      cursor += 1;
      continue;
    }
    // 硬边界：HTML 标签、公式定界符、表格竖线、反引号
    if (character === '<' && INLINE_HTML_TAG.test(text.slice(cursor))) break;
    if (character === '$' || character === '`' || character === '|' || character === '\\') break;

    const number = /^(?:\d+(?:\.\d+)?|\.\d+)/u.exec(text.slice(cursor));
    if (number) {
      cursor += number[0].length;
      sawAtom = true;
      lastToken = 'atom';
      if (stack.length === 0) lastSafeEnd = cursor;
      continue;
    }
    const identifierEnd = mathIdentifierEnd(text, cursor);
    if (identifierEnd !== null) {
      cursor = identifierEnd;
      sawAtom = true;
      lastToken = 'atom';
      if (stack.length === 0) lastSafeEnd = cursor;
      continue;
    }
    if (openings.has(character)) {
      stack.push(closingFor[character]);
      cursor += 1;
      lastToken = 'open';
      continue;
    }
    if (closings.has(character)) {
      if (stack[stack.length - 1] !== character) break;
      stack.pop();
      cursor += 1;
      lastToken = 'close';
      if (stack.length === 0 && sawAtom) lastSafeEnd = cursor;
      continue;
    }
    if (character === ',') {
      if (stack.length === 0) break;
      cursor += 1;
      lastToken = 'comma';
      continue;
    }
    if (/[+\-−–*/×·⋅=≈≠≤≥%]/u.test(character)) {
      cursor += 1;
      lastToken = 'operator';
      continue;
    }
    break;
  }

  if (cursor === maximumEnd && maximumEnd < text.length) return null;
  if (stack.length > 0 || lastToken === 'operator' || lastToken === 'open' || lastToken === 'comma') return null;
  return sawAtom ? lastSafeEnd : null;
}

/** `x_1=1`、`P_1=2`、`E[Y]=3`、`ε_arc = -0.17`、`ΔQ/(Q1+Q2) = 0.42/4.22` 这种「左式 = 右式」。
 *  左式按小扫描器吃（标识符 + 括号组 + `/` `+` `-` `*`），只有真的走到 `=` 才算公式，
 *  所以 `snake_case`、`Q (see above)` 这类散文不会被误伤。
 *  多字母下标输出时补成 `_{arc}`：TeX 会把 `x_arc` 读成 x_a·r·c。 */
const LHS_ALLOWED = /[A-Za-z0-9\p{Script=Greek}_^{}()\[\]+*/×·⋅%\- ]/u;

interface EquationStart {
  lhsStart: number;
  eqIndex: number;
  rhsStart: number;
}

/** 左式里出现的英文单词只能是经济学缩写（E、GDP、ATE…），否则是散文（"and"、"rising"）。 */
function lhsLooksMathematical(lhs: string): boolean {
  if (/\p{Script=Han}/u.test(lhs)) return false;
  // 下标/上标里的字母不算散文词：ε_arc 的 "arc" 不是单词
  const withoutScripts = lhs.replace(/[_^](?:\{[^{}]*\}|[A-Za-z]+)/gu, '');
  for (const word of withoutScripts.match(/[A-Za-z]{2,}/gu) ?? []) {
    if (!BARE_MATH_WORDS.has(word)) return false;
  }
  return true;
}

function scanEquationStart(text: string, start: number): EquationStart | null {
  let cursor = start;
  let depth = 0;
  const limit = Math.min(text.length, start + 60);
  while (cursor < limit) {
    const character = text[cursor];
    if (character === '\n' || character === '\r') return null;
    if (character === '(' || character === '[') {
      depth += 1;
      cursor += 1;
      continue;
    }
    if (character === ')' || character === ']') {
      depth -= 1;
      if (depth < 0) return null;
      cursor += 1;
      continue;
    }
    if (character === '=' && depth === 0) {
      const lhs = text.slice(start, cursor).trimEnd();
      if (lhs.length === 0 || !/[A-Za-z\p{Script=Greek}0-9)\]]/u.test(lhs.slice(-1))) return null;
      if (!/[A-Za-z\p{Script=Greek}]/u.test(lhs)) return null;
      if (!lhsLooksMathematical(lhs)) return null;
      let rhsStart = cursor + 1;
      while (rhsStart < text.length && (text[rhsStart] === ' ' || text[rhsStart] === '\t')) rhsStart += 1;
      return { lhsStart: start, eqIndex: cursor, rhsStart };
    }
    if (!LHS_ALLOWED.test(character)) return null;
    cursor += 1;
  }
  return null;
}

function wrapSimpleBareEquations(part: string): string {
  const identifierStart = /[A-Za-z\p{Script=Greek}]/u;
  let output = '';
  let copiedUntil = 0;
  let cursor = 0;
  while (cursor < part.length) {
    if (!identifierStart.test(part[cursor])) {
      cursor += 1;
      continue;
    }
    const previous = part[cursor - 1];
    if (previous !== undefined && /[\p{L}0-9_$\\`]/u.test(previous)) {
      cursor += 1;
      continue;
    }
    const found = scanEquationStart(part, cursor);
    const equationEnd = found ? equationRightHandSideEnd(part, found.rhsStart) : null;
    if (!found || equationEnd === null || found.lhsStart < copiedUntil) {
      cursor += 1;
      continue;
    }
    const leftHandSide = `${braceMultiLetterScripts(part.slice(found.lhsStart, found.eqIndex).trimEnd())} = `;
    output += part.slice(copiedUntil, found.lhsStart);
    output += `$${leftHandSide}${part.slice(found.rhsStart, equationEnd).trim()}$`;
    copiedUntil = equationEnd;
    cursor = equationEnd;
  }
  return output + part.slice(copiedUntil);
}

/** `x_arc` → `x_{arc}`、`x^max` → `x^{max}`；单字符与已带花括号的不动。 */
export function braceMultiLetterScripts(text: string): string {
  return text
    .replace(/([_^])([A-Za-z]{2,})/gu, '$1{$2}')
    .replace(/([_^])([\p{Script=Greek}]{2,})/gu, '$1{$2}');
}

function repairBareMathPart(part: string): string {
  if (!part) return part;
  let repaired = part
    .replace(/\$(?:\\)?times/giu, '×')
    .replace(/\$(?:\\)?imes/giu, '×');
  repaired = wrapNotationListHeads(repaired);
  repaired = wrapExpectationAndComparison(repaired);
  return wrapSimpleBareEquations(repaired);
}

/**
 * 把一段纯文本切成「可修复片段」与「硬边界」（行内 HTML 标签、转义 $、行内代码）。
 * 硬边界原样输出，修复绝不跨越它——SC 的 `<br>` 缺陷就是没有这一步。
 */
function repairBareMath(text: string): string {
  const pieces: string[] = [];
  let cursor = 0;
  let runStart = 0;
  while (cursor < text.length) {
    const tag = text[cursor] === '<' ? INLINE_HTML_TAG.exec(text.slice(cursor)) : null;
    const escapedDollar = text[cursor] === '\\' && text[cursor + 1] === '$' ? '\\$' : null;
    const boundary = tag ? tag[0] : escapedDollar;
    if (!boundary) {
      cursor += 1;
      continue;
    }
    pieces.push(repairBareMathPart(text.slice(runStart, cursor)));
    pieces.push(boundary);
    cursor += boundary.length;
    runStart = cursor;
  }
  pieces.push(repairBareMathPart(text.slice(runStart)));
  return pieces.join('');
}

// ---------------------------------------------------------------------------

/**
 * 定界识别 + （仅模型文本）bare-math 修复。
 */
export function tokenize(text: string, options: TokenizeOptions = {}): TokenizeResult {
  const source: MathSource = options.source ?? 'library';
  const bareMath = options.bareMath ?? source === 'model';
  const scanned = scan(text);
  if (!bareMath) return scanned;

  const segments: Segment[] = [];
  const unresolved: UnresolvedDelimiter[] = [];
  for (const segment of scanned.segments) {
    if ('text' in segment) {
      const repaired = repairBareMath(segment.text);
      // 修复会插入 `$…$`，重新扫一遍拿到干净的片段；unresolved 只认最终文本，
      // 免得同一处货币 $ 在原扫与复扫里各记一次。
      const rescanned = scan(repaired);
      segments.push(...rescanned.segments);
      unresolved.push(...rescanned.unresolved);
    } else {
      segments.push(segment);
    }
  }
  return { segments, unresolved };
}

/** 便于用例比较：文本片段用字符串，公式片段用 {math, display}。 */
export function normalize(result: TokenizeResult): Segment[] {
  return result.segments
    .filter((segment) => 'math' in segment || segment.text.length > 0)
    .map((segment) => ('text' in segment ? { text: segment.text } : { math: segment.math, display: segment.display }));
}

/** 公式片段在原文里的位置（只对不跑 bare-math 的输入精确；跑过修复后文本已变，偏移只在修复后文本里成立）。 */
export function mathRanges(text: string, options: TokenizeOptions = {}): Array<{ start: number; end: number }> {
  return tokenize(text, options).segments
    .filter((segment): segment is { math: string; display: boolean; start: number; end: number } =>
      'math' in segment && typeof segment.start === 'number' && typeof segment.end === 'number')
    .map((segment) => ({ start: segment.start, end: segment.end }));
}

/**
 * 「这段正文像公式」的判据：没有 HTML 标签；中文只允许出现在 \text{…} 里。
 * 真实公式可以只是一个数字（`$40$`），所以「纯数字」本身不能当成货币误判——
 * 那会把真正的中文讲义里 `净收益 $40$ 元` 这类公式算错成误判。
 */
function mathBodyIsPlausible(body: string): boolean {
  if (/<\/?[A-Za-z][^<>]*>/u.test(body)) return false;
  if (/\p{Script=Han}/u.test(body) && !/\\text\s*\{/u.test(body)) return false;
  return true;
}

/**
 * 货币审计：每个 `$` 紧跟数字的位置，判定它是否被当成了公式定界符。
 * - 落在公式片段内、且正文带数学记号 → 记作公式（不算误判，例如 $140 > 100$）；
 * - 落在公式片段内、但正文只是数字/货币/OCR 文本 → 记作货币误判；
 * - 不在任何公式片段内 → 正确。
 */
export function auditCurrencyDollars(text: string, options: TokenizeOptions = {}): {
  total: number;
  insideMath: number;
  falsePositives: Array<{ index: number; snippet: string; body: string }>;
} {
  const result = tokenize(text, options);
  const mathSegments = result.segments.filter(
    (segment): segment is { math: string; display: boolean; start: number; end: number } =>
      'math' in segment && typeof segment.start === 'number' && typeof segment.end === 'number',
  );
  const falsePositives: Array<{ index: number; snippet: string; body: string }> = [];
  let total = 0;
  let insideMath = 0;
  for (let index = 0; index < text.length; index += 1) {
    if (text[index] !== '$' || !/[0-9]/u.test(text[index + 1] ?? '')) continue;
    total += 1;
    const segment = mathSegments.find((range) => index >= range.start && index < range.end);
    if (!segment) continue;
    insideMath += 1;
    if (!mathBodyIsPlausible(segment.math)) {
      falsePositives.push({
        index,
        snippet: text.slice(Math.max(0, index - 20), index + 30),
        body: segment.math.slice(0, 120),
      });
    }
  }
  return { total, insideMath, falsePositives };
}
