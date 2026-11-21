# Throwback architecture

A single-module Android app (Kotlin, Jetpack Compose, minSdk 26, targetSdk 35). Instagram itself is
instagram.com in one long-lived Android WebView, with Throwback's filters injected as JavaScript and
CSS. Everything around it is native Compose: onboarding, settings, the daily time limit and its lock
screens, the mindful pause, "Your day", Support/About. The optional Instagram app connection
(Settings → Connect → Instagram; the code says relay/guard) adds a notification listener and a
foreground service.

The repository layout is in the [README](../README.md#repository-layout); product decisions and their
reasons are in [DECISIONS.md](../DECISIONS.md).

## Packages (`app/src/main/java/app/throwback/`)

| Package | What it holds |
|---|---|
| `ThrowbackApp`, `MainActivity` | Process start (warm-up of rules and the time limit), entry point, deep links and intents |
| `core` | `AppRoot` (what is on screen and how the areas connect), `Contracts`, `RateAsk`, `SupportAsk`, `TodayRoute` ("Your day" data) |
| `core/theme` | The Sage theme: `ThrowbackTheme.colors`, typography, system bar icons |
| `core/ui` | The shared UI kit used by `ui` and `timelimit/ui`: top bar, scaffold, buttons, icons, cards, rows, progress bar, duration texts, touch blocker |
| `web` | The WebView (`InstagramScreen`, `InstagramWebController`), injection (`PageScript`, `ScriptInjector`), routing (`UrlRouter`, `UrlParts`), the bridge (`BridgeMessage`), page features and clock (`PageFeatures`, `PageClock`), renderer crashes |
| `web/rules` | Filter rules: model and parsing (`FilterRules`), validation (`RulesValidator`, `RulePatterns`, `NotificationRules`), the daily fetch (`RulesFetcher`, `RulesRepository`) |
| `timelimit/engine` | The time-limit engine, pure Kotlin: state machine, trusted clock, PIN hashing |
| `timelimit/data`, `timelimit` | Storage (DataStore `timelimit`), the Android clock, `DefaultTimeLimitController` |
| `timelimit/ui` | Setup, manage, PIN entry and prompt, lock screens, forgot PIN, the time-left chip |
| `ui` | Settings and its screens (`settings`, `messages` = Connect › Instagram, `today`, `about`, `shortcut` = Customize icon), onboarding, pause, reel poster; `UiPrefs` (DataStore `ui_prefs`) |
| `relay` | Notification relay and the passes into the Instagram app (`CallInApp`, the pass ledger) |
| `guard` | The guard service and its pure policy (`GuardPolicy`), the cover overlay, restart after boot |

The page script is in `app/src/main/pagescript/`, one file per area; its
[README](../app/src/main/pagescript/README.md) lists the files and the rules for editing them.

## How AppRoot puts it together

1. DataStore loading: the splash screen stays.
2. Onboarding not done: `OnboardingFlow`; "Log in to Instagram" finishes it.
3. Otherwise `MainScreen`: `InstagramScreen` starts loading at once. On top of it, bottom to top: the
   time-left chip (home feed, full mode), `InstagramAppPrompt` (once after the first login),
   `SettingsHint` (once), `ReelPoster`, `TimeLimitOverlay` (locked, full mode), `MindfulPauseScreen`
   (once per launch if on; not for links or when the app opens straight into Settings).
4. Settings (`SettingsRoot`) is a full-screen layer over everything; the WebView stays alive below.
   Every layer under Settings takes `backEnabled = !settingsOpen`: Android gives Back to the handler
   added last, and a layer can appear while Settings is open (the lock engaging).
5. Messages-only mode: "Open messages only" on the lock screen, or a relayed message tapped while
   locked, switches the WebView to `MESSAGES_ONLY`; leaving `Locked` switches back.
6. Only time on Instagram counts: the time limit is told foreground/background from the activity's
   resumed state, and not while Settings or the pause covers the page.

## Web layer

**Injection.** The build joins the page script parts into the asset `inject/throwback.js`
(`join<Variant>PageScript`) and copies `filters/rules.json` into the assets (`bundle<Variant>FilterRules`,
which fails the build on invalid JSON). `PageScript.build` puts the config into the script:
`{mode, css, rules, clock, createInApp, storyInApp, supportAsk}`; `ScriptInjector` registers it as a
document-start script for Instagram's origins (or injects it on page start on old WebViews). A config
with only some keys updates the live page through `window.__throwback.configure(...)`.

**Routing.** `UrlRouter` (pure, tested) decides for every navigation: load, redirect (blocked pages:
Reels, a profile's Tagged tab…), open outside (only on a user's tap), or ignore. URLs
with a backslash or control characters are refused: Chromium reads their host differently from any
parser. Deep links are loaded as URLs rebuilt from their checked parts. The page script mirrors the
path rules for in-page navigation.

**Rules.** `filters/rules.json` is bundled and fetched once a day from this repository
(`RULES_URL`, HTTPS, 512 KB cap, ETag). A fetched file is used only if it validates and is newer.
Validation pins the hosts to Instagram's, and accepts only regex patterns that behave the same in
Java and JavaScript and can't backtrack catastrophically (`RulePatterns`).

**Page → app** (`BridgeMessage`, JSON `{"t": …}` through `addWebMessageListener`, Instagram origins
and the main frame only; old WebViews use a JavaScript interface that refuses the pass-granting types):

| `t` | App side |
|---|---|
| `ready` | First filtered page drawn: reveal the WebView, send the clock and features |
| `loc` | Location changed (also re-sends "covered" on a new document) |
| `blocked` | Show the short notice for that kind |
| `touch`, `bg` | Pull-to-refresh at the top; page background for the system bars |
| `settings` | Open Throwback's settings (profile icon, settings-page section, Home long-press) |
| `callInApp` | The chat's call button: open the chat in the Instagram app with a call pass |
| `createInApp`, `storyWeb` | "+" / "Your story" with the Instagram app connected (or why the story route waits) |
| `settingsInApp` | "Open settings in the Instagram app" |
| `reelLimit` | Show the reel poster |
| `caughtUp` | "You're all caught up" was seen (rating ask, once) |
| `supportAskShown`, `supportThanks` | The support line was seen / "Say thanks" tapped (opens Support) |
| `share` | Share a reel link from the Explore reel player |

**App → page** (`window.__throwback`): `configure(cfg)`, `setCovered(bool)` (pauses media, stops the
page's clocks), and for "Your day" and DevTools `dayStats()`, `perf()`, `feedStats()`, `storyStats()`,
`clockInfo()`.

**Features and clock.** `PageFeatures` is one `StateFlow` (Instagram app connected, story route
ready, support ask due); the web host pushes changes while the activity is started. `PageClock` gives
the page the time limit's trusted clock (`offset`, `zoneMin`, `day`, `nextAt`, and `at`, the phone
time when read), only once the time limit's state has loaded, again whenever the app comes to the
front and when the phone's time or zone changes. The page anchors it on a monotonic clock and
ignores clocks older than 10 minutes.

**Renderer crashes** (`RendererCrashes`): the WebView is recreated on the same page; a second crash
within 60 s restarts at home; a third shows the error screen.

## Time limit

`timelimit/engine` is pure Kotlin and fully unit-tested: the limit, the 5-minute wait, PIN extensions
(twice a day), locked until the day ends, changes that take effect the next day, the 3-day forgot-PIN
reset, and the trusted clock. Trusted time comes from the phone's since-boot clock plus the last
trusted time, preferring network time: Android's own (API 33+), else Instagram's server time from the
Date of its responses (the page sends it, `serverTime`; `ServerTimeHint`); a new day needs at least
23 hours of real time; the time zone counts from the next midnight. `DefaultTimeLimitController`
wires it to DataStore (`timelimit`, included in Auto Backup) and a ticker while Instagram is in front.

## Instagram app connection (`relay`, `guard`)

Nothing runs until the setup is finished. `relay.MessagesFeature` starts or stops the guard to match
the setup and the permissions.

- **Relay** (`RelayListenerService`): only Instagram's packages. Calls are never touched; messages and
  missed calls are replaced by Throwback's own notification (opens the chat, messages-only when
  locked); likes, comments, follows, mentions and live follow the user's switches; the rest is
  removed. `NotificationClassifier` uses Android's call signals first, then the rules' channel table.
