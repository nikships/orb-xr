# Spec: Stream pet speech via Gemini Interactions API

## Goal

Replace batch Gemini TTS (`generateContent` → full base64 PCM) with the
**Interactions API** streaming path so the pet starts talking as audio chunks
arrive. Design the voice turn as an **interaction chain** (Flash tools → final
text → TTS stream) on one API family—not a one-shot `streamGenerateContent`
swap.

## Product outcome

- Lower **time-to-first-audio** after a voice-command result/error toast.
- Keep toast as source of truth; TTS failures stay silent.
- Keep Pro / App Check / no keys in APK.
- Fixed voice **Aoede**, short pet lines only.

## Current pipeline (two Gemini stacks, disconnected)

```text
Mic ─► Deepgram (CF stt)
         │
         ▼
   Firebase AI Logic Chat   ◄── gemini-3.5-flash + cockpit tools (on device)
   GeminiCommandSession          generateContent-era SDK (Chat / function loop)
         │
         ▼  VoiceCommandState.Result|Error(message)
   PetSpeechCoordinator
         │
         ▼
   petTts CF ─► generateContent ─► gemini-3.1-flash-tts-preview
         │         (wait for full clip)
         ▼
   PetTtsClient ─► MODE_STATIC AudioTrack
```

Relevant code:

| Piece | Path |
|---|---|
| Command + tools | `…/voice/command/GeminiCommandSession.kt` (Firebase AI `generativeModel` + `Chat`) |
| Result → speech | `…/tts/PetSpeechCoordinator.kt` |
| Batch TTS proxy | `firebase/functions/index.js` `exports.petTts` |
| Client + player | `PetTtsClient.kt`, `PetSpeechPlayer.kt` (`MODE_STATIC`) |

Today TTS is a **second, batch** call: we take the toast string, wait for the
full PCM clip, then play with `MODE_STATIC`. That costs latency and keeps the
command stack on a different API family from speech. Flash→TTS via explicit
`output_text` is still Google's documented handoff; the wins are streaming +
one Interactions-shaped pipeline, not inventing a single HTTP body.

## What the docs actually enable

Sources (Interactions / speech / function calling / migrate / May-2026 breaking
changes on ai.google.dev). TTS itself remains **Preview**.

1. **Interactions is the recommended API.** `generateContent` is legacy but
   supported. New multimodal / agent features land on Interactions first.

2. **Flash → TTS handoff (docs-official):** pass `interaction.output_text` into
   a separate TTS `interactions.create`. That is two creates in API terms; one
   product turn. Sample (generate-a-prompt-to-convert-to-audio):

   ```text
   transcript = interactions.create(model=gemini-3.5-flash, input=…)
   audio      = interactions.create(
                   model=gemini-3.1-flash-tts-preview,
                   input=transcript.output_text,   // default handoff
                   response_format={type: audio},
                   generation_config={speech_config: […]})
   ```

   `previous_interaction_id` is first-class for **multi-turn conversation**
   continuation (and Flash tool rounds). It is **not** documented as the
   Flash→TTS speech handoff; treat any TTS+`previous_interaction_id` path as
   experimental only (see P2 spike).

3. **Streaming TTS** (3.1+ only, we already use `gemini-3.1-flash-tts-preview`):

   ```http
   POST /v1beta/interactions
   Headers: x-goog-api-key, Content-Type: application/json
   { model, input, response_format: {type: "audio"},
     generation_config: { speech_config: [{ voice: "Aoede" }] },
     stream: true, store: false }
   ```

   Upstream is **SSE**. Events include `step.delta` where `delta.type == "audio"`
   and `delta.data` is base64 PCM (24 kHz mono 16-bit LE per official samples).
   Also handle `interaction.failed` / mid-stream errors; do not assume only
   audio deltas arrive.

   Note: `Api-Revision: 2026-05-20` was the opt-in for the May-2026 schema.
   After the June 8 2026 legacy sunset the header is ignored; safe to omit or
   send, not required. Use the new schema (`response_format`, `steps` /
   `step.delta`)—never legacy `response_modalities` / `outputs`.

