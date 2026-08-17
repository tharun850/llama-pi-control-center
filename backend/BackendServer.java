package backend;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Ultra-lightweight Zero-Dependency Java 21 Backend
 * Controls llama-server.exe process lifecycle, streaming logs via SSE,
 * scans GGUF models, manages OpenCode projects, and launches interactive terminals.
 */
public class BackendServer {

    private static final int BACKEND_PORT = 8765;
    private static final Path BASE_DIR = Paths.get("C:\\AI");
    private static final Path CONFIG_FILE = BASE_DIR.resolve("localConfig").resolve("llama_config.json");
    private static final Path DEFAULT_MODELS_DIR = BASE_DIR.resolve("Models");
    private static final Path DEFAULT_PROJECTS_DIR = BASE_DIR.resolve("Projects");
    private static final List<Path> STATIC_DIRS = List.of(
            Paths.get("C:\\AI\\localConfig\\frontend\\dist\\frontend\\browser"),
            Paths.get("frontend/dist/frontend/browser"),
            Paths.get("../frontend/dist/frontend/browser"),
            Paths.get("dist/browser"),
            Paths.get("static")
    );

    // High-performance In-Memory Static File Cache with Pre-computed GZIP
    private static final ConcurrentHashMap<String, CachedStaticFile> STATIC_CACHE = new ConcurrentHashMap<>();

    private static class CachedStaticFile {
        final byte[] rawBytes;
        final byte[] gzipBytes;
        final String mimeType;
        final long lastModified;

        CachedStaticFile(byte[] rawBytes, byte[] gzipBytes, String mimeType, long lastModified) {
            this.rawBytes = rawBytes;
            this.gzipBytes = gzipBytes;
            this.mimeType = mimeType;
            this.lastModified = lastModified;
        }
    }

