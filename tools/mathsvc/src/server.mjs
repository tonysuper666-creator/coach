// Coach mathsvc：常驻进程，stdin/stdout JSON 行协议。
//
// 请求：
//   {"id":"1","tex":"\\frac{a}{b}","display":true}
//   {"id":"2","type":"ping"}
//   {"id":"3","type":"shutdown"}
// 应答：
//   {"id":"1","sha":"…","svg":"<svg …>","w":0.969,"h":0.697,"d":1.117}
//   {"id":"1","sha":"…","error":{"code":"unsupported-macro","message":"…","macro":"foo"}}
//   {"id":"2","type":"pong","pid":123,"uptime_ms":1234,"rss_kb":12345,"cache":12,"hits":0,"misses":12}
//
// 约定：一行一个 JSON，不写日志到 stdout（日志走 stderr）。
import { createInterface } from 'node:readline';
import { renderTeX, sha256, MATHJAX_VERSION, FONT_CACHE } from './render.mjs';

const startedAt = Date.now();
const cache = new Map();
const stats = { hits: 0, misses: 0, errors: 0, renders: 0 };

function log(...args) {
  process.stderr.write(`[mathsvc ${new Date().toISOString()}] ${args.join(' ')}\n`);
}

function cacheKey(sha, display) {
  return `${sha}:${display ? 'D' : 'I'}`;
}

export function handleRequest(request) {
  const id = request?.id ?? null;
  if (request?.type === 'ping') {
    return {
      id,
      type: 'pong',
      pid: process.pid,
      uptime_ms: Date.now() - startedAt,
      rss_kb: Math.round(process.memoryUsage().rss / 1024),
      cache: cache.size,
      hits: stats.hits,
      misses: stats.misses,
      errors: stats.errors,
      mathjax: MATHJAX_VERSION,
      font_cache: FONT_CACHE,
    };
  }
  if (typeof request?.tex !== 'string') {
    return { id, error: { code: 'protocol', message: 'missing "tex" string' } };
  }

  const display = Boolean(request.display);
  const key = cacheKey(sha256(request.tex), display);
  const cached = cache.get(key);
  if (cached) {
    stats.hits += 1;
    return { id, ...cached };
  }

  stats.misses += 1;
  const result = renderTeX({ tex: request.tex, display });
  stats.renders += 1;
  if (!result.ok) {
    stats.errors += 1;
    return { id, sha: result.sha, error: result.error };
  }
  if (!result.ok) log(`render failed ${result.sha} ${result.error.code}`);
  const payload = { sha: result.sha, svg: result.svg, w: result.w, h: result.h, d: result.d };
  cache.set(key, payload);
  return { id, ...payload };
}

function main() {
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
  log(`ready pid=${process.pid} mathjax=${MATHJAX_VERSION} fontCache=${FONT_CACHE}`);
  lines.on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    let request;
    try {
      request = JSON.parse(trimmed);
    } catch (error) {
      process.stdout.write(`${JSON.stringify({ id: null, error: { code: 'protocol', message: 'invalid json' } })}\n`);
      return;
    }
    if (request?.type === 'shutdown') {
      process.stdout.write(`${JSON.stringify({ id: request.id ?? null, type: 'bye', uptime_ms: Date.now() - startedAt })}\n`);
      process.exit(0);
    }
    const response = handleRequest(request);
    process.stdout.write(`${JSON.stringify(response)}\n`);
  });
  lines.on('close', () => process.exit(0));
}

main();