4. **Function calling is multi-interaction**, not one giant call. Stateful loop:

   ```text
   create(tools) → status requires_action + function_call steps
   client runs tools on device
   create(previous_interaction_id, input=[function_result…], tools)
   → model_output text  (or more function_calls)
   ```

5. **Cross-model rule:** next model must accept previous modalities as input.
   Explicit Flash text → TTS text-in is valid. A tool-loop interaction may
   include step types TTS cannot consume—another reason default handoff is
   `output_text`, not replaying the Flash interaction id into TTS.

6. **Cannot** put tools + TTS in a single model invocation: TTS models are
   audio-only, text-in; function calling is unsupported on the TTS model card.
   "One whole thing" means **one turn pipeline**, not one HTTP body.

## Target architecture

### Interaction chain for a voice command

```text
                    ┌─────────────────────────────────────────────┐
                    │  Voice turn (logical Interaction chain)     │
 Mic → STT text ───►│  1..N  gemini-3.5-flash + cockpit tools     │
                    │       previous_interaction_id between rounds│
                    │       (client executes tools on device)     │
                    │  N+1  gemini-3.1-flash-tts-preview stream   │
                    │       input = stylePrefix + final output_text│
                    │       response_format audio, stream true    │
                    └─────────────────────────────────────────────┘
                              │              │
                    toast text│              │PCM chunks
                              ▼              ▼
                         Result UI      MODE_STREAM play
```

### Phased delivery

| Phase | Scope | Why |
|---|---|---|
| **P0** | `petTts` CF → Interactions **stream** TTS; client progressive play | Unblocks latency; minimal surface; keeps current command stack |
| **P1** | Migrate command path to Interactions tool loop (replace Firebase AI `Chat`) | Same API family; server-side history via `previous_interaction_id`; ready to chain |
| **P2** | Unify as one voice-turn coordinator: tools complete → **immediate** TTS via `stylePrefix + reply` (`output_text`); optional experiment for interaction-id handoff | Product "string it together" (still two creates) |

P0 is shippable alone. P1–P2 are the architecture the user is pointing at.

---

## P0 — Stream TTS through Interactions (ship first)

### Server (`petTts`)

Replace:

```text
POST …/models/{tts}:generateContent  →  one JSON { audioBase64, sampleRate, … }
```

With:

```text
POST https://generativelanguage.googleapis.com/v1beta/interactions
Headers: x-goog-api-key, Content-Type: application/json
Body:
{
  "model": "gemini-3.1-flash-tts-preview",
  "input": "<TTS_PROMPT_PREFIX + truncated text>",
  "response_format": { "type": "audio" },
  "generation_config": {
    "speech_config": [ { "voice": "Aoede" } ]
  },
  "stream": true,
  "store": false
}
```

`store: false` for pure TTS: no history retention on speech-only calls
(cheaper, privacy-friendlier). Flash legs that need
`previous_interaction_id` use `store: true` on those creates only.

**Upstream is SSE; proxy to headset as NDJSON** (easier OkHttp line reader than
raw SSE re-emit). Implement a real SSE parser in the CF: buffer partial lines,
ignore comments/heartbeats, parse `event:` / `data:` frames. **Flush each
NDJSON line with `res.write` + chunked encoding**—do not buffer the whole clip
then respond (that kills time-to-first-audio).

Response headers for the streaming contract:

- `Content-Type: application/x-ndjson; charset=utf-8`
- Prefer client `Accept: application/x-ndjson` (or a version query) so old APKs
  that expect one JSON blob fail closed or stay on a batch path during rollout.

```jsonl
{"type":"meta","sampleRate":24000,"model":"gemini-3.1-flash-tts-preview"}
{"type":"audio","pcmBase64":"…"}
{"type":"audio","pcmBase64":"…"}
{"type":"done"}
```

