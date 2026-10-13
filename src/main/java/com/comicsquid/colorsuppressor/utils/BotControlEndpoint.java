package com.comicsquid.colorsuppressor.utils;

import com.comicsquid.colorsuppressor.modules.StepCheckered;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Tiny localhost-only HTTP control endpoint so an external supervisor (automation / agent) can
 * query and start the bot without touching the game:
 *
 *   GET  /status -> JSON status (role, module, state, held map + live pixel dump)
 *   POST /start  -> enable the module (and start the run when idle), idempotent
 *   POST /stop   -> disable the module
 *   POST /holdmap?on=1|0 -> hold or stow the pinned working map
 *   POST /lockmap -> run the configured cartography-table lock machine
 *   POST /cmd?c=<command> -> send a chat command as this bot
 *
 * Binds 127.0.0.1 only. The port auto-increments (42070, 42071, ...) when already in use, so
 * both instances on one machine each get their own endpoint; the chosen port is logged.
 */
public final class BotControlEndpoint {
    public static final int BASE_PORT = 42070;
    public static final int MAX_PORT_OFFSET = 5;

    private static HttpServer server;
    private static int boundPort = -1;

    public interface Controller {
        String statusJson();
        boolean isModuleActive();
        /** Runs on the client thread; must be idempotent and safe when the world is not loaded. */
        void startModule();
        void stopModule();
        /** Optional custom command (e.g. /break2, /breakall master-call triggers). */
        default void customCommand(String command) {}
        default void holdMap(boolean on) {}
        default void lockMap() {}
    }

    /**
     * Soft-dependency entry point used by StepCheckered's constructor reflection hook.
     * This class is a LOCAL tool (git-ignored): fresh clones without it compile and run
     * fine, and the hook in the module is simply skipped.
     */
    public static void startFor(StepCheckered module) {
        start(new Controller() {
            @Override
            public String statusJson() {
                return module.controlStatusJson();
            }

            @Override
            public boolean isModuleActive() {
                return module.isActive();
            }

            @Override
            public void startModule() {
                module.controlStart();
            }

            @Override
            public void stopModule() {
                module.controlStop();
            }

            @Override
            public void holdMap(boolean on) {
                module.controlHoldMap(on);
            }

            @Override
            public void lockMap() {
                module.requestLockMap();
            }

            @Override
            public void customCommand(String command) {
                module.onControlCommand(command);
            }
        });
        int port = getBoundPort();
        if (port > 0) {
            try {
                ChatUtils.info("§a[step-checker] Control endpoint listening on 127.0.0.1:" + port
                    + " (GET /status, POST /start, POST /stop, POST /holdmap, POST /lockmap, POST /cmd?c=<command>)");
            } catch (Exception ignored) {
                // chat not ready during early init - the endpoint itself is up regardless
            }
        }
    }

    public static synchronized void start(Controller controller) {
        if (server != null) return;
        for (int offset = 0; offset <= MAX_PORT_OFFSET; offset++) {
            int port = BASE_PORT + offset;
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                s.createContext("/status", ex -> safeHandle(ex, () -> respond(ex, 200, controller.statusJson())));
                s.createContext("/start", ex -> safeHandle(ex, () -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        respond(ex, 405, "{\"error\":\"POST required\"}");
                        return;
                    }
                    MinecraftClient.getInstance().execute(controller::startModule);
                    respond(ex, 200, "{\"ok\":true,\"action\":\"start\"}");
                }));
                s.createContext("/cmd", ex -> safeHandle(ex, () -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        respond(ex, 405, "{\"error\":\"POST required\"}");
                        return;
                    }
                    String command = null;
                    String query = ex.getRequestURI().getRawQuery();
                    if (query != null) {
                        for (String kv : query.split("&")) {
                            if (kv.startsWith("c=")) {
                                command = java.net.URLDecoder.decode(kv.substring(2), StandardCharsets.UTF_8);
                                break;
                            }
                        }
                    }
                    if (command == null || command.isBlank()) {
                        respond(ex, 400, "{\"error\":\"missing ?c=<command>\"}");
                        return;
                    }
                    final String cmd = command.startsWith("/") ? command.substring(1) : command;
                    MinecraftClient.getInstance().execute(() -> {
                        if (MinecraftClient.getInstance().player != null
                            && MinecraftClient.getInstance().player.networkHandler != null) {
                            MinecraftClient.getInstance().player.networkHandler.sendChatCommand(cmd);
                        }
                    });
                    respond(ex, 200, "{\"ok\":true,\"command\":\"" + cmd + "\"}");
                }));
                s.createContext("/holdmap", ex -> safeHandle(ex, () -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        respond(ex, 405, "{\"error\":\"POST required\"}");
                        return;
                    }
                    String query = ex.getRequestURI().getRawQuery();
                    boolean on = query != null && query.contains("on=1");
                    MinecraftClient.getInstance().execute(() -> controller.holdMap(on));
                    respond(ex, 200, "{\"ok\":true,\"holdMap\":" + on + "}");
                }));
                s.createContext("/lockmap", ex -> safeHandle(ex, () -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        respond(ex, 405, "{\"error\":\"POST required\"}");
                        return;
                    }
                    MinecraftClient.getInstance().execute(controller::lockMap);
                    respond(ex, 200, "{\"ok\":true,\"action\":\"lockmap\"}");
                }));
                s.createContext("/break2", ex -> safeHandle(ex, () -> {
                    MinecraftClient.getInstance().execute(() -> controller.customCommand("break2"));
                    respond(ex, 200, "{\"ok\":true,\"action\":\"break2\"}");
                }));
                s.createContext("/breakall", ex -> safeHandle(ex, () -> {
                    MinecraftClient.getInstance().execute(() -> controller.customCommand("breakall"));
                    respond(ex, 200, "{\"ok\":true,\"action\":\"breakall\"}");
                }));
                s.createContext("/stop", ex -> safeHandle(ex, () -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        respond(ex, 405, "{\"error\":\"POST required\"}");
                        return;
                    }
                    MinecraftClient.getInstance().execute(controller::stopModule);
                    respond(ex, 200, "{\"ok\":true,\"action\":\"stop\"}");
                }));
                s.setExecutor(null);
                s.start();
                server = s;
                boundPort = port;
                return;
            } catch (IOException ignored) {
                // port in use - try the next one (the sibling instance owns it)
            }
        }
    }

    public static synchronized int getBoundPort() {
        return boundPort;
    }

    private interface Handler {
        void run() throws IOException;
    }

    private static void safeHandle(HttpExchange ex, Handler handler) {
        try {
            handler.run();
        } catch (Exception e) {
            try {
                respond(ex, 500, "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
            } catch (IOException ignored) {
            }
        }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
