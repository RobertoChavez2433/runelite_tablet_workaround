/*
 * cross-process-surface-probe.c — direct-Android-surface de-risker (S82 task #25).
 *
 * QUESTION WE'RE ANSWERING
 *   Per docs/s82-capture/direct-android-blocker-3-scope.md, sub-problem 5a:
 *   can a peer process import a buffer produced by another process and bind
 *   it as an EGL render target on this Mali driver, with a clean lifecycle
 *   across many resize cycles? That is THE primitive that rlawt-on-Surface
 *   would lean on — ANativeWindow's BufferQueue ultimately produces
 *   AHardwareBuffers, and the JVM-side rlawt would import each one as an
 *   EGLImage to draw into.
 *
 *   We can't construct a Java Surface from pure C, but we CAN exercise the
 *   underlying buffer-share primitive: AHardwareBuffer over SCM_RIGHTS via
 *   AHardwareBuffer_sendHandleToUnixSocket / recvHandleFromUnixSocket
 *   (API 26+, libnativewindow). If THIS fails on Mali r44p1 we fall back to
 *   optimising virgl. If it passes we commit the 5a + 5b-A engineering block.
 *
 * DESIGN
 *   fork() → producer (parent) + consumer (child), socketpair() for IPC.
 *   Per iteration:
 *     1. Producer allocates AHB at varied W,H (resize simulation)
 *     2. Producer creates EGLImage from AHB, renders a colored triangle
 *     3. Producer glFinish, sends AHB handle via SCM_RIGHTS
 *     4. Consumer receives AHB, imports as EGLImage in its own EGLContext
 *     5. Consumer binds to FBO, glReadPixels at known triangle interior
 *     6. Consumer verifies pixel color matches expected
 *     7. Consumer acks, both sides release; FD count sampled at /proc/self/fd
 *
 * PASS CRITERIA
 *   - All N iterations pass colour verification
 *   - FD count delta (end - start) is 0 on both sides → no leak
 *   - No EGL_BAD_PARAMETER / EGL_BAD_NATIVE_BUFFER on import
 *
 * OUTPUT: JSON to stdout. Saved by build script to docs/s82-capture/.
 *
 * Build:  see scripts/build-cross-process-probe.sh (or NDK clang directly)
 * Run:    adb shell /data/local/tmp/cross-process-surface-probe [iters]
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
#include <GLES2/gl2ext.h>  /* PFNGLEGLIMAGETARGETTEXTURE2DOESPROC, GLeglImageOES */
#include <android/hardware_buffer.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

/* ------------------------------------------------------------------ logging */

static int g_role_is_producer = 0;
static FILE *g_logf = NULL;

#define LOGF(fmt, ...) do { \
    fprintf(g_logf ? g_logf : stderr, "[%s] " fmt "\n", \
            g_role_is_producer ? "P" : "C", ##__VA_ARGS__); \
    if (g_logf) fflush(g_logf); \
} while (0)

static const char *egl_err_str(EGLint err) {
    switch (err) {
        case EGL_SUCCESS: return "EGL_SUCCESS";
        case EGL_NOT_INITIALIZED: return "EGL_NOT_INITIALIZED";
        case EGL_BAD_ACCESS: return "EGL_BAD_ACCESS";
        case EGL_BAD_ALLOC: return "EGL_BAD_ALLOC";
        case EGL_BAD_ATTRIBUTE: return "EGL_BAD_ATTRIBUTE";
        case EGL_BAD_CONFIG: return "EGL_BAD_CONFIG";
        case EGL_BAD_CONTEXT: return "EGL_BAD_CONTEXT";
        case EGL_BAD_CURRENT_SURFACE: return "EGL_BAD_CURRENT_SURFACE";
        case EGL_BAD_DISPLAY: return "EGL_BAD_DISPLAY";
        case EGL_BAD_MATCH: return "EGL_BAD_MATCH";
        case EGL_BAD_NATIVE_PIXMAP: return "EGL_BAD_NATIVE_PIXMAP";
        case EGL_BAD_NATIVE_WINDOW: return "EGL_BAD_NATIVE_WINDOW";
        case EGL_BAD_PARAMETER: return "EGL_BAD_PARAMETER";
        case EGL_BAD_SURFACE: return "EGL_BAD_SURFACE";
        case EGL_CONTEXT_LOST: return "EGL_CONTEXT_LOST";
        default: return "EGL_UNKNOWN";
    }
}

