# tools/

Dev-time asset builders for Orb XR. See [`AGENTS.md`](AGENTS.md).

## build_environments.py

Bakes CC0 [Poly Haven](https://polyhaven.com) panoramas into inward-facing
**cubemap** `.glb` files used as focus-mode skybox geometry on Android XR.

```bash
WORKSPACE=/Volumes/NVMe/ghosttyxr-assets
python3 -m venv "$WORKSPACE/venv"
"$WORKSPACE/venv/bin/pip" install numpy Pillow pygltflib
WORKSPACE="$WORKSPACE" "$WORKSPACE/venv/bin/python" \
  ghosttyxr/tools/build_environments.py
# or: ... build_environments.py --only dikhololo_night
```

Output: `ghosttyxr/android/app/src/main/assets/environments/{slug}.glb`

Curated night skies: `dikhololo_night`, `rogland_clear_night`,
`satara_night_no_lamps`, `moonless_golf`.

## generate_narration.sh

Bakes first-flight coach/setup narration (local MLX-Audio Fish Speech S2 Pro →
Opus) into Android `res/raw/`. Runtime never synthesizes onboarding speech.

```bash
pip install mlx-audio            # Apple silicon; ffmpeg also required
./generate_narration.sh          # all clips
./generate_narration.sh coach_ready   # one clip
```

No API key and no network: the brand voice is cloned from the checked-in
`narration_voice_ref.ogg`, so a partial re-bake stays in voice.
