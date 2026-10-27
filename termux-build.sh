#!/usr/bin/env bash
set -euo pipefail

# AGP normally downloads a Linux x86_64 aapt2, which cannot run natively
# in Termux on ARM64. Override it only for this invocation.
AAPT2_BIN="${AAPT2_PATH:-}"
if [[ -z "$AAPT2_BIN" ]]; then
    AAPT2_BIN="$(command -v aapt2 || true)"
fi

if [[ -z "$AAPT2_BIN" || ! -x "$AAPT2_BIN" ]]; then
    echo "No executable native aapt2 found." >&2
    echo "Install a Termux-compatible ARM64 aapt2 or set AAPT2_PATH=/absolute/path/to/aapt2." >&2
    exit 1
fi

AAPT2_BIN="$(realpath "$AAPT2_BIN")"
if ! "$AAPT2_BIN" version >/dev/null 2>&1; then
    echo "The selected aapt2 could not run. Check that it is a native Termux/ARM64 binary." >&2
    exit 1
fi

if [[ "$#" -eq 0 ]]; then
    set -- assembleDebug
fi

exec ./gradlew "-Pandroid.aapt2FromMavenOverride=$AAPT2_BIN" "$@"
