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
| `devtools/cdp-scope.mjs '<expr>' [function]` | Evaluates **inside the page script's closure** (its private functions and `state`): pauses once in a function that runs often (default `onMutations`; on a page where nothing changes use `tickDay`, which runs every second) and evaluates on that frame. Useful to drive one part directly, e.g. `updateEndCard()`. Declare no `var` at the top level of the expression (wrap it in a function) |
| `devtools/memwatch.mjs <port> <seconds> <interval>` | Every interval: forces a garbage collection, then prints retained JS heap, DOM nodes, event listeners, main-thread time spent since the last sample, and the page script's own cache sizes |
| `devtools/profile.mjs <seconds>` | Sampling heap and CPU profile while you use the page: the functions that keep memory and the ones that take main-thread time (Throwback's frames show as `(injected)`) |
| `devtools/netwatch.mjs <seconds> [interval]` | Requests per interval by kind (GraphQL query name or file type) plus main-thread time, to spot request loops |
| `devtools/catchloop.mjs <seconds> [pagescript dir] ['<expr>']` | Attaches the debugger, pings the page, and when it stops answering pauses it and prints the JavaScript stack, with Throwback's frames mapped to their part file; optionally evaluates an expression on the top frame |

## Emulator

| Script | What it does |
|---|---|
| `emulator/emu.sh shot <name>` / `texts` / `tap "<text>"` / `back` | Screenshots, on-screen texts, tap by text |
| `emulator/memrun.sh <label> <seconds>` | Restarts Throwback, scrolls the home feed for `<seconds>` and records `memwatch` plus the app's and the WebView renderer's native memory |

`memrun.sh` with the test-build switch `files/debug_noscript` (see `docs/HANDOFF.md`, Testing) gives
the same run without Throwback's page script, as a baseline.

## Icons

`icons/shortcut_mark.py` writes the home-screen shortcut icons' foreground layers
(`res/drawable/ui_shortcut_mark*.xml`) from the logo's mark, `design/logo/logo-mark-transparent.svg`.
Run it from the repository root after changing the logo.

## Page script lint

`tools/pagescript-lint.sh` joins the page script as the build does and runs ESLint on it as ES5
(undefined and unused names, redeclarations, unreachable code). Run it after changing the page
script; it needs Node and, the first time, network access for `npx`.

## Test-build switches

The debug build reads a few switches from its files folder (fast clock, no page script) and accepts
`am start` extras that open settings screens directly. They are listed in `docs/HANDOFF.md`, Testing.
