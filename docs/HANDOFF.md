# Throwback: handoff

For the next Claude Code session (or developer) picking this project up. Read this first, then
[`DECISIONS.md`](../DECISIONS.md) (what we're building and why) and
[`ARCHITECTURE.md`](ARCHITECTURE.md) (how the code is organised).

_Last updated 2026-11-20, at version 0.1.2 (versionCode 3)._

---

## 1. What Throwback is

A free, open-source (GPL-3.0) Android app that shows Instagram "the way it used to be": only people
you follow (with stories), with no Reels, no Explore grid, no suggested posts and no ads. It is a
**WebView wrapper around instagram.com**, with filters injected as CSS/JS. There is no private API
and no modded app. On top of the filters:
- a little discovery: a few tagged suggested posts, strictly rationed (DECISIONS 13b)
- the Explore grid for 5 minutes a day (13i)
- reels sent in chats play, but a poster stops the swiping after 1–4 swipes (13f)

Everything else is native Jetpack Compose:
- onboarding and settings (Support and About are separate screens)
- an optional daily time limit with a PIN
- an optional mindful pause
- optional "Messages & calls": a notification relay and a guard around the real Instagram app

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
| Code | this repo, branch `main`. The old feature branches `feat/web`, `feat/timelimit`, `feat/ui` are merged and can be deleted; M5 (`feat/m5-messages-calls`) is merged too |
| Product decisions | `DECISIONS.md`: 1–33b, including 13b–13i, 28, 33a and 33b |
| Architecture, ownership, contracts, bridge, relay/guard | `docs/ARCHITECTURE.md` |
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

State at handoff (commit 4cf3f1c):
- **Build:** passes. A broken `filters/rules.json` (invalid JSON or merge-conflict markers) fails it.
- **Unit tests:** **219** pass (web 94 incl. rules, timelimit 68, relay 27, ui 22, guard 8).
- **Lint:** **0 errors**, 2 warnings: `mipmap-anydpi-v26` "obsolete" and the unused colour
  `tb_on_accent`.

Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each build you hand to the owner, so it
installs as an update.

**Debug builds enable WebView remote debugging**: `WebViewConfig.kt` calls
`setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`. Open `chrome://inspect` in desktop Chrome to see
the live, logged-in Instagram DOM inside Throwback (emulator, or phone on USB or through the tunnel in
section 8). **This is the main way to verify and fix filter selectors.** The page's
`__throwback.perf()`, `feedStats()`, `storyStats()` and `reelEvents()` are read from that console.

## 4. What's built (milestones)

| Milestone | Status |
|---|---|
| D: design mockups | Done. Sage theme chosen; the owner edited copy on the canvas, and the code follows those edits. The reel poster and the Explore "Enough for today" card still need their final design |
| M0: scaffold, toolchain, contracts | Done |
| M1: WebView + app feel | Built; in use on the owner's phone and the emulator |
| M2: filters + remote rules | Built. Feed data, stories and notification rules were read from the owner's phone; `RULES_URL` is empty until the repo exists |
| M3: time limit + PIN + messages-only | Built; the whole flow verified end to end with the test-build fast clock. A redesign of the setup screens has started on `main` |
| M4: onboarding, shortcut, Support/About, settings hint | Built |
| M5: Messages & calls relay + guard | Built and partly verified on the phone (see 5d) |
| M6: release prep | **Not started** |

The three feature areas (web, timelimit, ui) were built in parallel by separate agents under the
ownership rules in `ARCHITECTURE.md`, then merged and wired in `core/AppRoot.kt`. M5 (`relay/`,
`guard/`) was built by integration afterwards.

Highlights:
- **web** (`web/`, `assets/inject/`, `filters/`):
  - A long-lived WebView; document-start script injection; user agent without `; wv`.
  - Instagram's regular home feed (stories + ranking), filtered in its data before it is drawn
    (section 5c); `/?variant=following` stays allowed.
  - Reels tab and pages blocked; a reel link opens as a `/p/` post. Reels opened from a chat play in
    Instagram's swipe viewer until the reel limit's poster (DECISIONS 13f).
  - Stories: story ads removed from the data; the next 5 stories are loaded ahead (13g).
  - Explore grid for 5 minutes a day, then "Enough for today…" (drawn by the page script); search
    always works.
  - Settings entry points in the page (section 5a); a call button in chats (opens the chat in the
    Instagram app to call).
  - Messages warm-up: Instagram's messages and story-viewer code is prefetched when the feed is
    idle (13e).
  - Cookie banner auto-declined; messages-only mode; pull-to-refresh at the top of feed, inbox and
    notifications; file chooser; offline screen.
  - Remote rules with validation. `InstagramSession` tracks login via the `sessionid` cookie; when
    logged out, the WebView opens `/accounts/login/` directly.
- **timelimit** (`timelimit/`):
  - The engine is pure Kotlin. It implements decisions #16 to #24: 5-minute wait, PIN for
    +5 min twice a day, then locked until midnight; clock-change protection; changes that take
    effect the next day; 3-day forgot-PIN reset.
  - The PIN is stored as a salted PBKDF2 hash. Wrong-PIN cooldown: 5 tries, then 30 s.
  - State lives in DataStore `timelimit`, which is included in Auto Backup.
- **ui** (`ui/`):
  - Onboarding; "Instagram app found" **after** the first login; the one-time settings hint.
  - Settings with the read-only "Throwback removes" list; Support (tips Beer / Rock show / GTA 6,
    "Tips arrive with the Play Store release") and About (links, filter rules date, disclaimer).
  - Messages & calls: the 2-step wizard (install Instagram, then permissions) and the settings screen
    with the quieter-phone tip and "Open Instagram for 5 minutes".
  - Home-screen shortcut with custom name and icon; mindful pause; reel poster.
- **relay / guard** (`relay/`, `guard/`): see ARCHITECTURE "Messages & calls".

## 5. Current to-do (2026-11-20)

**Done since 0.1.1** (for orientation; details in DECISIONS and ARCHITECTURE):
- [x] Messages & calls built and partly verified on the phone:
  - relay, with Instagram's real notification channels in `rules.json`;
  - guard, including the call audio mode (an answered call stays allowed);
  - 2-step wizard ("Quiet the Instagram app" became an optional tip);
  - quieter-phone tip with the count of the last 7 days;
  - "Open Instagram for 5 minutes";
  - call button in chats via `ig.me/m/<username>` (works across accounts; the wizard asks for the
    same account in the Instagram app).
- [x] Reel limit: poster on a random 1st–4th swipe, then no free swipes for 1 hour.
- [x] Stories: story ads removed; look-ahead of 5.
- [x] Explore: 5 minutes a day, then "Enough for today…".
- [x] Settings: Support and About are separate screens; About shows the filter rules' date.
- [x] Time-limit flow verified end to end with the test-build fast clock (section 8).
- [x] One-time hint for Throwback's settings.

**Open:**
- [ ] **Owner decisions:** `/explore/people` (suggested people; blocked now); hashtag and search-result
      pages (allowed now); the hidden "Copy page info" debug action (copies the DOM outline with text
      stripped, for when USB debugging isn't available).
- [ ] **Design (owner):** the reel poster (directions in the design canvas, recommended
      "A · Postcard") and the Explore "Enough for today…" card. Give both "stop" screens one visual
      style; today both are plain text versions.
- [ ] **Bug:** after logging out a second time, Throwback lands on Instagram's "Log in or sign up"
      landing page instead of going straight to the login form.
- [ ] **Clean-up:** remove the temporary reel debug logs (`Log.d` "reel poster …" in `AppRoot.kt` and
      `InstagramWebController.kt`; `__throwback.reelEvents()` and its `localStorage` log in
      `throwback.js`) once the poster is settled.
- [ ] **Release prep (M6):**
  - Asset licence: `res/drawable-nodpi/ui_tip_rock.webp` (Rock show tip) is cropped from a prism
    render supplied by the owner on 2026-11-20. Confirm its source and licence before the Play
    release / public repo (credit it in About if required).
  - Play Billing tips; recheck third-party names as tip product names.
  - Final app name and logo (the owner designs them).
  - Privacy policy and Play listing, with the data safety form declaring that nothing is collected.
  - Release signing.
  - Fill the GitHub links (`AboutLinks.PLACEHOLDER_*`) and `RULES_URL`; GitHub Releases APK.
  - The owner's personal story in the README "Why" section, written with the owner.
- [ ] **Upcoming features:**
  - Reels from Explore, within the 5 Explore minutes.
  - A "Your day" stats page.
  - Time-limit setup redesign (started on `main`: the PIN is created only after "Turn on"; new keypad
    bottom row).

## 5a. Ways into Throwback's settings (no button on the feed)

| Way | Where it's implemented |
|---|---|
| Profile page icon (replaces the Threads icon, own profile only) | `throwback.js` `updateProfileEntry()`, bridge message `{"t":"settings"}` |
| "Throwback" section on Instagram's settings page, after "Your account" | `throwback.js` `updateSettingsSection()`; path and headings in `rules.json` `settingsSection` |
| Long-press the Home tab | `throwback.js` `installHomeLongPress()` |
| Long-press the app icon → "Throwback settings" | `res/xml/shortcuts.xml`, action `app.throwback.action.OPEN_SETTINGS` |

All of them end in `AppRoot` opening `SettingsRoot`. A one-time card (`ui.SettingsHint`, DECISIONS
33b) points new users at the Home long-press. Test builds can also use
`adb shell am start -n app.throwback/.MainActivity --es debug_open settings` (or `messages`).

## 5b. Ideas for later (owner)

- **Search tab after the Explore minutes** looks empty. Ideas: a calm motivating line or quote (short,
  original or public domain), or a small "filtered" set of content. Not decided; think it through
  before building.
- **Speed ideas not taken yet:** `Profile#preconnect` on resume (needs androidx.webkit ≥ 1.15),
  checking for HTTP-cache eviction of the DM/story code, a warm second WebView only if in-page DM
  opening is still > 1–2 s. Measure first with `__throwback.perf()`; the owner taps (Throwback never
  opens stories itself, that would mark them seen).

## 5c. How the home feed is filtered (`throwback.js`)

Decided 2026-11-20 after hiding drawn posts caused flicker (DECISIONS 13d, 13h):

1. **Data filter (`installDataFilter`, rules `feedData`).** Instagram's page loads the feed as JSON
   pages (`/graphql/query`, `PolarisFeedRootPaginationCachedQuery`, connection
   `xdt_api__v1__feed__timeline__connection`). Each edge's node holds exactly one of `media`
   (followed), `explore_story` (suggested; post at `explore_story.media`) or `ad`. `JSON.parse` and
   `Response.json` are wrapped at document start; ads, suggestions and the `dropNodeFields` blocks
   are removed from the data before Instagram's code sees it, so they are never drawn. The discovery
   allowance (DECISIONS 13b, `dataDiscovery`) keeps a few suggestions and marks them for the
   "Discover" tag. After 3 pages in a row with no followed post (`endAfterEmptyPages`),
   `page_info.has_next_page` is set to false: Instagram stops loading and "You're all caught up"
   shows right after the last post (exact end; `endAtMarker` is false, so Instagram's own "seen all
   new posts" marker doesn't end the feed). No extra requests: only data the page already downloaded
   is filtered. `friendship_status.following` is NOT reliable (false on some followed-feed posts) and
   is not used. Every post the filter keeps is remembered by its shortcode (`learnFeedMedia`).
2. **Hide until approved (`approvePost`, safety net).** On the home feed every `article` is hidden by
   CSS until it gets `data-tb-ok`:
   - **Fast path:** a post whose shortcode the data filter kept is shown at once. (Waiting for its
     photo left the lower half of the first screen blank for a moment.)
   - Any other post (for example from page HTML the data filter never saw) is shown once its header
     and photo or video have rendered, so an "Ad"/"Sponsored" label in the header has been checked
     first. This runs in the mutation callback, before the frame paints.
   - **Fallback:** a post with no photo or video yet is let through after 0.3 s
     (`APPROVE_WAIT_MS`), so nothing stays hidden for good.
   - A suggestion the data filter let through is tagged "Discover · not someone you follow".
3. **Look-ahead (`feedLookAhead`).** From the post on screen: photos/thumbnails of the next 7 posts go
   to the disk cache, and the first 400 KB (plus 64 KB of audio) of the next 3 videos are kept in
   memory and handed to the player; cleared when leaving the feed, off with Data Saver
   (`lookAheadPosts`, `lookAheadVideos`, `videoKB`). Stories use the same mechanism with 5 ahead,
   and story ads (`xdt_injected_story_units`) are emptied (rules `stories`).
4. **Batch size.** `installRequestTweak` can raise `first` in the feed's pagination request to
   `feedData.pageSize`. It is **off** (`pageSize` = 0): Instagram asks for 12 and its server returns
   at most 10 entries per batch whatever is asked, so a bigger number gains nothing. If Instagram
   ever refuses a raised request, the tweak turns itself off.

Fallback bottom logic (`updateEndCard`) still handles hidden posts left in the DOM: two latched
states, `data-tb-feedgap` (reserved space removed, Instagram's spinner shows) and `data-tb-feedend`
(20 hidden in a row or 10 s idle at the bottom). Images of the next 4 real posts load early, and the
WebView pre-rasters off-screen content.

## 6. Known issues and open questions

**Known issues / loose ends**
- **Second logout:** see 5 (lands on the "Log in or sign up" page).
- **Settings row:** the "Daily time limit" row doesn't show the current limit; `SettingsRoot` has no
  summary input yet.
- **Placeholders:** About's `AboutLinks.PLACEHOLDER_*` URLs (the repo doesn't exist yet).
- **`ui_prefs` backup:** not included in Auto Backup (probably fine; ask the owner).
- **Unused imports:** some remain in `ui/onboarding/Onboarding.kt` after the login-flow change.
- **Accessibility under the lock:** `clearAndSetSemantics {}` on the WebView while locked. Verify with
  TalkBack that the feed is really unreachable.
- **Stale code comments:** the header of `throwback.js` still says it hides Instagram's wordmark (no
  rule does), and `AppRoot`/`InstagramScreen` KDoc mention "top-start controls" (the chip is at the
  top centre). `core/ThrowbackMark.kt` and `GuardPass.SETUP_PASS_MS` are unused.

**Product questions waiting on the owner** (each is a `rules.json` change, no app update):
1. `/explore/people` ("Discover people") is blocked. OK?
2. Hashtag and search-result pages are allowed. OK?

**Artwork note.** The tip icons (`ui_tip_beer`, `ui_tip_rock`, `ui_tip_gta`) are one drawable each so
they're easy to swap. Beer and GTA 6 are original drawings; Rock show uses the owner's prism image
(licence to confirm, see 5). **Don't recreate or embed third-party artwork or logos** (album art, game
art, band or game logos) without written permission in the repo. Text references by name are fine.
Using third-party names as paid tip product names should be rechecked before the Play release.

## 7. On-device checklist (regression pass before handing over a build)

- [ ] **First launch:** Welcome → "Log in to Instagram" → **straight to Instagram's login form** (no
      "Log in or sign up" landing page) → after login, "You still have the Instagram app" (only if it's
      installed) → the feed → the one-time settings hint above the Home tab.
