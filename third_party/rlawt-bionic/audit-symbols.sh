#!/data/data/com.termux/files/usr/bin/sh
# Run on device inside Termux. Audits whether each undefined symbol from
# librlawt.so is exported by the expected provider .so on this device.

set -u
PREFIX=/data/data/com.termux/files/usr

echo "=== libc (Bionic /system/lib64/libc.so) ==="
for sym in __cxa_atexit __cxa_finalize __register_atfork __stack_chk_fail calloc free strstr vsnprintf; do
    if /system/bin/readelf -Ws /system/lib64/libc.so 2>/dev/null | grep -q " $sym$"; then
        echo "  FOUND  $sym"
    else
        echo "  MISSING $sym"
    fi
done

echo ""
echo "=== libGL (Termux $PREFIX/lib/libGL.so) ==="
for sym in glFinish glXChooseFBConfig glXCreateNewContext glXDestroyContext glXGetFBConfigAttrib glXGetProcAddress glXGetProcAddressARB glXMakeCurrent glXQueryExtension glXQueryExtensionsString glXSwapBuffers; do
    if /system/bin/readelf -Ws "$PREFIX/lib/libGL.so" 2>/dev/null | grep -q " $sym$"; then
        echo "  FOUND  $sym"
    else
        # GL dispatch may route through libGLdispatch — check that too
        if /system/bin/readelf -Ws "$PREFIX/lib/libGLdispatch.so.0" 2>/dev/null | grep -q " $sym$"; then
            echo "  FOUND  $sym (via libGLdispatch)"
        elif /system/bin/readelf -Ws "$PREFIX/lib/libGLX.so.0" 2>/dev/null | grep -q " $sym$"; then
            echo "  FOUND  $sym (via libGLX)"
        else
            echo "  MISSING $sym"
        fi
    fi
done

echo ""
echo "=== libX11 ($PREFIX/lib/libX11.so) ==="
for sym in XCloseDisplay XDisplayString XFree XOpenDisplay XSetErrorHandler XSync; do
    if /system/bin/readelf -Ws "$PREFIX/lib/libX11.so" 2>/dev/null | grep -q " $sym$"; then
        echo "  FOUND  $sym"
    else
        echo "  MISSING $sym"
    fi
done

echo ""
echo "=== libjawt ($PREFIX/lib/jvm/java-21-openjdk/lib/libjawt.so) ==="
for sym in JAWT_GetAWT; do
    if /system/bin/readelf -Ws "$PREFIX/lib/jvm/java-21-openjdk/lib/libjawt.so" 2>/dev/null | grep -q " $sym$"; then
        echo "  FOUND  $sym"
    else
        echo "  MISSING $sym"
    fi
done
