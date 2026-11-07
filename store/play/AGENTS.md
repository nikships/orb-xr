# store/play — Play Store listing package

Agent guidelines for maintaining the Google Play listing and graphics for
**Orb XR**. Dense, practical. Edit this package without breaking Play
specs or product truth. Full human docs: [`README.md`](README.md).

## Purpose

`store/play/` is the self-contained package for the Play Console store listing
of **Orb XR** (`com.ghosttyxr.app`) by **Orb XR**:

- Working en-US listing copy (title, short/full description, release notes, alt text)
- Store graphics (icon, feature graphic, Android XR screenshots)
- Spec sheet, positioning notes, and submission runbook
- Deterministic Python renderer that regenerates every image from repo truth

It is **not** the app binary, signing keys, privacy policy host, or Play Console
automation. Root [`AGENTS.md`](../../AGENTS.md) covers app build and Play API
access.

## Hard constraints (do not change)

| Item | Value | Why |
|---|---|---|
| Store name | **Orb XR** | Official Play name; locked product name |
| Package / applicationId | **`com.ghosttyxr.app`** | Permanent after first upload |
| Developer brand | **Orb XR** | Codebase and developer-facing brand |
| Architecture truth | Agents run on the **Mac over SSH** | Headset is a thin client |

Copy, captions, screenshots, and alt text must **never** imply that coding
agents run on the headset. Shells, agents, toolchains, and files live on the
Mac; the headset renders terminals and routes input. See `VISION.md` and
`listing/keywords-positioning.md`.

Voice copy must reflect the managed Free/Pro model in
[`../../docs/monetization.md`](../../docs/monetization.md): there is no
bring-your-own Deepgram or Gemini key path, and free users keep the complete
terminal workspace while Pro unlocks AI voice features. First-flight temporary
access is not a permanent Free-tier entitlement.

Do not rename the package. Do not invent store-name variants or taglines in
the title field.

## File map

```
store/play/
  AGENTS.md                 # this file
  README.md                 # package overview + Console slot map
  listing/
    en-US.md                # working listing copy + graphic alt text
    requirements.md         # researched Play specs with official citations
    keywords-positioning.md # positioning, term choices, terms to avoid
    submission-checklist.md # Play Console submission runbook
  assets/
    icon/icon_512.png
    feature/feature_graphic_1024x500.png
    screenshots/xr/01..06_*.png
  source/
    render_assets.py        # deterministic asset generator (editable source)
    requirements.txt        # Pillow==11.3.0
```

| Path | Play Console role |
|---|---|
| `listing/en-US.md` | Main store listing text + alt text for every graphic |
| `assets/icon/icon_512.png` | App icon |
| `assets/feature/feature_graphic_1024x500.png` | Feature graphic |
| `assets/screenshots/xr/*.png` | Graphics → Android XR screenshots (upload in numeric order) |
| `source/render_assets.py` | Regenerates all of the above images |

## Regenerating assets

```bash
pip install -r store/play/source/requirements.txt   # Pillow pinned
python3 store/play/source/render_assets.py          # everything
python3 store/play/source/render_assets.py --only 03  # one screenshot
```

Rules:

- Prefer the renderer over hand-edited PNGs. Screenshot content, captions, and
  layout live as plain data at the top of each `shot_*` function in
  `render_assets.py`.
- Renderer is **pixel-identical deterministic** (fixed seeds, no timestamps).
  Rerenders should satisfy
  `ImageChops.difference(a, b).getbbox() is None` against committed assets.
- PNG **bytes** may differ across Pillow/zlib versions. Committed files were
  encoded with **Pillow 11.3.0** (pinned in `source/requirements.txt`). Diff
  **pixels**, not bytes.
- Raw material comes from the shipping tree: JetBrains Mono (`res/font/`),
  Ghostty themes (`assets/themes/`), chrome tokens from `GhosttyTokens` /
  `PaneSurface.kt`. Keep those sources in sync when the app UI changes.
