# Throwback

Instagram, the way it used to be: just the people you follow, newest first. No Reels, no Explore
grid, no suggested posts, no ads. When you've seen it all, you're done.

Free and open source (GPL-3.0). No accounts, no tracking. Not affiliated with Instagram or Meta.

> Work in progress. The first test build is coming soon.

## Features

- Following feed, stories and messages, with the distractions removed (always on, no toggles)
- Optional daily time limit with a PIN, two 5-minute extensions a day, and a messages-only mode when locked
- Optional message and call alerts through a hidden copy of the Instagram app
- Optional mindful pause before opening
- Your own home-screen name and icon

## Building

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). In short: JDK 17, Android SDK 35, then
`./gradlew assembleDebug`.

## Why

<!-- Personal story to be written with the author before release. -->

## Licence

GPL-3.0, see [LICENSE](LICENSE). The Nunito font is under the SIL Open Font License
([docs/fonts-OFL.txt](docs/fonts-OFL.txt)).
