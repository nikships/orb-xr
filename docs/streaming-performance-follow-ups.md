# Desktop streaming performance follow-ups

## Native SSH read path

Resolved July 2026. `ghosttyxr_ssh.zig` keeps a reusable read buffer on each SSH
session and polls the libssh2 socket for up to 8 ms before retrying
`LIBSSH2_ERROR_EAGAIN`. This removes steady-state allocation churn and reduces
empty-read spin without adding a long input stall. The rebuilt arm64
`libghosttyxr.so` ships the change.

This also fixes the *ceiling* of that path, and it is worth stating plainly: the
SSH tunnel does one synchronous JNI round-trip per read or write, under a single
lock. That is fine for terminal bytes and hopeless for tens of Mbps of video.
Media must never ride the tunnel.

## Window mirror media path

Rewritten July 2026. Media now rides a **dedicated direct LAN TCP socket** (port
`45876`) with length-prefixed slices. Control, input, and metadata stay on the
SSH-tunnelled channel (port `45874`).

### Why this replaced WebRTC

WebRTC was adopted to get pacing, bandwidth estimation, NACK/RTX, and jitter
buffering for free. In practice, on a single-hop LAN, those mechanisms were the
problem rather than the solution. Four concrete faults were diagnosed:

1. **Unbounded jitter buffer.** The headset never bounded playout delay, so the
   buffer grew monotonically. This is the direct cause of the "gets laggier the
   longer it runs" symptom — the stream was not losing frames, it was
   accumulating delay.
2. **Every adaptation was a full sender restart.** Bitrate or aspect changes tore
   down the transceiver and renegotiated SDP, which is a guaranteed visible
   freeze. Adaptation fired on high RTT, aspect change, and every stall — so the
   reaction to a hiccup was worse than the hiccup.
3. **Congestion control had nowhere to go.** `degradationPreference` forbade
   degradation, so when the estimator decided to send less, bits piled up in the
   pacer instead.
4. **Freeze recovery was teardown, not resync.** A stall blacklisted HD for the
   rest of the session, and no receiver-driven keyframe request existed.

The common thread: every mechanism intended to absorb jitter instead stored it.

### The current design

The governing principle is **never let a queue form anywhere**, rather than
*buffer enough to hide jitter*. Latency stays bounded because very little is ever
in flight — not because something is smoothing it out.

- **Capture:** ScreenCaptureKit NV12 (the encoder ingests 4:2:0 natively),
  `minimumFrameInterval` at 90 fps, and a deliberately shallow `queueDepth`. A
  deeper queue only enables handing the encoder stale frames.
- **Encode:** VideoToolbox H.264 or HEVC, user-selectable, with
  `RealTime`, `AllowFrameReordering = false`, `MaxFrameDelayCount = 0`, and
  High/Main profile. Critically, `AverageBitRate` is paired with
  **`DataRateLimits`** over a 0.5 s window: a hard short-window ceiling is what
  actually bounds queueing delay, and it means the encoder itself cannot emit a
  burst large enough to build a queue in the socket.
- **Infinite GOP.** `MaxKeyFrameInterval` is effectively unbounded, so there is
  no recurring multi-hundred-KB IDR spike to hitch on. Keyframes are produced
  only when the headset asks for one.
- **Transport:** plain TCP with `NoDelay`, one slice written and flushed per NAL.
  No FEC, no pacer, no congestion controller, no jitter buffer. TCP retransmit is
  the only loss recovery, which is the right trade on one LAN hop.
- **Adaptation:** the headset measures real end-to-end latency and reports it; the
  Mac steers bitrate via `VTSessionSetProperty` on the **live** session. Nothing
  is torn down and nothing is renegotiated, so adaptation is invisible.
- **Recovery:** the headset detects a slice-sequence gap and sends
  `keyFrameRequest`. Repair costs one frame instead of a renegotiation, and is
  rate-limited so one loss cannot cause an IDR storm.
- **Backpressure:** if the send backlog exceeds 4 MiB the Mac drops
  *non-keyframe* slices only. Dropping a keyframe would strand the headset until
  the next request, which is strictly worse than the queueing it avoids.
- **Encryption:** AES-GCM per slice, keyed from the per-session token, on by
  default and disableable for a trusted LAN. Input and control always stay inside
  the SSH tunnel regardless.

Removing `io.github.webrtc-sdk` also took roughly 30 MB off the APK.

### What to watch

The acceptance criterion for this path is a **30+ minute soak with flat latency**.
Average latency is not the interesting number; the *slope* is. A creeping figure
means a queue has formed somewhere and the core invariant is broken.

The Mac and headset use lockstep window-mirror protocol **feature version 5**.
Version 4 was the WebRTC era and is intentionally rejected rather than
downgraded to.
