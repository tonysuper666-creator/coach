// 仓库侧语料测试：只用合成样例（acceptance/corpus/math/synthetic-formulas.json），
// 不需要本机真实语料。测的是用户能看到的两件事：公式都画得出来（元素在 Painter 子集内），
// 钱数不会被当成公式。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderTeX } from '../src/render.mjs';
import { auditCurrencyDollars, tokenize } from '../delims/reference.ts';

const HERE = dirname(fileURLToPath(import.meta.url));
const FIXTURES = JSON.parse(readFileSync(join(HERE, '..', '..', '..', 'acceptance', 'corpus', 'math', 'synthetic-formulas.json'), 'utf8'));
const ALLOW = new Set(['svg', 'g', 'path', 'rect', 'text']);

test('合成公式样例齐 20 条', () => {
  assert.equal(FIXTURES.formulas.length, 20);
  assert.equal(new Set(FIXTURES.formulas.map((item) => item.id)).size, 20, 'id 不能重复');
});

test('20 条合成公式全部出 SVG，元素在 Painter 子集内', () => {
  for (const item of FIXTURES.formulas) {
    const result = renderTeX({ tex: item.tex, display: item.display });
    assert.equal(result.ok, true, `${item.id} 应渲染成功：${JSON.stringify(result.error)}`);
    const tags = new Set([...result.svg.matchAll(/<([a-zA-Z][\w:-]*)/g)].map((match) => match[1].toLowerCase()));
    for (const tag of tags) assert.ok(ALLOW.has(tag), `${item.id} 出现子集外元素 ${tag}`);
    assert.ok(result.w > 0, `${item.id} 宽度应为正`);
    assert.ok(result.h >= 0 && result.d >= 0, `${item.id} 高/深应为非负`);
  }
});

test('合成公式里含 \\text{中文} 的，必须出 <text> 且带翻转 transform', () => {
  const withHan = FIXTURES.formulas.filter((item) => /\p{Script=Han}/u.test(item.tex));
  assert.ok(withHan.length >= 3, '样例里应有若干含中文的公式');
  for (const item of withHan) {
    const result = renderTeX({ tex: item.tex, display: item.display });
    const texts = [...result.svg.matchAll(/<text[^>]*>[^<]*<\/text>/g)].map((match) => match[0]);
    assert.ok(texts.length > 0, `${item.id} 中文应出 <text>`);
    for (const element of texts) assert.match(element, /scale\(1,-1\)/u, `${item.id} 的 <text> 缺翻转`);
  }
});

test('合成货币句子里，没有哪个 $ 被当成定界符', () => {
  for (const sentence of FIXTURES.currency_sentences) {
    const audit = auditCurrencyDollars(sentence.text, { source: 'library' });
    assert.equal(audit.falsePositives.length, 0, `${sentence.id} 把货币当成了公式：${JSON.stringify(audit.falsePositives)}`);
  }
});

test('货币与真公式同句时，只认出真公式', () => {
  const sentence = FIXTURES.currency_sentences.find((item) => item.id === 'synth-cur-06');
  const result = tokenize(sentence.text, { source: 'library' });
  const math = result.segments.filter((segment) => 'math' in segment);
  assert.equal(math.length, 1);
  assert.equal(math[0].math, '4.7\\times10^{10}');
});
