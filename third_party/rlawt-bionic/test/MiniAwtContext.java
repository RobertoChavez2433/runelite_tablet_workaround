import net.runelite.rlawt.AWTContext;

// Minimal harness to verify rlawt-1.8-bionic.jar's classpath + native load
// path end-to-end under Termux-native openjdk-21.
//
// What it tests:
//   1. AWTContext.class resolves from the jar (classloader sees it)
//   2. AWTContext.loadNatives() runs successfully, which internally:
//      a. System.loadLibrary("jawt")         -> Termux libjawt.so loads
//      b. reads net/runelite/rlawt/linux-aarch64/librlawt.so from the jar
//      c. extracts to a temp file
//      d. System.load(temp) -> Bionic dlopen resolves all NEEDED libs
//   3. The static init block completes without UnsatisfiedLinkError.
//
// What it does NOT test:
//   - Actually creating an AWTContext (requires a java.awt.Component, which
//     requires a live X server). That comes at Phase 3 when launch-runelite
//     -native.sh wires up the full pipeline.
//
// Success output: "AWTContext natives loaded via rlawt-1.8-bionic.jar"
public final class MiniAwtContext {
    public static void main(String[] args) throws Exception {
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("OS:   " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("Classpath entries: " + System.getProperty("java.class.path"));
        System.out.println("Library path:      " + System.getProperty("java.library.path"));
        System.out.println();

        System.out.print("Calling AWTContext.loadNatives()... ");
        AWTContext.loadNatives();
        System.out.println("OK");

        System.out.println("AWTContext natives loaded via rlawt-1.8-bionic.jar");
    }
}
