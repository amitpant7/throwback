// Evaluates an expression in the app's WebView page through the DevTools protocol.
// Usage: CDP_PORT=9333 node cdp-eval.mjs '<expression>'      (see tools/README.md for the port forward)
// Prints the value as JSON. Exits 1 if the page doesn't answer within 15 s.
const expr = process.argv[2];
const port = process.env.CDP_PORT || 9222;
const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const page = pages.find(p => p.type === 'page');
if (!page) { console.error('no page on port ' + port); process.exit(1); }
const ws = new WebSocket(page.webSocketDebuggerUrl.replace('ws://localhost', 'ws://127.0.0.1'));
setTimeout(() => { console.error('no answer'); process.exit(1); }, 15000);
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true, awaitPromise: true } }));
const msg = await new Promise(r => ws.addEventListener('message', e => { const m = JSON.parse(e.data); if (m.id === 1) r(m); }));
const res = msg.result || {};
console.log(JSON.stringify(res.exceptionDetails ? { error: res.exceptionDetails.text } : res.result && res.result.value, null, 1));
ws.close();
process.exit(0);
