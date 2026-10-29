// 跑 mathsvc 的真实进程，把黄金语料里的每条公式都渲染一遍并出报告。
// 用法：node --experimental-strip-types scripts/measure.ts [--corpus <目录>]
// 默认读 ~/Coach/corpus/math/（真实语料，不进仓库），产出该目录下的 report.json + REPORT.md。
// 环境变量：COACH_CORPUS
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createInterface } from 'node:readline';
import { tokenize } from '../delims/reference.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const TOOL = resolve(HERE, '..');
const HOME = process.env.HOME ?? '';
const CORPUS_ARG = process.argv.indexOf('--corpus');
const CORPUS = CORPUS_ARG > -1 && process.argv[CORPUS_ARG + 1]
  ? resolve(process.argv[CORPUS_ARG + 1])
  : (process.env.COACH_CORPUS ?? join(HOME, 'Coach/corpus/math'));
const ELEMENT_ALLOW = new Set(['svg', 'g', 'path', 'rect', 'text']);

interface WorkItem {
  id: string;
  origin: 'course-formula' | 'tutor-answer' | 'question';
  source: string;
  tex: string;
  display: boolean;
}

interface RenderReply {
  id: string;
  sha?: string;
  svg?: string;
  w?: number;
  h?: number;
  d?: number;
  error?: { code: string; message: string; macros?: string[] };
  type?: string;
  rss_kb?: number;
}

// ---------------------------------------------------------------------------
// 语料 → 待渲染清单
// ---------------------------------------------------------------------------
const worklist: WorkItem[] = [];
const courseFormulas = JSON.parse(readFileSync(join(CORPUS, 'course-formulas.json'), 'utf8'));
for (const item of courseFormulas.items) {
  worklist.push({ id: item.id, origin: 'course-formula', source: item.src, tex: item.tex, display: item.display });
}

const tutorAnswers = JSON.parse(readFileSync(join(CORPUS, 'tutor-answers.json'), 'utf8'));
for (const answer of tutorAnswers.items) {
  const result = tokenize(answer.content, { source: 'model' });
  let index = 0;
  for (const segment of result.segments) {
    if (!('math' in segment)) continue;
    index += 1;
    worklist.push({
      id: `${answer.id}-m${index}`,
      origin: 'tutor-answer',
      source: `msg ${answer.message_id}`,
      tex: segment.math,
      display: segment.display,
    });
  }
}

const questions = JSON.parse(readFileSync(join(CORPUS, 'questions-with-math.json'), 'utf8'));
for (const question of questions.items) {
  for (const field of question.notation_in_options ?? []) {
    field.notation.forEach((tex: string, index: number) => {
      worklist.push({
        id: `${question.id}-${field.field}-n${index + 1}`,
        origin: 'question',
        source: `digest ${question.digest_index} option ${field.field}`,
        tex,
        display: false,
      });
    });
  }
}

// ---------------------------------------------------------------------------
// 起真实进程，走 stdin/stdout JSON 行协议
// ---------------------------------------------------------------------------
const child = spawn(process.execPath, [join(TOOL, 'src', 'server.mjs')], { stdio: ['pipe', 'pipe', 'pipe'] });
const stderrChunks: string[] = [];
child.stderr.on('data', (chunk) => stderrChunks.push(String(chunk)));
const lines = createInterface({ input: child.stdout });
const pending: ((reply: RenderReply) => void)[] = [];
lines.on('line', (line) => {
  const resolveNext = pending.shift();
  if (resolveNext) resolveNext(JSON.parse(line) as RenderReply);
});

function call(request: Record<string, unknown>): Promise<RenderReply> {
  return new Promise((resolveCall) => {
    pending.push(resolveCall);
    child.stdin.write(`${JSON.stringify(request)}\n`);
  });
}

const latencies: number[] = [];
const seenKeys = new Set<string>();
let uniqueRenders = 0;
const cacheHits: number[] = [];
const failures: Array<{ id: string; origin: string; source: string; tex: string; error: unknown }> = [];
const elementCensus = new Map<string, number>();
const disallowed: Array<{ id: string; tags: string[] }> = [];
const results: RenderReply[] = [];
const perFormula: Array<{ id: string; origin: string; tags: string[]; svg_count: number; w?: number; h?: number; d?: number; sha?: string }> = [];