Map upstream stream:

- SSE `step.delta` + `delta.type == "audio"` → `audio` lines (`delta.data`)
- First audio (or completed metadata if present) → `meta` (default rate
  **24000** if mime absent)
- Upstream error before body → existing JSON `{error}` + 4xx/5xx
- Mid-stream / `interaction.failed` → `{"type":"error","error":"…"}` then close
- Retry **once** on empty stream or upstream **500** (TTS preview text-token
  glitch). Do not blindly retry `PROHIBITED_CONTENT`.

PCM is 16-bit LE mono: reassemble odd trailing bytes across chunks before
decode/play.

Keep: Pro gate, App Check, ID token, `MAX_TEXT_CHARS`, `maxInstances: 2`,
`GEMINI_API_KEY` secret, Aoede, prompt prefix (prevents style-instructions
read-aloud / classifier issues per TTS limitations).

### Client

| Component | Change |
|---|---|
| `PetTtsClient` | Stream NDJSON (`Accept: application/x-ndjson`); early first-audio; longer total read timeout than batch 15s; cancel aborts call |
| `PetSpeechPlayer` | `AudioTrack.MODE_STREAM`; prime ~50–100 ms then `play()`; write chunks; hold odd last byte |
| `CloudPetSpeechSynthesizer` | Collect stream into player |
| Coordinator | Unchanged product contract; cancel still aborts HTTP + `stop()` |
| Tests | NDJSON parsers; SSE→NDJSON mapping fixtures; player buffer edge cases (odd last byte) |

### Acceptance (P0)

1. Multi-line NDJSON with ≥2 `audio` events for a ~150-char line.
2. Playback starts before full clip would have downloaded on old path.
3. Supercede cancels stream mid-chunk.
4. Free tier still 403 before Gemini.
5. No key in APK.
6. Old client without stream Accept fails closed (or explicit dual deploy of
   client+CF).

---

## P1 — Command path on Interactions (same product surface)

Migrate `GeminiCommandSession` off Firebase AI `Chat` / `generateContent` onto
Interactions function-calling loop (function-calling + get-started guides).

### Why

- One API family for tools + TTS.
- Server-managed history (`previous_interaction_id`) replaces unbounded local
  `Chat` + manual prune (`MAX_CONTEXT_TOKENS`).
- Enables P2 to speak as soon as tools complete without a separate product
  pipeline (still `output_text` into TTS).

### Key design constraints

- **Tools still run on the headset** (`VoiceCommandController.handleCall`). CF
  cannot open panes; no full server-side agent.
- **Keys still not in APK.** Options:
  1. **Proxy Interactions through Firebase CF** (preferred: one secret, Pro
     gate every hop). Firebase AI Logic today still documents
     `generativeModel` / `Chat` only—do not assume Interactions is exposed.
  2. Keep Firebase AI only if/when it exposes Interactions on the same Google
     AI backend (verify before choosing).
- **Same project + API key for chainable ids.** Interaction ids are only
  portable if Flash and TTS use the same Google AI project/key. Firebase AI
  Chat ids are **not** chainable into raw `petTts` Interactions.
- Re-declare tools + system instruction each interaction (Interaction-scoped;
  not inherited from `previous_interaction_id`).
- Match function-result rules: `call_id` = function_call step `id`, matching
  `name`, one result per call; pin `result` shape to the function-calling docs
  content array.
- Bound rounds (`MAX_TOOL_ROUNDS`); stream optional for text (nice for toast
  typing later; not required for P1).
- `store=true` retention: Paid ~55 days / Free ~1 day. Delete or stop chaining
  on chat reset; tool payloads may land in Google-side logs.

### Stateful loop sketch

