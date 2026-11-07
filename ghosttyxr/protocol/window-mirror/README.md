# Window-mirror compatibility contract

Language-neutral source of truth for the GhosttyXR / Orb XR window-mirror
wire seam. Kotlin (Android headset) and Swift (Mac companion) each keep a native
runtime adapter; both test suites load these fixtures.

## Layout

| File | Role |
|---|---|
| `contract-v1.json` | Relay + media ports, wire/feature versions, frame table, defaults, rules |
| `vectors/valid.json` | Frozen valid frames (hex + expected decoded values + fragmentation) |
| `vectors/invalid.json` | Malformed frames and expected recovery |

## Adapters

| Platform | Protocol module | Incremental decoder | Relay |
|---|---|---|---|
| Android | `…/windowmirror/WindowMirrorProtocol.kt` | `WindowMirrorFrameDecoder` | `WindowMirrorRelayController` |
| Mac | `…/Services/WindowMirrorProtocol.swift` | `WindowMirrorFrameDecoder` | `WindowMirrorRelayServer` |

## Rules of change

1. Prefer editing this contract + vectors first.
2. Update **both** adapters and their unit tests in the same PR.
3. Do not bump wire version (`1`), feature version (`5`), ports (`45874` control,
   `45876` media), max
   frame size (`8 MiB`), or renumber frames without an intentional protocol
   change and dual-platform rollout plan.
4. JSON object key order is not part of compatibility. Freeze complete bytes for
   header-only / deliberately canonical frames; otherwise compare the six-byte
   header exactly and JSON payloads structurally.

See [`docs/architecture.md`](../../../docs/architecture.md) §"Window-mirror
compatibility seam".