- [ ] **Splash:** goes straight to the filtered home feed, with no white flash and no unfiltered posts.
- [ ] **Home feed:** stories row shows; suggested posts and ads ("Sponsored" and "Ad") are hidden; the
      first screen is never half blank; tapping Home causes no reload loop.
- [ ] **Feed bottom:** no blank space; Instagram's spinner shows in gaps between waves of followed
      posts; "You're all caught up" only at the true end, with no flicker.
- [ ] **Discovery:** now and then a post tagged "Discover · not someone you follow" (at most 1 video
      and 1 photo per 5 min of feed time, 20 a day).
- [ ] **Scrolling and stories** feel smooth (look-ahead); no story ads; no memory trouble.
- [ ] **Hidden:** no Reels tab and no gap in the bottom bar; no "Suggested for you"; no ads.
- [ ] **Explore:** the grid shows for 5 minutes a day on the Search tab, then "Enough for today…";
      search still works.
- [ ] **Reels in chats:** a reel sent in a chat plays; swiping on shows the poster within 1–4 swipes;
      Back returns to the chat; for the next hour the first swipe already shows it.
- [ ] **Captions:** captions and video descriptions are always visible.
- [ ] **Settings entry points:** no Throwback button on the feed. Own profile: Throwback icon where
      the Threads icon was, opens Settings. Instagram's settings page: "Throwback" section right after
      "Your account", looks native, opens Settings. Long-press Home opens Settings; a normal tap still
      goes home. Long-press the app icon → "Throwback settings".
