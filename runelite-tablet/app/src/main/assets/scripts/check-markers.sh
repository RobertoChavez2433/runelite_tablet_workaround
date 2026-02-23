#!/data/data/com.termux/files/usr/bin/bash
# check-markers.sh — Startup utility: reports marker file presence
# Output protocol:
#   PRESENT step-proot    — marker file exists
#   ABSENT step-java      — marker file missing
#   VERSION 2             — current version in markers dir
#   VERSION none          — no version file found
# Always exits 0 (only non-zero if the script itself crashes).
# No set -e — this script must always produce output, even on errors.

MARKER_DIR="$HOME/.runelite-tablet/markers"

# Check each tracked step marker
for STEP in step-proot step-java step-runelite; do
    if [ -f "$MARKER_DIR/$STEP.done" ]; then
        echo "PRESENT $STEP"
    else
        echo "ABSENT $STEP"
    fi
done

# Report version
if [ -f "$MARKER_DIR/version" ]; then
    VERSION=$(cat "$MARKER_DIR/version" 2>/dev/null || echo "none")
    echo "VERSION $VERSION"
else
    echo "VERSION none"
fi

exit 0
