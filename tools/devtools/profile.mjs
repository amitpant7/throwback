// Profiles the page for a while: where memory is allocated (sampling heap profiler) and where main-thread
// time goes (CPU profiler). Scroll or use the app meanwhile.
// Usage: CDP_PORT=9333 node profile.mjs <seconds> [top=25]
// Prints the top functions by retained allocation and by CPU time, and the page script's own share.
// Frames without a URL show as "(injected)" (the page script, or a built-in when the line is 0); line numbers are lines of the joined script
// (app/build/generated/assets/join<Variant>PageScript/inject/throwback.js).
const secs = +process.argv[2] || 30;
const top = +process.argv[3] || 25;
const port = process.env.CDP_PORT || 9222;
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
const call = (method, params = {}, ms = 60000) => new Promise(r => {
  const i = ++id;
  const t = setTimeout(() => { pending.delete(i); r({ timeout: true }); }, ms);
  pending.set(i, m => { clearTimeout(t); r(m); });
  ws.send(JSON.stringify({ id: i, method, params }));
});
const where = f => `${f.functionName || '(anonymous)'} ${f.url ? f.url.replace(/^.*\//, '').slice(0, 40) : '(injected)'}:${f.lineNumber + 1}`;

await call('HeapProfiler.enable');
await call('Profiler.enable');
await call('Profiler.setSamplingInterval', { interval: 1000 });
await call('HeapProfiler.startSampling', { samplingInterval: 16384 });
await call('Profiler.start');
await new Promise(r => setTimeout(r, secs * 1000));
const cpu = await call('Profiler.stop');
const heap = await call('HeapProfiler.stopSampling');

if (heap.result) {
  const by = new Map();
  (function walk(n) {
    const k = where(n.callFrame);
    by.set(k, (by.get(k) || 0) + n.selfSize);
    (n.children || []).forEach(walk);
  })(heap.result.profile.head);
  const total = [...by.values()].reduce((a, b) => a + b, 0);
  console.log(`\nLive allocations sampled: ${(total / 1048576).toFixed(1)} MB (still reachable at the end)`);
  [...by.entries()].sort((a, b) => b[1] - a[1]).slice(0, top)
    .forEach(([k, v]) => console.log(`${(v / 1048576).toFixed(2).padStart(8)} MB  ${k}`));
} else console.log('heap profile: no answer');

if (cpu.result) {
  const p = cpu.result.profile;
  const dt = (p.endTime - p.startTime) / 1000 / p.samples.length; // ms per sample
  const self = new Map();
  const nodes = new Map(p.nodes.map(n => [n.id, n]));
  const hits = new Map();
  p.samples.forEach(s => hits.set(s, (hits.get(s) || 0) + 1));
  for (const [nid, h] of hits) {
    const k = where(nodes.get(nid).callFrame);
    self.set(k, (self.get(k) || 0) + h * dt);
  }
  const busy = [...self.entries()].filter(([k]) => !/^\((idle|program|garbage collector)\)/.test(k)).reduce((a, [, v]) => a + v, 0);
  // Throwback's page script: frames without a URL but with a line number (built-ins have line 0).
  let own = 0;
  for (const [nid, h] of hits) {
    const f = nodes.get(nid).callFrame;
    if (!f.url && f.lineNumber > 0) own += h * dt;
  }
  console.log(`Throwback page script (own code): ${(own / 1000).toFixed(2)} s of ${(busy / 1000).toFixed(1)} s busy`);
  console.log(`\nCPU: ${(busy / 1000).toFixed(1)} s busy of ${secs} s; GC ${((self.get([...self.keys()].find(k => k.startsWith('(garbage collector)'))) || 0) / 1000).toFixed(1)} s`);
  [...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, top)
    .forEach(([k, v]) => console.log(`${(v / 1000).toFixed(2).padStart(8)} s   ${k}`));
} else console.log('cpu profile: no answer');
ws.close();
process.exit(0);