- Screenshots are studio recreations, not device captures. On-device
  `adb shell screencap` may replace them 1:1 later; slot specs stay the same.

## Spec limits

Re-verify UI-enforced limits in live Play Console before submit
(`listing/requirements.md` has citations).

### Text (`listing/en-US.md`)

| Field | Limit | Package status |
|---|---|---|
| App title | 30 chars | `Orb XR` (6) |
| Short description | 80 chars | Re-count from `en-US.md` before upload |
| Full description | 4000 chars | Re-count from `en-US.md` before upload |
| Release notes | 500 chars | Re-count from `en-US.md` before upload |
| Alt text (per graphic) | 140 chars | Re-count every row before upload |

Title rules: bare brand name only. No emoji, no ALL CAPS (unless brand), no
taglines, ranking, price, or program claims.

### Graphics

| Asset | Spec | Package output |
|---|---|---|
| Icon | 512×512, 32-bit PNG (alpha OK), ≤1 MB | `icon_512.png` |
| Feature graphic | 1024×500, JPEG or 24-bit PNG, **no alpha** | `feature_graphic_1024x500.png` |
| Android XR screenshots | 4–8 images, PNG/JPEG, ≤8 MB each, **8:5**, rec. 3840×2400 (min 1920×1200) | six files at 3840×2400 RGB |

Icon: full-bleed square; do not pre-round corners (Play masks dynamically).
XR screenshots: flat 2D only; no stereoscopic slot. Enter counted alt text from
`en-US.md` → "Graphic alt text" on every upload.

## Don'ts

1. **Don't invent features.** Listing copy and screenshots must match shipping
   product only. Claims are audited in `en-US.md` (truthfulness table). If a
   feature is not in the tree / not shipping, do not show or describe it.
2. **Don't drop or rewrite alt text.** Every graphic needs counted en-US alt
   text ≤140 chars in `en-US.md`. Keep it factual.
3. **Don't rename the package.** `com.ghosttyxr.app` is permanent after first
   upload. Store name stays **Orb XR**.
4. **Don't imply on-headset agents.** Avoid phrasing like "AI assistant on
   your headset" or "agents run locally." Always host-side (Mac over SSH).
5. **Don't hand-paint committed assets as the source of truth.** Prefer
   regenerating via `render_assets.py` so output stays pixel-deterministic and
   token-faithful. Diff pixels after rerender.
6. **Don't stuff keywords, badges, or promos.** No "#1"/"best", no award
   badges, no discount callouts, no keyword lists (metadata policy).
7. **Keep the product name consistent.** User-visible headset and Mac companion surfaces are **Orb XR**. Preserve internal companion identifiers only where required by signing, bundle, or build tooling.
8. **Don't upload debug APKs as the store build.** Listing package is
   copy/graphics only; release AAB is separate (see submission checklist).

## When editing

1. Change copy in `listing/en-US.md` first; re-count with `len()` (newlines
   count in full description). Stay under limits.
2. Re-audit every product claim against the shipping tree and
   `docs/monetization.md`; old counted values in adjacent docs are not proof
   that copy is still current.
3. Change screenshot narrative/layout in `source/render_assets.py`, then
   regenerate and pixel-diff against previous assets if intentional.
4. Update alt text rows in `en-US.md` when a graphic's content changes.
5. Keep `keywords-positioning.md` and the truthfulness audit aligned with
   real product claims.
6. Follow `listing/submission-checklist.md` for Console upload order; do not
   treat this package as a substitute for privacy policy or data-safety forms.

## Related

- Root product constraints: [`../../AGENTS.md`](../../AGENTS.md), [`../../VISION.md`](../../VISION.md)
- Architecture truth (thin client / Mac host): [`../../docs/architecture.md`](../../docs/architecture.md)
- Human package overview: [`README.md`](README.md)
