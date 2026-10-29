// mathsvc 的用户可见质量测试：出 SVG、元素在 Painter 子集内、错误退成 chip、尺寸是 em、缓存生效。
// 运行：npm test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { renderTeX } from '../src/render.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const ALLOW = new Set(['svg', 'g', 'path', 'rect', 'text']);

function tagsIn(svg) {
  return [...new Set([...svg.matchAll(/<([a-zA-Z][\w:-]*)/g)].map((match) => match[1].toLowerCase()))];
}

const SAMPLES = [
  ['x', false],
  ['P_{\\text{自付}} = P(1-c)', false],
  ['\\text{GDP}\\times\\text{占比}', false],
  ['E[Y\\mid P=p_1]-E[Y\\mid P=p_0]=\\underbrace{E[Y(p_1)-Y(p_0)\\mid P=p_1]}_{\\text{处理组的因果效应}}', true],
  ['\\frac{1}{\\sqrt{2\\pi\\sigma^2}}e^{-\\frac{(x-\\mu)^2}{2\\sigma^2}}', true],
  ['\\sum_{t=0}^{T}\\frac{I_p(t)}{(1+r^*)^t}=0', true],
  ['\\begin{matrix}a&b\\\\c&d\\end{matrix}', true],
];

test('每条公式都出 SVG，且元素只在 Painter 子集内', () => {
  for (const [tex, display] of SAMPLES) {
    const result = renderTeX({ tex, display });
    assert.equal(result.ok, true, `${tex} 应渲染成功：${JSON.stringify(result.error)}`);
    for (const tag of tagsIn(result.svg)) assert.ok(ALLOW.has(tag), `${tex} 出现子集外元素 ${tag}`);
    assert.match(result.svg, /^<svg /, '输出应是单个 <svg> 根，不带 mjx-container');
    assert.doesNotMatch(result.svg, /\sdata-|\saria-|\srole=|\sfocusable=/u, 'data-*/aria 应被剥掉');
  }
});

test('尺寸是 em，且 w/h/d 与 viewBox 一致', () => {
  const result = renderTeX({ tex: '\\frac{a}{b}', display: true });
  assert.equal(result.ok, true);
  assert.match(result.svg, /width="[\d.]+em"/u);
  assert.match(result.svg, /height="[\d.]+em"/u);
  assert.match(result.svg, /vertical-align: -[\d.]+em/u);
  const box = /viewBox="([^"]+)"/u.exec(result.svg)[1].split(/\s+/u).map(Number);
  assert.equal(result.w, Math.round((box[2] / 1000) * 1000) / 1000);
  assert.equal(result.d, Math.round((-box[1] / 1000) * 1000) / 1000);
  assert.ok(result.h > 0 && result.d > 0, 'w/h/d 应该是正数');
});

test('中文 \\text{} 出 <text>，带 font-size 与 font-family，客户端可回退字体', () => {
  const result = renderTeX({ tex: '\\text{斜率}', display: false });
  assert.equal(result.ok, true);
  const text = [...result.svg.matchAll(/<text[^>]*>[^<]*<\/text>/g)].map((match) => match[0]);
  assert.equal(text.length, 2, '两个汉字应是两个 <text>');
  for (const element of text) {
    assert.match(element, /font-size="\d+px"/u);
    assert.match(element, /font-family="serif"/u);
    assert.match(element, /transform="(translate\([^)]*\) )?scale\(1,-1\)"/u, 'text 必须有翻转 transform，否则倒着画');
  }
});

test('嵌套 svg 允许（Painter 按 viewBox 映射）', () => {
  const result = renderTeX({ tex: '\\overbrace{a+b}^{\\text{两}}', display: true });
  assert.equal(result.ok, true);
  assert.ok((result.svg.match(/<svg/g) ?? []).length > 1, 'overbrace 应产生嵌套 svg');
});

test('不认识的宏与写错的 TeX 都要报错（客户端显示源码 chip），不能静默画成红字', () => {
  const unknown = renderTeX({ tex: '\\foo{bar}', display: false });
  assert.equal(unknown.ok, false);
  assert.equal(unknown.error.code, 'unsupported-macro');
  assert.deepEqual(unknown.error.macros, ['foo']);

  const broken = renderTeX({ tex: '\\frac{1}{', display: false });
  assert.equal(broken.ok, false);
  assert.equal(broken.error.code, 'tex');
});

test('Painter 子集外的元素（\\cancel 的 <line>）整条退成 chip', () => {
  const result = renderTeX({ tex: '\\cancel{x}', display: false });
  assert.equal(result.ok, false);
  assert.equal(result.error.code, 'unsupported-output');
  assert.ok(result.error.elements.includes('line'));
});

test('空 TeX 直接报错，sha 仍是 tex 的 sha256', () => {
  const result = renderTeX({ tex: '   ', display: false });
  assert.equal(result.ok, false);
  assert.equal(result.error.code, 'tex');
  assert.match(result.sha, /^[0-9a-f]{64}$/u);
});

test('行协议：ping 有 RSS，重复请求命中缓存', async () => {
  const child = spawn(process.execPath, [join(HERE, '..', 'src', 'server.mjs')], { stdio: ['pipe', 'pipe', 'ignore'] });
  const lines = createInterface({ input: child.stdout });
  const queue = [];
  lines.on('line', (line) => queue.shift()?.(JSON.parse(line)));
  const call = (request) => new Promise((resolveCall) => {
    queue.push(resolveCall);
    child.stdin.write(`${JSON.stringify(request)}\n`);
  });

  const first = await call({ id: '1', tex: 'x^2', display: false });
  const second = await call({ id: '2', tex: 'x^2', display: false });
  assert.equal(first.sha, second.sha);
  assert.equal(second.svg, first.svg);

  const ping = await call({ id: '3', type: 'ping' });
  assert.equal(ping.type, 'pong');
  assert.equal(ping.hits, 1);
  assert.equal(ping.misses, 1);
  assert.ok(ping.rss_kb > 0);

  await call({ id: '4', type: 'shutdown' });
  child.stdin.end();
});
