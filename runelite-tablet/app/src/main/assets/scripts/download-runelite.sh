#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# download-runelite.sh — Step 5: Download RuneLite.jar into proot Ubuntu
# Idempotent: skips if RuneLite.jar already exists and passes verification.
# Writes step-runelite.done marker only after positive verification.

SCRIPT_VERSION="2"
MARKER_DIR="$HOME/.runelite-tablet/markers"
RUNELITE_DIR="/root/runelite"
RUNELITE_JAR="$RUNELITE_DIR/RuneLite.jar"
# Hardcoded fallback version — update this when RuneLite releases a new launcher.
# The script tries the GitHub latest redirect first; this is only used if the API fails.
FALLBACK_URL="https://github.com/runelite/launcher/releases/download/2.7.6/RuneLite.jar"

echo "=== Downloading RuneLite ==="

RUNELITE_DL_LOG="$HOME/.rlt-runelite-dl.log"
proot-distro login ubuntu -- bash -s << RUNELITE_SCRIPT < /dev/null 2>&1 | tee "$RUNELITE_DL_LOG" || true
    RUNELITE_URL="https://github.com/runelite/launcher/releases/latest/download/RuneLite.jar"
    FALLBACK_URL="$FALLBACK_URL"
    mkdir -p "$RUNELITE_DIR"

    # Skip download if jar already exists and is > 1MB
    if [ -f "$RUNELITE_JAR" ]; then
        FILE_SIZE=\$(wc -c < "$RUNELITE_JAR" 2>/dev/null || echo "0")
        if [ "\$FILE_SIZE" -gt 1048576 ]; then
            echo "RuneLite.jar already exists (\${FILE_SIZE} bytes), skipping download"
            echo "=== RuneLite download OK ==="
            exit 0
        else
            echo "Existing RuneLite.jar is too small (\${FILE_SIZE} bytes), re-downloading"
            rm -f "$RUNELITE_JAR"
        fi
    fi

    # Try latest release URL first, fall back to hardcoded version
    if ! wget -O "$RUNELITE_JAR" "\$RUNELITE_URL" 2>&1; then
        echo "WARNING: Latest release download failed, trying fallback URL..."
        wget -O "$RUNELITE_JAR" "\$FALLBACK_URL" 2>&1
    fi

    # Verify file size > 1MB
    FILE_SIZE=\$(wc -c < "$RUNELITE_JAR" 2>/dev/null || echo "0")
    if [ "\$FILE_SIZE" -lt 1048576 ]; then
        echo "ERROR: Downloaded file too small (\${FILE_SIZE} bytes)" >&2
        rm -f "$RUNELITE_JAR"
        exit 1
    fi

    # Verify ZIP magic bytes (50 4B 03 04 = PK..)
    MAGIC=\$(od -A n -t x1 -N 4 "$RUNELITE_JAR" 2>/dev/null | tr -d ' ')
    if [ "\$MAGIC" != "504b0304" ]; then
        echo "ERROR: Downloaded file is not a valid JAR/ZIP (magic: \$MAGIC)" >&2
        rm -f "$RUNELITE_JAR"
        exit 1
    fi

    echo "RuneLite.jar downloaded successfully (\${FILE_SIZE} bytes)"
    echo "=== RuneLite download OK ==="
RUNELITE_SCRIPT

if ! grep -q "=== RuneLite download OK ===" "$RUNELITE_DL_LOG"; then
    echo "ERROR: RuneLite download failed"
    rm -f "$RUNELITE_DL_LOG"
    exit 1
fi
rm -f "$RUNELITE_DL_LOG"

# Write marker only after positive verification
mkdir -p "$MARKER_DIR"
touch "$MARKER_DIR/step-runelite.done"
echo "$SCRIPT_VERSION" > "$MARKER_DIR/version"
echo "=== download-runelite.sh complete ==="
