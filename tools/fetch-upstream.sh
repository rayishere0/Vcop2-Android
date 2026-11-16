#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0-or-later
# Fetch pinned upstream vcop2-vr, verify HEAD, apply the overlay patches in
# series order, and overlay the flat/ files. Fails loudly on any mismatch.
# Usage: fetch-upstream.sh <build-dir> [overlay-root]
#   <build-dir>    Directory that will hold the upstream checkout (created
#                  if missing). Must contain no spaces (MAME build rule).
#   [overlay-root] Directory holding patches/, flat/, upstream-lock.json.
#                  Defaults to the parent of this script's directory.
set -euo pipefail

PIN="def8d32914911d03c9c8d24680bf21b3f869524c"
URL="https://github.com/vr4all-standalone/vcop2-vr"

fail() { echo "fetch-upstream: ERROR: $*" >&2; exit 1; }

[ $# -ge 1 ] || fail "missing <build-dir>. Usage: $0 <build-dir> [overlay-root]"
BUILD_DIR_INPUT="$1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OVERLAY_ROOT="${2:-$(dirname "$SCRIPT_DIR")}"
OVERLAY_ROOT="$(cd "$OVERLAY_ROOT" && pwd)"

# The pin in this script must agree with upstream-lock.json.
grep -q "$PIN" "$OVERLAY_ROOT/upstream-lock.json" || fail "pin $PIN not found in $OVERLAY_ROOT/upstream-lock.json"
grep -q "$URL" "$OVERLAY_ROOT/upstream-lock.json" || fail "URL $URL not found in $OVERLAY_ROOT/upstream-lock.json"

mkdir -p "$BUILD_DIR_INPUT"
BUILD_DIR="$(cd "$BUILD_DIR_INPUT" && pwd)"
case "$BUILD_DIR" in *[[:space:]]*) fail "build dir must contain no spaces: $BUILD_DIR";; esac

SRC="$BUILD_DIR/vcop2-vr"
[ -e "$SRC" ] && fail "$SRC already exists; delete it to re-fetch"

echo "fetch-upstream: cloning $URL ..."
git clone "$URL" "$SRC"
git -C "$SRC" checkout -q "$PIN"
HEAD="$(git -C "$SRC" rev-parse HEAD)"
[ "$HEAD" = "$PIN" ] || fail "HEAD mismatch after checkout: got $HEAD, want $PIN"
echo "fetch-upstream: HEAD verified: $HEAD"

echo "fetch-upstream: applying patches in series order ..."
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in ''|\#*) continue;; esac
  patch="$(echo "$line" | tr -d '[:space:]')"
  [ -n "$patch" ] || continue
  patch_file="$OVERLAY_ROOT/patches/$patch"
  [ -f "$patch_file" ] || fail "patch file missing: $patch_file"
  git -C "$SRC" apply --check "$patch_file" || fail "git apply --check failed for $patch"
  git -C "$SRC" apply "$patch_file" || fail "git apply failed for $patch"
  echo "fetch-upstream: applied $patch"
done < "$OVERLAY_ROOT/patches/series"

echo "fetch-upstream: overlaying flat/ files ..."
cp -R "$OVERLAY_ROOT/flat/." "$SRC/"
[ -f "$SRC/CMakeLists.txt" ] || fail "overlay failed: CMakeLists.txt missing"
[ -f "$SRC/native/flat_host.cpp" ] || fail "overlay failed: native/flat_host.cpp missing"

echo "fetch-upstream: verifying resulting tree state ..."
EXPECTED=$(printf '%s\n' ' M CMakeLists.txt' ' M native/gl_context.cpp' ' M native/gl_context.h' '?? native/flat_host.cpp' | sort)
ACTUAL="$(git -C "$SRC" status --porcelain | sort)"
[ "$ACTUAL" = "$EXPECTED" ] || {
  echo "fetch-upstream: ERROR: unexpected tree state after patch+overlay:" >&2
  echo "$ACTUAL" >&2
  echo "expected exactly:" >&2
  echo "$EXPECTED" >&2
  exit 1
}

echo "fetch-upstream: OK"
echo "  upstream checkout : $SRC"
echo "  HEAD              : $HEAD (v0.2.15)"
echo "  patches applied   : in patches/series order"
echo "  overlaid files    : CMakeLists.txt, native/flat_host.cpp"
echo
echo "NOTE: the Android app layer (activity, manifest, Gradle, JNI glue) is"
echo "NOT part of this overlay. See docs/ANDROID-GAP.md before building."
echo "Next: docs/BUILD.md"
