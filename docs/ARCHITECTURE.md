# Throwback architecture

A single-module Android app (Kotlin, Jetpack Compose). Instagram itself is shown in an
Android WebView (instagram.com), with Throwback's filters injected as CSS and JavaScript.
Everything else (onboarding, settings, time limit, lock screens, the reel poster) is native Compose.
The optional Instagram app feature (Settings → Connect → Instagram; the code still says messages/relay) adds two background parts: a notification listener (the
relay) and a foreground service (the guard).

```
app/src/main/java/app/throwback/
  ThrowbackApp.kt        integration: app-wide singletons, starts Messages & calls if set up
  MainActivity.kt        integration: entry point, deep links, shortcut and notification intents
  core/                  integration: contracts, theme, AppRoot wiring (ThrowbackMark.kt is unused)
  web/                   web: WebView, injection, filter rules, back handling, page bridge
  timelimit/             timelimit: lock engine, PIN, usage tracking, lock screens
  ui/                    ui: onboarding, settings, wizard, shortcut, Support/About, pause, hint, reel poster
  relay/                 integration (M5): notification relay, call state, hand-off to the Instagram app
  guard/                 integration (M5): keeps the user out of the real Instagram app outside calls
app/src/main/assets/inject/   web: throwback.js (page script), throwback.css
app/src/debug/                test builds only: fake-notification sender for the emulator
filters/rules.json            web: remote-updatable filter rules (bundled at build time)
app/src/test/java/app/throwback/<area>/   unit tests per area
```

## Ownership rules (parallel development)

web, timelimit and ui were built in parallel by separate agents on separate branches (M1–M4), then
merged and wired by integration. These rules kept the merges clean; keep to them whenever areas are
worked on in parallel again.

| Area | Owns (may create and edit) | Must not edit |
|---|---|---|
| web | `app/src/main/java/app/throwback/web/**`, `app/src/main/assets/inject/**`, `filters/**`, `app/src/main/res/values/strings_web.xml`, drawables named `web_*`, tests under `app/src/test/java/app/throwback/web/` | everything else |
| timelimit | `app/src/main/java/app/throwback/timelimit/**`, `app/src/main/res/values/strings_timelimit.xml`, drawables named `tl_*`, `app/src/main/res/xml/data_extraction_rules.xml` and `backup_rules.xml`, tests under `app/src/test/java/app/throwback/timelimit/` | everything else |
| ui | `app/src/main/java/app/throwback/ui/**`, `app/src/main/res/values/strings_ui.xml`, drawables named `ui_*`, tests under `app/src/test/java/app/throwback/ui/` | everything else |
| integration | `core/**`, `relay/**`, `guard/**`, `MainActivity.kt`, `ThrowbackApp.kt`, `AndroidManifest.xml`, `app/src/debug/**`, all Gradle files, shared `strings.xml`/`themes.xml`/`colors.xml`, `docs/`, `DECISIONS.md`, `README.md` | |

- **Never edit** Gradle files, the manifest, `core/` or the theme from a feature area. If you need a
  dependency, permission, manifest entry or contract change, write it in your final report and
  integration adds it.
- **Keep the public signatures** in your area (`web/InstagramScreen.kt`, `timelimit/TimeLimitApi.kt`,
  `ui/UiApi.kt`). You may move code into more files, but these public functions and objects must keep
  their names, parameters and package. New parameters get defaults.
- **Colours and type** always come from `ThrowbackTheme.colors` and `MaterialTheme.typography`
  (`core/theme/Theme.kt`, the Sage theme). No hard-coded hex colours in feature code, except
  illustrations.
- **Persistence:** each area uses its own DataStore file (`web`, `timelimit`, `ui_prefs`). Never share
  one. The relay reads two ui settings through `ui.UiPrefs` (`messagesSetupDone`,
  `extraNotifications`) and keeps its own day counts in the SharedPreferences file `relay_stats`.
  The page script keeps small state in the page's `localStorage` (`__tb_discover`, `__tb_explore`,
  `__tb_reels`, `__tb_dm_assets`).
- **Strings:** user-visible text goes in your own `strings_<area>.xml`.

## Contracts (`core/Contracts.kt`)

- `WebMode`: `FULL` or `MESSAGES_ONLY`.
- `TimeLimitController`: `state: StateFlow<LockState>`, `onForeground(mode)`, `onBackground()`.
  `LockState` is `Disabled`, `Running(remainingMillis)` or `Locked`.
- `UiPreferences`: `onboardingDone`, `mindfulPause`, `setOnboardingDone()`.

Entry points used by `core/AppRoot.kt`:

