# Monetization: Free vs Pro

Orb XR ships two tiers on Google Play:

| Tier | What you get | Cost model |
|---|---|---|
| **Free** | Full terminal cockpit — panes, SSH, tmux resume, window mirror, host keyboard. **No ongoing AI features after first flight.** | No ongoing metered AI cost |
| **Pro** (`pro_monthly`) | All AI voice features: dictation (Deepgram Nova-3), Gemini voice commands, talking voice pet (Gemini Flash TTS). Fully managed — no user API keys anywhere. | $4.99/mo, 7-day free trial, billed via Play |

There is **no BYO-key path**. All metered AI runs against our server-side keys,
gated by a server-checked entitlement.

First flight is a bounded exception to the Free/Pro steady state: a new user
may receive one server-owned onboarding entitlement for up to 24 hours, and the
app ends it when onboarding completes or is skipped. It cannot replace a paid
entitlement or be claimed again after completion.

## Architecture

```
Headset APK ──(ID token + App Check)──▶ Cloud Functions (us-central1)
   │                                        ├─ stt            Deepgram proxy   Entitled-only
   │                                        ├─ petTts         Gemini TTS proxy Entitled-only
   │                                        ├─ begin/completeOnboardingAccess  bounded first flight
   │                                        └─ verifyPurchase Play token check → entitlement
   │
   ├─ Play Billing (billing-ktx) ──▶ purchase token ──▶ verifyPurchase
   └─ Firestore listener ◀── entitlements/{uid} ◀── verifyPurchase / playRtdn
                                                     ▲
Play RTDN ──▶ Pub/Sub topic play-rtdn ──▶ playRtdn ──┘  (renewal / cancel / revoke)
```

- **Entitlement truth is server-side.** `entitlements/{uid}` in Firestore is
  written only by functions (rules deny client writes). The client mirrors it
  (`EntitlementStore`) for UI gating, but `stt`/`petTts` re-check Pro on every
  request — a patched APK gets 403s, not free AI.
- **Onboarding access is server-owned and one-shot.** Entitlement documents
  record `source`, claim, and completion fields. Backend checks give
  onboarding-sourced grants no expiry slack; paid Play grants retain the
  RTDN-lag slack. The Android UI mirror does not retain `source` and uses its
  generic cached slack, but that optimistic cache cannot authorize `stt` or
  `petTts`. Completion revokes only an onboarding source, never an active paid
  purchase.
- **Auth:** anonymous Firebase Auth (`FirebaseGate.ensureSignedIn`), plus App
  Check (Play Integrity in release, debug provider in debug builds).
- **Gemini voice commands** still run client-side via Firebase AI Logic, gated
  client-side by `isProProvider` in `GeminiCommandSession` (indie-standard: App
  Check + client gate; the expensive STT/TTS paths are fully server-enforced).

## Play Console objects (already created via `gplay` CLI)

| Object | Value |
|---|---|
| Subscription product | `pro_monthly` ("Orb XR Pro") |
| Base plan | `monthly` — P1M, $4.99 base (auto-converted regional prices, regions version 2025/03), grace P7D, ACTIVE |
| Offer | `free-trial-7d` — 1× P7D free phase, new-subscriber acquisition, ACTIVE |
| RTDN topic | `projects/ghosttyxr-headset/topics/play-rtdn` (Play publisher role bound) |

**Remaining manual step:** paste the RTDN topic name into
Play Console → Monetization setup → Real-time developer notifications, and save.
Then send a test notification; `playRtdn` logs "RTDN without subscriptionNotification".

## Firebase / GCP (project `ghosttyxr-headset`)

- Firestore database `(default)` (nam5) + `firebase/firestore.rules` deployed.
- Anonymous sign-in enabled (Identity Toolkit).
- App Check: Play Integrity config registered for the Android app.
  - **Manual:** for debug builds / the XR AVD, register the emulator's debug
    App Check token in Firebase console → App Check → Apps → Manage debug tokens
    (the token prints in logcat on first run).
  - **Manual (recommended):** turn ON App Check *enforcement* for Firebase AI
    Logic in the Firebase console once a release with this build is out.
- Secrets (Secret Manager): `GEMINI_API_KEY`, `DEEPGRAM_API_KEY`, `PLAY_SA_KEY`
  (the Play service-account JSON used by `verifyPurchase`/`playRtdn`).

