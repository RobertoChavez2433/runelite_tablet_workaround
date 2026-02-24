#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Detect GPU vendor from Android device nodes
GPU_VENDOR="unknown"

if [ -e /dev/kgsl-3d0 ]; then
    GPU_VENDOR="adreno"
elif [ -e /dev/mali0 ]; then
    GPU_VENDOR="mali"
elif [ -e /dev/pvr_sync ]; then
    GPU_VENDOR="powervr"
fi

echo "$GPU_VENDOR"
