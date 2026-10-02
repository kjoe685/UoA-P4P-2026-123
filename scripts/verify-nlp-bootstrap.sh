#!/bin/sh
# Portable recovery fixtures, not a native runtime or official Unix binary check.
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$project_root/scripts/nlp-runtime.sh"
fixture_root=$(mktemp -d "$project_root/target/nlp bootstrap portable.XXXXXX")
mkdir -p "$fixture_root/payload/uv-fixture"
printf '#!/bin/sh\nprintf "fixture uv\\n"\n' > "$fixture_root/payload/uv-fixture/uv"
chmod +x "$fixture_root/payload/uv-fixture/uv"
tar -czf "$fixture_root/fixture.tar.gz" -C "$fixture_root/payload" uv-fixture
if command -v sha256sum >/dev/null 2>&1; then checksum=$(sha256sum "$fixture_root/fixture.tar.gz" | cut -d ' ' -f 1);
else checksum=$(shasum -a 256 "$fixture_root/fixture.tar.gz" | cut -d ' ' -f 1); fi
curl() { printf '%s\n' 'Offline verification blocked a download' >&2; printf '%s\n' attempt >> "$fixture_root/blocked-downloads"; return 71; }
wget() { curl; }

cold="$fixture_root/cold with spaces"
mkdir -p "$cold/.runtime"
cp "$fixture_root/fixture.tar.gz" "$cold/.runtime/uv-0.12.16.tar.gz"
selected=$(get_parliament_uv "$cold" fixture "$checksum")
[ "$selected" = "$cold/.runtime/uv-0.12.16/uv" ] && [ "$("$selected")" = 'fixture uv' ]

recovery="$fixture_root/interrupted with spaces"
mkdir -p "$recovery/.runtime/uv-0.12.16/uv"
printf '%s\n' preserve > "$recovery/.runtime/uv-0.12.16/marker"
printf '%s\n' 'stale unverified partial' > "$recovery/.runtime/uv-0.12.16.tar.gz.part"
cp "$fixture_root/fixture.tar.gz" "$recovery/.runtime/uv-0.12.16.tar.gz"
selected=$(get_parliament_uv "$recovery" fixture "$checksum")
[ "$selected" = "$recovery/.runtime/uv-0.12.16/uv" ] && [ -f "$selected" ]
[ "$(find "$recovery/.runtime" -name marker -type f | wc -l | tr -d ' ')" = 1 ]
[ "$(cat "$recovery"/.runtime/uv-incomplete.*/uv/marker)" = preserve ]
directories=$(find "$recovery/.runtime" -type d | wc -l)
[ "$(get_parliament_uv "$recovery" fixture "$checksum")" = "$selected" ]
[ "$(find "$recovery/.runtime" -type d | wc -l)" = "$directories" ]
[ ! -e "$fixture_root/blocked-downloads" ]
printf '#!/bin/sh\n# interrupted executable extraction\n' > "$selected"
selected=$(get_parliament_uv "$recovery" fixture "$checksum")
cmp "$selected" "$fixture_root/payload/uv-fixture/uv"

corrupt="$fixture_root/corrupt with spaces"
mkdir -p "$corrupt/.runtime/uv-0.12.16"
printf '%s\n' preserve > "$corrupt/.runtime/uv-0.12.16/marker"
printf '%s\n' corrupt > "$corrupt/.runtime/uv-0.12.16.tar.gz"
printf '%s\n' stale > "$corrupt/.runtime/uv-0.12.16.tar.gz.part"
if get_parliament_uv "$corrupt" fixture "$checksum" > "$fixture_root/corrupt.stdout" 2> "$fixture_root/corrupt.stderr"; then printf '%s\n' 'Corrupt archive was accepted' >&2; exit 1; fi
[ "$(cat "$corrupt/.runtime/uv-0.12.16/marker")" = preserve ] && [ ! -e "$corrupt/.runtime/uv-0.12.16/uv" ]
[ "$(wc -l < "$fixture_root/blocked-downloads" | tr -d ' ')" = 1 ]