- **Guard** (`GuardService`, special-use foreground service): watches usage events while the screen
  is on and covers Instagram (`GuardOverlay`) unless a call or a pass allows it. Split screen,
  pop-up windows and picture-in-picture count as on screen (resumed or paused until stopped); while
  Instagram stays on screen the cover stays, stepping aside briefly every 8 s so its window can be
  closed. The guard writes a heartbeat; when it was stopped, the Instagram app's time since then is
  added to the day's usage when Throwback next runs. Call audio counts as Instagram's only after an
  Instagram call notification or when it starts while Instagram is in front.
- **Passes** (one ledger on the trusted clock, `relay/CallInApp`): call button (30 s, 3 taps an hour),
  "Open Instagram for 5 minutes" (once a day, not while locked), posting (Instagram's create screens,
  20 minutes, 10 a day, not while locked), Instagram's settings (its settings screens, 10 minutes,
  5 a day, not while locked), story (5 minutes of Instagram time without the camera, a 30-minute
  wait, 3 a day, not while locked; a pass ended by posting doesn't count; one left with at most 30 s
  outside the camera doesn't count either but starts a 10-minute wait). Only Instagram's own camera
  stops the clock: a camera that went into use while Instagram was in front. Screen patterns that fail
  or are missing allow nothing beyond a 3-second grace.
- Pure rules (`GuardPolicy`, `RelayRouting`, the classifier) are unit-tested; test builds can post
  fake notifications and watch another app (`relay/RelayDebug`, `app/src/debug/`).

## Storage

| Where | What |
|---|---|
| DataStore `web` | Rules cache, ETag, last check |
| DataStore `timelimit` | Time-limit state (Auto Backup) |
| DataStore `ui_prefs` | Onboarding, pause, setup done, notification switches, hints |
| SharedPreferences `guard_passes`, `relay_stats`, `rate_ask`, `support_ask`, `pause_lines` | Pass ledger, relay day counts, rating ask, support ask, the pause's line rotation |
| SharedPreferences `supporter` | Whether the user has tipped (unlocks Customize icon; Auto Backup) |
| instagram.com `localStorage` (the page) | `__tb_day` (Your day), `__tb_explore`, `__tb_discover`, `__tb_reels`, `__tb_totals` (support line), `__tb_lastday` (the page's day), `__tb_code_assets` (warm-up), `__tb_redirects` (loop guard) |

## Build and tests

```bash
export JAVA_HOME=$(ls -d ~/.local/opt/jdk-17* | head -1)   # or Android Studio's JDK 17
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug testDebugUnitTest lintDebug
tools/pagescript-lint.sh
```

Unit tests (`app/src/test`) cover the pure parts: the time-limit engine and controller, rules
parsing and validation, routing, the bridge, the guard policy and passes, the relay, formatters. The
WebView and the page script are checked on an emulator or a phone with the scripts in
[tools/](../tools/README.md) (DevTools access, memory and CPU profiles, a stuck-page catcher).