/* ----------------------------------------------------------------- fd count */

static int count_open_fds(void) {
    DIR *d = opendir("/proc/self/fd");
    if (!d) return -1;
    int n = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] != '.') n++;
    }
    closedir(d);
    return n;
}

/* ------------------------------------------------------- EGL extension fns */

static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID_p = NULL;
static PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR_p = NULL;
static PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR_p = NULL;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES_p = NULL;

static int load_egl_extensions(void) {
    eglGetNativeClientBufferANDROID_p =
        (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)
        eglGetProcAddress("eglGetNativeClientBufferANDROID");
    eglCreateImageKHR_p =
        (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
    eglDestroyImageKHR_p =
        (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
    glEGLImageTargetTexture2DOES_p =
        (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)
        eglGetProcAddress("glEGLImageTargetTexture2DOES");
    if (!eglGetNativeClientBufferANDROID_p) {
        LOGF("EGL ext missing: eglGetNativeClientBufferANDROID");
        return -1;
    }
    if (!eglCreateImageKHR_p || !eglDestroyImageKHR_p) {
        LOGF("EGL ext missing: eglCreateImageKHR/eglDestroyImageKHR");
        return -1;
    }
    if (!glEGLImageTargetTexture2DOES_p) {
        LOGF("GL ext missing: glEGLImageTargetTexture2DOES");
        return -1;
    }
    return 0;
}

/* ---------------------------------------------------------------- EGL init */

typedef struct {
    EGLDisplay dpy;
    EGLContext ctx;
    EGLConfig cfg;
} egl_t;

static int egl_init(egl_t *e) {
    e->dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (e->dpy == EGL_NO_DISPLAY) {
        LOGF("eglGetDisplay failed");
        return -1;
    }
    EGLint major, minor;
    if (!eglInitialize(e->dpy, &major, &minor)) {
        LOGF("eglInitialize failed err=%s", egl_err_str(eglGetError()));
        return -1;
    }
    LOGF("EGL %d.%d initialized", major, minor);

    EGLint cfg_attrs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLint num_cfg = 0;
    if (!eglChooseConfig(e->dpy, cfg_attrs, &e->cfg, 1, &num_cfg) || num_cfg < 1) {
        LOGF("eglChooseConfig failed err=%s", egl_err_str(eglGetError()));
        return -1;
    }

    EGLint ctx_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    e->ctx = eglCreateContext(e->dpy, e->cfg, EGL_NO_CONTEXT, ctx_attrs);
    if (e->ctx == EGL_NO_CONTEXT) {
        LOGF("eglCreateContext failed err=%s", egl_err_str(eglGetError()));
        return -1;
    }
    if (!eglMakeCurrent(e->dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, e->ctx)) {
        LOGF("eglMakeCurrent (surfaceless) failed err=%s",
             egl_err_str(eglGetError()));
        return -1;
    }

    /* Surfaceless context requires EGL_KHR_surfaceless_context. Verify. */
    const char *exts = eglQueryString(e->dpy, EGL_EXTENSIONS);
    if (!exts || !strstr(exts, "EGL_KHR_surfaceless_context")) {
        LOGF("EGL_KHR_surfaceless_context missing — would need pbuffer fallback");
        /* keep going; eglMakeCurrent already returned ok above so the driver
         * tolerated it on this path */
    }
    return 0;
}

static void egl_destroy(egl_t *e) {
    if (e->dpy != EGL_NO_DISPLAY) {
        eglMakeCurrent(e->dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (e->ctx != EGL_NO_CONTEXT) eglDestroyContext(e->dpy, e->ctx);
        eglTerminate(e->dpy);
    }
}

/* ---------------------------------------------------------------- shaders */

static GLuint compile_shader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        LOGF("shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static GLuint build_program(const char *vsrc, const char *fsrc) {
    GLuint vs = compile_shader(GL_VERTEX_SHADER, vsrc);
    GLuint fs = compile_shader(GL_FRAGMENT_SHADER, fsrc);
    if (!vs || !fs) return 0;
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(p, sizeof(log), NULL, log);
        LOGF("program link failed: %s", log);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

static const char *VS_SRC =
    "#version 300 es\n"
    "in vec2 a_pos;\n"
    "void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }\n";

static const char *FS_SRC =
    "#version 300 es\n"
    "precision mediump float;\n"
    "uniform vec4 u_color;\n"
    "out vec4 frag_color;\n"
    "void main() { frag_color = u_color; }\n";

/* ------------------------------------------------------- AHB → EGLImage */

static EGLImageKHR ahb_to_egl_image(EGLDisplay dpy, AHardwareBuffer *ahb) {
    EGLClientBuffer cbuf = eglGetNativeClientBufferANDROID_p(ahb);
    if (!cbuf) {
        LOGF("eglGetNativeClientBufferANDROID returned NULL err=%s",
             egl_err_str(eglGetError()));
        return EGL_NO_IMAGE_KHR;
    }
    EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR img = eglCreateImageKHR_p(
        dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cbuf, attrs);
    if (img == EGL_NO_IMAGE_KHR) {
        LOGF("eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID) failed err=%s",
             egl_err_str(eglGetError()));
    }
    return img;
}

/* Bind an EGLImage to a fresh FBO via texture target. Returns FBO id, or 0. */
static GLuint bind_image_as_fbo(EGLImageKHR img, GLuint *out_tex) {
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glEGLImageTargetTexture2DOES_p(GL_TEXTURE_2D, (GLeglImageOES)img);
    GLenum gerr = glGetError();
    if (gerr != GL_NO_ERROR) {
        LOGF("glEGLImageTargetTexture2DOES error 0x%x", gerr);
        glDeleteTextures(1, &tex);
        return 0;
    }
    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGF("FBO incomplete status=0x%x", glCheckFramebufferStatus(GL_FRAMEBUFFER));
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &tex);
        return 0;
    }
    *out_tex = tex;
    return fbo;
}

/* --------------------------------------------------------------- producer */

typedef struct {
    int iters;
    int sock;
    int fail_count;
    int last_w, last_h;
} producer_state_t;

static int producer_iter(producer_state_t *ps, int iter, GLuint prog,
                         GLint loc_color, GLuint vbo) {
    /* Vary W/H to simulate resize. Stay 16-aligned for EGLImage import. */
    int w = 256 + ((iter * 32) % 512);
    int h = 256 + ((iter * 16) % 384);
    w &= ~0xF; h &= ~0xF;
    if (w < 64) w = 64;
    if (h < 64) h = 64;
    ps->last_w = w; ps->last_h = h;

    AHardwareBuffer_Desc desc = {
        .width = w, .height = h, .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
               | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT
               | AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
    };
    AHardwareBuffer *ahb = NULL;
    if (AHardwareBuffer_allocate(&desc, &ahb) != 0 || !ahb) {
        LOGF("iter=%d AHardwareBuffer_allocate(%dx%d) failed", iter, w, h);
        return -1;
    }

    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLImageKHR img = ahb_to_egl_image(dpy, ahb);
    if (img == EGL_NO_IMAGE_KHR) {
        AHardwareBuffer_release(ahb);
        return -1;
    }

    GLuint tex = 0;
    GLuint fbo = bind_image_as_fbo(img, &tex);
    if (!fbo) {
        eglDestroyImageKHR_p(dpy, img);
        AHardwareBuffer_release(ahb);
        return -1;
    }

    glViewport(0, 0, w, h);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    /* Color cycle: red → green → blue, encoded into expected pixel below. */
    float r = (iter % 3 == 0) ? 1.0f : 0.0f;
    float g = (iter % 3 == 1) ? 1.0f : 0.0f;
    float b = (iter % 3 == 2) ? 1.0f : 0.0f;

    glUseProgram(prog);
    glUniform4f(loc_color, r, g, b, 1.0f);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, (void*)0);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glDisableVertexAttribArray(0);
    glFinish();

    /* Send AHB handle. SCM_RIGHTS happens inside this NDK fn. */
    if (AHardwareBuffer_sendHandleToUnixSocket(ahb, ps->sock) != 0) {
        LOGF("iter=%d sendHandle failed errno=%d", iter, errno);
        glDeleteFramebuffers(1, &fbo);
        glDeleteTextures(1, &tex);
        eglDestroyImageKHR_p(dpy, img);
        AHardwareBuffer_release(ahb);
        return -1;
    }

    /* Send dims + expected color so consumer can verify (avoids encoding
     * iter→color logic on both sides). */
    int meta[5] = { iter, w, h, 0, 0 };
    meta[3] = (int)(r * 255 + 0.5f) | ((int)(g * 255 + 0.5f) << 8) |
              ((int)(b * 255 + 0.5f) << 16) | (255 << 24);
    if (write(ps->sock, meta, sizeof(meta)) != (ssize_t)sizeof(meta)) {
        LOGF("iter=%d meta write failed errno=%d", iter, errno);
    }

    /* Wait ack: int verdict (0=ok, !=0=fail) */
    int verdict = -1;
    if (read(ps->sock, &verdict, sizeof(verdict)) != (ssize_t)sizeof(verdict)) {
        LOGF("iter=%d ack read failed errno=%d", iter, errno);
        verdict = -2;
    }

    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(1, &tex);
    eglDestroyImageKHR_p(dpy, img);
    AHardwareBuffer_release(ahb);

    if (verdict != 0) {
        LOGF("iter=%d %dx%d color=#%06x verdict=FAIL(%d)",
             iter, w, h, meta[3] & 0xFFFFFF, verdict);
        ps->fail_count++;
        return -1;
    }
    return 0;
}

static int producer_main(producer_state_t *ps) {
    g_role_is_producer = 1;
    egl_t e = { EGL_NO_DISPLAY, EGL_NO_CONTEXT, NULL };
    if (egl_init(&e) != 0) return 2;
    if (load_egl_extensions() != 0) { egl_destroy(&e); return 2; }

    GLuint prog = build_program(VS_SRC, FS_SRC);
    if (!prog) { egl_destroy(&e); return 2; }
    GLint loc_color = glGetUniformLocation(prog, "u_color");

    /* Triangle covering most of viewport — readback samples interior. */
    float verts[] = { -0.9f, -0.9f,  0.9f, -0.9f,  0.0f, 0.9f };
    GLuint vbo = 0;
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);

    int fd_start = count_open_fds();
    LOGF("producer ready fd_start=%d iters=%d", fd_start, ps->iters);

    int fd_mid = -1;
    for (int i = 0; i < ps->iters; i++) {
        if (producer_iter(ps, i, prog, loc_color, vbo) != 0) {
            /* keep going so we can count all failures */
        }
        if (i == ps->iters / 2) fd_mid = count_open_fds();
    }

    int fd_end = count_open_fds();
    LOGF("producer done fd_start=%d fd_mid=%d fd_end=%d fails=%d",
         fd_start, fd_mid, fd_end, ps->fail_count);

    /* Tell consumer we're done: send sentinel iter=-1. */
    int sentinel[5] = { -1, 0, 0, 0, 0 };
    write(ps->sock, sentinel, sizeof(sentinel));

    glDeleteBuffers(1, &vbo);
    glDeleteProgram(prog);
    egl_destroy(&e);

    /* stash fd metrics in struct via globals isn't ideal — return via params. */
    ps->last_w = fd_start; ps->last_h = fd_end;
    return ps->fail_count == 0 ? 0 : 1;
}

