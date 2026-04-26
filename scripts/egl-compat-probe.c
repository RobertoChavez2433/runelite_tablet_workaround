/*
 * egl-compat-probe.c — direct-Android feasibility probe.
 *
 * Runs as the shell UID (adb shell ./egl-compat-probe) so it sees the
 * Mali Android EGL driver, NOT Termux's Mesa. The point is to clear two
 * blockers BEFORE we commit weeks of work to a librlawt-android rewrite:
 *
 *   #1  GL feature surface — does Mali GLES expose what RuneLite GpuPlugin
 *       actually uses? GpuPlugin uses GLES-incompatible shader features
 *       on the Mesa virgl path (compute shaders, SSBOs, GL 4.3 fixed
 *       function bits). If Mali doesn't give us those via GLES 3.2, we
 *       need a translation layer (zink, ANGLE) which adds weeks.
 *
 *   #2  EGL extension surface — for direct ANativeWindow rendering we
 *       need EGL_ANDROID_image_native_buffer + GL_OES_EGL_image_external
 *       at minimum. Most Mali Android stacks have these but a Tab S10
 *       Ultra might be locked down or stubbed.
 *
 * Output is JSON to stdout so a follow-up script can parse it cleanly.
 *
 * Build (NDK):
 *   $NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android34-clang \
 *       -O0 -g egl-compat-probe.c -lEGL -lGLESv3 -llog -o egl-compat-probe
 *
 * Push + run:
 *   adb push egl-compat-probe /data/local/tmp/
 *   adb shell chmod 0755 /data/local/tmp/egl-compat-probe
 *   adb shell /data/local/tmp/egl-compat-probe
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl32.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define JS_KEY(k) printf("    \"%s\": ", (k))
#define JS_STR(v) printf("\"%s\"", (v) ? (v) : "")
#define JS_INT(v) printf("%d", (v))
#define JS_LINE_END(more) printf("%s\n", (more) ? "," : "")

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

/* Print a JSON-escaped string. EGL extension lists are space-separated; the
 * GL_EXTENSIONS string is also space-separated and can be ~10KB on Mali. We
 * just escape backslashes and quotes — newlines aren't expected. */
static void print_json_string(const char *s) {
    putchar('"');
    if (s) {
        for (; *s; s++) {
            unsigned char c = (unsigned char)*s;
            if (c == '"' || c == '\\') {
                putchar('\\');
                putchar(c);
            } else if (c < 0x20) {
                printf("\\u%04x", c);
            } else {
                putchar(c);
            }
        }
    }
    putchar('"');
}

/* Check whether `needle` appears as a whitespace-bounded token in `hay`.
 * EGL/GL extensions are space-separated, so substring search works as long
 * as we anchor on word boundaries. */
static int has_extension(const char *hay, const char *needle) {
    if (!hay || !needle) return 0;
    size_t nlen = strlen(needle);
    const char *p = hay;
    while ((p = strstr(p, needle)) != NULL) {
        int left_ok = (p == hay) || (p[-1] == ' ');
        char right = p[nlen];
        int right_ok = (right == ' ') || (right == '\0');
        if (left_ok && right_ok) return 1;
        p += nlen;
    }
    return 0;
}