| Function | Area | Purpose |
|---|---|---|
| `web.InstagramScreen(mode, onLocationChanged, onExitRequest, modifier, startUrl, onOpenSettings, onCallInApp, backEnabled, covered, onReelLimit)` | web | The Instagram WebView (parameters below) |
| `web.InstagramSession.loggedIn` | web | Logged in to Instagram (`sessionid` cookie), refreshed on every page change |
| `timelimit.TimeLimitModule.create(context)` | timelimit | App-wide `TimeLimitController` |
| `timelimit.TimeLimitOverlay(controller, onOpenMessagesOnly, onCloseApp, modifier)` | timelimit | Lock screens over Instagram |
| `timelimit.TimeRemainingChip(controller, modifier)` | timelimit | "18 min left" chip |
| `timelimit.TimeLimitSettingsScreen(controller, onClose)` | timelimit | Set up or manage the limit |
| `ui.UiModule.create(context)` | ui | `UiPreferences` |
| `ui.OnboardingFlow(onFinished)` | ui | First launch ("Log in to Instagram" finishes it) |
| `ui.shouldOfferInstagramPrompt(context)` / `ui.InstagramAppPrompt(onOpenMessagesSetup, onDone)` | ui | "Instagram app found", shown once after the first login |
| `ui.SettingsRoot(onClose, timeLimitScreen, startAt)` | ui | Settings and everything under it; `startAt` is `SettingsStart.Home` or `MessagesAndCalls` |
| `ui.MindfulPauseScreen(onContinue, onNotNow)` | ui | Optional pause, drawn over Instagram while it loads |
| `ui.shouldShowSettingsHint(context)` / `ui.markSettingsHintShown(context)` / `ui.SettingsHint(onDone, modifier)` | ui | One-time card: long-press Home opens Throwback's settings (DECISIONS 33b) |
| `ui.ReelPoster(onBack)` | ui | Full-screen poster after the reel allowance (DECISIONS 13f) |

`InstagramScreen` parameters beyond the basics:

| Parameter | Meaning |
|---|---|
| `onOpenSettings` | The page asked for Throwback's settings (profile icon, settings-page section, Home long-press) |
| `onCallInApp(thread, user)` | The chat's call button was tapped. AppRoot calls `relay.CallInApp.open`; if no Instagram app is installed it shows a toast and opens the Messages & calls setup |
| `backEnabled` | False while a Throwback screen covers the page (Settings, pause, reel poster): system back must not reach the page |
| `covered` | True while Settings, the pause, the lock or the reel poster cover the page. Forwarded to the page as `__throwback.setCovered()`, which pauses all media and stops the feed and Explore clocks |
| `onReelLimit` | The page used up the reel allowance and closed Instagram's reel viewer; AppRoot shows `ReelPoster` |

Screens inside `SettingsRoot` (`ui/settings/SettingsNav.kt`): Home, TimeLimit, Messages (the wizard
until setup is finished, then `MessagesSettingsScreen`), MessagesWizard, Shortcut, Support
(`SupportScreen`: the tips) and About (`AboutScreen`: links, filter rules date, disclaimer).
`MessagesSettingsScreen` holds the status card, the always-on rows (messages, calls), the per-type
notification switches, the optional `QuietTipCard` ("For a quieter phone": Instagram's two
notification groups, how many notifications of the "Instagram" group the relay saw in the last 7
days, and a button to Android's notification settings for Instagram), "Open Instagram for 5 minutes"
and "Turn off".

## Page bridge (`web/BridgeMessage.kt`, `throwback.js`)

The page script sends JSON strings `{"t": type, ...}` through `WebViewCompat.addWebMessageListener`
(object `throwbackNative`, Instagram origins and main frame only), or an `@JavascriptInterface`
fallback on old WebViews. Unknown types are ignored.

| `t` | Fields | App side |
|---|---|---|
| `ready` | | First filtered page drawn: remove the loading cover (and re-send `covered`) |
| `loc` | `url` | Location changed (load, pushState, back/forward); feeds `onLocationChanged` |
| `blocked` | `kind` (`reels`, `suggested`, `messagesOnly`, anything else) | Show the matching short notice |
| `touch` | `top` | A touch started; whether the page is at the top (pull to refresh) |
| `bg` | `c` (CSS colour) | Page background, for the system bar areas (opaque colours only) |
| `settings` | | Open Throwback's settings |
| `reelLimit` | | Show the reel poster |
| `callInApp` | `thread` (5–40 digits), `user` (username) | Open this chat in the Instagram app to call |

The app talks to the page through `window.__throwback` (not enumerable, not writable):

| Member | Used by | Purpose |
|---|---|---|
| `configure(cfg)` | app | New mode, rules or CSS without a reload (also used on a second injection) |
| `setCovered(bool)` | app | A Throwback screen covers the page: pause media, stop counting feed and Explore time |
| `version` | | `1` |
| `perf()` | DevTools | Tap → settled timings of page loads, stories and DMs (nothing is sent anywhere) |
| `reelEvents()` | DevTools | Last 100 reel-limit events with timestamps (temporary diagnostics) |
| `storyStats()` | DevTools | Story look-ahead counters (learned, prefetched, served from memory, memory KB) |
| `feedStats()` | DevTools | Feed look-ahead counters and the batch-size tweak (raised, refused, off) |

## Instagram app, formerly Messages & calls (`relay/`, `guard/`)

Nothing runs until the setup wizard is finished (`messages_setup_done` in `ui_prefs`).
`relay.MessagesFeature.install()` (from `ThrowbackApp.onCreate`) starts or stops the guard to match
the setup and the permissions, at app start and whenever Throwback comes to the front.

- **Relay** (`RelayListenerService`, a `NotificationListenerService` bound by Android while
  notification access is granted). Looks only at `com.instagram.android` and `com.instagram.lite`.
  `NotificationClassifier` sorts a notification: Android's own call signals first (category `call`,
  CallStyle, full-screen intent; no rule can override them), then the `notifications.classify` table
  in `rules.json` (Instagram's channel ids), then built-in defaults (an ongoing notification is a
  call). `RelayRouting.decide` (pure, unit-tested): calls are never touched; messages and missed calls
  are replaced by Throwback's own notification (`RelayNotifier`, action `ACTION_OPEN_MESSAGES`,
  opening the chat or the inbox, messages-only mode when locked); likes, comments, follows, mentions
  and live are relayed to the activity page only if switched on and not locked; everything else is
  removed. If Throwback can't post notifications, Instagram's own is kept. `RelayStats` counts per
  day (counts only, kept 14 days) the notifications from Instagram's "Instagram" group, for the
  quieter-phone tip.
