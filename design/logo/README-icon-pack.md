# App icon pack: "Falling backwards" logo

Brand mint: `#8FD1AD`. Every file is rendered from `master/logo.svg`.

## iOS: `ios/AppIcon.appiconset/`
- Delete the existing `AppIcon.appiconset` in `Assets.xcassets`, then drag this folder in its place.
- One 1024×1024 PNG with no transparency and square corners. iOS rounds the corners itself. Uses Xcode 14+ single-size format.
- **App Store Connect** uses the same 1024 image.

## Android: `android/res/`
- Copy the `mipmap-*` and `values` folders into `app/src/main/res/` and overwrite the old launcher icons.
- `AndroidManifest.xml` (usually already set):
  ```xml
  android:icon="@mipmap/ic_launcher"
  android:roundIcon="@mipmap/ic_launcher_round"
  ```
- Android 8+ uses the adaptive icon: the mint background plus `ic_launcher_foreground`. Older phones use `ic_launcher.png` / `ic_launcher_round.png`.
- If your project has `res/drawable/ic_launcher_background.xml` or `ic_launcher_foreground.xml` from the template, delete them so they don't clash.
- **Play Store listing:** upload `android/playstore-icon-512.png`.

## Web / PWA: `web/`
Copy the files to your site's public root and add to `<head>`:
```html
<link rel="icon" href="/favicon.ico" sizes="any">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="manifest" href="/site.webmanifest">
```

## In-app logo at 32 pt: `in-app/`
- `logo-32.png`, `logo-32@2x.png` (64 px) and `logo-32@3x.png` (96 px) for 32 pt on 1×/2×/3× screens.
  - iOS asset catalogue: drop all three into one image set.
  - React Native: keep the names and `require('./logo-32.png')`, which picks @2x/@3x automatically.
  - Flutter: put 64 px in `2.0x/` and 96 px in `3.0x/`.
- `logo.svg` if your app renders SVG.

## Master files: `master/`
- `logo.svg` / `logo-1024.png`: rounded mint tile, transparent corners
- `logo-square.svg` / `logo-square-1024.png`: full-bleed square, for platforms that apply their own mask
- `logo-mark-transparent.svg`: just the portal, arrow and figure, with no mint tile

## Not included
An Android 13 themed (monochrome) icon. It's optional, and phones fall back to the normal icon.
