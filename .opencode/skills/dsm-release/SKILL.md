---
name: dsm-release
description: Publish a Droid Sentence Miner release (signed APK on GitHub Releases) by pushing a v* tag. Use ONLY when the user explicitly asks to release, cut/tag/publish a version, or ship an APK — never as part of a normal commit/push.
metadata:
  audience: developers
  project: droid-sentence-miner
---

# Releasing

**Only release when the user explicitly asks for it.** Pushing a `v*` tag is the release: it is
public and people download the APK. Never create or push tags on your own, not even after a
"commit and push".

## How it works

`.github/workflows/release.yml` runs on every pushed tag matching `v*`:

1. Decodes the release keystore from the `DSM_KEYSTORE_BASE64` secret.
2. `./gradlew assembleRelease -PversionName=<tag without v> -PversionCode=<git rev-list --count HEAD>`
   (R8 minify + resource shrink, release signing from `DSM_KEYSTORE*` / `DSM_KEY_*` env vars in
   `app/build.gradle.kts`).
3. `gh release create <tag> droid-sentence-miner-<tag>.apk --generate-notes`.

The release app id is `io.github.wilsonnet.droidsentenceminer` (no `.debug` suffix), so it
installs next to debug builds; they have separate data.

**Every published release reaches installed apps** (0.5.0+): `update/AppUpdater` asks
`api.github.com/repos/WilsonNet/droid-sentence-miner/releases/latest` when Home opens, compares
its tag with `BuildConfig.VERSION_NAME` (`AppVersion`, semver) and offers the first `.apk` asset.
The APK must keep its name pattern `*.apk` and the release must not be a draft/prerelease (those
are not `latest`). Debug builds never check.

Testing an update end to end (done for 0.5.0 → 0.5.1 on the Xiaomi Pad 7): install release N from
GitHub (`gh release download vN -p '*.apk'`), publish N+1, open the app → Home banner → *Update to
vN+1* (download with progress) → the first time Android's "install unknown apps" page for DSM opens
(`appops set io.github.wilsonnet.droidsentenceminer REQUEST_INSTALL_PACKAGES allow` is the same
switch) → back in the app *Install vN+1* → system dialog このアプリを更新しますか? → 更新. Uninstall
the release app afterwards. HyperOS shows a 10 s "USB経由でインストール" prompt for an adb install of a
new package; run `adb install` in the background and tap インストール (landscape Pad 7: 1384,1322) —
otherwise it is cancelled (`INSTALL_FAILED_USER_RESTRICTED`). Check the result with `adb shell dumpsys package io.github.wilsonnet.droidsentenceminer |
grep versionName`.

## Steps

```bash
git status                       # clean, on main, everything pushed
git log --oneline $(git describe --tags --abbrev=0 2>/dev/null)..HEAD   # what's in it
# Pick the version with the user (semver; 0.x while pre-1.0)
# CHANGELOG.md: rename "## [Unreleased]" to "## [0.2.0] — <date>", add a fresh empty
# "## [Unreleased]" above it, update the compare links at the bottom; commit + push first.
git tag -a v0.2.0 -m "v0.2.0"
git -c credential.helper= -c credential.helper='!gh auth git-credential' \
  push https://github.com/WilsonNet/droid-sentence-miner.git v0.2.0
gh run watch $(gh run list -w Release -L1 --json databaseId -q '.[0].databaseId')
gh release view v0.2.0           # APK attached?
```

A failed run leaves no release; fix, then delete and re-push the tag
(`git push --delete <remote> v0.2.0 && git tag -d v0.2.0`) — only with the user's OK.

## Signing key

- Keystore: `~/.android-keys/dsm-release.jks` (alias `dsm`, PKCS12, store and key password in
  `~/.android-keys/dsm-release.password`) on the maintainer's Mac. **Back it up** — losing it
  means users must uninstall to get updates. Never commit it (`*.keystore`/`.jks` are local only).
- Repo secrets: `DSM_KEYSTORE_BASE64`, `DSM_KEYSTORE_PASSWORD`, `DSM_KEY_ALIAS`, `DSM_KEY_PASSWORD`.
  Setting them (maintainer runs this):

  ```bash
  PW=$(cat ~/.android-keys/dsm-release.password)
  base64 -i ~/.android-keys/dsm-release.jks | gh secret set DSM_KEYSTORE_BASE64
  printf '%s' "$PW" | gh secret set DSM_KEYSTORE_PASSWORD
  printf '%s' "$PW" | gh secret set DSM_KEY_PASSWORD
  printf dsm | gh secret set DSM_KEY_ALIAS
  ```

## Local release build (to test R8 before tagging)

```bash
export DSM_KEYSTORE=~/.android-keys/dsm-release.jks DSM_KEY_ALIAS=dsm
export DSM_KEYSTORE_PASSWORD=$(cat ~/.android-keys/dsm-release.password) DSM_KEY_PASSWORD=$DSM_KEYSTORE_PASSWORD
./gradlew assembleRelease -PversionName=0.0.0-test --console=plain
```

Install it and smoke-test what R8 can break (there is no debug API in release builds): Home →
*Run built-in sample* with meikiocr selected (ONNX/JNI), install a dictionary (Room + import), tap a
word (lookup popup). The first release build crashed in ONNX Runtime until its classes were kept.

Without `DSM_KEYSTORE` the release APK is unsigned (not installable). On the Xiaomi Pad the first
adb install of the release package is cancelled by HyperOS — push it to `/sdcard/Download` and
install from the file manager once.
