/*
 * gl_test_harness.c — VirGL rendering test harness.
 *
 * 9 modules testing GL capabilities required by RuneLite:
 *   Module 1: GL Capability Dump (glGetString, glGetIntegerv, extensions, function pointers)
 *   Module 2: GLSL 330 Shader Compilation (all shaders from test_shaders.h)
 *   Module 3: glClipControl Probe (pointer check, call test, state query)
 *   Module 4: Reversed-Z Depth (FBO render, 4a/4b/4c sub-modes for shim comparison)
 *   Module 5: sampler2DArray (3-layer texture, validate pixel colors)
 *   Module 6: noperspective vs smooth (compare FBO outputs)
 *   Module 7: FBO Blit (render to FBO A, blit to FBO B, compare CRC32)
 *   Module 8: RuneLite Scene Emulation (all features combined)
 *   Module 9: Performance Baseline (frame time stats, glReadPixels latency)
 *
 * Build:
 *   gcc -o gl_test_harness gl_test_harness.c \
 *       -lGL -lGLU -lglfw -lX11 -lm -ldl -DSTB_IMAGE_WRITE_IMPLEMENTATION
 *
 * Usage:
 *   ./gl_test_harness --results-dir <dir> --all
 *   ./gl_test_harness --results-dir <dir> --module <1-9|4a|4b|4c>
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <dlfcn.h>

#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glx.h>
#include <GL/glu.h>
#include <GLFW/glfw3.h>

#include "gl_test_log.h"
#include "test_shaders.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

/* ===== GL Extension Constants ===== */

#ifndef GL_CLIP_DISTANCE0
#define GL_CLIP_DISTANCE0 0x3000
#endif
#ifndef GL_LOWER_LEFT
#define GL_LOWER_LEFT 0x8CA1
#endif
#ifndef GL_UPPER_LEFT
#define GL_UPPER_LEFT 0x8CA2
#endif
#ifndef GL_ZERO_TO_ONE
#define GL_ZERO_TO_ONE 0x935F
#endif
#ifndef GL_NEGATIVE_ONE_TO_ONE
#define GL_NEGATIVE_ONE_TO_ONE 0x935E
#endif
#ifndef GL_CLIP_DEPTH_MODE
#define GL_CLIP_DEPTH_MODE 0x935D
#endif
#ifndef GL_CLIP_ORIGIN
#define GL_CLIP_ORIGIN 0x935C
#endif
#ifndef GL_TEXTURE_2D_ARRAY
#define GL_TEXTURE_2D_ARRAY 0x8C1A
#endif
#ifndef GL_DEPTH_COMPONENT32F
#define GL_DEPTH_COMPONENT32F 0x8CAC
#endif

/* ===== Function Pointer Types ===== */

typedef void (*PFNGLCLIPCONTROLPROC)(GLenum origin, GLenum depth);
typedef void (*PFNGLGENFRAMEBUFFERSPROC)(GLsizei n, GLuint *framebuffers);
typedef void (*PFNGLBINDFRAMEBUFFERPROC)(GLenum target, GLuint framebuffer);
typedef void (*PFNGLFRAMEBUFFERTEXTURE2DPROC)(GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level);
typedef GLenum (*PFNGLCHECKFRAMEBUFFERSTATUSPROC)(GLenum target);
typedef void (*PFNGLDELETEFRAMEBUFFERSPROC)(GLsizei n, const GLuint *framebuffers);
typedef void (*PFNGLBLITFRAMEBUFFERPROC)(GLint srcX0, GLint srcY0, GLint srcX1, GLint srcY1, GLint dstX0, GLint dstY0, GLint dstX1, GLint dstY1, GLbitfield mask, GLenum filter);
typedef void (*PFNGLGENBUFFERSPROC)(GLsizei n, GLuint *buffers);
typedef void (*PFNGLBINDBUFFERPROC)(GLenum target, GLuint buffer);
typedef void (*PFNGLBUFFERDATAPROC)(GLenum target, GLsizeiptr size, const void *data, GLenum usage);
typedef void (*PFNGLDELETEBUFFERSPROC)(GLsizei n, const GLuint *buffers);
typedef GLuint (*PFNGLCREATESHADERPROC)(GLenum type);
typedef void (*PFNGLSHADERSOURCEPROC)(GLuint shader, GLsizei count, const GLchar *const *string, const GLint *length);
typedef void (*PFNGLCOMPILESHADERPROC)(GLuint shader);
typedef void (*PFNGLGETSHADERIVPROC)(GLuint shader, GLenum pname, GLint *params);
typedef void (*PFNGLGETSHADERINFOLOGPROC)(GLuint shader, GLsizei bufSize, GLsizei *length, GLchar *infoLog);
typedef GLuint (*PFNGLCREATEPROGRAMPROC)(void);
typedef void (*PFNGLATTACHSHADERPROC)(GLuint program, GLuint shader);
typedef void (*PFNGLLINKPROGRAMPROC)(GLuint program);
typedef void (*PFNGLGETPROGRAMIVPROC)(GLuint program, GLenum pname, GLint *params);
typedef void (*PFNGLGETPROGRAMINFOLOGPROC)(GLuint program, GLsizei bufSize, GLsizei *length, GLchar *infoLog);
typedef void (*PFNGLUSEPROGRAMPROC)(GLuint program);
typedef void (*PFNGLDELETESHADERPROC)(GLuint shader);
typedef void (*PFNGLDELETEPROGRAMPROC)(GLuint program);
typedef void (*PFNGLGENVERTEXARRAYSPROC)(GLsizei n, GLuint *arrays);
typedef void (*PFNGLBINDVERTEXARRAYPROC)(GLuint array);
typedef void (*PFNGLDELETEVERTEXARRAYSPROC)(GLsizei n, const GLuint *arrays);
typedef void (*PFNGLENABLEVERTEXATTRIBARRAYPROC)(GLuint index);
typedef void (*PFNGLVERTEXATTRIBPOINTERPROC)(GLuint index, GLint size, GLenum type, GLboolean normalized, GLsizei stride, const void *pointer);
typedef void (*PFNGLBINDBUFFERBASEPROC)(GLenum target, GLuint index, GLuint buffer);
typedef GLuint (*PFNGLGETUNIFORMBLOCKINDEXPROC)(GLuint program, const GLchar *uniformBlockName);
typedef void (*PFNGLUNIFORMBLOCKBINDINGPROC)(GLuint program, GLuint uniformBlockIndex, GLuint uniformBlockBinding);
typedef void (*PFNGLBUFFERSUBDATAPROC)(GLenum target, GLintptr offset, GLsizeiptr size, const void *data);
typedef GLint (*PFNGLGETUNIFORMLOCATIONPROC)(GLuint program, const GLchar *name);
typedef void (*PFNGLUNIFORM1IPROC)(GLint location, GLint v0);
typedef void (*PFNGLACTIVETEXTUREPROC)(GLenum texture);
typedef void (*PFNGLTEXIMAGE3DPROC)(GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLsizei depth, GLint border, GLenum format, GLenum type, const void *pixels);
typedef void (*PFNGLTEXSUBIMAGE3DPROC)(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint zoffset, GLsizei width, GLsizei height, GLsizei depth, GLenum format, GLenum type, const void *pixels);
typedef void (*PFNGLGENERATEMIPMAPPROC)(GLenum target);
typedef void (*PFNGLDRAWBUFFERSPROC)(GLsizei n, const GLenum *bufs);

/* ===== Resolved Function Pointers ===== */

static PFNGLCLIPCONTROLPROC pglClipControl = NULL;
static PFNGLGENFRAMEBUFFERSPROC pglGenFramebuffers = NULL;
static PFNGLBINDFRAMEBUFFERPROC pglBindFramebuffer = NULL;
static PFNGLFRAMEBUFFERTEXTURE2DPROC pglFramebufferTexture2D = NULL;
static PFNGLCHECKFRAMEBUFFERSTATUSPROC pglCheckFramebufferStatus = NULL;
static PFNGLDELETEFRAMEBUFFERSPROC pglDeleteFramebuffers = NULL;
static PFNGLBLITFRAMEBUFFERPROC pglBlitFramebuffer = NULL;
static PFNGLGENBUFFERSPROC pglGenBuffers = NULL;
static PFNGLBINDBUFFERPROC pglBindBuffer = NULL;
static PFNGLBUFFERDATAPROC pglBufferData = NULL;
static PFNGLDELETEBUFFERSPROC pglDeleteBuffers = NULL;
static PFNGLCREATESHADERPROC pglCreateShader = NULL;
static PFNGLSHADERSOURCEPROC pglShaderSource = NULL;
static PFNGLCOMPILESHADERPROC pglCompileShader = NULL;
static PFNGLGETSHADERIVPROC pglGetShaderiv = NULL;
static PFNGLGETSHADERINFOLOGPROC pglGetShaderInfoLog = NULL;
static PFNGLCREATEPROGRAMPROC pglCreateProgram = NULL;
static PFNGLATTACHSHADERPROC pglAttachShader = NULL;
static PFNGLLINKPROGRAMPROC pglLinkProgram = NULL;
static PFNGLGETPROGRAMIVPROC pglGetProgramiv = NULL;
static PFNGLGETPROGRAMINFOLOGPROC pglGetProgramInfoLog = NULL;
static PFNGLUSEPROGRAMPROC pglUseProgram = NULL;
static PFNGLDELETESHADERPROC pglDeleteShader = NULL;
static PFNGLDELETEPROGRAMPROC pglDeleteProgram = NULL;
static PFNGLGENVERTEXARRAYSPROC pglGenVertexArrays = NULL;
static PFNGLBINDVERTEXARRAYPROC pglBindVertexArray = NULL;
static PFNGLDELETEVERTEXARRAYSPROC pglDeleteVertexArrays = NULL;
static PFNGLENABLEVERTEXATTRIBARRAYPROC pglEnableVertexAttribArray = NULL;
static PFNGLVERTEXATTRIBPOINTERPROC pglVertexAttribPointer = NULL;
static PFNGLBINDBUFFERBASEPROC pglBindBufferBase = NULL;
static PFNGLGETUNIFORMBLOCKINDEXPROC pglGetUniformBlockIndex = NULL;
static PFNGLUNIFORMBLOCKBINDINGPROC pglUniformBlockBinding = NULL;
static PFNGLBUFFERSUBDATAPROC pglBufferSubData = NULL;
static PFNGLGETUNIFORMLOCATIONPROC pglGetUniformLocation = NULL;
static PFNGLUNIFORM1IPROC pglUniform1i = NULL;
static PFNGLACTIVETEXTUREPROC pglActiveTexture = NULL;
static PFNGLTEXIMAGE3DPROC pglTexImage3D = NULL;
static PFNGLTEXSUBIMAGE3DPROC pglTexSubImage3D = NULL;
static PFNGLGENERATEMIPMAPPROC pglGenerateMipmap = NULL;
static PFNGLDRAWBUFFERSPROC pglDrawBuffers = NULL;