int main(int argc, char **argv) {
    (void)argc; (void)argv;

    printf("{\n");
    printf("  \"probe\": \"egl-compat-probe\",\n");
    printf("  \"target\": \"Mali Android EGL/GLES — direct-android-surface feasibility\",\n");

    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY) {
        printf("  \"error\": \"eglGetDisplay returned EGL_NO_DISPLAY\"\n}\n");
        return 1;
    }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(dpy, &major, &minor)) {
        printf("  \"error\": \"eglInitialize: %s\"\n}\n", egl_err_str(eglGetError()));
        return 1;
    }

    printf("  \"egl\": {\n");
    JS_KEY("version");        printf("\"%d.%d\",\n", major, minor);
    JS_KEY("vendor");         print_json_string(eglQueryString(dpy, EGL_VENDOR));         puts(",");
    JS_KEY("egl_version");    print_json_string(eglQueryString(dpy, EGL_VERSION));        puts(",");
    JS_KEY("client_apis");    print_json_string(eglQueryString(dpy, EGL_CLIENT_APIS));    puts(",");
    const char *eglExt = eglQueryString(dpy, EGL_EXTENSIONS);
    JS_KEY("extensions");     print_json_string(eglExt);                                  puts(",");

    /* Direct-Android critical extensions. */
    const char *want[] = {
        "EGL_ANDROID_image_native_buffer",
        "EGL_ANDROID_native_fence_sync",
        "EGL_ANDROID_presentation_time",
        "EGL_ANDROID_get_native_client_buffer",
        "EGL_KHR_image",
        "EGL_KHR_image_base",
        "EGL_KHR_gl_renderbuffer_image",
        "EGL_KHR_gl_texture_2D_image",
        "EGL_KHR_fence_sync",
        "EGL_KHR_wait_sync",
        "EGL_KHR_surfaceless_context",
        "EGL_EXT_buffer_age",
        NULL
    };
    JS_KEY("required_extensions"); printf("{\n");
    for (int i = 0; want[i]; i++) {
        printf("      \"%s\": %s%s\n", want[i],
               has_extension(eglExt, want[i]) ? "true" : "false",
               want[i+1] ? "," : "");
    }
    printf("    }\n");
    printf("  },\n");

    /* Probe a GLES 3.x context against a 1x1 PBUFFER so we don't need a
     * Surface or window — adb shell is headless. */
    static const EGLint cfgAttrs[] = {
        EGL_SURFACE_TYPE,   EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE,       8,
        EGL_GREEN_SIZE,     8,
        EGL_BLUE_SIZE,      8,
        EGL_ALPHA_SIZE,     8,
        EGL_DEPTH_SIZE,     24,
        EGL_STENCIL_SIZE,   8,
        EGL_NONE
    };
    EGLConfig cfg;
    EGLint nCfg = 0;
    if (!eglChooseConfig(dpy, cfgAttrs, &cfg, 1, &nCfg) || nCfg == 0) {
        printf("  \"error\": \"eglChooseConfig: %s nCfg=%d\"\n}\n", egl_err_str(eglGetError()), nCfg);
        eglTerminate(dpy);
        return 2;
    }

    static const EGLint pbAttrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, pbAttrs);
    if (surf == EGL_NO_SURFACE) {
        printf("  \"error\": \"eglCreatePbufferSurface: %s\"\n}\n", egl_err_str(eglGetError()));
        eglTerminate(dpy);
        return 3;
    }

    /* Try GLES 3.2 first, fall back to 3.1 then 3.0. RL GpuPlugin's modern
     * paths want compute shaders (3.1) and SSBOs (3.1); 3.2 adds geometry
     * shaders and tessellation which RL doesn't strictly need. We log which
     * version actually instantiated. */
    int gles_major = 0, gles_minor = 0;
    EGLContext ctx = EGL_NO_CONTEXT;
    static const int versions[][2] = {{3,2},{3,1},{3,0},{2,0}};
    for (size_t v = 0; v < sizeof(versions)/sizeof(versions[0]); v++) {
        EGLint ctxAttrs[] = {
            EGL_CONTEXT_MAJOR_VERSION, versions[v][0],
            EGL_CONTEXT_MINOR_VERSION, versions[v][1],
            EGL_NONE
        };
        ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttrs);
        if (ctx != EGL_NO_CONTEXT) {
            gles_major = versions[v][0];
            gles_minor = versions[v][1];
            break;
        }
    }
    if (ctx == EGL_NO_CONTEXT) {
        printf("  \"error\": \"eglCreateContext (all GLES versions tried): %s\"\n}\n", egl_err_str(eglGetError()));
        eglDestroySurface(dpy, surf);
        eglTerminate(dpy);
        return 4;
    }

    if (!eglMakeCurrent(dpy, surf, surf, ctx)) {
        printf("  \"error\": \"eglMakeCurrent: %s\"\n}\n", egl_err_str(eglGetError()));
        eglDestroyContext(dpy, ctx);
        eglDestroySurface(dpy, surf);
        eglTerminate(dpy);
        return 5;
    }

    const char *glVersion  = (const char *)glGetString(GL_VERSION);
    const char *glVendor   = (const char *)glGetString(GL_VENDOR);
    const char *glRenderer = (const char *)glGetString(GL_RENDERER);
    const char *glsl       = (const char *)glGetString(GL_SHADING_LANGUAGE_VERSION);
    const char *glExt      = (const char *)glGetString(GL_EXTENSIONS);

    printf("  \"gles\": {\n");
    JS_KEY("requested_version"); printf("\"%d.%d\",\n", gles_major, gles_minor);
    JS_KEY("version");           print_json_string(glVersion);  puts(",");
    JS_KEY("vendor");            print_json_string(glVendor);   puts(",");
    JS_KEY("renderer");          print_json_string(glRenderer); puts(",");
    JS_KEY("shading_lang");      print_json_string(glsl);       puts(",");

    GLint maxTex = 0, max3d = 0, maxCube = 0, maxRb = 0, maxSamples = 0;
    GLint maxVtxAttribs = 0, maxColorAttachments = 0, maxDrawBuffers = 0;
    GLint maxUboBindings = 0, maxSsboBindings = 0;
    GLint maxComputeWG[3] = {0,0,0}, maxComputeInv = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE,             &maxTex);
    glGetIntegerv(GL_MAX_3D_TEXTURE_SIZE,          &max3d);
    glGetIntegerv(GL_MAX_CUBE_MAP_TEXTURE_SIZE,    &maxCube);
    glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE,        &maxRb);
    glGetIntegerv(GL_MAX_SAMPLES,                  &maxSamples);
    glGetIntegerv(GL_MAX_VERTEX_ATTRIBS,           &maxVtxAttribs);
    glGetIntegerv(GL_MAX_COLOR_ATTACHMENTS,        &maxColorAttachments);
    glGetIntegerv(GL_MAX_DRAW_BUFFERS,             &maxDrawBuffers);
    glGetIntegerv(GL_MAX_UNIFORM_BUFFER_BINDINGS,  &maxUboBindings);
    if (gles_major >= 3 && gles_minor >= 1) {
        glGetIntegerv(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS, &maxSsboBindings);
        glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, &maxComputeWG[0]);
        glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_COUNT, 1, &maxComputeWG[1]);
        glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_COUNT, 2, &maxComputeWG[2]);
        glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, &maxComputeInv);
    }
    /* clear any errors generated by querying constants the driver doesn't honor */
    while (glGetError() != GL_NO_ERROR) { }

    JS_KEY("limits"); printf("{\n");
    printf("      \"GL_MAX_TEXTURE_SIZE\": %d,\n", maxTex);
    printf("      \"GL_MAX_3D_TEXTURE_SIZE\": %d,\n", max3d);
    printf("      \"GL_MAX_CUBE_MAP_TEXTURE_SIZE\": %d,\n", maxCube);
    printf("      \"GL_MAX_RENDERBUFFER_SIZE\": %d,\n", maxRb);
    printf("      \"GL_MAX_SAMPLES\": %d,\n", maxSamples);
    printf("      \"GL_MAX_VERTEX_ATTRIBS\": %d,\n", maxVtxAttribs);
    printf("      \"GL_MAX_COLOR_ATTACHMENTS\": %d,\n", maxColorAttachments);
    printf("      \"GL_MAX_DRAW_BUFFERS\": %d,\n", maxDrawBuffers);
    printf("      \"GL_MAX_UNIFORM_BUFFER_BINDINGS\": %d,\n", maxUboBindings);
    printf("      \"GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS\": %d,\n", maxSsboBindings);
    printf("      \"GL_MAX_COMPUTE_WORK_GROUP_COUNT\": [%d, %d, %d],\n",
           maxComputeWG[0], maxComputeWG[1], maxComputeWG[2]);
    printf("      \"GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS\": %d\n", maxComputeInv);
    printf("    },\n");

    JS_KEY("extensions"); print_json_string(glExt); puts(",");

    /* GLES extensions that RL GpuPlugin's GLES path (if any) would need.
     * The desktop RL plugin uses some of these via Mesa's GLES translation;
     * if Mali doesn't expose them natively we'd still need a layer. */
    const char *glWant[] = {
        "GL_OES_EGL_image",
        "GL_OES_EGL_image_external",
        "GL_OES_EGL_image_external_essl3",
        "GL_EXT_color_buffer_float",
        "GL_EXT_color_buffer_half_float",
        "GL_EXT_texture_storage",
        "GL_EXT_texture_filter_anisotropic",
        "GL_EXT_buffer_storage",
        "GL_EXT_disjoint_timer_query",
        "GL_EXT_geometry_shader",
        "GL_EXT_tessellation_shader",
        "GL_OES_compressed_ETC1_RGB8_texture",
        "GL_KHR_debug",
        NULL
    };
    JS_KEY("relevant_extensions"); printf("{\n");
    for (int i = 0; glWant[i]; i++) {
        printf("      \"%s\": %s%s\n", glWant[i],
               has_extension(glExt, glWant[i]) ? "true" : "false",
               glWant[i+1] ? "," : "");
    }
    printf("    }\n");
    printf("  },\n");

    /* Verdict — programmatic discriminator the wrapper script can grep. */
    int has_image_buf = has_extension(eglExt, "EGL_ANDROID_image_native_buffer");
    int has_egl_image_ext = has_extension(glExt, "GL_OES_EGL_image_external");
    int has_compute = (gles_major == 3 && gles_minor >= 1) || gles_major >= 4;
    int has_ssbo = has_compute; /* SSBOs are GLES 3.1+ */

    printf("  \"verdict\": {\n");
    printf("    \"egl_android_image_native_buffer\": %s,\n", has_image_buf ? "true" : "false");
    printf("    \"gl_oes_egl_image_external\": %s,\n", has_egl_image_ext ? "true" : "false");
    printf("    \"compute_shaders\": %s,\n", has_compute ? "true" : "false");
    printf("    \"ssbo\": %s,\n", has_ssbo ? "true" : "false");
    printf("    \"direct_android_blocker_1_clear\": %s,\n",
           (has_compute && has_ssbo) ? "true" : "false");
    printf("    \"direct_android_blocker_2_clear\": %s,\n",
           (has_image_buf && has_egl_image_ext) ? "true" : "false");
    printf("    \"summary\": \"%s\"\n",
           (has_compute && has_ssbo && has_image_buf && has_egl_image_ext)
               ? "PASS — proceed to feasibility blocker #3 (AWT chrome embedding) before code commitment"
               : "FAIL — at least one blocker not cleared; direct-android needs a translation layer or different approach");
    printf("  }\n");
    printf("}\n");

    eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(dpy, ctx);
    eglDestroySurface(dpy, surf);
    eglTerminate(dpy);
    return 0;
}
