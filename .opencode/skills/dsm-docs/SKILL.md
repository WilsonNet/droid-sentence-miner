---
name: dsm-docs
description: Keep Droid Sentence Miner's markdown docs in sync with the code. Use at the end of every task that changes behavior, UI, settings, build/toolchain, debug API endpoints, test numbers or dependencies — before committing — or when asked to "update the docs/markdowns".
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
| User-visible feature, setting, workflow | `README.md` → *Features*, *Usage*, (if architecture) *How it works* |
| Measured accuracy/timing, test counts | `README.md` → *Validation* (numbers + how measured); `./gradlew testDebugUnitTest` count in *Building* |
| Something deferred / known limitation | `README.md` → *Roadmap* |
| New third-party code, model, idea source | `README.md` → *Credits* (+ license if it ships or is downloaded) |
| Toolchain, versions, build flags, devices | `AGENTS.md` → *Toolchain* / *Common commands* |
| Non-obvious behavior, pitfall, design invariant | `AGENTS.md` → *Gotchas learned the hard way* (what + why + where in code) |
| New package/dir | `AGENTS.md` → *Project layout* |
| Debug API endpoint/param/state field | `.opencode/skills/dsm-debug-api/SKILL.md` table + recipe, and the endpoint list in `AGENTS.md` |
| README screenshots / demo videos | `docs/screenshots`, `docs/videos` — recipe in `.opencode/skills/dsm-promo-media/SKILL.md` |
| Release workflow, signing, versioning | `.opencode/skills/dsm-release/SKILL.md` (+ `.github/workflows/release.yml`) |
| OCR pipeline/engine or its benchmark | `.opencode/skills/dsm-ocr-eval/SKILL.md` (+ `tools/ocr-eval/`) |
| App/game/dialog navigation, coordinates, adb UI flows | `.opencode/skills/dsm-navigation/SKILL.md` |
| End-of-task wisdom harvest workflow | `.opencode/skills/wisdom-increaser/SKILL.md` |

## Checklist

1. `git diff --stat` — list every behavior change in this task.
2. For each, find its row above and edit that section. Remove or rewrite statements the change made
   false (grep the old names: `grep -rn "<old setting/endpoint>" *.md .opencode`).
3. Numbers only from runs you actually did; say how they were measured (device, data set).
4. Keep AGENTS.md terse: one bullet per gotcha, code pointers in backticks.
5. Commit docs with the code; push (see AGENTS.md → *Git*).
