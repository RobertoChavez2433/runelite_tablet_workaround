#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export TERMUX_X11_ARGS="-legacy-drawing"

exec /data/data/com.termux/files/usr/bin/bash "$SCRIPT_DIR/frame-pacing.sh" "$@"
