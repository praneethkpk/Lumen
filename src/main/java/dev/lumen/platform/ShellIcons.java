package dev.lumen.platform;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.PointerByReference;
import dev.lumen.config.Config;
import dev.lumen.core.IndexItem;
import dev.lumen.core.ItemKind;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/**
 * Real Windows shell icons, extracted with {@code SHGetFileInfo} and rendered into JavaFX
 * images.
 *
 * <p>Extraction goes through GDI: the shell hands back an {@code HICON}, which is drawn into
 * a 32-bit top-down DIB section so its pixels can be read directly into a
 * {@link WritableImage}. That avoids pulling in AWT/Swing just to convert an image, and it
 * keeps the icon looking exactly like Explorer's.
 *
 * <p>Everything runs off the JavaFX thread on a single daemon thread, because
 * {@code SHGetFileInfo} can block on a cold or slow file (a network shortcut, a large
 * executable). Rows paint their kind badge immediately and swap in the icon when it is ready,
 * so typing never waits for icon extraction. Results are cached per source path.
 */
public final class ShellIcons {

    private static final int SHGFI_ICON = 0x0000_0100;
    private static final int SHGFI_LARGEICON = 0x0000_0000;
    private static final int DI_NORMAL = 0x0003;
    private static final int DIB_RGB_COLORS = 0;

    /** Magenta sentinel used to recover transparency from icons without an alpha channel. */
    private static final int SENTINEL = 0x00FF_00FF;
    private static final int SENTINEL_RGB = SENTINEL & 0x00FF_FFFF;

    private static final int MAX_CACHE = 512;

