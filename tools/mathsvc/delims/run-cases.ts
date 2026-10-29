// 用 delims/cases.json 跑参考实现。语言无关：Kotlin 移植只需按同一份 JSON 对齐。
// 用法：node --experimental-strip-types delims/run-cases.ts [cases.json]
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { normalize, tokenize, type MathSource, type Segment } from './reference.ts';

interface CaseExpectation {
  text?: string;
  math?: string;
  display?: boolean;
}

interface DelimCase {
  name: string;
  source: MathSource;
  input: string;
  expect: CaseExpectation[];
  unresolved?: number;
  note?: string;
}

const here = dirname(fileURLToPath(import.meta.url));
const file = process.argv[2] ?? resolve(here, 'cases.json');
const cases = JSON.parse(readFileSync(file, 'utf8')) as { cases: DelimCase[] };

function describe(segments: Segment[]): CaseExpectation[] {
  return segments.map((segment) => ('text' in segment ? { text: segment.text } : { math: segment.math, display: segment.display }));
}

function same(a: CaseExpectation[], b: CaseExpectation[]): boolean {
  if (a.length !== b.length) return false;
  return a.every((item, index) => {
    const other = b[index];
    if (item.text !== undefined) return other.text === item.text;
    return other.math === item.math && other.display === item.display;
  });
}

let failed = 0;
for (const testCase of cases.cases) {
  const result = tokenize(testCase.input, { source: testCase.source });
  const actual = describe(normalize(result));
  const expectUnresolved = testCase.unresolved ?? 0;
  const ok = same(actual, testCase.expect) && result.unresolved.length === expectUnresolved;
  if (!ok) {
    failed += 1;
    console.log(`FAIL ${testCase.name}`);
    console.log('  input     ', JSON.stringify(testCase.input));
    console.log('  expect    ', JSON.stringify(testCase.expect), 'unresolved', expectUnresolved);
    console.log('  actual    ', JSON.stringify(actual), 'unresolved', result.unresolved.length);
    if (result.unresolved.length > 0) console.log('  reasons   ', JSON.stringify(result.unresolved));
  }
}

console.log(`${cases.cases.length - failed}/${cases.cases.length} cases passed (${file})`);
process.exit(failed === 0 ? 0 : 1);
