package dev.lumen.platform;

import javafx.application.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Lets a second launch summon the already-running instance instead of dying silently.
 *
 * <p>The first instance holds {@code instance.lock} and listens on loopback; any later
 * launch sends "show" and exits. That gives the user a reliable way in even if the global
 * hotkey could not be registered.
 */
public final class SingleInstance {

    private static final int PORT = 51_733;
    private static final int CONNECT_TIMEOUT_MS = 400;

    private SingleInstance() {
    }

    /** Ask the running instance to open its window. Returns false if nobody answered. */
    public static boolean requestShow() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), CONNECT_TIMEOUT_MS);
            socket.getOutputStream().write("show\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static void listen(Runnable onShow) {
        Thread.ofVirtual().name("instance-listener").start(() -> {
            try (ServerSocket server = new ServerSocket(PORT, 8, InetAddress.getLoopbackAddress())) {
                System.out.println("[instance] listening on 127.0.0.1:" + PORT);
                while (!server.isClosed()) {
                    try (Socket client = server.accept()) {
                        InputStream in = client.getInputStream();
                        String command = new String(in.readNBytes(16), StandardCharsets.UTF_8).trim();
                        if (command.startsWith("show")) {
                            Platform.runLater(onShow);
                        }
                    } catch (IOException ignored) {
                        // a client that disconnects early is harmless
                    }
                }
            } catch (IOException e) {
                System.out.println("[instance] listener unavailable on port " + PORT + ": " + e.getMessage());
            }
        });
    }
}
