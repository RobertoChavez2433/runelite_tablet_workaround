#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# install-java.sh — Step 4: Install OpenJDK 11 + x11-xserver-utils (xrandr) + openbox inside Ubuntu proot
# Idempotent: skips if java binary already exists in proot.
# Writes step-java.done marker only after positive verification.

SCRIPT_VERSION="2"
MARKER_DIR="$HOME/.runelite-tablet/markers"

echo "=== Installing Java + openbox + x11-xserver-utils inside Ubuntu ==="

# Check if Java is already installed — skip if so (idempotent)
JAVA_BIN=$(proot-distro login ubuntu -- which java 2>/dev/null < /dev/null || echo "")
if [ -n "$JAVA_BIN" ]; then
    echo "Java already installed at $JAVA_BIN, skipping"
else
    # DEBIAN_FRONTEND=noninteractive prevents debconf prompts that hang in background mode.
    # proot login has harmless /proc/self/fd warnings — tolerate non-zero and verify afterward.
    # Full JDK required — RuneLite needs AWT/X11 libs (libawt_xawt.so) not in headless.
    # x11-xserver-utils provides xrandr for display resolution management.
    # openbox provides window management (maximizes RuneLite window on launch).
    JAVA_INSTALL_LOG="$HOME/.rlt-java-install.log"
    proot-distro login ubuntu -- env DEBIAN_FRONTEND=noninteractive bash -s << 'JAVA_SCRIPT' 2>&1 | tee "$JAVA_INSTALL_LOG" || true
        # Retry apt-get update up to 3 times
        attempt=1
        while [ $attempt -le 3 ]; do
            if apt-get update -y; then break; fi
            echo "WARNING: apt-get update failed (attempt $attempt/3), retrying in 5s..."
            sleep 5
            attempt=$((attempt + 1))
        done
        apt-get install -y openjdk-11-jdk x11-xserver-utils openbox wget ca-certificates
        if java -version 2>&1; then
            echo "=== Java install OK ==="
        fi
JAVA_SCRIPT
    if ! grep -q "=== Java install OK ===" "$JAVA_INSTALL_LOG"; then
        echo "ERROR: Java installation failed"
        rm -f "$JAVA_INSTALL_LOG"
        exit 1
    fi
    rm -f "$JAVA_INSTALL_LOG"
fi

# Deploy openbox config for auto-maximize + no decorations
echo "=== Deploying openbox config ==="
proot-distro login ubuntu -- bash -c 'mkdir -p /root/.config/openbox && cat > /root/.config/openbox/rc.xml' << 'OBCFG' 2>&1 || true
<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc"
    xmlns:xi="http://www.w3.org/2001/XInclude">
  <applications>
    <application class="*" groupclass="*">
      <decor>no</decor>
      <maximized>yes</maximized>
    </application>
  </applications>
</openbox_config>
OBCFG

# Positive verification: check java binary exists
JAVA_BIN=$(proot-distro login ubuntu -- which java 2>/dev/null < /dev/null || echo "")
if [ -z "$JAVA_BIN" ]; then
    echo "ERROR: java binary not found after install" >&2
    exit 1
fi
echo "Java verified at $JAVA_BIN"

# Write marker only after positive verification
mkdir -p "$MARKER_DIR"
touch "$MARKER_DIR/step-java.done"
echo "$SCRIPT_VERSION" > "$MARKER_DIR/version"
echo "=== install-java.sh complete ==="
