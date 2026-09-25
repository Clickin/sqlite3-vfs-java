#!/usr/bin/env bash
# Build-time C/WASI tools only; the resulting engine runs on the JVM.
set -euo pipefail
export LC_ALL=C

native_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
engine_dir=$(dirname -- "$native_dir")
target_dir="$engine_dir/target"
tools_dir="$target_dir/tools"
mkdir -p "$tools_dir"

fail() { printf '%s\n' "$*" >&2; exit 1; }

if command -v sha256sum >/dev/null 2>&1; then
  sha256=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  sha256=(shasum -a 256)
else
  fail 'Install sha256sum or shasum to verify pinned downloads.'
fi

verify() {
  local actual
  actual=$("${sha256[@]}" "$1")
  actual=${actual%% *}
  [[ "$actual" == "$2" ]] || fail "SHA-256 mismatch: $1 (expected $2, got $actual)"
}

fetch() {
  local url=$1 destination=$2 checksum=$3
  if [[ ! -f "$destination" ]]; then
    curl --fail --location --retry 3 --output "$destination.part" "$url"
    verify "$destination.part" "$checksum"
    mv -- "$destination.part" "$destination"
  fi
  verify "$destination" "$checksum"
}

sqlite_archive=sqlite-amalgamation-3530400
fetch "https://sqlite.org/2026/$sqlite_archive.zip" \
  "$tools_dir/$sqlite_archive.zip" \
  1e71ddf93849c6a6ecf58b827c0692073d2dd7ee40196158068f7b29f422e87d
# Always restore the verified sources instead of trusting a mutable extracted copy.
unzip -oq "$tools_dir/$sqlite_archive.zip" -d "$tools_dir"
sqlite_dir="$tools_dir/$sqlite_archive"
for source in "$sqlite_dir/sqlite3.h" "$sqlite_dir/sqlite3.c"; do
  grep -Eq '^#define SQLITE_VERSION[[:space:]]+"3\.53\.4"$' "$source" \
    || fail "Unexpected SQLite version in $source"
  grep -Eq '^#define SQLITE_VERSION_NUMBER[[:space:]]+3053004$' "$source" \
    || fail "Unexpected SQLite version number in $source"
  grep -Eq '^#define SQLITE_SOURCE_ID[[:space:]]+"2026-07-24 19:02:57 bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc"$' "$source" \
    || fail "Unexpected SQLite source ID in $source"
done
verify "$native_dir/sqlite3_helpers.c" \
  1cf7495542b1828418bf8ae56a873760a0fab4106956b70654ff5776f6d7853b

if [[ -z "${WASI_SDK_PATH:-}" ]]; then
  case "$(uname -s)" in
    Linux) sdk_os=linux ;;
    Darwin) sdk_os=macos ;;
    *) fail 'Automatic WASI SDK installation supports Linux and macOS only; set WASI_SDK_PATH.' ;;
  esac
  case "$(uname -m)" in
    aarch64|arm64) sdk_arch=arm64 ;;
    x86_64|amd64) sdk_arch=x86_64 ;;
    *) fail 'Automatic WASI SDK installation supports arm64 and x86_64 only; set WASI_SDK_PATH.' ;;
  esac
  case "$sdk_arch-$sdk_os" in
    arm64-linux) sdk_sha=47fccad8b2498f2239e05e1115c3ffc652bf37e7de2f88fb64b2d663c976ce2d ;;
    x86_64-linux) sdk_sha=52640dde13599bf127a95499e61d6d640256119456d1af8897ab6725bcf3d89c ;;
    arm64-macos) sdk_sha=e1e529ea226b1db0b430327809deae9246b580fa3cae32d31c82dfe770233587 ;;
    x86_64-macos) sdk_sha=55e3ff3fee1a15678a16eeccba0129276c9f6be481bc9c283e7f9f65bf055c11 ;;
  esac
  sdk_name="wasi-sdk-25.0-$sdk_arch-$sdk_os"
  fetch "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-25/$sdk_name.tar.gz" \
    "$tools_dir/$sdk_name.tar.gz" "$sdk_sha"
  WASI_SDK_PATH="$tools_dir/$sdk_name"
  if [[ ! -x "$WASI_SDK_PATH/bin/clang" ]]; then
    tar -xzf "$tools_dir/$sdk_name.tar.gz" -C "$tools_dir"
  fi
fi
[[ -x "$WASI_SDK_PATH/bin/clang" ]] || fail "No compiler at $WASI_SDK_PATH/bin/clang"
[[ -f "$WASI_SDK_PATH/VERSION" ]] || fail 'WASI SDK VERSION is required (use SDK 25.0).'
read -r sdk_version < "$WASI_SDK_PATH/VERSION"
[[ "$sdk_version" == 25.0 ]] || fail "Expected WASI SDK 25.0, got $sdk_version"

# No Binaryen pass is needed. Keep WAL compiled: io_methods v1 does not
# advertise shared-memory support. OS_OTHER excludes all built-in VFSes.
"$WASI_SDK_PATH/bin/clang" \
  --sysroot="$WASI_SDK_PATH/share/wasi-sysroot" \
  --target=wasm32-wasi -std=c11 -Oz -g0 \
  -ffile-prefix-map="$engine_dir"=. \
  -mexec-model=reactor \
  -mnontrapping-fptoint -msign-ext -mmutable-globals -mmultivalue \
  -mbulk-memory -mreference-types \
  -fno-stack-protector \
  -Wl,--export-all -Wl,--import-undefined -Wl,--no-entry \
  -Wl,--initial-memory=32768000 -Wl,--stack-first -Wl,--strip-debug \
  -DSQLITE_OS_OTHER=1 -DSQLITE_THREADSAFE=0 \
  -DSQLITE_OMIT_SHARED_CACHE=1 -DSQLITE_OMIT_LOAD_EXTENSION=1 \
  -DSQLITE_ENABLE_COLUMN_METADATA -DSQLITE_CORE \
  -DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS3_PARENTHESIS -DSQLITE_ENABLE_FTS5 \
  -DSQLITE_ENABLE_RTREE -DSQLITE_ENABLE_STAT4 -DSQLITE_ENABLE_DBSTAT_VTAB \
  -DSQLITE_ENABLE_MATH_FUNCTIONS -DSQLITE_HAVE_ISNAN=1 \
  -DSQLITE_USE_ALLOCA=0 \
  -I "$sqlite_dir" \
  "$sqlite_dir/sqlite3.c" "$native_dir/sqlite3_helpers.c" "$native_dir/java_nio_vfs.c" \
  -o "$target_dir/sqlite-vfs.wasm.part"
mv -- "$target_dir/sqlite-vfs.wasm.part" "$target_dir/sqlite-vfs.wasm"
printf 'Built %s with SQLite 3.53.4 and WASI SDK %s\n' "$target_dir/sqlite-vfs.wasm" "$sdk_version"
"${sha256[@]}" "$target_dir/sqlite-vfs.wasm"
