#!/usr/bin/env bash
# Download a pinned Termux proot .deb and extract the aarch64 binary + host
# libs into app/src/full/assets/proot/ so assembleFullDebug can package them.
# Play Store flavor never ships these files.
#
# CI used to fail with:
#   1) HTTP 404 on a hyphenated pin (proot_5.1.107-81_aarch64.deb)
#      — Termux now uses dotted revisions (proot_5.1.107.92_aarch64.deb)
#   2) `cp ... dest/proot` after `cd "$WORKDIR"` when dest was relative
#
# Pin lives in scripts/proot-pin.env (sourced if present). Override with
# PROOT_DEB_NAME. If the pin 404s we resolve the latest aarch64 .deb.
# Missing libtalloc / binary is a hard failure (do not package a half tree).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

if [[ -f "${SCRIPT_DIR}/proot-pin.env" ]]; then
  # shellcheck disable=SC1091
  set -a
  # shellcheck disable=SC1091
  source "${SCRIPT_DIR}/proot-pin.env"
  set +a
fi

DEST_IN="${1:-${ROOT}/app/src/full/assets/proot}"
if [[ "${DEST_IN}" = /* ]]; then
  DEST="${DEST_IN}"
else
  DEST="$(pwd)/${DEST_IN}"
fi
mkdir -p "${DEST}/lib"

PINNED_DEB="${PROOT_DEB_NAME:-proot_5.1.107.92_aarch64.deb}"
BASE_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

echo "Obtaining proot into ${DEST} (prefer pinned: ${PINNED_DEB})"
cd "${WORKDIR}"

DOWNLOADED=""
if curl -fsSL -o proot.deb "${BASE_URL}/${PINNED_DEB}"; then
  echo "Downloaded pinned ${PINNED_DEB}"
  DOWNLOADED="${PINNED_DEB}"
else
  echo "Pinned deb missing — resolving latest aarch64..."
  DEB_NAME="$(curl -fsSL "${BASE_URL}/" | grep -oE 'proot_[0-9][^"<> ]*_aarch64\.deb' | sort -V | tail -1 || true)"
  if [[ -z "${DEB_NAME}" ]]; then
    echo "ERROR: Could not find proot aarch64 .deb at ${BASE_URL}/"
    exit 1
  fi
  echo "Found latest: ${DEB_NAME}"
  curl -fsSL -o proot.deb "${BASE_URL}/${DEB_NAME}"
  DOWNLOADED="${DEB_NAME}"
fi

if [[ ! -s proot.deb ]]; then
  echo "ERROR: downloaded proot.deb is empty"
  exit 1
fi

ar x proot.deb
if [[ -f data.tar.xz ]]; then
  tar -xf data.tar.xz
elif [[ -f data.tar.gz ]]; then
  tar -xf data.tar.gz
else
  shopt -s nullglob
  data_tars=(data.tar.*)
  shopt -u nullglob
  if [[ ${#data_tars[@]} -eq 0 ]]; then
    echo "ERROR: no data.tar in deb"
    exit 1
  fi
  tar -xf "${data_tars[0]}"
fi

SRC_BIN="./data/data/com.termux/files/usr/bin/proot"
SRC_LIB="./data/data/com.termux/files/usr/lib"
if [[ ! -f "${SRC_BIN}" ]]; then
  echo "ERROR: proot binary not found inside deb"
  find . -name proot -type f 2>/dev/null | head || true
  exit 1
fi

cp "${SRC_BIN}" "${DEST}/proot"
chmod 755 "${DEST}/proot"

# Host libs live in separate Termux packages, not in the proot .deb.
# Copy from the deb if present, otherwise fetch the companion packages.
POOL="https://packages.termux.dev/apt/termux-main/pool/main"

fetch_so_from_deb() {
  local deb_url="$1"
  local so_name="$2"
  local tmpdeb="${WORKDIR}/dep.deb"
  echo "Fetching ${so_name} from ${deb_url}"
  curl -fsSL -o "${tmpdeb}" "${deb_url}"
  local depdir="${WORKDIR}/dep-$$"
  mkdir -p "${depdir}"
  (
    cd "${depdir}"
    ar x "${tmpdeb}"
    if [[ -f data.tar.xz ]]; then tar -xf data.tar.xz
    elif [[ -f data.tar.gz ]]; then tar -xf data.tar.gz
    else tar -xf data.tar.*
    fi
  )
  # soname is often a symlink (libtalloc.so.2 -> libtalloc.so.2.4.3)
  local found=""
  found="$(find "${depdir}" -name "${so_name}" ! -type d | head -1 || true)"
  if [[ -z "${found}" ]]; then
    found="$(find "${depdir}" -name "${so_name}.*" ! -type d | head -1 || true)"
  fi
  if [[ -z "${found}" ]]; then
    echo "ERROR: ${so_name} not inside ${deb_url}"
    return 1
  fi
  if [[ -L "${found}" ]]; then
    local real
    real="$(readlink -f "${found}" 2>/dev/null || true)"
    if [[ -n "${real}" && -e "${real}" ]]; then
      found="${real}"
    fi
  fi
  cp "${found}" "${DEST}/lib/${so_name}"
  chmod 755 "${DEST}/lib/${so_name}"
}

ensure_lib() {
  local name="$1"
  local deb_url="$2"
  if [[ -f "${SRC_LIB}/${name}" ]]; then
    cp "${SRC_LIB}/${name}" "${DEST}/lib/"
    chmod 755 "${DEST}/lib/${name}"
    return 0
  fi
  if fetch_so_from_deb "${deb_url}" "${name}"; then
    return 0
  fi
  if [[ -f "${DEST}/lib/${name}" ]]; then
    echo "WARNING: using pre-existing ${DEST}/lib/${name}"
    return 0
  fi
  echo "ERROR: ${name} missing and could not be downloaded"
  return 1
}

ensure_lib "libtalloc.so.2" "${POOL}/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
ensure_lib "libandroid-shmem.so" "${POOL}/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"

if command -v sha256sum >/dev/null 2>&1; then
  DEB_SHA="$(sha256sum proot.deb | awk '{print $1}')"
else
  DEB_SHA="unknown"
fi

{
  echo "proot_deb: ${DOWNLOADED}"
  echo "proot_deb_sha256: ${DEB_SHA}"
  echo "target_abi: arm64-v8a"
  echo "date: $(date -u +%Y-%m-%d)"
  echo "source: termux packages (scripts/obtain-proot.sh)"
} > "${DEST}/VERSION"

if command -v file >/dev/null 2>&1; then
  file "${DEST}/proot"
fi
ls -la "${DEST}" "${DEST}/lib"
test -f "${DEST}/proot" && test -x "${DEST}/proot"
test -f "${DEST}/lib/libtalloc.so.2"
test -f "${DEST}/lib/libandroid-shmem.so"
echo "OK: proot ready at ${DEST}"
