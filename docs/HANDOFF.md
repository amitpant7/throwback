# Throwback: handoff

For the next Claude Code session (or developer) picking this project up. Read this first, then
[`DECISIONS.md`](../DECISIONS.md) (what we're building and why) and
[`ARCHITECTURE.md`](ARCHITECTURE.md) (how the code is organised).

_Last updated 2026-11-20, at version 0.2.0 (versionCode 4)._

---

## 1. What Throwback is

A free, source-available (PolyForm Strict 1.0.0: personal use, no redistribution) Android app that
shows Instagram "the way it used to be": only people you follow (with stories), with no Reels, no
Explore grid, no suggested posts and no ads. It is a **WebView wrapper around instagram.com** with
filters injected as JavaScript and CSS: no private API, no modded app. On top of the filters, a little
rationed discovery, the Explore grid for 5 minutes a day (reels there play in Throwback's own player),
reels sent in chats with a swipe limit, an optional daily time limit with a PIN, an optional mindful
pause, "Your day", and an optional connection to the real Instagram app (calls, message alerts,
posting, with a guard that keeps the app out of reach otherwise).

**Principles the owner cares about** (don't break these without asking):
- **Minimal: the filters are always on.** Never add a setting that brings Reels, Explore,
  suggestions or ads back.
- **Privacy:** no analytics, no accounts, no server. Only Instagram traffic and a daily fetch of the
  public filter rules from this repository.
- **"A speed bump, not a cage":** be honest about what the time limit can and can't stop.
- **Branding:** no Instagram name or logo in Throwback's own branding; "Not affiliated with Instagram
  or Meta" stays on About.
- **Process:** the owner reviews decisions; propose, then build. They answer quickly and briefly;
  when something is ambiguous, ask one focused question.

## 2. Where things are

| What | Where |
|---|---|
| Code | github.com/amitpant7/throwback, branch `main`; layout in the [README](../README.md#repository-layout) |
| Product decisions | `DECISIONS.md` |
| Architecture, bridge, storage | `docs/ARCHITECTURE.md` |
| Page script (one file per area) | `app/src/main/pagescript/` and its README |
| Filter rules (remote-updatable) | `filters/rules.json` (bundled; fetched daily from `main`) |
| Development tools | `tools/` and its README |
| Design mockups (Sage theme, private) | https://claude.ai/artifact/JVunY8kiNTP8abRP43LSGV (Claude can read it with the Artifact tool) |

**Editing `filters/rules.json` changes installed apps within a day** (they fetch it from `main`):
bump its `version` (yyyymmddNN), keep the rules tests passing, and only push rules the released app
understands.

## 3. Build, run, test

```bash
export JAVA_HOME=$(ls -d ~/.local/opt/jdk-17* | head -1) ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug testDebugUnitTest lintDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
tools/pagescript-lint.sh                                 # after page-script changes (Node)
```

State at handoff: build passes; 309 unit tests pass; lint has 0 errors and only "newer version
available" warnings (dependencies date from December 2024; see 5). The page script lints clean as ES5.

Bump `versionCode`/`versionName` in `app/build.gradle.kts` for each build handed to the owner.

**Debug builds** allow WebView remote debugging: `chrome://inspect`, or the scripts in `tools/`
(evaluate in the page or inside the page script's scope, memory and CPU profiles, request counts,
a stuck-page catcher). This is the main way to check filters and page behaviour.

**Emulator.** AVD `tb35` (Android 15, 4 GB) on the workstation, with a logged-in Instagram test account
in Throwback and the real Instagram app installed. Start it with
`sg kvm -c "$HOME/Android/Sdk/emulator/emulator -avd tb35 -no-window -no-audio -no-boot-anim -gpu swangle_indirect -feature -Vulkan"`
(Vulkan crashes with the Instagram app). Instagram blocks logins from the workstation ("Log in on
another device"), so never log the emulator out; don't like, follow or save with the test account.

**Owner's phone** (only with the owner present): a reverse SSH tunnel, `adb -H localhost -P 15037`.
Before every command check `ss -ltnp 'sport = :15037'` shows the tunnel, not a local adb server (kill
one with `adb -P 15037 kill-server`). Never open stories on the owner's account (it marks them seen).

**Test-build switches** (debug only):

| Switch | Effect |
|---|---|
| `files/debug_clock` containing `<speed> [HH:MM]` | Time-limit clock runs faster; with `HH:MM` the day starts there. Delete it and `files/debug_clock_anchor` to return to real time |
| `files/debug_noscript` | No page script: plain Instagram in the same WebView, as a baseline |
| `am start … --es debug_open settings` or `messages` | Opens Settings or Connect › Instagram directly |
| `am start … --ez debug_supporter true` | Acts as if a tip went through: unlocks Make It Yours (stays set) |
| `DebugRelayReceiver` broadcasts | Fake Instagram notifications; make the guard watch another app (see `app/src/debug/`) |

```bash
adb shell "run-as app.throwback sh -c 'echo 60 23:55 > /data/data/app.throwback/files/debug_clock'"
adb shell am start -n app.throwback/.MainActivity --es debug_open settings
```

Logs (`adb logcat -s <tag>`): `ThrowbackWeb`, `ThrowbackRules`, `ThrowbackRelay`,
`ThrowbackRelayLabels` (test builds: labels of every Instagram notification, never their text),
`ThrowbackGuard`, `ThrowbackTime`, `ThrowbackApp`, `ThrowbackStart`.

## 4. Review of 2026-11-20 (what was checked and fixed)

The owner asked for a clean, organised, reviewed code base without bugs or ways around the limits,
with memory and latency checked. Five reviews (page script ×2, WebView layer, UI, a red-team of the
time limit and guard) and measurements on the emulator led to these changes:

- **Memory/freeze:** Instagram's video player could ask Throwback's in-memory look-ahead for the same
  piece thousands of times a second; the page froze after a minute or two and its heap grew from
  34 MB to 760 MB. Pieces are now handed over once, on a later task. A 6-minute scroll stays at
  Instagram's own growth (~9 MB/min, as without Throwback); the page script's own code takes about
  0.1 s of 5 s main-thread work in 40 s of scrolling.
- **Cheats closed:** the page's day followed the phone's clock (Explore, Discover, Your day reset);
  pass counters used the phone's clock and zone (unlimited 5-minute visits); the story pass's free
  leave allowed unlimited 3-minute slices of the real app; clock forward + reboot (API 33+, via
  network time, and Instagram's server time below Android 13); split screen, pop-up windows and PiP left the real app uncovered; settings and
  posting passes were long and uncounted; any phone call kept the real app open; stopping the guard
  hid the real app's time (now counted when Throwback next runs); the profile Tagged tab.
- **Security:** a URL with a backslash could load another site full-screen through a deep link;
  remote rules are pinned to Instagram's hosts and patterns are checked for catastrophic
  backtracking and JavaScript compatibility; old WebViews' bridge can't grant passes.
- **Bugs:** Back from Settings could close the app; the guard-off PIN keypad stayed blocked; the
  second logout landed on Instagram's landing page; renderer crashes looped; the rating ask and
  support line fired while the end card was off screen; a cached feed page ended the feed early;
  the tip field went under the keyboard; text clipped at large font sizes; and more (see the
  commits of 2026-11-20).
- **Organisation:** the 4,000-line page script is one file per area; one shared UI kit (`core/ui`)
  replaced the duplicated components of the ui and timelimit areas; dead code and stale comments
  removed; `.editorconfig`; `tools/`.

Verified on the emulator: the freeze is gone (6-minute scroll), Explore minutes, the end card and
support line, the profile icon and settings-page rows, deep-link refusal, the Tagged tab, renderer
crash recovery, recreation after a deep link, the daily rules check, the time-zone pass reset, the
guard covering the real app (also in a pop-up window and in picture-in-picture, with the cover
stepping aside so the window can be closed), and an empty feed showing "You're all caught up".
An integration review after merging the three fix batches found and fixed: "Your day" blank during
a chat-reel cooldown, wrong "Your story" messages, the page's clock not refreshed after a late time-limit
load or network time, Back reaching the page under the lock, and a stale guard heartbeat.
Not verifiable here (needs the phone or a fresh login): the login/logout flows, and Instagram's own
editor screens (they can't render on the emulator).

## 5. Open items

**Release prep (M6):**
- Google Play now requires a recent target API (35 since Aug 2025; 36 is expected for 2026 updates).
  Raise `targetSdk`/`compileSdk` and update the dependencies (AGP 8.7, Compose BOM 2024.12 and
  androidx libraries are about two years old; lint lists them) in one focused change, then run the
  full on-device checklist (7).
- Play Billing for the tips (product ids per tip; "Whatever you want" maps to fixed step products;
  `SupportAsk.markTipped` once a tip is confirmed). Recheck third-party names ("GTA 6") as tip product
  names, and replace or license the gig ticket's prism image (`ui_tip_rock`).
- Privacy policy, Play listing and data-safety form (nothing collected); release signing; the
  owner's story in the README "Why" section.
- Optional: an enforced Kotlin formatter (ktlint via Gradle). Downloading and running it was not
  permitted in the 2026-11-20 session, so `.editorconfig` and Android lint are the standard for now.

**Known issues:**
- Accessibility under the lock (`clearAndSetSemantics {}` on the WebView): verify with TalkBack.
- `ui_prefs` is not in Auto Backup, so a reinstall forgets the Instagram connection (the owner
  accepted reinstalling as the emergency exit).

**Owner's answers of 2026-11-21, built** (DECISIONS.md #34):
- [x] a. Suggested people in the home feed and on Discover people
- [x] b. Calls unlock only the call screen (allowed as before when it can't be recognised)
- [x] c. Offer to connect the Instagram app after turning a limit on
- [x] d. Remote rules tap only dismiss buttons
- [x] e. Chat reel viewer detection and Close labels into the rules
- [x] f. Connect › Instagram setup mentions clones and work profiles

## 6. How the home feed is filtered

1. **Data filter** (`80-feed-data.js`, rules `feedData`): Instagram's feed arrives as JSON pages; ads,
   suggestions and the configured blocks are removed from the data in a `JSON.parse`/`Response.json`
   wrapper before Instagram's code sees them. The discovery allowance (DECISIONS 13b) keeps a few
   suggestions and tags them. After 3 pages in a row with no followed post, the feed ends:
   `has_next_page` is set to false and "You're all caught up" shows after the last post.
2. **Hide until approved** (`86-approve-posts.js`): on the home feed every post stays hidden until
   checked: posts the data filter kept show at once; others once their header and own photo or video
   have rendered (labels checked), at the latest after 0.3 s.
3. **Look-ahead** (`82-stories.js`, `84-feed-lookahead.js`): photos of the next posts and stories go to
   the disk cache; the start of the next videos is kept in memory and handed to the player once.
4. **Fallback bottom logic** (`30-feed-end.js`): for hidden posts left in the DOM, a latched "gap"
   state and an "ended" state.

## 7. On-device checklist (before handing over a build)

- [ ] First launch: Welcome → login form directly → "You still have the Instagram app" (if installed)
      → the feed → the one-time settings hint.
- [ ] Feed: stories row; no ads, suggestions or Reels; first screen never half blank; "You're all
      caught up" only at the real end; the support line only when due.
- [ ] Scroll for 5+ minutes: smooth, no freeze (`tools/devtools/memwatch.mjs` if in doubt).
- [ ] Explore: 5 minutes a day on the grid and in the reel player, then "Enough for today"; search
      works and doesn't use the minutes; the reel player plays, likes, saves, opens comments.
- [ ] Reels in chats: the opened reel plays; the poster within 1–4 swipes; an hour's cooldown.
- [ ] Captions and video descriptions always visible.
- [ ] Settings entry points: profile icon, Instagram's settings page section, Home long-press,
      app-icon shortcut. Back steps through Settings and never closes the app from there.
- [ ] Login persists; logging out twice still lands on the login form.
- [ ] Dark/light follows the phone, including a live switch (system bar icons too).
- [ ] Posting through the photo picker; links from bios open in the browser; offline screen.
- [ ] Time limit: lock → 5-minute wait → PIN → +5 minutes (twice) → locked until the day ends;
      messages-only mode; nothing plays under the lock, Settings, the pause or the poster.
- [ ] Changing the phone's date or time zone changes nothing (Explore minutes, passes, the limit).
- [ ] Connect › Instagram: message notifications replaced; calls ring and open; the real app is
      covered outside passes, also in split screen and picture-in-picture; each pass behaves as in
      ARCHITECTURE.

## 8. Conventions

- **Commits:** small and focused, with a clear subject and a body saying what and why.
- **Tests:** pure logic in plain Kotlin with JVM tests; page-script changes checked on the emulator.
- **UI:** components from `core/ui`, colours from `ThrowbackTheme.colors`, type from
  `MaterialTheme.typography`; user-visible strings in `strings_<area>.xml`.
- **Page script:** ES5 style, one area per file (see its README); run `tools/pagescript-lint.sh`.
- **Filter rules:** bump `version` on every edit; remember installed apps fetch it from `main`.
