// Samples the WebView page's memory and work over time through DevTools (CDP).
// Usage: node memwatch.mjs <port> <seconds> <intervalSec>
// Each sample: forced GC first (retained heap, not garbage), then Performance metrics and a few
// page-side counts. One JSON line per sample on stdout.
const [port, dur, every] = [process.argv[2], +process.argv[3], +process.argv[4]];
const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = pages.find(p => p.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl.replace('ws://localhost', 'ws://127.0.0.1'));
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
let id = 0;
const pending = new Map();
ws.addEventListener('message', e => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
});
const call = (method, params = {}, ms = 20000) => new Promise(r => {
  const i = ++id;
  const t = setTimeout(() => { pending.delete(i); r({ timeout: true }); }, ms);
  pending.set(i, m => { clearTimeout(t); r(m); });
  ws.send(JSON.stringify({ id: i, method, params }));
});
await call('Performance.enable');
await call('HeapProfiler.enable');
const PAGE = `JSON.stringify((function () {
  var t = window.__throwback, f = t && t.feedStats ? t.feedStats() : null, s = t && t.storyStats ? t.storyStats() : null;
  return { posts: document.querySelectorAll('main article').length, y: Math.round(scrollY),
    feedKB: f && f.inMemoryKB, learned: f && f.postsLearned, storyKB: s && s.inMemoryKB,
    ls: (function () { var n = 0; try { for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); n += k.length + (localStorage.getItem(k) || '').length; } } catch (e) {} return n; })() };
})())`;
const t0 = Date.now();
let prev = null;
while (Date.now() - t0 < dur * 1000) {
  await call('HeapProfiler.collectGarbage', {}, 30000);
  const m = await call('Performance.getMetrics');
  if (m.timeout || !m.result) { console.log(JSON.stringify({ t: Math.round((Date.now() - t0) / 1000), error: 'no answer' })); continue; }
  const x = Object.fromEntries(m.result.metrics.map(v => [v.name, v.value]));
  const ev = await call('Runtime.evaluate', { expression: PAGE, returnByValue: true });
  const row = {
    t: Math.round((Date.now() - t0) / 1000),
    heapMB: +(x.JSHeapUsedSize / 1048576).toFixed(1),
    nodes: x.Nodes, listeners: x.JSEventListeners, docs: x.Documents, frames: x.Frames,
    // per-interval work (seconds of main-thread time)
    scriptS: prev ? +(x.ScriptDuration - prev.ScriptDuration).toFixed(2) : 0,
    taskS: prev ? +(x.TaskDuration - prev.TaskDuration).toFixed(2) : 0,
    layoutS: prev ? +(x.LayoutDuration - prev.LayoutDuration).toFixed(2) : 0,
    styleS: prev ? +(x.RecalcStyleDuration - prev.RecalcStyleDuration).toFixed(2) : 0,
    page: ev.result && ev.result.result ? JSON.parse(ev.result.result.value) : null,
  };
  prev = x;
  console.log(JSON.stringify(row));
  await new Promise(r => setTimeout(r, every * 1000));
}
ws.close();
process.exit(0);