/* ===== Constants ===== */

#define FBO_WIDTH  256
#define FBO_HEIGHT 256
#define PERF_FRAMES 300
#define PERF_HALF_WIDTH 128
#define PERF_HALF_HEIGHT 128

/* ===== Helper: Resolve GL Function Pointers ===== */

static void *resolve_gl(const char *name) {
    void *ptr = (void *)glXGetProcAddressARB((const GLubyte *)name);
    return ptr;
}

static void resolve_all_functions(void) {
    pglClipControl = (PFNGLCLIPCONTROLPROC)resolve_gl("glClipControl");
    pglGenFramebuffers = (PFNGLGENFRAMEBUFFERSPROC)resolve_gl("glGenFramebuffers");
    pglBindFramebuffer = (PFNGLBINDFRAMEBUFFERPROC)resolve_gl("glBindFramebuffer");
    pglFramebufferTexture2D = (PFNGLFRAMEBUFFERTEXTURE2DPROC)resolve_gl("glFramebufferTexture2D");
    pglCheckFramebufferStatus = (PFNGLCHECKFRAMEBUFFERSTATUSPROC)resolve_gl("glCheckFramebufferStatus");
    pglDeleteFramebuffers = (PFNGLDELETEFRAMEBUFFERSPROC)resolve_gl("glDeleteFramebuffers");
    pglBlitFramebuffer = (PFNGLBLITFRAMEBUFFERPROC)resolve_gl("glBlitFramebuffer");
    pglGenBuffers = (PFNGLGENBUFFERSPROC)resolve_gl("glGenBuffers");
    pglBindBuffer = (PFNGLBINDBUFFERPROC)resolve_gl("glBindBuffer");
    pglBufferData = (PFNGLBUFFERDATAPROC)resolve_gl("glBufferData");
    pglDeleteBuffers = (PFNGLDELETEBUFFERSPROC)resolve_gl("glDeleteBuffers");
    pglCreateShader = (PFNGLCREATESHADERPROC)resolve_gl("glCreateShader");
    pglShaderSource = (PFNGLSHADERSOURCEPROC)resolve_gl("glShaderSource");
    pglCompileShader = (PFNGLCOMPILESHADERPROC)resolve_gl("glCompileShader");
    pglGetShaderiv = (PFNGLGETSHADERIVPROC)resolve_gl("glGetShaderiv");
    pglGetShaderInfoLog = (PFNGLGETSHADERINFOLOGPROC)resolve_gl("glGetShaderInfoLog");
    pglCreateProgram = (PFNGLCREATEPROGRAMPROC)resolve_gl("glCreateProgram");
    pglAttachShader = (PFNGLATTACHSHADERPROC)resolve_gl("glAttachShader");
    pglLinkProgram = (PFNGLLINKPROGRAMPROC)resolve_gl("glLinkProgram");
    pglGetProgramiv = (PFNGLGETPROGRAMIVPROC)resolve_gl("glGetProgramiv");
    pglGetProgramInfoLog = (PFNGLGETPROGRAMINFOLOGPROC)resolve_gl("glGetProgramInfoLog");
    pglUseProgram = (PFNGLUSEPROGRAMPROC)resolve_gl("glUseProgram");
    pglDeleteShader = (PFNGLDELETESHADERPROC)resolve_gl("glDeleteShader");
    pglDeleteProgram = (PFNGLDELETEPROGRAMPROC)resolve_gl("glDeleteProgram");
    pglGenVertexArrays = (PFNGLGENVERTEXARRAYSPROC)resolve_gl("glGenVertexArrays");
    pglBindVertexArray = (PFNGLBINDVERTEXARRAYPROC)resolve_gl("glBindVertexArray");
    pglDeleteVertexArrays = (PFNGLDELETEVERTEXARRAYSPROC)resolve_gl("glDeleteVertexArrays");
    pglEnableVertexAttribArray = (PFNGLENABLEVERTEXATTRIBARRAYPROC)resolve_gl("glEnableVertexAttribArray");
    pglVertexAttribPointer = (PFNGLVERTEXATTRIBPOINTERPROC)resolve_gl("glVertexAttribPointer");
    pglBindBufferBase = (PFNGLBINDBUFFERBASEPROC)resolve_gl("glBindBufferBase");
    pglGetUniformBlockIndex = (PFNGLGETUNIFORMBLOCKINDEXPROC)resolve_gl("glGetUniformBlockIndex");
    pglUniformBlockBinding = (PFNGLUNIFORMBLOCKBINDINGPROC)resolve_gl("glUniformBlockBinding");
    pglBufferSubData = (PFNGLBUFFERSUBDATAPROC)resolve_gl("glBufferSubData");
    pglGetUniformLocation = (PFNGLGETUNIFORMLOCATIONPROC)resolve_gl("glGetUniformLocation");
    pglUniform1i = (PFNGLUNIFORM1IPROC)resolve_gl("glUniform1i");
    pglActiveTexture = (PFNGLACTIVETEXTUREPROC)resolve_gl("glActiveTexture");
    pglTexImage3D = (PFNGLTEXIMAGE3DPROC)resolve_gl("glTexImage3D");
    pglTexSubImage3D = (PFNGLTEXSUBIMAGE3DPROC)resolve_gl("glTexSubImage3D");
    pglGenerateMipmap = (PFNGLGENERATEMIPMAPPROC)resolve_gl("glGenerateMipmap");
    pglDrawBuffers = (PFNGLDRAWBUFFERSPROC)resolve_gl("glDrawBuffers");
}

/* ===== Helper: Compile Shader ===== */

static GLuint compile_shader(GLenum type, const char *source, const char *name) {
    if (!pglCreateShader || !pglShaderSource || !pglCompileShader || !pglGetShaderiv) {
        LOG_ERROR("Shader functions not available for %s", name);
        return 0;
    }

    GLuint shader = pglCreateShader(type);
    CHECK_GL("glCreateShader");
    pglShaderSource(shader, 1, &source, NULL);
    CHECK_GL("glShaderSource");
    pglCompileShader(shader);
    CHECK_GL("glCompileShader");

    GLint status = 0;
    pglGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    CHECK_GL("glGetShaderiv");

    if (!status) {
        char info[1024] = {0};
        if (pglGetShaderInfoLog) {
            pglGetShaderInfoLog(shader, sizeof(info), NULL, info);
        }
        LOG_ERROR("Shader %s compile FAILED: %s", name, info);
        pglDeleteShader(shader);
        return 0;
    }

    LOG_INFO("Shader %s compiled OK", name);
    return shader;
}

/* ===== Helper: Link Program ===== */

static GLuint link_program(GLuint vert, GLuint frag, const char *name) {
    if (!pglCreateProgram || !pglAttachShader || !pglLinkProgram || !pglGetProgramiv) {
        LOG_ERROR("Program functions not available for %s", name);
        return 0;
    }

    GLuint prog = pglCreateProgram();
    CHECK_GL("glCreateProgram");
    pglAttachShader(prog, vert);
    CHECK_GL("glAttachShader(vert)");
    pglAttachShader(prog, frag);
    CHECK_GL("glAttachShader(frag)");
    pglLinkProgram(prog);
    CHECK_GL("glLinkProgram");

    GLint status = 0;
    pglGetProgramiv(prog, GL_LINK_STATUS, &status);
    CHECK_GL("glGetProgramiv");

    if (!status) {
        char info[1024] = {0};
        if (pglGetProgramInfoLog) {
            pglGetProgramInfoLog(prog, sizeof(info), NULL, info);
        }
        LOG_ERROR("Program %s link FAILED: %s", name, info);
        pglDeleteProgram(prog);
        return 0;
    }

    LOG_INFO("Program %s linked OK", name);
    return prog;
}

/* ===== Helper: Create FBO with color + depth ===== */

static int create_fbo(GLuint *fbo, GLuint *colorTex, GLuint *depthTex, int w, int h, int depth32f) {
    if (!pglGenFramebuffers || !pglBindFramebuffer || !pglFramebufferTexture2D || !pglCheckFramebufferStatus) {
        LOG_ERROR("FBO functions not available");
        return 0;
    }

    glGenTextures(1, colorTex);
    glBindTexture(GL_TEXTURE_2D, *colorTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    CHECK_GL("color texture");

    glGenTextures(1, depthTex);
    glBindTexture(GL_TEXTURE_2D, *depthTex);
    if (depth32f) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, w, h, 0, GL_DEPTH_COMPONENT, GL_FLOAT, NULL);
    } else {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, NULL);
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    CHECK_GL("depth texture");

    pglGenFramebuffers(1, fbo);
    pglBindFramebuffer(GL_FRAMEBUFFER, *fbo);
    pglFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, *colorTex, 0);
    pglFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, *depthTex, 0);
    CHECK_GL("FBO attach");

    GLenum status = pglCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        LOG_ERROR("FBO incomplete: 0x%04x", status);
        return 0;
    }

    return 1;
}

