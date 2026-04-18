#!/data/data/com.termux/files/usr/bin/bash
# patch-lwjgl-bionic.sh — Rewrite LWJGL's glibc-linked liblwjgl.so so it
# dlopens cleanly under Bionic (Termux native JVM path).
#
# The jar ships glibc sonames (libpthread.so.0, libc.so.6, libdl.so.2,
# ld-linux-aarch64.so.1) in DT_NEEDED plus matching DT_VERNEED entries.
# Bionic's loader rejects the .so because:
#   1. libpthread.so.0 / libc.so.6 / libdl.so.2 / ld-linux-aarch64.so.1
#      don't exist as distinct files on Android (pthread is merged into
#      libc.so; ld-linux-aarch64.so.1 isn't present at all).
#   2. Bionic validates DT_VERNEED sonames against the set of *loaded*
#      DSO sonames. Even if we put ld-linux-aarch64.so.1 in DT_NEEDED
#      and symlink it to libc.so, the loader reads the loaded lib's
#      DT_SONAME (`libc.so`) and rejects the VERNEED entry whose file
#      is `ld-linux-aarch64.so.1` — verified via `cannot find "ld-linux-
#      aarch64.so.1" from verneed[0] in DT_NEEDED list`.
#
# Fix: `patchelf --replace-needed OLD NEW` rewrites both DT_NEEDED AND
# the matching DT_VERNEED File entries. So we remap every glibc soname
# to a Bionic soname in one operation — no symlink shim needed.
#
# Resulting DT_NEEDED: libc.so libc.so libc.so libdl.so (duplicates are
# harmless; Bionic load-once already handles them). DT_VERNEED becomes
# entirely libc.so / libdl.so files. Symbol versions (GLIBC_2.17) stay
# as-is but no concrete symbol is resolved via them — __stack_chk_guard
# has .gnu.version index 1 (*global*, unversioned), so Bionic's global
# sym table lookup satisfies it from Bionic libc.
#
# Idempotent: re-invoking this on an already-patched jar is a no-op.

set -euo pipefail

LOGFILE="${LOGFILE:-$HOME/patch-lwjgl-bionic.log}"
log() { echo "[patch-lwjgl] $*" | tee -a "$LOGFILE"; }

ROOTFS="${ROOTFS:-$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu}"
REPO_JAR="${REPO_JAR:-$ROOTFS/root/.runelite/repository2/lwjgl-3.3.2-natives-linux-arm64.jar}"
SO_JAR_PATH="linux/arm64/org/lwjgl/liblwjgl.so"
SHA_JAR_PATH="META-INF/linux/arm64/org/lwjgl/liblwjgl.so.sha1"
LWJGL_EXTRACT_CACHE="$PREFIX/tmp/lwjgl-rl"
# Stale symlink from an earlier approach (v1 of this script). Delete it so
# it never shadows the real /system lookup from LD_LIBRARY_PATH again.
STALE_LD_LINUX_LINK="$PREFIX/lib/ld-linux-aarch64.so.1"

if [ ! -f "$REPO_JAR" ]; then
    log "ERROR: jar missing at $REPO_JAR — run proot launcher once to populate repository2"
    exit 1
fi

# Preflight — tools we need. clang is needed to build libbionic-compat.so.
for tool in patchelf readelf unzip sha1sum clang; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        log "ERROR: $tool not installed; run: pkg install patchelf binutils unzip coreutils clang"
        exit 1
    fi
done

# ---------------------------------------------------------------------
# Build libbionic-compat.so if missing/stale. This ships 4 glibc-only
# shims (__errno_location, __xstat64, __fxstat64, __getdelim) that Bionic
# libc lacks. We --add-needed it to liblwjgl.so so Bionic's loader finds
# them. Rebuild when the source file is newer than the compiled .so.
# ---------------------------------------------------------------------
COMPAT_SRC="$HOME/scripts/libbionic-compat.c"
COMPAT_SO="$PREFIX/lib/libbionic-compat.so"
if [ ! -f "$COMPAT_SRC" ]; then
    log "ERROR: libbionic-compat.c missing at $COMPAT_SRC — ScriptManager didn't deploy it"
    exit 1
fi
if [ ! -f "$COMPAT_SO" ] || [ "$COMPAT_SRC" -nt "$COMPAT_SO" ]; then
    log "compiling $COMPAT_SRC -> $COMPAT_SO"
    if ! clang -shared -fPIC -O2 \
        -Wl,-soname,libbionic-compat.so \
        -o "$COMPAT_SO" "$COMPAT_SRC" 2>>"$LOGFILE"; then
        log "ERROR: failed to compile libbionic-compat.so"
        exit 1
    fi
    log "  built $(stat -c%s "$COMPAT_SO") bytes"
fi

# Locate the jar tool from Termux openjdk-21
JAR_BIN=""
for candidate in \
    "$PREFIX/lib/jvm/java-21-openjdk/bin/jar" \
    "$PREFIX/lib/jvm/java-17-openjdk/bin/jar" \
    "$PREFIX/opt/java/bin/jar"; do
    if [ -x "$candidate" ]; then JAR_BIN="$candidate"; break; fi
done
if [ -z "$JAR_BIN" ]; then
    log "ERROR: jar tool not found — install with: pkg install openjdk-21"
    exit 1
