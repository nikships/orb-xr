# website/ — Orb XR marketing site

Static marketing site for **Orb XR**.
Product story, Galaxy XR CTA, Play + GitHub links, voice suite demos, privacy
page. Not the headset app, not the companion download backend contracts.

Live URL: **https://ghosttyxr-headset.web.app/**  
Firebase project: **`ghosttyxr-headset`** (Hosting only from this package)

## Stack

| Piece | Value |
|---|---|
| Framework | Next.js 16 App Router (`output: "export"`) |
| UI | React 19, TypeScript, Tailwind CSS v4, shadcn/ui |
| Motion | GSAP, Lenis smooth scroll |
| Node | **24** (`.nvmrc` + `package.json` engines) |
| Env vars | **None** — fully static; no `NEXT_PUBLIC_*` |
| Output | `out/` → Firebase Hosting |

## Commands

```bash
cd website
npm ci
npm run dev        # next dev
npm run build      # static export → out/
npm run lint
npm run typecheck
npm run check      # lint + typecheck + build
```

Local Hosting deploy (prefer CI; see landmines):

```bash
npm run build
npx firebase-tools@15 deploy --only hosting --project ghosttyxr-headset --force
```

## Layout

```text
website/
├─ package.json, next.config.ts, firebase.json, .firebaserc, .nvmrc
├─ AGENTS.md, README.md
├─ src/
│  ├─ app/           layout.tsx, page.tsx, globals.css, favicon
│  ├─ components/    Header, Hero, ScrollCinema, FeatureRail, BentoGrid,
│  │                 HardwareStrip, VoiceCta, VoiceSuite, Footer, MacBook,
│  │                 TerminalWindow, feature-visuals, SmoothScroll, voice/, ui/
│  ├─ data/voice.ts  feature copy, orb palettes, scenarios
│  ├─ lib/utils.ts
│  └─ types/voice.ts
└─ public/
   ├─ images/orbxr/   hero / coach / orb mark shots
   ├─ images/products/    samsung-galaxy-xr, glasses, palm
   ├─ privacy/index.html  static privacy → /privacy/
   ├─ seo/, fonts/, audio/, videos/
```

Landing assembly order in `page.tsx`: SmoothScroll → Header → Hero →
ScrollCinema → FeatureRail → BentoGrid → HardwareStrip → VoiceCta →
VoiceSuite → Footer.

## Deploy (CI)

[`.github/workflows/website-deploy.yml`](../.github/workflows/website-deploy.yml)

- Trigger: push to `main` under `website/**` (or manual dispatch)
- Steps: `npm ci` → lint → typecheck → build → verify `out/index.html` →
  Firebase Hosting deploy with secret
  `FIREBASE_SERVICE_ACCOUNT_GHOSTTYXR_HEADSET` and `--force`

## Product truth (do not invent)

- Play name **Orb XR**, package `com.ghosttyxr.app`
- Headset is a thin client; agents/shells run on the **Mac over SSH**
- Free = full spatial workspace without AI voice; Pro unlocks managed voice AI
  (no BYO Deepgram/Gemini keys)
- CTAs: Google Play listing + GitHub `anand-92/ghostty-vibe-xr`
- Keep feature demos aligned with shipping product (panes, voice pet, coach,
  Mac host). Do not claim on-headset agent execution.

## Landmines

1. **Static export only.** Keep `output: "export"`, `images.unoptimized`, and
   `trailingSlash: true` in sync with `firebase.json` Hosting.
2. **Hosting ownership.** This package deploys the **entire** Hosting site for
   `ghosttyxr-headset` with `--force`. Redeploying `firebase/public` hosting
   overwrites the marketing root (and vice versa). Coordinate companion
   download artifacts if both need to share the domain.
3. **No secrets.** Do not add API keys, service accounts, or private endpoints
   to the static site.
4. **Upstream template leftovers.** `package.json` name still says
   `ai-website-clone-template` in places; ignore template clone-website docs.
   This folder is the Orb XR marketing site.
5. Prefer Conventional Commits with `feat(website):` / `fix(website):` /
   `docs(website):`.

## Related

- Root: [`../AGENTS.md`](../AGENTS.md)
- Product vision: [`../VISION.md`](../VISION.md)
- Backend / companion mini-site history: [`../firebase/AGENTS.md`](../firebase/AGENTS.md)
- Play listing: [`../store/play/AGENTS.md`](../store/play/AGENTS.md)
