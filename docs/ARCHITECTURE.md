# Throwback architecture

A single-module Android app (Kotlin, Jetpack Compose). Instagram itself is shown in an
Android WebView (instagram.com), with Throwback's filters injected as CSS and JavaScript.
Everything else (onboarding, settings, time limit, lock screens) is native Compose.

```
app/src/main/java/app/throwback/
  ThrowbackApp.kt        integration: app-wide singletons
  MainActivity.kt        integration: entry point, deep links
  core/                  integration: contracts, theme, AppRoot wiring, Throwback mark
  web/                   web agent: WebView, injection, filter rules, back handling
  timelimit/             timelimit agent: lock engine, PIN, usage tracking, lock screens
  ui/                    ui agent: onboarding, settings, wizard, shortcut, about, pause
app/src/main/assets/inject/   web agent: throwback.js, throwback.css
filters/rules.json            web agent: remote-updatable filter rules (bundled at build time)
app/src/test/java/app/throwback/<area>/   unit tests per area
```

## Ownership rules (parallel development)

Three agents build in parallel on separate branches. To merge cleanly:

| Area | Owns (may create and edit) | Must not edit |
|---|---|---|
| web | `app/src/main/java/app/throwback/web/**`, `app/src/main/assets/inject/**`, `filters/**`, `app/src/main/res/values/strings_web.xml`, drawables named `web_*`, tests under `app/src/test/java/app/throwback/web/` | everything else |
| timelimit | `app/src/main/java/app/throwback/timelimit/**`, `app/src/main/res/values/strings_timelimit.xml`, drawables named `tl_*`, `app/src/main/res/xml/data_extraction_rules.xml` and `backup_rules.xml`, tests under `app/src/test/java/app/throwback/timelimit/` | everything else |
| ui | `app/src/main/java/app/throwback/ui/**`, `app/src/main/res/values/strings_ui.xml`, drawables named `ui_*`, tests under `app/src/test/java/app/throwback/ui/` | everything else |
| integration | `core/**`, `MainActivity.kt`, `ThrowbackApp.kt`, `AndroidManifest.xml`, all Gradle files, shared `strings.xml`/`themes.xml`/`colors.xml`, `docs/`, `DECISIONS.md`, `README.md` | |

- **Never edit** Gradle files, the manifest, `core/` or the theme. If you need a dependency,
  permission, manifest entry or contract change, write it in your final report and integration adds it.
- **Keep the stub signatures** in your area (`web/InstagramScreen.kt`, `timelimit/TimeLimitApi.kt`,
  `ui/UiApi.kt`). You may move code into more files, but these public functions and objects must keep
  their names, parameters and package.
- **Colours and type** always come from `ThrowbackTheme.colors` and `MaterialTheme.typography`
  (`core/theme/Theme.kt`, the Sage theme). No hard-coded hex colours in feature code, except
  illustrations.
- **Persistence:** each area uses its own DataStore file (`web`, `timelimit`, `ui_prefs`). Never share one.
- **Strings:** user-visible text goes in your own `strings_<area>.xml`.

## Contracts (`core/Contracts.kt`)

- `WebMode`: `FULL` or `MESSAGES_ONLY`.
- `TimeLimitController`: `state: StateFlow<LockState>`, `onForeground(mode)`, `onBackground()`.
  `LockState` is `Disabled`, `Running(remainingMillis)` or `Locked`.
- `UiPreferences`: `onboardingDone`, `mindfulPause`, `setOnboardingDone()`.

Composable entry points used by `core/AppRoot.kt`:

| Function | Area | Purpose |
|---|---|---|
| `web.InstagramScreen(mode, onLocationChanged, onExitRequest, modifier, startUrl)` | web | The Instagram WebView |
| `timelimit.TimeLimitModule.create(context)` | timelimit | App-wide `TimeLimitController` |
| `timelimit.TimeLimitOverlay(controller, onOpenMessagesOnly, onCloseApp, modifier)` | timelimit | Lock screens over Instagram |
| `timelimit.TimeRemainingChip(controller, modifier)` | timelimit | "18 min left" chip |
| `timelimit.TimeLimitSettingsScreen(controller, onClose)` | timelimit | Set up or manage the limit |
| `ui.UiModule.create(context)` | ui | `UiPreferences` |
| `ui.OnboardingFlow(onFinished, onOpenMessagesSetup)` | ui | First launch and "Instagram app found" |
| `ui.SettingsRoot(onClose, timeLimitScreen, startAt)` | ui | Settings and everything under it |
| `ui.MindfulPauseScreen(onContinue, onNotNow)` | ui | Optional pause before Instagram |

## How AppRoot puts it together

1. DataStore loading: blank (the splash screen covers it).
2. Onboarding not done: `OnboardingFlow`.
3. Otherwise `MainScreen`: optional `MindfulPauseScreen` once per launch, then
   `InstagramScreen` with, on top: the Throwback mark (opens Settings) and `TimeRemainingChip` at
   top-start on the home feed only (the web layer hides Instagram's wordmark there), and
   `TimeLimitOverlay` when the state is `Locked` and the mode is `FULL`.
4. "Open messages only" on the lock screen switches the WebView to `MESSAGES_ONLY`. When the state
   leaves `Locked`, AppRoot switches back to `FULL`.
5. Settings opens as a full-screen layer over everything; the WebView stays alive underneath.

## Build

JDK 17 and the Android SDK live in the home folder on the dev workstation:

```bash
export JAVA_HOME=$(ls -d ~/.local/opt/jdk-17* | head -1)
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug testDebugUnitTest lintDebug
```

`local.properties` (git-ignored) holds `sdk.dir`. The debug APK lands in
`app/build/outputs/apk/debug/app-debug.apk`.
