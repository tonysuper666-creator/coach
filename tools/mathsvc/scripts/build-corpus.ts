// 生成黄金语料目录（默认 ~/Coach/corpus/math/，不进仓库：里面有课程原文、真题与他的对话）
// 用法：node --experimental-strip-types scripts/build-corpus.ts
//   环境变量：COACH_CORPUS / COACH_D1 / COACH_LIBRARY / COACH_DIGEST / COACH_STAGING
// 来源全部只读：核查员产物 ~/Coach/verifier-artifacts/d1、DeepTutor 库（只读）、chat_history.db 的迁移暂存拷贝。
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, readdirSync, statSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { auditCurrencyDollars, braceMultiLetterScripts, tokenize } from '../delims/reference.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const HOME = process.env.HOME ?? '';
const OUT = process.env.COACH_CORPUS ?? join(HOME, 'Coach/corpus/math');

const D1 = process.env.COACH_D1 ?? join(HOME, 'Coach/verifier-artifacts/d1');
const LIBRARY = process.env.COACH_LIBRARY ?? join(HOME, 'DeepTutor/library/ECNM10119-健康经济学');
const DIGEST = process.env.COACH_DIGEST ?? join(HOME, 'DeepTutor/data/digests/questions/ECNM10119-期中练习题.json');
const STAGING = process.env.COACH_STAGING ?? join(HOME, 'Coach/migrate-staging/20261029T195312');

const sha256 = (text: string): string => createHash('sha256').update(text, 'utf8').digest('hex');
const sha256File = (path: string): string => createHash('sha256').update(readFileSync(path)).digest('hex');

interface MathSegmentCheck {
  math: number;
  first?: { tex: string; display: boolean };
}

function mathIn(text: string, source: 'model' | 'library'): MathSegmentCheck {
  const result = tokenize(text, { source });
  const mathSegments = result.segments.filter((segment) => 'math' in segment) as { math: string; display: boolean }[];
  return mathSegments.length === 0
    ? { math: 0 }
    : { math: mathSegments.length, first: { tex: mathSegments[0].math, display: mathSegments[0].display } };
}

// ---------------------------------------------------------------------------
// 1. 50 条课程公式
// ---------------------------------------------------------------------------
const formulas50 = JSON.parse(readFileSync(join(D1, 'formulas50.json'), 'utf8')) as { tex: string; display: boolean; src: string }[];
if (formulas50.length !== 50) throw new Error(`formulas50.json 应为 50 条，实际 ${formulas50.length}`);
const courseFormulas = {
  source: '~/Coach/verifier-artifacts/d1/formulas50.json',
  note: '核查员 d1 从课程库（~/' + 'DeepTutor/library/ECNM10119-健康经济学）抽出的 50 条公式，src 为原文件或对话消息',
  items: formulas50.map((item, index) => ({
    id: `course-${String(index + 1).padStart(2, '0')}`,
    tex: item.tex,
    display: item.display,
    src: item.src,
    sha256: sha256(item.tex),
  })),
};

// ---------------------------------------------------------------------------
// 2. 真实导师回答（整段 Markdown）
// ---------------------------------------------------------------------------
interface AnswerRow {
  id: number;
  session_id: string;
  content: string;
  created_at: number;
}

const DATABASES = [
  { path: join(STAGING, 'chat_history.db'), label: 'chat_history.db（2026-10-29 迁移暂存拷贝，主库）' },
  { path: join(STAGING, 'migration-chat_history.db'), label: 'chat_history.db（迁移目标暂存拷贝，2026-10-28 之前的历史会话）' },
];

const tutorAnswers: unknown[] = [];
for (const database of DATABASES) {
  const rows = JSON.parse(
    execFileSync('sqlite3', [
      '-json',
      database.path,
      "select id, session_id, content, created_at from messages where role='assistant' and length(content)>0 order by id",
    ]).toString(),
  ) as AnswerRow[];
  for (const row of rows) {
    const check = mathIn(row.content, 'model');
    if (check.math === 0) continue;
    tutorAnswers.push({
      id: `answer-${database.path.includes('migration') ? 'old' : 'live'}-${row.id}`,
      source: database.label,
      message_id: row.id,
      session_id: row.session_id,
      created_at: new Date(row.created_at * 1000).toISOString(),
      chars: row.content.length,
      math_segments: check.math,
      md_sha256: sha256(row.content),
      content: row.content,
    });
  }
}