- **Call state** (`CallState.tracker`, a `StateFlow<CallSnapshot>`): the relay reports call
  notifications showing and going away; the guard reads it. Elapsed-realtime times only.
- **Guard** (`GuardService`, foreground service of the special-use type; `BootReceiver` restarts it
  after a reboot or an app update). While the screen is on it checks usage events about twice a
  second. If an Instagram app is in front, `GuardPolicy.decide` (pure, unit-tested) covers it with
  `GuardOverlay` ("Use Throwback instead") and brings Throwback to the front, except when: the relay
  isn't connected (calls can't be seen, so it never acts), a call notification is showing or went
  away less than `callGraceSeconds` ago, the phone's audio was in call mode within that time (an
  answered Instagram call has no notification), or a `GuardPass` is running.
- **Passes into the real app** (`relay/CallInApp.kt`): `open(thread, user)` for the chat's call
  button (90-second pass) opens `https://ig.me/m/<username>` in the Instagram app, which works
  whichever account the app is logged in to; without a username, the chat-id link; else the app
  itself. `visit()` is "Open Instagram for 5 minutes" (5-minute pass). These are the only passes
  (`GuardPass.SETUP_PASS_MS`, a 10-minute default from the old wizard step, is not used).
- **Test builds:** `relay/RelayDebug.kt` and `app/src/debug/.../DebugRelayReceiver.kt` post fake
  "Instagram" notifications and can make the guard watch another app, for the emulator (see
  HANDOFF, Testing).

## How AppRoot puts it together

1. DataStore loading: blank (the splash screen covers it).
2. Onboarding not done: `OnboardingFlow`. Its "Log in to Instagram" finishes onboarding; the web layer
   then opens Instagram's login form directly whenever the user is logged out.
3. Otherwise `MainScreen`: `InstagramScreen` starts loading at once. On top of it, bottom to top:
   - `TimeRemainingChip` at the top centre, on the logged-in home feed only, in `FULL` mode.
   - `MessagesOnlyBar` at the bottom in `MESSAGES_ONLY` mode ("Ask for more time" returns to the lock).
   - `InstagramAppPrompt`, once after the first login, if the Instagram app is installed.
   - `SettingsHint`, once, 1.5 s after the logged-in home feed first shows (opening settings any
     other way also marks it done).
   - `ReelPoster` when the page reports `reelLimit`; Back removes it.
   - `TimeLimitOverlay` when the state is `Locked` and the mode is `FULL`.
   - `MindfulPauseScreen` once per launch if switched on (not when opened from a link). Instagram
     keeps loading underneath, so the feed is ready when the pause ends.

   There is no Throwback button or mark on the feed, and Instagram's own header is left as it is.
4. "Open messages only" on the lock screen switches the WebView to `MESSAGES_ONLY`; so does a tapped
   relayed message notification while the limit is locked. When the state leaves `Locked`, AppRoot
   switches back to `FULL`.
5. Settings opens as a full-screen layer over everything; the WebView stays alive underneath. It opens
   from the page (profile icon, the section on Instagram's settings page, long-press on the Home tab:
   the page script sends `{"t":"settings"}`), from the app-icon shortcut and the guard's notification
   (`MainActivity.ACTION_OPEN_SETTINGS`), or straight at Messages & calls from `InstagramAppPrompt`
   or a call attempt without the Instagram app.
6. Only time on Instagram counts: the time limit gets `onForeground` only while the activity is
   resumed and neither Settings nor the pause covers the page.

## Build

JDK 17 and the Android SDK live in the home folder on the dev workstation:

```bash
export JAVA_HOME=$(ls -d ~/.local/opt/jdk-17* | head -1)
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug testDebugUnitTest lintDebug
```

`local.properties` (git-ignored) holds `sdk.dir`. The debug APK lands in
`app/build/outputs/apk/debug/app-debug.apk`. `filters/rules.json` is copied into the APK's assets on
every build; a rules file that isn't valid JSON or has merge-conflict markers fails the build.
