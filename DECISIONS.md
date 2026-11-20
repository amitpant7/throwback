# Throwback: decisions

The product decisions agreed during planning (2026-11-20). Change a decision here first, then in code.

## Product

1. **What it is:** a free Android app that shows Instagram "the way it used to be": only people you
   follow (with stories), no Reels, no Explore grid, no suggested posts, no ads.
2. **Name** (owner, 2026-11-20): **"Throwback: 2019 Mode"** on the Play Store (colon, the usual
   "name: what it is" pattern; 20 of Play's 30 characters). Under the icon it is just "Throwback"
   (launchers cut longer names). The logo is still a placeholder.
3. **Repository:** public on GitHub (github.com/amitpant7/throwback). **Licence: PolyForm Strict 1.0.0**
   (owner, 2026-11-20, replacing GPL-3.0): personal and other non-commercial use only, no
   redistribution, no published changes. Source-available, not open source (so no F-Droid). The
   repo was public under GPL-3.0 for about an hour on 2026-11-20; copies from then keep GPL rights.
4. **Money:** free, with optional tips through Google Play Billing (no paid features). The tip tiers in
   the mockup are Beer / Rock show / GTA 6. Beer and GTA 6 have original icons; Rock show uses a prism
   image supplied by the owner (source and licence to confirm before release). Before the Play
   release, recheck using third-party names as product names.
4a. **Rating ask** (owner, 2026-11-20): once, through Google Play's own in-app review card, at the
    moment "You're all caught up" shows, once the app is at least 7 days old and was used on 5
    different days. Play decides whether the card actually appears; no "do you like it?" pre-question
    (Play policy). Builds not from Play do nothing.
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
13i. **Explore, 5 minutes a day** (decided 2026-11-20): the Explore grid on the Search tab shows for
    5 minutes a day of time actually on that tab (rules `explore.dailySeconds`), then it is hidden
    as before and "Enough for today… Your time is yours. Keep it. Go have an argument with your sibling instead." shows in its
    place until midnight. Search keeps working. Stored on the device per day.
    **Reels there play as reels** (owner, 2026-11-20): Instagram's mobile site has no single-reel
    player (a reel tile opens as a static post, even with Throwback off). So while the minutes last,
    tapping a reel tile on Explore or in search opens Throwback's own full-screen swipe player with
    the reels from that page, in the order shown. It uses the video, username and caption Explore
    already loaded; no extra logged-in requests. Shows "m:ss left today"; tap = sound on/off; ✕ or
    Back closes it; "Open post" shows the normal post (likes, comments, profile). The time counts
    toward the 5 minutes, and at 0:00 it closes onto "Enough for today". Only 3 videos are loaded
    at a time.
    **Side bar like Instagram's** (owner, 2026-11-20): ♥ (and double-tap) and 🔖 save work by
    rendering the post's own page under the player (Instagram's router, no reload) and pressing
    Instagram's own button there, so Instagram's code sends the request and Throwback sends none
    (labels in rules `explore.actions`, fixable remotely); the icon changes at once and goes back if
    the button can't be found. 💬 opens the post's comments (Back returns to the player at that reel),
    ↗ opens Android's share sheet with the reel link, the username opens the profile.
13b. **A little discovery** (decided 2026-11-20): of Instagram's suggested posts (never ads), a few are
    shown, tagged "Discover · not someone you follow". Per 5 minutes of time actually spent on the feed:
    at most 1 video/reel and 1 photo post; at most 20 a day; only after 8 posts from people you follow
    since the previous one; never after "You're all caught up". Always on, no setting.
13d. **Filter the feed data, not the drawn page** (decided 2026-11-20): ads and suggestions are removed
    from Instagram's feed data before the page draws them (no flicker, exact end); as a safety net,
    home-feed posts stay hidden until checked. No extra requests to Instagram. See HANDOFF 5c.
13e. **Warm-up** (decided 2026-11-20): Throwback remembers which of Instagram's public code files
    messages and the story viewer use, and prefetches them when the feed is idle on later starts
    (skipped with Data Saver; no logged-in requests). Measured: inbox 1.0 s → 0.88 s, 0 files
    downloaded; repeat warm-ups come from cache.
13c. **Feed bottom** (decided 2026-11-20): never blank space. "You're all caught up" appears only when only
    suggestions are left, not at Instagram's own "seen all new posts" point (owner confirmed
    2026-11-20; rules `feedData.endAtMarker` = false). Gaps between waves of followed posts show
    Instagram's loading spinner; "You're all caught up" only when only suggestions are left (20 hidden
    in a row, or nothing new for 10 s at the bottom). The next posts are loaded ahead for smooth scrolling.
13f. **Reels sent in chats** (decided 2026-11-20): a reel opened from a chat plays in Instagram's
    full-screen swipe viewer (same address, no history entry). The opened reel is free. Swipes to
    other reels are counted (per 10 minutes, across viewers, rules `reelViewer`); the poster shows on
    a random 1st to 4th swipe, so no number of swipes is safe. Then the videos stop, the page closes
    the viewer with its own Close button, and a full-screen poster shows over the chat ("Thought
    you could cheat…? Nahhh…"). No button (Instagram's reel viewer has none either): Back or the back
    swipe removes the poster, leaving you in the chat. After a poster, no free swipes for an hour
    (each new poster restarts the hour), stored on the device so a restart doesn't reset it. The
    poster's final design and an animated version with licensed music are in design.
13g. **Stories** (decided 2026-11-20): story ads are removed from Instagram's data (key
    `xdt_injected_story_units`). Look-ahead to avoid the dark screen between stories: the first
    400 KB (plus a little audio) of the next 5 story videos (owner: 5 is the sweet spot) are kept in memory and handed to the
    player; photo stories ahead go to the browser's disk cache. The current story comes from the
    address (/stories/<user>/<id>/). Memory stays under ~3 MB and is cleared on leaving stories;
    off with Data Saver; no extra logged-in requests; nothing is marked seen (rules `stories`).