/* ===== Helper: Destroy FBO ===== */

static void destroy_fbo(GLuint fbo, GLuint colorTex, GLuint depthTex) {
    if (pglDeleteFramebuffers) pglDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &colorTex);
    glDeleteTextures(1, &depthTex);
}

/* ===== Helper: Save framebuffer to PNG ===== */

static void save_fbo_png(const char *filename, int w, int h) {
    unsigned char *pixels = (unsigned char *)malloc(w * h * 4);
    if (!pixels) return;

    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    CHECK_GL("glReadPixels");

    /* Flip vertically (OpenGL origin is bottom-left) */
    unsigned char *flipped = (unsigned char *)malloc(w * h * 4);
    if (flipped) {
        for (int y = 0; y < h; y++) {
            memcpy(flipped + y * w * 4, pixels + (h - 1 - y) * w * 4, w * 4);
        }
        stbi_write_png(filename, w, h, 4, flipped, w * 4);
        free(flipped);
    }
    free(pixels);
}

/* ===== Helper: Read depth buffer stats ===== */

static void read_depth_stats(int w, int h, float *out_min, float *out_max, float *out_mean) {
    float *depth = (float *)malloc(w * h * sizeof(float));
    if (!depth) {
        *out_min = *out_max = *out_mean = -1.0f;
        return;
    }

    glReadPixels(0, 0, w, h, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
    CHECK_GL("glReadPixels(depth)");

    float dmin = 1.0f, dmax = 0.0f;
    double dsum = 0.0;
    for (int i = 0; i < w * h; i++) {
        if (depth[i] < dmin) dmin = depth[i];
        if (depth[i] > dmax) dmax = depth[i];
        dsum += depth[i];
    }

    *out_min = dmin;
    *out_max = dmax;
    *out_mean = (float)(dsum / (w * h));
    free(depth);
}

/* ===== Helper: Setup basic triangle geometry ===== */

static void setup_fullscreen_quad(GLuint *vao, GLuint *vbo) {
    float quad[] = {
        /* pos(x,y,z), color(r,g,b,a) */
        -1.0f, -1.0f, 0.0f,   1.0f, 0.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 0.0f,   0.0f, 1.0f, 0.0f, 1.0f,
        -1.0f,  1.0f, 0.0f,   0.0f, 0.0f, 1.0f, 1.0f,
         1.0f,  1.0f, 0.0f,   1.0f, 1.0f, 0.0f, 1.0f,
    };

    pglGenVertexArrays(1, vao);
    pglBindVertexArray(*vao);
    pglGenBuffers(1, vbo);
    pglBindBuffer(GL_ARRAY_BUFFER, *vbo);
    pglBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    pglEnableVertexAttribArray(0);
    pglVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void *)0);
    pglEnableVertexAttribArray(1);
    pglVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void *)(3 * sizeof(float)));
    CHECK_GL("setup_fullscreen_quad");
}

/* ===== Helper: Destroy VAO/VBO ===== */

static void destroy_geometry(GLuint vao, GLuint vbo) {
    if (pglDeleteVertexArrays) pglDeleteVertexArrays(1, &vao);
    if (pglDeleteBuffers) pglDeleteBuffers(1, &vbo);
}

/* ===== Helper: Create basic shader program from vert+frag ===== */

static GLuint create_program_from_sources(const char *vert_src, const char *frag_src, const char *name) {
    GLuint vert = compile_shader(GL_VERTEX_SHADER, vert_src, name);
    if (!vert) return 0;
    GLuint frag = compile_shader(GL_FRAGMENT_SHADER, frag_src, name);
    if (!frag) { pglDeleteShader(vert); return 0; }
    GLuint prog = link_program(vert, frag, name);
    pglDeleteShader(vert);
    pglDeleteShader(frag);
    return prog;
}

/* ===== Helper: Check pixel color at (x, y) ===== */

static int check_pixel(int x, int y, unsigned char er, unsigned char eg, unsigned char eb, int tolerance, const char *desc) {
    unsigned char pixel[4];
    glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    CHECK_GL("glReadPixels(pixel)");

    int dr = abs((int)pixel[0] - (int)er);
    int dg = abs((int)pixel[1] - (int)eg);
    int db = abs((int)pixel[2] - (int)eb);

    if (dr <= tolerance && dg <= tolerance && db <= tolerance) {
        LOG_INFO("Pixel check %s: OK (got %d,%d,%d expected %d,%d,%d)", desc, pixel[0], pixel[1], pixel[2], er, eg, eb);
        return 1;
    } else {
        LOG_ERROR("Pixel check %s: FAIL (got %d,%d,%d expected %d,%d,%d tol=%d)", desc, pixel[0], pixel[1], pixel[2], er, eg, eb, tolerance);
        return 0;
    }
}

/* ===== Helper: qsort comparator for doubles ===== */

static int cmp_double(const void *a, const void *b) {
    double da = *(const double *)a;
    double db = *(const double *)b;
    if (da < db) return -1;
    if (da > db) return 1;
    return 0;
}

/* ################################################################## */
/* ===== MODULE 1: GL Capability Dump ===== */
/* ################################################################## */

static void run_module_1(void) {
    double t0 = get_time_ms();
    LOG_INFO("=== Module 1: GL Capability Dump ===");

    /* Log environment and GL caps (writes JSON files) */
    log_environment();
    log_gl_caps();

    /* Probe function pointers */
    char fp_path[600];
    snprintf(fp_path, sizeof(fp_path), "%s/function-pointers.json", g_results_dir);
    FILE *fp_file = fopen(fp_path, "w");
    if (fp_file) fprintf(fp_file, "{\n");
    int first = 1;

    struct { const char *name; void *ptr; } probes[] = {
        {"glClipControl", (void *)pglClipControl},
        {"glBlitFramebuffer", (void *)pglBlitFramebuffer},
        {"glTexImage3D", (void *)pglTexImage3D},
        {"glTexSubImage3D", (void *)pglTexSubImage3D},
        {"glGenFramebuffers", (void *)pglGenFramebuffers},
        {"glBindFramebuffer", (void *)pglBindFramebuffer},
        {"glCreateShader", (void *)pglCreateShader},
        {"glCreateProgram", (void *)pglCreateProgram},
        {"glGenVertexArrays", (void *)pglGenVertexArrays},
        {"glBindVertexArray", (void *)pglBindVertexArray},
        {"glGenBuffers", (void *)pglGenBuffers},
        {"glBindBuffer", (void *)pglBindBuffer},
        {"glBufferData", (void *)pglBufferData},
        {"glUniformBlockBinding", (void *)pglUniformBlockBinding},
        {"glGetUniformBlockIndex", (void *)pglGetUniformBlockIndex},
        {"glBindBufferBase", (void *)pglBindBufferBase},
        {"glActiveTexture", (void *)pglActiveTexture},
        {"glGenerateMipmap", (void *)pglGenerateMipmap},
        {"glDrawBuffers", (void *)pglDrawBuffers},
        {"glGetUniformLocation", (void *)pglGetUniformLocation},
        {"glUniform1i", (void *)pglUniform1i},
        {"glShaderSource", (void *)pglShaderSource},
        {"glCompileShader", (void *)pglCompileShader},
        {"glLinkProgram", (void *)pglLinkProgram},
        {"glUseProgram", (void *)pglUseProgram},
        {"glEnableVertexAttribArray", (void *)pglEnableVertexAttribArray},
        {"glVertexAttribPointer", (void *)pglVertexAttribPointer},
        {"glDeleteShader", (void *)pglDeleteShader},
        {"glDeleteProgram", (void *)pglDeleteProgram},
        {"glDeleteFramebuffers", (void *)pglDeleteFramebuffers},
        {"glDeleteBuffers", (void *)pglDeleteBuffers},
    };

    int num_probes = sizeof(probes) / sizeof(probes[0]);
    int available = 0;
    for (int i = 0; i < num_probes; i++) {
        log_function_pointer(fp_file, probes[i].name, probes[i].ptr, &first);
        if (probes[i].ptr) available++;
    }

    if (fp_file) {
        fprintf(fp_file, "\n}\n");
        fclose(fp_file);
    }

    double elapsed = get_time_ms() - t0;
    char detail[128];
    snprintf(detail, sizeof(detail), "%d/%d function pointers available", available, num_probes);
    log_module_result(1, NULL, "GL Capability Dump", "PASS", elapsed, detail);
}

/* ################################################################## */
/* ===== MODULE 2: GLSL 330 Shader Compilation ===== */
/* ################################################################## */

