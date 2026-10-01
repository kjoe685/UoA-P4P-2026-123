#!/bin/sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
runtime_root="$project_root/.runtime"
uv_root="$runtime_root/uv-0.12.16"
if [ ! -x "$uv_root/uv" ]; then
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64) platform=x86_64-unknown-linux-gnu; checksum=8e5c6e5523dffc2dcf615bd995554c84c9feb4e577808a3fb8698a639d3f8d9c ;;
    Linux:aarch64) platform=aarch64-unknown-linux-gnu; checksum=36d913ee9c647481d64f1a0a0485f85ff2feaee605c341fc22e73398f9212c26 ;;
    Darwin:x86_64) platform=x86_64-apple-darwin; checksum=a42bcc9ce97eb8b364d7f162233a9c6b8c0ee25388e551d362809795127e0c31 ;;
    Darwin:arm64) platform=aarch64-apple-darwin; checksum=b6e03fae61704b1aa622f12b792a69483e837b83068e44f4fd34f8a07a8f74a3 ;;
    *) echo 'Unsupported managed NLP platform' >&2; exit 1 ;;
  esac
  mkdir -p "$runtime_root"
  archive="$runtime_root/uv-0.12.16.tar.gz.part"
  url="https://github.com/astral-sh/uv/releases/download/0.12.16/uv-$platform.tar.gz"
  if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 "$url" -o "$archive"; else wget -O "$archive" "$url"; fi
  if command -v sha256sum >/dev/null 2>&1; then printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c - >/dev/null;
  else printf '%s  %s\n' "$checksum" "$archive" | shasum -a 256 -c - >/dev/null; fi
  staging=$(mktemp -d "$runtime_root/uv-extract.XXXXXX")
  tar -xzf "$archive" -C "$staging"
  mv "$staging/uv-$platform" "$uv_root"
fi
UV_CACHE_DIR="$runtime_root/uv-cache"; export UV_CACHE_DIR
UV_PYTHON_INSTALL_DIR="$runtime_root/python"; export UV_PYTHON_INSTALL_DIR
UV_PROJECT_ENVIRONMENT="$runtime_root/nlp-env"; export UV_PROJECT_ENVIRONMENT
UV_MANAGED_PYTHON=true; export UV_MANAGED_PYTHON
if [ "${1:-}" = models ] || [ -f "$runtime_root/nlp/models.complete" ]; then
  exec "$uv_root/uv" sync --project "$project_root/nlp" --locked --no-dev --extra models
else exec "$uv_root/uv" sync --project "$project_root/nlp" --locked --no-dev; fi
