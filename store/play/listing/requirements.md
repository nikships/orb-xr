# Google Play listing requirements — Android XR (researched 2026-07)

Spec sheet the assets and copy in this package were built against. Every claim
cites an official Google source. Re-verify anything marked ⚠ in the live Play
Console UI before submitting — Google adjusts UI-enforced limits without
updating policy pages.

Primary sources:

- **Preview assets** (icon, feature graphic, screenshots, video):
  <https://support.google.com/googleplay/android-developer/answer/9866151>
- **Metadata policy** (title/description rules):
  <https://support.google.com/googleplay/android-developer/answer/9898842>
- **Android XR release track**:
  <https://support.google.com/googleplay/android-developer/answer/13295490>
- **XR packaging/distribution** (manifest features):
  <https://developer.android.com/develop/xr/package-and-distribute>
- **Android XR app quality guidelines**:
  <https://developer.android.com/docs/quality-guidelines/android-xr>

## Text fields

| Field | Limit | Notes |
|---|---|---|
| App title | 30 chars | Metadata policy. No emoji, no ALL CAPS (unless brand), no ranking/price/program claims. |
| Short description | 80 chars ⚠ | Play Console UI-enforced; verify live. |
| Full description | 4000 chars ⚠ | Play Console UI-enforced; verify live. No keyword stuffing, no unattributed testimonials. |
| Release notes | 500 chars ⚠ | UI-enforced; not documented on a policy page. |

## Store icon

- **512 × 512 px**, 32-bit PNG (alpha allowed), max **1024 KB**.
- Full-bleed square; Play applies corner masking dynamically — don't pre-round corners.

## Feature graphic

- **1024 × 500 px**, JPEG or 24-bit PNG (**no alpha**). Required to publish.
- Shown primarily as the backdrop for the preview video when one is set.

## Screenshots — Android XR form factor

Play Console has a **dedicated Android XR screenshot slot** (alongside phone /
tablet / TV / Wear / Automotive). Quoted spec:

> "Android XR: To showcase your app, you must upload between 4 and 8
> screenshots. Screenshots must be PNG or JPEG, up to 8 MB each, 8:5 aspect
> ratio. Recommended image resolution: 3840x2400 (minimum 1920x1200)."

- **4–8 screenshots**, PNG/JPEG, **≤ 8 MB each**, **8:5**, recommended
  **3840 × 2400** (min 1920 × 1200). Flat 2D images only — there is no
  stereoscopic/360° screenshot slot (immersive media goes in the spatial video
  field instead).
- Platform-wide publish minimum is 2 screenshots across device types; the 4–8
  XR set satisfies this for an XR-only app. ⚠ Verify in Play Console once the
  device-support declaration is final.
- General rules: no alpha channel, aspect ratio between 1:2 and 2:1, max
  dimension 3840 px.

## Video (optional, not included in this package)

- **Standard preview video**: YouTube URL, optional for apps.
- **Non-spatial Android XR video**: standard YouTube URL, shown on the XR
  headset listing (overrides standard preview there).
- **Spatial Android XR video**: YouTube URL that is 360°/180°/3D; public or
  unlisted, ads off, not age-restricted.

## Android XR distribution requirements (already satisfied by the app)

- Publishing to the dedicated **XR release track** requires
  `android.software.xr.api.spatial` (Jetpack XR SDK apps) or
  `android.software.xr.api.openxr` (OpenXR/Unity apps) in the manifest.
  ✅ `app/src/main/AndroidManifest.xml` declares
  `android.software.xr.api.spatial` with `required=true`.
- Track UI: Play Console → Test and release → Advanced settings → Form factors
  → Add form factor → Android XR (optional; the standard mobile track can also
  serve XR devices).
- Input feature flags `android.hardware.xr.input.controller` and
  `android.hardware.xr.input.hand_tracking` are declared `required=false`,
  keeping the device pool broad.

## Metadata policy constraints honored by this package

- Title is the bare brand name — no taglines, emoji, caps, or ranking terms.
- Copy contains no performance/ranking claims ("#1", "best"), no price/promo
  text, no Google Play program claims, no unattributed testimonials.
- Screenshots depict actual product functionality (see the provenance note in
  [`../README.md`](../README.md)) with no award badges or discount callouts.
