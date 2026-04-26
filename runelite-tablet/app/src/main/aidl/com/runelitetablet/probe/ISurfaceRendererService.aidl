// S82 task #26 — Surface-AIDL handoff de-risker (leg 2).
//
// The S82 task #25 probe validated that Mali r44p1 imports cross-process
// AHardwareBuffers as EGLImages. This leg tests the OTHER half of the
// rlawt-on-Surface plan: marshaling an android.view.Surface across a process
// boundary via Binder/AIDL, and exercising the resize/destroy lifecycle on
// the receiver side. Canvas-renders only — EGL was already cleared upstream.
package com.runelitetablet.probe;

import android.view.Surface;

interface ISurfaceRendererService {
    /**
     * Hand off the SurfaceView's Surface to the renderer process. The Surface
     * is parceled by Binder into a fresh native handle on the other side.
     * Idempotent: calling again with a new Surface (or new dimensions) detaches
     * the previous one cleanly and re-attaches.
     */
    void attachSurface(in Surface surface, int width, int height);

    /**
     * Render a single solid-coloured triangle into the attached Surface so the
     * Activity can sample-verify the IPC chain end-to-end.
     */
    void renderTriangle(int argbColor);

    /** Drop the Surface. Returns when the renderer thread has fully released it. */
    void detachSurface();

    /** Sample /proc/self/fd in the renderer process. Activity polls this across
     *  the resize-loop to detect FD leaks on the consumer side. */
    int sampleOpenFdCount();
}