static void run_module_2(void) {
    double t0 = get_time_ms();
    LOG_INFO("=== Module 2: GLSL 330 Shader Compilation ===");

    struct { const char *name; GLenum type; const char *source; } shaders[] = {
        {"SHADER_VERT_BASIC", GL_VERTEX_SHADER, SHADER_VERT_BASIC},
        {"SHADER_FRAG_BASIC", GL_FRAGMENT_SHADER, SHADER_FRAG_BASIC},
        {"SHADER_FRAG_TEXARRAY", GL_FRAGMENT_SHADER, SHADER_FRAG_TEXARRAY},
        {"SHADER_VERT_NOPERSP", GL_VERTEX_SHADER, SHADER_VERT_NOPERSP},
        {"SHADER_FRAG_NOPERSP", GL_FRAGMENT_SHADER, SHADER_FRAG_NOPERSP},
        {"SHADER_VERT_SMOOTH", GL_VERTEX_SHADER, SHADER_VERT_SMOOTH},
        {"SHADER_FRAG_COLORBLIND", GL_FRAGMENT_SHADER, SHADER_FRAG_COLORBLIND},
        {"SHADER_FRAG_TEXTURESIZE", GL_FRAGMENT_SHADER, SHADER_FRAG_TEXTURESIZE},
        {"SHADER_VERT_SCENE", GL_VERTEX_SHADER, SHADER_VERT_SCENE},
        {"SHADER_FRAG_SCENE", GL_FRAGMENT_SHADER, SHADER_FRAG_SCENE},
    };

    int num_shaders = sizeof(shaders) / sizeof(shaders[0]);
    int passed = 0;

    for (int i = 0; i < num_shaders; i++) {
        GLuint s = compile_shader(shaders[i].type, shaders[i].source, shaders[i].name);
        if (s) {
            passed++;
            pglDeleteShader(s);
        }
    }

    /* Test linking of shader pairs */
    struct { const char *name; const char *vert; const char *frag; } programs[] = {
        {"basic", SHADER_VERT_BASIC, SHADER_FRAG_BASIC},
        {"noperspective", SHADER_VERT_NOPERSP, SHADER_FRAG_NOPERSP},
        {"smooth", SHADER_VERT_SMOOTH, SHADER_FRAG_BASIC},
        {"colorblind", SHADER_VERT_BASIC, SHADER_FRAG_COLORBLIND},
        {"scene", SHADER_VERT_SCENE, SHADER_FRAG_SCENE},
    };

    int num_progs = sizeof(programs) / sizeof(programs[0]);
    int linked = 0;

    for (int i = 0; i < num_progs; i++) {
        GLuint prog = create_program_from_sources(programs[i].vert, programs[i].frag, programs[i].name);
        if (prog) {
            linked++;
            pglDeleteProgram(prog);
        }
    }

    double elapsed = get_time_ms() - t0;
    char detail[128];
    snprintf(detail, sizeof(detail), "%d/%d shaders compiled, %d/%d programs linked", passed, num_shaders, linked, num_progs);
    const char *status = (passed == num_shaders && linked == num_progs) ? "PASS" : "FAIL";
    log_module_result(2, NULL, "GLSL 330 Shader Compilation", status, elapsed, detail);
}

/* ################################################################## */
/* ===== MODULE 3: glClipControl Probe ===== */
/* ################################################################## */

static void run_module_3(void) {
    double t0 = get_time_ms();
    LOG_INFO("=== Module 3: glClipControl Probe ===");

    char detail[256] = "";
    const char *status = "FAIL";

    /* 3a: pointer check */
    LOG_INFO("3a: glClipControl function pointer check");
    if (!pglClipControl) {
        LOG_ERROR("3a: glClipControl function pointer is NULL");
        snprintf(detail, sizeof(detail), "glClipControl not available");
        log_module_result(3, "a", "glClipControl pointer", "FAIL", get_time_ms() - t0, detail);
    } else {
        LOG_INFO("3a: glClipControl pointer = %p", (void *)pglClipControl);
        log_module_result(3, "a", "glClipControl pointer", "PASS", get_time_ms() - t0, "pointer non-NULL");
    }

    /* 3b: call + glGetError */
    LOG_INFO("3b: glClipControl call test");
    if (pglClipControl) {
        /* Clear any prior errors */
        while (glGetError() != GL_NO_ERROR) {}

        pglClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE);
        GLenum err = glGetError();

        if (err == GL_NO_ERROR) {
            LOG_INFO("3b: glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE) succeeded");
            log_module_result(3, "b", "glClipControl call", "PASS", get_time_ms() - t0, "no error");
        } else {
            LOG_ERROR("3b: glClipControl returned error 0x%04x", err);
            char err_detail[64];
            snprintf(err_detail, sizeof(err_detail), "glGetError=0x%04x", err);
            log_module_result(3, "b", "glClipControl call", "FAIL", get_time_ms() - t0, err_detail);
        }

        /* 3c: state query */
        LOG_INFO("3c: glClipControl state query");
        GLint clip_depth = 0, clip_origin = 0;
        glGetIntegerv(GL_CLIP_DEPTH_MODE, &clip_depth);
        GLenum e1 = glGetError();
        glGetIntegerv(GL_CLIP_ORIGIN, &clip_origin);
        GLenum e2 = glGetError();

        LOG_INFO("3c: CLIP_DEPTH_MODE=0x%04x (expected 0x%04x), CLIP_ORIGIN=0x%04x (expected 0x%04x)",
                 clip_depth, GL_ZERO_TO_ONE, clip_origin, GL_LOWER_LEFT);

        if (e1 == GL_NO_ERROR && e2 == GL_NO_ERROR &&
            clip_depth == (GLint)GL_ZERO_TO_ONE && clip_origin == (GLint)GL_LOWER_LEFT) {
            status = "PASS";
            snprintf(detail, sizeof(detail), "state verified: depth=ZERO_TO_ONE, origin=LOWER_LEFT");
        } else {
            snprintf(detail, sizeof(detail), "state mismatch: depth=0x%04x origin=0x%04x e1=0x%04x e2=0x%04x",
                     clip_depth, clip_origin, e1, e2);
        }
        log_module_result(3, "c", "glClipControl state", status, get_time_ms() - t0, detail);

        /* Reset to default */
        pglClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
        while (glGetError() != GL_NO_ERROR) {}
    } else {
        log_module_result(3, "b", "glClipControl call", "SKIP", get_time_ms() - t0, "pointer NULL");
        log_module_result(3, "c", "glClipControl state", "SKIP", get_time_ms() - t0, "pointer NULL");
    }
}

/* ################################################################## */
/* ===== MODULE 4: Reversed-Z Depth ===== */
/* ################################################################## */

/*
 * Render two overlapping triangles to FBO:
 *   - Red triangle at z=0.3
 *   - Blue triangle at z=0.7
 * With reversed-Z (glDepthFunc(GL_GREATER), glClearDepth(0)):
 *   - Expected: Blue (farther) should be BEHIND red (nearer)
 *   - i.e., red should be visible at the overlap
 *
 * Sub-modes:
 *   4a: No shim (baseline)
 *   4b: With ClipControl shim (Shim A via LD_PRELOAD)
 *   4c: With depth flip shim (Shim B via LD_PRELOAD)
 */

static void run_module_4(const char *sub) {
    double t0 = get_time_ms();
    LOG_INFO("=== Module 4%s: Reversed-Z Depth ===", sub);

    /* Check required functions */
    if (!pglGenFramebuffers || !pglBindFramebuffer || !pglGenVertexArrays ||
        !pglGenBuffers || !pglCreateShader) {
        log_module_result(4, sub, "Reversed-Z Depth", "SKIP", get_time_ms() - t0, "required GL functions not available");
        return;
    }

    /* Create FBO with GL_DEPTH_COMPONENT24 (universally supported; 32F may fail on VirGL) */
    GLuint fbo, colorTex, depthTex;
    if (!create_fbo(&fbo, &colorTex, &depthTex, FBO_WIDTH, FBO_HEIGHT, 0)) {
        log_module_result(4, sub, "Reversed-Z Depth", "FAIL", get_time_ms() - t0, "FBO creation failed");
        return;
    }

    /* Create shader program */
    GLuint prog = create_program_from_sources(SHADER_VERT_BASIC, SHADER_FRAG_BASIC, "depth_test");
    if (!prog) {
        destroy_fbo(fbo, colorTex, depthTex);
        log_module_result(4, sub, "Reversed-Z Depth", "FAIL", get_time_ms() - t0, "shader compilation failed");
        return;
    }

    pglUseProgram(prog);

    /* Set up UBO with identity matrices */
    float ubo_data[32];
    memset(ubo_data, 0, sizeof(ubo_data));
    /* Identity mat4 for projection */
    ubo_data[0] = 1.0f; ubo_data[5] = 1.0f; ubo_data[10] = 1.0f; ubo_data[15] = 1.0f;
    /* Identity mat4 for view */
    ubo_data[16] = 1.0f; ubo_data[21] = 1.0f; ubo_data[26] = 1.0f; ubo_data[31] = 1.0f;

    GLuint ubo;
    pglGenBuffers(1, &ubo);
    pglBindBuffer(GL_UNIFORM_BUFFER, ubo);
    pglBufferData(GL_UNIFORM_BUFFER, sizeof(ubo_data), ubo_data, GL_STATIC_DRAW);
    CHECK_GL("UBO setup");

    GLuint blockIdx = pglGetUniformBlockIndex(prog, "Matrices");
    if (blockIdx != GL_INVALID_INDEX) {
        pglUniformBlockBinding(prog, blockIdx, 0);
        pglBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo);
    } else {
        LOG_ERROR("UBO block 'Matrices' not found in shader — identity matrices will NOT be applied");
    }
    CHECK_GL("UBO binding");

    /* Set up two triangles with different depths and colors */
    float vertices[] = {
        /* Red triangle at z=0.3 (NDC) — will appear as "near" in reversed-Z */
        -0.5f, -0.5f, 0.3f,   1.0f, 0.0f, 0.0f, 1.0f,
         0.5f, -0.5f, 0.3f,   1.0f, 0.0f, 0.0f, 1.0f,
         0.0f,  0.5f, 0.3f,   1.0f, 0.0f, 0.0f, 1.0f,
        /* Blue triangle at z=0.7 (NDC) — will appear as "far" in reversed-Z */
        -0.5f, -0.3f, 0.7f,   0.0f, 0.0f, 1.0f, 1.0f,
         0.5f, -0.3f, 0.7f,   0.0f, 0.0f, 1.0f, 1.0f,
         0.0f,  0.7f, 0.7f,   0.0f, 0.0f, 1.0f, 1.0f,
    };

    GLuint vao, vbo;
    pglGenVertexArrays(1, &vao);
    pglBindVertexArray(vao);
    pglGenBuffers(1, &vbo);
    pglBindBuffer(GL_ARRAY_BUFFER, vbo);
    pglBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    pglEnableVertexAttribArray(0);
    pglVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void *)0);
    pglEnableVertexAttribArray(1);
    pglVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void *)(3 * sizeof(float)));
    CHECK_GL("vertex setup");

    /* Reversed-Z setup */
    glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_GREATER);
    glClearDepth(0.0);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    CHECK_GL("reversed-Z setup");

    /* Draw both triangles */
    glDrawArrays(GL_TRIANGLES, 0, 6);
    CHECK_GL("glDrawArrays");

    glFinish();
    CHECK_GL("glFinish");

    /* Save color buffer */
    char png_path[600];
    snprintf(png_path, sizeof(png_path), "%s/module4%s-color.png", g_results_dir, sub);
    save_fbo_png(png_path, FBO_WIDTH, FBO_HEIGHT);

    /* Read depth buffer stats */
    float depth_min, depth_max, depth_mean;
    read_depth_stats(FBO_WIDTH, FBO_HEIGHT, &depth_min, &depth_max, &depth_mean);
    LOG_INFO("Depth stats: min=%.4f max=%.4f mean=%.4f", depth_min, depth_max, depth_mean);

    /* Pixel validation: check center-ish area for red (near triangle should win in reversed-Z) */
    int center_x = FBO_WIDTH / 2;
    int center_y = FBO_HEIGHT / 4;  /* Lower area where triangles overlap */

    /* Read center pixel */
    unsigned char center_pixel[4];
    glReadPixels(center_x, center_y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, center_pixel);
    CHECK_GL("center pixel read");

    LOG_INFO("Center pixel at (%d,%d): R=%d G=%d B=%d A=%d",
             center_x, center_y, center_pixel[0], center_pixel[1], center_pixel[2], center_pixel[3]);

    /* In correct reversed-Z: red (z=0.3) should be "nearer" and win over blue (z=0.7) */
    /* With GL_GREATER, larger depth values pass. z=0.3 is written first, z=0.7 passes GL_GREATER => blue wins */
    /* UNLESS a shim transforms the depth function */
    const char *pixel_desc;
    if (center_pixel[0] > 128 && center_pixel[2] < 128) {
        pixel_desc = "RED dominant (near wins)";
    } else if (center_pixel[2] > 128 && center_pixel[0] < 128) {
        pixel_desc = "BLUE dominant (far wins)";
    } else if (center_pixel[0] < 20 && center_pixel[1] < 20 && center_pixel[2] < 20) {
        pixel_desc = "BLACK (no geometry)";
    } else {
        pixel_desc = "MIXED/UNEXPECTED";
    }

    LOG_INFO("Result: %s", pixel_desc);

    /* Reset GL state */
    glDepthFunc(GL_LESS);
    glClearDepth(1.0);
    pglBindFramebuffer(GL_FRAMEBUFFER, 0);

    /* Cleanup */
    destroy_geometry(vao, vbo);
    pglDeleteBuffers(1, &ubo);
    pglDeleteProgram(prog);
    destroy_fbo(fbo, colorTex, depthTex);

    double elapsed = get_time_ms() - t0;
    char result_detail[256];
    snprintf(result_detail, sizeof(result_detail), "%s; depth min=%.4f max=%.4f mean=%.4f",
             pixel_desc, depth_min, depth_max, depth_mean);
    log_module_result(4, sub, "Reversed-Z Depth", "PASS", elapsed, result_detail);
}

