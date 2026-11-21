# Throwback: decisions

The product decisions agreed during planning (2026-11-20). Change a decision here first, then in code.

## Product

1. **What it is:** a free Android app that shows Instagram "the way it used to be": only people you
   follow (with stories), no Reels, no Explore grid, no suggested posts, no ads.
2. **Name** (owner, 2026-11-20): **"Throwback: 2019 Mode"** on the Play Store (colon, the usual
   "name: what it is" pattern; 20 of Play's 30 characters). Under the icon it is just "Throwback"
   (launchers cut longer names). **Logo** (owner, 2026-11-20): "Falling backwards": a figure
   falling backwards in a night-sky portal formed by a rewind arrow, on **Emerald #36A56E** (owner's pick from samples, 2026-11-20; the icon pack's mint was #8FD1AD). Masters in
   `design/logo/`; the launcher (adaptive + legacy + round), splash, in-app logo, guard cover, the
   home-screen shortcut presets (#31) and the white notification icon (made from the mark) all use it.
   The "Throwback settings" launcher shortcut shows a white gear on the Emerald (2026-11-21).
3. **Repository:** public on GitHub (github.com/amitpant7/throwback). **Licence: PolyForm Strict 1.0.0**
   (owner, 2026-11-20, replacing GPL-3.0): personal and other non-commercial use only, no
   redistribution, no published changes. Source-available, not open source (so no F-Droid). The
   repo was public under GPL-3.0 for about an hour on 2026-11-20; copies from then keep GPL rights.
4. **Money:** free, with optional tips through Google Play Billing (no paid features; one thank-you
   unlock, #4d). The tip tiers are A beer / A gig ticket / GTA 6 (names: owner, 2026-11-21; GTA 6
   for now). A beer and GTA 6 have original icons; A gig ticket uses a prism
   image supplied by the owner (source and licence to confirm before release). Before the Play
   release, recheck using third-party names as product names.
4a. **Rating ask** (owner, 2026-11-20): **once, after a month, never again.** Google Play's own in-app
    review card, at "You're all caught up", once the app is at least 30 days old and was used on 10
    different days. Google may choose not to show it; there is no second try and no "do you like it?"
    question first (Play policy). Builds not from Play do nothing.
4b. **Support screen: design A "list rows"** (owner, 2026-11-20; mockups row 5): one row per tip with
    the existing icons, a one-liner and the price: A beer **$3** ("A cold one, on you"), A gig ticket
    **$10** ("Toward the next show"), GTA 6 **$25** ("Toward the next game"; "big" dropped, owner 2026-11-21). **No "Whatever you
    want"** (owner, 2026-11-21): Google Play can only charge prices set up in advance, so a typed
    amount would have needed one product per price. Play products `tip_small`, `tip_medium`,
    `tip_large` (named by size, so a renamed tip keeps its product); India priced by hand (₹99 / ₹299 /
    ₹799 suggested). Prices shown from Google Play in the user's currency (the dollar amounts until it
    answers). **"GTA 6" stays** (owner, 2026-11-21: change it if Google flags it). Fine print "Paid through Google Play", then
    "How tips work", "Report something Throwback missed" and "Say Hi on Instagram · @97m.it" (the
    developer's profile, opened in Throwback; owner, 2026-11-21). The intro says what a tip gets:
    Make It Yours (#4d). The prism image's source and licence stay on the release list.
4c. **Support ask** (owner, 2026-11-20, design C with boxes): in "You're all caught up", after 1 month
    of use, then every 2 months, never once the user has tipped: "Your first month" (later "Your last 2
    months"), two minimal boxes with the ads and suggested posts removed since the last ask, and
    "Say thanks →" (opens Support). Skipped when fewer than 20 were removed. The card can be built
    while still screens away; the support line and the rating ask (#4a) wait until it is on screen.
4d. **One tip unlocks Make It Yours** (owner, 2026-11-21): any tip, once, unlocks the whole Make It
    Yours screen (#31) for good. Before that it can be tried out; its button leads to Support. The
    flag is kept on the phone and in Auto Backup (`core/Supporter`). Until billing is set up nobody
    can unlock it (test builds can).
    **With billing (plan, 2026-11-21):** Google Play remembers it, so a reinstall or a new phone on the
    same Google account unlocks again without the backup. A tip isn't consumed right away: the latest
    purchase of each tip product stays owned (acknowledged), and is consumed only just before the
    same product is bought again, so one stays on record. At start the app asks Play for owned tips
    (queryPurchasesAsync) and sets the flag. A refunded tip leaves the flag as it is. **Built
    2026-11-21** (`billing/`, Play Billing 9.1); a pending payment unlocks once Google Play has it.
5. **Privacy:** no analytics, no accounts, no server. Nothing leaves the phone except traffic to
   Instagram itself and a daily fetch of the public filter rules.
6. **Disclaimer:** "Not affiliated with Instagram or Meta" on the store listing and the About screen.
   No Instagram name or logo in the app's branding.
7. **Releases: Google Play only** (owner, 2026-11-21): tips and Make It Yours need Google Play billing,
   and Android 13+ makes downloaded APKs ask for "restricted settings" (#28). No APKs on GitHub
   Releases; the code stays public there.

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
13b. **A little discovery** (decided 2026-11-20): of Instagram's suggested posts (never ads), a few are
    shown, tagged "Discover · not someone you follow". Per 5 minutes of time actually spent on the feed:
    at most 1 video/reel and 1 photo post; at most 20 a day; only after 8 posts from people you follow
    since the previous one; never after "You're all caught up". Always on, no setting.
13c. **Feed bottom** (decided 2026-11-20): never blank space. "You're all caught up" appears only when only
    suggestions are left, not at Instagram's own "seen all new posts" point (owner confirmed
    2026-11-20; rules `feedData.endAtMarker` = false). Gaps between waves of followed posts show
    Instagram's loading spinner; "You're all caught up" only when only suggestions are left: the data
    filter ends the feed after 3 data pages in a row without a followed post (rules
    `feedData.endAfterEmptyPages`); the page's own fallback is 20 hidden in a row, or nothing new for
    10 s at the bottom. The next posts are loaded ahead for smooth scrolling.
13d. **Filter the feed data, not the drawn page** (decided 2026-11-20): ads and suggestions are removed
    from Instagram's feed data before the page draws them (no flicker, exact end); as a safety net,
    home-feed posts stay hidden until checked. No extra requests to Instagram. See HANDOFF §6.
13e. **Warm-up** (decided 2026-11-20): Throwback remembers which of Instagram's public code files
    messages and the story viewer use, and prefetches them when the feed is idle on later starts
    (skipped with Data Saver; no logged-in requests). Measured: inbox 1.0 s → 0.88 s, 0 files
    downloaded; repeat warm-ups come from cache.
13f. **Reels sent in chats** (decided 2026-11-20): a reel opened from a chat plays in Instagram's
    full-screen swipe viewer (same address, no history entry). The opened reel is free. Swipes to
    other reels are counted (per 10 minutes, across viewers, rules `reelViewer`); the poster shows on
    a random 1st to 4th swipe, so no number of swipes is safe. Then the videos stop, the page closes
    the viewer with its own Close button, and a full-screen poster shows over the chat ("Thought
    you could cheat…? Nahhh…"). No button (Instagram's reel viewer has none either): Back or the back
    swipe removes the poster, leaving you in the chat. After a poster, no free swipes for an hour
    (each new poster restarts the hour), stored on the device so a restart doesn't reset it. The
    poster's final design and an animated version with licensed music are in design. Counted in
    chats only (2026-11-20 review: other video plays skip the check).
13g. **Stories** (decided 2026-11-20): story ads are removed from Instagram's data (key
    `xdt_injected_story_units`). Look-ahead to avoid the dark screen between stories: the first
    400 KB (plus a little audio) of the next 5 story videos (owner: 5 is the sweet spot) are kept in memory and handed to the
    player; photo stories ahead go to the browser's disk cache. The current story comes from the
    address (/stories/<user>/<id>/). Memory stays under ~3 MB and is cleared on leaving stories;
    off with Data Saver; no extra logged-in requests; nothing is marked seen (rules `stories`).
    Each piece is handed over from memory once (2026-11-20): Instagram's player can ask for the same
    piece again and again, and answered from memory at once that froze the page; a repeat ask goes
    to the network. The same applies to the feed's video look-ahead (#13h).
13h. **Feed loading** (decided 2026-11-20): Instagram's feed request asks for 12 entries (`first`);
    after filtering ~2-4 real posts remain per batch, ~3 s per batch, and Instagram already requests
    the next batch the moment one arrives (measured), so supply is set by its server, not by
    Throwback. Tried `first: 24`: accepted, but the server still returns 10 entries, so it's off
    again (rules feedData.pageSize = 0; the mechanism stays, unused). Not taken: switching the
    request's `variant` to "following" (3x real posts, but chronological instead of Instagram's
    ranking; owner keeps the ranking). Look-ahead: photos/thumbnails of the next 7 posts into the
    disk cache, start of the next 3 videos (400 KB + 64 KB audio) in memory, cleared off the feed.
13i. **Explore, 5 minutes a day** (decided 2026-11-20): the Explore grid on the Search tab shows for
    5 minutes a day of time actually on that tab (rules `explore.dailySeconds`), then it is hidden
    as before and "Enough for today… Your time is yours. Keep it. Go have an argument with your sibling instead." shows in its
    place until midnight. Search keeps working, and typing a search doesn't use the minutes (they
    count on the grid pages and in the reel player). Stored on the device per day. **Design B** (owner,
    2026-11-20): a calm scene above the lines, one picked at random each time from five drawn scenes
    (sunset hills, misty lake, moonlit sea, aurora, dunes) plus photos listed in rules
    `explore.images`, which must be served from Throwback's own GitHub repo (CC0 or own art).
    **Reels there play as reels** (owner, 2026-11-20): Instagram's mobile site has no single-reel
    player (a reel tile opens as a static post, even with Throwback off). So while the minutes last,
    tapping a reel tile on Explore or in search opens Throwback's own full-screen swipe player with
    the reels from that page, in the order shown. It uses the video, username and caption Explore
    already loaded; no extra logged-in requests. Shows "m:ss left today"; tap = sound on/off; ✕ or
    Back closes it. The time counts
    toward the 5 minutes, and at 0:00 it closes onto "Enough for today". The caption shows two lines;
    a tap shows all of it (#15). Only 3 videos are loaded
    at a time.
    **Side bar like Instagram's** (owner, 2026-11-20): ♥ (and double-tap) and 🔖 save work by
    rendering the post's own page under the player (Instagram's router, no reload) and pressing
    Instagram's own button there, so Instagram's code sends the request and Throwback sends none
    (labels in rules `explore.actions`, fixable remotely); the icon changes at once and goes back if
    the button can't be found. 💬 opens the post's comments (Back returns to the player at that reel),
    ↗ opens Android's share sheet with the reel link, the username opens the profile.
13j. **One trusted clock** (owner, 2026-11-20): the daily limit and the page's daily counters
    (Explore minutes, Discover, chat-reel cooldown, Your day) use the same tamper-resistant time:
    the phone's since-boot stopwatch plus the last trusted time, and the time zone saved with it. A
    new time zone counts only from the next midnight, and a new day needs at least 23 h of real time
    after the last one began, so switching to a zone where it's already tomorrow gives nothing. The
    page never goes back a day. The page anchors the app's clock on its own monotonic clock and only
    uses a fresh one (read less than 10 minutes before); without one the day doesn't move on, so
    changing the phone's date while the page is open changes nothing (2026-11-20 review). Clock
    forward + reboot: network time is preferred (Android's own on 13+; on any version also
    Instagram's server time, from the Date of Instagram's own responses the page already receives:
    no extra request), and after a reboot without it the trusted time continues from the last one
    until it arrives (never ahead of the phone's clock: a backup restored onto another phone). Closed
    on all versions (2026-11-21). A delayed day's start carries over, so the next day too is at
    least 23 h later (2026-11-21 review).
13k. **Suggested people stay** (owner, 2026-11-21): "Suggested for you" lists of accounts (on the
    notifications page, on profiles, in the home feed and on the Discover people page), and profiles
    of people you don't follow, are shown as Instagram shows them (#34a), Tagged tab included (#34n).
    Discover people doesn't use Explore's minutes. Only suggested posts in the feed are filtered
    (13b, 13d).
14. **No toggles.** Throwback is minimal; there is no setting to bring distractions back.
    Settings shows a read-only "Throwback removes" list.
15. Video captions and descriptions must never be hidden (a known complaint about a competitor). Where
    space is short (the Explore reel player) they fold to two lines and a tap shows them whole.

## Daily time limit (optional, off by default)

16. The user picks a daily limit (15/30/45/60 min) and sets a 4-digit PIN.
17. When time is up, Throwback locks.
18. **Extensions:** wait 5 minutes, then enter the PIN for +5 minutes. This works twice a day, with
    the wait before each one. After that it stays locked until local midnight. There is no full-day unlock.
19. **Messages-only mode while locked:** the inbox and chats only. Time there does not count.
20. **Calls** never count and are never blocked: also not in the time counted after the guard was off
    (#27b): Instagram's call screens are left out of it.
21. **Anti-cheat:** turning the limit off, raising it, or changing the PIN needs the PIN and takes
    effect the next day. Lowering the limit is immediate. Changing the phone's clock does not reset usage.
22. **Forgot PIN:** a 3-day countdown, then a new PIN can be set. The countdown can be cancelled.
    During the wait the limit stays on and extensions aren't possible.
23. **Reinstall / clear data:** onboarding says honestly that Throwback is "a speed bump, not a cage".
    The time-limit settings (limit, PIN hash, extension state, pending PIN reset) and whether the user
    has tipped (#4d) are included in Android Auto Backup, so a reinstall restores them. Instagram login and WebView data are never
    backed up. "Clear data" stays the emergency exit.
24. The PIN is stored only as a salted PBKDF2 hash. The setup screen says "Write it down somewhere
    you won't see every day."

## Instagram app (optional; was "Messages & calls", renamed 2026-11-20 when posting was added)

25. The real Instagram app stays installed (its icon is not hidden, see #28), only for notifications,
    calls, posting and its own settings; the guard (#27) keeps the user out of the rest.
26. **Relay:** calls ring normally and are answered in the real app. Message notifications are replaced
    by Throwback's own, which open the Throwback inbox (or messages-only mode when locked). Other
    notification types are per-type toggles, all off by default, and not shown while the daily limit
    is reached. Instagram's security and login alerts always come through.
27. **Guard:** opening the real Instagram app outside a call shows "Use Throwback instead" and returns
    the user to Throwback. During a call its call screen is allowed (#34b). While Android has
    Throwback's notification access disconnected (briefly, e.g. after an update) calls can't be seen,
    so the guard lets the Instagram app open, uncounted (owner, 2026-11-21: rare and brief, #34m).
    A phone call never unlocks Instagram on its own: call audio counts as Instagram's after its call
    notification, while its call screen shows, or when an internet call starts in Instagram
    (2026-11-21 review: a phone call answered from a pop-up during a pass unlocked all of Instagram). Throwback itself opens the app with a short
    pass in two cases: a chat's call button (the mobile website has no calls) and "Open Instagram for
    5 minutes" on the Instagram app screen (switching accounts, Instagram's own settings).
27a. **Passes into the real app are limited** (owner + red-team review, 2026-11-20).
    "Open Instagram for 5 minutes": once a day, not while the daily limit is reached, and the pass ends
    as soon as the user leaves Instagram (no saving it for later). The chat's 📞 button: a 15-second (owner, 2026-11-21: 30 s was too long)
    pass, at most 15 taps an hour (owner, 2026-11-21: 3 was too few, then 10); it ends once the call is running (the call then keeps its call
    screen allowed). "Open Instagram for 5 minutes" stays (owner, 2026-11-21, #34k). Call audio counts as an Instagram call only after an Instagram call
    notification, or when it starts while Instagram is in front (2026-11-20 review: any phone call
    used to count). All passes are counted on the time limit's trusted clock, so changing the
    phone's date or time zone gives no extra passes (2026-11-20 review).
    Explore's 5 minutes also cover hashtag, place and keyword-result grids (every page under /explore/).
27b. **Turning the guard off follows the time-limit rule** (owner, 2026-11-20): while a daily limit
    is on, "Disconnect the Instagram app" needs the PIN and takes effect the next day ("Keep it on"
    cancels it). Without a limit it is immediate (there is no PIN). If the limit itself is turned
    off, a scheduled guard turn-off becomes due with it. Not preventable in-app: force-stopping
    Throwback or revoking its permissions in Android settings. Since 2026-11-20, the real app's time
    while the guard was stopped is counted against the daily limit when Throwback next runs (call
    screens excepted, #20), and a notification says when the guard is off: Android stopped it and it
    can't restart itself, or a permission it needs was turned off (2026-11-21).
27c. **Posting through the Instagram app** (owner, 2026-11-20): with the app connected, "+" and
    "Your story" in Throwback open the app's create screen (link `instagram://share`, its better
    editor: music, filters, reels). The guard then follows the app's *screen*, not just the app: it
    allows only creation screens (activity class `instagram.features.creation.*`, rules `appCreate`),
    with 3 s of grace while it opens and at most 20 minutes, 10 a day, not while the daily limit is
    reached (2026-11-20 review). Any other screen (the feed after posting, Reels) ends the pass and the
    guard brings the user back. Calls work as before.
27d. **Name and place** (owner, 2026-11-20): Settings section **"Connect"** (first, above Focus), row
    **"Instagram · Calls, message alerts, posting · Connected"**; screen title "Instagram", setup
    "Connect Instagram", turn-off "Disconnect Instagram".
27e. **Instagram app settings** (owner, 2026-11-20): with Instagram connected, a row at the very end
    of Instagram's settings page, "Open settings in the Instagram app", opens the app's Settings and
    activity (`instagram://settings`). Settings screens (`com.instagram.urlhandlers.settings.*`): at
    most 10 minutes, 5 a day, not while the daily limit is reached (2026-11-20 review: Saved and
    Archive live inside that screen). Instagram's main screen (the feed; Back out of settings lands there, and Accounts Center opens there too) is never allowed during this pass, after the first 3 seconds while the screen opens (owner, 2026-11-20: Back to the feed was a loophole); Accounts Center is on Instagram's website.
    Leaving Instagram ends the pass (pickers and Chrome's in-app pages excepted). Rules `appSettings`.
27f. **"Your story" through the Instagram app** (owner, 2026-11-20): Instagram's story camera, gallery
    and editor share the app's main screen with the feed, and its story link lands on the feed. So
    "Your story" opens the app with a hint ("Swipe right for the story camera") and gives **5 minutes**
    of Instagram (rules `appCreate.story.editMinutes`), not counting time while the phone's camera is
    on (Android reports camera use to every app: confirmed on the owner's phone). Instagram's posting
    notification or leaving the app ends it; at most 3 a day. Throwback can't swipe for the user
    (that would need the accessibility permission, avoided). The top bar's "+" opens the post/reel
    editor (its own screen, no time window). **Wait between story passes: 30 minutes** (rules
    `cooldownMinutes`), at most 3 a day (`perDay`); meanwhile "Your story" uses Instagram's website story
    upload in Throwback. A pass that ended with a post (Instagram's posting notification) starts no wait
    and doesn't count (owner: a real upload means no cheating). One left early counts nothing only if
    at most 30 seconds of it were spent outside the camera (a change of mind), and even then a
    10-minute wait follows (2026-11-20 review: leaving within 3 minutes allowed endless 3-minute
    visits to the whole app).
28. A 2-step setup wizard: install Instagram (via the Play Store), then grant the permissions
    (notification access, usage access, display over other apps; Android 13+ also asks to allow
    notifications). No "hide the icon" step: the guard sends the user back to Throwback anyway, and
    "Remove it" covers uninstalling (decided 2026-11-20). "Quiet the Instagram app" is not a step but
    an optional tip on the Instagram app screen ("For a quieter phone"), since the relay already
    removes everything except messages and calls (owner, 2026-11-20).
29. **Instagram app found:** after first login, if the Instagram app is installed, Throwback offers
    "Connect Instagram to Throwback" (Recommended, #34g), "Remove it" (opens Android Settings) or
    "Decide later".

## Other

30. **Mindful pause:** **on by default** (owner, 2026-11-20: Instagram loads underneath, so it hides the
    page load), can be turned off in Settings. **4 seconds**, then "Almost there…" until the feed
    underneath is ready (at most 5 s more); no buttons (Back still leaves). 30 short
    original lines in a shuffled order that goes through all of them before repeating.
31. **Make It Yours** (owner, 2026-11-21; was "Add to home screen", then "Customize icon"): a home-screen shortcut with the
    user's own name and icon: the logo on the app colour, dark or light, a photo frame, or the user's
    own picture (cropped square, any picture; Throwback ships no Instagram name or logo, but the
    user's shortcut is theirs). Android doesn't let an app rename or re-icon itself, so it is a
    pinned shortcut; most launchers add a small Throwback badge to it. Unlocked by one tip (#4d).
32. **Theme: Sage.** Nunito type with a sage-green accent, following the phone's light/dark setting.
    The Instagram feed keeps Instagram's own colours.
33. **Mockups:** https://claude.ai/artifact/JVunY8kiNTP8abRP43LSGV (private to the owner). Brought in
    line with the app on 2026-11-21 (#34l); options not taken and dropped steps are labelled.
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
33c. **Your day** (owner, 2026-11-20): first row of Settings' Focus section (after Connect). Time on Instagram today (split into
    feed, stories, messages, Explore, profiles and posts), the daily limit left, Explore minutes left,
    Discover posts left (no reels-in-chats row: it added nothing, owner 2026-11-21), and what was removed today (ads, suggested posts,
    Instagram alerts); with the Instagram app connected also its story passes, the 5-minute visit and
    time in the app. Counted on the phone only (page storage `__tb_day` + the app), reset at midnight.
34. **Owner's answers of 2026-11-21** (multiple choice, from the reviews):
    a. **Suggested people show everywhere:** also the accounts block in the home feed and the
       Discover people page (`/explore/people`), as on notifications and profiles (#13k).
    b. **Calls unlock only the call screen:** during an Instagram call, the rest of the Instagram app
       is covered. If Throwback can't recognise the call screen (Instagram renamed it), the call is
       allowed as before, so a call is never cut off.
    c. **Daily limit + unconnected Instagram app:** right after a limit is turned on (each time), a
       skippable offer to connect the Instagram app so the limit covers it too.
    d. **Remote rules may only tap dismiss buttons** (Close, Not now, Decline, Cancel and the like,
       a fixed list in the app, `DismissLabels`): a rules file naming any other button is refused
       (the last good rules stay), and an element found by a rule's selector is pressed only when
       its label is one of those. The same for the Explore reel player's Like and Save labels (rules
       `explore.actions`, `ExploreActionLabels`).
    e. **The chat reel viewer's detection and its Close labels move into the rules**, so they can be
       fixed without an app update.
    f. **Clones:** the Connect › Instagram setup says that a second copy of Instagram (work profile,
       dual apps, Private Space) isn't covered.
    g. **After the first login, connecting the Instagram app is the recommended choice** (marked
       "Recommended"; "Decide later" stays at the bottom).
    h. **No budget for browsing profiles or suggested accounts:** not worth the complexity.
    i. **Licence stays PolyForm Strict** (people may read the code, not publish copies), so the app
       says "the code is public on GitHub", not "open source" (welcome screen; see t).
    j. **Releases on Google Play only** (#7).
    k. **"Open Instagram for 5 minutes" stays**, once a day (#27a).
    l. **The design canvas follows the app** (#33).
    m. **The notification-access gap stays** (#27): rare and brief.
    n. **A profile's Tagged tab shows** (#13k), like the rest of the profile.
    o. **The short lines stay** ("No Explore grid", "No suggested posts" on the welcome screen and in
       Settings): they give the spirit; Explore's 5 minutes and Discover are explained where they show.
    p. **"Report something Throwback missed" opens the website's contact form**
       (amitpant.com.np/throwback, live since 2026-11-21) instead of a GitHub issue (no account
       needed). About also links the website and its privacy page; "How tips work" its support section.
    q. **instagram.com in other browsers is out of scope** (Chrome or any other browser isn't
       limited): blocking it would need an accessibility service or a local VPN (Play policy,
       battery), which a speed bump doesn't justify.
    r. **Filter rules stay unsigned**, fetched over HTTPS from this repository. The validator already
       limits what a bad file can do (hosts, patterns, buttons the page may press, Reels and the
       feed stay blocked where they must); the GitHub account keeps two-factor login.
    s. **Code layout: dependencies go one way** (docs/ARCHITECTURE.md, "Code layers"). Moving the
       notification listener meant allowing Notification access once more after the update; accepted,
       since only test installs existed.
    t. **The welcome screen has a row about Throwback itself** under the list of what changes on
       Instagram: "Throwback collects nothing" (shield) and "Code is public" (`</>`). Not "no data
       leaves your phone": Instagram still gets your Instagram activity. Kept apart from the "No
       Reels" list so it doesn't read as if Instagram's own tracking were gone.
    u. **Connecting the Instagram app right after login returns to the feed** when the setup is
       finished (not to its "Ready" screen), and the "long-press Home for settings" tip still shows:
       only Settings opened at its home counts as "found" (the tip used to be skipped because the
       setup had opened Settings).
    v. **The setup's first step says that step 2 asks for permissions** (four on Android 13+, else
       three), and why.
    w. **No mindful pause on the very first launch** (owner, 2026-11-21): right after onboarding it
       stood between a new user and their first login; it starts with the next launch (#30).
    x. **"Back to your Instagram call"** (owner's bug report, 2026-11-21): minimising a call went to its
       chat, which the guard covers (call screens only, #34b), and left the user in Throwback with no
       way back to the call. While an Instagram call runs, a bar at the top of Throwback (over
       Settings and the lock screen too) opens the call screen again through Instagram's own call
       notification; it shows only when that notification is there to open.
    y. **Instagram calls recognised by their screen and sound** (owner's phone, 2026-11-21): there the
       call notification sits in the switched-off "Instagram" group and the audio mode stays normal,
       so a minimised call looked like plain Instagram. Instagram's call screen with voice-call sound
       (Android's sound label, never the sound) now counts as a call while that sound plays. The call
       button's pass lasts until the call screen is back (tapping call again during a call is the way
       back), and "Back to your Instagram call" uses that pass when there is no notification.
       15 call-button taps an hour.
    z. **During a call, its chat is allowed; a pass no longer opens the feed** (owner, 2026-11-21: "Back
       to your call" opened the chat, and Back from there let him scroll the feed). A minimised call
       shows its chat (ModalActivity) with Instagram's return-to-call banner: rules `appCall.chatScreens`
       allow it during a call next to the call screens, and the validator refuses any pattern that
       matches the feed's screen. During a call a pass counts only for its first 4 seconds (the way into
       the chat). "Back to your Instagram call" reopens the chat the call started from.
    aa. **The guard follows only the app in front** (owner, 2026-11-21: the cover kept coming back over
        the gallery after a call, though Instagram wasn't open). Split screen and floating windows
        aren't followed any more: the list of "visible" Instagram screens they needed went stale when a
        stop report was missed, and the cover then stepped aside and came back every few seconds over
        other apps. Now Instagram counts only while it is the app in front (the last one opened, until
        it pauses), the cover simply stays while it is, and there is no stepping aside or "close that
        window" text.
