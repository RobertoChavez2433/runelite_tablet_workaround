/*
 * fix_flip_depth.c — Shim B: Flip reversed-Z depth calls to standard depth
 *
 * LD_PRELOAD shim that intercepts:
 * - glDepthFunc: GL_GREATER -> GL_LESS, GL_GEQUAL -> GL_LEQUAL
 * - glClearDepth: 0.0 -> 1.0
 *
 * This allows RuneLite's reversed-Z pipeline to work on drivers that lack
 * glClipControl by inverting the depth comparisons at the API level.
 *
 * Compile: gcc -shared -fPIC -o fix_flip_depth.so fix_flip_depth.c -ldl
 * Usage:   LD_PRELOAD=/path/to/fix_flip_depth.so ./gl_test_harness ...
 *
 * Thread-safe via _Atomic flag (for one-time logging).
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdatomic.h>

/* GL types */
typedef unsigned int GLenum;
typedef double GLdouble;

/* GL depth function constants */
#define GL_NEVER    0x0200
#define GL_LESS     0x0201
#define GL_EQUAL    0x0202
#define GL_LEQUAL   0x0203
#define GL_GREATER  0x0204
#define GL_NOTEQUAL 0x0205
#define GL_GEQUAL   0x0206
#define GL_ALWAYS   0x0207

/* Function pointer types */
typedef void (*PFNGLDEPTHFUNCPROC)(GLenum func);
typedef void (*PFNGLCLEARDEPTHPROC)(GLdouble depth);

/* Real function pointers */
static PFNGLDEPTHFUNCPROC real_glDepthFunc = NULL;
static PFNGLCLEARDEPTHPROC real_glClearDepth = NULL;

/* One-time init flag for logging */
static _Atomic int logged_init = 0;

static const char *depth_func_name(GLenum func) {
    switch (func) {
        case GL_NEVER:    return "GL_NEVER";
        case GL_LESS:     return "GL_LESS";
        case GL_EQUAL:    return "GL_EQUAL";
        case GL_LEQUAL:   return "GL_LEQUAL";
        case GL_GREATER:  return "GL_GREATER";
        case GL_NOTEQUAL: return "GL_NOTEQUAL";
        case GL_GEQUAL:   return "GL_GEQUAL";
        case GL_ALWAYS:   return "GL_ALWAYS";
        default:          return "UNKNOWN";
    }
}

void glDepthFunc(GLenum func) {
    if (!real_glDepthFunc) {
        real_glDepthFunc = (PFNGLDEPTHFUNCPROC)dlsym(RTLD_NEXT, "glDepthFunc");
        if (!real_glDepthFunc) {
            fprintf(stderr, "[SHIM-B] FATAL: Cannot resolve real glDepthFunc\n");
            return;
        }
    }

    GLenum original = func;

    /* Flip reversed-Z depth functions to standard */
    switch (func) {
        case GL_GREATER:
            func = GL_LESS;
            break;
        case GL_GEQUAL:
            func = GL_LEQUAL;
            break;
        case GL_LESS:
            func = GL_GREATER;
            break;
        case GL_LEQUAL:
            func = GL_GEQUAL;
            break;
        default:
            /* No change for NEVER, EQUAL, NOTEQUAL, ALWAYS */
            break;
    }

    if (func != original) {
        fprintf(stderr, "[SHIM-B] glDepthFunc(%s -> %s)\n",
                depth_func_name(original), depth_func_name(func));
    }

    real_glDepthFunc(func);
}

void glClearDepth(GLdouble depth) {
    if (!real_glClearDepth) {
        real_glClearDepth = (PFNGLCLEARDEPTHPROC)dlsym(RTLD_NEXT, "glClearDepth");
        if (!real_glClearDepth) {
            fprintf(stderr, "[SHIM-B] FATAL: Cannot resolve real glClearDepth\n");
            return;
        }
    }

    if (!atomic_exchange(&logged_init, 1)) {
        fprintf(stderr, "[SHIM-B] Depth flip shim active\n");
    }

    /* Flip reversed-Z clear depth: 0.0 -> 1.0 */
    if (depth == 0.0) {
        fprintf(stderr, "[SHIM-B] glClearDepth(0.0 -> 1.0)\n");
        depth = 1.0;
    } else if (depth == 1.0) {
        fprintf(stderr, "[SHIM-B] glClearDepth(1.0 -> 0.0)\n");
        depth = 0.0;
    }

    real_glClearDepth(depth);
}
