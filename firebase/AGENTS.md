# Firebase — entitlement and companion-download backend

Firebase project `ghosttyxr-headset` contains the Node 20 Cloud Functions,
Firestore entitlement rules, and the small Hosting site used to download the
Mac companion. No AI or Play service-account key may ship in the APK.

Voice runs entirely on-device through the Gemini Live API (`gemini-3.8-live` via
Firebase AI Logic): mic audio streams in, model speech + function calls stream back.
There is no server STT/TTS path — the old `stt` (Deepgram proxy) and `petTts` (Gemini
TTS streaming) functions, their `lib/pet-tts.js` / `lib/sse.js` adapters, and the
`GEMINI_API_KEY` / `DEEPGRAM_API_KEY` secrets are deleted. The Pro gate for voice is
the Firestore entitlement doc (read client-side) + Firebase Auth/App Check identity
on the Live session; there is no per-request server voice check anymore.

See [`../docs/monetization.md`](../docs/monetization.md) for the Free/Pro and
first-flight model. When changing an HTTP contract, keep its Android client in
the same change (`OnboardingAccessClient` or `VerifyPurchaseClient`).

## Functions

| Function | Trigger | Gate | Purpose |
|---|---|---|---|
| `beginOnboardingAccess` | HTTPS POST | ID token + App Check | Atomically claim the caller's one bounded first-flight grant |
| `completeOnboardingAccess` | HTTPS POST | ID token + App Check | Complete first flight and revoke only an onboarding-sourced grant |
| `verifyPurchase` | HTTPS POST | ID token + App Check | Verify/acknowledge a Play subscription and write entitlement truth |
| `playRtdn` | Pub/Sub `play-rtdn` | Google-managed trigger | Re-verify renewals/cancellations/revocations and update entitlement truth |

All functions run in `us-central1`. HTTPS clients use the stable pattern:

```text
https://us-central1-ghosttyxr-headset.cloudfunctions.net/<functionName>
```

Changing the project, region, or function name requires updating the matching
hardcoded Android endpoint before shipping a new APK.

## Layout

```text
firebase/
├─ .firebaserc                 default project: ghosttyxr-headset
├─ firebase.json               Hosting, Firestore, and Functions deployment config
├─ firestore.rules             users read only their entitlement; writes are server-only
├─ public/                     companion download site + privacy page
└─ functions/
   ├─ index.js                 exported HTTP/Pub/Sub functions and request contracts
   ├─ lib/
   │  ├─ auth.js               Firebase ID-token + App Check verification
   │  ├─ entitlements.js       Firestore Free/Pro/onboarding transitions
   │  └─ play.js               Android Publisher subscription verification
   ├─ test/                    host-Node tests (`node --test`)
   └─ package.json             Node 20 runtime and dependencies
```

Function secrets are `PLAY_SA_KEY`
(the Play service-account JSON). Keep them in Secret Manager; never copy them
into source, Hosting, Android resources, build properties, fixtures, or logs.

## Shared HTTP gate

Every HTTPS function accepts POST only and requires:

- `Authorization: Bearer <Firebase ID token>`; and
- `X-Firebase-AppCheck: <App Check token>`.

Voice has no server endpoint: the headset streams mic audio straight to the Gemini
Live API over a Firebase-AI-Logic session (Auth + App Check identity, Pro entitlement
read from Firestore client-side). There is no per-request server voice check anymore.
Missing/invalid authentication returns 401, missing entitlement returns 403,
and non-POST requests return 405.

Firestore rules allow a signed-in user to read only
`entitlements/{theirUid}` and deny every client write. Admin SDK writes in
`lib/entitlements.js` are the source of truth. In particular:

- paid grants use `source: "play"` and allow the documented RTDN-lag slack;
- onboarding grants use `source: "onboarding"`, expire without slack, and may
  be claimed once for at most 24 hours; and
- `completeOnboardingAccess` never revokes a paid Play entitlement.

## Request contracts

- `beginOnboardingAccess` / `completeOnboardingAccess`: empty POST body →
  `{ "tier", "expiryTimeMillis", "source" }`.
- `verifyPurchase`: `{ "purchaseToken": "...", "productId": "pro_monthly" }`
  → Free/Pro entitlement summary. The server verifies against
  `purchases.subscriptionsv2`, acknowledges when necessary, and writes
  Firestore.
- `playRtdn`: subscription notifications are re-fetched from Play; never trust
  the Pub/Sub payload alone as entitlement proof.

## Test and deploy

```bash
cd firebase/functions
npm install
npm test

# From firebase/ with the Play secret configured:
npx firebase-tools deploy \
  --only firestore:rules,functions \
  --project ghosttyxr-headset
# Hosting: see landmine #7 — marketing site CI owns the live Hosting root
```

Set or rotate the Play secret from `firebase/`, for example:

```bash
npx firebase-tools functions:secrets:set PLAY_SA_KEY \
  --project ghosttyxr-headset
```

The checked-in tests cover entitlement transitions. An unauthenticated
`curl` is not a successful smoke test: production endpoints require real ID
and App Check tokens. After deployment, smoke through the app or supply both
headers deliberately, and never print the token values in logs.

## Landmines

1. The Pro entitlement doc is the voice gate: a patched APK must still read 403-free
   only when entitled. UI gating is only a convenience.
2. Preserve PCM frame integrity. Odd Live audio chunk boundaries are legal; the
   Android assembler joins them into 16-bit frames. Do not relabel compressed
   audio as raw PCM.
3. Do not raise instance ceilings casually; Live sessions incur provider cost.
4. `PLAY_SA_KEY` must remain server-side. RTDN identifies a purchase token but
   the Publisher API re-verification decides entitlement.
5. Hosting's companion artifact and privacy page are public. Never place
   credentials, local configuration, or non-distribution builds under
   `firebase/public/`.

6. **Hosting coexistence with the marketing site.** CI deploys `website/out` to
   the same Firebase project (`ghosttyxr-headset`) Hosting root with `--force`.
   Prefer deploying **functions + firestore:rules** from this package for day-to-day
   backend work:

   ```bash
   npx firebase-tools deploy \
     --only firestore:rules,functions \
     --project ghosttyxr-headset
   ```

   Only redeploy Hosting from `firebase/public/` when intentionally replacing
   the live marketing root (or after coordinating a combined site layout).
   Companion download artifacts under `public/` must stay free of secrets.
