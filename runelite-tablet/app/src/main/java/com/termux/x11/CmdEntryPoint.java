package com.termux.x11;

import static android.system.Os.getenv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minimal in-app fork of the upstream Termux:X11 CmdEntryPoint.
 *
 * This lets Termux launch the Xlorie server from our APK through app_process so
 * we can keep the Java-side entrypoint in our package while still reusing the
 * stock native Xlorie library as a donor dependency.
 */
@Keep
@SuppressLint({"StaticFieldLeak", "UnsafeDynamicallyLoadedCode"})
public class CmdEntryPoint extends ICmdEntryInterface.Stub {
    public static final String ACTION_START = "com.termux.x11.CmdEntryPoint.ACTION_START";
    private static final String DEFAULT_TARGET_PACKAGE = "com.runelitetablet";
    private static final String DONOR_PACKAGE = "com.termux.x11";
    private static final String XLORIE_SO = "libXlorie.so";

    static final Handler handler;
    public static Context ctx;

    private final Intent intent = createIntent();

    public static void main(String[] args) {
        Log.i("CmdEntryPoint", "starting in-app CmdEntryPoint");
        handler.post(() -> new CmdEntryPoint(args));
        Looper.loop();
    }

    CmdEntryPoint(String[] args) {
        if (!start(args)) {
            System.exit(1);
        }

        spawnListeningThread();
        sendBroadcastDelayed();
    }

    @SuppressLint({"WrongConstant", "PrivateApi"})
    private Intent createIntent() {
        String targetPackage = getenv("TERMUX_X11_OVERRIDE_PACKAGE");
        if (targetPackage == null || targetPackage.isEmpty()) {
            targetPackage = DEFAULT_TARGET_PACKAGE;
        }

        Bundle bundle = new Bundle();
        bundle.putBinder(null, this);

        Intent nextIntent = new Intent(ACTION_START);
        nextIntent.putExtra(null, bundle);
        nextIntent.setPackage(targetPackage);
        return nextIntent;
    }

    private void sendBroadcastDelayed() {
        if (!connected()) {
            sendBroadcast(intent);
        }
        handler.postDelayed(this::sendBroadcastDelayed, 1000);
    }

    // The donor native bridge resolves and calls CmdEntryPoint.sendBroadcast() via JNI.
    // Keep this zero-arg instance method aligned with upstream so internal-hybrid
    // synthetic probes do not abort when the native listener accepts a client.
    private void sendBroadcast() {
        sendBroadcast(intent);
    }

    void spawnListeningThread() {
        new Thread(this::listenForConnections).start();
    }

    static void sendBroadcast(Intent intent) {
        try {
            ctx.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e("Broadcast", "Failed to broadcast ACTION_START through system context", e);
        }
    }

