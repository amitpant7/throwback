// Catches a page that stops responding (a long or endless JavaScript task): attaches the debugger
// first, then pings the page every 2 s; when a ping goes unanswered for 4 s, pauses the page and prints
// the JavaScript call stack, with page-script frames mapped to their part file (src/main/pagescript).
// Usage: CDP_PORT=9333 node catchloop.mjs <seconds> [path/to/app/src/main/pagescript] ['<expr on the top frame>']
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
const secs = +process.argv[2] || 120;
const partsDir = process.argv[3];
const frameExpr = process.argv[4];
const port = process.env.CDP_PORT || 9222;
// Line ranges of the parts in the joined script (0-based), to name the file of a frame.
const parts = [];
if (partsDir) {
  let at = 0;
  for (const f of readdirSync(partsDir).filter(f => f.endsWith('.js')).sort()) {
    const n = readFileSync(join(partsDir, f), 'utf8').split('\n').length - 1;
    parts.push({ f, from: at, to: at + n });
    at += n;
  }
}
const partOf = line => { const p = parts.find(p => line >= p.from && line < p.to); return p ? `${p.f}:${line - p.from + 1}` : `line ${line + 1}`; };
const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = pages.find(p => p.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl.replace('ws://localhost', 'ws://127.0.0.1'));
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
let id = 0;
const pending = new Map();
const scripts = new Map();
let onPaused = null;
ws.addEventListener('message', e => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); return; }
  if (m.method === 'Debugger.scriptParsed') scripts.set(m.params.scriptId, m.params);
  if (m.method === 'Debugger.paused' && onPaused) onPaused(m.params);
});
const call = (method, params = {}, ms = 4000) => new Promise(r => {
  const i = ++id;
  const t = setTimeout(() => { pending.delete(i); r({ timeout: true }); }, ms);
  pending.set(i, m => { clearTimeout(t); r(m); });
  ws.send(JSON.stringify({ id: i, method, params }));
});
await call('Debugger.enable', {}, 15000);
console.log('debugger attached; watching for', secs, 's');
const t0 = Date.now();
while (Date.now() - t0 < secs * 1000) {
  const ping = await call('Runtime.evaluate', { expression: '1' }, 4000);
  if (!ping.timeout) { await new Promise(r => setTimeout(r, 2000)); continue; }
  console.log(`t=${Math.round((Date.now() - t0) / 1000)}s page not answering: pausing`);
  const paused = new Promise(r => { onPaused = r; });
  ws.send(JSON.stringify({ id: ++id, method: 'Debugger.pause', params: {} }));
  const p = await Promise.race([paused, new Promise(r => setTimeout(() => r(null), 20000))]);
  if (!p) { console.log('could not pause (not running JavaScript?)'); process.exit(2); }
  for (const f of p.callFrames.slice(0, 25)) {
    const s = scripts.get(f.location.scriptId) || {};
    const own = !s.url;
    const where = own ? partOf(f.location.lineNumber) : `${(s.url || '').replace(/^.*\//, '').slice(0, 40)}:${f.location.lineNumber + 1}`;
    console.log(`  ${(f.functionName || '(anonymous)').padEnd(28)} ${own ? '[throwback] ' : ''}${where}`);
  }
  if (frameExpr) {
    const v = await call('Debugger.evaluateOnCallFrame', { callFrameId: p.callFrames[0].callFrameId, expression: frameExpr, returnByValue: true }, 10000);
    console.log('top frame:', JSON.stringify(v.result && (v.result.exceptionDetails ? v.result.exceptionDetails.text : v.result.result.value)));
  }
  process.exit(0);
}
console.log('page answered throughout');
process.exit(0);
