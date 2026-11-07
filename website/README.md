# Orb XR marketing site

Next.js static site for **Orb XR**.

- Live: https://ghosttyxr-headset.web.app/
- Agent guide: [`AGENTS.md`](AGENTS.md)

```bash
cd website
npm ci
npm run dev      # local
npm run check    # lint + typecheck + static export
```

CI deploys `out/` to Firebase Hosting project `ghosttyxr-headset` on push to
`main` under `website/**`.
