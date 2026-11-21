# Page script

The JavaScript that Throwback injects into every instagram.com page: the filters, the routing rules,
Throwback's own additions to the page (Explore minutes, the reel player, "Your day" counters, the
support line) and the bridge to the app.

## How it is built

The script is one function, written in parts: one area per file. The build
(`app/build.gradle.kts`, task `join<Variant>PageScript`) joins the parts **in file-name order** into
the asset `inject/throwback.js`. At run time `web/PageScript.kt` replaces the token
`__THROWBACK_CONFIG__` at the end with `{mode, css, rules, clock, …}`, and `web/ScriptInjector.kt`
injects it at document start.

- `00-core.js` opens the function; `99-setup.js` builds `window.__throwback`, closes the function
  and starts everything. All parts share one scope, so a function in one file can call a function
  in any other.
- A `var` with a value is set when its line runs, in file order. Code that runs while the script
  loads (the start at the end of `99-setup.js`) therefore sees every value. Keep it that way: apart
  from the two guards at the top of `00-core.js`, no top-level statements in the other files.
- Numbers leave gaps, so a new area can go between two others without renaming files.

## Language

ES5 style: `var` and `function`, no arrow functions, no classes, no `let`/`const`, no optional
chaining (`?.`) and no nullish coalescing (`??`). On old WebViews the script is injected the
fallback way and must still run there.

## Files

| File | Area |
|---|---|
| `00-core.js` | Header, the function's opening, shared constants and `state` |
| `05-bridge.js` | `send()`: messages to the app (`{t: …}`, see `web/BridgeMessage.kt`) |
| `10-helpers.js` | Text, regex, URL and DOM helpers; the log of caught errors |
| `12-clock.js` | Trusted clock: today's date and time as the app's tamper-resistant clock sees them |
| `15-rules.js` | Compiles `rules.json` for the page; builds the CSS |
| `20-page-state.js` | Which rules apply on this page; the style element; the viewport |
| `25-entry-points.js` | Ways into Throwback's settings: profile icon, settings-page section, Home long-press |
| `26-chat-call.js` | The call button in chats (opens the chat in the Instagram app) |
| `28-instagram-app.js` | Posting and Instagram's own settings through the Instagram app |
| `30-feed-end.js` | The bottom of the feed: "You're all caught up" and the support line |
| `35-routing.js` | Which pages may show (mirrors `web/UrlRouter.kt`); in-page navigation |
| `40-filtering.js` | Hiding by selector and by label; blocks between posts |
| `42-dialogs.js` | Cookie banner and app nags, dismissed |
| `44-nav-bar.js` | Navigation bars: hidden slots collapse |
| `45-navigation-timing.js` | `__throwback.perf()`: how long screens take to open |
| `50-chat-reel-limit.js` | Reels sent in chats: the swipe limit and its poster |
| `55-explore.js` | The Explore grid: a few minutes a day, then "Enough for today" |
| `60-explore-reel-player.js` | Throwback's own reel player for reels from Explore and search |
| `65-your-day.js` | "Your day" counters (time per area, what was removed) |
| `70-covered.js` | Nothing plays while a Throwback screen covers the page |
| `72-warmup.js` | Prefetches Instagram's messages and story code when idle |
| `80-feed-data.js` | Removes ads and suggestions from the feed data before it is drawn |
| `82-stories.js` | Story ads removed; the next stories load ahead |
| `84-feed-lookahead.js` | The next feed posts load ahead |
| `86-approve-posts.js` | Home-feed posts stay hidden until checked |
| `88-discovery.js` | A few tagged suggestions, strictly rationed |
| `90-readiness.js` | Tells the app when the first filtered page is drawn; page colour; touches |
| `92-passes.js` | The mutation observer and the scan passes |
| `99-setup.js` | `configure()`, the `__throwback` API, start |

## Checking a change

```bash
./gradlew :app:joinDebugPageScript
node --check app/build/generated/assets/joinDebugPageScript/inject/throwback.js
```

Then run it: a debug build on the emulator or a phone, with `chrome://inspect` (the page's
`__throwback.perf()`, `feedStats()`, `storyStats()`, `dayStats()` and `errors()` are there). `PageScriptTest`
checks the joined script's config token.
