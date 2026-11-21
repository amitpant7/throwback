# Throwback

Instagram, the way it used to be: just the people you follow, with their stories. No Reels, no Explore
grid, no suggested posts, no ads. When you've seen it all, you're done.

Free. Source available for personal use (see Licence). No accounts, no tracking. Not affiliated with Instagram or Meta.

> Work in progress. The first test build is coming soon.

## Features

- Your home feed, stories and messages, with the distractions removed (always on, no toggles)
- A little discovery: now and then a suggested post, tagged "Discover", strictly rationed
- The Explore grid for 5 minutes a day, then "Enough for today"
- Reels sent to you in chats play, but swiping on to more reels is stopped after a few swipes
- Optional daily time limit with a PIN, two 5-minute extensions a day, and a messages-only mode when locked
- Optional connected Instagram app: calls, message alerts, and posting with Instagram's own editor; the app stays installed
  but out of reach. Calls ring and are answered there (a chat's call button starts one there);
  messages open in Throwback; other Instagram notifications are removed unless you switch them on
  (security and login alerts always come through)
- A mindful pause before opening (on by default; can be turned off)
- Make It Yours: your own home-screen name and icon for Throwback (unlocked by any tip)

## Opening Throwback's settings

There's no Throwback button on the feed, so it looks exactly like Instagram. Open Throwback's settings
(Connect → Instagram, Your day, time limit, mindful pause, Make It Yours, Support, About) any of these ways:

1. **Your profile:** the Throwback icon at the top right (where the Threads icon was).
2. **Instagram's settings:** profile → gear → the **Throwback** section, right after "Your account".
3. **Long-press the Home tab** in the bottom bar.
4. **Long-press the Throwback app icon** on your home screen → **Throwback settings**.

The first time the feed shows, a one-time hint points at the Home long-press.

## Repository layout

A standard single-module Android project: all source code is under `app/src/`.

| Path | What's there |
|---|---|
| `app/src/main/java/app/throwback/` | The app (Kotlin, Jetpack Compose). The entry point (`MainActivity`, `AppRoot`) sits in the root folder, with one folder per area: `ui` (Settings and its screens, onboarding), `connect` (the optional Instagram app connection: relay, guard, passes), `web` (the Instagram WebView, filter rules, page bridge), `timelimit` (daily limit, PIN, lock screens), `core` (shared types, theme, UI kit). [Architecture](docs/ARCHITECTURE.md) shows how they fit |
| `app/src/main/pagescript/` | The page script injected into instagram.com (JavaScript, one file per area; the build joins them). Its [README](app/src/main/pagescript/README.md) lists the files |
| `app/src/main/res/`, `app/src/main/assets/` | Resources and the page's CSS |
| `app/src/test/` | Unit tests (JVM) |
| `app/src/debug/` | Test-build helpers (fake notifications for the emulator) |
| `filters/rules.json` | The filter rules: bundled into the app and fetched daily from this repository |
| `tools/` | Development tools: DevTools scripts, emulator helpers, the page-script lint, the shortcut-icon generator ([README](tools/README.md)) |
| `docs/` | [Architecture](docs/ARCHITECTURE.md) and [handoff notes](docs/HANDOFF.md); [DECISIONS.md](DECISIONS.md) records the product decisions, [PRIVACY.md](PRIVACY.md) the privacy policy |
| `design/` | Logo masters |

## Building

JDK 17 and Android SDK 36 (Android Studio bundles both), then:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
tools/pagescript-lint.sh        # after changing the page script (needs Node)
tools/sort-kotlin-imports.py    # Kotlin import order (--fix sorts)
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. More in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Why

<!-- Personal story to be written with the author before release. -->

## How tips work

Throwback is free. If you'd like to say thanks, Settings → Support Throwback offers three tips (A beer,
A gig ticket, GTA 6). They're one-time payments made through Google Play in
your own currency; Google keeps its fee and the rest goes to the developer. Any tip, once, unlocks
Make It Yours (your own name and icon for Throwback on the home screen); tips also help keep the
filters working as Instagram changes.

## Licence

[PolyForm Strict 1.0.0](LICENSE): you may read the code and use it for personal, non-commercial purposes; you may not redistribute it or publish changed versions. The Nunito font is under the SIL Open Font License
([docs/fonts-OFL.txt](docs/fonts-OFL.txt)).
