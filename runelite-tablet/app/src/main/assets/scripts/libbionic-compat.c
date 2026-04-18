// libbionic-compat.c — glibc compatibility shims for LWJGL's liblwjgl.so
// running under Bionic (Termux native JVM path).
//
// liblwjgl.so is built against glibc and references 4 glibc-only symbols
// that Bionic libc does not export:
//   __errno_location, __xstat64, __fxstat64, __getdelim
// Three more (__gmon_start__, _ITM_registerTMCloneTable,
// _ITM_deregisterTMCloneTable) are WEAK and harmless when unresolved.
//
// We compile this file into libbionic-compat.so and add it to liblwjgl.so's
// DT_NEEDED via `patchelf --add-needed`. Bionic's dynamic linker finds the
// missing symbols here; each one forwards to the Bionic equivalent.
//
// Built by patch-lwjgl-bionic.sh via Termux clang at launch time.

#include <errno.h>
#include <stdio.h>
#include <stdint.h>
#include <sys/stat.h>
#include <sys/types.h>

// Bionic: errno is a thread-local accessed via `__errno()` (returns int*).
// glibc code calls `__errno_location()` for the same purpose; same sig.
extern int* __errno(void);
int* __errno_location(void) { return __errno(); }

// glibc __xstat64 / __fxstat64 take a `ver` argument (stat struct version)
// that Bionic doesn't have. On 64-bit Android, `struct stat` is already
// 64-bit off_t, so `stat`/`fstat` match the layout `struct stat64` expects.
int __xstat64(int ver, const char* path, struct stat* buf) {
    (void)ver;
    return stat(path, buf);
}

int __fxstat64(int ver, int fd, struct stat* buf) {
    (void)ver;
    return fstat(fd, buf);
}

// Bionic has `getdelim` directly; glibc exports it under `__getdelim` too.
ssize_t __getdelim(char** lineptr, size_t* n, int delim, FILE* fp) {
    return getdelim(lineptr, n, delim, fp);
}