    private static final Map<String, Optional<Image>> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<Consumer<Image>>> WAITERS = new HashMap<>();
    private static final ExecutorService EXTRACTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "shell-icons");
        thread.setDaemon(true);
        return thread;
    });

    private ShellIcons() {
    }

    /** Kinds whose results can show a real shell icon. */
    public static boolean supports(ItemKind kind) {
        return kind == ItemKind.APP || kind == ItemKind.FILE
                || kind == ItemKind.FOLDER || kind == ItemKind.WINDOW;
    }

    /** Icon already in memory, or {@code null} when it still has to be extracted. */
    public static Image cached(IndexItem item) {
        String key = keyFor(item);
        if (key == null) {
            return null;
        }
        Optional<Image> found = CACHE.get(key);
        return found == null ? null : found.orElse(null);
    }

    /**
     * Extracts an icon in the background and hands it to {@code onReady} on the JavaFX
     * thread. The callback is skipped when extraction fails; the caller's kind badge remains.
     */
    public static void request(IndexItem item, Consumer<Image> onReady) {
        if (!supports(item.kind())) {
            return;
        }
        String source = sourceFor(item);
        if (source == null) {
            return;
        }
        String key = cacheKey(source);

        // Two visible rows can share one icon (two Chrome windows, two .pdf files). The
        // second caller registers as a waiter instead of starting a duplicate extraction.
        synchronized (WAITERS) {
            Optional<Image> known = CACHE.get(key);
            if (known != null) {
                known.ifPresent(image -> runOnFxThread(() -> onReady.accept(image)));
                return;
            }
            WAITERS.computeIfAbsent(key, ignored -> new ArrayList<>(2)).add(onReady);
            if (!PENDING.add(key)) {
                return;
            }
        }

        EXTRACTOR.execute(() -> {
            Image image = null;
            Path cacheFile = cacheFile(key);
            try {
                if (cacheFile != null && Files.isRegularFile(cacheFile)) {
                    Image loaded = new Image(cacheFile.toUri().toString(), false);
                    if (!loaded.isError() && loaded.getWidth() > 0 && loaded.getPixelReader() != null) {
                        image = loaded;
                    }
                }
                if (image == null) {
                    image = extract(source);
                    if (image != null && cacheFile != null) {
                        writeImage(cacheFile, image);
                    }
                }
            } catch (Throwable t) {
                System.err.println("[icons] could not extract " + source + ": " + t.getMessage());
            }

            List<Consumer<Image>> waiters;
            synchronized (WAITERS) {
                if (CACHE.size() > MAX_CACHE) {
                    CACHE.clear();
                }
                CACHE.put(key, Optional.ofNullable(image));
                waiters = WAITERS.remove(key);
                PENDING.remove(key);
            }

            if (image != null && waiters != null) {
                Image ready = image;
                runOnFxThread(() -> waiters.forEach(waiter -> waiter.accept(ready)));
            }
        });
    }

    /** The FX toolkit may already be shutting down when a late icon arrives. */
    private static void runOnFxThread(Runnable action) {
        try {
            Platform.runLater(action);
        } catch (IllegalStateException ignored) {
            // Application is exiting; the image is cached and unused.
        }
    }

    private static String keyFor(IndexItem item) {
        String source = sourceFor(item);
        return source == null ? null : cacheKey(source);
    }

    /** The file whose icon represents this item. Windows results use the app executable. */
    private static String sourceFor(IndexItem item) {
        if (item.kind() == ItemKind.WINDOW) {
            long handle;
            try {
                handle = Long.parseLong(item.path());
            } catch (NumberFormatException e) {
                return null;
            }
            String exe = OpenWindows.processPath(handle);
            return exe == null || exe.isBlank() ? null : exe;
        }
        String path = item.path();
        return path == null || path.isBlank() ? null : path;
    }

    private static String cacheKey(String source) {
        String base = source.toLowerCase(Locale.ROOT);
        try {
            Path path = Path.of(source);
            if (Files.isRegularFile(path)) {
                base += "|" + Files.getLastModifiedTime(path).toMillis()
                        + "|" + Files.size(path);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return base;
    }

    private static Path cacheFile(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                name.append(String.format("%02x", value));
            }
            return Config.dataDir().resolve("icons").resolve(name + ".png");
        } catch (RuntimeException ignored) {
            return null;
        } catch (java.security.NoSuchAlgorithmException ignored) {
            return null;
        }
    }

    private static void writeImage(Path file, Image image) {
        if (file == null || image == null || image.getPixelReader() == null) {
            return;
        }
        try {
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            PixelReader reader = image.getPixelReader();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    buffered.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            Files.createDirectories(file.getParent());
            ImageIO.write(buffered, "png", file.toFile());
        } catch (IOException | RuntimeException ignored) {
            // Disk cache is best-effort; the in-memory icon is still used.
        }
    }

    /** Visible for the icon test: runs the full Win32 extraction path synchronously. */
    static Image extract(String path) {
        Win32Ext.SHFILEINFO info = new Win32Ext.SHFILEINFO();
        WinDef.UINT_PTR result = Win32Ext.Shell32Ex.INSTANCE.SHGetFileInfo(
                new WString(path), 0, info, info.size(), SHGFI_ICON | SHGFI_LARGEICON);
        if (result.longValue() == 0L || info.hIcon == null) {
            return null;
        }
        try {
            int width = Math.max(16, User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXICON));
            int height = Math.max(16, User32.INSTANCE.GetSystemMetrics(WinUser.SM_CYICON));
            return toImage(info.hIcon, width, height);
        } finally {
            User32.INSTANCE.DestroyIcon(info.hIcon);
        }
    }

    /**
     * Draws the icon into a 32-bit DIB and copies the pixels out.
     *
     * <p>Modern icons carry an alpha channel and can be used as drawn. Icons from before
     * Windows XP only have an AND mask, which lands as alpha 0 everywhere; for those a second
     * pass draws over a magenta sentinel so transparency can be recovered from the colour.
     */
    private static Image toImage(HICON icon, int width, int height) {
        HDC screen = User32.INSTANCE.GetDC(null);
        if (screen == null) {
            return null;
        }
        HDC memory = GDI32.INSTANCE.CreateCompatibleDC(screen);
        if (memory == null) {
            User32.INSTANCE.ReleaseDC(null, screen);
            return null;
        }

        HBITMAP dib = null;
        WinNT.HANDLE previous = null;
        try {
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biSize = bmi.bmiHeader.size();
            bmi.bmiHeader.biWidth = width;
            bmi.bmiHeader.biHeight = -height; // negative: top-down row order
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
            bmi.bmiHeader.write();

            PointerByReference bitsRef = new PointerByReference();
            dib = GDI32.INSTANCE.CreateDIBSection(memory, bmi, DIB_RGB_COLORS, bitsRef, null, 0);
            if (dib == null) {
                return null;
            }
            Pointer bits = bitsRef.getValue();
            if (bits == null) {
                return null;
            }

            previous = GDI32.INSTANCE.SelectObject(memory, dib);
            if (!draw(memory, icon, width, height)) {
                return null;
            }

            int[] pixels = bits.getIntArray(0, width * height);
            if (!hasAlpha(pixels)) {
                fill(bits, width * height, SENTINEL);
                if (!draw(memory, icon, width, height)) {
                    return null;
                }
                pixels = bits.getIntArray(0, width * height);
                for (int i = 0; i < pixels.length; i++) {
                    int rgb = pixels[i] & 0x00FF_FFFF;
                    pixels[i] = rgb == SENTINEL_RGB ? 0 : 0xFF00_0000 | rgb;
                }
            }

            WritableImage image = new WritableImage(width, height);
            image.getPixelWriter().setPixels(0, 0, width, height,
                    PixelFormat.getIntArgbInstance(), pixels, 0, width);
            return image;
        } finally {
            if (previous != null) {
                GDI32.INSTANCE.SelectObject(memory, previous);
            }
            if (dib != null) {
                GDI32.INSTANCE.DeleteObject(dib);
            }
            GDI32.INSTANCE.DeleteDC(memory);
            User32.INSTANCE.ReleaseDC(null, screen);
        }
    }

    private static boolean draw(HDC dc, HICON icon, int width, int height) {
        return Win32Ext.User32Ex.INSTANCE.DrawIconEx(dc, 0, 0, icon, width, height, 0, null,
                DI_NORMAL);
    }

    private static boolean hasAlpha(int[] pixels) {
        for (int pixel : pixels) {
            if ((pixel >>> 24) != 0) {
                return true;
            }
        }
        return false;
    }

    private static void fill(Pointer bits, int count, int value) {
        int[] buffer = new int[count];
        java.util.Arrays.fill(buffer, value);
        bits.write(0, buffer, 0, count);
    }
}
