# Building

[← README](../README.md) · [Features](features.md) · [Usage](usage.md) · [Architecture](architecture.md) · [Building](building.md) · [Validation](validation.md) · [Roadmap](roadmap.md)

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

# 3. Build & test
./gradlew testDebugUnitTest          # 191 JVM unit tests
./gradlew connectedDebugAndroidTest  # 4 on-device tests (VAD + AAC encoding)
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk

# 4. Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are labelled "Droid Sentence Miner (Dev)", have a localhost debug API for driving the app over adb (see [`.opencode/skills/dsm-debug-api`](../.opencode/skills/dsm-debug-api/SKILL.md)) and are signed with the committed `app/debug.keystore`, so they upgrade each other in place. Release builds come from the [release workflow](../.github/workflows/release.yml) on a `v*` tag.

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
