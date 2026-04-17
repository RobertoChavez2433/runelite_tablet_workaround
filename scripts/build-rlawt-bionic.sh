#!/usr/bin/env bash
# Reproducible Bionic-native rebuild of rlawt for Termux.
#
# Context: rlawt-1.8.jar ships a glibc-linked librlawt.so which Bionic's
# dynamic linker rejects at four layers (NEEDED soname, namespace
# permitted_paths, VERNEED glibc versions, VERSYM/vn_version). This
# script cross-compiles the same rlawt C sources through the Android NDK
# against Termux-harvested stub libs, producing a drop-in librlawt.so
# with Bionic NEEDED sonames.
#
# Requirements:
#   - Android NDK 28.2.13676358 at the path below (or set ANDROID_NDK_ROOT)
#   - Host JDK 11+ (for the pre-generated JNI header — already checked in
#     at third_party/rlawt-bionic/generated/, so JDK not needed at build
#     time unless you regenerate)
#   - Harvested target headers + stub libs at
#     third_party/rlawt-bionic/harvest/ (see harvest README).
#
# Output: third_party/rlawt-bionic/build-bionic/librlawt.so

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

: "${ANDROID_NDK_ROOT:=C:/Users/rseba/AppData/Local/Android/Sdk/ndk/28.2.13676358}"
: "${ANDROID_SDK_CMAKE:=C:/Users/rseba/AppData/Local/Android/Sdk/cmake/3.22.1/bin}"
: "${ANDROID_ABI:=arm64-v8a}"
: "${ANDROID_PLATFORM:=android-31}"

if [[ -d "${ANDROID_SDK_CMAKE}" ]]; then
    # Git-Bash needs MSYS-form paths on PATH for command lookups to work.
    _msys_cmake_bin="$(cygpath -u "${ANDROID_SDK_CMAKE}" 2>/dev/null || echo "${ANDROID_SDK_CMAKE}")"
    export PATH="${_msys_cmake_bin}:${PATH}"
fi

if ! command -v cmake >/dev/null 2>&1; then
    echo "ERROR: cmake not on PATH. Install Android SDK cmake or set ANDROID_SDK_CMAKE." >&2
    exit 1
fi

if [[ ! -f "${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" ]]; then
    echo "ERROR: NDK toolchain file not found at:" >&2
    echo "  ${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" >&2
    echo "Set ANDROID_NDK_ROOT to a valid NDK install." >&2
    exit 1
fi

BUILD_DIR="third_party/rlawt-bionic/build-bionic"

if [[ "${1:-}" == "--clean" ]]; then
    echo "Cleaning ${BUILD_DIR}..."
    rm -rf "${BUILD_DIR}"
    shift
fi

mkdir -p "${BUILD_DIR}"

echo "Configuring rlawt-bionic with NDK at ${ANDROID_NDK_ROOT}..."
cmake \
    -S third_party/rlawt-bionic \
    -B "${BUILD_DIR}" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${ANDROID_ABI}" \
    -DANDROID_PLATFORM="${ANDROID_PLATFORM}" \
    -DCMAKE_BUILD_TYPE=Release \
    -G "Ninja"

echo ""
echo "Building librlawt.so..."
cmake --build "${BUILD_DIR}" --config Release --verbose

OUTPUT="${BUILD_DIR}/librlawt.so"
if [[ ! -f "${OUTPUT}" ]]; then
    echo "ERROR: build completed but ${OUTPUT} does not exist" >&2
    exit 1
fi

echo ""
echo "=== Build succeeded ==="
echo "Output: ${OUTPUT}"
echo "Size:   $(wc -c < "${OUTPUT}") bytes"

READELF="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
if [[ -x "${READELF}" ]]; then
    echo ""
    echo "=== readelf -d ==="
    "${READELF}" -d "${OUTPUT}" | grep -E "(NEEDED|SONAME|VERNEED)" || true
fi