for (const item of worklist) {
  const key = `${item.tex}\u0000${item.display}`;
  const firstTime = !seenKeys.has(key);
  seenKeys.add(key);
  const started = process.hrtime.bigint();
  const reply = await call({ id: item.id, tex: item.tex, display: item.display });
  const elapsed = Number(process.hrtime.bigint() - started) / 1e6;
  // 冷启动只统计真渲染（缓存 miss）的那一次，否则重复公式会把 p50 拉成缓存命中
  if (firstTime) {
    latencies.push(elapsed);
    uniqueRenders += 1;
  }
  results.push(reply);
  if (reply.error) {
    failures.push({ id: item.id, origin: item.origin, source: item.source, tex: item.tex, error: reply.error });
    continue;
  }
  const tags = new Set<string>();
  for (const match of (reply.svg ?? '').matchAll(/<([a-zA-Z][\w:-]*)/g)) tags.add(match[1].toLowerCase());
  const svgCount = ((reply.svg ?? '').match(/<svg/g) ?? []).length;
  perFormula.push({
    id: item.id,
    origin: item.origin,
    tags: [...tags].sort(),
    svg_count: svgCount,
    w: reply.w,
    h: reply.h,
    d: reply.d,
    sha: reply.sha,
  });
  for (const tag of tags) elementCensus.set(tag, (elementCensus.get(tag) ?? 0) + 1);
  const outside = [...tags].filter((tag) => !ELEMENT_ALLOW.has(tag));
  if (outside.length > 0) disallowed.push({ id: item.id, tags: outside });
}

// 第二轮：全部命中缓存，量缓存路径耗时
for (const item of worklist) {
  const started = process.hrtime.bigint();
  await call({ id: `${item.id}#warm`, tex: item.tex, display: item.display });
  cacheHits.push(Number(process.hrtime.bigint() - started) / 1e6);
}

const ping = await call({ id: 'ping', type: 'ping' });
await call({ id: 'bye', type: 'shutdown' });

function percentile(values: number[], p: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return Math.round(sorted[index] * 100) / 100;
}

const successRate = worklist.length === 0 ? 0 : (worklist.length - failures.length) / worklist.length;

// 中文 <text> 的形态：找一条含 \text{中文} 的公式，把它的 text 元素原样摘出来
const cjkItem = worklist.find((item) => /\p{Script=Han}/u.test(item.tex)) ?? null;
let cjkShape: { id: string; tex: string; textElements: string[] } | null = null;
if (cjkItem) {
  const reply = await Promise.resolve().then(() => renderLocally(cjkItem.tex, cjkItem.display));
  const textElements = [...reply.matchAll(/<text[^>]*>[^<]*<\/text>/g)].map((match) => match[0]).slice(0, 4);
  cjkShape = { id: cjkItem.id, tex: cjkItem.tex, textElements };
}

function renderLocally(tex: string, display: boolean): Promise<string> {
  const worker = spawn(process.execPath, ['-e', `
    import('${join(TOOL, 'src', 'render.mjs').replace(/\\/g, '/')}').then(({renderTeX}) => {
      const result = renderTeX({tex: ${JSON.stringify(tex)}, display: ${display}});
      process.stdout.write(result.ok ? result.svg : '');
    });
  `], { stdio: ['ignore', 'pipe', 'inherit'] });
  return new Promise((resolveLocal) => {
    let out = '';
    worker.stdout.on('data', (chunk) => (out += String(chunk)));
    worker.on('close', () => resolveLocal(out));
  });
}

