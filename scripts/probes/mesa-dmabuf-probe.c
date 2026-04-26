/*
 * mesa-dmabuf-probe.c — Option A de-risker for the rlawt-on-Surface pivot.
 *
 * Goal: confirm Mesa's virtio_gpu / virpipe driver advertises
 *   EGL_MESA_image_dma_buf_export AND can return a usable cross-process FD.
 * If both pass, the producer-side rewrite (rlawt → Mesa EGL → DMA-BUF →
 * SCM_RIGHTS → consumer EGLImage import) is viable. If either fails,
 * Option A is dead and we fall back to D (park direct-surface, hunt FPS
 * elsewhere).
 *
 * Build (Termux on-device):
 *   clang mesa-dmabuf-probe.c -lEGL -lGLESv2 -lX11 -o mesa-dmabuf-probe
 *
 * Run (with virgl_test_server_android + X server already up):
 *   GALLIUM_DRIVER=virpipe \
 *   VTEST_SOCKET_NAME=$PREFIX/tmp/.virgl_test \
 *   DISPLAY=:0 \
 *   ./mesa-dmabuf-probe
 *
 * Exit status: 0 = export works, FD is a real cross-process handle.
 *              non-zero = some step failed; stderr explains which.
 */
#define _GNU_SOURCE
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <X11/Xlib.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef EGLBoolean (EGLAPIENTRYP PFNEGLEXPORTDMABUFIMAGEQUERYMESA)(
    EGLDisplay, EGLImageKHR, int*, int*, EGLuint64KHR*);
typedef EGLBoolean (EGLAPIENTRYP PFNEGLEXPORTDMABUFIMAGEMESA)(
    EGLDisplay, EGLImageKHR, int*, EGLint*, EGLint*);

#define DIE(fmt, ...) do { \
    fprintf(stderr, "FAIL: " fmt " (egl_err=0x%x)\n", ##__VA_ARGS__, eglGetError()); \
    return 1; \
} while (0)

