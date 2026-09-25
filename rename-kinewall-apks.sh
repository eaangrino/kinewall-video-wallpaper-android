#!/usr/bin/env bash
set -Eeuo pipefail

readonly REPO="eaangrino/kinewall-video-wallpaper-android"
readonly MAIN_BRANCH="main"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_FILE="$ROOT_DIR/app/build.gradle.kts"

DEBUG_ORIGINAL="$ROOT_DIR/app/debug/app-debug.apk"
RELEASE_ORIGINAL="$ROOT_DIR/app/release/app-release.apk"

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

for command in git gh sed; do
    command -v "$command" >/dev/null 2>&1 ||
        fail "Required command not found: $command"
done

git rev-parse --is-inside-work-tree >/dev/null 2>&1 ||
    fail "Run this script inside the KineWall Android Git repository."

cd "$ROOT_DIR"

[[ -f "$GRADLE_FILE" ]] ||
    fail "app/build.gradle.kts not found."

VERSION_NAME="$(
    sed -nE \
        's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' \
        "$GRADLE_FILE" |
        head -n 1
)"

[[ -n "$VERSION_NAME" ]] ||
    fail "Could not read versionName from app/build.gradle.kts."

[[ "$VERSION_NAME" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] ||
    fail "versionName must use X.Y.Z format. Found: $VERSION_NAME"

readonly TAG="v${VERSION_NAME}"
readonly EXPECTED_RELEASE_NAME="KineWall - ${VERSION_NAME}"

DEBUG_TARGET="$ROOT_DIR/app/debug/kinewall-${VERSION_NAME}-debug.apk"
RELEASE_TARGET="$ROOT_DIR/app/release/kinewall-${VERSION_NAME}.apk"

current_branch="$(git branch --show-current)"

[[ "$current_branch" == "$MAIN_BRANCH" ]] ||
    fail "Release must be created from '$MAIN_BRANCH'. Current branch: '${current_branch:-detached HEAD}'"

if ! git diff --quiet || ! git diff --cached --quiet; then
    fail "There are uncommitted tracked changes. Commit or discard them before releasing."
fi

gh auth status --hostname github.com >/dev/null 2>&1 ||
    fail "GitHub CLI is not authenticated. Run: gh auth login"

printf 'Fetching %s...\n' "$MAIN_BRANCH"
git fetch origin "$MAIN_BRANCH" --prune

head_sha="$(git rev-parse HEAD)"
origin_sha="$(git rev-parse "origin/$MAIN_BRANCH")"

[[ "$head_sha" == "$origin_sha" ]] ||
    fail "Local '$MAIN_BRANCH' does not match origin/$MAIN_BRANCH. Pull or push your changes first."

prepare_apk() {
    local original="$1"
    local target="$2"
    local label="$3"

    if [[ -f "$original" ]]; then
        rm -f "$target"
        mv "$original" "$target"
        printf '%s APK renamed: %s\n' "$label" "${target#"$ROOT_DIR/"}"
        return
    fi

    if [[ -f "$target" ]]; then
        printf '%s APK already ready: %s\n' "$label" "${target#"$ROOT_DIR/"}"
        return
    fi

    fail "$label APK not found. Expected either:
  ${original#"$ROOT_DIR/"}
  ${target#"$ROOT_DIR/"}"
}

prepare_apk "$DEBUG_ORIGINAL" "$DEBUG_TARGET" "Debug"
prepare_apk "$RELEASE_ORIGINAL" "$RELEASE_TARGET" "Release"

printf '\nAPKs ready:\n'
printf '  %s\n' "${DEBUG_TARGET#"$ROOT_DIR/"}"
printf '  %s\n' "${RELEASE_TARGET#"$ROOT_DIR/"}"

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    RELEASE_NAME="$(
        gh release view "$TAG" \
            --repo "$REPO" \
            --json name \
            --jq '.name'
    )"

    [[ "$RELEASE_NAME" == "$EXPECTED_RELEASE_NAME" ]] ||
        fail "Release name does not match.
Expected: $EXPECTED_RELEASE_NAME
Found:    $RELEASE_NAME"

    printf '\nRelease already exists. Uploading/replacing APKs:\n'
    printf '  Tag:     %s\n' "$TAG"
    printf '  Release: %s\n' "$RELEASE_NAME"

    gh release upload "$TAG" \
        "$DEBUG_TARGET" \
        "$RELEASE_TARGET" \
        --repo "$REPO" \
        --clobber
else
    if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
        printf '\nTag %s already exists; creating GitHub Release from it...\n' "$TAG"

        gh release create "$TAG" \
            "$DEBUG_TARGET" \
            "$RELEASE_TARGET" \
            --repo "$REPO" \
            --verify-tag \
            --title "$EXPECTED_RELEASE_NAME" \
            --generate-notes
    else
        printf '\nCreating tag and GitHub Release %s from commit %s...\n' \
            "$TAG" "$head_sha"

        gh release create "$TAG" \
            "$DEBUG_TARGET" \
            "$RELEASE_TARGET" \
            --repo "$REPO" \
            --target "$head_sha" \
            --title "$EXPECTED_RELEASE_NAME" \
            --generate-notes
    fi
fi

printf '\nFetching tag locally...\n'
git fetch origin "refs/tags/$TAG:refs/tags/$TAG" 2>/dev/null || true

printf '\nRelease completed successfully:\n'
printf '  Tag:     %s\n' "$TAG"
printf '  Release: %s\n' "$EXPECTED_RELEASE_NAME"
printf '  Debug:   kinewall-%s-debug.apk\n' "$VERSION_NAME"
printf '  Release: kinewall-%s.apk\n' "$VERSION_NAME"

printf '\n'
gh release view "$TAG" --repo "$REPO"
