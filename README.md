# GETDOWN Textrator

Copy any text from any screen on Android. A see-through floating bubble sits over every app.
Tap it and every piece of text on screen is outlined, including text inside images.
Select what you need, tap **Copy**, and it's saved to your in-app history.

| Part | Where | What |
|---|---|---|
| Android app | `android/` | Kotlin, accessibility overlay bubble, on-device ML Kit OCR (Latin + Devanagari) |
| Backend | `supabase/` | Postgres tables + secure RPC functions (Supabase Auth for login) |
| Website + admin | `web/` | Landing page, APK download, privacy policy, admin dashboard (Vercel) |
| CI | `.github/workflows/` | Builds the APK on every push and publishes it as the latest GitHub Release |

## Two builds

| Build | File | Install from | How it reads the screen |
|---|---|---|---|
| **Go** (`go` flavour) | `GetDownTextrator.apk` | Website or any APK share | Overlay bubble + screen capture (approve once per session) + OCR |
| **Play Store** (`full` flavour) | `GetDownTextrator-PlayStore.aab` | Google Play | Accessibility: exact text + OCR, no capture prompt |

Why two: in India, Google Play Protect blocks **sideloaded** apps that use Accessibility (its "enhanced fraud protection").
Apps installed from Play are fine. See `docs/PLAY_STORE.md` for the submission pack.

## How it works

1. **Bubble.** `TractorService` (an AccessibilityService) draws the bubble as an *accessibility overlay*,
   so no separate "draw over other apps" permission is needed. It's see-through when idle and grows a
   little (with a brown dot) when the screen has a lot of text.
2. **Tap.** The bubble hides itself first so it's never in the capture. Then:
   - the **accessibility tree** gives exact text and positions for normal UI;
   - on **Android 11+** a screenshot is taken with `takeScreenshot()` (no MediaProjection consent popup each
     time) and read by **ML Kit on the phone**: free, offline, no API key;
   - both are merged: exact text wins, OCR only fills gaps (images, videos, games, apps that hide text).
3. **Select.** A transparent layer outlines each line exactly where it is. You can tap boxes, drag to select
   an area, or long-press a box to pick exact words. The bar has Select all, Edit, Share, Copy.
4. **Saved.** Every copy goes to the local history (the user's dashboard). The server only receives
   *counts* (capture or copy, number of characters, source app), **never the text**.

## Problems this code handles (from research on similar apps)

| Known problem | Fix in this code |
|---|---|
| MediaProjection asks for consent every session on Android 14+ | Uses the accessibility `takeScreenshot()` instead |
| Bubble appears in its own screenshot | Bubble goes INVISIBLE ~160 ms before capture |
| `takeScreenshot` only allowed ~1x/second | Rate limiter + one automatic retry |
| ML Kit crashes on HARDWARE bitmaps | Copied to ARGB_8888 first |
| Hindi comes out as garbage from the Latin model | Runs the Devanagari model too and prefers its lines |
| Banking/secure apps give black screenshots | Detected; falls back to accessibility text with a message |
| Overlay boxes offset by status bar / notch | Boxes drawn relative to the overlay's real on-screen position; cutout mode set |
| "Comparison method violates its general contract" when sorting | Stable bucketed sort key |
| Xiaomi/Oppo/Vivo kill the service | Battery-optimisation exemption + OEM Autostart screens in setup |
| Android 13+ blocks accessibility for sideloaded APKs ("Restricted setting") | Setup explains it and opens App info for "Allow restricted settings" |
| Google Play rejects accessibility apps without disclosure | Prominent disclosure + explicit consent before setup |
| New APK won't install over the old one ("App not installed") | Fixed signing key committed for sideload builds |
| Password fields | Never read (`isPassword` skipped) |
| Rotation leaves boxes in the wrong place | Selection closes on rotation; bubble re-clamps to screen |

## Login and the bypass

`AUTH_MODE` decides the login behaviour. The admin dashboard can change it at any time without an app update:

- `optional` (default): sign in, or **Continue without login**
- `required`: guests must create an account
- `off`: no login screen (full bypass)

If the app is built without Supabase keys, it skips login completely and works offline.

## Setup

1. **Supabase:** create a project and run `supabase/migrations/001_text_tractor.sql` in its SQL editor.
   The admin list starts with `contact@getdownaf.info`. Create that account on `/admin` and confirm the email.
2. **GitHub secrets** (Settings, then Secrets and variables, then Actions): `SUPABASE_URL`, `SUPABASE_ANON_KEY`.
   Optional Play Store key: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
3. **Vercel:** import this repo with root directory `web`, build command `node build.js`, output `dist`.
   Add the env vars `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
4. Push to `main`. Actions builds `GetDownTextrator.apk` and publishes it as the latest release.
   The website's download button always points at the newest one.

## Logo

The app uses the vector placeholder in `android/app/src/main/res/drawable/ic_logo.xml` and `web/logo.png`.
To use the real logo, replace those two files (and `ic_launcher_fg.xml`).