int main(void)
{
    Display *xdpy = XOpenDisplay(NULL);
    if (!xdpy) {
        fprintf(stderr, "FAIL: XOpenDisplay(NULL) returned NULL — is DISPLAY=:0 set + X server up?\n");
        return 1;
    }
    fprintf(stderr, "OK: XOpenDisplay\n");

    EGLDisplay egl = eglGetDisplay((EGLNativeDisplayType)xdpy);
    if (egl == EGL_NO_DISPLAY) DIE("eglGetDisplay returned NO_DISPLAY");

    EGLint maj = 0, min = 0;
    if (!eglInitialize(egl, &maj, &min)) DIE("eglInitialize");
    fprintf(stderr, "OK: eglInitialize %d.%d vendor=%s\n",
            maj, min, eglQueryString(egl, EGL_VENDOR));

    const char *exts = eglQueryString(egl, EGL_EXTENSIONS);
    if (!exts) DIE("eglQueryString(EGL_EXTENSIONS)");
    fprintf(stderr, "Display extensions: %s\n", exts);

    int has_export = strstr(exts, "EGL_MESA_image_dma_buf_export") != NULL;
    int has_image_base = strstr(exts, "EGL_KHR_image_base") != NULL;
    int has_gl_tex2d = strstr(exts, "EGL_KHR_gl_texture_2D_image") != NULL;
    fprintf(stderr, "Extension flags: image_base=%d gl_texture_2D_image=%d MESA_dma_buf_export=%d\n",
            has_image_base, has_gl_tex2d, has_export);

    if (!has_export || !has_image_base || !has_gl_tex2d) {
        fprintf(stderr, "FAIL: required extensions missing (need image_base + gl_texture_2D_image + MESA_dma_buf_export)\n");
        return 2;
    }

    if (!eglBindAPI(EGL_OPENGL_ES_API)) DIE("eglBindAPI(GLES)");

    static const EGLint cfg_attr[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8,  EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE,
    };
    EGLConfig cfg;
    EGLint nc = 0;
    if (!eglChooseConfig(egl, cfg_attr, &cfg, 1, &nc) || nc < 1)
        DIE("eglChooseConfig found nc=%d", nc);

    static const EGLint pb_attr[] = { EGL_WIDTH, 256, EGL_HEIGHT, 256, EGL_NONE };
    EGLSurface pb = eglCreatePbufferSurface(egl, cfg, pb_attr);
    if (pb == EGL_NO_SURFACE) DIE("eglCreatePbufferSurface");

    static const EGLint ctx_attr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = eglCreateContext(egl, cfg, EGL_NO_CONTEXT, ctx_attr);
    if (ctx == EGL_NO_CONTEXT) DIE("eglCreateContext");

    if (!eglMakeCurrent(egl, pb, pb, ctx)) DIE("eglMakeCurrent");
    fprintf(stderr, "OK: GL renderer=%s version=%s\n",
            glGetString(GL_RENDERER), glGetString(GL_VERSION));

    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 256, 256, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glFinish();
    fprintf(stderr, "OK: 256x256 RGBA texture allocated tex=%u\n", tex);

    PFNEGLCREATEIMAGEKHRPROC fn_create =
        (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
    PFNEGLDESTROYIMAGEKHRPROC fn_destroy =
        (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
    PFNEGLEXPORTDMABUFIMAGEQUERYMESA fn_query =
        (PFNEGLEXPORTDMABUFIMAGEQUERYMESA)eglGetProcAddress("eglExportDMABUFImageQueryMESA");
    PFNEGLEXPORTDMABUFIMAGEMESA fn_export =
        (PFNEGLEXPORTDMABUFIMAGEMESA)eglGetProcAddress("eglExportDMABUFImageMESA");

    fprintf(stderr, "Procs: createImage=%p destroyImage=%p query=%p export=%p\n",
            (void*)fn_create, (void*)fn_destroy, (void*)fn_query, (void*)fn_export);
    if (!fn_create || !fn_query || !fn_export) {
        fprintf(stderr, "FAIL: required eglGetProcAddress lookups returned NULL\n");
        return 3;
    }

    EGLImageKHR img = fn_create(egl, ctx, EGL_GL_TEXTURE_2D_KHR,
                                (EGLClientBuffer)(uintptr_t)tex, NULL);
    if (img == EGL_NO_IMAGE_KHR) DIE("eglCreateImageKHR(GL_TEXTURE_2D)");
    fprintf(stderr, "OK: EGLImage created from texture\n");

    int fourcc = 0, num_planes = 0;
    EGLuint64KHR modifier = 0;
    if (!fn_query(egl, img, &fourcc, &num_planes, &modifier))
        DIE("eglExportDMABUFImageQueryMESA");

    fprintf(stderr,
            "Query: fourcc=0x%08x ('%c%c%c%c') num_planes=%d modifier=0x%016llx\n",
            (unsigned)fourcc,
            (fourcc      ) & 0xff, (fourcc >> 8 ) & 0xff,
            (fourcc >> 16) & 0xff, (fourcc >> 24) & 0xff,
            num_planes, (unsigned long long)modifier);

    if (num_planes <= 0 || num_planes > 4) {
        fprintf(stderr, "FAIL: implausible plane count %d\n", num_planes);
        return 4;
    }

    int fds[4]      = { -1, -1, -1, -1 };
    EGLint strides[4] = { 0, 0, 0, 0 };
    EGLint offsets[4] = { 0, 0, 0, 0 };
    if (!fn_export(egl, img, fds, strides, offsets))
        DIE("eglExportDMABUFImageMESA");

    fprintf(stderr, "Export results:\n");
    for (int i = 0; i < num_planes; ++i) {
        fprintf(stderr, "  plane[%d] fd=%d stride=%d offset=%d\n",
                i, fds[i], strides[i], offsets[i]);
    }

    if (fds[0] < 0) {
        fprintf(stderr, "FAIL: plane[0].fd < 0 — driver returned no FD\n");
        return 5;
    }

    struct stat st = {0};
    if (fstat(fds[0], &st) != 0) {
        fprintf(stderr, "FAIL: fstat(fd=%d) errno=%d (%s)\n",
                fds[0], errno, strerror(errno));
        return 6;
    }
    fprintf(stderr, "fstat: mode=0%o size=%lld dev=%lu ino=%lu\n",
            st.st_mode, (long long)st.st_size,
            (unsigned long)st.st_dev, (unsigned long)st.st_ino);

    char link[256] = {0};
    char proc_path[64];
    snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", fds[0]);
    ssize_t n = readlink(proc_path, link, sizeof(link) - 1);
    if (n > 0) {
        link[n] = 0;
        fprintf(stderr, "fd-target: %s\n", link);
    } else {
        fprintf(stderr, "readlink %s failed errno=%d\n", proc_path, errno);
    }

    fprintf(stderr, "PASS: Mesa-virpipe DMA-BUF export returned a stat-able FD.\n");

    if (fn_destroy) fn_destroy(egl, img);
    for (int i = 0; i < num_planes; ++i) if (fds[i] >= 0) close(fds[i]);
    glDeleteTextures(1, &tex);
    eglMakeCurrent(egl, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(egl, pb);
    eglDestroyContext(egl, ctx);
    eglTerminate(egl);
    XCloseDisplay(xdpy);
    return 0;
}
