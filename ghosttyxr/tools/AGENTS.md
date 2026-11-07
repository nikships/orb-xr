# tools/ — asset builders

Dev-time asset pipelines for the headset app. Two scripts:

1. [`build_environments.py`](build_environments.py) — Poly Haven panoramas → focus-mode skybox `.glb`s
2. [`generate_narration.sh`](generate_narration.sh) — local MLX-Audio TTS → Opus clips for first-flight coach (`res/raw/`)

Human-oriented notes: [`README.md`](README.md).

---

# Immersive environment builder

Bake CC0 [Poly Haven](https://polyhaven.com) panoramas into inward-facing cubemap `.glb` files used as focus-mode skybox geometry on Android XR. Consumers load them as `SpatialEnvironmentPreference.geometry` (`GltfModel`) from `GhosttyXRApp`'s immersive `LaunchedEffect`.

Script: [`build_environments.py`](build_environments.py). Human-oriented notes: [`README.md`](README.md).

## Why cubemap geometry (not equirect / EXR)

Jetpack XR splits environment into two slots:

| Slot | Role for Orb XR |
|---|---|
| `SpatialEnvironmentPreference.geometry` (`GltfModel`) | **Visible** skybox. This is what the user sees. |
| EXR / IBL | Lights 3D objects only. Terminals have none, so EXR alone is invisible. |

The panorama must therefore be baked onto **unlit mesh geometry**, not left as an EXR skybox.

Cubemap faces (not an equirect sphere dome):

- Equirect on a sphere starves horizon detail (one 2:1 texture spans full 360°) and stretches at the poles.
- Six square cube faces sample the sphere uniformly: no pole pinch, no horizontal starvation, matches GPU cube-map sampling.

## How to run

Heavy work (venv, downloads, scratch) stays on an external/NVMe workspace. Only final `.glb` files land in the app assets tree.

```bash
# from repo root (ghostty-vibe-xr/), or adjust paths if cwd is ghosttyxr/
WORKSPACE=/Volumes/NVMe/ghosttyxr-assets
python3 -m venv "$WORKSPACE/venv"
"$WORKSPACE/venv/bin/pip" install numpy Pillow pygltflib

# all curated assets
WORKSPACE="$WORKSPACE" "$WORKSPACE/venv/bin/python" ghosttyxr/tools/build_environments.py

# single asset
WORKSPACE="$WORKSPACE" "$WORKSPACE/venv/bin/python" ghosttyxr/tools/build_environments.py --only dikhololo_night
```

- `WORKSPACE` defaults to `/Volumes/NVMe/ghosttyxr-assets` if unset (see `WORKSPACE` in the script).
- Scratch/downloads go under `$WORKSPACE/downloads/`.
- Deps: `numpy`, `Pillow`, `pygltflib` (venv only; not committed).

## Output path

```
ghosttyxr/android/app/src/main/assets/environments/{slug}.glb
```

Resolved in-script as `ASSETS_OUT = REPO_ROOT / "android/app/src/main/assets/environments"` where `REPO_ROOT` is `ghosttyxr/`. Each glb is roughly 7–24 MB (6× JPEG faces at `FACE_SIZE`, quality 90).

## Curated assets

All CC0 Poly Haven night skies (dark for terminal-text contrast). `ASSETS` in the script:

| Slug | Notes |
|---|---|
| `dikhololo_night` | Starry night |
| `rogland_clear_night` | Clear night sky |
| `satara_night_no_lamps` | Dense stars, lamps removed |
| `moonless_golf` | Near-black, faint horizon glow |

Unknown `--only` slugs fail with exit 2. Adding a sky means editing `ASSETS` and re-running the bake.

## Key constants

| Constant | Value | Why |
|---|---|---|
| `FACE_SIZE` | `4096` | Galaxy XR target: per-eye 3552×3840 over ~109°×100° FOV → ~32 px/°. A 4096² face over 90° is ~35.7 px/° at centre, which captures 8K equirect source (~22.75 px/° equator) with headroom. Old 2048×1024 equirect domes were ~5.7 px/° (pixelated). Bump only if source resolution rises; larger faces just upsample 8K JPGs. |
| `JPEG_QUALITY` | `90` | Night skies compress well; keeps glbs in low-MB range without blocking. |
| `CUBE_HALF_EXTENT` | `100.0` m | At-infinity backdrop; inside typical 200 m XR view distance. |

Pipeline per slug: Poly Haven tonemapped 8K JPG → equirect sample → 6 cube faces (OpenGL convention) → unlit glTF mesh (`KHR_materials_unlit`) → `{slug}.glb` under `ASSETS_OUT`.

## Don'ts

- **Do not commit** `$WORKSPACE` scratch, downloads, or venv. Only final `.glb` files under `android/app/src/main/assets/environments/` belong in git.
- **Do not** check in intermediate equirect JPGs or face textures.
- **Keep dark night skies** when adding assets. Bright day/HDRI skies kill terminal glyph contrast in focus mode.
- **Do not lower `FACE_SIZE` casually.** It is sized for Galaxy XR panel density; 2048-class faces look soft/pixelated on headset.
- Prefer Poly Haven **tonemapped JPGs** (script default) over raw HDR for exposure consistency on night assets.


---

# First-flight coach narration

[`generate_narration.sh`](generate_narration.sh) bakes prerecorded coach/setup
clips at **development time** using local MLX-Audio (Fish Speech S2 Pro) on
Apple silicon. Runtime onboarding never synthesizes speech; the APK only ships
Opus under `res/raw/`.

```bash
pip install mlx-audio   # plus ffmpeg
cd ghosttyxr/tools
./generate_narration.sh                # every clip
./generate_narration.sh coach_ready    # one clip
```

Output lands under `ghosttyxr/android/app/src/main/res/raw/` (`onboarding_*.ogg`,
`coach_*.ogg`).

## Voice continuity

The brand voice is **cloned from `narration_voice_ref.ogg`**, a checked-in copy
of a shipped clip, passed as `--ref_audio`/`--ref_text`. That is what lets a
single line be re-baked without drifting off-voice from the lines around it. If
you replace the reference, re-bake **every** clip in the same run and update
`VOICE_REF_TEXT` to match the new reference's exact words.

## Don'ts

- **No cloud TTS and no API keys.** Generation is local and offline; nothing in
  this pipeline should reintroduce a key into the script, APK, or repo.
- **Keep transcripts and on-screen copy in sync.** The `CLIPS` block is the
  source of truth for spoken lines; `intro/OnboardingFlow.kt` and
  `intro/OnboardingCoach.kt` carry the matching visible copy. Renaming the
  product or the voice orb means editing both and re-baking.
