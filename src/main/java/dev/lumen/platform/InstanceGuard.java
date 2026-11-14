package dev.lumen.platform;

import dev.lumen.config.Config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * One instance per user session, enforced with a file lock.
 *
 * <p>Called from {@code main} before JavaFX starts, so a second launch can hand off to the
 * running instance in milliseconds instead of paying a full JVM + toolkit start first.
 */
public final class InstanceGuard {

    private static FileChannel channel;
    private static FileLock lock;

    private InstanceGuard() {
    }

    public static synchronized boolean tryAcquire() {
        if (lock != null) {
            return true;
        }
        try {
            Files.createDirectories(Config.dataDir());
            channel = FileChannel.open(Config.dataDir().resolve("instance.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                channel = null;
                return false;
            }
            return true;
        } catch (IOException e) {
            System.out.println("[instance] could not create lock: " + e.getMessage());
            return true;
        }
    }

    public static synchronized void release() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // exiting anyway
            }
            channel = null;
            lock = null;
        }
    }
}
