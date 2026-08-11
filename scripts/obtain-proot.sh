#!/usr/bin/env bash
# Pin Termux proot aarch64 for reproducible CI builds.
# Usage: ./scripts/obtain-proot.sh [dest_dir]
set -euo pipefail

DEST="${1:-app/src/full/assets/proot}"
mkdir -p "$DEST/lib"

# Pinned package name (update VERSION file when changing).
PINNED_DEB="${PROOT_DEB_NAME:-proot_5.1.107-81_aarch64.deb}"
BASE_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT
cd "$WORKDIR"

echo "Obtaining proot into $DEST (prefer pinned: $PINNED_DEB)"
if curl -fsSL -o proot.deb "$BASE_URL/$PINNED_DEB"; then
  echo "Downloaded pinned $PINNED_DEB"
else
  echo "Pinned deb missing — resolving latest aarch64..."
  DEB_URL=$(curl -fsSL "$BASE_URL/" | grep -oE 'proot_[0-9][^"]*_aarch64.deb' | sort -V | tail -1)
  if [ -z "$DEB_URL" ]; then
    echo "ERROR: Could not find proot aarch64 .deb"
    exit 1
  fi
  echo "Found latest: $DEB_URL"
  curl -fsSL -o proot.deb "$BASE_URL/$DEB_URL"
  PINNED_DEB="$DEB_URL"
fi

ar x proot.deb
tar -xf data.tar.*
cp ./data/data/com.termux/files/usr/bin/proot "$DEST/proot"
chmod 755 "$DEST/proot"
cp ./data/data/com.termux/files/usr/lib/libtalloc.so.2 "$DEST/lib/" 2>/dev/null || true
cp ./data/data/com.termux/files/usr/lib/libandroid-shmem.so "$DEST/lib/" 2>/dev/null || true

# Record pin for diagnostics
{
  echo "proot_deb: $PINNED_DEB"
  echo "target_abi: arm64-v8a"
  echo "date: $(date -u +%Y-%m-%d)"
  echo "source: termux packages (scripts/obtain-proot.sh)"
} > "$DEST/VERSION"

file "$DEST/proot"
ls -la "$DEST" "$DEST/lib"
echo "OK: proot ready at $DEST"
