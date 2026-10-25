---
name: wisdom-increaser
description: Harvest hard-won lessons, device coordinates, debugging recipes, and measured numbers at the end of every task into AGENTS.md, skills (.opencode/skills/*), and README.md so the project gets wiser after each session. Use at the end of every non-trivial task, benchmark, or debugging session before committing.
metadata:
  audience: developers
  project: droid-sentence-miner
---

# Wisdom Increaser — making the project wiser after every task

Every non-trivial task (debugging, device automation, OCR/audio benchmarking, UI/overlay work)
uncovers friction: a coordinate that missed, a process that froze, an Android/HyperOS/One UI quirk,
or a hypothesis that measured slower. If those lessons stay in the chat transcript, the next agent
session repeats the same mistakes from scratch.

Run this pass together with `dsm-docs` at the end of every task before committing.

## Step-by-step harvest

1. **Review where time was lost in this session**:
   - Did an `adb` tap miss a button or hit an overlay layer? Record the exact bounds/coordinates and
     why (`uiautomator dump`, overlay interception, pause screen) in `.opencode/skills/dsm-navigation/SKILL.md`.
   - Did a Debug API call time out, return stale state, or leave side effects (e.g. `/ocr_bench`
     leaving non-default options loaded, `/autoocr?on=1` bringing `MainActivity` forward)? Document
     it in `.opencode/skills/dsm-debug-api/SKILL.md`.
   - Did an optimization or model/thread/buffer experiment succeed or fail? Record the exact
     measured numbers, device, dataset, and dead ends in `.opencode/skills/dsm-ocr-eval/SKILL.md`
     and `README.md` (*Validation* / *Roadmap*).
   - Did you hit a non-obvious codebase invariant or Android OS behavior? Add a terse bullet with
     code pointers in `AGENTS.md` → *Gotchas learned the hard way*.

2. **Route each lesson to its home**:

   | Type of wisdom | Where it belongs |
   | --- | --- |
   | Codebase invariant, Android/OEM quirk, architecture gotcha | `AGENTS.md` (*Gotchas learned the hard way*, 1 terse bullet + code pointers) |
   | App/game/dialog navigation, coordinates, `adb` UI flows | `.opencode/skills/dsm-navigation/SKILL.md` |
   | Debug HTTP API endpoints, parameters, state fields, recipes | `.opencode/skills/dsm-debug-api/SKILL.md` + endpoint list in `AGENTS.md` |
   | OCR accuracy/latency/CPU benchmarks, A/B recipes, dead ends | `.opencode/skills/dsm-ocr-eval/SKILL.md` + `README.md` (*Validation*) |
   | Promo screenshots, demo videos, GIF recipes | `.opencode/skills/dsm-promo-media/SKILL.md` |
   | Release tagging, signing, CI | `.opencode/skills/dsm-release/SKILL.md` |
   | User-visible features, usage, validation, roadmap items | `README.md` (see `.opencode/skills/dsm-docs/SKILL.md`) |

3. **Keep skill pointers synchronized**:
   - All skills live in `.opencode/skills/<name>/SKILL.md` and are exposed to Claude Code via
     `.claude/skills` and Antigravity via `.agents/skills` (symlinks to `../.opencode/skills`).
   - Whenever a new skill is added, list it in `AGENTS.md` (both under *Every task ends with a docs
     pass* and *Project layout*) and in `.opencode/skills/dsm-docs/SKILL.md`.

4. **Quality bar**:
   - **Measured numbers only**: always state the device (`Galaxy S24+`, `Xiaomi Pad 7`), resolution,
     and workload (idle, YouTube, DroidDeck + Nightshade).
   - **Terse and actionable**: prefer copy-pasteable commands and exact coordinates over prose.
   - **Prune obsolete claims**: if a roadmap item was validated or a behavior changed, update or
     remove the old text in the same commit.
