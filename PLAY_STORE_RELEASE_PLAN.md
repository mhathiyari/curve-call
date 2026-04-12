# Play Store Release Plan

This checklist tracks the repo work needed to make CurveCue publishable, plus the manual Play Console steps that still need to happen outside the codebase.

## In Repo

- [x] Inspect current Android release setup and identify Play Store blockers
- [x] Update Android SDK and release version metadata for current Play requirements
- [x] Add release signing scaffold for signed App Bundle builds
- [x] Fix user-facing branding/version inconsistencies in the app
- [x] Add an in-app privacy policy surface for store review readiness
- [x] Document the exact bundle/signing/release steps for this repo
- [x] Upgrade Android Gradle Plugin and wrapper for API 35 support
- [x] Draft privacy policy text for hosted publication
- [x] Draft Play Store listing copy and Play Console submission answers
- [x] Verify the app still builds after release-readiness changes
- [x] Verify the minified release App Bundle builds

## Manual Play Console Work

- [ ] Create or verify the Google Play developer account
- [ ] Verify the developer account contact details and device requirements
- [ ] Host a public privacy policy URL and add it to the store listing
- [ ] Create the app in Play Console and configure Play App Signing
- [ ] Upload a signed `.aab` to internal testing
- [ ] Complete `App content`: privacy policy, Data safety, content rating, app access, ads, target audience
- [ ] Add store assets: icon, screenshots, short description, full description, feature graphic
- [ ] Complete required testing track steps before production rollout

## Notes

- As of August 31, 2025, new Google Play submissions must target Android 15 / API 35.
- This app uses foreground location while an active driving session is running, so the Play listing and Data safety answers must describe that clearly.
- This repo currently does not request `ACCESS_BACKGROUND_LOCATION`, which avoids the stricter background-location declaration path unless that permission is added later.
- `:app:assembleDebug` passes locally with JDK 21, Gradle `8.7`, Android Gradle Plugin `8.6.1`, and `compileSdk = 35`.
- `:app:bundleRelease` passes locally with R8 minification enabled.
- Before actual Play upload, build with a real upload key and a hosted privacy policy URL in `~/.gradle/gradle.properties`.

## Release Commands

- [x] `compileSdk` and `targetSdk` are now set to `35`
- [x] `versionName` is now `1.0.0`
- [x] Release signing can be supplied through Gradle properties

Add these properties to `~/.gradle/gradle.properties` before building a release:

```properties
CURVECUE_UPLOAD_STORE_FILE=/absolute/path/to/curvecue-upload.jks
CURVECUE_UPLOAD_STORE_PASSWORD=your-store-password
CURVECUE_UPLOAD_KEY_ALIAS=upload
CURVECUE_UPLOAD_KEY_PASSWORD=your-key-password
CURVECUE_PRIVACY_POLICY_URL=https://your-domain.example/curvecue/privacy
```

Build the Play Store bundle with:

```bash
./gradlew :app:bundleRelease
```

The bundle output will be:

```text
app/build/outputs/bundle/release/app-release.aab
```

Upload that `.aab` to Play Console, enable Play App Signing, and start with the internal testing track.

## Current Stop Point

- Stopped on April 12, 2026 because Google Play Console is blocking app creation and release setup until the personal developer account verification flow is completed.
- Repo status at stop: release-ready Android project, verified `:app:assembleDebug`, verified `:app:bundleRelease`, and generated `app/build/outputs/bundle/release/app-release.aab`.
- Next advisor/session should begin from the Play Console side, not from Android build work.

## Immediate Next Steps After Verification

- Sign in at `https://play.google.com/console`
- Create the `CurveCue` app entry
- Add the hosted privacy policy URL from `docs/privacy-policy.html` or `docs/PRIVACY_POLICY.md`
- Turn on Play App Signing and upload `app/build/outputs/bundle/release/app-release.aab`
- Complete store listing and App content forms using `docs/PLAY_STORE_LISTING.md` and `docs/PLAY_CONSOLE_SUBMISSION.md`
- If this personal developer account is subject to Google’s newer rollout rules, move into internal/closed testing before requesting production