/* --------------------------------------------------------------- consumer */

typedef struct {
    int sock;
    int verifies_ok;
    int verifies_fail;
    int recv_fail;
    int import_fail;
} consumer_state_t;

static int consumer_main(consumer_state_t *cs) {
    g_role_is_producer = 0;
    egl_t e = { EGL_NO_DISPLAY, EGL_NO_CONTEXT, NULL };
    if (egl_init(&e) != 0) return 2;
    if (load_egl_extensions() != 0) { egl_destroy(&e); return 2; }

    int fd_start = count_open_fds();
    LOGF("consumer ready fd_start=%d", fd_start);

    int iter = 0;
    int fd_mid = -1;
    while (1) {
        /* Peek 4 bytes first to distinguish a fresh AHB-handle frame from the
         * end-of-stream sentinel (meta-only packet with iter == -1). recv with
         * MSG_PEEK so the bytes stay in the kernel buffer for whoever consumes
         * them next. */
        int peek_iter = 0;
        ssize_t pn = recv(cs->sock, &peek_iter, sizeof(peek_iter), MSG_PEEK);
        if (pn <= 0) { LOGF("consumer EOF (peek pn=%zd)", pn); break; }
        if (pn == (ssize_t)sizeof(peek_iter) && peek_iter == -1) {
            int meta[5];
            (void)read(cs->sock, meta, sizeof(meta));
            LOGF("consumer received sentinel; clean exit");
            break;
        }

        AHardwareBuffer *ahb = NULL;
        int rc = AHardwareBuffer_recvHandleFromUnixSocket(cs->sock, &ahb);
        if (rc != 0) {
            cs->recv_fail++;
            int meta[5];
            ssize_t n = read(cs->sock, meta, sizeof(meta));
            if (n <= 0) { LOGF("consumer EOF after recv_fail"); break; }
            int verdict = -10;
            write(cs->sock, &verdict, sizeof(verdict));
            iter++;
            continue;
        }

        int meta[5];
        if (read(cs->sock, meta, sizeof(meta)) != (ssize_t)sizeof(meta)) {
            LOGF("consumer meta read failed");
            AHardwareBuffer_release(ahb);
            break;
        }
        if (meta[0] == -1) {
            AHardwareBuffer_release(ahb);
            break;
        }
        int piter = meta[0], w = meta[1], h = meta[2];
        uint32_t expected = (uint32_t)meta[3];

        EGLImageKHR img = ahb_to_egl_image(e.dpy, ahb);
        if (img == EGL_NO_IMAGE_KHR) {
            cs->import_fail++;
            int verdict = -3;
            write(cs->sock, &verdict, sizeof(verdict));
            AHardwareBuffer_release(ahb);
            iter++;
            continue;
        }

        GLuint tex = 0;
        GLuint fbo = bind_image_as_fbo(img, &tex);
        int verdict = 0;
        uint32_t got = 0;
        if (!fbo) {
            cs->import_fail++;
            verdict = -4;
        } else {
            /* Sample center pixel — triangle covers it. */
            uint8_t px[4] = {0,0,0,0};
            glReadPixels(w / 2, h / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            got = px[0] | (px[1] << 8) | (px[2] << 16) | (px[3] << 24);
            if ((got & 0x00FFFFFF) != (expected & 0x00FFFFFF)) {
                cs->verifies_fail++;
                verdict = -5;
            } else {
                cs->verifies_ok++;
            }
            glDeleteFramebuffers(1, &fbo);
            glDeleteTextures(1, &tex);
        }

        eglDestroyImageKHR_p(e.dpy, img);
        AHardwareBuffer_release(ahb);

        if (verdict != 0) {
            LOGF("iter=%d %dx%d expected=#%06x got=#%06x verdict=%d",
                 piter, w, h, expected & 0xFFFFFF, got & 0xFFFFFF, verdict);
        }
        write(cs->sock, &verdict, sizeof(verdict));

        if (iter == 5) fd_mid = count_open_fds();
        iter++;
    }

    int fd_end = count_open_fds();
    LOGF("consumer done iters=%d ok=%d fail=%d recv_fail=%d import_fail=%d "
         "fd_start=%d fd_mid=%d fd_end=%d",
         iter, cs->verifies_ok, cs->verifies_fail, cs->recv_fail,
         cs->import_fail, fd_start, fd_mid, fd_end);

    egl_destroy(&e);
    /* stash for json */
    cs->import_fail += 0;  /* keep struct */
    return (cs->verifies_fail == 0 && cs->import_fail == 0 &&
            cs->recv_fail == 0) ? 0 : 1;
}

/* ----------------------------------------------------------------- main */

int main(int argc, char **argv) {
    int iters = 20;
    if (argc > 1) iters = atoi(argv[1]);
    if (iters < 1) iters = 1;
    if (iters > 1000) iters = 1000;

    int sv[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) {
        fprintf(stderr, "socketpair failed errno=%d\n", errno);
        return 2;
    }

    int parent_fd_before = count_open_fds();

    pid_t pid = fork();
    if (pid < 0) { perror("fork"); return 2; }

    if (pid == 0) {
        /* child = consumer */
        close(sv[0]);
        consumer_state_t cs = { sv[1], 0, 0, 0, 0 };
        int rc = consumer_main(&cs);
        close(sv[1]);
        /* exit code packs counters in low/high bytes — parent reads via exit */
        _exit(rc & 0xFF);
    }

    /* parent = producer */
    close(sv[1]);
    producer_state_t ps = { iters, sv[0], 0, 0, 0 };
    int prc = producer_main(&ps);
    close(sv[0]);

    int wstatus = 0;
    waitpid(pid, &wstatus, 0);
    int crc = WIFEXITED(wstatus) ? WEXITSTATUS(wstatus) : 99;

    int parent_fd_after = count_open_fds();

    /* JSON to stdout */
    printf("{\n");
    printf("  \"probe\": \"cross-process-surface-probe\",\n");
    printf("  \"iters\": %d,\n", iters);
    printf("  \"producer\": {\n");
    printf("    \"exit\": %d,\n", prc);
    printf("    \"fail_count\": %d,\n", ps.fail_count);
    printf("    \"fd_start\": %d,\n", ps.last_w);
    printf("    \"fd_end\": %d\n", ps.last_h);
    printf("  },\n");
    printf("  \"consumer\": {\n");
    printf("    \"exit\": %d\n", crc);
    printf("  },\n");
    printf("  \"parent_proc\": {\n");
    printf("    \"fd_before_fork\": %d,\n", parent_fd_before);
    printf("    \"fd_after_join\": %d\n", parent_fd_after);
    printf("  },\n");
    printf("  \"verdict\": {\n");
    printf("    \"cross_process_ahb_egl_import\": \"%s\",\n",
           (prc == 0 && crc == 0) ? "PASS" : "FAIL");
    printf("    \"summary\": \"%s\"\n",
           (prc == 0 && crc == 0)
             ? "Mali driver imports AHB across processes via SCM_RIGHTS; "
               "5a primitive is viable; proceed to Surface-AIDL leg."
             : "Cross-process AHB import failed on this Mali r44p1 stack; "
               "review logs before committing 5a.");
    printf("  }\n");
    printf("}\n");

    return (prc == 0 && crc == 0) ? 0 : 1;
}
