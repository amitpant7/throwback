# Throwback: handoff

For the next Claude Code session (or developer) picking this project up. Read this first, then
[`DECISIONS.md`](../DECISIONS.md) (what we're building and why) and
[`ARCHITECTURE.md`](ARCHITECTURE.md) (how the code is organised).

_Last updated 2026-11-20, at version 0.1.1 (versionCode 2)._

---

## 1. What Throwback is

A free, open-source (GPL-3.0) Android app that shows Instagram "the way it used to be": only people
you follow (with stories), with no Reels, no Explore grid, no suggested posts and no ads. It is a
**WebView wrapper around instagram.com**, with filters injected as CSS/JS. There is no private API
and no modded app. Everything else is native Jetpack Compose:
- onboarding and settings
- an optional daily time limit with a PIN
- an optional mindful pause
- an optional "Messages & calls" setup

**Principles the owner cares about** (don't break these without asking):
- **Minimal: the filters are always on.** Never add a setting that brings Reels, Explore,
  suggestions or ads back.
- **Privacy:** no analytics, no accounts, no server. Only Instagram traffic and a daily fetch of the
  public filter rules.
- **"A speed bump, not a cage":** be honest about what the time limit can and can't stop.
- **Branding:** no Instagram name or logo in Throwback's own branding. The "Not affiliated with
  Instagram or Meta" disclaimer stays on About.
- **Process:** the owner reviews every decision. Propose, then build. They often answer quickly and
  briefly; when something is ambiguous, ask one focused question.

## 2. Where things are

| What | Where |
|---|---|
| Code | this repo, branch `main` (feature branches `feat/web`, `feat/timelimit`, `feat/ui` are merged and can be deleted) |
| Product decisions (33 numbered) | `DECISIONS.md` |
| Architecture, ownership, contracts | `docs/ARCHITECTURE.md` |
| Filter rules (remote-updatable) | `filters/rules.json`, bundled into the APK at build time |
| Design mockups (Sage theme, ~20 screens, private) | Claude Design canvas: https://claude.ai/artifact/JVunY8kiNTP8abRP43LSGV. Open it with the owner's claude.ai account; Claude can read it with the Artifact tool. |
| GitHub | **not created yet.** The owner will create a public repo `throwback`; then push and fill in the placeholders in section 6. |

## 3. Build and run

You need **JDK 17** and **Android SDK 35** (Android Studio bundles both). `local.properties`
(git-ignored) holds `sdk.dir=...`; Android Studio creates it.

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

State at handoff:
- **Build:** clean build passes.
- **Unit tests:** **170** pass (web 78, timelimit 68, ui 24).
- **Lint:** **0 errors** and 38 warnings: newer library versions available,
  `mipmap-anydpi-v26` "obsolete", and the unused colour `tb_on_accent`.

Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each build you hand to the owner, so it
installs as an update.

**Debug builds enable WebView remote debugging**: `WebViewConfig.kt` calls
`setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`. With the phone on USB, open
`chrome://inspect` in desktop Chrome to see the live, logged-in Instagram DOM inside Throwback.
**This is the main way to verify and fix filter selectors.**

## 4. What's built (milestones)

| Milestone | Status |
|---|---|
| D: design mockups | Done. Sage theme chosen; the owner edited copy on the canvas, and the code follows those edits |
| M0: scaffold, toolchain, contracts | Done |
| M1: WebView + app feel | Built, **not yet verified on a phone** |
| M2: filters + remote rules | Built, **selectors unverified against logged-in Instagram** |
| M3: time limit + PIN + messages-only | Built with thorough unit tests; UI unverified on device |
| M4: onboarding, shortcut, About | Built; UI unverified on device |
| M5: Messages & calls relay + guard | **Not started.** Wizard and settings screens exist as a "preview" |
| M6: release prep | **Not started** |

The three feature areas were built in parallel by separate agents, each under the ownership rules in
`ARCHITECTURE.md`, then merged and wired in `core/AppRoot.kt`.

Highlights:
- **web** (`web/`, `assets/inject/`, `filters/`):
  - A long-lived WebView.
  - Document-start script injection; user agent without `; wv`.
  - Instagram's regular home feed (stories + ranking), filtered; `/?variant=following` stays allowed.
  - Reels blocked, and a single shared reel opens as a `/p/` post.
  - Ads and "Suggested" hidden by header-only label matching, so captions are never hidden.
  - Explore grid hidden, search kept.
  - Cookie banner auto-declined.
  - Messages-only mode.
  - Pull-to-refresh at the top of feed, inbox and notifications.
  - File chooser and offline screen.
  - Remote rules with validation. `RULES_URL` is empty until the repo exists.
  - `InstagramSession` tracks login via the `sessionid` cookie.
  - When logged out, the WebView opens `/accounts/login/` directly.
- **timelimit** (`timelimit/`):
  - The engine is pure Kotlin. It implements decisions #16 to #24: 5-minute wait, PIN for
    +5 min twice a day, then locked until midnight; clock-change protection; changes that take
    effect the next day; 3-day forgot-PIN reset.
  - The PIN is stored as a salted PBKDF2 hash. Wrong-PIN cooldown: 5 tries, then 30 s.
  - State lives in DataStore `timelimit`, which is included in Auto Backup.
- **ui** (`ui/`):
  - Onboarding.
  - "Instagram app found", now shown **after** the first login.
  - Settings with the read-only "Throwback removes" list.
  - Messages & calls wizard with real permission checks.
  - Home-screen shortcut with custom name and icon.
  - About with the tips Beer / Rock show / GTA 6.
  - Mindful pause.

## 5. Next steps, in order

1. **First phone test with a logged-in spare Instagram account.** The owner has a throwaway account.
   - Logging in from a server or automated browser is blocked by Instagram ("Log in on another device
     to continue", `auth_platform/no_challenge`), so **test on the owner's phone only**.
   - Never store credentials in the repo, in memory files or in agent prompts.
   - The full on-device checklist is in section 7.
2. **Tune `filters/rules.json` against the real DOM** using `chrome://inspect`. Bump `version` (yyyymmddNN)
   on every edit, and keep the rules tests in `app/src/test/.../web/` passing.
3. **Fix known issues** (section 6).
4. **Answer the open product questions** (section 6).
5. **Optional:** a hidden "Copy page info" debug action (copies the DOM outline with text stripped) for
   when USB debugging isn't available. It was proposed and the owner hasn't decided.
6. **M5, Messages & calls:**
   - A `NotificationListenerService` that keeps call notifications as they are, replaces message
     notifications with Throwback's own (opening the inbox, or messages-only when locked), and drops
     the other types according to the per-type toggles in `ui_prefs`. The keys are listed in the
     ui area's notes.
   - A guard: a foreground service using usage access plus an overlay, which bounces the user out of
     the real Instagram app except during calls.
   - The manifest already declares `PACKAGE_USAGE_STATS` and `SYSTEM_ALERT_WINDOW`.
   - Needs the phone to learn how Instagram labels its call and message notifications (channel ids and
     categories).
7. **M6, release:**
   - Play Billing tips.
   - Final app name and logo (the owner designs them).
   - Privacy policy and Play listing, with the data safety form declaring that nothing is collected.
   - Release signing.
   - Fill the GitHub links and `RULES_URL`.
   - GitHub Releases APK.
   - The owner's personal story in the README "Why" section, written with the owner.

## 5b. Ideas for later (owner)

- **Search tab looks empty** now that the Explore grid is hidden. Ideas: a calm motivating line or
  quote (short, original or public domain), or a small "filtered" set of content. Not decided; think
  it through before building.

## 6. Known issues and open questions

**Known issues / loose ends**
- **Video under the lock:** a feed video may keep playing under the lock overlay. Idea: add a `paused`
  parameter to `InstagramScreen` and pause media while locked.
- **Repeated deep link:** opening the same link twice in a row doesn't reopen it, because
  `deepLink` state doesn't change. Pair it with an event counter if it matters.
- **Settings row:** the "Daily time limit" row doesn't show the current limit; `SettingsRoot` has no
  summary input yet.
- **Placeholders:** About's `AboutLinks.PLACEHOLDER_*` URLs and the "Filters updated" date, which
  should come from the web rules version.
- **`ui_prefs` backup:** not included in Auto Backup (probably fine; ask the owner).
- **Unused imports:** some remain in `ui/onboarding/Onboarding.kt` after the login-flow change.
- **Accessibility under the lock:** `clearAndSetSemantics {}` on the WebView while locked. Verify with
  TalkBack that the feed is really unreachable.

**Product questions waiting on the owner** (each is a `rules.json` change, no app update):
1. A single reel shared in a DM opens as a normal post (one video, no Reels feed). Should reels
   instead be blocked completely?
2. `/explore/people` ("Discover people") is blocked. OK?
3. Hashtag and search-result pages are allowed. OK?
4. Optional cookies are auto-declined on the cookie banner. OK?

**Artwork note.** The tip icons (`ui_tip_beer`, `ui_tip_rock` prism + equaliser, `ui_tip_gta` sunset) are
original drawings, one drawable each so they're easy to swap. The owner wants to reference a famous
album cover on the Rock show tip and said they'll handle that themselves after getting permission.
**Don't recreate or embed third-party artwork or logos** (album art, game art, band or game logos)
without written permission in the repo. Text references by name are fine. Using third-party names as
paid tip product names should be rechecked before the Play release.

## 7. On-device checklist (first phone test)

- [ ] **First launch:** Welcome → "Log in to Instagram" → **straight to Instagram's login form** (no
      "Log in or sign up" landing page) → after login, "You still have the Instagram app" (only if it's
      installed) → the feed.
- [ ] **Splash:** goes straight to the filtered home feed, with no white flash and no unfiltered posts.
- [ ] **Home feed:** stories row shows; suggested posts and everything after "You're all caught up"
      are hidden; tapping Home causes no reload loop.
- [ ] **Hidden:** no Reels tab and no gap in the bottom bar; no Explore grid (search still works);
      no "Suggested for you"; no ads.
- [ ] **Captions:** captions and video descriptions are always visible.
- [ ] **Throwback button:** sits top-left on the feed, opens Settings, and doesn't overlap Instagram's
      heart and DM icons.
- [ ] **Login persists** after killing the app; two-factor login works.
- [ ] **Dark/light** follows the phone, including a live toggle.
- [ ] **Posting** a photo or video works through the photo picker.
- [ ] **Navigation:** back goes through Instagram's screens; links from bios open in the browser.
- [ ] **Offline:** airplane mode shows the offline screen, which recovers by itself.
- [ ] **Time limit:** 15 min + PIN → lock → 5-min wait → PIN → +5 min (twice) → locked until
      midnight; "Open messages only" → bottom bar "Ask for more time".
- [ ] **Settings and pause** time doesn't count toward the limit.
- [ ] **Shortcut:** pinning works; Mindful pause works; About tips show the "coming with Play Store" message.
- [ ] **Messages & calls wizard:** Play Store / Settings intents open correctly.

## 8. Working with the owner's phone remotely (optional)

If the phone is on USB to a machine you can't reach directly, the owner can open a reverse SSH
tunnel from that machine:

```bash
ssh -N -R 15037:localhost:5037 -R 9222:localhost:9222 <user>@<workstation>
# then on the workstation:
adb -H localhost -P 15037 devices
adb -H localhost -P 15037 install -r app-debug.apk
adb -H localhost -P 15037 forward tcp:9222 localabstract:webview_devtools_remote_<pid>   # DevTools
```

While the tunnel is open the remote machine has developer access to the phone. Only touch Throwback,
and tell the owner when you're done so they can close it and turn USB debugging off.
On a laptop with the phone plugged in directly, none of this is needed: just use `adb` and `chrome://inspect`.

## 9. Conventions

- **Commits:** small and focused, with a clear subject. Don't push or create PRs unless the owner asks.
- **Tests:** keep pure logic in plain Kotlin and covered by JVM tests (`app/src/test/...`).
- **Colours and type:** only from `ThrowbackTheme.colors` / `MaterialTheme.typography`
  (`core/theme/Theme.kt`, Sage theme).
- **Strings:** user-visible strings go in `strings_<area>.xml`.
- **Filter changes:** go in `filters/rules.json`. Bump its `version`.
