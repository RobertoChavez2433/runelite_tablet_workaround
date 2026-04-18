/*
 * glx-fbconfig-probe.c — reproduce rlawt's glXChooseFBConfig call and dump
 * every returned fbconfig's attributes.
 *
 * Build (Termux-native, runs under Bionic against Termux Mesa):
 *   clang -O0 -g glx-fbconfig-probe.c -lX11 -lGL -lGLX -o glx-fbconfig-probe
 *
 * Build (Ubuntu-proot, runs under glibc against Ubuntu Mesa):
 *   proot-distro login ubuntu -- bash -c "apt-get install -y libgl-dev libglx-dev libx11-dev clang && \
 *       clang -O0 -g glx-fbconfig-probe.c -lX11 -lGL -lGLX -o /root/glx-fbconfig-probe-ubuntu"
 *
 * Run:
 *   DISPLAY=:0 ./glx-fbconfig-probe
 *
 * Expected output: for each FBConfig returned, 15 attributes including
 * GLX_VISUAL_ID, GLX_X_RENDERABLE, GLX_DRAWABLE_TYPE, GLX_RENDER_TYPE,
 * GLX_DOUBLEBUFFER, RGBA sizes, DEPTH, STENCIL, SAMPLES.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <X11/Xlib.h>
#include <GL/glx.h>

static void print_attr(Display *dpy, GLXFBConfig c, int attr, const char *name) {
    int v = -999;
    int rc = glXGetFBConfigAttrib(dpy, c, attr, &v);
    if (rc != 0) {
        printf("    %-22s = <ERR rc=%d>\n", name, rc);
    } else {
        printf("    %-22s = %d (0x%x)\n", name, v, v);
    }
}

int main(int argc, char **argv) {
    Display *dpy = XOpenDisplay(NULL);
    if (!dpy) {
        fprintf(stderr, "FAIL: XOpenDisplay returned NULL\n");
        return 1;
    }
    int screen = DefaultScreen(dpy);

    int major, minor;
    if (glXQueryVersion(dpy, &major, &minor)) {
        printf("GLX version (client-negotiated): %d.%d\n", major, minor);
    }
    const char *server_vendor = glXQueryServerString(dpy, screen, GLX_VENDOR);
    const char *server_ver    = glXQueryServerString(dpy, screen, GLX_VERSION);
    const char *client_vendor = glXGetClientString(dpy, GLX_VENDOR);
    const char *client_ver    = glXGetClientString(dpy, GLX_VERSION);
    printf("server vendor : %s\n", server_vendor ? server_vendor : "(null)");
    printf("server version: %s\n", server_ver ? server_ver : "(null)");
    printf("client vendor : %s\n", client_vendor ? client_vendor : "(null)");
    printf("client version: %s\n", client_ver ? client_ver : "(null)");
    printf("\n");

    /* rlawt's default ctx values (see AWTContext.java defaults for RL):
     *   alphaDepth=0, depthDepth=24, stencilDepth=8, multisamples=0 */
    int alphaDepth = 0;
    int depthDepth = 24;
    int stencilDepth = 8;
    int multisamples = 0;

    for (int db = 0; db < 2; db++) {
        int doubleBuffered = (db == 0) ? 1 : 0;
        printf("=== glXChooseFBConfig DB=%d ===\n", doubleBuffered);

        int attribs[] = {
            GLX_RENDER_TYPE,     GLX_RGBA_BIT,
            GLX_DRAWABLE_TYPE,   GLX_WINDOW_BIT,
            GLX_X_VISUAL_TYPE,   GLX_TRUE_COLOR,
            GLX_X_RENDERABLE,    True,
            GLX_RED_SIZE,        8,
            GLX_GREEN_SIZE,      8,
            GLX_BLUE_SIZE,       8,
            GLX_ALPHA_SIZE,      alphaDepth,
            GLX_DEPTH_SIZE,      depthDepth,
            GLX_STENCIL_SIZE,    stencilDepth,
            GLX_SAMPLE_BUFFERS,  multisamples > 0,
            GLX_SAMPLES,         multisamples,
            GLX_DOUBLEBUFFER,    doubleBuffered,
            None
        };

        int nConfigs = 0;
        GLXFBConfig *fbConfigs = glXChooseFBConfig(dpy, screen, attribs, &nConfigs);
        printf("returned nConfigs=%d, ptr=%p\n", nConfigs, (void*)fbConfigs);
        if (!fbConfigs) {
            printf("  -> NULL (rlawt would `continue` and try next DB value)\n\n");
            continue;
        }

        for (int i = 0; i < nConfigs && i < 16; i++) {
            printf("  fbconfig[%d]:\n", i);
            print_attr(dpy, fbConfigs[i], GLX_VISUAL_ID,      "GLX_VISUAL_ID");
            print_attr(dpy, fbConfigs[i], GLX_FBCONFIG_ID,    "GLX_FBCONFIG_ID");
            print_attr(dpy, fbConfigs[i], GLX_RENDER_TYPE,    "GLX_RENDER_TYPE");
            print_attr(dpy, fbConfigs[i], GLX_DRAWABLE_TYPE,  "GLX_DRAWABLE_TYPE");
            print_attr(dpy, fbConfigs[i], GLX_X_RENDERABLE,   "GLX_X_RENDERABLE");
            print_attr(dpy, fbConfigs[i], GLX_X_VISUAL_TYPE,  "GLX_X_VISUAL_TYPE");
            print_attr(dpy, fbConfigs[i], GLX_CONFIG_CAVEAT,  "GLX_CONFIG_CAVEAT");
            print_attr(dpy, fbConfigs[i], GLX_DOUBLEBUFFER,   "GLX_DOUBLEBUFFER");
            print_attr(dpy, fbConfigs[i], GLX_RED_SIZE,       "GLX_RED_SIZE");
            print_attr(dpy, fbConfigs[i], GLX_GREEN_SIZE,     "GLX_GREEN_SIZE");
            print_attr(dpy, fbConfigs[i], GLX_BLUE_SIZE,      "GLX_BLUE_SIZE");
            print_attr(dpy, fbConfigs[i], GLX_ALPHA_SIZE,     "GLX_ALPHA_SIZE");
            print_attr(dpy, fbConfigs[i], GLX_DEPTH_SIZE,     "GLX_DEPTH_SIZE");
            print_attr(dpy, fbConfigs[i], GLX_STENCIL_SIZE,   "GLX_STENCIL_SIZE");
            print_attr(dpy, fbConfigs[i], GLX_SAMPLE_BUFFERS, "GLX_SAMPLE_BUFFERS");
            print_attr(dpy, fbConfigs[i], GLX_SAMPLES,        "GLX_SAMPLES");
        }
        if (nConfigs > 16) {
            printf("  ... (%d more fbconfigs suppressed)\n", nConfigs - 16);
        }
        XFree(fbConfigs);
        printf("\n");
    }

    /* Also dump the default visual from the root window — this is closest
     * to what JAWT would hand rlawt as dspi->visualID. */
    Visual *defVisual = DefaultVisual(dpy, screen);
    VisualID defVid = XVisualIDFromVisual(defVisual);
    printf("DefaultVisual VID = %lu (0x%lx)\n", defVid, defVid);

    XCloseDisplay(dpy);
    return 0;
}