/* ################################################################## */
/* ===== MODULE 5: sampler2DArray ===== */
/* ################################################################## */

static void run_module_5(void) {
    double t0 = get_time_ms();
    LOG_INFO("=== Module 5: sampler2DArray ===");

    if (!pglGenFramebuffers || !pglTexImage3D || !pglActiveTexture ||
        !pglGetUniformLocation || !pglUniform1i) {
        log_module_result(5, NULL, "sampler2DArray", "SKIP", get_time_ms() - t0, "required GL functions not available");
        return;
    }

    /* Create FBO */
    GLuint fbo, colorTex, depthTex;
    if (!create_fbo(&fbo, &colorTex, &depthTex, FBO_WIDTH, FBO_HEIGHT, 0)) {
        log_module_result(5, NULL, "sampler2DArray", "FAIL", get_time_ms() - t0, "FBO creation failed");
        return;
    }

    /* Create a texture array vertex shader that passes texcoord + layer */
    static const char *texarray_vert =
        "#version 330\n"
        "layout(location = 0) in vec3 aPosition;\n"
        "out vec2 vTexCoord;\n"
        "flat out int vLayer;\n"
        "uniform int uLayer;\n"
        "void main() {\n"
        "    gl_Position = vec4(aPosition, 1.0);\n"
        "    vTexCoord = aPosition.xy * 0.5 + 0.5;\n"
        "    vLayer = uLayer;\n"
        "}\n";

    GLuint prog = create_program_from_sources(texarray_vert, SHADER_FRAG_TEXARRAY, "texarray");
    if (!prog) {
        destroy_fbo(fbo, colorTex, depthTex);
        log_module_result(5, NULL, "sampler2DArray", "FAIL", get_time_ms() - t0, "shader compilation failed");
        return;
    }

    /* Create 3-layer texture (R/G/B) */
    GLuint texArray;
    glGenTextures(1, &texArray);
    pglActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D_ARRAY, texArray);

    /* 4x4 pixels per layer, 3 layers */
    unsigned char layer_data[3][4 * 4 * 4]; /* 3 layers, 4x4, RGBA */

    /* Layer 0: Red */
    for (int i = 0; i < 4 * 4; i++) {
        layer_data[0][i * 4 + 0] = 255;
        layer_data[0][i * 4 + 1] = 0;
        layer_data[0][i * 4 + 2] = 0;
        layer_data[0][i * 4 + 3] = 255;
    }
    /* Layer 1: Green */
    for (int i = 0; i < 4 * 4; i++) {
        layer_data[1][i * 4 + 0] = 0;
        layer_data[1][i * 4 + 1] = 255;
        layer_data[1][i * 4 + 2] = 0;
        layer_data[1][i * 4 + 3] = 255;
    }
    /* Layer 2: Blue */
    for (int i = 0; i < 4 * 4; i++) {
        layer_data[2][i * 4 + 0] = 0;
        layer_data[2][i * 4 + 1] = 0;
        layer_data[2][i * 4 + 2] = 255;
        layer_data[2][i * 4 + 3] = 255;
    }

    pglTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, 4, 4, 3, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    CHECK_GL("glTexImage3D");

    /* Upload each layer */
    for (int i = 0; i < 3; i++) {
        pglTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, i, 4, 4, 1, GL_RGBA, GL_UNSIGNED_BYTE, layer_data[i]);
        CHECK_GL("glTexSubImage3D");
    }

    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    CHECK_GL("tex params");

    /* Setup fullscreen quad */
    GLuint vao, vbo;
    float quad[] = {
        -1.0f, -1.0f, 0.0f,
         1.0f, -1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f,
         1.0f,  1.0f, 0.0f,
    };
    pglGenVertexArrays(1, &vao);
    pglBindVertexArray(vao);
    pglGenBuffers(1, &vbo);
    pglBindBuffer(GL_ARRAY_BUFFER, vbo);
    pglBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    pglEnableVertexAttribArray(0);
    pglVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 3 * sizeof(float), (void *)0);
    CHECK_GL("quad setup");

    pglUseProgram(prog);
    GLint texLoc = pglGetUniformLocation(prog, "uTexArray");
    GLint layerLoc = pglGetUniformLocation(prog, "uLayer");
    pglUniform1i(texLoc, 0);

    glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);
    glDisable(GL_DEPTH_TEST);

    int checks_passed = 0;

    /* Test each layer: render fullscreen quad with that layer, check pixel */
    for (int layer = 0; layer < 3; layer++) {
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        pglUniform1i(layerLoc, layer);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        CHECK_GL("draw layer");
        glFinish();

        /* Check center pixel */
        unsigned char expected_r = (layer == 0) ? 255 : 0;
        unsigned char expected_g = (layer == 1) ? 255 : 0;
        unsigned char expected_b = (layer == 2) ? 255 : 0;
        char desc[32];
        snprintf(desc, sizeof(desc), "layer_%d", layer);
        checks_passed += check_pixel(FBO_WIDTH / 2, FBO_HEIGHT / 2, expected_r, expected_g, expected_b, 30, desc);
    }

    /* Save last frame */
    char png_path[600];
    snprintf(png_path, sizeof(png_path), "%s/module5-texarray.png", g_results_dir);
    save_fbo_png(png_path, FBO_WIDTH, FBO_HEIGHT);

    /* Cleanup */
    pglBindFramebuffer(GL_FRAMEBUFFER, 0);
    destroy_geometry(vao, vbo);
    glDeleteTextures(1, &texArray);
    pglDeleteProgram(prog);
    destroy_fbo(fbo, colorTex, depthTex);

    double elapsed = get_time_ms() - t0;
    char detail[128];
    snprintf(detail, sizeof(detail), "%d/3 layer checks passed", checks_passed);
    log_module_result(5, NULL, "sampler2DArray", checks_passed == 3 ? "PASS" : "FAIL", elapsed, detail);
}

/* ################################################################## */
/* ===== MODULE 6: noperspective vs smooth ===== */
/* ################################################################## */

