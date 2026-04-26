/*
 * Copyright (c) 2022 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#include "net_runelite_rlawt_AWTContext.h"
#include <jawt.h>
#include <jawt_md.h>
#include <stdbool.h>

#ifdef __APPLE__
# define GL_SILENCE_DEPRECATION
#	include <IOSurface/IOSurface.h>
# include <QuartzCore/CALayer.h>
# include <OpenGL/OpenGL.h>
#endif

#ifdef __unix__
#	include <X11/Xlib.h>
#	include <GL/glx.h>
#	include <stddef.h>
#	include <stdint.h>
#	include <stdio.h>
#	include <time.h>
#endif

#ifdef _WIN32
#	include <windows.h>
#	include <GL/gl.h>
#	include <wglext.h>
#endif

#ifdef __APPLE__
#define RLAWT_MAX_POOL 8

typedef struct {
	IOSurfaceRef surface;
	CGFloat scale;
	GLuint tex;
	GLuint fbo;
} IOSurfaceEntry;

typedef struct {
	IOSurfaceEntry entries[RLAWT_MAX_POOL];
	int count; // entries currently allocated (0..RLAWT_MAX_POOL)
	int back;  // current render target index
	int front; // most recently presented surface index

#ifdef RLAWT_POOL_DEBUG
	struct {
		int swaps;
		int allocs;
		int reuses;
		int grows;
		int stalls;
		int failures;
		int highWater;
	} stats;
	CFAbsoluteTime lastLogTime;
#endif
} IOSurfacePool;
#endif

#ifdef __unix__
/* GPU-perf telemetry state. All fields Unix-only. Guarded by perf.enabled. */
typedef unsigned long long rlawt_GLuint64;
typedef void (*rlawt_PFNGLGENQUERIESPROC)(int /*GLsizei*/ n, unsigned int * /*GLuint**/ ids);
typedef void (*rlawt_PFNGLDELETEQUERIESPROC)(int /*GLsizei*/ n, const unsigned int * /*GLuint**/ ids);
typedef void (*rlawt_PFNGLBEGINQUERYPROC)(unsigned int /*GLenum*/ target, unsigned int /*GLuint*/ id);
typedef void (*rlawt_PFNGLENDQUERYPROC)(unsigned int /*GLenum*/ target);
typedef void (*rlawt_PFNGLGETQUERYOBJECTUI64VPROC)(unsigned int /*GLuint*/ id, unsigned int /*GLenum*/ pname, rlawt_GLuint64 *params);

#define RLAWT_PERF_MAX_WINDOW 10000

typedef struct {
	bool enabled;            /* RLAWT_PERF master gate */
	bool finish_enabled;     /* RLAWT_PERF_FINISH sub-flag */
	bool xsync_enabled;      /* RLAWT_PERF_XSYNC sub-flag */
	bool gpu_timer_enabled;  /* RLAWT_PERF_GPU_TIMER sub-flag (intent) */
	bool gpu_timer_available;/* actual availability after extension probe */

	size_t window;           /* samples per window; clamped to 1..RLAWT_PERF_MAX_WINDOW */
	size_t frame_idx;        /* monotonically increasing */
	struct timespec last_post_swap_ts; /* zero-initialized; zero means "no prior frame" */

	uint64_t *swap_samples;  /* window-sized; allocated once at createGLContext */
	uint64_t *gap_samples;
	uint64_t *xsync_samples;
	uint64_t *finish_samples;
	uint64_t *gpu_samples;   /* microseconds from GL timer query; 0 => invalid */
	size_t    gpu_valid_count; /* per-window count of valid GPU samples */

	unsigned int *gpu_queries; /* GL query object IDs, window-sized ring */
	bool gpu_queries_allocated;

	unsigned int mkcur_count_window;

	/* GL timer query function pointers */
	rlawt_PFNGLGENQUERIESPROC           fpGenQueries;
	rlawt_PFNGLDELETEQUERIESPROC        fpDeleteQueries;
	rlawt_PFNGLBEGINQUERYPROC           fpBeginQuery;
	rlawt_PFNGLENDQUERYPROC             fpEndQuery;
	rlawt_PFNGLGETQUERYOBJECTUI64VPROC  fpGetQueryObjectui64v;

	/* S81 audit: optional per-frame CSV dump, gated by RLAWT_PERF_CSV=<path>.
	 * Writes one line per swapBuffers. Independent of the window summary. */
	FILE *csv_fp;
} RlawtPerfState;
#endif

typedef struct {
	JAWT awt;
	JAWT_DrawingSurface *ds;
	bool contextCreated;

#ifdef __APPLE__
	CALayer *layer;
	CGLContextObj context;
	IOSurfacePool pool;

	int offsetX;
	int offsetY;
#endif

#ifdef __unix__
	Display *dpy;
	Drawable drawable;
	GLXContext context;
	PFNGLXSWAPINTERVALEXTPROC glXSwapIntervalEXT;
	bool glxSwapControlTear;
	PFNGLXSWAPINTERVALSGIPROC glXSwapIntervalSGI;
	bool doubleBuffered;
	RlawtPerfState perf;
#endif

#ifdef _WIN32
	JAWT_DrawingSurfaceInfo *dsi;
	JAWT_Win32DrawingSurfaceInfo *dspi;
	HGLRC context;
	PFNWGLSWAPINTERVALEXTPROC wglSwapIntervalEXT;
	bool wglSwapControlTear;
#endif

	int alphaDepth;
	int depthDepth;
	int stencilDepth;

	int multisamples;
} AWTContext;

void rlawtThrow(JNIEnv *env, const char *msg);
void rlawtUnlockAWT(JNIEnv *env, AWTContext *ctx);
AWTContext *rlawtGetContext(JNIEnv *env, jobject self);
bool rlawtContextState(JNIEnv *env, AWTContext *context, bool created);


void rlawtContextFreePlatform(JNIEnv *env, AWTContext *ctx);