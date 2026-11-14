package dev.lumen;

import dev.lumen.util.Log;

/**
 * Plain (non-JavaFX) entry point.
 *
 * <p>Launching a class that extends {@code Application} directly from a classpath can fail
 * with "JavaFX runtime components are missing". A separate launcher avoids that and gives
 * jpackage/DirectClassLoader a stable main class.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");
        Log.install();
        LumenApp.main(args);
    }
}
