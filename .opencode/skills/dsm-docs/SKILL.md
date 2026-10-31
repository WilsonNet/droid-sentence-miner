---
name: dsm-docs
description: Keep Droid Sentence Miner's markdown docs in sync with the code. Use at the end of every task that changes behavior, UI, settings, build/toolchain, debug API endpoints, test numbers or dependencies — before committing — or when asked to "update the docs/markdowns" or to check/clean up the roadmap (docs/roadmap.md: verify each item against the code, drop shipped ones).
metadata:
  audience: developers
  project: droid-sentence-miner
---

# Keeping the docs current

Every task ends with a docs pass, in the same commit as the code (or a follow-up commit pushed
together). Stale docs cost the next agent hours: they trust AGENTS.md and the skills.

## Which file owns what

| Changed… | Update |
| --- | --- |
| User-visible feature, setting, workflow | `docs/features.md` (full description) and/or `docs/usage.md`; the one-line list in `README.md` → *What it does* only for headline features; `CHANGELOG.md` → *Unreleased*; user guides in `site/src/content/docs/` |
| Architecture / pipeline / dictionary format support | `docs/architecture.md` |
| Measured accuracy/timing, test counts | `docs/validation.md` (numbers + how measured); test count in `docs/building.md` |
| Something deferred / known limitation | `docs/roadmap.md` (remove items once shipped; they go to `CHANGELOG.md`; full check in step 5 below) |
| Every user-visible change, fix or removal | `CHANGELOG.md` → *Unreleased* (Added / Changed / Fixed), one user-facing line each |
| New third-party code, model, idea source | `README.md` → *Credits* (+ license if it ships or is downloaded) |
| Build steps, SDK setup | `docs/building.md` (+ `AGENTS.md` → *Toolchain* for agents) |
| Toolchain, versions, build flags, devices | `AGENTS.md` → *Toolchain* / *Common commands* |
| Non-obvious behavior, pitfall, design invariant | `AGENTS.md` → *Gotchas learned the hard way* (what + why + where in code) |
| New package/dir | `AGENTS.md` → *Project layout* |
| Debug API endpoint/param/state field | `.opencode/skills/dsm-debug-api/SKILL.md` table + recipe, and the endpoint list in `AGENTS.md` |
| README screenshots / demo videos | `docs/screenshots`, `docs/videos` — recipe in `.opencode/skills/dsm-promo-media/SKILL.md` |
| Release workflow, signing, versioning | `.opencode/skills/dsm-release/SKILL.md` (+ `.github/workflows/release.yml`) |
| OCR pipeline/engine or its benchmark | `.opencode/skills/dsm-ocr-eval/SKILL.md` (+ `tools/ocr-eval/`) |
| Sentence audio / mic fallback chain or its measurements | `.opencode/skills/dsm-audio/SKILL.md` (+ `tools/mic-eval/`) |
| Nightshade gameplay flows/coordinates | `.opencode/skills/dsm-nightshade/SKILL.md` |
| App/game/dialog navigation, coordinates, adb UI flows | `.opencode/skills/dsm-navigation/SKILL.md` |
| End-of-task wisdom harvest workflow | `.opencode/skills/wisdom-increaser/SKILL.md` |
| Website landing copy, docs pages, deploy workflow | `site/README.md` + `README.md` website URLs + `AGENTS.md` → *Website* |

`README.md` is the project's front page for humans: what it is, demo, short feature list,
download/quick start, where the docs are, how to contribute, credits, license. Keep it short —
details belong in `docs/`, history in `CHANGELOG.md`. A release (`dsm-release`) turns
*Unreleased* into the new version's section.

## Checklist

1. `git diff --stat` — list every behavior change in this task.
2. For each, find its row above and edit that section. Remove or rewrite statements the change made
   false (grep the old names: `grep -rn "<old setting/endpoint>" *.md .opencode`).
3. Numbers only from runs you actually did; say how they were measured (device, data set).
4. Keep AGENTS.md terse: one bullet per gotcha, code pointers in backticks.
5. **Roadmap cleanup** (`docs/roadmap.md`): read every item and check it against the code, not
   memory — `grep -rn` for the feature (an intent filter, a class, a pref, a debug endpoint) or
   try it over the debug API (e.g. `/lookup_bench?text=` for a deinflection item, `/popup.html` for
   popup content). Shipped → delete it from the roadmap and make sure CHANGELOG *Unreleased* has
   the user-facing line; partly done → rewrite it to what is left; a number in an item (latency,
   accuracy) → re-measure or drop it. Add gaps this task found but didn't fix. Never leave an item
   the code already does: the 2026-10 cleanup found 6 of 15 items shipped or fixed (faster mining,
   pitch in the popup, share-sheet text, AnkiDroid media names, noun + で deinflection, share a
   photo into Book).
6. Commit docs with the code; push (see AGENTS.md → *Git*).