- [ ] **Login persists** after killing the app; two-factor login works; logging out twice still
      lands on the login form.
- [ ] **Dark/light** follows the phone, including a live toggle.
- [ ] **Posting** a photo or video works through the photo picker.
- [ ] **Navigation:** back goes through Instagram's screens; links from bios open in the browser.
- [ ] **Offline:** airplane mode shows the offline screen, which recovers by itself.
- [ ] **Time limit:** 15 min + PIN → lock → 5-min wait → PIN → +5 min (twice) → locked until
      midnight; "Open messages only" → bottom bar "Ask for more time". Nothing keeps playing under
      the lock, Settings, the pause or the poster.
- [ ] **Settings and pause** time doesn't count toward the limit.
- [ ] **Shortcut:** pinning works; Mindful pause works; Support shows "Tips arrive with the Play Store
      release."
- [ ] **Messages & calls:** the wizard's Play Store / Settings intents open correctly; a message
      notification is replaced by Throwback's (opens the chat, messages-only when locked); other
      types follow the switches; incoming calls ring and can be answered; the chat's call button opens
      the chat in the Instagram app; opening the Instagram app otherwise shows "Use Throwback instead";
      "Open Instagram for 5 minutes" lets you in for 5 minutes.

## 8. Testing

**Emulator.** The workstation has an Android 15 AVD (`tb35`, SDK in `~/Android/Sdk`) with a logged-in
Instagram test account in Throwback. Logging in from a server or automated browser is usually blocked
by Instagram ("Log in on another device to continue"), so keep that session instead of logging in
again. Never store credentials in the repo, in memory files or in agent prompts. There is no Instagram
app on the emulator: test the relay and the guard with the fake notifications below.