fi

WORK="$(mktemp -d "$PREFIX/tmp/lwjgl-patch.XXXXXX")"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

log "extracting jar to $WORK"
(cd "$WORK" && unzip -q "$REPO_JAR")

SO="$WORK/$SO_JAR_PATH"
if [ ! -f "$SO" ]; then
    log "ERROR: expected embedded .so at $SO_JAR_PATH not found in jar"
    exit 1
fi

# ---------------------------------------------------------------------
# Check current NEEDEDs. If Bionic-clean already, bail idempotently.
# ---------------------------------------------------------------------
current_needs="$(patchelf --print-needed "$SO" 2>/dev/null | tr '\n' ' ')"
log "current DT_NEEDED: $current_needs"

# Always run the full patch — each step below is individually idempotent
# (patchelf --replace-needed is a no-op if the old name isn't in NEEDED;
# --clear-symbol-version is a no-op if the symbol already has no version).
# A cheap ~1s repack on every launch is much simpler than debugging a
# fragile early-exit check.

# ---------------------------------------------------------------------
# Apply patchelf rewrites. --replace-needed also rewrites the matching
# DT_VERNEED File entry — verified empirically on patchelf 0.18.
# ---------------------------------------------------------------------
log "patching NEEDEDs + VERNEED"
for pair in \
    "libpthread.so.0 libc.so" \
    "libc.so.6 libc.so" \
    "libdl.so.2 libdl.so" \
    "ld-linux-aarch64.so.1 libc.so"; do
    old="${pair% *}"
    new="${pair##* }"
    if patchelf --print-needed "$SO" | grep -q "^${old}\$"; then
        patchelf --replace-needed "$old" "$new" "$SO"
        log "  replaced NEEDED + VERNEED $old -> $new"
    fi
done

# Strip GLIBC_2.17 version on *every* imported symbol. Bionic exports
# the same names under version LIBC, not GLIBC_2.17, and its strict
# versioned lookup fails per-symbol (observed for stderr, then snprintf,
# then each call along the chain). Clearing the version makes Bionic
# do unversioned lookup and resolve by name. Only UND (undefined /
# imported) symbols matter here — defined symbols keep their versions.
versioned_syms="$(readelf --dyn-syms "$SO" 2>/dev/null \
    | awk '$7 == "UND" { n=$8; sub("@.*","",n); print n }' \
    | sort -u)"
sym_count=0
for sym in $versioned_syms; do
    [ -z "$sym" ] && continue
    if patchelf --clear-symbol-version "$sym" "$SO" 2>/dev/null; then
        sym_count=$((sym_count + 1))
    fi
done
log "  cleared glibc version on $sym_count imported symbols"

# Add libbionic-compat.so to DT_NEEDED so the 4 glibc-only shims resolve.
if ! patchelf --print-needed "$SO" | grep -q '^libbionic-compat.so$'; then
    patchelf --add-needed libbionic-compat.so "$SO"
    log "  added NEEDED libbionic-compat.so"
fi

log "post-patch DT_NEEDED: $(patchelf --print-needed "$SO" | tr '\n' ' ')"

# ---------------------------------------------------------------------
# Update embedded SHA1 marker so LWJGL's loader validates the patched .so
# ---------------------------------------------------------------------
NEW_SHA="$(sha1sum "$SO" | awk '{print $1}')"
log "new .so sha1=$NEW_SHA"
mkdir -p "$(dirname "$WORK/$SHA_JAR_PATH")"
printf '%s' "$NEW_SHA" > "$WORK/$SHA_JAR_PATH"

# ---------------------------------------------------------------------
# Repack jar. jar tool with --no-manifest keeps the existing META-INF/MANIFEST.MF
# file that's already inside the work dir (jar --create merges the dir's
# META-INF rather than creating a new manifest).
# ---------------------------------------------------------------------
log "repacking jar via $JAR_BIN"
NEW_JAR="$WORK/lwjgl-bionic-repacked.jar"
(cd "$WORK" && "$JAR_BIN" --create --no-manifest --file="$NEW_JAR" .)

# Atomic replace
mv "$NEW_JAR" "$REPO_JAR"
log "replaced $REPO_JAR ($(stat -c%s "$REPO_JAR") bytes)"

# ---------------------------------------------------------------------
# Clean up the v1 symlink if an earlier script run created it. The new
# approach has no reference to ld-linux-aarch64.so.1 anywhere in the .so,
# so the symlink is now useless (and a dangling symlink can confuse
# glibc-aware tooling).
# ---------------------------------------------------------------------
if [ -L "$STALE_LD_LINUX_LINK" ]; then
    rm -f "$STALE_LD_LINUX_LINK"
    log "removed stale v1 symlink at $STALE_LD_LINUX_LINK"
fi

# ---------------------------------------------------------------------
# Clear LWJGL's extraction cache so the next JVM run re-extracts the
# patched .so (the cache is keyed by the jar's META-INF SHA1 value;
# clearing it forces a re-extract regardless).
# ---------------------------------------------------------------------
if [ -d "$LWJGL_EXTRACT_CACHE" ]; then
    rm -rf "$LWJGL_EXTRACT_CACHE"
    log "cleared lwjgl extraction cache at $LWJGL_EXTRACT_CACHE"
fi

log "done"
exit 0
