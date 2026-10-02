#!/bin/sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
runtime_root="$project_root/.runtime"
. "$project_root/scripts/nlp-runtime.sh"
case "$(uname -s):$(uname -m)" in
    Linux:x86_64) platform=x86_64-unknown-linux-gnu; checksum=8e5c6e5523dffc2dcf615bd995554c84c9feb4e577808a3fb8698a639d3f8d9c ;;
    Linux:aarch64) platform=aarch64-unknown-linux-gnu; checksum=36d913ee9c647481d64f1a0a0485f85ff2feaee605c341fc22e73398f9212c26 ;;
    Darwin:x86_64) platform=x86_64-apple-darwin; checksum=a42bcc9ce97eb8b364d7f162233a9c6b8c0ee25388e551d362809795127e0c31 ;;
    Darwin:arm64) platform=aarch64-apple-darwin; checksum=b6e03fae61704b1aa622f12b792a69483e837b83068e44f4fd34f8a07a8f74a3 ;;
    *) echo 'Unsupported managed NLP platform' >&2; exit 1 ;;
esac
uv=$(get_parliament_uv "$project_root" "$platform" "$checksum")
UV_CACHE_DIR="$runtime_root/uv-cache"; export UV_CACHE_DIR
UV_PYTHON_INSTALL_DIR="$runtime_root/python"; export UV_PYTHON_INSTALL_DIR
UV_PROJECT_ENVIRONMENT="$runtime_root/nlp-env"; export UV_PROJECT_ENVIRONMENT
UV_MANAGED_PYTHON=true; export UV_MANAGED_PYTHON
if [ "${1:-}" = models ] || [ -f "$runtime_root/nlp/models.complete" ]; then
  exec "$uv" sync --project "$project_root/nlp" --locked --no-dev --extra models
else exec "$uv" sync --project "$project_root/nlp" --locked --no-dev; fi
