# Play Console — App Content Form Answers

Last updated: May 25, 2026

Exact answers for every section under **"Provide app information and create your store listing"** in Google Play Console. Fill each form exactly as written here.

---

## 1. Set Privacy Policy

**Privacy policy URL field:**
Enter the public HTTPS URL where `docs/privacy-policy.html` is hosted.

Suggested hosting options (pick one):
- **GitHub Pages** — push the repo, enable Pages on `main`, URL will be `https://<username>.github.io/curve_call/docs/privacy-policy.html`
- **Your own domain** — upload to any static host under `https://yourdomain.com/curvecue/privacy`

Requirements checklist before submitting:
- [ ] URL returns HTTP 200 (not behind a login)
- [ ] Page loads on mobile
- [ ] URL is stable (won't change)
- [ ] Update `CURVECUE_PRIVACY_POLICY_URL` in `~/.gradle/gradle.properties` to match

---

## 2. App Access

**Does your app require users to log in or have special access to use it?**
- Select: **All or most functionality is accessible without special access**

No accounts, no sign-in, no invite codes. All primary screens are reachable after first launch. Location permission is requested at runtime but is not a gating prerequisite for launching the app.

---

## 3. Ads

**Does your app contain ads?**
- Select: **No, my app does not contain ads**

CurveCue includes no advertising SDK and displays no ads. If an ad network is ever added, this must be changed before the next release.

---

## 4. Content Rating

Complete the IARC questionnaire. Expected answers:

| Question | Answer |
|---|---|
| App category | Utility / Reference |
| Violence | No |
| Sexual content | No |
| Language | No profanity |
| Controlled substances | No |
| User-generated content | No |
| User interaction (chat, sharing) | No |
| Location sharing with other users | No |
| Digital purchases / billing | No |
| Gambling | No |

**Expected rating:** Everyone (E) — suitable for all ages.

---

## 5. Target Audience

**Who is your target audience?**
- Select age groups: **18 and over**
- Does your app appeal to children? **No**

CurveCue is a driving-assistance tool. Driving requires a valid licence (18+ in most jurisdictions). The app is not designed for or marketed to children.

**Does your app primarily target children under 13?**
- Select: **No**

---

## 6. Data Safety

This is the most detailed form. Fill each field exactly as listed below.

### Does your app collect or share any of the required user data types?

**Yes** — the app collects and/or shares data from the categories below.

---

### Location

| Field | Answer |
|---|---|
| Collected? | **Yes** |
| Approximate location | Yes |
| Precise location | Yes |
| Shared with third parties? | **Yes** (see below) |
| Data encrypted in transit? | **Yes** (HTTPS only) |
| Can users request deletion? | **Yes** — via "Clear app data" in Android settings |
| Collection required? | **Yes** — app core functionality depends on it |
| Why collected | App functionality (curve narration requires GPS position) |

**Third-party sharing details for Location:**
Location coordinates are shared with:
- **Nominatim** (nominatim.openstreetmap.org) — for destination search and reverse geocoding
- **OSRM** (router.project-osrm.org) — for online route calculation as a fallback

These are point-in-time requests (search or route start), not continuous location streams. The app's foreground location service stays on-device.

---

### App Activity

| Field | Answer |
|---|---|
| Collected? | **Yes** |
| In-app search history | Yes (destination search queries, stored locally) |
| Other app activity | Yes (session event logs: timestamps, speed, curve events — stored locally) |
| Shared with third parties? | **Yes** — search query text is sent to Nominatim |
| Data encrypted in transit? | **Yes** (HTTPS only) |
| Can users request deletion? | **Yes** — "Clear app data" removes all local history and logs |
| Collection required? | Search history: No (optional feature). Session logs: No (can be disabled) |
| Why collected | App functionality (destination search, post-ride debug review) |

---

### Files and Docs

| Field | Answer |
|---|---|
| Collected? | **Yes** |
| Files | Yes — GPX files that the user explicitly opens via Android share/open intent |
| Shared with third parties? | **No** — GPX content stays on-device, used only to parse the route |
| Data encrypted in transit? | N/A (not transmitted) |
| Can users request deletion? | Yes — user manages GPX files via the device file manager |
| Collection required? | No (optional route import feature) |
| Why collected | App functionality (route import) |

---

### Data NOT collected

The following categories are **not** collected:
- Personal info (name, email, address, phone, race, religion, etc.)
- Financial info
- Health and fitness data
- Messages, photos, or videos
- Contacts or calendar
- Web browsing history
- Device or other identifiers (no analytics SDK, no crash reporter)

---

### Summary statement for the Data Safety form

> CurveCue collects precise and approximate location during active driving sessions to time curve narration. Location coordinates are also shared with OpenStreetMap-based Nominatim and OSRM services solely to fulfil on-demand destination search and route requests. No location data is sold or used for advertising. Search queries entered by the user are sent to Nominatim to return search results. Session event logs (speed, cue events) are stored locally and never transmitted. GPX files opened by the user are read on-device only. No user account, analytics SDK, or advertising SDK is present.

---

## 7. Government Apps

**Is this app a government app?**
- Select: **No**

CurveCue is an independent developer app, not affiliated with any government entity.

---

## 8. Financial Features

**Does your app contain financial features?**
- Select: **No**

No in-app purchases, no billing, no financial services, no cryptocurrency features.

---

## 9. Health

**Does your app contain health or fitness features?**
- Select: **No**

CurveCue does not track biometrics, fitness activity, or health data. Speed and location are used solely for driving-session narration.

---

## 10. App Category and Contact Details

**Category:** Navigation  
(Alternative if Navigation is rejected: Auto & Vehicles or Tools)

**Contact email:** curvecue.app@proton.me  
**Website:** (your GitHub Pages or personal site URL)  
**Privacy policy URL:** (same URL from Section 1)

---

## 11. Store Listing

Refer to `docs/PLAY_STORE_LISTING.md` for the full app name, short description, full description, and keyword suggestions.

**Assets still needed before submission:**
- [ ] App icon: 512×512 px PNG, no alpha channel
- [ ] Feature graphic: 1024×500 px JPG or PNG
- [ ] Phone screenshots: at least 2, up to 8 (recommended: home, route preview, active session, settings)
- [ ] Short description: max 80 characters — use text from `PLAY_STORE_LISTING.md`
- [ ] Full description: max 4000 characters — use text from `PLAY_STORE_LISTING.md`

---

## Pre-Submission Checklist

- [ ] Privacy policy URL is live and publicly accessible
- [ ] `CURVECUE_PRIVACY_POLICY_URL` gradle property is set and a release `.aab` is built with it
- [ ] All Data Safety answers above are entered and saved
- [ ] Content rating questionnaire is completed and rating confirmed as "Everyone"
- [ ] No ads, no account required answers are saved
- [ ] Store listing copy, icon, and at least 2 screenshots are uploaded
- [ ] App is submitted to **Internal Testing** track first
- [ ] At least 12 testers have tested before requesting Closed Testing
- [ ] Closed testing period completed before Production rollout request