const tutorAnswerFile = {
  source: DATABASES.map((database) => ({
    path: database.path,
    label: database.label,
    sha256: sha256File(database.path),
  })),
  note: 'DeepTutor 导师（role=assistant）回答整段 Markdown，含公式的按 message id 全量收录',
  items: tutorAnswers,
};

// ---------------------------------------------------------------------------
// 3. 课程库中含 $数字 的原句（货币误判的黄金用例）
// ---------------------------------------------------------------------------
const TEXT_FILE = /\.(md|txt|json)$/u;

function walk(directory: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) out.push(...walk(path));
    else if (TEXT_FILE.test(entry.name)) out.push(path);
  }
  return out;
}

const DOLLAR_DIGIT = /\$[0-9]/u;
const currencyLines: unknown[] = [];
const currencyByDir = new Map<string, number>();
let currencyDollarTotal = 0;
const currencyFalsePositives: Array<{ id: string; path: string; line: number; index: number; snippet: string; body: string }> = [];
let libraryFiles = 0;

for (const path of walk(LIBRARY).sort()) {
  libraryFiles += 1;
  const lines = readFileSync(path, 'utf8').split('\n');
  const rel = relative(LIBRARY, path);
  lines.forEach((text, index) => {
    if (!DOLLAR_DIGIT.test(text)) return;
    const check = mathIn(text, 'library');
    const audit = auditCurrencyDollars(text, { source: 'library' });
    currencyDollarTotal += audit.total;
    const id = `currency-${currencyLines.length + 1}`;
    for (const hit of audit.falsePositives) {
      currencyFalsePositives.push({ id, path: rel, line: index + 1, index: hit.index, snippet: hit.snippet, body: hit.body });
    }
    const top = rel.split('/')[0];
    currencyByDir.set(top, (currencyByDir.get(top) ?? 0) + 1);
    currencyLines.push({
      id,
      path: rel,
      line: index + 1,
      text,
      dollars_followed_by_digit: audit.total,
      currency_false_positives: audit.falsePositives.length,
      math_segments_on_line: check.math,
      ...(check.first ? { first_math: check.first } : {}),
    });
  });
}

const currencyFile = {
  source: `~/DeepTutor/library/ECNM10119-健康经济学（${libraryFiles} 个 .md/.txt/.json 文件，只读）`,
  note: '课程库中所有含 `$数字` 的原始行。判定以「每个 `$数字` 是否被当成定界符」为准（currency_false_positives），不是整行有没有公式——同一行里可以既有货币又有真公式。核查员 d1 计数 386 行，以本文件实际行数为准。',
  counts: {
    files: libraryFiles,
    lines: currencyLines.length,
    dollars_followed_by_digit: currencyDollarTotal,
    currency_false_positives: currencyFalsePositives.length,
    by_top_dir: Object.fromEntries(currencyByDir),
  },
  false_positives: currencyFalsePositives,
  items: currencyLines,
};

// ---------------------------------------------------------------------------
// 4. 10 道选项含公式的题
// ---------------------------------------------------------------------------
interface DigestQuestion {
  question: string;
  question_type?: string;
  options?: Record<string, string>;
  correct_answer?: string;
  explanation?: string;
  difficulty?: string;
  tags?: string[];
}

const digest = JSON.parse(readFileSync(DIGEST, 'utf8')) as DigestQuestion[];
const QUESTION_INDEXES = [4, 20, 21, 22, 36, 48, 56, 66, 75, 92]; // 0-based；7 道记号题 + 3 道货币题（4/66/…），见 note
/** 选项里的公式是裸记号（没有 `$`），这里按两种可判定的形态抽出来，只用于渲染测试。 */
const NOTATION_SCRIPTED = /[A-Za-z\u0391-\u03c9](?:(?:_|\^)\{?[A-Za-z0-9]{1,4}\}?)+/gu;
const NOTATION_EQUATION = /[A-Za-z]{1,8}\s*=\s*[-−]?\d+(?:\.\d+)?/gu;

