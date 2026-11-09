# Building

[← README](../README.md) · [Features](features.md) · [Usage](usage.md) · [Architecture](architecture.md) · [Building](building.md) · [Validation](validation.md) · [Dictionaries](dictionaries.md) · [Roadmap](roadmap.md)

## Android app

No Android Studio required.

```bash
# 1. Toolchain — JDK 21 + Android CLI (Linux example, no root needed)
curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
mkdir -p ~/Android/tools && curl -sSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" | tar xz -C ~/Android/tools
export JAVA_HOME=$(echo ~/Android/tools/jdk-21*)      # macOS: brew install openjdk@21
export ANDROID_HOME=$HOME/Android/Sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# 2. Android SDK packages
android sdk install platform-tools platforms/android-36 build-tools/35.0.0 emulator \
                    system-images/android-35/google_apis_playstore/x86_64
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 3. Lint, build & test (the same gate as PR CI, .github/workflows/ci.yml)
./gradlew detekt detektDebug detektDebugUnitTest   # detekt: every rule + ktlint + Compose rules
./gradlew lintDebug                  # Android Lint: every check, warnings are errors
./gradlew testDebugUnitTest          # 265 JVM unit tests, incl. ArchitectureTest and property tests
./gradlew connectedDebugAndroidTest  # 4 on-device tests (VAD + AAC encoding)
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk

# 4. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are labelled "Droid Sentence Miner (Dev)", have a localhost debug API for driving the app over adb (see [`.opencode/skills/dsm-debug-api`](../.opencode/skills/dsm-debug-api/SKILL.md)) and are signed with the committed `app/debug.keystore`, so they upgrade each other in place. Release builds come from the [release workflow](../.github/workflows/release.yml) on a `v*` tag.

## Linting and module rules

Strict by default, so the codebase stays consistent as more people contribute:

- **detekt 2** with `allRules = true` (`config/detekt/detekt.yml`): all detekt rules, the ktlint
  wrapper (`ktlint_official` style, 140 columns, `.editorconfig`) and the Compose rules, warnings
  fail the build. `detekt` checks syntax; `detektDebug`/`detektDebugUnitTest` add type resolution.
- **Android Lint** with `checkAllWarnings` + `warningsAsErrors` (`app/build.gradle.kts`).
- **ArchitectureTest** (Konsist, runs with the unit tests): generic code never imports a language
  package, language packages don't import each other, every `LanguageModule` lives in
  `lang/<code>/` and is registered, no hard-coded kana/kanji checks outside `lang/ja`, ONNX
  Runtime/ML Kit only in `ocr/`, the AnkiDroid API only in `anki/`, main code never imports the
  debug API, no asset over 1 MB. Each failure message names the files and the fix.

Findings that predate the linter are recorded in `app/detekt-baseline*.xml` and
`app/lint-baseline.xml`. New code must be clean, and touching an old file can resurface its
findings (baseline entries are tied to the code): fix them in the same change. Regenerate a
baseline (`./gradlew detektBaseline detektBaselineDebug detektBaselineDebugUnitTest`, or delete
`lint-baseline.xml` and run `lintDebug` twice) only after fixing old findings, never to hide new
ones. How to write code that passes the first time (the shapes ktlint accepts, conflicting
rules, when a `@Suppress` with a reason is right, editing legacy code without resurfacing its
baseline): `.opencode/skills/dsm-lint/SKILL.md`. `./gradlew detekt -PdetektAutoCorrect` applies
ktlint's formatting fixes — to *every* file, so revert files you didn't mean to touch.

**Property-based tests** (kotest-property, `app/src/test/.../properties/`) check invariants on
generated input where examples can't cover the space: every language module's scanner, deinflector
and OCR helpers (`LanguagePropertiesTest`), the Anki field mapper and card sizing
(`AnkiPropertiesTest`), version ordering and the What's new selection (`VersionPropertiesTest`).
They found two bugs on the first run (a *Sentence Audio* field filled while sentence audio was
off; a wrong test assumption about curly apostrophes). Use them for parsers, scanners, mappers
and anything with an "always/never" rule; keep example tests for specific language facts.

## Language packs

Per-language OCR models are not in the APK: `tools/lang-packs/build_pack.py <lang> <out>` builds
`dsm-lang-<lang>-<version>.zip` from pinned Hugging Face revisions and prints the SHA-256 to put in
the language module's `pack`. Publish it as a release that is **not** the latest (the in-app
updater reads `releases/latest`):

```bash
gh release create lang-en-1 out/dsm-lang-en-1.zip --latest=false --title "Language pack: English OCR v1"
```

## Website and docs

The marketing site and these docs live in [`site/`](../site/) — Astro 7, Starlight for the docs and
Tailwind CSS v4 for the landing page. It is a separate Node project; the Android build ignores it.
Strict TypeScript and Biome run in CI, and `.github/workflows/site.yml` deploys `site/dist` to
GitHub Pages on every push that touches the folder.

```bash
cd site
pnpm install
pnpm dev      # http://localhost:4321/droid-sentence-miner/
pnpm check    # astro check + biome check --error-on-warnings
pnpm build    # site/dist
```
