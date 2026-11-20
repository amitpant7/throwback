# Throwback: decisions

The product decisions agreed during planning (2026-11-20). Change a decision here first, then in code.

## Product

1. **What it is:** a free Android app that shows Instagram "the way it used to be": only people you
   follow (with stories), no Reels, no Explore grid, no suggested posts, no ads.
2. **Codename:** Throwback. The public app name and logo are chosen later; the current logo is a placeholder.
3. **Repository:** public on GitHub, licensed GPL-3.0, so nobody can repackage it as a closed paid app.
4. **Money:** free, with optional tips through Google Play Billing (no paid features). The tip tiers in
   the mockup are Beer / Rock show / GTA 6, all with original icons. Before the Play release, recheck
   using third-party names as product names.
5. **Privacy:** no analytics, no accounts, no server. Nothing leaves the phone except traffic to
   Instagram itself and a daily fetch of the public filter rules.
6. **Disclaimer:** "Not affiliated with Instagram or Meta" on the store listing and the About screen.
   No Instagram name or logo in the app's branding.
7. **Releases:** Google Play, plus APKs on GitHub Releases.

## How it works

8. **WebView wrapper** around instagram.com with injected CSS/JS. There is no private API and no modded app.
9. **Native Kotlin + Jetpack Compose**, single module, min SDK 26 (Android 8), target SDK 35.
10. **Filter rules** live in `filters/rules.json` in the repo. They are bundled into the APK, and the
    app fetches the latest version at most once a day. Instagram changes can be fixed without an app update.
11. **App feel:** no browser signs (full screen, no URL bar, no "open in app" banners, no zoom or
    text-select glitches). It has a splash screen, back button through Instagram's screens, pull to
    refresh, the native photo picker, an offline screen, and Instagram links opening in Throwback.
12. **Bottom bar:** keep Instagram's own web bottom bar, minus Reels.

## Filters (always on)

13. Open at Instagram's regular home feed (stories included, Instagram's own ranking; decided
    2026-11-20, replacing the Following feed, which has no stories), then filter it: hide Reels (tab and pages), hide the Explore grid (search stays),
    hide suggested posts, hide sponsored posts.
14. **No toggles.** Throwback is minimal; there is no setting to bring distractions back.
    Settings shows a read-only "Throwback removes" list.
15. Video captions and descriptions must never be hidden (a known complaint about a competitor).

## Daily time limit (optional, off by default)

16. The user picks a daily limit (15/30/45/60 min) and sets a 4-digit PIN.
17. When time is up, Throwback locks.
18. **Extensions:** wait 5 minutes, then enter the PIN for +5 minutes. This works twice a day, with
    the wait before each one. After that it stays locked until local midnight. There is no full-day unlock.
19. **Messages-only mode while locked:** the inbox and chats only. Time there does not count.
20. **Calls** never count and are never blocked.
21. **Anti-cheat:** turning the limit off, raising it, or changing the PIN needs the PIN and takes
    effect the next day. Lowering the limit is immediate. Changing the phone's clock does not reset usage.
22. **Forgot PIN:** a 3-day countdown, then a new PIN can be set. The countdown can be cancelled.
    During the wait the limit stays on and extensions aren't possible.
23. **Reinstall / clear data:** onboarding says honestly that Throwback is "a speed bump, not a cage".
    The time-limit settings (limit, PIN hash, extension state, pending PIN reset) are included in
    Android Auto Backup, so a reinstall restores them. Instagram login and WebView data are never
    backed up. "Clear data" stays the emergency exit.
24. The PIN is stored only as a salted PBKDF2 hash. The setup screen says "Write it down somewhere
    you won't see every day."

## Messages & calls (optional, built after the first phone test)

25. The real Instagram app stays installed but hidden, only for notifications and calls.
26. **Relay:** calls ring normally and are answered in the real app. Message notifications are replaced
    by Throwback's own, which open the Throwback inbox (or messages-only mode when locked). Other
    notification types are per-type toggles, all off by default.
27. **Guard:** opening the real Instagram app outside a call shows "Use Throwback instead" and returns
    the user to Throwback. During a call it is allowed.
28. A 3-step setup wizard installs Instagram (via the Play Store), explains which Instagram
    notifications to keep, and grants 3 permissions (notification access, usage access, display over
    other apps). No "hide the icon" step: the guard sends the user back to Throwback anyway, and
    "Remove it" covers uninstalling (decided 2026-11-20).
29. **Instagram app found:** after first login, if the Instagram app is installed, Throwback offers
    "Keep it for calls & messages", "Remove it" (opens Android Settings) or "Decide later".

## Other

30. **Mindful pause:** optional and off by default. A 3-second quote before Instagram opens.
31. **Home-screen shortcut:** the user picks a name and icon (pinned shortcut).
32. **Theme: Sage.** Nunito type with a sage-green accent, following the phone's light/dark setting.
    The Instagram feed keeps Instagram's own colours.
33a. **Throwback settings ("portal") has no button on the feed** (decided 2026-11-20); the feed looks
    exactly like Instagram. Ways in:
    1. **Profile page:** Throwback's icon at the top right of your own profile, replacing the Threads icon.
    2. **Instagram's settings page:** a "Throwback" section right after "Your account".
    3. **Long-press the Home tab** in the bottom bar (a normal tap still goes home).
    4. **Long-press the Throwback app icon** → "Throwback settings" (launcher shortcut).
33. **Mockups:** https://claude.ai/artifact/JVunY8kiNTP8abRP43LSGV (private to the owner).
