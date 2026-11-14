package dev.lumen.util;

import dev.lumen.config.Config;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Tees {@code System.out}/{@code System.err} into
 * {@code %LOCALAPPDATA%\Lumen\lumen.log}.
 *
 * <p>A tray app has no console, so without this every failure is invisible: the hotkey
 * that did not register, the window that could not be shown, the index that threw. Existing
 * {@code System.out} calls therefore keep working and are persisted for free.
 *
 * <p>The default uncaught-exception handler is installed here too. A launcher must never lose
 * its tray and hotkey because one callback threw; the stack trace goes to the log and a
 * listener can surface it without letting the exception escape.
 */
public final class Log {

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static boolean installed;
    private static volatile Consumer<Throwable> crashListener = error -> {
    };

    private Log() {
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        installCrashHandler();
        try {
            Files.createDirectories(Config.dataDir());
            Path file = Config.dataDir().resolve("lumen.log");
            rotate(file);

            OutputStream target = new BufferedOutputStream(Files.newOutputStream(
                    file, StandardOpenOption.CREATE, StandardOpenOption.APPEND), 8192);
            PrintStream tee = new PrintStream(new Tee(System.out, target), true, StandardCharsets.UTF_8);
            System.setOut(tee);
            System.setErr(tee);

            tee.println();
            tee.println("--- Lumen started " + Instant.now() + " ---");
        } catch (IOException | RuntimeException e) {
            // Logging must never stop the app from starting.
        }
    }

    public static Path file() {
        return Config.dataDir().resolve("lumen.log");
    }

    /** Called on every uncaught exception, from any thread, after the trace is written. */
    public static void setCrashListener(Consumer<Throwable> listener) {
        crashListener = listener == null ? error -> {
        } : listener;
    }

    private static void installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                System.err.println("[crash] uncaught on " + thread.getName()
                        + ": " + error);
                error.printStackTrace(System.err);
            } catch (Throwable ignored) {
                // The logging tee itself may have failed; nothing else to do.
            }
            try {
                crashListener.accept(error);
            } catch (Throwable ignored) {
                // Never let the reporter escalate a crash.
            }
        });
    }

    private static void rotate(Path file) {
        try {
            if (Files.isRegularFile(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, file.resolveSibling("lumen.log.1"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Keep appending to the existing file.
        }
    }

    private static final class Tee extends OutputStream {

        private final OutputStream console;
        private final OutputStream file;

        Tee(OutputStream console, OutputStream file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(int value) {
            try {
                console.write(value);
            } catch (IOException ignored) {
                // console may be gone; the file still matters
            }
            try {
                file.write(value);
            } catch (IOException ignored) {
                // never let logging break the caller
            }
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            try {
                console.write(buffer, offset, length);
            } catch (IOException ignored) {
                // ignore
            }
            try {
                file.write(buffer, offset, length);
            } catch (IOException ignored) {
                // ignore
            }
        }

        @Override
        public void flush() {
            try {
                console.flush();
            } catch (IOException ignored) {
                // ignore
            }
            try {
                file.flush();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