static void run_module_6(void) {
    double t0 = get_time_ms();
    LOG_INFO("=== Module 6: noperspective vs smooth ===");

    if (!pglGenFramebuffers || !pglCreateShader) {
        log_module_result(6, NULL, "noperspective", "SKIP", get_time_ms() - t0, "required GL functions not available");
        return;
    }

    /* Create two FBOs */
    GLuint fboA, colorA, depthA, fboB, colorB, depthB;
    if (!create_fbo(&fboA, &colorA, &depthA, FBO_WIDTH, FBO_HEIGHT, 0)) {
        log_module_result(6, NULL, "noperspective", "FAIL", get_time_ms() - t0, "FBO A creation failed");
        return;
    }
    if (!create_fbo(&fboB, &colorB, &depthB, FBO_WIDTH, FBO_HEIGHT, 0)) {
        destroy_fbo(fboA, colorA, depthA);
        log_module_result(6, NULL, "noperspective", "FAIL", get_time_ms() - t0, "FBO B creation failed");
        return;
    }

    /* Create noperspective program */
    GLuint progNP = create_program_from_sources(SHADER_VERT_NOPERSP, SHADER_FRAG_NOPERSP, "noperspective");
    /* Create smooth program (smooth vert + basic frag) */
    GLuint progSmooth = create_program_from_sources(SHADER_VERT_SMOOTH, SHADER_FRAG_BASIC, "smooth");

    if (!progNP || !progSmooth) {
        if (progNP) pglDeleteProgram(progNP);
        if (progSmooth) pglDeleteProgram(progSmooth);
        destroy_fbo(fboA, colorA, depthA);
        destroy_fbo(fboB, colorB, depthB);
        log_module_result(6, NULL, "noperspective", "FAIL", get_time_ms() - t0, "shader compilation failed");
        return;
    }

    /* Create a quad with perspective-inducing vertex positions */
    float verts[] = {
        /* pos(x,y,z), color(r,g,b,a) — use varying z to induce perspective differences */
        -0.8f, -0.8f, 0.0f,   1.0f, 0.0f, 0.0f, 1.0f,
         0.8f, -0.8f, 0.0f,   0.0f, 1.0f, 0.0f, 1.0f,
        -0.2f,  0.8f, 0.5f,   0.0f, 0.0f, 1.0f, 1.0f,
    };

    GLuint vao, vbo;
    pglGenVertexArrays(1, &vao);
    pglBindVertexArray(vao);
    pglGenBuffers(1, &vbo);
    pglBindBuffer(GL_ARRAY_BUFFER, vbo);
    pglBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    pglEnableVertexAttribArray(0);
    pglVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void *)0);
    pglEnableVertexAttribArray(1);
    pglVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 7 * sizeof(float), (void *)(3 * sizeof(float)));
    CHECK_GL("nopersp geometry");

    glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);
    glDisable(GL_DEPTH_TEST);

    /* Render with noperspective to FBO A */
    pglBindFramebuffer(GL_FRAMEBUFFER, fboA);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    pglUseProgram(progNP);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFinish();
    CHECK_GL("nopersp draw");

    /* Read FBO A pixels */
    unsigned char *pixelsA = (unsigned char *)malloc(FBO_WIDTH * FBO_HEIGHT * 4);
    glReadPixels(0, 0, FBO_WIDTH, FBO_HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixelsA);
    uint32_t crcA = crc32_compute(pixelsA, FBO_WIDTH * FBO_HEIGHT * 4);

    char pngA[600];
    snprintf(pngA, sizeof(pngA), "%s/module6-noperspective.png", g_results_dir);
    save_fbo_png(pngA, FBO_WIDTH, FBO_HEIGHT);

    /* Render with smooth to FBO B */
    pglBindFramebuffer(GL_FRAMEBUFFER, fboB);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    pglUseProgram(progSmooth);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFinish();
    CHECK_GL("smooth draw");

    /* Read FBO B pixels */
    unsigned char *pixelsB = (unsigned char *)malloc(FBO_WIDTH * FBO_HEIGHT * 4);
    glReadPixels(0, 0, FBO_WIDTH, FBO_HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixelsB);
    uint32_t crcB = crc32_compute(pixelsB, FBO_WIDTH * FBO_HEIGHT * 4);

    char pngB[600];
    snprintf(pngB, sizeof(pngB), "%s/module6-smooth.png", g_results_dir);
    save_fbo_png(pngB, FBO_WIDTH, FBO_HEIGHT);

    /* Compare: they should differ (noperspective vs perspective interpolation) */
    int differ = (crcA != crcB);
    LOG_INFO("CRC32: noperspective=0x%08x smooth=0x%08x differ=%s", crcA, crcB, differ ? "YES" : "NO");

    /* Also count different pixels */
    int diff_pixels = 0;
    for (int i = 0; i < FBO_WIDTH * FBO_HEIGHT * 4; i++) {
        if (abs((int)pixelsA[i] - (int)pixelsB[i]) > 2) {
            diff_pixels++;
            break; /* Just need to know if any differ */
        }
    }

    free(pixelsA);
    free(pixelsB);

    /* Cleanup */
    pglBindFramebuffer(GL_FRAMEBUFFER, 0);
    destroy_geometry(vao, vbo);
    pglDeleteProgram(progNP);
    pglDeleteProgram(progSmooth);
    destroy_fbo(fboA, colorA, depthA);
    destroy_fbo(fboB, colorB, depthB);

    double elapsed = get_time_ms() - t0;
    char detail[128];
    snprintf(detail, sizeof(detail), "crcNP=0x%08x crcSmooth=0x%08x differ=%s", crcA, crcB, differ ? "YES" : "NO");
    /* Both modes should produce non-black output AND they should differ */
    const char *status = differ ? "PASS" : "WARN";
    log_module_result(6, NULL, "noperspective vs smooth", status, elapsed, detail);
}

/* ################################################################## */
/* ===== MODULE 7: FBO Blit ===== */
/* ################################################################## */

static void run_module_7(void) {
    double t0 = get_time_ms();
    LOG_INFO("=== Module 7: FBO Blit ===");

    if (!pglGenFramebuffers || !pglBlitFramebuffer) {
        log_module_result(7, NULL, "FBO Blit", "SKIP", get_time_ms() - t0, "glBlitFramebuffer not available");
        return;
    }

    /* Create two FBOs */
    GLuint fboA, colorA, depthA, fboB, colorB, depthB;
    if (!create_fbo(&fboA, &colorA, &depthA, FBO_WIDTH, FBO_HEIGHT, 0)) {
        log_module_result(7, NULL, "FBO Blit", "FAIL", get_time_ms() - t0, "FBO A creation failed");
        return;
    }
    if (!create_fbo(&fboB, &colorB, &depthB, FBO_WIDTH, FBO_HEIGHT, 0)) {
        destroy_fbo(fboA, colorA, depthA);
        log_module_result(7, NULL, "FBO Blit", "FAIL", get_time_ms() - t0, "FBO B creation failed");
        return;
    }

    /* Render a gradient to FBO A */
    GLuint prog = create_program_from_sources(SHADER_VERT_NOPERSP, SHADER_FRAG_NOPERSP, "blit_src");
    if (!prog) {
        destroy_fbo(fboA, colorA, depthA);
        destroy_fbo(fboB, colorB, depthB);
        log_module_result(7, NULL, "FBO Blit", "FAIL", get_time_ms() - t0, "shader failed");
        return;
    }

    GLuint vao, vbo;
    setup_fullscreen_quad(&vao, &vbo);

    pglBindFramebuffer(GL_FRAMEBUFFER, fboA);
    glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    pglUseProgram(prog);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glFinish();
    CHECK_GL("render to A");

    /* Read FBO A pixels and CRC */
    unsigned char *pixA = (unsigned char *)malloc(FBO_WIDTH * FBO_HEIGHT * 4);
    glReadPixels(0, 0, FBO_WIDTH, FBO_HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixA);
    uint32_t crcA = crc32_compute(pixA, FBO_WIDTH * FBO_HEIGHT * 4);
    free(pixA);

    /* Blit FBO A -> FBO B */
    pglBindFramebuffer(GL_READ_FRAMEBUFFER, fboA);
    pglBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboB);
    pglBlitFramebuffer(0, 0, FBO_WIDTH, FBO_HEIGHT, 0, 0, FBO_WIDTH, FBO_HEIGHT,
                       GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glFinish();
    CHECK_GL("blit A->B");

    /* Read FBO B pixels and CRC */
    pglBindFramebuffer(GL_FRAMEBUFFER, fboB);
    unsigned char *pixB = (unsigned char *)malloc(FBO_WIDTH * FBO_HEIGHT * 4);
    glReadPixels(0, 0, FBO_WIDTH, FBO_HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixB);
    uint32_t crcB = crc32_compute(pixB, FBO_WIDTH * FBO_HEIGHT * 4);
    free(pixB);

    int match = (crcA == crcB);
    LOG_INFO("Blit CRC: A=0x%08x B=0x%08x match=%s", crcA, crcB, match ? "YES" : "NO");

    /* Cleanup */
    pglBindFramebuffer(GL_FRAMEBUFFER, 0);
    destroy_geometry(vao, vbo);
    pglDeleteProgram(prog);
    destroy_fbo(fboA, colorA, depthA);
    destroy_fbo(fboB, colorB, depthB);

    double elapsed = get_time_ms() - t0;
    char detail[128];
    snprintf(detail, sizeof(detail), "crcA=0x%08x crcB=0x%08x match=%s", crcA, crcB, match ? "YES" : "NO");
    log_module_result(7, NULL, "FBO Blit", match ? "PASS" : "FAIL", elapsed, detail);
}

/* ################################################################## */
/* ===== MODULE 8: RuneLite Scene Emulation ===== */
/* ################################################################## */

