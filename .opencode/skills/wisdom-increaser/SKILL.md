---
name: wisdom-increaser
description: Prune stale/redundant documentation and harvest hard-won lessons, device coordinates, debugging recipes, and measured numbers at the end of every task into AGENTS.md, skills (.opencode/skills/*), and README.md. Use at the end of every non-trivial task, benchmark, or debugging session before committing.
metadata:
  audience: developers
  project: droid-sentence-miner
---

# Wisdom Increaser — making the project lighter and wiser after every task

Every non-trivial task changes what is true about the codebase and uncovers friction (`adb`
coordinates that missed, Android/HyperOS/One UI quirks, hypotheses that measured slower, or old
notes that are no longer true).

**Removing is always more important than adding.** Stale, verbose, or duplicated text taxes every
future agent session's context window and attention. Before adding any new note, prune what is
obsolete, redundant, or overly wordy so the docs become sharper and lighter.

Run this pass together with `dsm-docs` at the end of every task before committing.

## Step 1: Prune and check for staleness (do this first)

1. **Delete superseded claims and numbers**:
   - Did a default change (e.g. thread count, spinning, frame polling, model cut)? Replace old
     defaults everywhere (`AGENTS.md`, `README.md`, skills) rather than appending a contradiction.
   - Did a *Roadmap* hypothesis in `README.md` get validated or disproved? Remove it from *Roadmap*
     (or narrow it to what truly remains open) and put the measured outcome in *Validation*.
   - Did a bug/gotcha get fixed in code so agents no longer need a manual workaround? Delete the
     manual workaround from the docs or compress it to a 1-line architectural invariant.
2. **Deduplicate across files**:
   - Keep each fact in **one canonical place** and point to it from elsewhere:
     - `AGENTS.md`: 1 terse bullet per codebase invariant / gotcha (`ClassName.method`), pointing
       to the skill for full tables/recipes.
     - `.opencode/skills/*`: exact commands, coordinate tables, and benchmark recipes.
     - `README.md`: user-facing behavior, architecture summary, and headline validation numbers.
3. **Compress prose**:
   - Turn multi-sentence war stories into 1-line rules with exact symbols, coordinates, or numbers.
   - If a paragraph can be a 5-word bullet without losing signal, cut the paragraph.

## Step 2: Harvest high-signal lessons from this session

Only add lessons that save real time or prevent real bugs in future sessions:

1. **Where time was lost**:
   - Did an `adb` tap miss a button, hit an overlay, or trigger PiP? Record the exact bounds and
     sequence in `.opencode/skills/dsm-navigation/SKILL.md`.
   - Did a Debug API call have a non-obvious side effect? Fix the API if possible, or document it in
     `.opencode/skills/dsm-debug-api/SKILL.md`.
   - Did an optimization or model/thread/buffer experiment succeed or fail? Record the measured
     numbers, device, workload, and dead ends in `.opencode/skills/dsm-ocr-eval/SKILL.md` and
     `README.md` (*Validation*).
   - Did you hit a non-obvious Android/OEM or codebase invariant? Add a terse bullet with code
     pointers in `AGENTS.md` → *Gotchas learned the hard way*.

2. **Canonical home for each type of wisdom**:

   | Type of wisdom | Canonical home |
   | --- | --- |
   | Codebase invariant, Android/OEM quirk, architecture gotcha | `AGENTS.md` (*Gotchas learned the hard way*, 1 terse bullet + code pointers) |
   | App/game/dialog navigation, coordinates, `adb` UI flows | `.opencode/skills/dsm-navigation/SKILL.md` |
   | Debug HTTP API endpoints, parameters, state fields, recipes | `.opencode/skills/dsm-debug-api/SKILL.md` + endpoint list in `AGENTS.md` |
   | OCR accuracy/latency/CPU benchmarks, A/B recipes, dead ends | `.opencode/skills/dsm-ocr-eval/SKILL.md` + `README.md` (*Validation*) |
   | Promo screenshots, demo videos, GIF recipes | `.opencode/skills/dsm-promo-media/SKILL.md` |
   | Release tagging, signing, CI | `.opencode/skills/dsm-release/SKILL.md` |
   | User-visible features, usage, validation, roadmap items | `README.md` (see `.opencode/skills/dsm-docs/SKILL.md`) |

3. **Keep skill pointers synchronized**:
   - All skills live in `.opencode/skills/<name>/SKILL.md` (symlinked into `.claude/skills` and
     `.agents/skills`).
   - Whenever a skill is added or removed, update `AGENTS.md` and `.opencode/skills/dsm-docs/SKILL.md`.
