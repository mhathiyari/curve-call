# Play Console Submission Notes

Last updated: April 5, 2026

This file is meant to reduce the amount of guesswork when filling out Google Play Console for CurveCue.

## Release Artifact

- Preferred upload format: Android App Bundle (`.aab`)
- Build command:

```bash
./gradlew :app:bundleRelease
```

- Expected output:

```text
app/build/outputs/bundle/release/app-release.aab
```

## Required Local Gradle Properties

Add these to `~/.gradle/gradle.properties` before building a release:

```properties
CURVECUE_UPLOAD_STORE_FILE=/absolute/path/to/curvecue-upload.jks
CURVECUE_UPLOAD_STORE_PASSWORD=your-store-password
CURVECUE_UPLOAD_KEY_ALIAS=upload
CURVECUE_UPLOAD_KEY_PASSWORD=your-key-password
CURVECUE_PRIVACY_POLICY_URL=https://your-hosted-privacy-policy-url
```

## Store Presence

Use [PLAY_STORE_LISTING.md](/Users/mustafahathiyari/gitworkspace/curve_call/docs/PLAY_STORE_LISTING.md) as the initial draft for:

- App name
- Short description
- Full description
- Screenshot list

## Privacy Policy

Use [PRIVACY_POLICY.md](/Users/mustafahathiyari/gitworkspace/curve_call/docs/PRIVACY_POLICY.md) as the source text for the hosted privacy-policy page.

Suggested hosting options:

- GitHub Pages
- Your own domain
- Any static site host with a stable HTTPS URL

The URL should be:

- Publicly accessible
- Non-editable by end users
- Stable
- Linked from both Play Console and the app

## Suggested App Content Answers

These are working assumptions based on the current codebase.

### App Access

- No login required
- No demo account required
- All primary screens are accessible after first launch

### Ads

- Select `No`, assuming no advertising SDK is added before release

### Data Safety Working Draft

Potential data categories collected or shared through app features:

- Location
- Search queries
- App activity related to route/session usage

Working implementation assumptions:

- Data is not sold
- No account system is present
- No cloud sync is present
- Preferences and recent destinations are stored locally on device
- Search, geocoding, routing, and tile features may transmit limited request data to third-party map/routing services when used

Before submitting, verify the Data safety form against the exact release build and every SDK dependency included in it.

### Content Rating

- Utility / maps / navigation-adjacent driving aid
- No user-generated content
- No gambling
- No sexual content
- No violence content beyond ordinary road imagery

### Target Audience

- Adults
- Not designed for children

## Testing Track Suggestion

Use this order:

1. Internal testing
2. Closed testing
3. Production

If the developer account is subject to Google’s newer personal-account rollout rules, complete the required closed-testing period before production.

## Pre-Submission QA

- Confirm the release bundle is signed and uploads successfully
- Confirm the privacy policy URL opens from the About screen
- Confirm screenshots match the release build branding (`CurveCue`)
- Confirm no debug text, placeholder URLs, or test-only assets remain
- Confirm location disclosure and safety messaging are visible in the app