function extractNotation(text: string): string[] {
  const found: string[] = [];
  for (const pattern of [NOTATION_SCRIPTED, NOTATION_EQUATION]) {
    for (const match of text.matchAll(pattern)) {
      const tex = braceMultiLetterScripts(match[0].trim());
      if (!found.includes(tex)) found.push(tex);
    }
  }
  return found;
}

const questions = QUESTION_INDEXES.map((index, order) => {
  const item = digest[index];
  if (!item) throw new Error(`digest 没有第 ${index} 题`);
  const options = item.options ?? {};
  const mathFields = Object.entries(options)
    .map(([key, value]) => {
      const notation = extractNotation(value);
      return notation.length > 0 ? { field: key, raw: value, notation } : null;
    })
    .filter((entry): entry is { field: string; raw: string; notation: string[] } => entry !== null);
  return {
    id: `question-${String(order + 1).padStart(2, '0')}`,
    digest_index: index,
    source: join('~/DeepTutor/data/digests/questions', 'ECNM10119-期中练习题.json'),
    question: item.question,
    options,
    correct_answer: item.correct_answer,
    explanation: item.explanation,
    tags: item.tags ?? [],
    notation_kind: mathFields.length > 0 ? 'notation' : 'currency-only',
    notation_in_options: mathFields,
  };
});

const questionFile = {
  source: '~/DeepTutor/data/digests/questions/ECNM10119-期中练习题.json（108 题）',
  note: '10 道题：8 道选项里带公式记号（^、_、=、%，notation_kind=notation），2 道选项里只有货币金额（notation_kind=currency-only，用来证明钱数保持文本）。digest_index 是原文件 0-based 序号，便于复查；notation_in_options 把裸记号抽成可渲染的 TeX（多字母下标补花括号），只用于验证 mathsvc。',
  items: questions,
};

// ---------------------------------------------------------------------------

mkdirSync(OUT, { recursive: true });
const write = (name: string, data: unknown): { file: string; bytes: number; sha256: string } => {
  const text = `${JSON.stringify(data, null, 1)}\n`;
  const path = join(OUT, name);
  writeFileSync(path, text);
  return { file: name, bytes: statSync(path).size, sha256: sha256(text) };
};

const written = [
  write('course-formulas.json', courseFormulas),
  write('tutor-answers.json', tutorAnswerFile),
  write('currency-lines.json', currencyFile),
  write('questions-with-math.json', questionFile),
];

const meta = {
  generated_at: new Date().toISOString(),
  generator: 'tools/mathsvc/scripts/build-corpus.ts',
  node: process.version,
  counts: {
    course_formulas: courseFormulas.items.length,
    tutor_answers: tutorAnswers.length,
    currency_lines: currencyLines.length,
    currency_dollars_followed_by_digit: currencyDollarTotal,
    currency_false_positives: currencyFalsePositives.length,
    questions: questions.length,
  },
  sources: {
    d1: D1,
    library: LIBRARY,
    library_files: libraryFiles,
    digest: DIGEST,
    databases: tutorAnswerFile.source,
  },
  files: written,
};
write('meta.json', meta);

console.log(JSON.stringify(meta.counts, null, 1));
console.log(`currency lines by dir: ${JSON.stringify(Object.fromEntries(currencyByDir))}`);
console.log(`currency dollars followed by digit: ${currencyDollarTotal}, false positives: ${currencyFalsePositives.length}`);
for (const hit of currencyFalsePositives.slice(0, 10)) console.log('  FP', `${hit.path}:${hit.line}`, JSON.stringify(hit.body));
console.log(`written to ${OUT}`);
for (const entry of written) console.log(` ${entry.file}  ${entry.bytes} bytes  ${entry.sha256.slice(0, 12)}`);
