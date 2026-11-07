# Play Console submission checklist — Orb XR

Working order for the first submission of `com.ghosttyxr.app`. The app must be
created manually in Play Console first (the Android Publisher API cannot create
listings — see root `AGENTS.md`).

## 1. App creation

- [ ] Play Console → Create app → name **Orb XR**, default language
      **en-US**, type **App**, **Free**.
- [ ] Confirm `applicationId = "com.ghosttyxr.app"` in the uploaded artifact —
      the package name locks permanently on first upload.
- [ ] Grant the service account
      `play-console-cli@ghosttyxr-play-2026.iam.gserviceaccount.com` access to
      the app with release-management permissions (enables `gplay` automation).

## 2. Main store listing (copy from `listing/en-US.md`)

- [ ] App name: `Orb XR` (6/30)
- [ ] Short description (78/80)
- [ ] Full description (3162/4000)
- [ ] Developer name: `Orb XR`

## 3. Graphics (files from `assets/`, mapping in `../README.md`)

- [ ] App icon ← `assets/icon/icon_512.png` (512×512 PNG, 43 KB < 1 MB)
- [ ] Feature graphic ← `assets/feature/feature_graphic_1024x500.png`
- [ ] **Android XR screenshots** (Graphics → Android XR): upload all six
      `assets/screenshots/xr/*.png` in numeric order (each 3840×2400, 8:5,
      < 8 MB). Minimum for the slot is 4; we ship 6.
- [ ] **Alt text**: when uploading each graphic (icon, feature graphic, all
      six screenshots), paste its counted en-US alt text (≤140 chars) from
      `listing/en-US.md` → "Graphic alt text" into the upload dialog's
      alt-text field.
- [ ] No phone/tablet screenshots are provided — the app declares
      `android.software.xr.api.spatial required=true`, so it does not serve
      phones. If Play Console still demands a phone set to publish, capture
      the flat Home Space window (the app's 2D fallback), or extend
      `source/render_assets.py` to add a 16:9 output first — the renderer
      currently produces only the icon, feature graphic, and the six 8:5 XR
      screenshots.
- [ ] Video fields: leave empty for v1 (optional). Candidates for later: a
      non-spatial XR video walkthrough on YouTube.

## 4. Form factor / release track

- [ ] Test and release → Advanced settings → Form factors → **Add form factor →
      Android XR** (manifest already declares `android.software.xr.api.spatial`,
      so the build qualifies for the dedicated XR track).
- [ ] Upload an AAB signed with the Play upload key (the checked-in debug
      signing config is sideload-only — do NOT upload `app-debug.apk`).

## 5. App content declarations

- [ ] **Privacy policy URL** — required for apps requesting camera, microphone,
      and location-adjacent permissions. Host one before submission (not in
      this package).
- [ ] **Data safety form** — declare truthfully:
  - Camera: QR pairing only, processed on device, not collected.
  - Microphone/voice: audio is captured only when the user activates voice
    input. Dictation streams to **Deepgram** using the user's own API key
    (optional feature, off until configured). Voice commands send transcripts
    to **Gemini via Firebase AI Logic** (Google). Disclose both as data shared
    with third parties for app functionality, user-initiated.
  - SSH host, username, and keys: stored on device in encrypted storage
    (`EncryptedSharedPreferences`), never transmitted to the developer.
  - Terminal content: transits directly between headset and the user's Mac
    over SSH on the local network; the developer operates no relay servers.
  - Bluetooth/location-class permissions (`BLUETOOTH_*`, `ACCESS_*_LOCATION`,
    `NEARBY_WIFI_DEVICES`): used for device discovery/pairing on the local
    network; review against the final feature set before declaring.
- [ ] **Content rating questionnaire** — utility/productivity app, no
      user-generated content shared publicly, no violence: expect "Everyone".
- [ ] **Target audience**: 18+ / developers; not directed at children.
- [ ] **Ads declaration**: contains no ads.

## 6. Pre-launch verification

- [ ] Run `python3 store/play/source/render_assets.py` (Pillow pinned in
      `source/requirements.txt`) and confirm the rerender is pixel-identical
      to the committed assets — compare pixels, not bytes:
      `ImageChops.difference(committed, rerendered).getbbox() is None` for all
      8 images. PNG bytes may legitimately differ across Pillow/zlib versions;
      committed outputs were encoded with Pillow 11.3.0.
- [ ] Re-verify UI-enforced character limits in the live console (short/full
      description, release notes) — see `listing/requirements.md` ⚠ flags.
- [ ] Screenshot provenance note: the committed screenshots are studio
      recreations built from the app's real design tokens, fonts, themes, and
      feature states (see `source/render_assets.py` header). Before final
      submission, consider replacing hero shots with raw on-device captures
      from a Galaxy XR (`adb shell screencap`) for maximum fidelity — the
      Play slot specs are identical.
- [ ] Release notes from `listing/en-US.md` (231/500).

## 7. Do NOT

- Publish from this PR — it prepares material only.
- Rename the app or package: **Orb XR** / `com.ghosttyxr.app` are locked.
- Add ranking/price language ("best", "#1", "free for a limited time") to any
  field — metadata policy violations.
- Imply agents run on the headset in any future copy edits.
