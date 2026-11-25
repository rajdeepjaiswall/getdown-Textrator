# Publishing GETDOWN Textrator on Google Play

Upload the **Play Store build** (`GetDownTextrator-PlayStore.aab` from the latest GitHub Release).
It uses Accessibility, which Google allows only after you fill in a declaration. Everything you need to paste is below.

## 1. One-time setup (about 30 minutes, plus Google's review)

1. Create a developer account at https://play.google.com/console ($25, one time). Use **Organization** if Getdown Foundation has a D-U-N-S number, otherwise **Personal**.
   - New **personal** accounts must run a **closed test with at least 12 testers for 14 days** before going to production. Collect 12 Gmail addresses (team, friends, customers).
2. **Create app:** name `GETDOWN Textrator`, default language English (India), App, Free.
3. **Play App Signing:** accept the default. Google holds the real signing key.
4. **Upload key (important):** the repo's `android/keystore/dev.keystore` is public, so don't use it for Play. Create a private one on any computer with Java:
   ```
   keytool -genkeypair -v -keystore upload.keystore -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```
   Then add these GitHub secrets (Repo, then Settings, then Secrets and variables, then Actions):
   `KEYSTORE_BASE64` (output of `base64 -w0 upload.keystore`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` = `upload`, `KEY_PASSWORD`.
   The next push builds an AAB signed with it. Keep `upload.keystore` safe (Google Drive and a pen drive).

## 2. Store listing

**Short description (80 chars max)**
> Copy any text from any screen: apps, images, videos. Tap the bubble, select, copy.

**Full description**
> GETDOWN Textrator puts a small floating bubble over your apps. Tap it on any screen and every piece of text lights up, even text inside photos, posters, videos and apps that don't let you copy. Pick what you need and tap Copy.
>
> • Works in Instagram, YouTube, WhatsApp, Chrome, PDFs, games and more
> • Reads Hindi and English, also inside images
> • Tap one box, drag across many, or long-press to pick exact words
> • Everything you copy is saved in your history: search, pin and copy again
> • Quick Settings tile for one-tap capture
> • Private: text is read on your phone, never uploaded
>
> GETDOWN Textrator uses the Accessibility Service API only to show the bubble and, when you tap it, to read the text on your screen so you can copy it. It does not control your phone, tap anything for you, or read passwords.

**Category:** Tools · **Contact email:** contact@getdownaf.info · **Website:** https://getdown-textrator.vercel.app
**Privacy policy URL:** https://getdown-textrator.vercel.app/privacy.html
**Graphics:** 512×512 icon (`web/logo.png` upscaled from the original logo file), 1024×500 feature graphic, and at least 2 phone screenshots (take them from the app).

## 3. App content → Accessibility API declaration (the important one)

- **Is your app an accessibility tool (IsAccessibilityTool)?** No.
- **Core functionality:** Universal copy. Users copy text from any app, including apps that block copying and text inside images.
- **Why Accessibility is needed (paste):**
  > The app's core feature is a floating bubble that lets users select and copy text from any screen. The AccessibilityService is used to (1) draw the bubble as an accessibility overlay, (2) when the user taps the bubble, read the text nodes currently on screen with their positions so the user can select and copy them, and (3) on Android 11+, take a screenshot with AccessibilityService.takeScreenshot() so on-device OCR can read text inside images. Nothing is read until the user taps the bubble or the Quick Settings tile. Screen content and screenshots are processed on the device and never transmitted. Password fields are skipped. The service performs no actions on other apps (no clicks, gestures, or text input).
- **Prominent disclosure:** built in. It's step 1 of the setup screen, with an "I agree" checkbox shown before the user is sent to Accessibility settings.
- **Video:** Google asks for a short YouTube or Drive link (unlisted is fine) showing the disclosure screen, then turning on Accessibility, then tapping the bubble and copying text. Record it with the phone's screen recorder in 1–2 minutes.

## 4. App content → Data safety

| Question | Answer |
|---|---|
| Collects or shares data? | Collects: **Yes**. Shares: **No** |
| Email address | Collected (optional, only if the user signs up) · Account management |
| App interactions | Collected · Analytics (counts of captures and copies, source app package) |
| Device or other IDs | Collected · Analytics (random app-generated device ID) |
| Encrypted in transit | Yes (HTTPS) |
| Users can request deletion | Yes (email contact@getdownaf.info) |
| Screen content / messages / photos | **Not collected** (processed on device only) |

Also fill in: **Content rating** questionnaire (Utility, no objectionable content), **Target audience** 18+, **Ads**: No, **Government app**: No, **Financial features**: None.

## 5. Release

Testing, then Closed testing, then create a track. Upload `GetDownTextrator-PlayStore.aab`, add testers, and roll out.
After 14 days with 12 testers (personal accounts), apply for **Production**.
Each push to `main` bumps the version code automatically, so later uploads just work.
