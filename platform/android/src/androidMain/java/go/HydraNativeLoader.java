package go;

/**
 * The seam HydraCore's generated {@code go.Seq} loads its native library through.
 *
 * <p>This class is not optional and it is not ours to rename. The Android artifact of
 * HydraCore is post-processed at build time ({@code cmd/internal/build_libbox/android_loader_patch.go}):
 * the generated {@code go.Seq} static initialiser is rewritten from
 * {@code System.loadLibrary("box")} to {@code HydraNativeLoader.loadLibrary("box")}. A build
 * that bundles the AAR without providing this class therefore fails on the first touch of any
 * {@code Libbox} symbol, with {@code NoClassDefFoundError: go.HydraNativeLoader} — which is
 * every path in the app: opening an encrypted subscription, checking a configuration, and
 * starting the core. 1.x ships the same file at
 * {@code android/app/src/main/java/go/HydraNativeLoader.java}; the 2.0 alpha did not port it,
 * and that single missing file is what made the alpha look like it had no working core.</p>
 *
 * <p>1.x additionally verifies a downloaded core against a signed digest before loading it,
 * because it can replace the core at runtime. 2.0 ships exactly one core inside the APK, so
 * the candidate machinery is deliberately not carried over: there is nothing to choose
 * between, and a verification path with no second candidate is code that cannot be exercised.
 * The class keeps no Android dependency, so it can be loaded from any process at any time.</p>
 */
public final class HydraNativeLoader {
    private static final Object LOCK = new Object();

    private static volatile boolean loaded;

    private HydraNativeLoader() {}

    /** Called from the patched generated {@code go.Seq} static initializer. */
    public static void loadLibrary(String name) {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            System.loadLibrary(name);
            loaded = true;
        }
    }

    /** Which copy of the core is in memory. One answer today, kept for diagnostics. */
    public static String loadedSource() {
        return loaded ? "embedded" : "none";
    }
}