13h. **Feed loading** (decided 2026-11-20): Instagram's feed request asks for 12 entries (`first`);
    after filtering ~2-4 real posts remain per batch, ~3 s per batch, and Instagram already requests
    the next batch the moment one arrives (measured), so supply is set by its server, not by
    Throwback. Tried `first: 24`: accepted, but the server still returns 10 entries, so it's off
    again (rules feedData.pageSize = 0; the mechanism stays, unused). Not taken: switching the
    request's `variant` to "following" (3x real posts, but chronological instead of Instagram's
    ranking; owner keeps the ranking). Look-ahead: photos/thumbnails of the next 7 posts into the
    disk cache, start of the next 3 videos (400 KB + 64 KB audio) in memory, cleared off the feed.
13j. **One trusted clock** (owner, 2026-11-20): the daily limit and the page's daily counters
    (Explore minutes, Discover, chat-reel cooldown, Your day) use the same tamper-resistant time:
    the phone's since-boot stopwatch plus the last trusted time, and the time zone saved with it. A
    new time zone counts only from the next midnight, and a new day needs at least 23 h of real time
    after the last one began, so switching to a zone where it's already tomorrow gives nothing. The
    page never goes back a day. Still open: clock forward + reboot (the stopwatch restarts at boot).
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

25. The real Instagram app stays installed (its icon is not hidden, see #28), only for notifications
    and calls; the guard (#27) keeps the user out of it.
26. **Relay:** calls ring normally and are answered in the real app. Message notifications are replaced
    by Throwback's own, which open the Throwback inbox (or messages-only mode when locked). Other
    notification types are per-type toggles, all off by default.
27. **Guard:** opening the real Instagram app outside a call shows "Use Throwback instead" and returns
    the user to Throwback. During a call it is allowed. Throwback itself opens the app with a short
    pass in two cases: a chat's call button (the mobile website has no calls) and "Open Instagram for
    5 minutes" on the Messages & calls screen (switching accounts, Instagram's own settings).
28. A 2-step setup wizard: install Instagram (via the Play Store), then grant the permissions
    (notification access, usage access, display over other apps; Android 13+ also asks to allow
    notifications). No "hide the icon" step: the guard sends the user back to Throwback anyway, and
    "Remove it" covers uninstalling (decided 2026-11-20). "Quiet the Instagram app" is not a step but
    an optional tip on the Messages & calls screen ("For a quieter phone"), since the relay already
    removes everything except messages and calls (owner, 2026-11-20).
27a. **Passes into the real app are limited** (owner + red-team review, 2026-11-20).
    "Open Instagram for 5 minutes": once a day, not while the daily limit is reached, and the pass ends
    as soon as the user leaves Instagram (no saving it for later). The chat's 📞 button: a 30-second
    pass, at most 3 taps an hour; it ends once the call is running (the call itself then keeps
    Instagram allowed, and call audio during that pass counts as an Instagram call).
    Explore's 5 minutes also cover hashtag, place and keyword-result grids (every page under /explore/).
27b. **Turning the guard off follows the time-limit rule** (owner, 2026-11-20): while a daily limit
    is on, "Turn off Messages & calls" needs the PIN and takes effect the next day ("Keep it on"
    cancels it). Without a limit it is immediate (there is no PIN). If the limit itself is turned
    off, a scheduled guard turn-off becomes due with it. Not preventable in-app: force-stopping
    Throwback or revoking its permissions in Android settings (to look at: count the real app's time
    against the limit when Throwback next runs).
27c. **Posting through the Instagram app** (owner, 2026-11-20): with the app connected, "+" and
    "Your story" in Throwback open the app's create screen (link `instagram://share`, its better
    editor: music, filters, reels). The guard then follows the app's *screen*, not just the app: it
    allows only creation screens (activity class `instagram.features.creation.*`, rules `appCreate`),
    with 3 s of grace while it opens and at most 30 minutes. Any other screen (the feed after
    posting, Reels) ends the pass and the guard brings the user back. Calls work as before.
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
    2. **Instagram's settings page:** a "Throwback" section right after "Your account" (page path and
       heading texts in `rules.json` `settingsSection`, so it can be fixed remotely).
    3. **Long-press the Home tab** in the bottom bar (a normal tap still goes home).
    4. **Long-press the Throwback app icon** → "Throwback settings" (launcher shortcut).
33b. **One-time settings hint** (decided 2026-11-20): after setup, once the logged-in feed shows, a
    small card above the Home tab says long-press Home opens Throwback's settings (and mentions the
    profile icon and the section in Instagram's settings). Shown once; opening settings any other way
    also counts.
33c. **Your day** (owner, 2026-11-20): first row in Settings. Time on Instagram today (split into
    feed, stories, messages, Explore, profiles and posts), the daily limit left, Explore minutes left,
    reels-in-chats cooldown, Discover posts left, and what was removed today (ads, suggested posts,
    Instagram alerts). Counted on the phone only (page storage `__tb_day` + the app), reset at midnight.
33. **Mockups:** https://claude.ai/artifact/JVunY8kiNTP8abRP43LSGV (private to the owner).
