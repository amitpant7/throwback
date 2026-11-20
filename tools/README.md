# Development tools

Scripts for checking Throwback on an emulator or a phone with USB debugging. They are not part of
the app build. Node 22 or newer (built-in `fetch` and `WebSocket`), `adb`, Python 3 for `emu.sh tap`.

## DevTools access to the page

Debug builds allow remote debugging of the WebView. Forward its socket to a local port first:

```bash
PID=$(adb shell pidof app.throwback | tr -d '\r')
adb forward tcp:9333 localabstract:webview_devtools_remote_$PID
export CDP_PORT=9333
```

`chrome://inspect` in desktop Chrome shows the same page interactively.

| Script | What it does |
|---|---|
| `devtools/cdp-eval.mjs '<expr>'` | Evaluates an expression in the page, e.g. `__throwback.feedStats()` |
| `devtools/cdp-scope.mjs '<expr>' [function]` | Evaluates **inside the page script's closure** (its private functions and `state`): pauses once in a function that runs often (default `onMutations`) and evaluates on that frame. Useful to drive one part directly, e.g. `showEndCard(...)` |
| `devtools/memwatch.mjs <port> <seconds> <interval>` | Every interval: forces a garbage collection, then prints retained JS heap, DOM nodes, event listeners, main-thread time spent since the last sample, and the page script's own cache sizes |

## Emulator

| Script | What it does |
|---|---|
| `emulator/emu.sh shot <name>` / `texts` / `tap "<text>"` / `back` | Screenshots, on-screen texts, tap by text |
| `emulator/memrun.sh <label> <seconds>` | Restarts Throwback, scrolls the home feed for `<seconds>` and records `memwatch` plus the app's and the WebView renderer's native memory |

`memrun.sh` with the test-build switch `files/debug_noscript` (see `docs/HANDOFF.md`, Testing) gives
the same run without Throwback's page script, as a baseline.

## Test-build switches

The debug build reads a few switches from its files folder (fast clock, no page script) and accepts
`am start` extras that open settings screens directly. They are listed in `docs/HANDOFF.md`, Testing.
