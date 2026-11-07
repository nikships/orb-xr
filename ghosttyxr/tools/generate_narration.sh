#!/usr/bin/env bash
# Dev-time generator for the bundled first-flight narration clips.
#
# Synthesizes each script below locally with MLX-Audio (Fish Speech S2 Pro on
# Apple silicon) and encodes the result to compact Opus .ogg under
# app/src/main/res/raw/. Runs ONCE at dev time; the APK never synthesizes
# onboarding narration and no key ships in the app.
#
# The brand voice is cloned from the checked-in reference clip
# narration_voice_ref.ogg, so regenerating one line stays in voice with the
# lines around it. No network calls and no API key: everything runs on-device.
#
# Usage:
#   ./generate_narration.sh [clip ...]
# With no args, regenerates every clip listed in CLIPS.
#
# Requires: mlx_audio (pip install mlx-audio), ffmpeg.
set -euo pipefail

TOOLS_DIR="$(cd "$(dirname "$0")" && pwd)"
RAW_DIR="$(cd "${TOOLS_DIR}/../android/app/src/main/res/raw" && pwd)"

MODEL="mlx-community/fish-audio-s2-pro"
MLX_TTS="${MLX_TTS:-mlx_audio.tts.generate}"

# Brand voice: cloned from a shipped clip so re-bakes never drift off-voice.
VOICE_REF="${TOOLS_DIR}/narration_voice_ref.ogg"
VOICE_REF_TEXT="That's it — your cockpit is ready. Voice AI is unlocked for your first flight. Fly well."

command -v "$MLX_TTS" >/dev/null || {
  echo "ERROR: $MLX_TTS not found (pip install mlx-audio)" >&2
  exit 1
}
command -v ffmpeg >/dev/null || { echo "ERROR: ffmpeg not found" >&2; exit 1; }
[ -f "$VOICE_REF" ] || { echo "ERROR: missing voice reference $VOICE_REF" >&2; exit 1; }

# Style direction shared by every clip: warm, confident flight instructor.
# Fish inline tags carry delivery; they are stripped from the spoken output.
STYLE_OPEN="[warm conversational tone]"

# clip_name|transcript
#
# Accumulated with read rather than `CLIPS=$(cat <<'EOF')`: bash 3.2 (the system
# bash on macOS) mis-parses an odd number of apostrophes inside a quoted heredoc
# nested in command substitution, so one contraction here broke the whole script.
CLIPS=""
while IFS= read -r line; do
  CLIPS="${CLIPS}${line}"$'\n'
done <<'EOF'
onboarding_welcome|Welcome to Orb XR. [short pause] This is where one developer becomes a whole team. Put every coding agent in its own floating terminal, keep every workstream visible, and step inside the work.
onboarding_pair|Your Mac stays the source of truth. [short pause] Open the Orb XR companion on your Mac, turn on Remote Login if it asks, then look at its pairing code. One scan brings your shells, your tools, and your agents into the headset, while everything keeps running safely on the Mac.
coach_first_terminal|This is your real Mac terminal. The shell, and every agent, keep running on your Mac [short pause] even when the headset comes off.
coach_spawn_pane|Time to give another agent a seat. Each pane is a persistent workspace. Go ahead [short pause] spawn one, right beside this terminal.
coach_lock_layout|One more thing before you fly. X R pinches are twitchy — typing, or even a sip of water, can fling a pane across the room. Press both palms together, like a prayer, and hold for half a second. [short pause] That freezes the layout exactly where it is. The same hold unlocks it.
coach_summon_pet|Now, meet your orb. Raise your free hand, palm up, and hold. The orb settles onto your palm, and listens. [short pause] When you're done talking, flip your palm down — that sends it, and puts the orb away.
coach_ready|That's it — your cockpit is ready. Voice A I is unlocked for your first flight. [short pause] Fly well.
coach_lock_done|Locked in. The same hold sets it free.
coach_pet_done|And that's the whole trick. Summon, speak, flip. [short pause] You've got it.
orb_no_speech|Didn't catch that — try again.
EOF

generate() { # $1=name $2=transcript
  local name="$1" transcript="$2" work
  echo "==> ${name}"
  work="$(mktemp -d -t "narration.${name}.XXXX")"
  "$MLX_TTS" \
    --model "$MODEL" \
    --ref_audio "$VOICE_REF" \
    --ref_text "$VOICE_REF_TEXT" \
    --text "${STYLE_OPEN} ${transcript}" \
    --output_path "$work" \
    --file_prefix "$name" >/dev/null

  # MLX writes one ${name}_NNN.wav per generated chunk; concatenate in order.
  local parts=("$work/${name}"_*.wav)
  [ -e "${parts[0]}" ] || { echo "    ERROR: no audio for ${name}" >&2; exit 1; }
  local concat="$work/concat.txt"
  : > "$concat"
  for part in "${parts[@]}"; do printf "file '%s'\n" "$part" >> "$concat"; done

  ffmpeg -hide_banner -loglevel error -y \
    -f concat -safe 0 -i "$concat" \
    -ac 1 -ar 48000 \
    -c:a libopus -b:a 48k -application voip \
    "${RAW_DIR}/${name}.ogg"
  rm -rf "$work"
  echo "    wrote res/raw/${name}.ogg ($(du -h "${RAW_DIR}/${name}.ogg" | cut -f1 | tr -d ' '))"
}

wanted=("$@")
while IFS='|' read -r name transcript; do
  [ -z "$name" ] && continue
  if [ "${#wanted[@]}" -gt 0 ]; then
    match=false
    for w in "${wanted[@]}"; do [ "$w" = "$name" ] && match=true; done
    $match || continue
  fi
  generate "$name" "$transcript"
done <<< "$CLIPS"

echo "Done."