    private static byte[] gzipCompress(byte[] uncompressed) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(uncompressed);
            gzos.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            return uncompressed;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Collections.emptyMap();
    }

    private final LlamaProcessManager processManager = new LlamaProcessManager();
    private final LogManager logManager = new LogManager();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public static void main(String[] args) {
        try {
            BackendServer server = new BackendServer();
            server.start();
        } catch (Exception e) {
            System.err.println("[BackendServer] Fatal error starting server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private HttpServer httpServer;

    public void start() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", BACKEND_PORT), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Register REST and SSE Endpoints
        httpServer.createContext("/api/status", this::handleStatus);
        httpServer.createContext("/api/server/start", this::handleServerStart);
        httpServer.createContext("/api/server/stop", this::handleServerStop);
        httpServer.createContext("/api/server/restart", this::handleServerRestart);
        httpServer.createContext("/api/server/logs", this::handleServerLogsSse);
        httpServer.createContext("/api/server/logs/poll", this::handleServerLogsPoll);
        httpServer.createContext("/api/server/logs/clear", this::handleServerLogsClear);

        httpServer.createContext("/api/backend/shutdown", this::handleBackendShutdown);

        httpServer.createContext("/api/models/scan", this::handleScanModels);
        httpServer.createContext("/api/config/llama", this::handleLlamaConfig);
        httpServer.createContext("/api/config/llama/presets", this::handlePresets);

        httpServer.createContext("/api/projects/list", this::handleProjectsList);
        httpServer.createContext("/api/projects/create", this::handleProjectsCreate);
        httpServer.createContext("/api/projects/pi/inject", this::handleOpenCodeInject);
        httpServer.createContext("/api/projects/pi/start", this::handlePiStartTerminal);
        httpServer.createContext("/api/projects/opencode/inject", this::handleOpenCodeInject);
        httpServer.createContext("/api/projects/opencode/start", this::handlePiStartTerminal);
        httpServer.createContext("/api/projects/open-explorer", this::handleOpenExplorer);
        httpServer.createContext("/api/projects/open-vscode", this::handleOpenVsCode);

        httpServer.createContext("/api/chat/test", this::handleChatTest);
        httpServer.createContext("/api/system/paths", this::handleSystemPaths);

        // Serve Angular Single-Page-Application (SPA) on root "/"
        httpServer.createContext("/", this::handleStaticFiles);

        // Periodic Health Check background task
        scheduler.scheduleAtFixedRate(this::performHealthCheck, 2, 2, TimeUnit.SECONDS);

        httpServer.start();
        System.out.println("=========================================================================");
        System.out.println(" Llama Server & Pi Control Center");
        System.out.println(" Unified Server (Angular 21 UI + Java 21 Backend) is LIVE");
        System.out.println(" Open in browser: http://127.0.0.1:" + BACKEND_PORT);
        System.out.println("=========================================================================");
    }

    // =========================================================================
    // HTTP Request Handlers
    // =========================================================================

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }

        Map<String, Object> status = processManager.getStatus();
        sendResponse(exchange, 200, SimpleJson.toJson(status));
    }

    private void handleServerStart(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }

        String body = readBody(exchange);
        Map<String, Object> config = SimpleJson.parseObject(body);
        if (config == null || config.isEmpty()) {
            config = loadLlamaConfig();
        }

        try {
            Map<String, Object> result = processManager.start(config, logManager);
            // Save active config
            saveLlamaConfig(config);
            sendResponse(exchange, 200, SimpleJson.toJson(result));
        } catch (Exception e) {
            sendResponse(exchange, 400, jsonError("Failed to start server: " + e.getMessage()));
        }
    }

    private void handleServerStop(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }

        try {
            Map<String, Object> result = processManager.stop();
            logManager.appendLog("[SYSTEM] Llama Server stopped by user request.");
            sendResponse(exchange, 200, SimpleJson.toJson(result));
        } catch (Exception e) {
            sendResponse(exchange, 500, jsonError("Failed to stop server: " + e.getMessage()));
        }
    }

    private void handleServerRestart(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }

        String body = readBody(exchange);
        Map<String, Object> config = SimpleJson.parseObject(body);
        if (config == null || config.isEmpty()) {
            config = loadLlamaConfig();
        }

        try {
            processManager.stop();
            Thread.sleep(1000);
            Map<String, Object> result = processManager.start(config, logManager);
            saveLlamaConfig(config);
            sendResponse(exchange, 200, SimpleJson.toJson(result));
        } catch (Exception e) {
            sendResponse(exchange, 500, jsonError("Failed to restart server: " + e.getMessage()));
        }
    }

    private void handleBackendShutdown(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }

        try {
            String body = readBody(exchange);
            Map<String, Object> req = SimpleJson.parseObject(body);
            boolean keepLlama = req != null && Boolean.parseBoolean(String.valueOf(req.getOrDefault("keepLlama", false)));

            if (!keepLlama) {
                try {
                    processManager.stop();
                } catch (Exception ignored) {}
                logManager.appendLog("[SYSTEM] Java Backend shutting down (Llama server stopped).");
            } else {
                logManager.appendLog("[SYSTEM] Java Backend shutting down (Llama server kept running in background).");
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", keepLlama ? "Backend stopped. Llama server running in background." : "Backend and Llama server stopped.");
            resp.put("keepLlama", keepLlama);
            sendResponse(exchange, 200, SimpleJson.toJson(resp));

            // Schedule graceful exit after sending response
            scheduler.schedule(() -> {
                try {
                    if (httpServer != null) {
                        httpServer.stop(0);
                    }
                } catch (Exception ignored) {}
                System.out.println("[BackendServer] Gracefully stopped.");
                System.exit(0);
            }, 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            sendResponse(exchange, 500, jsonError("Failed to shutdown backend: " + e.getMessage()));
        }
    }

    private void handleServerLogsSse(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true);

        // Send historical logs first
        List<LogEntry> history = logManager.getRecentLogs(500);
        for (LogEntry entry : history) {
            writer.write("data: " + SimpleJson.toJson(entry.toMap()) + "\n\n");
        }
        writer.flush();

        // Subscribe for real-time logs
        BlockingQueue<LogEntry> clientQueue = new LinkedBlockingQueue<>(1000);
        logManager.registerSubscriber(clientQueue);

        try {
            while (true) {
                LogEntry entry = clientQueue.poll(20, TimeUnit.SECONDS);
                if (entry != null) {
                    writer.write("data: " + SimpleJson.toJson(entry.toMap()) + "\n\n");
                    writer.flush();
                } else {
                    // SSE Keep-Alive ping
                    writer.write(": ping\n\n");
                    writer.flush();
                }
            }
        } catch (Exception ignored) {
            // Client disconnected
        } finally {
            logManager.unregisterSubscriber(clientQueue);
            try { os.close(); } catch (Exception ignored) {}
        }
    }

    private void handleServerLogsPoll(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        URI uri = exchange.getRequestURI();
        Map<String, String> queryParams = parseQueryParams(uri.getQuery());
        long sinceId = 0;
        if (queryParams.containsKey("since")) {
            try { sinceId = Long.parseLong(queryParams.get("since")); } catch (Exception ignored) {}
        }

        List<LogEntry> logs = logManager.getLogsSince(sinceId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (LogEntry log : logs) {
            list.add(log.toMap());
        }
        sendResponse(exchange, 200, SimpleJson.toJson(list));
    }

    private void handleServerLogsClear(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        logManager.clear();
        Map<String, Object> res = Map.of("success", true, "message", "Logs cleared");
        sendResponse(exchange, 200, SimpleJson.toJson(res));
    }

    private void handleScanModels(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        URI uri = exchange.getRequestURI();
        Map<String, String> queryParams = parseQueryParams(uri.getQuery());
        String customPath = queryParams.get("path");

        List<Path> scanDirs = new ArrayList<>();
        if (customPath != null && !customPath.isBlank()) {
            scanDirs.add(Paths.get(customPath));
        } else {
            scanDirs.add(DEFAULT_MODELS_DIR);
            scanDirs.add(BASE_DIR);
            scanDirs.add(Paths.get("C:\\AI\\llama.cpp"));
        }

        Map<String, Map<String, Object>> modelsMap = new LinkedHashMap<>();
        Map<String, Map<String, Object>> executablesMap = new LinkedHashMap<>();

        for (Path dir : scanDirs) {
            if (!Files.exists(dir) || !Files.isDirectory(dir)) continue;
            try (var stream = Files.walk(dir, 2)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String normPath = p.toAbsolutePath().normalize().toString();
                    String key = normPath.toLowerCase();
                    String name = p.getFileName().toString();
                    String nameLower = name.toLowerCase();

                    try {
                        long size = Files.size(p);
                        long lastModified = Files.getLastModifiedTime(p).toMillis();
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", name);
                        item.put("path", normPath);
                        item.put("sizeBytes", size);
                        item.put("sizeFormatted", formatFileSize(size));
                        item.put("lastModified", lastModified);

                        if (nameLower.endsWith(".gguf")) {
                            modelsMap.putIfAbsent(key, item);
                        } else if (nameLower.equals("llama-server.exe") || nameLower.equals("llama-cli.exe") || nameLower.endsWith(".exe")) {
                            executablesMap.putIfAbsent(key, item);
                        }
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", new ArrayList<>(modelsMap.values()));
        result.put("executables", new ArrayList<>(executablesMap.values()));
        sendResponse(exchange, 200, SimpleJson.toJson(result));
    }

    private void handleLlamaConfig(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        String method = exchange.getRequestMethod();

        if ("GET".equalsIgnoreCase(method)) {
            Map<String, Object> config = loadLlamaConfig();
            sendResponse(exchange, 200, SimpleJson.toJson(config));
        } else if ("POST".equalsIgnoreCase(method)) {
            String body = readBody(exchange);
            Map<String, Object> config = SimpleJson.parseObject(body);
            if (config != null) {
                saveLlamaConfig(config);
                sendResponse(exchange, 200, SimpleJson.toJson(Map.of("success", true, "config", config)));
            } else {
                sendResponse(exchange, 400, jsonError("Invalid JSON configuration"));
            }
        } else {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
        }
    }

    private void handlePresets(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        List<Map<String, Object>> presets = getBuiltinPresets();
        sendResponse(exchange, 200, SimpleJson.toJson(presets));
    }

    private void handleProjectsList(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        URI uri = exchange.getRequestURI();
        Map<String, String> queryParams = parseQueryParams(uri.getQuery());
        String baseDirStr = queryParams.getOrDefault("baseDir", DEFAULT_PROJECTS_DIR.toString());
        Path baseDirPath = Paths.get(baseDirStr);

        if (!Files.exists(baseDirPath)) {
            Files.createDirectories(baseDirPath);
        }

        List<Map<String, Object>> projects = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDirPath)) {
            for (Path dir : stream) {
                if (Files.isDirectory(dir)) {
                    Map<String, Object> proj = new LinkedHashMap<>();
                    proj.put("name", dir.getFileName().toString());
                    proj.put("path", dir.toAbsolutePath().toString());
                    proj.put("lastModified", Files.getLastModifiedTime(dir).toMillis());

                    Path piDir = dir.resolve(".pi");
                    Path piModelsJson = piDir.resolve("models.json");
                    Path rootModelsJson = dir.resolve("models.json");
                    Path opencodeJson = dir.resolve("opencode.json");

                    boolean hasPi = Files.exists(piModelsJson) || Files.exists(rootModelsJson);
                    boolean hasOpenCode = Files.exists(opencodeJson);

                    proj.put("hasPiConfig", hasPi || hasOpenCode);
                    proj.put("hasOpenCodeConfig", hasOpenCode);

                    if (Files.exists(piModelsJson)) {
                        try {
                            String content = Files.readString(piModelsJson, StandardCharsets.UTF_8);
                            proj.put("piModelsRaw", content);
                            proj.put("piModelsConfig", SimpleJson.parseObject(content));
                        } catch (Exception ignored) {}
                    } else if (Files.exists(rootModelsJson)) {
                        try {
                            String content = Files.readString(rootModelsJson, StandardCharsets.UTF_8);
                            proj.put("piModelsRaw", content);
                            proj.put("piModelsConfig", SimpleJson.parseObject(content));
                        } catch (Exception ignored) {}
                    }

                    Path piSettingsJson = piDir.resolve("settings.json");
                    if (Files.exists(piSettingsJson)) {
                        try {
                            proj.put("piSettingsRaw", Files.readString(piSettingsJson, StandardCharsets.UTF_8));
                        } catch (Exception ignored) {}
                    }

                    if (hasOpenCode) {
                        try {
                            String content = Files.readString(opencodeJson, StandardCharsets.UTF_8);
                            proj.put("opencodeConfigRaw", content);
                            proj.put("opencodeConfig", SimpleJson.parseObject(content));
                        } catch (Exception ignored) {}
                    }
                    projects.add(proj);
                }
            }
        } catch (Exception e) {
            sendResponse(exchange, 500, jsonError("Failed to list projects: " + e.getMessage()));
            return;
        }

        // Sort descending by last modified
        projects.sort((a, b) -> Long.compare((long) b.get("lastModified"), (long) a.get("lastModified")));
        sendResponse(exchange, 200, SimpleJson.toJson(projects));
    }

    private void handleProjectsCreate(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }

        String body = readBody(exchange);
        Map<String, Object> req = SimpleJson.parseObject(body);
        if (req == null) {
            sendResponse(exchange, 400, jsonError("Invalid request body"));
            return;
        }

        String projectName = (String) req.getOrDefault("projectName", "new-project");
        String baseDir = (String) req.getOrDefault("baseDir", DEFAULT_PROJECTS_DIR.toString());
        Path projectPath = Paths.get(baseDir, projectName);

        try {
            Files.createDirectories(projectPath);
            Path piDir = projectPath.resolve(".pi");
            Files.createDirectories(piDir);

            // Active Llama config info for default Pi configuration
            Map<String, Object> llamaCfg = loadLlamaConfig();
            Map<String, Object> srv = asMap(llamaCfg.get("server"));
            String alias = (String) srv.getOrDefault("alias", "qwen3.8");
            int port = parseNumber(srv.get("port"), 9090).intValue();

            // 1. Write .pi/models.json (the only required config file for Pi)
            Object piModelsObj = req.get("piModelsConfig");
            String piModelsStr;
            if (piModelsObj instanceof Map<?, ?> mapObj) {
                piModelsStr = SimpleJson.toPrettyJson(asMap(mapObj));
            } else if (piModelsObj instanceof String s && !s.isBlank()) {
                piModelsStr = s;
            } else {
                piModelsStr = generateDefaultPiModelsJson(alias, port);
            }
            Path piModelsPath = piDir.resolve("models.json");
            Files.writeString(piModelsPath, piModelsStr, StandardCharsets.UTF_8);

            // 2. Sync global ~/.pi/agent/models.json & auth.json so Pi works smoothly everywhere
            syncGlobalPiConfig(alias, port);

            // 3. Optional starter sample file (if requested)
            boolean createSample = Boolean.parseBoolean(String.valueOf(req.getOrDefault("createSampleFile", false)));
            if (createSample) {
                String sampleType = (String) req.getOrDefault("sampleType", "java");
                if ("java".equalsIgnoreCase(sampleType)) {
                    Path sampleFile = projectPath.resolve("Main.java");
                    if (!Files.exists(sampleFile)) {
                        Files.writeString(sampleFile, "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello from " + projectName + "!\");\n    }\n}\n", StandardCharsets.UTF_8);
                    }
                } else if ("node".equalsIgnoreCase(sampleType) || "javascript".equalsIgnoreCase(sampleType)) {
                    Path sampleFile = projectPath.resolve("index.js");
                    if (!Files.exists(sampleFile)) {
                        Files.writeString(sampleFile, "console.log('Hello from " + projectName + "!');\n", StandardCharsets.UTF_8);
                    }
                }
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("projectName", projectName);
            resp.put("projectPath", projectPath.toAbsolutePath().toString());
            resp.put("piModelsPath", piModelsPath.toAbsolutePath().toString());
            resp.put("configPath", piModelsPath.toAbsolutePath().toString());
            sendResponse(exchange, 200, SimpleJson.toJson(resp));

        } catch (Exception e) {
            sendResponse(exchange, 500, jsonError("Failed to create project: " + e.getMessage()));
        }
    }

    private void handleOpenCodeInject(HttpExchange exchange) throws IOException {
        handlePiInject(exchange);
    }

    private void handlePiInject(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }

        String body = readBody(exchange);
        Map<String, Object> req = SimpleJson.parseObject(body);
        if (req == null || !req.containsKey("projectPath")) {
            sendResponse(exchange, 400, jsonError("projectPath is required"));
            return;
        }

        String projectPathStr = (String) req.get("projectPath");
        Path projectPath = Paths.get(projectPathStr);
        if (!Files.exists(projectPath)) {
            sendResponse(exchange, 404, jsonError("Project directory does not exist: " + projectPathStr));
            return;
        }

        Map<String, Object> llamaCfg = loadLlamaConfig();
        Map<String, Object> srv = asMap(llamaCfg.get("server"));
        String alias = (String) srv.getOrDefault("alias", "qwen3.8");
        int port = parseNumber(srv.get("port"), 9090).intValue();

        Path piDir = projectPath.resolve(".pi");
        Files.createDirectories(piDir);

        Object piModelsObj = req.get("piModelsConfig");
        String piModelsStr;
        if (piModelsObj instanceof Map<?, ?> mapObj) {
            piModelsStr = SimpleJson.toPrettyJson(asMap(mapObj));
        } else if (piModelsObj instanceof String s && !s.isBlank()) {
            piModelsStr = s;
        } else {
            piModelsStr = generateDefaultPiModelsJson(alias, port);
        }

        Path piModelsFile = piDir.resolve("models.json");
        Files.writeString(piModelsFile, piModelsStr, StandardCharsets.UTF_8);

        syncGlobalPiConfig(alias, port);

        Map<String, Object> resp = Map.of("success", true, "configPath", piModelsFile.toAbsolutePath().toString());
        sendResponse(exchange, 200, SimpleJson.toJson(resp));
    }

    private void handleOpenCodeStartTerminal(HttpExchange exchange) throws IOException {
        handlePiStartTerminal(exchange);
    }

    private void handlePiStartTerminal(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }

        String body = readBody(exchange);
        Map<String, Object> req = SimpleJson.parseObject(body);
        if (req == null || !req.containsKey("projectPath")) {
            sendResponse(exchange, 400, jsonError("projectPath is required"));
            return;
        }

        String projectPath = (String) req.get("projectPath");
        String terminalType = (String) req.getOrDefault("terminalType", "wt"); // wt, powershell, cmd

        boolean started = launchTerminal(projectPath, terminalType);
        if (started) {
            sendResponse(exchange, 200, SimpleJson.toJson(Map.of("success", true, "message", "Pi terminal launched in " + projectPath)));
        } else {
            sendResponse(exchange, 500, jsonError("Failed to launch Pi terminal"));
        }
    }

    private void handleOpenExplorer(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }
        String body = readBody(exchange);
        Map<String, Object> req = SimpleJson.parseObject(body);
        String projectPath = req != null ? (String) req.get("projectPath") : null;
        if (projectPath != null && Files.exists(Paths.get(projectPath))) {
            try {
                new ProcessBuilder("cmd.exe", "/c", "start", "", projectPath).start();
                sendResponse(exchange, 200, SimpleJson.toJson(Map.of("success", true)));
            } catch (Exception e) {
                sendResponse(exchange, 500, jsonError("Failed to open Explorer: " + e.getMessage()));
            }
        } else {
            sendResponse(exchange, 400, jsonError("Invalid path"));
        }
    }

    private void handleOpenVsCode(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }
        String body = readBody(exchange);
        Map<String, Object> req = SimpleJson.parseObject(body);
        String projectPath = req != null ? (String) req.get("projectPath") : null;
        if (projectPath != null && Files.exists(Paths.get(projectPath))) {
            try {
                new ProcessBuilder("cmd.exe", "/c", "start", "", "code", projectPath).start();
                sendResponse(exchange, 200, SimpleJson.toJson(Map.of("success", true)));
            } catch (Exception e) {
                sendResponse(exchange, 500, jsonError("Failed to launch VS Code: " + e.getMessage()));
            }
        } else {
            sendResponse(exchange, 400, jsonError("Invalid path"));
        }
    }

    private void handleChatTest(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }

        String body = readBody(exchange);
        Map<String, Object> req = SimpleJson.parseObject(body);
        String host = (String) req.getOrDefault("host", "127.0.0.1");
        int port = parseNumber(req.get("port"), 9090).intValue();
        String model = (String) req.getOrDefault("model", "qwen-fable");
        String message = (String) req.getOrDefault("prompt", "Hello! Write a 1-sentence response.");
        double temperature = parseNumber(req.get("temperature"), 0.7).doubleValue();
        int maxTokens = parseNumber(req.get("max_tokens"), 256).intValue();

        String payload = SimpleJson.toJson(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", message)),
                "max_tokens", maxTokens,
                "temperature", temperature
        ));

        long startTime = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + port + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = System.currentTimeMillis() - startTime;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("statusCode", response.statusCode());
            result.put("elapsedMs", elapsed);
            result.put("raw", response.body());

            if (response.statusCode() == 200) {
                Map<String, Object> parsed = SimpleJson.parseObject(response.body());
                if (parsed != null && parsed.containsKey("choices")) {
                    List<?> choices = (List<?>) parsed.get("choices");
                    if (!choices.isEmpty() && choices.get(0) instanceof Map choiceMap) {
                        Map<?, ?> msg = (Map<?, ?>) choiceMap.get("message");
                        if (msg != null) {
                            result.put("content", msg.get("content"));
                            result.put("reasoning_content", msg.get("reasoning_content"));
                        }
                    }
                    if (parsed.containsKey("usage")) {
                        result.put("usage", parsed.get("usage"));
                    }
                }
            }
            sendResponse(exchange, 200, SimpleJson.toJson(result));

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            sendResponse(exchange, 200, SimpleJson.toJson(Map.of(
                    "statusCode", 0,
                    "elapsedMs", elapsed,
                    "error", "Connection error: " + e.getMessage()
            )));
        }
    }

    private void handleSystemPaths(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("defaultBaseDir", BASE_DIR.toString());
        info.put("defaultModelsDir", DEFAULT_MODELS_DIR.toString());
        info.put("defaultProjectsDir", DEFAULT_PROJECTS_DIR.toString());
        info.put("defaultExePath", "C:\\AI\\llama.cpp\\llama-server.exe");
        info.put("defaultLlamaConfig", CONFIG_FILE.toString());
        info.put("os", System.getProperty("os.name"));
        info.put("javaVersion", System.getProperty("java.version"));
        sendResponse(exchange, 200, SimpleJson.toJson(info));
    }

    private void handleStaticFiles(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            sendResponse(exchange, 405, jsonError("Method Not Allowed"));
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path == null || path.isBlank() || path.equals("/")) {
            path = "/index.html";
        }

        // Clean path to prevent path traversal
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        cleanPath = cleanPath.replace('\\', '/');

        // Try finding file in static dirs
        Path targetFile = null;
        for (Path baseDir : STATIC_DIRS) {
            Path candidate = baseDir.resolve(cleanPath).normalize();
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                targetFile = candidate;
                break;
            }
        }

        // SPA Fallback: If not found and not requesting a file with an extension, serve index.html
        if (targetFile == null) {
            if (!cleanPath.contains(".")) {
                for (Path baseDir : STATIC_DIRS) {
                    Path candidate = baseDir.resolve("index.html").normalize();
                    if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                        targetFile = candidate;
                        break;
                    }
                }
            }
        }

        if (targetFile != null && Files.exists(targetFile)) {
            String filePathKey = targetFile.toAbsolutePath().toString();
            long fileModTime = Files.getLastModifiedTime(targetFile).toMillis();

            CachedStaticFile cached = STATIC_CACHE.get(filePathKey);
            if (cached == null || cached.lastModified != fileModTime) {
                byte[] raw = Files.readAllBytes(targetFile);
                byte[] gzipped = gzipCompress(raw);
                String mime = getMimeType(targetFile.getFileName().toString());
                cached = new CachedStaticFile(raw, gzipped, mime, fileModTime);
                STATIC_CACHE.put(filePathKey, cached);
            }

            String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
            boolean canGzip = acceptEncoding != null && acceptEncoding.contains("gzip") && cached.gzipBytes.length < cached.rawBytes.length;

            byte[] toSend = canGzip ? cached.gzipBytes : cached.rawBytes;

            exchange.getResponseHeaders().set("Content-Type", cached.mimeType);
            if (canGzip) {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            }
            exchange.getResponseHeaders().set("Cache-Control", cleanPath.endsWith(".html") ? "no-cache" : "public, max-age=31536000, immutable");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, toSend.length);

            if ("GET".equalsIgnoreCase(method)) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(toSend);
                }
            }
        } else {
            sendResponse(exchange, 404, "404 Not Found");
        }
    }

    private String getMimeType(String filename) {
        String name = filename.toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript; charset=UTF-8";
        if (name.endsWith(".css")) return "text/css; charset=UTF-8";
        if (name.endsWith(".json")) return "application/json; charset=UTF-8";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".txt")) return "text/plain; charset=UTF-8";
        return "application/octet-stream";
    }

    // =========================================================================
    // Background Health Checker & Helper Methods
    // =========================================================================

    private int healthCheckCounter = 0;

    private void performHealthCheck() {
        if (!processManager.isRunning()) {
            return;
        }

        // When healthy, throttle health check to run every 4 intervals to conserve CPU/network
        if (processManager.isHealthy() && (++healthCheckCounter % 4 != 0)) {
            return;
        }

        Map<String, Object> cfg = loadLlamaConfig();
        Map<String, Object> srv = asMap(cfg.get("server"));
        String host = (String) srv.getOrDefault("host", "127.0.0.1");
        int port = parseNumber(srv.get("port"), 9090).intValue();

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + port + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(2))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200) {
                processManager.setHealthy(true);
            }
        } catch (Exception e) {
            // Not ready yet or stopped
            processManager.setHealthy(false);
        }
    }

    private boolean launchTerminal(String projectPath, String terminalType) {
        try {
            Path dir = Paths.get(projectPath);
            if (!Files.exists(dir)) return false;

            // Get active model alias & port
            Map<String, Object> llamaCfg = loadLlamaConfig();
            Map<String, Object> srv = asMap(llamaCfg.get("server"));
            String alias = (String) srv.getOrDefault("alias", "qwen3.8");
            int port = parseNumber(srv.get("port"), 9090).intValue();

            // Sync global Pi configuration before launching
            syncGlobalPiConfig(alias, port);

            String endpoint = "http://127.0.0.1:" + port + "/v1";
            String winTitle = "Pi Coding Agent - " + dir.getFileName();

            // 1. If terminalType is wt, try Windows Terminal first
            if ("wt".equalsIgnoreCase(terminalType)) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("wt.exe", "-d", projectPath, "--title", winTitle, "cmd.exe", "/k", "cd /d " + projectPath + " && set LLAMA_BASE_URL=" + endpoint + " && set LLAMA_API_KEY=no-key && pi --api-key no-key --provider llama --model " + alias);
                    pb.directory(dir.toFile());
                    pb.environment().put("LLAMA_BASE_URL", endpoint);
                    pb.environment().put("LLAMA_API_KEY", "no-key");
                    pb.start();
                    return true;
                } catch (Exception ignored) {}
            }

            // 2. PowerShell launch
            if ("powershell".equalsIgnoreCase(terminalType)) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "start", winTitle, "powershell.exe", "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", "Set-Location -LiteralPath '" + projectPath + "'; $env:LLAMA_BASE_URL='" + endpoint + "'; $env:LLAMA_API_KEY='no-key'; Write-Host '=========================================================================' -ForegroundColor Cyan; Write-Host '   Pi Coding Agent Initialized - Local Model: " + alias + "' -ForegroundColor Yellow; Write-Host '   Endpoint: " + endpoint + "' -ForegroundColor DarkGray; Write-Host '=========================================================================' -ForegroundColor Cyan; pi --api-key no-key --provider llama --model " + alias);
                    pb.directory(dir.toFile());
                    pb.environment().put("LLAMA_BASE_URL", endpoint);
                    pb.environment().put("LLAMA_API_KEY", "no-key");
                    pb.start();
                    return true;
                } catch (Exception ignored) {}
            }

            // 3. Reliable standard Windows CMD launch
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "start", winTitle, "cmd.exe", "/k", "cd /d " + projectPath + " && set LLAMA_BASE_URL=" + endpoint + " && set LLAMA_API_KEY=no-key && pi --api-key no-key --provider llama --model " + alias);
            pb.directory(dir.toFile());
            pb.environment().put("LLAMA_BASE_URL", endpoint);
            pb.environment().put("LLAMA_API_KEY", "no-key");
            pb.start();
            return true;
        } catch (Exception e) {
            System.err.println("[BackendServer] Failed to launch terminal: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private String generateDefaultPiModelsJson(String alias, int port) {
        return """
        {
          "providers": {
            "llama": {
              "name": "llama.cpp (local)",
              "baseUrl": "http://127.0.0.1:%d/v1",
              "api": "openai-completions",
              "apiKey": "no-key",
              "compat": {
                "supportsDeveloperRole": false,
                "supportsReasoningEffort": false
              },
              "models": [
                {
                  "id": "%s",
                  "name": "%s",
                  "contextWindow": 32768,
                  "maxTokens": 8192,
                  "reasoning": true
                }
              ]
            }
          }
        }
        """.formatted(port, alias, alias);
    }

    private String generateDefaultPiSettingsJson(String alias) {
        return """
        {
          "defaultProvider": "llama",
          "defaultModel": "%s",
          "thinking": "high",
          "enabledModels": [
            "%s"
          ],
          "theme": "dark"
        }
        """.formatted(alias, alias);
    }

    private void syncGlobalPiConfig(String alias, int port) {
        try {
            Path globalPiDir = Paths.get(System.getProperty("user.home"), ".pi", "agent");
            Files.createDirectories(globalPiDir);
            Path modelsFile = globalPiDir.resolve("models.json");
            Files.writeString(modelsFile, generateDefaultPiModelsJson(alias, port), StandardCharsets.UTF_8);

            Path settingsFile = globalPiDir.resolve("settings.json");
            Files.writeString(settingsFile, generateDefaultPiSettingsJson(alias), StandardCharsets.UTF_8);

            Path authFile = globalPiDir.resolve("auth.json");
            String authJson = """
            {
              "llama": {
                "type": "api_key",
                "key": "no-key"
              }
            }
            """;
            Files.writeString(authFile, authJson, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[BackendServer] Failed to sync global Pi config: " + e.getMessage());
        }
    }

    private Map<String, Object> loadLlamaConfig() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                String content = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
                Map<String, Object> map = SimpleJson.parseObject(content);
                if (map != null && !map.isEmpty()) return map;
            }
        } catch (Exception ignored) {}
        return getDefaultLlamaConfig();
    }

    private void saveLlamaConfig(Map<String, Object> config) {
        try {
            if (!Files.exists(CONFIG_FILE.getParent())) {
                Files.createDirectories(CONFIG_FILE.getParent());
            }
            String pretty = SimpleJson.toPrettyJson(config);
            Files.writeString(CONFIG_FILE, pretty, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[BackendServer] Failed to save config: " + e.getMessage());
        }
    }

    private Map<String, Object> getDefaultLlamaConfig() {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("exe_path", "C:\\AI\\llama.cpp\\llama-server.exe");
        paths.put("model_path", "C:\\AI\\Models\\qwen-fable-q4_k_m.gguf");
        paths.put("model_url", "");
        paths.put("hf_repo", "");
        paths.put("hf_file", "");
        paths.put("hf_token", "");
        paths.put("docker_repo", "");
        paths.put("mmproj_path", "");
        root.put("paths", paths);

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("host", "127.0.0.1");
        server.put("port", 9090);
        server.put("alias", "qwen3.8");
        server.put("tags", "");
        server.put("gpu_layers", "");
        server.put("context_size", "");
        server.put("max_tokens", "");
        server.put("threads", "");
        server.put("threads_batch", "");
        server.put("batch_size", "");
        server.put("ubatch_size", "");
        server.put("parallel_slots", "");
        server.put("flash_attention", "auto"); // on, off, auto
        server.put("continuous_batching", "auto");
        server.put("reasoning_mode", "auto"); // on, off, auto
        server.put("reasoning_format", "auto"); // deepseek, none, deepseek-legacy, auto
        server.put("reasoning_budget", "");
        server.put("reasoning_preserve", false);
        server.put("jinja", false);
        server.put("chat_template", "");
        server.put("chat_template_file", "");
        server.put("chat_template_kwargs", "");
        server.put("skip_chat_parsing", false);
        server.put("prefill_assistant", false);
        server.put("slot_prompt_similarity", "");
        server.put("cache_type_k", "auto");
        server.put("cache_type_v", "auto");
        server.put("kv_offload", "auto");
        server.put("kv_unified", "auto");
        server.put("cache_ram", "");
        server.put("cache_prompt", "auto");
        server.put("cache_reuse", "");
        server.put("context_shift", false);
        server.put("load_mode", "auto"); // auto, mmap, mlock, mmap+mlock, dio
        server.put("split_mode", "auto"); // auto, none, layer, row, tensor
        server.put("main_gpu", "");
        server.put("fit", "auto");
        server.put("timeout", "");
        server.put("sse_ping_interval", "");
        server.put("threads_http", "");
        server.put("cors_origins", "*");
        server.put("webui", false);
        server.put("metrics", false);
        server.put("slots_endpoint", false);
        server.put("advanced", "");
        root.put("server", server);

        Map<String, Object> sampling = new LinkedHashMap<>();
        sampling.put("temperature", "");
        sampling.put("top_k", "");
        sampling.put("top_p", "");
        sampling.put("min_p", "");
        sampling.put("repeat_penalty", "");
        sampling.put("repeat_last_n", "");
        sampling.put("presence_penalty", "");
        sampling.put("frequency_penalty", "");
        sampling.put("dry_multiplier", "");
        sampling.put("dry_base", "");
        sampling.put("samplers", "");
        root.put("sampling", sampling);

        return root;
    }

    private List<Map<String, Object>> getBuiltinPresets() {
        List<Map<String, Object>> presets = new ArrayList<>();

        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("id", "qwen-coder-fast");
        p1.put("name", "Qwen Coder Fast 8K (Full GPU Offload)");
        p1.put("description", "Optimized for fast code completion and low latency on NVIDIA GPUs with 8K context.");
        Map<String, Object> c1 = getDefaultLlamaConfig();
        asMap(c1.get("server")).put("context_size", 8192);
        asMap(c1.get("server")).put("gpu_layers", 999);
        asMap(c1.get("server")).put("flash_attention", "on");
        asMap(c1.get("server")).put("continuous_batching", true);
        asMap(c1.get("server")).put("parallel_slots", 2);
        p1.put("config", c1);
        presets.add(p1);

        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("id", "qwen-deep-reasoning");
        p2.put("name", "Qwen 32K Deep Reasoning (Thinking Mode)");
        p2.put("description", "Large 32K context window with Flash Attention and DeepSeek-style reasoning trace.");
        Map<String, Object> c2 = getDefaultLlamaConfig();
        asMap(c2.get("server")).put("context_size", 32768);
        asMap(c2.get("server")).put("gpu_layers", 999);
        asMap(c2.get("server")).put("reasoning_mode", "on");
        asMap(c2.get("server")).put("reasoning_format", "deepseek");
        asMap(c2.get("server")).put("flash_attention", "on");
        p2.put("config", c2);
        presets.add(p2);

        Map<String, Object> p3 = new LinkedHashMap<>();
        p3.put("id", "low-vram-balanced");
        p3.put("name", "Low VRAM / Partial CPU Offload");
        p3.put("description", "Quantized KV Cache (q8_0) and 24 GPU layers for cards with limited VRAM.");
        Map<String, Object> c3 = getDefaultLlamaConfig();
        asMap(c3.get("server")).put("gpu_layers", 24);
        asMap(c3.get("server")).put("cache_type_k", "q8_0");
        asMap(c3.get("server")).put("cache_type_v", "q8_0");
        asMap(c3.get("server")).put("context_size", 4096);
        p3.put("config", c3);
        presets.add(p3);

        Map<String, Object> p4 = new LinkedHashMap<>();
        p4.put("id", "high-concurrency");
        p4.put("name", "High Concurrency (4 Slots, Continuous Batching)");
        p4.put("description", "Designed for serving multiple agent / IDE requests simultaneously.");
        Map<String, Object> c4 = getDefaultLlamaConfig();
        asMap(c4.get("server")).put("parallel_slots", 4);
        asMap(c4.get("server")).put("continuous_batching", true);
        asMap(c4.get("server")).put("cache_prompt", true);
        p4.put("config", c4);
        presets.add(p4);

        return presets;
    }

    // =========================================================================
    // HTTP Utilities
    // =========================================================================

    private boolean handleCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0], kv[1]);
            } else if (kv.length == 1) {
                map.put(kv[0], "");
            }
        }
        return map;
    }

    private String jsonError(String message) {
        return "{\"error\":\"" + escapeJson(message) + "\",\"success\":false}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.2f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private static Number parseNumber(Object obj, Number fallback) {
        if (obj == null) return fallback;
        if (obj instanceof Number n) return n;
        try {
            String s = obj.toString().trim();
            if (s.contains(".")) return Double.parseDouble(s);
            return Long.parseLong(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    // =========================================================================
    // Llama Server Process Manager
    // =========================================================================

    public static class LlamaProcessManager {
        private Process process;
        private long pid = -1;
        private Instant startTime;
        private final AtomicBoolean isHealthy = new AtomicBoolean(false);
        private String currentCommandStr = "";

        public synchronized boolean isRunning() {
            if (process == null) return false;
            return process.isAlive();
        }

        public void setHealthy(boolean healthy) {
            this.isHealthy.set(healthy);
        }

        public boolean isHealthy() {
            return isRunning() && this.isHealthy.get();
        }

        public synchronized Map<String, Object> getStatus() {
            Map<String, Object> map = new LinkedHashMap<>();
            boolean running = isRunning();
            map.put("running", running);
            map.put("healthy", running && isHealthy.get());
            map.put("pid", running ? pid : null);
            map.put("status", !running ? "STOPPED" : (isHealthy.get() ? "RUNNING" : "STARTING"));
            map.put("startTime", startTime != null ? startTime.toEpochMilli() : null);
            map.put("uptimeSeconds", (running && startTime != null) ? Duration.between(startTime, Instant.now()).getSeconds() : 0);
            map.put("command", currentCommandStr);
            return map;
        }

        public synchronized Map<String, Object> start(Map<String, Object> config, LogManager logManager) throws Exception {
            if (isRunning()) {
                throw new IllegalStateException("Llama server is already running with PID: " + pid);
            }

            List<String> cmd = buildCommandLine(config);
            currentCommandStr = String.join(" ", cmd);

            logManager.appendLog("[SYSTEM] Launching Llama Server with command: " + currentCommandStr);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            process = pb.start();
            pid = process.pid();
            startTime = Instant.now();
            isHealthy.set(false);

            logManager.appendLog("[SYSTEM] Process started successfully with PID: " + pid);

            // Read output stream in virtual thread
            Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logManager.appendLog(line);
                    }
                } catch (Exception ignored) {
                } finally {
                    int exitCode = -1;
                    try { exitCode = process.waitFor(); } catch (Exception ignored) {}
                    logManager.appendLog("[SYSTEM] Process terminated with exit code " + exitCode);
                    isHealthy.set(false);
                }
            });

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("pid", pid);
            res.put("command", currentCommandStr);
            return res;
        }

        public synchronized Map<String, Object> stop() {
            if (!isRunning()) {
                return Map.of("success", true, "message", "Server is not running");
            }

            try {
                // On Windows, cleanly kill the process tree including child threads
                if (System.getProperty("os.name").toLowerCase().contains("win") && pid > 0) {
                    new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid), "/T").start().waitFor();
                } else {
                    process.destroy();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            } catch (Exception e) {
                if (process != null) process.destroyForcibly();
            } finally {
                process = null;
                pid = -1;
                startTime = null;
                isHealthy.set(false);
            }

            return Map.of("success", true, "message", "Server stopped");
        }

        private List<String> buildCommandLine(Map<String, Object> config) {
            List<String> cmd = new ArrayList<>();

            Map<String, Object> paths = asMap(config.get("paths"));
            Map<String, Object> server = asMap(config.get("server"));
            Map<String, Object> sampling = asMap(config.get("sampling"));

            String exe = (String) paths.getOrDefault("exe_path", "C:\\AI\\llama.cpp\\llama-server.exe");
            cmd.add(exe);

            String model = (String) paths.getOrDefault("model_path", "");
            if (model != null && !model.isBlank()) {
                cmd.add("-m");
                cmd.add(model);
            }

            String modelUrl = (String) paths.getOrDefault("model_url", "");
            if (modelUrl != null && !modelUrl.isBlank()) {
                cmd.add("--model-url");
                cmd.add(modelUrl);
            }

            String hfRepo = (String) paths.getOrDefault("hf_repo", "");
            if (hfRepo != null && !hfRepo.isBlank()) {
                cmd.add("--hf-repo");
                cmd.add(hfRepo);
            }

            String hfFile = (String) paths.getOrDefault("hf_file", "");
            if (hfFile != null && !hfFile.isBlank()) {
                cmd.add("--hf-file");
                cmd.add(hfFile);
            }

            String hfToken = (String) paths.getOrDefault("hf_token", "");
            if (hfToken != null && !hfToken.isBlank()) {
                cmd.add("--hf-token");
                cmd.add(hfToken);
            }

            String mmproj = (String) paths.getOrDefault("mmproj_path", "");
            if (mmproj != null && !mmproj.isBlank()) {
                cmd.add("--mmproj");
                cmd.add(mmproj);
            }

            // Server & Network
            String host = (String) server.getOrDefault("host", "127.0.0.1");
            if (host != null && !host.isBlank()) {
                cmd.add("--host");
                cmd.add(host);
            }

            Number port = parseNumber(server.get("port"), null);
            if (port != null && port.intValue() > 0) {
                cmd.add("--port");
                cmd.add(String.valueOf(port));
            }

            String alias = (String) server.getOrDefault("alias", "");
            if (alias != null && !alias.isBlank()) {
                cmd.add("--alias");
                cmd.add(alias.trim());
            }

            // Hardware & Offloading
            Object ngl = server.get("gpu_layers");
            if (ngl != null && !ngl.toString().isBlank() && !ngl.toString().equalsIgnoreCase("default")) {
                cmd.add("-ngl");
                cmd.add(ngl.toString().trim());
            }

            Number mainGpu = parseNumber(server.get("main_gpu"), null);
            if (mainGpu != null && mainGpu.intValue() > 0) {
                cmd.add("-mg");
                cmd.add(String.valueOf(mainGpu));
            }

            // Split Mode
            String splitMode = (String) server.getOrDefault("split_mode", "");
            if (splitMode != null && !splitMode.isBlank() && !splitMode.equalsIgnoreCase("layer") && !splitMode.equalsIgnoreCase("auto") && !splitMode.equalsIgnoreCase("default")) {
                cmd.add("-sm");
                cmd.add(splitMode.trim());
            }

            // Load Mode
            String loadMode = (String) server.getOrDefault("load_mode", "");
            if (loadMode != null && !loadMode.isBlank() && !loadMode.equalsIgnoreCase("auto") && !loadMode.equalsIgnoreCase("default")) {
                cmd.add("-lm");
                cmd.add(loadMode.trim());
            }

            // Fit
            Object fit = server.get("fit");
            if (fit != null) {
                String fitStr = fit.toString().trim().toLowerCase();
                if (fitStr.equals("false") || fitStr.equals("off")) {
                    cmd.add("--no-fit");
                } else if (fitStr.equals("true") || fitStr.equals("on")) {
                    cmd.add("--fit");
                }
            }

            Number ctx = parseNumber(server.get("context_size"), null);
            if (ctx != null && ctx.intValue() > 0) {
                cmd.add("-c");
                cmd.add(String.valueOf(ctx));
            }

            Number maxTokens = parseNumber(server.get("max_tokens"), null);
            if (maxTokens != null && maxTokens.intValue() > 0) {
                cmd.add("-n");
                cmd.add(String.valueOf(maxTokens));
            }

            Number threads = parseNumber(server.get("threads"), null);
            if (threads != null && threads.intValue() > 0) {
                cmd.add("-t");
                cmd.add(String.valueOf(threads));
            }

            Number threadsBatch = parseNumber(server.get("threads_batch"), null);
            if (threadsBatch != null && threadsBatch.intValue() > 0) {
                cmd.add("-tb");
                cmd.add(String.valueOf(threadsBatch));
            }

            Number batch = parseNumber(server.get("batch_size"), null);
            if (batch != null && batch.intValue() > 0) {
                cmd.add("-b");
                cmd.add(String.valueOf(batch));
            }

            Number ubatch = parseNumber(server.get("ubatch_size"), null);
            if (ubatch != null && ubatch.intValue() > 0) {
                cmd.add("-ub");
                cmd.add(String.valueOf(ubatch));
            }

            Number parallel = parseNumber(server.get("parallel_slots"), null);
            if (parallel != null && parallel.intValue() > 0) {
                cmd.add("-np");
                cmd.add(String.valueOf(parallel));
            }

            // Flash attention
            Object fa = server.get("flash_attention");
            if (fa != null) {
                String faStr = fa.toString().trim().toLowerCase();
                if (faStr.equals("true") || faStr.equals("on")) {
                    cmd.add("--flash-attn");
                    cmd.add("on");
                } else if (faStr.equals("false") || faStr.equals("off")) {
                    cmd.add("--flash-attn");
                    cmd.add("off");
                }
            }

            // Continuous batching
            Object cb = server.get("continuous_batching");
            if (cb != null) {
                String cbStr = cb.toString().trim().toLowerCase();
                if (cbStr.equals("true") || cbStr.equals("on")) {
                    cmd.add("--cont-batching");
                } else if (cbStr.equals("false") || cbStr.equals("off")) {
                    cmd.add("--no-cont-batching");
                }
            }

            // Cache data types
            String ctk = (String) server.getOrDefault("cache_type_k", "");
            if (ctk != null && !ctk.isBlank() && !ctk.equalsIgnoreCase("f16") && !ctk.equalsIgnoreCase("auto") && !ctk.equalsIgnoreCase("default")) {
                cmd.add("-ctk");
                cmd.add(ctk.trim());
            }

            String ctv = (String) server.getOrDefault("cache_type_v", "");
            if (ctv != null && !ctv.isBlank() && !ctv.equalsIgnoreCase("f16") && !ctv.equalsIgnoreCase("auto") && !ctv.equalsIgnoreCase("default")) {
                cmd.add("-ctv");
                cmd.add(ctv.trim());
            }

            Object kvo = server.get("kv_offload");
            if (kvo != null) {
                String kvoStr = kvo.toString().trim().toLowerCase();
                if (kvoStr.equals("false") || kvoStr.equals("off")) {
                    cmd.add("--no-kv-offload");
                }
            }

            Object kvu = server.get("kv_unified");
            if (kvu != null) {
                String kvuStr = kvu.toString().trim().toLowerCase();
                if (kvuStr.equals("true") || kvuStr.equals("on")) {
                    cmd.add("-kvu");
                }
            }

            Object cachePrompt = server.get("cache_prompt");
            if (cachePrompt != null) {
                String cpStr = cachePrompt.toString().trim().toLowerCase();
                if (cpStr.equals("true") || cpStr.equals("on")) {
                    cmd.add("--cache-prompt");
                } else if (cpStr.equals("false") || cpStr.equals("off")) {
                    cmd.add("--no-cache-prompt");
                }
            }

            // Reasoning
            Object rea = server.get("reasoning_mode");
            if (rea != null && !rea.toString().isBlank() && !rea.toString().equalsIgnoreCase("auto") && !rea.toString().equalsIgnoreCase("default")) {
                cmd.add("--reasoning");
                cmd.add(rea.toString().trim().toLowerCase());
            }

            Object reaFormat = server.get("reasoning_format");
            if (reaFormat != null && !reaFormat.toString().isBlank() && !reaFormat.toString().equalsIgnoreCase("auto") && !reaFormat.toString().equalsIgnoreCase("default") && !reaFormat.toString().equalsIgnoreCase("none")) {
                cmd.add("--reasoning-format");
                cmd.add(reaFormat.toString().trim().toLowerCase());
            }

            Number reaBudget = parseNumber(server.get("reasoning_budget"), null);
            if (reaBudget != null && reaBudget.intValue() >= 0) {
                cmd.add("--reasoning-budget");
                cmd.add(String.valueOf(reaBudget));
            }

            // Templates
            String chatTemplate = (String) server.getOrDefault("chat_template", "");
            if (chatTemplate != null && !chatTemplate.isBlank() && !chatTemplate.equalsIgnoreCase("auto")) {
                cmd.add("--chat-template");
                cmd.add(chatTemplate.trim());
            }

            String chatTemplateFile = (String) server.getOrDefault("chat_template_file", "");
            if (chatTemplateFile != null && !chatTemplateFile.isBlank()) {
                cmd.add("--chat-template-file");
                cmd.add(chatTemplateFile.trim());
            }

            String chatKwargs = (String) server.getOrDefault("chat_template_kwargs", "");
            if (chatKwargs != null && !chatKwargs.isBlank()) {
                cmd.add("--chat-template-kwargs");
                cmd.add(chatKwargs.trim());
            }

            Object jinja = server.get("jinja");
            if (jinja != null) {
                String jinjaStr = jinja.toString().trim().toLowerCase();
                if (jinjaStr.equals("true") || jinjaStr.equals("on")) {
                    cmd.add("--jinja");
                }
            }

            // Sampling defaults (Only if explicitly provided)
            Number temp = parseNumber(sampling.get("temperature"), null);
            if (temp != null) {
                cmd.add("--temp");
                cmd.add(String.valueOf(temp));
            }

            Number topK = parseNumber(sampling.get("top_k"), null);
            if (topK != null) {
                cmd.add("--top-k");
                cmd.add(String.valueOf(topK));
            }

            Number topP = parseNumber(sampling.get("top_p"), null);
            if (topP != null) {
                cmd.add("--top-p");
                cmd.add(String.valueOf(topP));
            }

            Number minP = parseNumber(sampling.get("min_p"), null);
            if (minP != null) {
                cmd.add("--min-p");
                cmd.add(String.valueOf(minP));
            }

            Number repPen = parseNumber(sampling.get("repeat_penalty"), null);
            if (repPen != null) {
                cmd.add("--repeat-penalty");
                cmd.add(String.valueOf(repPen));
            }

            Number repLastN = parseNumber(sampling.get("repeat_last_n"), null);
            if (repLastN != null && repLastN.intValue() != 64) {
                cmd.add("--repeat-last-n");
                cmd.add(String.valueOf(repLastN));
            }

            Number presPen = parseNumber(sampling.get("presence_penalty"), null);
            if (presPen != null && presPen.doubleValue() != 0.0) {
                cmd.add("--presence-penalty");
                cmd.add(String.valueOf(presPen));
            }

            Number freqPen = parseNumber(sampling.get("frequency_penalty"), null);
            if (freqPen != null && freqPen.doubleValue() != 0.0) {
                cmd.add("--frequency-penalty");
                cmd.add(String.valueOf(freqPen));
            }

            Number dryMult = parseNumber(sampling.get("dry_multiplier"), null);
            if (dryMult != null && dryMult.doubleValue() > 0.0) {
                cmd.add("--dry-multiplier");
                cmd.add(String.valueOf(dryMult));
                Number dryBase = parseNumber(sampling.get("dry_base"), null);
                if (dryBase != null) {
                    cmd.add("--dry-base");
                    cmd.add(String.valueOf(dryBase));
                }
            }

            // Advanced custom CLI options
            String advanced = (String) server.getOrDefault("advanced", "");
            if (advanced != null && !advanced.isBlank()) {
                for (String part : advanced.trim().split("\\s+")) {
                    if (!part.isBlank()) cmd.add(part);
                }
            }

            return cmd;
        }
    }

    // =========================================================================
    // In-Memory Ring Buffer Log Manager
    // =========================================================================

    public static class LogManager {
        private static final int MAX_LOGS = 5000;
        private final LinkedList<LogEntry> logs = new LinkedList<>();
        private final AtomicLong sequence = new AtomicLong(0);
        private final Set<BlockingQueue<LogEntry>> subscribers = ConcurrentHashMap.newKeySet();

        public synchronized void appendLog(String line) {
            long id = sequence.incrementAndGet();
            String level = detectLevel(line);
            LogEntry entry = new LogEntry(id, System.currentTimeMillis(), level, line);

            logs.add(entry);
            while (logs.size() > MAX_LOGS) {
                logs.removeFirst();
            }

            // Dispatch to real-time subscribers
            for (var queue : subscribers) {
                queue.offer(entry);
            }
        }

        public synchronized List<LogEntry> getRecentLogs(int limit) {
            int count = Math.min(limit, logs.size());
            return new ArrayList<>(logs.subList(logs.size() - count, logs.size()));
        }

        public synchronized List<LogEntry> getLogsSince(long sinceId) {
            List<LogEntry> result = new ArrayList<>();
            for (LogEntry entry : logs) {
                if (entry.id() > sinceId) {
                    result.add(entry);
                }
            }
            return result;
        }

        public synchronized void clear() {
            logs.clear();
        }

        public void registerSubscriber(BlockingQueue<LogEntry> queue) {
            subscribers.add(queue);
        }

        public void unregisterSubscriber(BlockingQueue<LogEntry> queue) {
            subscribers.remove(queue);
        }

        private String detectLevel(String line) {
            if (line.startsWith("[SYSTEM]")) return "SYSTEM";
            if (line.contains(" E srv") || line.contains(" E cmn") || line.contains(" E llama") || line.contains("error:") || line.contains("fatal:")) {
                return "ERROR";
            }
            if (line.contains(" W srv") || line.contains(" W cmn") || line.contains(" W llama") || line.contains(" W common") || line.contains("warning:") || line.contains(" W model")) {
                return "WARN";
            }
            String lower = line.toLowerCase();
            if (lower.contains("error") || lower.contains("fatal")) return "ERROR";
            if (lower.contains("warn") || lower.contains("failed to fit")) return "WARN";
            if (lower.contains("slot") || lower.contains("ctx") || lower.contains("prompt eval")) return "SLOT";
            return "INFO";
        }
    }

    public record LogEntry(long id, long timestamp, String level, String message) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("timestamp", timestamp);
            map.put("level", level);
            map.put("message", message);
            return map;
        }
    }

    // =========================================================================
    // Ultra-Lightweight Zero-Dependency JSON Parser & Serializer
    // =========================================================================

    public static class SimpleJson {

        public static String toJson(Object obj) {
            if (obj == null) return "null";
            if (obj instanceof String s) return "\"" + escapeJson(s) + "\"";
            if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
            if (obj instanceof Map<?, ?> map) {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(escapeJson(String.valueOf(e.getKey()))).append("\":").append(toJson(e.getValue()));
                }
                sb.append("}");
                return sb.toString();
            }
            if (obj instanceof List<?> list) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object item : list) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append(toJson(item));
                }
                sb.append("]");
                return sb.toString();
            }
            return "\"" + escapeJson(obj.toString()) + "\"";
        }

        public static String toPrettyJson(Map<String, Object> map) {
            return toPretty(map, 0);
        }

        private static String toPretty(Object obj, int indent) {
            String space = "  ".repeat(indent);
            String childSpace = "  ".repeat(indent + 1);

            if (obj instanceof Map<?, ?> map) {
                if (map.isEmpty()) return "{}";
                StringBuilder sb = new StringBuilder("{\n");
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) sb.append(",\n");
                    first = false;
                    sb.append(childSpace).append("\"").append(escapeJson(String.valueOf(e.getKey()))).append("\": ")
                            .append(toPretty(e.getValue(), indent + 1));
                }
                sb.append("\n").append(space).append("}");
                return sb.toString();
            }
            if (obj instanceof List<?> list) {
                if (list.isEmpty()) return "[]";
                StringBuilder sb = new StringBuilder("[\n");
                boolean first = true;
                for (Object item : list) {
                    if (!first) sb.append(",\n");
                    first = false;
                    sb.append(childSpace).append(toPretty(item, indent + 1));
                }
                sb.append("\n").append(space).append("]");
                return sb.toString();
            }
            return toJson(obj);
        }

        @SuppressWarnings("unchecked")
        public static Map<String, Object> parseObject(String json) {
            if (json == null || json.isBlank()) return new LinkedHashMap<>();
            try {
                JsonTokenizer tokenizer = new JsonTokenizer(json.trim());
                Object obj = tokenizer.parseValue();
                if (obj instanceof Map<?, ?>) {
                    return (Map<String, Object>) obj;
                }
            } catch (Exception e) {
                System.err.println("[SimpleJson] Parse error: " + e.getMessage());
            }
            return new LinkedHashMap<>();
        }

        private static class JsonTokenizer {
            private final String src;
            private int pos = 0;

            public JsonTokenizer(String src) {
                this.src = src;
            }

            public Object parseValue() {
                skipWhitespace();
                if (pos >= src.length()) return null;
                char c = src.charAt(pos);
                if (c == '{') return parseMap();
                if (c == '[') return parseList();
                if (c == '"') return parseString();
                if (c == 't' || c == 'f') return parseBoolean();
                if (c == 'n') return parseNull();
                if (c == '-' || Character.isDigit(c)) return parseNumber();
                throw new RuntimeException("Unexpected char at pos " + pos + ": " + c);
            }

            private Map<String, Object> parseMap() {
                Map<String, Object> map = new LinkedHashMap<>();
                pos++; // skip '{'
                skipWhitespace();
                if (peek() == '}') {
                    pos++;
                    return map;
                }
                while (pos < src.length()) {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    if (peek() == ':') pos++;
                    Object val = parseValue();
                    map.put(key, val);
                    skipWhitespace();
                    if (peek() == ',') {
                        pos++;
                    } else if (peek() == '}') {
                        pos++;
                        break;
                    }
                }
                return map;
            }

            private List<Object> parseList() {
                List<Object> list = new ArrayList<>();
                pos++; // skip '['
                skipWhitespace();
                if (peek() == ']') {
                    pos++;
                    return list;
                }
                while (pos < src.length()) {
                    list.add(parseValue());
                    skipWhitespace();
                    if (peek() == ',') {
                        pos++;
                    } else if (peek() == ']') {
                        pos++;
                        break;
                    }
                }
                return list;
            }

            private String parseString() {
                pos++; // skip leading '"'
                StringBuilder sb = new StringBuilder();
                while (pos < src.length()) {
                    char c = src.charAt(pos++);
                    if (c == '"') return sb.toString();
                    if (c == '\\' && pos < src.length()) {
                        char esc = src.charAt(pos++);
                        switch (esc) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'u' -> {
                                String hex = src.substring(pos, pos + 4);
                                pos += 4;
                                sb.append((char) Integer.parseInt(hex, 16));
                            }
                            default -> sb.append(esc);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }

            private Boolean parseBoolean() {
                if (src.startsWith("true", pos)) {
                    pos += 4;
                    return true;
                }
                if (src.startsWith("false", pos)) {
                    pos += 5;
                    return false;
                }
                throw new RuntimeException("Invalid boolean");
            }

            private Object parseNull() {
                if (src.startsWith("null", pos)) {
                    pos += 4;
                    return null;
                }
                throw new RuntimeException("Invalid null");
            }

            private Number parseNumber() {
                int start = pos;
                if (src.charAt(pos) == '-') pos++;
                while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.' || src.charAt(pos) == 'e' || src.charAt(pos) == 'E' || src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                String s = src.substring(start, pos);
                if (s.contains(".") || s.contains("e") || s.contains("E")) {
                    return Double.parseDouble(s);
                }
                return Long.parseLong(s);
            }

            private void skipWhitespace() {
                while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                    pos++;
                }
            }

            private char peek() {
                return pos < src.length() ? src.charAt(pos) : '\0';
            }
        }
    }
}