bad_download="$fixture_root/bad download with spaces"
curl() { for argument do destination=$argument; done; printf '%s\n' invalid > "$destination"; }
if get_parliament_uv "$bad_download" fixture "$checksum" > "$fixture_root/download.stdout" 2> "$fixture_root/download.stderr"; then printf '%s\n' 'Invalid download was accepted' >&2; exit 1; fi
[ ! -e "$bad_download/.runtime/uv-0.12.16.tar.gz" ] && [ ! -e "$bad_download/.runtime/uv-0.12.16/uv" ]

empty="$fixture_root/missing executable with spaces"
mkdir -p "$empty/.runtime/uv-0.12.16" "$fixture_root/empty-payload/uv-fixture"
printf '%s\n' preserve > "$empty/.runtime/uv-0.12.16/marker"
tar -czf "$empty/.runtime/uv-0.12.16.tar.gz" -C "$fixture_root/empty-payload" uv-fixture
if command -v sha256sum >/dev/null 2>&1; then empty_hash=$(sha256sum "$empty/.runtime/uv-0.12.16.tar.gz" | cut -d ' ' -f 1);
else empty_hash=$(shasum -a 256 "$empty/.runtime/uv-0.12.16.tar.gz" | cut -d ' ' -f 1); fi
if get_parliament_uv "$empty" fixture "$empty_hash" > "$fixture_root/empty.stdout" 2> "$fixture_root/empty.stderr"; then printf '%s\n' 'Missing executable was accepted' >&2; exit 1; fi
[ "$(cat "$empty/.runtime/uv-0.12.16/marker")" = preserve ]

# Exercise the real setup adapter with capture-only tool/platform substitutes.
wiring="$fixture_root/setup wiring with spaces"
mkdir -p "$wiring/scripts" "$wiring/.runtime/nlp" "$wiring/.runtime/uv-cache" "$wiring/bin"
cp "$project_root/scripts/setup-nlp.sh" "$wiring/scripts/setup-nlp.sh"
cat > "$wiring/scripts/nlp-runtime.sh" <<'FIXTURE'
get_parliament_uv() { printf '%s\n' "$1/.runtime/uv-stub"; }
FIXTURE
cat > "$wiring/.runtime/uv-stub" <<'FIXTURE'
#!/bin/sh
printf '%s\n' "$@" > "$UV_CACHE_DIR/../captured.args"
printf '%s\n' "$UV_PROJECT_ENVIRONMENT" "$UV_MANAGED_PYTHON" > "$UV_CACHE_DIR/../captured.env"
FIXTURE
cat > "$wiring/bin/uname" <<'FIXTURE'
#!/bin/sh
case "$1" in -s) printf 'Linux\n' ;; -m) printf 'x86_64\n' ;; *) exit 1 ;; esac
FIXTURE
chmod +x "$wiring/.runtime/uv-stub" "$wiring/bin/uname"
for selection in base models preserved; do
  if [ "$selection" = preserved ]; then printf '%s\n' existing > "$wiring/.runtime/nlp/models.complete"; fi
  if [ "$selection" = models ]; then PATH="$wiring/bin:$PATH" sh "$wiring/scripts/setup-nlp.sh" models;
  else PATH="$wiring/bin:$PATH" sh "$wiring/scripts/setup-nlp.sh"; fi
  grep -Fx -- '--locked' "$wiring/.runtime/captured.args" >/dev/null
  grep -Fx -- '--no-dev' "$wiring/.runtime/captured.args" >/dev/null
  grep -Fx -- "$wiring/nlp" "$wiring/.runtime/captured.args" >/dev/null
  grep -Fx -- "$wiring/.runtime/nlp-env" "$wiring/.runtime/captured.env" >/dev/null
  grep -Fx -- 'true' "$wiring/.runtime/captured.env" >/dev/null
  if [ "$selection" = base ]; then ! grep -Fx -- '--extra' "$wiring/.runtime/captured.args" >/dev/null;
  else grep -Fx -- '--extra' "$wiring/.runtime/captured.args" >/dev/null; grep -Fx -- models "$wiring/.runtime/captured.args" >/dev/null; fi
done
printf 'PASS: portable cache/recovery/integrity fixtures and locked setup/model-extra wiring\nFixture: %s\n' "$fixture_root" | tee "$fixture_root/result.txt"