**Owner's phone (through the adb tunnel).** If the phone is on USB to a machine you can't reach
directly, the owner opens a reverse SSH tunnel from that machine:

```bash
ssh -N -R 15037:localhost:5037 -R 9222:localhost:9222 <user>@<workstation>
```

Before any `adb -P 15037` command, check that port 15037 on the workstation is held by the tunnel,
not by a local adb server; a local server there would answer instead of the phone (and starting one
there blocks the tunnel):

```bash
ss -ltnp 'sport = :15037'   # tunnel: Process column empty (sshd runs as root) or sshd. If it shows adb, stop: not the phone
adb -H localhost -P 15037 devices
adb -H localhost -P 15037 install -r app/build/outputs/apk/debug/app-debug.apk
adb -H localhost -P 15037 forward tcp:9222 localabstract:webview_devtools_remote_<pid>   # DevTools
```

While the tunnel is open the workstation has developer access to the phone. Only touch Throwback,
installs need the owner's OK, and tell the owner when you're done so they can close the tunnel and
turn USB debugging off. With the phone plugged in directly, plain `adb` and `chrome://inspect` work.

**Test-build switches** (debug builds only; release builds ignore them):

| Switch | Effect |
|---|---|
| `files/debug_clock` containing `<speed> [HH:MM]` | Time-limit clock runs `<speed>` times faster (1–3600); with `HH:MM` the day starts at that time (e.g. `60 23:55` to reach midnight quickly). The start point is saved in `files/debug_clock_anchor` so restarts continue; delete both files (and the time-limit data) to return to real time |
| `files/debug_noscript` (any content) | No page script is injected: plain Instagram in the same WebView and session, to measure Throwback's own cost |
| `--es debug_open settings` or `messages` | `am start` extra that opens Settings or Messages & calls directly, without an Instagram login |
| `DebugRelayReceiver` broadcasts | Fake "Instagram" notifications (`--es cmd notify --es kind message\|call\|likes\|…`), `remove`, and `guard_pkg` to make the guard watch another app; examples in `app/src/debug/java/app/throwback/relay/DebugRelayReceiver.kt` |