const report = {
  generated_at: new Date().toISOString(),
  node: process.version,
  mathjax: 'mathjax-full@3.2.2',
  font_cache: 'none',
  totals: {
    formulas: worklist.length,
    unique_formulas: uniqueRenders,
    by_origin: worklist.reduce<Record<string, number>>((acc, item) => {
      acc[item.origin] = (acc[item.origin] ?? 0) + 1;
      return acc;
    }, {}),
    rendered: worklist.length - failures.length,
    failed: failures.length,
    success_rate: Math.round(successRate * 10000) / 10000,
  },
  latency_ms: {
    cold: { p50: percentile(latencies, 50), p95: percentile(latencies, 95), max: Math.max(...latencies.map((v) => Math.round(v * 100) / 100)) },
    cache_hit: { p50: percentile(cacheHits, 50), p95: percentile(cacheHits, 95) },
  },
  memory: { rss_kb_after_render: ping.rss_kb ?? null, cache_entries: (ping as { cache?: number }).cache ?? null },
  elements: {
    allow: [...ELEMENT_ALLOW],
    nested_svg_formulas: perFormula.filter((entry) => entry.svg_count > 1).length,
    per_formula: perFormula,
    census: Object.fromEntries([...elementCensus.entries()].sort((a, b) => b[1] - a[1])),
    out_of_subset: disallowed,
  },
  cjk_text_shape: cjkShape,
  failures,
  stderr_tail: stderrChunks.join('').split('\n').filter(Boolean).slice(-5),
};

const md: string[] = [];
md.push('# mathsvc 渲染报告（黄金语料）');
md.push('');
md.push(`生成时间：${report.generated_at} · Node ${report.node} · mathjax-full 3.2.2 · fontCache none`);
md.push('');
md.push('## 结果');
md.push('');
md.push(`- 公式总数：${report.totals.formulas}（课程公式 ${report.totals.by_origin['course-formula'] ?? 0}，真实回答 ${report.totals.by_origin['tutor-answer'] ?? 0}，题目选项 ${report.totals.by_origin.question ?? 0}）`);
md.push(`- 出 SVG：${report.totals.rendered} / ${report.totals.formulas}（${(successRate * 100).toFixed(1)}%），失败 ${report.totals.failed}`);
md.push(`- 冷启动单条耗时 p50 ${report.latency_ms.cold.p50} ms · p95 ${report.latency_ms.cold.p95} ms · max ${report.latency_ms.cold.max} ms`);
md.push(`- 缓存命中 p50 ${report.latency_ms.cache_hit.p50} ms · p95 ${report.latency_ms.cache_hit.p95} ms`);
md.push(`- 常驻 RSS：${report.memory.rss_kb_after_render} KB（${Math.round((report.memory.rss_kb_after_render ?? 0) / 1024)} MB），缓存 ${report.memory.cache_entries} 条`);
md.push('');
md.push('## 元素种类分布');
md.push('');
md.push('| 元素 | 出现次数（按公式计） | 在子集内 |');
md.push('|---|---|---|');
for (const [tag, count] of Object.entries(report.elements.census)) {
  md.push(`| \`${tag}\` | ${count} | ${ELEMENT_ALLOW.has(tag) ? '是' : '**否**'} |`);
}
md.push('');
md.push(`子集外元素：${disallowed.length === 0 ? '无' : disallowed.map((item) => `${item.id}: ${item.tags.join(',')}`).join('; ')}`);
md.push('');
md.push(`含嵌套 \`<svg>\` 的公式：${report.elements.nested_svg_formulas} 条（客户端 Painter 按 DESIGN §7.4 处理嵌套 svg 的 x/y 与 viewBox）`);
md.push('');
if (cjkShape) {
  md.push('## 含中文公式的 `<text>` 形态');
  md.push('');
  md.push(`样例：\`${cjkShape.tex.replace(/`/g, '')}\`（${cjkShape.id}）`);
  md.push('');
  md.push('```html');
  for (const element of cjkShape.textElements) md.push(element);
  md.push('```');
  md.push('');
}
if (failures.length > 0) {
  md.push('## 失败清单（退路：客户端显示源码 chip，并单独计数）');
  md.push('');
  md.push('| 条目 | 来源 | TeX | 原因 |');
  md.push('|---|---|---|---|');
  for (const failure of failures) {
    const error = failure.error as { code: string; message: string };
    md.push(`| ${failure.id} | ${failure.source} | \`${failure.tex.replace(/\|/g, '\\|')}\` | ${error.code}: ${error.message.replace(/\|/g, '\\|')} |`);
  }
  md.push('');
}
writeFileSync(join(CORPUS, 'report.json'), `${JSON.stringify(report, null, 1)}\n`);
writeFileSync(join(CORPUS, 'REPORT.md'), `${md.join('\n')}\n`);
console.log(JSON.stringify({ totals: report.totals, latency: report.latency_ms, elements: report.elements.census, out_of_subset: disallowed.length, rss_kb: report.memory.rss_kb_after_render }, null, 1));
