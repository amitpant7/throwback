# Throwback privacy policy

Effective 21 November 2026. Throwback is an Android app by Amit Raj Pant. This is the same policy as
https://amitpant.com.np/throwback/privacy/.

## In short

**Throwback collects no personal data.** It has no accounts, no analytics, no ads and no server of its own. What it keeps, it keeps on your phone. The other services involved are listed below.

## Instagram

Throwback shows Instagram's website (instagram.com) inside the app and hides parts of it on your phone. You log in on Instagram's own page: Throwback never sees or stores your password. Your posts, messages and everything else you see go directly between your phone and Instagram, as in a web browser. Instagram's own [privacy policy](https://privacycenter.instagram.com/policy) applies to that. Instagram's login cookies are kept in the app's private storage on your phone.

## Kept on your phone only

- **Your settings** (for example the mindful pause and which notifications to pass on).
- **The daily time limit**, if you set one: the limit, a salted hash of your PIN (never the PIN itself) and today's usage.
- **"Your day"**: time spent today and how many ads and suggestions were removed. Counted on the phone, reset at midnight.
- **Small counters** for the app's own features (for example how much of today's Explore time is left), stored in the app's storage for instagram.com on your phone.

Android's own backup (to your Google account, if you have it on) includes only the time-limit settings and whether you have left a tip, so a reinstall keeps them. Nothing else is backed up.

## Optional: the connected Instagram app

If you connect the Instagram app (Settings → Connect → Instagram), Throwback asks for three Android permissions. Each is used only for the Instagram app:

- **Notification access**, to pass on Instagram's call and message notifications and remove the others. Throwback looks only at Instagram's notifications (Android hands a notification listener every app's notifications; Throwback ignores the others), shows them on your phone and keeps only daily counts.
- **Usage access**, to notice when the Instagram app opens, so it can be covered outside calls. Android reports which app is on screen; Throwback uses that only to tell when it's Instagram, and stores or sends none of it.
- **Display over other apps**, to show that cover.

For the story feature, Throwback notices whether a camera is in use (not what it sees). It does not use the camera, microphone, contacts or location itself.

## Other services

- **GitHub**: once a day the app downloads its filter rules (a public file) from GitHub, which sees an ordinary download request from your phone's internet address.
- **Google**: tips are paid through Google Play, which handles the payment; Throwback only learns that a tip went through. The rating card comes from Google Play too. On Android 13 and newer the app reads Android's network time, which keeps the daily limit honest; on older Android it uses the time in Instagram's own replies. Neither sends anything.
- **This website** is hosted by Netlify. If you use the contact form, your message and email address are stored by Netlify and used only to reply to you.

## Sharing and selling

Throwback shares no data with anyone and sells nothing. There is nothing to share: it doesn't collect any.

## Children

Throwback is for people who can use Instagram, which requires its users to be at least 13.

## Deleting your data

Uninstall the app, or clear its storage in Android's settings, and everything it kept is gone. To remove the backup, delete it in your Google account's backup settings.

## Changes and contact

If this policy changes, the new version will be on this page with a new date. Questions: use the [contact form](https://amitpant.com.np/throwback/#contact). The app's code is public on [GitHub](https://github.com/amitpant7/throwback), so you can check what it does.