**Play service account:** `play-console-cli@ghosttyxr-play-2026.iam.gserviceaccount.com`
must have Play Console permission to view financial data / manage orders for
Orb XR (it already manages releases; verify "View financial data" is
granted so `purchases.subscriptionsv2.get` works in prod).

## Functions (`firebase/functions/`)

| Function | Auth | Purpose |
|---|---|---|
| `stt` | ID token + App Check + Pro | Deepgram `/v1/listen` proxy (base64 PCM in JSON, `{transcript}` out) |
| `petTts` | ID token + App Check + Pro | Gemini Flash TTS proxy; streams NDJSON PCM to current clients and retains negotiated legacy JSON |
| `beginOnboardingAccess` | ID token + App Check | Atomically claim the caller's one bounded first-flight entitlement |
| `completeOnboardingAccess` | ID token + App Check | Mark first flight complete and revoke only onboarding-sourced access |
| `verifyPurchase` | ID token + App Check | Verify + acknowledge a Play purchase token, write entitlement |
| `playRtdn` | Pub/Sub `play-rtdn` | Re-verify on renewal/cancel/revoke, update entitlement |

Deploy: `cd firebase && npx firebase-tools deploy --only firestore:rules,functions --project ghosttyxr-headset`

## Android map

| Piece | Path |
|---|---|
| Tier model + cached UI expiry slack | `app/.../billing/Entitlement.kt` |
| Firestore mirror + prefs cache | `app/.../billing/EntitlementStore.kt` |
| Anonymous sign-in + token fetch | `app/.../billing/FirebaseGate.kt` |
| Play Billing connect/purchase/restore | `app/.../billing/BillingController.kt` |
| Begin/complete first-flight access | `app/.../billing/OnboardingAccessClient.kt` |
| verifyPurchase HTTP client | `app/.../billing/VerifyPurchaseClient.kt` |
| STT proxy client (replaces DeepgramClient) | `app/.../terminal/voice/CloudSttClient.kt` |
| App Check provider install | `app/.../GhosttyXRApplication.kt` |
| Paywall UI | `app/.../ui/panel/ProSection.kt` |

Voice affordances for free users surface
`"Voice features need Orb XR Pro — upgrade in Settings."` instead of recording.
During first flight the server-issued onboarding entitlement drives the same
`EntitlementStore` and backend checks; there is no separate client bypass.

## Dev backdoor (test all Pro features without buying)

Debug builds on this machine bypass the per-install churn entirely — survives
`adb uninstall`, data wipes, and 40 reinstalls a day:

1. **Fixed dev account** — `devauth.email` / `devauth.password` in the
   gitignored `ghosttyxr/android/local.properties`. Debug builds sign into this
   account (`FirebaseGate.ensureSignedIn`) instead of anonymous auth, so the
   uid is stable. That uid's `entitlements/{uid}` doc is hand-set to `pro`
   (expiry ~10 years, `purchaseToken: "dev-backdoor"`); `playRtdn` never
   touches it because no real purchase token matches.
2. **Fixed App Check debug token** — `devappcheck.token` in local.properties,
   pre-registered in the Firebase console ("dev-machine-fixed").
   `GhosttyXRApplication.seedFixedDebugToken` writes it into the debug
   provider's persistence store before init, so no per-install console
   approval is ever needed.

Both fields compile to `""` in CI and release builds, making the paths
unreachable outside this machine. To grant Pro to another uid manually:

```bash
cd firebase/functions && GOOGLE_CLOUD_PROJECT=ghosttyxr-headset node -e '
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
initializeApp();
getFirestore().collection("entitlements").doc(process.argv[1]).set({
  tier: "pro", expiryTimeMillis: Date.now() + 315e9, purchaseToken: "dev-backdoor",
}).then(() => console.log("done"));
' <UID>
```

## Testing purchases

1. Play Console → Setup → License testing: add tester Gmail accounts.
2. Upload this build to the **internal testing** track (billing only works in
   Play-distributed builds, not sideloads).
3. Testers see test-card instruments; subscriptions renew on an accelerated
   schedule (5-min months) for license testers.
4. Verify: purchase → `verifyPurchase` logs "Entitlement granted" → voice unlocks;
   cancel → RTDN → entitlement flips after expiry.

## Cost guardrails

- `stt`: `maxInstances: 4`, 12 MB PCM cap, Pro-checked per call.
- `petTts`: bounded instances and input length, entitlement-checked per call.
- Deepgram `mip_opt_out=true` (no training retention).
- Rough COGS ceiling per heavy Pro user: ~$1–1.50/mo vs $4.24 net after Play's 15%.
