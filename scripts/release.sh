#!/usr/bin/env bash
# Build, verify and publish a Warden release to GitHub Releases.
#
#   scripts/release.sh 1.2.3            build, verify, tag v1.2.3, push the tag, create the release
#   scripts/release.sh 1.2.3 --dry-run  build and verify only; no git checks, no tag, no upload
#
# Needs: ~/.android/keystores/warden/{release.keystore,signing.env}, the Android SDK build-tools (apksigner),
# and an authenticated `gh`. See docs/RELEASING.md.
set -euo pipefail

# Public SHA-256 of the release signing certificate (also published in the README). The script
# refuses to publish an APK signed with anything else.
EXPECTED_CERT_SHA256="96d1023748eafd441ae6ce8872aad979306a37b8564a396f08c43ce8db4106a4"

die() { echo "release: $*" >&2; exit 1; }

VERSION="${1:-}"
DRY_RUN=false
[[ "${2:-}" == "--dry-run" ]] && DRY_RUN=true
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: scripts/release.sh MAJOR.MINOR.PATCH [--dry-run]"
TAG="v$VERSION"

cd "$(git rev-parse --show-toplevel)"

# --- preconditions ----------------------------------------------------------------------------
grep -qx "VERSION_NAME=$VERSION" gradle.properties || die "gradle.properties VERSION_NAME is not $VERSION"
NOTES=$(awk -v v="$VERSION" 'index($0, "## [" v "]") == 1 {f=1; next} /^## \[/ && f {exit} f' CHANGELOG.md)
[[ -n "${NOTES//[[:space:]]/}" ]] || die "CHANGELOG.md has no notes under '## [$VERSION]'"

if ! $DRY_RUN; then
    command -v gh >/dev/null || die "gh is not installed"
    # Checked first: logged out, `gh release view` below fails and reads as "no release yet", and
    # the problem would only surface after the tag is already pushed.
    gh auth status >/dev/null 2>&1 || die "gh is not logged in; run 'gh auth login'"
    [[ "$(git branch --show-current)" == "main" ]] || die "not on main"
    [[ -z "$(git status --porcelain)" ]] || die "working tree is not clean"
    git fetch --quiet --tags origin main
    [[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "main is not in sync with origin/main; push first"
    ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "tag $TAG already exists"
    ! git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null || die "tag $TAG already exists on origin"
    ! gh release view "$TAG" >/dev/null 2>&1 || die "GitHub release $TAG already exists"
    # Android refuses to install a lower versionCode over a higher one, so never go backwards.
    LAST=$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' | sed 's/^v//' | sort -V | tail -1)
    if [[ -n "$LAST" ]]; then
        [[ "$(printf '%s\n%s\n' "$LAST" "$VERSION" | sort -V | tail -1)" == "$VERSION" ]] \
            || die "$VERSION is lower than the last release $LAST"
    fi
fi

# --- build -------------------------------------------------------------------------------------
./gradlew --no-daemon clean lintRelease assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
MAPPING="app/build/outputs/mapping/release/mapping.txt"
[[ -f "$APK" ]] || die "no signed release APK at $APK"

# --- verify the signature ----------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
APKSIGNER=$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
[[ -x "$APKSIGNER" ]] || die "apksigner not found under $SDK/build-tools"
"$APKSIGNER" verify --verbose "$APK" | grep -q "Verified using v2 scheme (APK Signature Scheme v2): true" \
    || die "APK is not v2-signed"
ACTUAL=$("$APKSIGNER" verify --print-certs "$APK" | awk -F': ' '/Signer #1 certificate SHA-256 digest/ {print tolower($2); exit}')
[[ "$ACTUAL" == "$EXPECTED_CERT_SHA256" ]] || die "APK is signed with an unexpected certificate ($ACTUAL)"

# --- stage the assets --------------------------------------------------------------------------
mkdir -p dist
OUT="dist/warden.apk"
cp "$APK" "$OUT"
(cd dist && shasum -a 256 "warden.apk" > "warden.apk.sha256")
[[ -f "$MAPPING" ]] && cp "$MAPPING" "dist/warden-mapping.txt"
SHA=$(cut -d' ' -f1 "$OUT.sha256")
echo "release: built $OUT"
echo "release: sha256 $SHA"

if $DRY_RUN; then
    echo "release: dry run - nothing tagged or uploaded"
    exit 0
fi

# --- publish -----------------------------------------------------------------------------------
if [[ -t 0 ]]; then
    read -r -p "Tag $TAG, push it and publish the GitHub release? [y/N] " answer
    [[ "$answer" == "y" || "$answer" == "Y" ]] || die "aborted"
fi

BODY="$NOTES

---
**Install:** download \`warden.apk\`, then run the [one-time grant](https://github.com/abdulsamad/warden#-one-time-setup) from the README.

**SHA-256** of \`warden.apk\`: \`$SHA\`
**Signing certificate SHA-256:** \`$EXPECTED_CERT_SHA256\`"

git tag -a "$TAG" -m "Warden $VERSION"
git push origin "$TAG"
ASSETS=("$OUT" "$OUT.sha256")
[[ -f "dist/warden-mapping.txt" ]] && ASSETS+=("dist/warden-mapping.txt")
gh release create "$TAG" "${ASSETS[@]}" --verify-tag --title "Warden $VERSION" --notes "$BODY"
echo "release: published $TAG"