```text
id = null
input = user transcript (+ cockpit_context envelope)
loop:
  interaction = create(
    model=gemini-3.5-flash,
    input=input,
    tools=CommandFunctions,
    system_instruction=…,
    previous_interaction_id=id,
    store=true)
  id = interaction.id
  if function_call steps:
    results = run on device
    input = function_result steps
    continue
  else:
    reply = interaction.output_text
    break
// P0/P2: speak(stylePrefix + reply)
```

### Non-goals for P1

- Changing tool schema / system prompt semantics.
- Streaming the tool-call arguments UI (optional later).
- Dropping Deepgram (STT stays CF `stt`).

---

## P2 — One voice-turn chain (Flash → speak)

After P0 + P1:

```text
VoiceTurn.run(transcript):
  reply, lastInteractionId = toolLoop(transcript)   // Interactions Flash
  showToast(reply)
  if speakEnabled:
    streamSpeak(
      // Default (docs-official, always works):
      input = stylePrefix + reply   // interaction.output_text
      model = gemini-3.1-flash-tts-preview
      stream = true, response_format = audio, store = false
      // Optional experiment only (same API key as Flash; may fail
      // cross-model / speak whole tool trace—spike first):
      //   previous_interaction_id = lastInteractionId
      //   input = "Speak only the final assistant reply. …"
    )
```

Product "one chain" is still **two** `interactions.create` calls. That matches
Google's own Flash→TTS sample.

### Validation spike (optional; before any interaction-id TTS path)

Prove whether TTS with `previous_interaction_id=<flash id>` + a short director
prompt correctly speaks **only** the last `model_output` (not the whole tool
trace / user transcript), on the same key/project as Flash. If not, keep
explicit `input=output_text` forever.

### Optional CF shape for P2

`petTts` accepts:

```json
{ "text": "…" }
```

Optional later (only after spike + same-key constraint):

```json
{ "previousInteractionId": "…", "mode": "speak_last_reply" }
```

Server builds TTS Interactions request; headset only streams audio. Keeps key
server-side and unifies billing/Pro checks.

---

## Explicit non-goals

- Gemini **Live API** (unstructured bidirectional audio).
- Multi-speaker TTS / voice picker.
- Moving cockpit tool execution into Cloud Functions.
- Dropping visual toasts.
- `streamGenerateContent` as the long-term path (P0 may keep it as emergency
  fallback only if Interactions stream is flaky; default is Interactions).
- Treating Flash→TTS `previous_interaction_id` as the default handoff without
  a proven spike.

## Risks / landmines

| Risk | Mitigation |
|---|---|
| TTS preview 500 / text-token glitch | Retry once on empty stream / 500 |
| Style prefix spoken aloud | Keep labeled `### TRANSCRIPT` preamble |
| Firebase AI vs raw Interactions dual stack during P0 | Accept temporary dual stack; P1 collapses |
| `store=true` retention of command history | Privacy notes; delete / stop chain on chat reset |
| MODE_STREAM underruns on XR | Prime buffer; tune min buffer size |
| Dual APK/CF contract | `Accept: application/x-ndjson` or ship client+CF together |
| Interaction id from Firebase AI into `petTts` | Not portable; P1 Flash must share Google AI key with TTS |
| SSE parse bugs / buffered CF response | Real SSE parser; flush NDJSON lines |
| Cross-model TTS via `previous_interaction_id` | Default `output_text`; spike before any id handoff |

## References

- https://ai.google.dev/gemini-api/docs/interactions-overview  
- https://ai.google.dev/gemini-api/docs/speech-generation (Interactions + streaming)  
- https://ai.google.dev/gemini-api/docs/function-calling (Interactions tool loop)  
- https://ai.google.dev/gemini-api/docs/get-started  
- https://ai.google.dev/gemini-api/docs/migrate-to-interactions  
- https://ai.google.dev/gemini-api/docs/interactions-breaking-changes-may-2026  
- In-repo: `GeminiCommandSession.kt`, `firebase/functions/index.js` `petTts`, `PetTtsClient.kt`