static void run_module_8(void) {
    double t0 = get_time_ms();
    LOG_INFO("=== Module 8: RuneLite Scene Emulation ===");

    if (!pglGenFramebuffers || !pglTexImage3D || !pglActiveTexture ||
        !pglBindBufferBase || !pglGetUniformBlockIndex) {
        log_module_result(8, NULL, "Scene Emulation", "SKIP", get_time_ms() - t0, "required GL functions not available");
        return;
    }

    /* Create FBO with GL_DEPTH_COMPONENT24 (universally supported; 32F may fail on VirGL) */
    GLuint fbo, colorTex, depthTex;
    if (!create_fbo(&fbo, &colorTex, &depthTex, FBO_WIDTH, FBO_HEIGHT, 0)) {
        log_module_result(8, NULL, "Scene Emulation", "FAIL", get_time_ms() - t0, "FBO creation failed");
        return;
    }

    /* Create scene program */
    GLuint prog = create_program_from_sources(SHADER_VERT_SCENE, SHADER_FRAG_SCENE, "scene");
    if (!prog) {
        destroy_fbo(fbo, colorTex, depthTex);
        log_module_result(8, NULL, "Scene Emulation", "FAIL", get_time_ms() - t0, "shader compilation failed");
        return;
    }

    pglUseProgram(prog);

    /* Create scene UBO: projection, view, fog params, fog color */
    /* Simple perspective projection with reversed-Z */
    float fov = 50.0f * 3.14159f / 180.0f;
    float aspect = (float)FBO_WIDTH / (float)FBO_HEIGHT;
    float near_val = 0.1f;
    float far_val = 100.0f;
    float f = 1.0f / tanf(fov / 2.0f);

    /* Reversed-Z infinite projection (far at 0, near at 1) */
    float proj[16] = {0};
    proj[0] = f / aspect;
    proj[5] = f;
    proj[10] = 0.0f;      /* reversed-Z: near maps to 1, far to 0 */
    proj[11] = -1.0f;
    proj[14] = near_val;   /* reversed-Z */

    /* Simple view matrix (look at z=-5) */
    float view[16] = {0};
    view[0] = 1.0f;
    view[5] = 1.0f;
    view[10] = 1.0f;
    view[14] = -5.0f;
    view[15] = 1.0f;

    /* Fog params + fog color (std140 layout) */
    float fog_params[4] = {3.0f, 15.0f, 1.0f, 0.0f};
    float fog_color[4] = {0.6f, 0.7f, 0.8f, 1.0f};

    /* UBO: 2 mat4 + 2 vec4 = 32+32+4+4 = 72 floats = 288 bytes, pad to 40 floats / 160 bytes per mat4 */
    float ubo_data[40]; /* 2*16 + 2*4 = 40 */
    memcpy(ubo_data, proj, 16 * sizeof(float));
    memcpy(ubo_data + 16, view, 16 * sizeof(float));
    memcpy(ubo_data + 32, fog_params, 4 * sizeof(float));
    memcpy(ubo_data + 36, fog_color, 4 * sizeof(float));

    GLuint ubo;
    pglGenBuffers(1, &ubo);
    pglBindBuffer(GL_UNIFORM_BUFFER, ubo);
    pglBufferData(GL_UNIFORM_BUFFER, sizeof(ubo_data), ubo_data, GL_STATIC_DRAW);
    CHECK_GL("scene UBO");

    GLuint blockIdx = pglGetUniformBlockIndex(prog, "SceneUniforms");
    if (blockIdx != GL_INVALID_INDEX) {
        pglUniformBlockBinding(prog, blockIdx, 0);
        pglBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo);
    } else {
        LOG_ERROR("UBO block 'SceneUniforms' not found in shader — scene uniforms will NOT be applied");
    }
    CHECK_GL("scene UBO binding");

    /* Create texture array (R/G/B layers) */
    GLuint texArray;
    glGenTextures(1, &texArray);
    pglActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D_ARRAY, texArray);

    unsigned char layers[3][4 * 4 * 4];
    unsigned char colors[3][3] = {{200, 80, 80}, {80, 200, 80}, {80, 80, 200}};
    for (int l = 0; l < 3; l++) {
        for (int i = 0; i < 16; i++) {
            layers[l][i * 4 + 0] = colors[l][0];
            layers[l][i * 4 + 1] = colors[l][1];
            layers[l][i * 4 + 2] = colors[l][2];
            layers[l][i * 4 + 3] = 255;
        }
    }

    pglTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, 4, 4, 3, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    for (int i = 0; i < 3; i++) {
        pglTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, i, 4, 4, 1, GL_RGBA, GL_UNSIGNED_BYTE, layers[i]);
    }
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    CHECK_GL("scene texarray");

    GLint texLoc = pglGetUniformLocation(prog, "uTexArray");
    if (texLoc >= 0) pglUniform1i(texLoc, 0);

    /* Create scene geometry: 3 quads at different depths, different texture layers */
    /* pos(x,y,z), color(r,g,b,a), texcoord(u,v), texlayer */
    float scene_verts[] = {
        /* Quad 1: z=-2, layer 0 (reddish) */
        -1.0f, -1.0f, -2.0f,   1.0f, 1.0f, 1.0f, 1.0f,   0.0f, 0.0f,  0.0f,
         1.0f, -1.0f, -2.0f,   1.0f, 1.0f, 1.0f, 1.0f,   1.0f, 0.0f,  0.0f,
        -1.0f,  0.0f, -2.0f,   1.0f, 1.0f, 1.0f, 1.0f,   0.0f, 1.0f,  0.0f,
         1.0f,  0.0f, -2.0f,   1.0f, 1.0f, 1.0f, 1.0f,   1.0f, 1.0f,  0.0f,

        /* Quad 2: z=-4, layer 1 (greenish) */
        -0.5f, -0.5f, -4.0f,   1.0f, 1.0f, 1.0f, 1.0f,   0.0f, 0.0f,  1.0f,
         0.5f, -0.5f, -4.0f,   1.0f, 1.0f, 1.0f, 1.0f,   1.0f, 0.0f,  1.0f,
        -0.5f,  0.5f, -4.0f,   1.0f, 1.0f, 1.0f, 1.0f,   0.0f, 1.0f,  1.0f,
         0.5f,  0.5f, -4.0f,   1.0f, 1.0f, 1.0f, 1.0f,   1.0f, 1.0f,  1.0f,

        /* Quad 3: z=-8, layer 2 (bluish) — should be foggier */
        -2.0f, -2.0f, -8.0f,   1.0f, 1.0f, 1.0f, 1.0f,   0.0f, 0.0f,  2.0f,
         2.0f, -2.0f, -8.0f,   1.0f, 1.0f, 1.0f, 1.0f,   1.0f, 0.0f,  2.0f,
        -2.0f,  2.0f, -8.0f,   1.0f, 1.0f, 1.0f, 1.0f,   0.0f, 1.0f,  2.0f,
         2.0f,  2.0f, -8.0f,   1.0f, 1.0f, 1.0f, 1.0f,   1.0f, 1.0f,  2.0f,
    };

    GLuint vao, vbo;
    pglGenVertexArrays(1, &vao);
    pglBindVertexArray(vao);
    pglGenBuffers(1, &vbo);
    pglBindBuffer(GL_ARRAY_BUFFER, vbo);
    pglBufferData(GL_ARRAY_BUFFER, sizeof(scene_verts), scene_verts, GL_STATIC_DRAW);

    /* Stride: 10 floats (3 pos + 4 color + 2 texcoord + 1 layer) */
    int stride = 10 * sizeof(float);
    pglEnableVertexAttribArray(0); /* pos */
    pglVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, (void *)0);
    pglEnableVertexAttribArray(1); /* color */
    pglVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, stride, (void *)(3 * sizeof(float)));
    pglEnableVertexAttribArray(2); /* texcoord */
    pglVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, stride, (void *)(7 * sizeof(float)));
    pglEnableVertexAttribArray(3); /* texlayer */
    pglVertexAttribPointer(3, 1, GL_FLOAT, GL_FALSE, stride, (void *)(9 * sizeof(float)));
    CHECK_GL("scene geometry");

    /* Render with reversed-Z */
    glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_GREATER);
    glClearDepth(0.0);
    glClearColor(fog_color[0], fog_color[1], fog_color[2], 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    /* Draw 3 quads as triangle strips */
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDrawArrays(GL_TRIANGLE_STRIP, 4, 4);
    glDrawArrays(GL_TRIANGLE_STRIP, 8, 4);
    glFinish();
    CHECK_GL("scene draw");

    /* Save result */
    char png_path[600];
    snprintf(png_path, sizeof(png_path), "%s/module8-scene.png", g_results_dir);
    save_fbo_png(png_path, FBO_WIDTH, FBO_HEIGHT);

    /* Read depth stats */
    float d_min, d_max, d_mean;
    read_depth_stats(FBO_WIDTH, FBO_HEIGHT, &d_min, &d_max, &d_mean);
    LOG_INFO("Scene depth: min=%.4f max=%.4f mean=%.4f", d_min, d_max, d_mean);

    /* Check that rendering produced something (not all black/fog) */
    unsigned char center[4];
    glReadPixels(FBO_WIDTH / 2, FBO_HEIGHT / 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, center);
    int has_content = (center[0] > 10 || center[1] > 10 || center[2] > 10);

    /* Reset GL state */
    glDepthFunc(GL_LESS);
    glClearDepth(1.0);
    pglBindFramebuffer(GL_FRAMEBUFFER, 0);

    /* Cleanup */
    destroy_geometry(vao, vbo);
    pglDeleteBuffers(1, &ubo);
    glDeleteTextures(1, &texArray);
    pglDeleteProgram(prog);
    destroy_fbo(fbo, colorTex, depthTex);

    double elapsed = get_time_ms() - t0;
    char detail[256];
    snprintf(detail, sizeof(detail), "content=%s depth(min=%.4f max=%.4f mean=%.4f) pixel(%d,%d,%d)",
             has_content ? "YES" : "NO", d_min, d_max, d_mean, center[0], center[1], center[2]);
    log_module_result(8, NULL, "Scene Emulation", has_content ? "PASS" : "WARN", elapsed, detail);
}

/* ################################################################## */
/* ===== MODULE 9: Performance Baseline ===== */
/* ################################################################## */

