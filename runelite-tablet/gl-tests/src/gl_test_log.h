/*
 * gl_test_log.h — Logging, JSON output, timing, crash handler, and GL error checking
 * for the VirGL rendering test pipeline.
 *
 * Usage: #include "gl_test_log.h" in gl_test_harness.c
 * Must be included AFTER GL headers.
 */
#ifndef GL_TEST_LOG_H
#define GL_TEST_LOG_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <time.h>
#include <signal.h>
#include <unistd.h>
#include <stdint.h>

/* ===== Configuration ===== */

static char g_results_dir[512] = ".";
static FILE *g_log_file = NULL;
static FILE *g_summary_json = NULL;
static int g_summary_first_entry = 1;

/* ===== Logging Macros ===== */

#define LOG_INFO(fmt, ...) \
    do { \
        fprintf(stdout, "[INFO] " fmt "\n", ##__VA_ARGS__); \
        if (g_log_file) { fprintf(g_log_file, "[INFO] " fmt "\n", ##__VA_ARGS__); fflush(g_log_file); } \
    } while (0)

#define LOG_ERROR(fmt, ...) \
    do { \
        fprintf(stderr, "[ERROR] " fmt "\n", ##__VA_ARGS__); \
        if (g_log_file) { fprintf(g_log_file, "[ERROR] " fmt "\n", ##__VA_ARGS__); fflush(g_log_file); } \
    } while (0)

#define LOG_GL(func_name) \
    do { \
        GLenum _err = glGetError(); \
        if (_err != GL_NO_ERROR) { \
            LOG_ERROR("GL error after %s: 0x%04x", func_name, _err); \
        } \
    } while (0)

#define CHECK_GL(func_name) LOG_GL(func_name)

/* ===== Timing Helpers ===== */

static inline double get_time_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1e9 + (double)ts.tv_nsec;
}

static inline double get_time_ms(void) {
    return get_time_ns() / 1e6;
}

/* ===== CRC32 (for framebuffer validation) ===== */

static uint32_t crc32_table[256];
static int crc32_table_init = 0;

static void crc32_init_table(void) {
    if (crc32_table_init) return;
    for (uint32_t i = 0; i < 256; i++) {
        uint32_t c = i;
        for (int j = 0; j < 8; j++) {
            c = (c & 1) ? (0xEDB88320 ^ (c >> 1)) : (c >> 1);
        }
        crc32_table[i] = c;
    }
    crc32_table_init = 1;
}

static uint32_t crc32_compute(const void *data, size_t len) {
    crc32_init_table();
    const uint8_t *buf = (const uint8_t *)data;
    uint32_t crc = 0xFFFFFFFF;
    for (size_t i = 0; i < len; i++) {
        crc = crc32_table[(crc ^ buf[i]) & 0xFF] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFF;
}

/* ===== JSON Helpers ===== */

static void json_escape_string(FILE *f, const char *s) {
    fputc('"', f);
    if (s) {
        for (; *s; s++) {
            switch (*s) {
                case '"':  fputs("\\\"", f); break;
                case '\\': fputs("\\\\", f); break;
                case '\n': fputs("\\n", f); break;
                case '\r': fputs("\\r", f); break;
                case '\t': fputs("\\t", f); break;
                default:   fputc(*s, f); break;
            }
        }
    }
    fputc('"', f);
}

/* ===== Log Initialization ===== */

static void log_init(const char *results_dir) {
    strncpy(g_results_dir, results_dir, sizeof(g_results_dir) - 1);
    g_results_dir[sizeof(g_results_dir) - 1] = '\0';

    char log_path[600];
    snprintf(log_path, sizeof(log_path), "%s/harness.log", g_results_dir);
    g_log_file = fopen(log_path, "w");
    if (!g_log_file) {
        fprintf(stderr, "[FATAL] Cannot open log file: %s\n", log_path);
    }

    /* Write header */
    time_t now = time(NULL);
    struct tm *tm_info = localtime(&now);
    char time_buf[64];
    strftime(time_buf, sizeof(time_buf), "%Y-%m-%d %H:%M:%S", tm_info);

    LOG_INFO("=== VirGL Test Harness ===");
    LOG_INFO("Started: %s", time_buf);
    LOG_INFO("Results dir: %s", g_results_dir);

    /* Open summary.json */
    snprintf(log_path, sizeof(log_path), "%s/summary.json", g_results_dir);
    g_summary_json = fopen(log_path, "w");
    if (g_summary_json) {
        fprintf(g_summary_json, "{\n  \"started\": \"%s\",\n  \"modules\": [\n", time_buf);
    }
}

static void log_close(void) {
    if (g_summary_json) {
        fprintf(g_summary_json, "\n  ]\n}\n");
        fclose(g_summary_json);
        g_summary_json = NULL;
    }
    if (g_log_file) {
        time_t now = time(NULL);
        struct tm *tm_info = localtime(&now);
        char time_buf[64];
        strftime(time_buf, sizeof(time_buf), "%Y-%m-%d %H:%M:%S", tm_info);
        LOG_INFO("Finished: %s", time_buf);
        fclose(g_log_file);
        g_log_file = NULL;
    }
}

/* ===== Module Result Recording ===== */

static void log_module_result(int module, const char *sub, const char *name,
                              const char *status, double time_ms, const char *detail) {
    LOG_INFO("Module %d%s (%s): %s (%.1f ms)", module, sub ? sub : "", name, status, time_ms);
    if (detail && detail[0]) {
        LOG_INFO("  Detail: %s", detail);
    }

    if (g_summary_json) {
        if (!g_summary_first_entry) {
            fprintf(g_summary_json, ",\n");
        }
        g_summary_first_entry = 0;

        fprintf(g_summary_json, "    {\"module\": %d", module);
        if (sub) {
            fprintf(g_summary_json, ", \"sub\": ");
            json_escape_string(g_summary_json, sub);
        }
        fprintf(g_summary_json, ", \"name\": ");
        json_escape_string(g_summary_json, name);
        fprintf(g_summary_json, ", \"status\": ");
        json_escape_string(g_summary_json, status);
        fprintf(g_summary_json, ", \"time_ms\": %.1f", time_ms);
        if (detail && detail[0]) {
            fprintf(g_summary_json, ", \"detail\": ");
            json_escape_string(g_summary_json, detail);
        }
        fprintf(g_summary_json, "}");
        fflush(g_summary_json);
    }
}

/* ===== Environment Logging (allowlist only) ===== */

/* Only dump env vars matching these prefixes — no credential/path leakage */
static const char *env_allowlist[] = {
    "GALLIUM_", "MESA_", "VIRGL_", "VTEST_", "LIBGL_",
    "ST_DEBUG", "TGSI_", "DISPLAY", "PROOT_",
    "GL_", "EGL_", "WAYLAND_", "XDG_",
    NULL
};

static void log_environment(void) {
    LOG_INFO("=== Environment Variables (allowlisted) ===");

    char env_path[600];
    snprintf(env_path, sizeof(env_path), "%s/environment.json", g_results_dir);
    FILE *env_json = fopen(env_path, "w");
    if (env_json) fprintf(env_json, "{\n");
    int first = 1;

    extern char **environ;
    for (char **env = environ; *env; env++) {
        for (int i = 0; env_allowlist[i]; i++) {
            size_t prefix_len = strlen(env_allowlist[i]);
            if (strncmp(*env, env_allowlist[i], prefix_len) == 0) {
                LOG_INFO("  %s", *env);
                if (env_json) {
                    char *eq = strchr(*env, '=');
                    if (eq) {
                        if (!first) fprintf(env_json, ",\n");
                        first = 0;
                        fprintf(env_json, "  ");
                        /* Write key */
                        fputc('"', env_json);
                        for (const char *p = *env; p < eq; p++) fputc(*p, env_json);
                        fputs("\": ", env_json);
                        json_escape_string(env_json, eq + 1);
                    }
                }
                break;
            }
        }
    }

    if (env_json) {
        fprintf(env_json, "\n}\n");
        fclose(env_json);
    }
}

/* ===== GL Capability Logging ===== */

static void log_gl_caps(void) {
    LOG_INFO("=== GL Capabilities ===");

    char caps_path[600];
    snprintf(caps_path, sizeof(caps_path), "%s/gl-caps.json", g_results_dir);
    FILE *caps = fopen(caps_path, "w");
    if (!caps) {
        LOG_ERROR("Cannot open gl-caps.json");
        return;
    }

    fprintf(caps, "{\n");

    /* String queries */
    const char *vendor = (const char *)glGetString(GL_VENDOR);
    const char *renderer = (const char *)glGetString(GL_RENDERER);
    const char *version = (const char *)glGetString(GL_VERSION);
    const char *glsl_ver = (const char *)glGetString(GL_SHADING_LANGUAGE_VERSION);

    LOG_INFO("  Vendor: %s", vendor ? vendor : "NULL");
    LOG_INFO("  Renderer: %s", renderer ? renderer : "NULL");
    LOG_INFO("  Version: %s", version ? version : "NULL");
    LOG_INFO("  GLSL: %s", glsl_ver ? glsl_ver : "NULL");

    fprintf(caps, "  \"vendor\": "); json_escape_string(caps, vendor); fprintf(caps, ",\n");
    fprintf(caps, "  \"renderer\": "); json_escape_string(caps, renderer); fprintf(caps, ",\n");
    fprintf(caps, "  \"version\": "); json_escape_string(caps, version); fprintf(caps, ",\n");
    fprintf(caps, "  \"glsl_version\": "); json_escape_string(caps, glsl_ver); fprintf(caps, ",\n");

    /* Integer queries */
    struct { GLenum e; const char *name; } int_queries[] = {
        {GL_MAX_TEXTURE_SIZE, "max_texture_size"},
        {GL_MAX_3D_TEXTURE_SIZE, "max_3d_texture_size"},
        {GL_MAX_ARRAY_TEXTURE_LAYERS, "max_array_texture_layers"},
        {GL_MAX_TEXTURE_IMAGE_UNITS, "max_texture_image_units"},
        {GL_MAX_VERTEX_ATTRIBS, "max_vertex_attribs"},
        {GL_MAX_VERTEX_UNIFORM_COMPONENTS, "max_vertex_uniform_components"},
        {GL_MAX_FRAGMENT_UNIFORM_COMPONENTS, "max_fragment_uniform_components"},
        {GL_MAX_UNIFORM_BLOCK_SIZE, "max_uniform_block_size"},
        {GL_MAX_UNIFORM_BUFFER_BINDINGS, "max_uniform_buffer_bindings"},
        {GL_MAX_VARYING_FLOATS, "max_varying_floats"},
        {GL_MAX_DRAW_BUFFERS, "max_draw_buffers"},
        {GL_MAX_COLOR_ATTACHMENTS, "max_color_attachments"},
        {GL_MAX_RENDERBUFFER_SIZE, "max_renderbuffer_size"},
        {GL_MAX_VIEWPORT_DIMS, "max_viewport_dims"},
        {GL_MAX_CLIP_DISTANCES, "max_clip_distances"},
        {GL_MAX_SAMPLES, "max_samples"},
        {GL_MAX_VERTEX_OUTPUT_COMPONENTS, "max_vertex_output_components"},
        {GL_MAX_FRAGMENT_INPUT_COMPONENTS, "max_fragment_input_components"},
        {GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, "max_combined_texture_image_units"},
        {GL_MAX_ELEMENTS_VERTICES, "max_elements_vertices"},
        {GL_MAX_ELEMENTS_INDICES, "max_elements_indices"},
        {GL_DEPTH_BITS, "depth_bits"},
        {GL_STENCIL_BITS, "stencil_bits"},
        {GL_SAMPLE_BUFFERS, "sample_buffers"},
        {GL_SAMPLES, "samples"},
        {GL_SUBPIXEL_BITS, "subpixel_bits"},
    };

    fprintf(caps, "  \"limits\": {\n");
    int num_queries = sizeof(int_queries) / sizeof(int_queries[0]);
    for (int i = 0; i < num_queries; i++) {
        GLint val[2] = {0, 0};
        glGetIntegerv(int_queries[i].e, val);
        /* Clear any errors from unsupported queries */
        while (glGetError() != GL_NO_ERROR) {}
        if (int_queries[i].e == GL_MAX_VIEWPORT_DIMS) {
            LOG_INFO("  %s: %dx%d", int_queries[i].name, val[0], val[1]);
            fprintf(caps, "    \"%s\": [%d, %d]%s\n", int_queries[i].name, val[0], val[1],
                    i < num_queries - 1 ? "," : "");
        } else {
            LOG_INFO("  %s: %d", int_queries[i].name, val[0]);
            fprintf(caps, "    \"%s\": %d%s\n", int_queries[i].name, val[0],
                    i < num_queries - 1 ? "," : "");
        }
    }
    fprintf(caps, "  },\n");

    /* Extensions */
    GLint num_ext = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &num_ext);
    while (glGetError() != GL_NO_ERROR) {}
    LOG_INFO("  Extensions: %d", num_ext);

    fprintf(caps, "  \"num_extensions\": %d,\n", num_ext);
    fprintf(caps, "  \"extensions\": [\n");

    /* Use glGetStringi if available, fall back to glGetString */
    typedef const GLubyte *(*PFNGLGETSTRINGIPROC)(GLenum, GLuint);
    PFNGLGETSTRINGIPROC pglGetStringi = (PFNGLGETSTRINGIPROC)glXGetProcAddressARB((const GLubyte *)"glGetStringi");

    if (pglGetStringi && num_ext > 0) {
        for (int i = 0; i < num_ext; i++) {
            const char *ext = (const char *)pglGetStringi(GL_EXTENSIONS, i);
            if (ext) {
                fprintf(caps, "    ");
                json_escape_string(caps, ext);
                fprintf(caps, "%s\n", i < num_ext - 1 ? "," : "");
            }
        }
    } else {
        /* Fallback: parse space-separated string */
        const char *exts = (const char *)glGetString(GL_EXTENSIONS);
        if (exts) {
            char *copy = strdup(exts);
            char *tok = strtok(copy, " ");
            int first = 1;
            while (tok) {
                if (!first) fprintf(caps, ",\n");
                first = 0;
                fprintf(caps, "    ");
                json_escape_string(caps, tok);
                tok = strtok(NULL, " ");
            }
            fprintf(caps, "\n");
            free(copy);
        }
    }
    fprintf(caps, "  ]\n");
    fprintf(caps, "}\n");
    fclose(caps);
}

/* ===== Function Pointer Logging ===== */

static void log_function_pointer(FILE *f, const char *name, void *ptr, int *first) {
    LOG_INFO("  %s: %s (%p)", name, ptr ? "AVAILABLE" : "NULL", ptr);
    if (f) {
        if (!*first) fprintf(f, ",\n");
        *first = 0;
        fprintf(f, "  \"%s\": {\"available\": %s, \"address\": \"%p\"}",
                name, ptr ? "true" : "false", ptr);
    }
}

/* ===== Signal Handler (crash dumps) ===== */

static void crash_handler(int sig) {
    const char *sig_name = "UNKNOWN";
    switch (sig) {
        case SIGSEGV: sig_name = "SIGSEGV"; break;
        case SIGBUS:  sig_name = "SIGBUS"; break;
        case SIGABRT: sig_name = "SIGABRT"; break;
        case SIGFPE:  sig_name = "SIGFPE"; break;
    }

    /* Write crash info — use write() for signal safety */
    char buf[256];
    int len = snprintf(buf, sizeof(buf),
        "\n[CRASH] Signal %s (%d) received. Results may be incomplete.\n",
        sig_name, sig);
    (void)write(STDERR_FILENO, buf, len);

    /* Try to close JSON cleanly — use write() for signal safety */
    if (g_summary_json) {
        int fd = fileno(g_summary_json);
        char json_buf[128];
        int jlen = snprintf(json_buf, sizeof(json_buf),
            "\n  ],\n  \"crashed\": \"%s\"\n}\n", sig_name);
        (void)write(fd, json_buf, jlen);
        g_summary_json = NULL;
    }
    if (g_log_file) {
        int fd = fileno(g_log_file);
        char log_buf[128];
        int llen = snprintf(log_buf, sizeof(log_buf),
            "\n[CRASH] Signal %s (%d)\n", sig_name, sig);
        (void)write(fd, log_buf, llen);
        g_log_file = NULL;
    }

    /* Re-raise for core dump */
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_signal_handlers(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = crash_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESETHAND; /* One-shot: re-raise goes to default */

    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
}

#endif /* GL_TEST_LOG_H */
