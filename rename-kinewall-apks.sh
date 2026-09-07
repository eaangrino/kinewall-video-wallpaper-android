#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_FILE="$ROOT_DIR/app/build.gradle.kts"

DEBUG_SOURCE="$ROOT_DIR/app/debug/app-debug.apk"
RELEASE_SOURCE="$ROOT_DIR/app/release/app-release.apk"

REPO="eaangrino/kinewall-video-wallpaper-android"

if [[ ! -f "$GRADLE_FILE" ]]; then
    echo "Error: app/build.gradle.kts not found."
    exit 1
fi

VERSION_NAME="$(
    sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$GRADLE_FILE" |
    head -n 1
)"

if [[ -z "$VERSION_NAME" ]]; then
    echo "Error: Could not read versionName from app/build.gradle.kts."
    exit 1
fi

TAG="v${VERSION_NAME}"
EXPECTED_RELEASE_NAME="kinewall - ${VERSION_NAME}"

DEBUG_TARGET="$ROOT_DIR/app/debug/kinewall-${VERSION_NAME}-debug.apk"
RELEASE_TARGET="$ROOT_DIR/app/release/kinewall-${VERSION_NAME}.apk"

if [[ ! -f "$DEBUG_SOURCE" ]]; then
    echo "Error: Debug APK not found:"
    echo "  app/debug/app-debug.apk"
    exit 1
fi

if [[ ! -f "$RELEASE_SOURCE" ]]; then
    echo "Error: Release APK not found:"
    echo "  app/release/app-release.apk"
    exit 1
fi

rm -f "$DEBUG_TARGET" "$RELEASE_TARGET"

mv "$DEBUG_SOURCE" "$DEBUG_TARGET"
mv "$RELEASE_SOURCE" "$RELEASE_TARGET"

echo "Renamed:"
echo "  app/debug/kinewall-${VERSION_NAME}-debug.apk"
echo "  app/release/kinewall-${VERSION_NAME}.apk"

if ! command -v gh >/dev/null 2>&1; then
    echo "Error: GitHub CLI (gh) is not installed."
    exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "Error: GitHub CLI is not authenticated."
    echo "Run: gh auth login"
    exit 1
fi

if ! gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "Error: Release with tag '$TAG' does not exist."
    echo "Expected release:"
    echo "  Tag:   $TAG"
    echo "  Title: $EXPECTED_RELEASE_NAME"
    exit 1
fi

RELEASE_NAME="$(
    gh release view "$TAG" \
        --repo "$REPO" \
        --json name \
        --jq '.name'
)"

if [[ "$RELEASE_NAME" != "$EXPECTED_RELEASE_NAME" ]]; then
    echo "Error: Release name does not match the expected naming convention."
    echo "  Expected: $EXPECTED_RELEASE_NAME"
    echo "  Found:    $RELEASE_NAME"
    exit 1
fi

echo
echo "Uploading APKs to GitHub Release:"
echo "  Tag:     $TAG"
echo "  Release: $RELEASE_NAME"

gh release upload "$TAG" \
    "$DEBUG_TARGET" \
    "$RELEASE_TARGET" \
    --repo "$REPO"

echo
echo "Upload completed:"
echo "  kinewall-${VERSION_NAME}-debug.apk"
echo "  kinewall-${VERSION_NAME}.apk"