    /** @noinspection DataFlowIssue*/
    @SuppressLint("DiscouragedPrivateApi")
    public static Context createContext() {
        Context context;
        PrintStream err = System.err;
        try {
            java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int arg0) {}
            }));
            if (System.getenv("OLD_CONTEXT") != null) {
                Object activityThread = Class.forName("android.app.ActivityThread")
                    .getMethod("systemMain")
                    .invoke(null);
                context = (Context) activityThread.getClass().getMethod("getSystemContext").invoke(activityThread);
            } else {
                Object activityThread = Class.forName("sun.misc.Unsafe")
                    .getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, Class.forName("android.app.ActivityThread"));
                context = (Context) activityThread.getClass().getMethod("getSystemContext").invoke(activityThread);
            }
        } catch (Exception e) {
            Log.e("Context", "Failed to instantiate context", e);
            context = null;
        } finally {
            System.setErr(err);
        }
        return context;
    }

    private static ApplicationInfo getDonorApplicationInfo() {
        return getApplicationInfo(DONOR_PACKAGE);
    }

    private static ApplicationInfo getApplicationInfo(String packageName) {
        if (ctx == null) {
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return ctx.getPackageManager().getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                );
            }
            return ctx.getPackageManager().getApplicationInfo(packageName, 0);
        } catch (Exception e) {
            Log.e("CmdEntryPoint", "Failed to resolve application info for " + packageName, e);
            e.printStackTrace(System.err);
            return null;
        }
    }

    private static String[] getSupportedAbis() {
        String[] abis = Build.SUPPORTED_ABIS;
        return (abis != null && abis.length > 0) ? abis : new String[] {"arm64-v8a"};
    }

    private static String tryNativeLibraryDir(ApplicationInfo appInfo, String packageName) {
        if (appInfo == null || appInfo.nativeLibraryDir == null) {
            return null;
        }
        File packagedLib = new File(appInfo.nativeLibraryDir, XLORIE_SO);
        if (packagedLib.exists() && packagedLib.canRead()) {
            System.err.println("Using packaged native library for " + packageName + ": " + packagedLib.getAbsolutePath());
            return packagedLib.getAbsolutePath();
        }
        return null;
    }

    private static File getWritableRuntimeDir() {
        String tmpDir = System.getenv("TMPDIR");
        if (tmpDir != null && !tmpDir.isEmpty()) {
            return new File(tmpDir);
        }

        String homeDir = System.getenv("HOME");
        if (homeDir != null && !homeDir.isEmpty()) {
            return new File(homeDir);
        }

        return new File("/data/data/com.termux/files/usr/tmp");
    }

    private static String tryExtractLibrary(ApplicationInfo appInfo, String packageName) {
        if (appInfo == null || appInfo.sourceDir == null) {
            return null;
        }

        File extractionDir = new File(getWritableRuntimeDir(), "termux-x11-" + packageName + "-libs");
        if (!extractionDir.exists() && !extractionDir.mkdirs()) {
            System.err.println("Failed to create donor extraction dir: " + extractionDir);
            return null;
        }

        try (ZipFile zipFile = new ZipFile(appInfo.sourceDir)) {
            for (String abi : getSupportedAbis()) {
                String entryName = "lib/" + abi + "/" + XLORIE_SO;
                ZipEntry entry = zipFile.getEntry(entryName);
                if (entry == null) {
                    continue;
                }

                File extractedLib = new File(extractionDir, abi + "-" + XLORIE_SO);
                try (InputStream in = zipFile.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(extractedLib, false)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }

                extractedLib.setReadable(true, false);
                extractedLib.setExecutable(true, false);
                System.err.println(
                    "Extracted Xlorie library for " + packageName + " from " + appInfo.sourceDir +
                        " entry=" + entryName +
                        " to " + extractedLib.getAbsolutePath()
                );
                return extractedLib.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e("CmdEntryPoint", "Failed to extract Xlorie library for " + packageName + " from APK", e);
            e.printStackTrace(System.err);
            return null;
        }

        System.err.println("APK for " + packageName + " did not contain a supported Xlorie ABI entry");
        return null;
    }

    private static String resolveApplicationLibraryPath(String packageName) {
        ApplicationInfo appInfo = getApplicationInfo(packageName);
        if (appInfo == null) {
            return null;
        }

        String packaged = tryNativeLibraryDir(appInfo, packageName);
        if (packaged != null) {
            return packaged;
        }

        return tryExtractLibrary(appInfo, packageName);
    }

    private static String resolveDonorLibraryPath() {
        String ownLibraryPath = resolveApplicationLibraryPath(DEFAULT_TARGET_PACKAGE);
        if (ownLibraryPath != null) {
            return ownLibraryPath;
        }

        return resolveApplicationLibraryPath(DONOR_PACKAGE);
    }

    public static native boolean start(String[] args);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    private static native boolean connected();
    private native void listenForConnections();

    static {
        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
        } catch (Exception e) {
            Log.e("CmdEntryPoint", "Something went wrong when preparing MainLooper", e);
        }

        handler = new Handler();
        ctx = createContext();

        String donorLibPath = resolveDonorLibraryPath();
        if (donorLibPath == null) {
            System.err.println("Failed to locate donor Xlorie library in " + DONOR_PACKAGE);
            System.exit(134);
        }

        File donorLibFile = new File(donorLibPath);
        System.err.println(
            "CmdEntryPoint donor library candidate path=" + donorLibPath +
                " exists=" + donorLibFile.exists() +
                " canRead=" + donorLibFile.canRead()
        );
        File donorLibDir = donorLibFile.getParentFile();
        if (donorLibDir != null) {
            File[] donorDirEntries = donorLibDir.listFiles();
            if (donorDirEntries == null) {
                System.err.println("CmdEntryPoint donor library dir listing unavailable: " + donorLibDir);
            } else {
                System.err.println("CmdEntryPoint donor library dir=" + donorLibDir + " entries=" + donorDirEntries.length);
                for (File entry : donorDirEntries) {
                    System.err.println(
                        "  " + entry.getName() +
                            " exists=" + entry.exists() +
                            " canRead=" + entry.canRead()
                    );
                }
            }
        }

        try {
            System.load(donorLibPath);
            Log.i("CmdEntryPoint", "Loaded donor Xlorie library from " + donorLibPath);
        } catch (Throwable e) {
            Log.e("CmdEntryPoint", "Failed to load donor Xlorie library from " + donorLibPath, e);
            System.err.println("Failed to load donor Xlorie library from " + donorLibPath + ": " + e);
            e.printStackTrace(System.err);
            System.exit(134);
        }
    }
}
