# Google Play listing package — Orb XR

Everything needed to fill in the Play Console listing for
**Orb XR** (`com.ghosttyxr.app`) by **Orb XR**. Copy is final and
counted; assets meet the current Play specs for the Android XR form factor.

## File → Play Console slot map

| File | Play Console slot | Spec | Actual |
|---|---|---|---|
| `listing/en-US.md` | Main store listing → all text fields | title ≤30 · short ≤80 · full ≤4000 | 13 · 78 · 3162 |
| `assets/icon/icon_512.png` | App icon | 512×512, 32-bit PNG, ≤1 MB | 512×512 PNG (RGBA) |
| `assets/feature/feature_graphic_1024x500.png` | Feature graphic | 1024×500, JPEG/24-bit PNG, no alpha | 1024×500 PNG (RGB) |
| `assets/screenshots/xr/01_fleet_cockpit.png` | Graphics → Android XR screenshots (1) | 4–8 imgs, 8:5, ≤8 MB, rec. 3840×2400 | 3840×2400 PNG (RGB) |
| `assets/screenshots/xr/02_native_terminal.png` | Android XR screenshots (2) | 〃 | 3840×2400 PNG (RGB) |
| `assets/screenshots/xr/03_pair_with_mac.png` | Android XR screenshots (3) | 〃 | 3840×2400 PNG (RGB) |
| `assets/screenshots/xr/04_focus_mode.png` | Android XR screenshots (4) | 〃 | 3840×2400 PNG (RGB) |
| `assets/screenshots/xr/05_themes.png` | Android XR screenshots (5) | 〃 | 3840×2400 PNG (RGB) |
| `assets/screenshots/xr/06_voice_and_controls.png` | Android XR screenshots (6) | 〃 | 3840×2400 PNG (RGB) |

Every graphic has counted en-US **alt text** (≤140 chars each) in
`listing/en-US.md` → "Graphic alt text" — enter it in each upload dialog's
alt-text field.

Supporting docs:

| File | Purpose |
|---|---|
| `listing/requirements.md` | Researched Play spec sheet (official citations) the package was built against |
| `listing/keywords-positioning.md` | Positioning statement, term choices, terms to avoid |
| `listing/submission-checklist.md` | Step-by-step Play Console submission runbook (incl. data safety guidance) |
| `source/render_assets.py` | Reproducible generator for every image above |

## Regenerating the assets

```bash
pip install -r store/play/source/requirements.txt      # Pillow (pinned)
python3 store/play/source/render_assets.py            # everything
python3 store/play/source/render_assets.py --only 03  # one screenshot
```

The renderer is deterministic at the **pixel level** (fixed seeds, no
timestamps): rerendering produces images whose pixel content is identical to
the committed assets (`ImageChops.difference(a, b).getbbox() is None`). The
PNG **bytes** are not guaranteed to match across Pillow/zlib versions, since
PNG encoding is version-dependent — the committed files were encoded with
**Pillow 11.3.0** (pinned in `source/requirements.txt`). To compare a rerender
against the committed assets, diff pixels, not bytes.

The renderer pulls its raw material from this repository so the output stays
faithful to the shipping app:

- **Fonts**: the app's bundled JetBrains Mono TTFs (`res/font/`).
- **Terminal palettes**: real Ghostty theme files (`assets/themes/` —
  Catppuccin Mocha is the app default).
- **Chrome**: colors/geometry mirrored from `GhosttyTokens`
  (`terminal/theme/GhosttyXRTheme.kt`) and the pane status bar
  (`ui/pane/PaneSurface.kt`).

The Python script is the editable source — screenshot content, captions, and
layout are plain data structures at the top of each `shot_*` function.

### Provenance note

The screenshots are studio recreations of the Orb XR UI (exact tokens, fonts,
themes, layout, and feature states from this source tree), not raw device
captures — the sandbox this package was produced in cannot run the XR
emulator. They depict only real, shipping functionality (see the truthfulness
audit in `listing/en-US.md`). For maximum fidelity, hero shots can be replaced
1:1 with Galaxy XR captures (`adb shell screencap`) before submission; slot
specs are unchanged.

## Hard constraints (do not change)

- Store name **Orb XR**; package **`com.ghosttyxr.app`** (locked at
  first upload).
- Developer brand **Orb XR**.
- Copy must never imply agents run on the headset — they run on the user's Mac
  over SSH.
