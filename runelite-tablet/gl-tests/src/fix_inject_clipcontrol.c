/*
 * fix_inject_clipcontrol.c — Shim A: Inject glClipControl on first glClearDepth(0.0)
 *
 * LD_PRELOAD shim that intercepts glClearDepth and, on the first call with
 * depth == 0.0, attempts to call glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)
 * to enable reversed-Z natively.
 *
 * Compile: gcc -shared -fPIC -o fix_inject_clipcontrol.so fix_inject_clipcontrol.c -ldl
 * Usage:   LD_PRELOAD=/path/to/fix_inject_clipcontrol.so ./gl_test_harness ...
 *
 * Thread-safe via _Atomic flag.
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdatomic.h>

/* GL types (avoid pulling in full GL headers for a shim) */
typedef unsigned int GLenum;
typedef double GLdouble;
typedef int GLint;

/* GL constants */
#define GL_LOWER_LEFT 0x8CA1
#define GL_ZERO_TO_ONE 0x935F
#define GL_NO_ERROR 0
#define GL_CLIP_DEPTH_MODE 0x935D
#define GL_CLIP_ORIGIN 0x935C

/* Function pointer types */
typedef void (*PFNGLCLEARDEPTHPROC)(GLdouble depth);
typedef void (*PFNGLCLIPCONTROLPROC)(GLenum origin, GLenum depth);
typedef GLenum (*PFNGLGETERRORPROC)(void);
typedef void (*PFNGLGETINTEGERVPROC)(GLenum pname, GLint *params);

/* glXGetProcAddressARB for function lookup */
typedef void *(*PFNGLXGETPROCADDRESSARBPROC)(const unsigned char *procName);

/* Thread-safe initialization flag */
static _Atomic int initialized = 0;

/* Real function pointer (resolved once) */
static PFNGLCLEARDEPTHPROC real_glClearDepth = NULL;

void glClearDepth(GLdouble depth) {
    /* Resolve real glClearDepth on first call */
    if (!real_glClearDepth) {
        real_glClearDepth = (PFNGLCLEARDEPTHPROC)dlsym(RTLD_NEXT, "glClearDepth");
        if (!real_glClearDepth) {
            fprintf(stderr, "[SHIM-A] FATAL: Cannot resolve real glClearDepth\n");
            return;
        }
    }

    /* On first glClearDepth(0.0), inject glClipControl */
    if (depth == 0.0 && !atomic_exchange(&initialized, 1)) {
        fprintf(stderr, "[SHIM-A] Intercepted glClearDepth(0.0) — injecting glClipControl\n");

        /* Look up glXGetProcAddressARB to find glClipControl */
        PFNGLXGETPROCADDRESSARBPROC pGetProc =
            (PFNGLXGETPROCADDRESSARBPROC)dlsym(RTLD_DEFAULT, "glXGetProcAddressARB");

        PFNGLCLIPCONTROLPROC pClipControl = NULL;
        if (pGetProc) {
            pClipControl = (PFNGLCLIPCONTROLPROC)pGetProc(
                (const unsigned char *)"glClipControl");
        }

        if (pClipControl) {
            /* Clear any pending errors */
            PFNGLGETERRORPROC pGetError =
                (PFNGLGETERRORPROC)dlsym(RTLD_DEFAULT, "glGetError");
            if (pGetError) {
                while (pGetError() != GL_NO_ERROR) {}
            }

            /* Call glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE) */
            pClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE);

            GLenum err = pGetError ? pGetError() : 0;
            fprintf(stderr, "[SHIM-A] glClipControl injected, ptr=%p, glGetError=0x%04x\n",
                    (void *)pClipControl, err);

            /* Verify state was set */
            if (err == GL_NO_ERROR) {
                PFNGLGETINTEGERVPROC pGetIntegerv =
                    (PFNGLGETINTEGERVPROC)dlsym(RTLD_DEFAULT, "glGetIntegerv");
                if (pGetIntegerv) {
                    GLint depth_mode = 0, clip_origin = 0;
                    pGetIntegerv(GL_CLIP_DEPTH_MODE, &depth_mode);
                    pGetIntegerv(GL_CLIP_ORIGIN, &clip_origin);
                    fprintf(stderr, "[SHIM-A] Verified: CLIP_DEPTH_MODE=0x%04x, CLIP_ORIGIN=0x%04x\n",
                            depth_mode, clip_origin);
                }
            }
        } else {
            fprintf(stderr, "[SHIM-A] FATAL: glClipControl function pointer is NULL\n");
            fprintf(stderr, "[SHIM-A] glXGetProcAddressARB=%p\n", (void *)pGetProc);
        }
    }

    /* Always pass through to real glClearDepth */
    real_glClearDepth(depth);
}