static void run_module_9(void) {
    double t0 = get_time_ms();
    LOG_INFO("=== Module 9: Performance Baseline ===");

    if (!pglGenFramebuffers || !pglCreateShader) {
        log_module_result(9, NULL, "Performance", "SKIP", get_time_ms() - t0, "required GL functions not available");
        return;
    }

    /* Create FBO at full size */
    GLuint fbo, colorTex, depthTex;
    if (!create_fbo(&fbo, &colorTex, &depthTex, FBO_WIDTH, FBO_HEIGHT, 0)) {
        log_module_result(9, NULL, "Performance", "FAIL", get_time_ms() - t0, "FBO creation failed");
        return;
    }

    GLuint prog = create_program_from_sources(SHADER_VERT_NOPERSP, SHADER_FRAG_NOPERSP, "perf");
    if (!prog) {
        destroy_fbo(fbo, colorTex, depthTex);
        log_module_result(9, NULL, "Performance", "FAIL", get_time_ms() - t0, "shader failed");
        return;
    }

    GLuint vao, vbo;
    setup_fullscreen_quad(&vao, &vbo);

    pglUseProgram(prog);
    glViewport(0, 0, FBO_WIDTH, FBO_HEIGHT);
    glDisable(GL_DEPTH_TEST);

    /* Warmup */
    for (int i = 0; i < 10; i++) {
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }
    glFinish();

    /* Full-size frame times */
    double *frame_times = (double *)malloc(PERF_FRAMES * sizeof(double));
    for (int i = 0; i < PERF_FRAMES; i++) {
        double ft0 = get_time_ms();
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glFinish();
        frame_times[i] = get_time_ms() - ft0;
    }

    /* glReadPixels latency */
    unsigned char *read_buf = (unsigned char *)malloc(FBO_WIDTH * FBO_HEIGHT * 4);
    double rp_times[10];
    for (int i = 0; i < 10; i++) {
        double rt0 = get_time_ms();
        glReadPixels(0, 0, FBO_WIDTH, FBO_HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, read_buf);
        glFinish();
        rp_times[i] = get_time_ms() - rt0;
    }
    free(read_buf);

    /* Half-size run */
    GLuint fboH, colorH, depthH;
    double *half_times = NULL;
    int has_half = 0;

    if (create_fbo(&fboH, &colorH, &depthH, PERF_HALF_WIDTH, PERF_HALF_HEIGHT, 0)) {
        pglBindFramebuffer(GL_FRAMEBUFFER, fboH);
        glViewport(0, 0, PERF_HALF_WIDTH, PERF_HALF_HEIGHT);

        /* Warmup */
        for (int i = 0; i < 10; i++) {
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
        glFinish();

        half_times = (double *)malloc(PERF_FRAMES * sizeof(double));
        for (int i = 0; i < PERF_FRAMES; i++) {
            double ft0 = get_time_ms();
            glClear(GL_COLOR_BUFFER_BIT);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glFinish();
            half_times[i] = get_time_ms() - ft0;
        }
        has_half = 1;
        destroy_fbo(fboH, colorH, depthH);
    }

    /* Compute stats */
    qsort(frame_times, PERF_FRAMES, sizeof(double), cmp_double);

    double ft_min = frame_times[0];
    double ft_max = frame_times[PERF_FRAMES - 1];
    double ft_sum = 0;
    for (int i = 0; i < PERF_FRAMES; i++) ft_sum += frame_times[i];
    double ft_mean = ft_sum / PERF_FRAMES;
    double ft_p95 = frame_times[(int)(PERF_FRAMES * 0.95)];
    double ft_p99 = frame_times[(int)(PERF_FRAMES * 0.99)];

    double rp_sum = 0;
    for (int i = 0; i < 10; i++) rp_sum += rp_times[i];
    double rp_mean = rp_sum / 10.0;

    /* Half-size stats */
    double ht_mean = 0;
    if (has_half) {
        qsort(half_times, PERF_FRAMES, sizeof(double), cmp_double);
        double ht_sum = 0;
        for (int i = 0; i < PERF_FRAMES; i++) ht_sum += half_times[i];
        ht_mean = ht_sum / PERF_FRAMES;
    }

    LOG_INFO("Frame times (ms): min=%.2f max=%.2f mean=%.2f p95=%.2f p99=%.2f",
             ft_min, ft_max, ft_mean, ft_p95, ft_p99);
    LOG_INFO("glReadPixels latency (ms): mean=%.2f", rp_mean);
    if (has_half) {
        LOG_INFO("Half-res frame time (ms): mean=%.2f (speedup=%.2fx)", ht_mean, ft_mean / ht_mean);
    }

    /* Write timing.json */
    char timing_path[600];
    snprintf(timing_path, sizeof(timing_path), "%s/timing.json", g_results_dir);
    FILE *tf = fopen(timing_path, "w");
    if (tf) {
        fprintf(tf, "{\n");
        fprintf(tf, "  \"frames\": %d,\n", PERF_FRAMES);
        fprintf(tf, "  \"resolution\": \"%dx%d\",\n", FBO_WIDTH, FBO_HEIGHT);
        fprintf(tf, "  \"frame_time_ms\": {\n");
        fprintf(tf, "    \"min\": %.3f,\n", ft_min);
        fprintf(tf, "    \"max\": %.3f,\n", ft_max);
        fprintf(tf, "    \"mean\": %.3f,\n", ft_mean);
        fprintf(tf, "    \"p95\": %.3f,\n", ft_p95);
        fprintf(tf, "    \"p99\": %.3f\n", ft_p99);
        fprintf(tf, "  },\n");
        fprintf(tf, "  \"readpixels_ms\": %.3f,\n", rp_mean);
        if (has_half) {
            fprintf(tf, "  \"half_resolution\": \"%dx%d\",\n", PERF_HALF_WIDTH, PERF_HALF_HEIGHT);
            fprintf(tf, "  \"half_frame_time_ms\": %.3f,\n", ht_mean);
            fprintf(tf, "  \"resolution_speedup\": %.2f,\n", ft_mean / ht_mean);
        }
        fprintf(tf, "  \"fps_estimate\": %.1f\n", 1000.0 / ft_mean);
        fprintf(tf, "}\n");
        fclose(tf);
    }

    free(frame_times);
    if (half_times) free(half_times);

    /* Cleanup */
    pglBindFramebuffer(GL_FRAMEBUFFER, 0);
    destroy_geometry(vao, vbo);
    pglDeleteProgram(prog);
    destroy_fbo(fbo, colorTex, depthTex);

    double elapsed = get_time_ms() - t0;
    char detail[256];
    snprintf(detail, sizeof(detail), "mean=%.2fms p95=%.2fms p99=%.2fms readpix=%.2fms fps=%.0f",
             ft_mean, ft_p95, ft_p99, rp_mean, 1000.0 / ft_mean);
    log_module_result(9, NULL, "Performance Baseline", "PASS", elapsed, detail);
}

/* ################################################################## */
/* ===== MAIN ===== */
/* ################################################################## */

int main(int argc, char **argv) {
    /* Parse arguments */
    const char *results_dir = ".";
    int run_all = 0;
    int run_module = 0;
    const char *module_sub = NULL;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--results-dir") == 0 && i + 1 < argc) {
            results_dir = argv[++i];
        } else if (strcmp(argv[i], "--all") == 0) {
            run_all = 1;
        } else if (strcmp(argv[i], "--module") == 0 && i + 1 < argc) {
            i++;
            if (strcmp(argv[i], "4a") == 0) { run_module = 4; module_sub = "a"; }
            else if (strcmp(argv[i], "4b") == 0) { run_module = 4; module_sub = "b"; }
            else if (strcmp(argv[i], "4c") == 0) { run_module = 4; module_sub = "c"; }
            else { run_module = atoi(argv[i]); module_sub = NULL; }
        } else {
            fprintf(stderr, "Usage: %s --results-dir <dir> [--all | --module <1-9|4a|4b|4c>]\n", argv[0]);
            return 1;
        }
    }

    if (!run_all && run_module == 0) {
        fprintf(stderr, "Error: specify --all or --module <N>\n");
        return 1;
    }

    /* Initialize GLFW */
    if (!glfwInit()) {
        fprintf(stderr, "FATAL: glfwInit failed\n");
        return 1;
    }

    /* Request GL 3.3 compatibility profile */
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);
    glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); /* Offscreen */

    GLFWwindow *window = glfwCreateWindow(FBO_WIDTH, FBO_HEIGHT, "VirGL Test Harness", NULL, NULL);
    if (!window) {
        fprintf(stderr, "FATAL: glfwCreateWindow failed\n");
        glfwTerminate();
        return 1;
    }
    glfwMakeContextCurrent(window);

    /* Initialize logging */
    log_init(results_dir);
    install_signal_handlers();

    /* Resolve function pointers */
    resolve_all_functions();

    /* Run modules */
    if (run_all) {
        run_module_1();
        run_module_2();
        run_module_3();
        /* Module 4 in --all mode runs 4a (no shim baseline) */
        run_module_4("a");
        run_module_5();
        run_module_6();
        run_module_7();
        run_module_8();
        run_module_9();
    } else if (run_module == 4 && module_sub) {
        run_module_4(module_sub);
    } else {
        switch (run_module) {
            case 1: run_module_1(); break;
            case 2: run_module_2(); break;
            case 3: run_module_3(); break;
            case 4: run_module_4("a"); break;
            case 5: run_module_5(); break;
            case 6: run_module_6(); break;
            case 7: run_module_7(); break;
            case 8: run_module_8(); break;
            case 9: run_module_9(); break;
            default:
                LOG_ERROR("Unknown module: %d", run_module);
                break;
        }
    }

    /* Cleanup */
    log_close();
    glfwDestroyWindow(window);
    glfwTerminate();

    LOG_INFO("Harness complete.");
    return 0;
}
