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
- Optional mindful pause before opening
- Your own home-screen name and icon

## Opening Throwback's settings

There's no Throwback button on the feed, so it looks exactly like Instagram. Open Throwback's settings
(Instagram app, Your day, time limit, mindful pause, shortcut, Support, About) any of these ways:

1. **Your profile:** the Throwback icon at the top right (where the Threads icon was).
2. **Instagram's settings:** profile → gear → the **Throwback** section, right after "Your account".
3. **Long-press the Home tab** in the bottom bar.
4. **Long-press the Throwback app icon** on your home screen → **Throwback settings**.

The first time the feed shows, a one-time hint points at the Home long-press.

## Building

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). In short: JDK 17, Android SDK 35, then
`./gradlew assembleDebug`.

## Why

<!-- Personal story to be written with the author before release. -->

## Licence

[PolyForm Strict 1.0.0](LICENSE): you may read the code and use it for personal, non-commercial purposes; you may not redistribute it or publish changed versions. Earlier copies were published under GPL-3.0 for a short time on 2026-11-20. The Nunito font is under the SIL Open Font License
([docs/fonts-OFL.txt](docs/fonts-OFL.txt)).
