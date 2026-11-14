package dev.lumen.platform;

import dev.lumen.config.Config;
import javafx.application.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lets a second launch summon the already-running instance instead of dying silently.
 *
 * <p>The first instance holds {@code instance.lock} and listens on loopback; any later
 * launch sends "show" and exits. That gives the user a reliable way in even if the global
 * hotkey could not be registered.
 *
 * <p>The listener takes the first free port in {@link #FIRST_PORT}..{@code +SPAN} and writes
 * it to {@code instance.port}, so an unrelated app squatting on one port cannot break the
 * handoff. A launch without a port file probes the whole range, which also recovers from a
 * stale file left by a crash.
 */
public final class SingleInstance {

    private static final int FIRST_PORT = 51_733;
    private static final int PORT_SPAN = 10;
    private static final int CONNECT_TIMEOUT_MS = 400;

    private SingleInstance() {
    }

    /** Ask the running instance to open its window. Returns false if nobody answered. */
    public static boolean requestShow() {
        for (int port : candidatePorts()) {
            if (sendShow(port)) {
                return true;
            }
        }
        return false;
    }

    public static void listen(Runnable onShow) {
        Thread.ofVirtual().name("instance-listener").start(() -> {
            for (int port = FIRST_PORT; port < FIRST_PORT + PORT_SPAN; port++) {
                try (ServerSocket server = new ServerSocket(
                        port, 8, InetAddress.getLoopbackAddress())) {
                    System.out.println("[instance] listening on 127.0.0.1:" + port);
                    writePortFile(port);
                    serve(server, onShow);
                    return;
                } catch (IOException e) {
                    // Port taken by something else: try the next one in the range.
                }
            }
            System.err.println("[instance] no free port in " + FIRST_PORT + ".."
                    + (FIRST_PORT + PORT_SPAN - 1)
                    + "; a second launch cannot hand off (the tray icon still opens Lumen)");
        });
    }

    private static void serve(ServerSocket server, Runnable onShow) {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                client.setSoTimeout(500);
                Thread.ofVirtual().start(() -> {
                    try (client) {
                        InputStream in = client.getInputStream();
                        String command = new String(in.readNBytes(16), StandardCharsets.UTF_8).trim();
                        if (command.startsWith("show")) {
                            Platform.runLater(onShow);
                        }
                    } catch (IOException ignored) {
                        // a client that disconnects early or stalls is harmless
                    }
                });
            } catch (IOException ignored) {
                // accept failure; keep listening
            }
        }
    }

    /** The recorded port first, then the whole range so a stale or missing file still works. */
    private static List<Integer> candidatePorts() {
        List<Integer> ports = new ArrayList<>(PORT_SPAN + 1);
        Path file = portFile();
        try {
            if (Files.isRegularFile(file)) {
                int recorded = Integer.parseInt(Files.readString(file).trim());
                if (recorded > 0) {
                    ports.add(recorded);
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // Fall through to the range probe.
        }
        for (int port = FIRST_PORT; port < FIRST_PORT + PORT_SPAN; port++) {
            if (!ports.contains(port)) {
                ports.add(port);
            }
        }
        return ports;
    }

    private static boolean sendShow(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    CONNECT_TIMEOUT_MS);
            socket.getOutputStream().write("show\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Path portFile() {
        return Config.dataDir().resolve("instance.port");
    }

    private static void writePortFile(int port) {
        try {
            Files.createDirectories(Config.dataDir());
            Files.writeString(portFile(), Integer.toString(port));
        } catch (IOException e) {
            // The second launch still probes the range, so this is cosmetic.
        }
    }
}
