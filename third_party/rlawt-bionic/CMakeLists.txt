cmake_minimum_required(VERSION 3.22)
project(rlawt-bionic C)

# Bionic-native build of rlawt for Termux (Samsung OneUI + Android 12+).
#
# Drop-in replacement for rlawt-1.8.jar's
# net/runelite/rlawt/linux-aarch64/librlawt.so. Compiled against Termux
# openjdk-21-x JAWT + Termux Mesa libGL + Termux libX11 (all Bionic
# sonames, no glibc). The upstream rlawt source under ../rlawt/ is
# untouched; we just compile it through the NDK toolchain with harvested
# target headers and link stubs.

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(RLAWT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../rlawt")
set(HARVEST "${CMAKE_CURRENT_SOURCE_DIR}/harvest")
set(GENERATED "${CMAKE_CURRENT_SOURCE_DIR}/generated")

# Sanity: every harvest path must resolve. Fail loud at configure time.
foreach(path
    "${RLAWT_ROOT}/rlawt.c"
    "${RLAWT_ROOT}/rlawt_nix.c"
    "${RLAWT_ROOT}/rlawt_windows.c"
    "${RLAWT_ROOT}/rlawt.h"
    "${HARVEST}/jdk-include/jni.h"
    "${HARVEST}/jdk-include/jawt.h"
    "${HARVEST}/jdk-include/linux/jni_md.h"
    "${HARVEST}/jdk-include/linux/jawt_md.h"
    "${HARVEST}/x11-include/X11/Xlib.h"
    "${HARVEST}/gl-include/GL/glx.h"
    "${HARVEST}/sysroot-stubs/libjawt.so"
    "${HARVEST}/sysroot-stubs/libGL.so"
    "${HARVEST}/sysroot-stubs/libGLX.so"
    "${HARVEST}/sysroot-stubs/libX11.so"
    "${GENERATED}/net_runelite_rlawt_AWTContext.h")
    if(NOT EXISTS "${path}")
        message(FATAL_ERROR "rlawt-bionic: missing harvest artifact: ${path}")
    endif()
endforeach()

add_library(rlawt SHARED
    "${RLAWT_ROOT}/rlawt.c"
    "${RLAWT_ROOT}/rlawt_nix.c"
    "${RLAWT_ROOT}/rlawt_windows.c")

set_target_properties(rlawt PROPERTIES
    OUTPUT_NAME "rlawt"
    PREFIX "lib"
    SUFFIX ".so")

target_include_directories(rlawt PRIVATE
    "${GENERATED}"
    "${HARVEST}/jdk-include"
    "${HARVEST}/jdk-include/linux"
    "${HARVEST}/x11-include"
    "${HARVEST}/gl-include")

target_compile_options(rlawt PRIVATE
    -Wall
    -fvisibility=hidden)

target_link_options(rlawt PRIVATE
    -Wl,-soname,librlawt.so
    -Wl,--no-undefined)

target_link_libraries(rlawt PRIVATE
    "${HARVEST}/sysroot-stubs/libjawt.so"
    "${HARVEST}/sysroot-stubs/libGL.so"
    "${HARVEST}/sysroot-stubs/libGLX.so"
    "${HARVEST}/sysroot-stubs/libX11.so")
