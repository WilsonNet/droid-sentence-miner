# Droid Sentence Miner — website

Marketing site and documentation for [Droid Sentence Miner](https://github.com/WilsonNet/droid-sentence-miner).

- **Live:** <https://wilsonnet.github.io/droid-sentence-miner/>
- **Docs:** <https://wilsonnet.github.io/droid-sentence-miner/docs/>

## Stack

- [Astro 7](https://astro.build/) — static output.
- [Starlight](https://starlight.astro.build/) — documentation theme and search.
- [Tailwind CSS v4](https://tailwindcss.com/) — landing page, via `@tailwindcss/vite` (no config file).
- [Biome 2](https://biomejs.dev/) — formatter and linter, all rules and nursery enabled.
- TypeScript — Astro `strictest` preset plus `noUncheckedIndexedAccess`,
  `exactOptionalPropertyTypes`, `noPropertyAccessFromIndexSignature`, `noUncheckedSideEffectImports`
  and `verbatimModuleSyntax`.

Biome runs with `--error-on-warnings`. Exceptions are deliberate and live in `biome.json`: Astro
template false positives (`noUnusedVariables`, `noUnusedImports`, `noUnresolvedImports`), the
`package.json` sorter (a Biome 2.6 write hang), the reduced-motion `!important` block, design-number
data in the landing page, and a Baseline target of _newly available_ rather than _widely available_.

## Develop

```bash
pnpm install
pnpm dev
```

Open <http://localhost:4321/droid-sentence-miner/>. The docs are under `/docs/`.

## Verify

```bash
pnpm check       # astro check + biome check --error-on-warnings
pnpm check:fix   # apply safe Biome fixes
pnpm build       # static output in dist/
```

`pnpm check` must pass before pushing. The GitHub workflow runs the same command and deploys
`site/dist` to GitHub Pages on every push that touches `site/`.

## Layout

```
src/pages/index.astro              landing page
src/layouts/BaseLayout.astro       head, SEO, analytics-free scripts
src/components/                    header, footer, phone mockup, icons
src/content/docs/docs/             Starlight docs (all pages under /docs/)
src/styles/landing.css             Tailwind theme + landing animations
src/styles/starlight.css           docs theme
src/assets/screenshots/            optimized at build
public/media/                      demo MP4s and posters
public/og.png                      Open Graph image
```

## Deployment

`.github/workflows/site.yml` builds and deploys on pushes to `main`. The repository's Pages
source must be **GitHub Actions**. Astro is configured with:

```ts
site: "https://wilsonnet.github.io";
base: "/droid-sentence-miner";
```

All internal links go through `src/lib/url.ts` (`withBase`) or Starlight's link handling. If the
repository or custom domain changes, update `site`, `base`, `public/robots.txt`, the manifest and
the workflow together.

## Content rules

- The landing page must stay truthful to `README.md`; numbers come from measured runs.
- Screenshots and demo videos are shared with the README. Refresh them with the
  `dsm-promo-media` skill, then update both copies.
- Keep the docs in `src/content/docs/docs/`; the site is English and uses relative links inside
  the docs collection so the GitHub Pages base path keeps working.