```bash
adb shell "run-as app.throwback sh -c 'echo 60 23:55 > /data/data/app.throwback/files/debug_clock'"
adb shell "run-as app.throwback touch /data/data/app.throwback/files/debug_noscript"
adb shell am start -n app.throwback/.MainActivity --es debug_open settings
```

**Logs** (`adb logcat -s <tag>`): `ThrowbackRelayLabels` (test builds: the labels of every Instagram
notification the relay sees, i.e. package, channel, category, style, ongoing, summary, full-screen;
never titles, text, names or chat ids; use it to fill `rules.json` `notifications`), `ThrowbackRelay`,
`ThrowbackGuard`, `ThrowbackTime` (fast clock), `ThrowbackWeb`, `ThrowbackApp`.

**Page script in headless Chrome.** `throwback.js` can be checked without a phone or WebView, in real
Chromium (XHR/fetch wrapping, MutationObserver timing, layout). The harness is not in the repo; it is
a throwaway HTML file:
1. Put `throwback.js` first in `<head>`, with `__THROWBACK_CONFIG__` replaced by
   `{"mode":"FULL","css":<throwback.css as a JSON string>,"rules":<rules.json>}` (what
   `PageScript.build` produces), and a stub `window.throwbackNative = {postMessage: …}` before it to
   collect bridge messages.
2. Add mock Instagram markup or feed JSON, and a test script that writes its results into an
   attribute on `<body>` (e.g. `data-r`), using `__throwback.feedStats()` and friends.
3. Run `google-chrome --headless --disable-gpu --virtual-time-budget=10000 --dump-dom file:///…/index.html > dom.html`
   and read the attribute. Without `--virtual-time-budget`, timers don't run before the dump.

Pure logic stays in JVM unit tests (`app/src/test/...`), including `PageScriptTest` and the rules tests.

## 9. Conventions

- **Commits:** small and focused, with a clear subject. Don't push or create PRs unless the owner asks.
- **Tests:** keep pure logic in plain Kotlin and covered by JVM tests (`app/src/test/...`).
- **Colours and type:** only from `ThrowbackTheme.colors` / `MaterialTheme.typography`
  (`core/theme/Theme.kt`, Sage theme).
- **Strings:** user-visible strings go in `strings_<area>.xml`.
- **Filter changes:** go in `filters/rules.json`. Bump its `version` (yyyymmddNN) on every edit and
  keep the rules tests in `app/src/test/.../web/` passing.
