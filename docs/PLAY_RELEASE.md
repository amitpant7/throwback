# Releasing on Google Play

The steps in order, with drafts for every form. Google's rules change yearly; check the linked
help pages when filling them in. Prepared 2026-11-21.

## 1. Developer account (owner)

- Play Console account: a one-time $25 fee and an identity check.
- A **personal** account created after 13 November 2023 must run a **closed test with at least 12
  testers, opted in for 14 days in a row**, before it can apply for production
  ([Google](https://support.google.com/googleplay/android-developer/answer/14151465)). An
  organisation account skips this but needs a D-U-N-S number.
- For tips: a **payments profile** (merchant account). Your country must be on Google's list of
  merchant locations.

## 2. Upload key (owner, once)

Google signs the app for users (Play App Signing); you sign uploads with your own upload key.

```bash
keytool -genkeypair -keystore ~/keys/throwback-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

Keep the `.jks` file and its password in a password manager and a backup; never commit them
(`.gitignore` excludes `*.jks` and `keystore.properties`). A lost upload key can be replaced
through Play Console support, because Google holds the app signing key.

Then create `keystore.properties` in the repository root (git-ignored):

```properties
storeFile=/home/you/keys/throwback-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

## 3. Build

```bash
./gradlew testDebugUnitTest lintDebug bundleRelease
# upload: app/build/outputs/bundle/release/app-release.aab
```

Raise `versionCode` (and `versionName`) in `app/build.gradle.kts` for every upload. The app targets
Android 16 (API 36), which Play requires since 31 August 2026.

## 4. Create the app in Play Console

Free app, name **Throwback**, default language English. Category: Social (or Productivity).

## 5. App content (Policy → App content)

| Form | Answer |
|---|---|
| Privacy policy | `https://amitpant.com.np/throwback/privacy/` (the page must be live) |
| Ads | No ads |
| App access | Some functionality is restricted: everything needs an Instagram login. Give the reviewers a **test Instagram account made for review** (never your own) and the steps below |
| Content rating | IARC questionnaire: no violence, gambling and so on; **users can interact** (Instagram's messages and comments) |
| Target audience | 18 and over is the simplest (13–17 adds the Families requirements); Instagram itself is 13+ |
| News app | No |
| Data safety | See below: **your decision** |
| Foreground service | Special use: text below, plus a demo video |
| Financial features, health, government | None |

**Reviewer steps (App access):** "Open Throwback, tap 'Log in to Instagram' and sign in with the test
account. The feed shows only followed accounts; Reels and the Explore grid are removed. Settings:
long-press the Home tab. The Instagram app connection (Settings → Connect → Instagram) is optional
and needs the Instagram app installed."

**Data safety: decide before submitting.** Throwback has no server and never receives anything, but
Google counts data sent through a web view **the app controls** (unless the user browses the open
web) ([Google](https://support.google.com/googleplay/android-developer/answer/10787469)). Throwback's
web view shows only instagram.com, with its own script, so the login, messages, photos and posts the
user sends to Instagram may count. Two answers:

- **Conservative (fewer surprises in review):** declare what Instagram receives through the app as
  *collected*, for *App functionality*: personal info (name, email, user IDs), messages, photos and
  videos, app activity (interactions, user content). Not *shared* (the user starts each transfer and
  expects it). Encrypted in transit: yes (HTTPS). No deletion request through Throwback (there is no
  Throwback account; Instagram's own settings delete Instagram data).
- **Plain:** "No data collected or shared": true for Throwback itself, but a reviewer may read the web
  view rule the other way.

Either way: tips go through Google Play Billing; notification text and app usage are read on the
phone only and never leave it.

**Foreground service (special use)**, in the App content declaration:

- *Description:* "Digital wellbeing. When the user connects the Instagram app to Throwback (Settings →
  Connect → Instagram), a foreground service watches which app is in front and returns the user to
  Throwback when the Instagram app is opened outside a call or posting. It shows a permanent
  notification ('Throwback is protecting you') and stops when the user disconnects."
- *If not allowed:* "The Instagram app could be opened freely, which defeats the purpose the user
  chose: Throwback's time limit and filters would no longer apply."
- *Video* (about 30 s, screen recording): Settings → Connect → Instagram finished; the notification in
  the shade; open the Instagram app from the launcher; Throwback covers it and returns; a call still
  rings and opens.

The other sensitive permissions (notification access, usage access, display over other apps) have no
Play form, but Google's User Data policy needs a clear explanation before each: the setup's step 2
gives one per permission.

## 6. Store listing (Grow → Store presence → Main store listing)

- **Name:** Throwback
- **Short description** (62 of 80): Only the people you follow. No Reels, no Explore grid, no ads.
- **Full description** (draft):

  > Throwback shows Instagram the way it used to be: the people you follow, without the parts built to
  > keep you scrolling.
  >
  > Removed: Reels, the Explore grid, suggested posts and ads. Kept: your feed, stories, profiles,
  > search and messages.
  >
  > Focus, if you want it:
  > • A daily time limit with a PIN; messages stay open when it's up, and calls never count
  > • A short pause before Instagram opens
  > • "Your day": time spent and what was removed
  >
  > Optional: keep message alerts and calls. Connect the Instagram app and Throwback passes on its
  > message notifications and keeps you out of the app outside calls and posting.
  >
  > Private: no account, no tracking, no analytics. You log in on Instagram's own page; Throwback never
  > sees your password. The code is public on GitHub.
  >
  > Free. Tips are optional and unlock Make It Yours, your own name and icon on the home screen.
  >
  > Throwback is not affiliated with Instagram or Meta.

- **Graphics:** icon 512 × 512 (`design/logo`), feature graphic 1024 × 500, at least 2 phone
  screenshots (Throwback's own screens and a feed with ordinary accounts; **no Instagram logo** in
  the icon or graphics).
- **Contact:** the website's contact page and an email address.

**Risk to know:** Google removes apps that are mostly a web view of someone else's site
([Google, 2020](https://android-developers.googleblog.com/2020/10/developer-tips-and-guides-common-policy.html)).
Throwback's own features (time limit, messages, guard, Your day) are its case; lead with them, keep
"Instagram" out of the name and icon, and keep the "not affiliated" line.

## 7. Tips (Monetize → Products → One-time products)

| Product ID | Name | Price |
|---|---|---|
| `tip_small` | A beer | $3 (India ₹99) |
| `tip_medium` | A gig ticket | $10 (India ₹299) |
| `tip_large` | GTA 6 | $25 (India ₹799) |

The IDs must match `billing/TipRules.kt` exactly and can never be reused. Test with a **license
tester** (Setup → License testing) on a testing track: buy, see "Make It Yours is unlocked",
reinstall, and check it unlocks again.

## 8. Testing tracks and production

1. Internal testing: upload the AAB, add yourself, install from the Play link, run the on-device
   checklist (docs/HANDOFF.md, 7) and a test tip.
2. Closed testing: 12+ testers for 14 days in a row (personal accounts).
3. Apply for production access (Dashboard), answering Google's questions about the test.
4. Production: start with a staged rollout (for example 20%), then 100%.

After publishing: put the Play link on the website (`id="play-link"`, see the website README).
