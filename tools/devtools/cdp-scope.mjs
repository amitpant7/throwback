// Evaluates an expression INSIDE the page script's closure (its private functions and state), by
// pausing on a breakpoint in a function that runs often and evaluating on that call frame.
// Usage: CDP_PORT=9333 node cdp-scope.mjs '<expression>' [functionName=onMutations]
const expr = process.argv[2];
const fn = process.argv[3] || 'onMutations';
const port = process.env.CDP_PORT || 9222;
const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = pages.find(p => p.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl.replace('ws://localhost', 'ws://127.0.0.1'));
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
let id = 0;
const pending = new Map();
const listeners = [];
ws.addEventListener('message', e => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  else if (m.method) listeners.forEach(l => l(m));
});
const call = (method, params = {}) => new Promise(r => {
  const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params }));
});
const done = (out, code = 0) => { console.log(out); ws.close(); process.exit(code); };
setTimeout(() => done('timeout', 1), 25000);

const scripts = [];
listeners.push(m => { if (m.method === 'Debugger.scriptParsed') scripts.push(m.params); });
await call('Debugger.enable');
await new Promise(r => setTimeout(r, 400));
let target = null, source = null;
for (const s of scripts.filter(s => !s.url && s.length > 100000).sort((a, b) => b.length - a.length)) {
  const src = (await call('Debugger.getScriptSource', { scriptId: s.scriptId })).result.scriptSource;
  if (src.includes('Throwback page script')) { target = s; source = src; break; }
}
if (!target) done('page script not found', 1);
const line = source.split('\n').findIndex(l => l.startsWith('  function ' + fn + '('));
if (line < 0) done('function not found: ' + fn, 1);
const bp = await call('Debugger.setBreakpoint', { location: { scriptId: target.scriptId, lineNumber: line + 1 } });
const paused = new Promise(r => listeners.push(m => { if (m.method === 'Debugger.paused') r(m.params); }));
const p = await paused;
const res = await call('Debugger.evaluateOnCallFrame', { callFrameId: p.callFrames[0].callFrameId, expression: expr, returnByValue: true });
await call('Debugger.removeBreakpoint', { breakpointId: bp.result.breakpointId });
await call('Debugger.resume');
const r = res.result;
done(JSON.stringify(r.exceptionDetails ? { error: r.exceptionDetails.exception && r.exceptionDetails.exception.description } : r.result.value));
