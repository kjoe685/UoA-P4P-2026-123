#!/bin/sh
# Helpers for explicit optional setup. The caller supplies its pinned platform/checksum.
parliament_verify_uv_sha() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$1" "$2" | sha256sum -c - >/dev/null
  else
    printf '%s  %s\n' "$1" "$2" | shasum -a 256 -c - >/dev/null
  fi
}
parliament_uv_file_sha() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d ' ' -f 1;
  else shasum -a 256 "$1" | cut -d ' ' -f 1; fi
}
get_parliament_uv() (
  set -eu
  uv_runtime_root="$1/.runtime"
  uv_platform=$2
  uv_checksum=$3
  uv_root="$uv_runtime_root/uv-0.12.16"
  if [ -f "$uv_root/uv" ] && [ -x "$uv_root/uv" ] && [ -f "$uv_root/uv.bootstrap.sha256" ] &&
     [ "$(cat "$uv_root/uv.bootstrap.sha256")" = "$uv_checksum:$(parliament_uv_file_sha "$uv_root/uv")" ]; then
    printf '%s\n' "$uv_root/uv"; exit 0
  fi
  mkdir -p "$uv_runtime_root" || exit 1
  uv_archive="$uv_runtime_root/uv-0.12.16.tar.gz"
  if [ ! -f "$uv_archive" ] || ! parliament_verify_uv_sha "$uv_checksum" "$uv_archive"; then
    printf '%s\n' 'Downloading pinned uv for optional local analysis...' >&2
    uv_url="https://github.com/astral-sh/uv/releases/download/0.12.16/uv-$uv_platform.tar.gz"
    if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 "$uv_url" -o "$uv_archive.part" || exit 1;
    else wget -O "$uv_archive.part" "$uv_url" || exit 1; fi
    parliament_verify_uv_sha "$uv_checksum" "$uv_archive.part" || { printf '%s\n' 'uv download checksum mismatch. Re-run local setup.' >&2; exit 1; }
    mv "$uv_archive.part" "$uv_archive" || exit 1
  fi
  printf '%s\n' 'Extracting verified uv archive...' >&2
  uv_staging=$(mktemp -d "$uv_runtime_root/uv-extract.XXXXXX") || exit 1
  tar -xzf "$uv_archive" -C "$uv_staging" || exit 1
  [ -f "$uv_staging/uv-$uv_platform/uv" ] && [ -x "$uv_staging/uv-$uv_platform/uv" ] || { printf '%s\n' 'Verified uv archive contains no executable.' >&2; exit 1; }
  printf '%s:%s\n' "$uv_checksum" "$(parliament_uv_file_sha "$uv_staging/uv-$uv_platform/uv")" > "$uv_staging/uv-$uv_platform/uv.bootstrap.sha256" || exit 1
  if [ -e "$uv_root" ] || [ -L "$uv_root" ]; then
    uv_incomplete=$(mktemp -d "$uv_runtime_root/uv-incomplete.XXXXXX") || exit 1
    mv "$uv_root" "$uv_incomplete/uv" || exit 1
  fi
  mv "$uv_staging/uv-$uv_platform" "$uv_root" || exit 1
  printf '%s\n' "$uv_root/uv"
)
