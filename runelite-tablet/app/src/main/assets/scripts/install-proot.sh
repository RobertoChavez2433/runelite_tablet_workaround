#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# install-proot.sh — Step 3: Install proot-distro + Ubuntu ARM64 rootfs
# Idempotent: skips if rootfs already exists.
# Writes step-proot.done marker only after positive verification.

SCRIPT_VERSION="2"
MARKER_DIR="$HOME/.runelite-tablet/markers"
ROOTFS_DIR="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"

# Retry helper: up to 3 attempts with 5s backoff for apt network operations.
retry_apt() {
    local attempt=1
    while [ $attempt -le 3 ]; do
        if "$@"; then
            return 0
        fi
        echo "WARNING: apt command failed (attempt $attempt/3), retrying in 5s..." >&2
        sleep 5
        attempt=$((attempt + 1))
    done
    echo "ERROR: apt command failed after 3 attempts: $*" >&2
    return 1
}

# Check available disk space (need at least 1GB)
AVAIL_KB=$(df -k "$HOME" 2>/dev/null | awk 'NR==2 {print $4}')
if [ -n "$AVAIL_KB" ] && [ "$AVAIL_KB" -lt 1000000 ]; then
    AVAIL_MB=$((AVAIL_KB / 1024))
    echo "ERROR: Insufficient disk space. Need at least 1000MB, have ${AVAIL_MB}MB"
    exit 1
fi

echo "=== Installing proot-distro ==="
# apt-get update may return non-zero due to GPG warnings when gpgv is not installed
if ! find "$PREFIX/var/lib/apt/lists" -maxdepth 1 -type f -mmin -60 2>/dev/null | grep -q .; then
    apt-get update -y 2>&1 || true
fi
retry_apt apt-get install -y -o Dpkg::Options::="--force-confnew" proot-distro wget pulseaudio

echo "=== Installing Termux:X11 CLI package ==="
apt-get install -y x11-repo 2>&1 || true
apt-get update -y 2>&1 || true
retry_apt apt-get install -y termux-x11-nightly

echo "=== Installing Ubuntu ARM64 rootfs ==="
if [ -d "$ROOTFS_DIR" ]; then
    echo "Ubuntu rootfs already exists at $ROOTFS_DIR, skipping installation"
else
    # proot-distro install may return non-zero due to proot warnings about
    # /proc/self/fd bindings — these are harmless on Android.
    proot-distro install ubuntu < /dev/null 2>&1 || true
    if [ ! -d "$ROOTFS_DIR" ]; then
        echo "proot-distro install failed — attempting manual rootfs extraction"
        CACHE_DIR="$PREFIX/var/lib/proot-distro/dlcache"
        TARBALL=$(ls "$CACHE_DIR"/ubuntu-*-aarch64-pd-*.tar.xz 2>/dev/null | head -1 || true)
        if [ -n "$TARBALL" ]; then
            mkdir -p "$ROOTFS_DIR"
            mkdir -p "$ROOTFS_DIR/.l2s"
            proot --link2symlink tar xf "$TARBALL" -C "$ROOTFS_DIR" --strip-components=1 < /dev/null 2>&1 || true
            if [ ! -f "$ROOTFS_DIR/bin/bash" ]; then
                echo "ERROR: rootfs extraction failed — $ROOTFS_DIR/bin/bash not found" >&2
                exit 1
            fi
            echo "Manual rootfs extraction complete"

            # Post-install configuration (DNS, hosts, environment)
            cat > "$ROOTFS_DIR/etc/resolv.conf" <<'RESOLV'
nameserver 8.8.8.8
nameserver 8.8.4.4
RESOLV

            cat > "$ROOTFS_DIR/etc/hosts" <<'HOSTS'
127.0.0.1 localhost
::1 localhost
HOSTS

            cat > "$ROOTFS_DIR/etc/environment" <<'ENV'
LANG=en_US.UTF-8
LANGUAGE=en_US:en
MOZ_FAKE_NO_SANDBOX=1
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
TERM=xterm
TMPDIR=/tmp
PULSE_SERVER=127.0.0.1:4713
ENV

            echo "Post-extraction rootfs configuration complete"
        else
            echo "ERROR: No cached Ubuntu tarball found in $CACHE_DIR"
            exit 1
        fi
    fi
fi

# Positive verification: check rootfs directory exists
if [ ! -d "$ROOTFS_DIR" ]; then
    echo "ERROR: Ubuntu rootfs not found at $ROOTFS_DIR after installation" >&2
    exit 1
fi
echo "Ubuntu rootfs verified at $ROOTFS_DIR"

# Write marker only after positive verification
mkdir -p "$MARKER_DIR"
touch "$MARKER_DIR/step-proot.done"
echo "$SCRIPT_VERSION" > "$MARKER_DIR/version"
echo "=== install-proot.sh complete ==="
