// Counts the page's network requests over time, by kind (GraphQL query name, or file type), to spot
// request loops. Usage: CDP_PORT=9333 node netwatch.mjs <seconds> [intervalSec=5]
// Prints one line per interval: request counts and the main thread's script/task time in that interval.
const secs = +process.argv[2] || 60;
const every = +process.argv[3] || 5;
const port = process.env.CDP_PORT || 9222;
const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = pages.find(p => p.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl.replace('ws://localhost', 'ws://127.0.0.1'));
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
let id = 0;
const pending = new Map();
let counts = {};
ws.addEventListener('message', e => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); return; }
  if (m.method !== 'Network.requestWillBeSent') return;
  const req = m.params.request;
  const h = req.headers || {};
  let kind = h['X-FB-Friendly-Name'] || h['x-fb-friendly-name'];
  if (!kind && req.postData) { const q = /fb_api_req_friendly_name=([^&]+)/.exec(req.postData); if (q) kind = q[1]; }
  if (!kind) {
    const u = new URL(req.url);
    kind = u.hostname.includes('fbcdn') || u.hostname.includes('cdninstagram')
      ? (u.pathname.match(/\.(mp4|jpg|webp|heic|js|css)$/) || ['', 'cdn'])[1]
      : u.hostname.replace('www.', '') + u.pathname.replace(/\/\d+\//g, '/N/').slice(0, 40);
  }
  counts[kind] = (counts[kind] || 0) + 1;
});
const call = (method, params = {}, ms = 15000) => new Promise(r => {
  const i = ++id;
  const t = setTimeout(() => { pending.delete(i); r({ timeout: true }); }, ms);
  pending.set(i, m => { clearTimeout(t); r(m); });
  ws.send(JSON.stringify({ id: i, method, params }));
});
await call('Network.enable');
await call('Performance.enable');
const t0 = Date.now();
let prev = null;
while (Date.now() - t0 < secs * 1000) {
  await new Promise(r => setTimeout(r, every * 1000));
  const m = await call('Performance.getMetrics');
  let work = 'no answer';
  if (m.result) {
    const x = Object.fromEntries(m.result.metrics.map(v => [v.name, v.value]));
    if (prev) work = `script ${(x.ScriptDuration - prev.ScriptDuration).toFixed(1)}s task ${(x.TaskDuration - prev.TaskDuration).toFixed(1)}s layout ${(x.LayoutDuration - prev.LayoutDuration).toFixed(1)}s style ${(x.RecalcStyleDuration - prev.RecalcStyleDuration).toFixed(1)}s heap ${(x.JSHeapUsedSize / 1048576).toFixed(0)}MB`;
    prev = x;
  }
  const top = Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 6).map(([k, v]) => `${k}:${v}`).join(' ');
  console.log(`t=${Math.round((Date.now() - t0) / 1000)}s ${work} | ${top || '-'}`);
  counts = {};
}
ws.close();
process.exit(0);
