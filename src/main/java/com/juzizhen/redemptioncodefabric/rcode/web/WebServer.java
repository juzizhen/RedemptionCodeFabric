package com.juzizhen.redemptioncodefabric.rcode.web;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.juzizhen.redemptioncodefabric.RedemptionCodeFabric;
import com.juzizhen.redemptioncodefabric.async.AsyncIoManager;
import com.juzizhen.redemptioncodefabric.config.Config;
import com.juzizhen.redemptioncodefabric.rcode.manager.CodeManager;
import com.juzizhen.redemptioncodefabric.rcode.model.CodeData;
import com.juzizhen.redemptioncodefabric.rcode.model.CodeType;
import com.juzizhen.redemptioncodefabric.rcode.model.OperationLogEntry;
import com.juzizhen.redemptioncodefabric.util.Utils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.UserCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 基于 JDK 原生 {@link HttpServer} 的轻量级 Web 服务器，
 * 所有 I/O 操作通过 {@link AsyncIoManager#getIoExecutor()} 异步执行。
 */
public class WebServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("RedemptionCodeFabric-Web");
    private static final Gson GSON = new Gson();

    /**
     * 线程安全的日期格式化（SimpleDateFormat 非线程安全，不能共享实例）。
     * 每次调用创建新实例，适合低频使用场景。
     */
    private static String formatDate(long timestamp) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(timestamp));
    }

    private static WebServer instance;

    private HttpServer server;

    private WebServer() {
    }

    public static WebServer getInstance() {
        if (instance == null) {
            instance = new WebServer();
        }
        return instance;
    }

    /**
     * 启动 Web 服务器。
     *
     * @param port 监听端口
     */
    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);

            // 将所有请求处理委派到异步 I/O 线程池
            server.setExecutor(AsyncIoManager.getIoExecutor());

            // 初始化安全层独立 Redis 连接池（web.redis.* 配置，失败自动降级内存模式）
            SecurityFilter.initPool();

            // 注册路由
            registerRoutes();

            server.start();
            LOGGER.info("Web server started on port {}", port);

        } catch (IOException e) {
            LOGGER.error("Failed to start web server on port {}", port, e);
        }
    }

    /**
     * 注册所有 HTTP 路由，并统一挂载 SecurityFilter。
     */
    private void registerRoutes() {
        SecurityFilter securityFilter = new SecurityFilter();

        // 静态资源（HTML 页面）
        var rootCtx = server.createContext("/", new StaticResourceHandler("/assets/redemptioncodefabric/web/index.html"));
        rootCtx.getFilters().add(securityFilter);
        var indexCtx = server.createContext("/index.html", new StaticResourceHandler("/assets/redemptioncodefabric/web/index.html"));
        indexCtx.getFilters().add(securityFilter);

        String adminPath = Config.getString("web.adminPath", "/admin.html");
        if (!adminPath.startsWith("/")) {
            adminPath = "/" + adminPath;
        }
        var adminCtx = server.createContext(adminPath, new StaticResourceHandler("/assets/redemptioncodefabric/web/admin.html"));
        adminCtx.getFilters().add(securityFilter);

        // 语言文件
        var langCtx = server.createContext("/lang/", new LangFileHandler());
        langCtx.getFilters().add(securityFilter);

        // API 路由
        var loginCtx = server.createContext("/api/login", new LoginHandler());
        loginCtx.getFilters().add(securityFilter);
        var verifyCtx = server.createContext("/api/verify", new VerifyHandler());
        verifyCtx.getFilters().add(securityFilter);
        var logoutCtx = server.createContext("/api/logout", new LogoutHandler());
        logoutCtx.getFilters().add(securityFilter);
        var statusCtx = server.createContext("/api/status", new StatusHandler());
        statusCtx.getFilters().add(securityFilter);
        var playersCtx = server.createContext("/api/players", new PlayersHandler());
        playersCtx.getFilters().add(securityFilter);
        var scoreboardsCtx = server.createContext("/api/scoreboards", new ScoreboardsHandler());
        scoreboardsCtx.getFilters().add(securityFilter);
        var codesCtx = server.createContext("/api/codes", new CodesHandler());
        codesCtx.getFilters().add(securityFilter);
        var logsCtx = server.createContext("/api/logs", new LogsHandler());
        logsCtx.getFilters().add(securityFilter);
        var reloadCtx = server.createContext("/api/reload", new ReloadHandler());
        reloadCtx.getFilters().add(securityFilter);
        var configCtx = server.createContext("/api/config", new ConfigHandler());
        configCtx.getFilters().add(securityFilter);
        var statsCtx = server.createContext("/api/stats/daily", new StatsDailyHandler());
        statsCtx.getFilters().add(securityFilter);
        var exportCtx = server.createContext("/api/export", new ExportHandler());
        exportCtx.getFilters().add(securityFilter);
    }

    /**
     * 停止 Web 服务器。
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            LOGGER.info("Web server stopped.");
        }
        // 关闭安全层独立 Redis 连接池
        SecurityFilter.shutdownPool();
    }

    private MinecraftServer getServer() {
        return RedemptionCodeFabric.getServerInstance();
    }

    private String mask(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() <= 2) return "***";
        return s.charAt(0) + "*".repeat(s.length() - 2) + s.charAt(s.length() - 1);
    }

    private String getClientIp(HttpExchange exchange) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = GSON.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, Object> parseJsonBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            return GSON.fromJson(body, mapType);
        }
    }

    private Map<String, String> parseFormBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = new HashMap<>();
            for (String param : body.split("&")) {
                String[] parts = param.split("=", 2);
                if (parts.length == 2) {
                    params.put(java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                              java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                }
            }
            return params;
        }
    }

    private void serveResource(HttpExchange exchange, String path, String contentType) throws IOException {
        try (InputStream is = WebServer.class.getResourceAsStream(path)) {
            if (is == null) {
                sendJsonResponse(exchange, 404, Map.of("error", "Not Found"));
                return;
            }
            byte[] bytes = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class StaticResourceHandler implements HttpHandler {
        private final String resourcePath;

        StaticResourceHandler(String resourcePath) {
            this.resourcePath = resourcePath;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            serveResource(exchange, resourcePath, "text/html");
        }
    }

    private class LangFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String fileName = path.substring("/lang/".length());
            if (!fileName.matches("[a-z]{2}_[a-z]{2}\\.json")) {
                sendJsonResponse(exchange, 404, Map.of("error", "Not Found"));
                return;
            }
            serveResource(exchange, "/assets/redemptioncodefabric/web/lang/" + fileName, "application/json");
        }
    }

    private class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, Map.of("success", false, "message", "Method not allowed"));
                return;
            }

            String clientIp = getClientIp(exchange);
            Map<String, String> form = parseFormBody(exchange);
            String user = form.get("user");
            String pass = form.get("password");
            String cfgUser = Config.getString("web.user", "admin");
            String cfgPass = Config.getString("web.password", "");

            if (cfgUser.equals(user) && cfgPass.equals(pass)) {
                String token = UUID.randomUUID().toString().replace("-", "");
                // 抢占式写入：新 Token 覆盖旧会话，旧设备立即失效
                SecurityFilter.setActiveToken(token);
                LOGGER.info("Web login success for user: {} from IP: {}", user, clientIp);
                String adminPath = Config.getString("web.adminPath", "/admin.html");
                sendJsonResponse(exchange, 200, Map.of("success", true, "token", token, "adminPath", adminPath));
            } else {
                LOGGER.warn("Web login failed for user: {} from IP: {}", user, clientIp);
                sendJsonResponse(exchange, 401, Map.of("success", false, "message", "用户名或密码错误"));
            }
        }
    }

    private class VerifyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendJsonResponse(exchange, 200, Map.of("success", true, "valid", true));
        }
    }

    private class LogoutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            SecurityFilter.clearActiveToken();
            sendJsonResponse(exchange, 200, Map.of("success", true));
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            CodeManager cm = RedemptionCodeFabric.codeManager;
            MinecraftServer server = getServer();
            int onlinePlayers = server != null ? server.getCurrentPlayerCount() : 0;
            int totalCodes = cm != null ? cm.getAllCodes().size() : 0;
            String dsType = Config.getString("datastore.type", "file");
            String modVersion = RedemptionCodeFabric.getModVersion();
            String mcVersion = server != null ? server.getVersion() : "Unknown";

            sendJsonResponse(exchange, 200, Map.of(
                    "success", true,
                    "data", Map.of(
                            "onlinePlayers", onlinePlayers,
                            "totalCodes", totalCodes,
                            "datastoreType", dsType,
                            "modVersion", modVersion,
                            "mcVersion", mcVersion
                    )
            ));
        }
    }

    private class PlayersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            MinecraftServer server = getServer();
            List<Map<String, Object>> players = new ArrayList<>();
            if (server != null) {
                for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", p.getName().getString());
                    info.put("uuid", p.getUuid().toString());
                    info.put("ping", p.pingMilliseconds);
                    info.put("ip", p.getIp());
                    info.put("tags", new ArrayList<>(p.getCommandTags()));
                    players.add(info);
                }
            }
            sendJsonResponse(exchange, 200, Map.of("success", true, "data", players));
        }
    }

    private class ScoreboardsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            MinecraftServer server = getServer();
            List<Map<String, String>> objectives = new ArrayList<>();
            if (server != null) {
                var scoreboard = server.getScoreboard();
                for (var obj : scoreboard.getObjectives()) {
                    Map<String, String> info = new LinkedHashMap<>();
                    info.put("name", obj.getName());
                    info.put("displayName", obj.getDisplayName().getString());
                    objectives.add(info);
                }
            }
            sendJsonResponse(exchange, 200, Map.of("success", true, "data", objectives));
        }
    }

    private class CodesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                handleGetCodes(exchange);
            } else if ("POST".equals(method)) {
                handleCreateCode(exchange);
            } else if ("DELETE".equals(method)) {
                handleDeleteCode(exchange);
            } else {
                sendJsonResponse(exchange, 405, Map.of("success", false, "message", "Method not allowed"));
            }
        }

        private void handleGetCodes(HttpExchange exchange) throws IOException {
            CodeManager cm = RedemptionCodeFabric.codeManager;
            if (cm == null) {
                sendJsonResponse(exchange, 500, Map.of("success", false, "message", "CodeManager not initialized"));
                return;
            }

            // 检查是否是单个代码查询
            String path = exchange.getRequestURI().getPath();
            if (path.matches("/api/codes/.+")) {
                String code = java.net.URLDecoder.decode(path.substring("/api/codes/".length()), StandardCharsets.UTF_8);
                handleGetSingleCode(exchange, cm, code);
                return;
            }

            List<Map<String, Object>> list = new ArrayList<>();
            for (CodeData cd : cm.getAllCodes().values()) {
                Map<String, Object> item = buildCodeSummary(cd);
                list.add(item);
            }
            list.sort(Comparator.comparing(m -> (String) m.get("code")));
            sendJsonResponse(exchange, 200, Map.of("success", true, "data", list));
        }

        private void handleGetSingleCode(HttpExchange exchange, CodeManager cm, String code) throws IOException {
            CodeData cd = cm.getCode(code);
            if (cd == null) {
                sendJsonResponse(exchange, 404, Map.of("success", false, "message", "兑换码不存在"));
                return;
            }

            Map<String, Object> detail = buildCodeDetail(cd);
            sendJsonResponse(exchange, 200, Map.of("success", true, "data", detail));
        }

        private void handleCreateCode(HttpExchange exchange) throws IOException {
            CodeManager cm = RedemptionCodeFabric.codeManager;
            MinecraftServer server = getServer();
            if (cm == null || server == null) {
                sendJsonResponse(exchange, 500, Map.of("success", false, "message", "服务未就绪"));
                return;
            }

            Map<String, Object> req = parseJsonBody(exchange);

            String typeStr = (String) req.get("type");
            String reward = (String) req.get("reward");
            if (typeStr == null || reward == null || reward.trim().isEmpty()) {
                sendJsonResponse(exchange, 400, Map.of("success", false, "message", "类型和奖励不能为空"));
                return;
            }

            CodeType type;
            try {
                type = CodeType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, Map.of("success", false, "message", "无效的兑换码类型: " + typeStr));
                return;
            }

            String code = req.containsKey("code") && req.get("code") instanceof String s && !s.trim().isEmpty()
                    ? s.trim() : Utils.generateRandomString(16);

            if (cm.getCode(code) != null) {
                sendJsonResponse(exchange, 400, Map.of("success", false, "message", "兑换码已存在: " + code));
                return;
            }

            String player = null;
            int count = -1;
            long startTime = 0, endTime = 0, interval = 0;

            if (type == CodeType.GLOBAL_LIMIT) {
                String selectMode = req.containsKey("selectMode") ? (String) req.get("selectMode") : "manual";
                int limit = req.containsKey("limit") ? ((Number) req.get("limit")).intValue() : 0;
                if (limit == 0) {
                    sendJsonResponse(exchange, 400, Map.of("success", false, "message", "limit 不能为 0"));
                    return;
                }

                List<String> selectedUuids = new ArrayList<>();

                if ("tag".equals(selectMode)) {
                    String tag = req.containsKey("tag") ? ((String) req.get("tag")).trim() : "";
                    if (tag.isEmpty()) {
                        sendJsonResponse(exchange, 400, Map.of("success", false, "message", "tag 不能为空"));
                        return;
                    }
                    List<ServerPlayerEntity> tagged = server.getPlayerManager().getPlayerList().stream()
                            .filter(p -> p.getCommandTags().contains(tag))
                            .toList();
                    List<ServerPlayerEntity> picked;
                    if (limit > 0) {
                        picked = tagged.stream().limit(limit).toList();
                    } else {
                        int pos = -limit;
                        if (tagged.size() > pos) {
                            picked = tagged.subList(tagged.size() - pos, tagged.size());
                        } else {
                            picked = tagged;
                        }
                    }
                    for (ServerPlayerEntity p : picked) selectedUuids.add(p.getUuid().toString());

                } else if ("scoreboard".equals(selectMode)) {
                    String objName = req.containsKey("scoreboard") ? ((String) req.get("scoreboard")).trim() : "";
                    if (objName.isEmpty()) {
                        sendJsonResponse(exchange, 400, Map.of("success", false, "message", "scoreboard 不能为空"));
                        return;
                    }
                    Scoreboard scoreboard = server.getScoreboard();
                    ScoreboardObjective obj = scoreboard.getNullableObjective(objName);
                    if (obj == null) {
                        sendJsonResponse(exchange, 400, Map.of("success", false, "message", "计分板目标不存在: " + objName));
                        return;
                    }
                    var comparator = Comparator.comparingInt(ScoreboardPlayerScore::getScore);
                    if (limit > 0) comparator = comparator.reversed();
                    List<String> playerNames = scoreboard.getAllPlayerScores(obj).stream()
                            .sorted(comparator)
                            .limit(Math.abs(limit))
                            .map(ScoreboardPlayerScore::getPlayerName)
                            .toList();
                    UserCache userCache = server.getUserCache();
                    if (userCache != null) {
                        for (String name : playerNames) {
                            userCache.findByName(name)
                                    .ifPresent(profile -> selectedUuids.add(profile.getId().toString()));
                        }
                    }

                } else {
                    player = req.containsKey("player") ? (String) req.get("player") : null;
                }

                if (!selectedUuids.isEmpty()) {
                    player = String.join(",", selectedUuids);
                }
                count = req.containsKey("count") ? ((Number) req.get("count")).intValue() : (selectedUuids.isEmpty() ? -1 : selectedUuids.size());

            } else if (type == CodeType.PERSONAL) {
                player = req.containsKey("player") ? (String) req.get("player") : null;
            } else if (type == CodeType.TIMED) {
                startTime = req.containsKey("startTime") ? ((Number) req.get("startTime")).longValue() : 0;
                endTime = req.containsKey("endTime") ? ((Number) req.get("endTime")).longValue() : 0;
            } else if (type == CodeType.CYCLE) {
                startTime = req.containsKey("startTime") ? ((Number) req.get("startTime")).longValue() : 0;
                interval = req.containsKey("interval") ? ((Number) req.get("interval")).longValue() : 0;
            }

            CodeData codeData = new CodeData(code, type, reward, player, count, startTime, endTime, interval);
            server.execute(() -> cm.addCode(codeData, "WebAdmin"));
            sendJsonResponse(exchange, 200, Map.of("success", true, "message", "创建成功", "code", code));
        }

        private void handleDeleteCode(HttpExchange exchange) throws IOException {
            CodeManager cm = RedemptionCodeFabric.codeManager;
            MinecraftServer server = getServer();

            String path = exchange.getRequestURI().getPath();
            String code = java.net.URLDecoder.decode(path.substring("/api/codes/".length()), StandardCharsets.UTF_8);

            if (cm == null || server == null) {
                sendJsonResponse(exchange, 500, Map.of("success", false, "message", "服务未就绪"));
                return;
            }
            if (cm.getCode(code) == null) {
                sendJsonResponse(exchange, 404, Map.of("success", false, "message", "兑换码不存在: " + code));
                return;
            }
            server.execute(() -> cm.deleteCode(code, "WebAdmin"));
            sendJsonResponse(exchange, 200, Map.of("success", true, "message", "删除成功"));
        }

        private Map<String, Object> buildCodeSummary(CodeData cd) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", cd.getCode());
            item.put("type", cd.getType().name());
            item.put("reward", cd.getReward());
            item.put("usedByCount", cd.getUsedBy().size());
            int totalUses = cd.getUsedBy().values().stream().mapToInt(List::size).sum();
            item.put("totalUses", totalUses);
            if (cd.getCount() != -1) item.put("count", cd.getCount());
            if (cd.getPlayer() != null && !cd.getPlayer().isEmpty()) item.put("player", cd.getPlayer());
            if (cd.getStartTime() != 0) item.put("startTime", cd.getStartTime());
            if (cd.getEndTime() != 0) item.put("endTime", cd.getEndTime());
            if (cd.getInterval() != 0) item.put("interval", cd.getInterval());
            return item;
        }

        private Map<String, Object> buildCodeDetail(CodeData cd) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("code", cd.getCode());
            detail.put("type", cd.getType().name());
            detail.put("reward", cd.getReward());
            detail.put("count", cd.getCount());
            detail.put("player", cd.getPlayer());
            detail.put("startTime", cd.getStartTime());
            detail.put("endTime", cd.getEndTime());
            detail.put("interval", cd.getInterval());
            if (cd.getStartTime() != 0) detail.put("startTimeStr", formatDate(cd.getStartTime()));
            if (cd.getEndTime() != 0) detail.put("endTimeStr", formatDate(cd.getEndTime()));

            List<Map<String, Object>> usedByList = new ArrayList<>();
            for (Map.Entry<String, List<Long>> entry : cd.getUsedBy().entrySet()) {
                Map<String, Object> usage = new LinkedHashMap<>();
                usage.put("uuid", entry.getKey());
                usage.put("times", entry.getValue().size());
                List<String> timestamps = new ArrayList<>();
                for (Long ts : entry.getValue()) timestamps.add(formatDate(ts));
                usage.put("timestamps", timestamps);
                usedByList.add(usage);
            }
            usedByList.sort((a, b) -> Integer.compare((int) b.get("times"), (int) a.get("times")));
            detail.put("usedBy", usedByList);

            return detail;
        }
    }

    private class LogsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            CodeManager cm = RedemptionCodeFabric.codeManager;
            if (cm == null) {
                sendJsonResponse(exchange, 500, Map.of("success", false, "message", "CodeManager not initialized"));
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            int offset = 0, limit = 50;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] parts = param.split("=", 2);
                    if (parts.length == 2) {
                        if ("offset".equals(parts[0])) {
                            try { offset = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
                        } else if ("limit".equals(parts[0])) {
                            try { limit = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
            limit = Math.min(limit, 200);

            List<OperationLogEntry> logs = cm.getOperationLog(offset, limit);
            List<Map<String, Object>> result = new ArrayList<>();
            for (OperationLogEntry entry : logs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("timestamp", entry.getTimestamp());
                item.put("time", formatDate(entry.getTimestamp()));
                item.put("type", entry.getOperationType());
                item.put("executor", entry.getExecutor());
                item.put("details", entry.getDetails());
                result.add(item);
            }
            sendJsonResponse(exchange, 200, Map.of("success", true, "data", result));
        }
    }

    private class ReloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, Map.of("success", false, "message", "Method not allowed"));
                return;
            }

            MinecraftServer server = getServer();
            if (server == null) {
                sendJsonResponse(exchange, 500, Map.of("success", false, "message", "服务器未就绪"));
                return;
            }
            server.execute(RedemptionCodeFabric::reloadConfig);
            sendJsonResponse(exchange, 200, Map.of("success", true, "message", "配置已重新加载"));
        }
    }

    private class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("datastore.type", Config.getString("datastore.type", "file"));
            cfg.put("web.enabled", Config.getBoolean("web.enabled", false));
            cfg.put("web.url", Config.getString("web.url", "http://localhost"));
            cfg.put("web.port", Config.getInt("web.port", 8080));
            cfg.put("web.user", Config.getString("web.user", "admin"));
            cfg.put("web.password", mask(Config.getString("web.password", "")));
            cfg.put("web.adminPath", Config.getString("web.adminPath", ""));
            cfg.put("web.sendUrlToOP", Config.getBoolean("web.sendUrlToOP", true));
            cfg.put("web.redis.enabled", Config.getBoolean("web.redis.enabled", false));
            cfg.put("web.redis.host", Config.getString("web.redis.host", "localhost"));
            cfg.put("web.redis.port", Config.getInt("web.redis.port", 6379));
            cfg.put("web.redis.password", mask(Config.getString("web.redis.password", "")));
            cfg.put("web.redis.database", Config.getInt("web.redis.database", 0));
            cfg.put("log.redemption.history", Config.getBoolean("log.redemption.history", true));
            cfg.put("log.max.entries", Config.getInt("log.max.entries", 20000));
            cfg.put("sql.host", Config.getString("sql.host", "localhost"));
            cfg.put("sql.port", Config.getInt("sql.port", 3306));
            cfg.put("sql.user", Config.getString("sql.user", "admin"));
            cfg.put("sql.password", mask(Config.getString("sql.password", "")));
            cfg.put("sql.database", Config.getString("sql.database", "redemptioncode"));
            cfg.put("sql.url", Config.getString("sql.url", ""));
            cfg.put("redis.host", Config.getString("redis.host", "localhost"));
            cfg.put("redis.port", Config.getInt("redis.port", 6379));
            cfg.put("redis.password", mask(Config.getString("redis.password", "")));
            cfg.put("redis.database", Config.getInt("redis.database", 0));
            cfg.put("pool.maxPoolSize", Config.getInt("pool.maxPoolSize", 10));
            cfg.put("pool.minIdle", Config.getInt("pool.minIdle", 2));
            cfg.put("pool.connectionTimeout", Config.getInt("pool.connectionTimeout", 30000));
            cfg.put("pool.idleTimeout", Config.getInt("pool.idleTimeout", 600000));
            cfg.put("pool.maxLifetime", Config.getInt("pool.maxLifetime", 1800000));
            cfg.put("pool.leakDetectionThreshold", Config.getInt("pool.leakDetectionThreshold", 60000));
            cfg.put("pool.keepaliveTime", Config.getInt("pool.keepaliveTime", 300000));
            cfg.put("pool.validationTimeout", Config.getInt("pool.validationTimeout", 5000));
            cfg.put("pool.connectionInitSql", Config.getString("pool.connectionInitSql", ""));
            sendJsonResponse(exchange, 200, Map.of("success", true, "data", cfg));
        }
    }

    /**
     * 近 7 天兑换趋势统计：从操作日志中统计 REDEEM 类型的每日次数。
     */
    private class StatsDailyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            CodeManager cm = RedemptionCodeFabric.codeManager;
            if (cm == null) {
                sendJsonResponse(exchange, 500, Map.of("success", false, "message", "CodeManager not initialized"));
                return;
            }

            // 取最近 7 天的日期标签
            java.text.SimpleDateFormat dayFmt = new java.text.SimpleDateFormat("MM-dd");
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);

            String[] labels = new String[7];
            long[] dayStarts = new long[8]; // 8 个边界用于区间判断
            for (int i = 6; i >= 0; i--) {
                java.util.Calendar d = (java.util.Calendar) cal.clone();
                d.add(java.util.Calendar.DAY_OF_MONTH, -(i));
                labels[6 - i] = dayFmt.format(d.getTime());
                dayStarts[6 - i] = d.getTimeInMillis();
            }
            dayStarts[7] = System.currentTimeMillis() + 1;

            int[] counts = new int[7];

            // 从操作日志中批量读取 REDEEM 记录（最多取 5000 条）
            List<OperationLogEntry> logs = cm.getOperationLog(0, 5000);
            for (OperationLogEntry entry : logs) {
                if (!"REDEEM".equals(entry.getOperationType())) continue;
                long ts = entry.getTimestamp();
                for (int i = 0; i < 7; i++) {
                    if (ts >= dayStarts[i] && ts < dayStarts[i + 1]) {
                        counts[i]++;
                        break;
                    }
                }
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", labels[i]);
                item.put("count", counts[i]);
                result.add(item);
            }
            sendJsonResponse(exchange, 200, Map.of("success", true, "data", result));
        }
    }

    /**
     * CSV 流式导出：将所有兑换码以 CSV 格式写入响应流。
     */
    private class ExportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            CodeManager cm = RedemptionCodeFabric.codeManager;
            if (cm == null) {
                sendJsonResponse(exchange, 500, Map.of("success", false, "message", "CodeManager not initialized"));
                return;
            }

            exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"redemption_codes.csv\"");
            exchange.sendResponseHeaders(200, 0); // chunked

            try (OutputStream os = exchange.getResponseBody()) {
                // BOM for Excel UTF-8 compatibility
                os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                String header = "code,type,reward,player,count,total_uses,start_time,end_time,interval\n";
                os.write(header.getBytes(StandardCharsets.UTF_8));

                for (CodeData cd : cm.getAllCodes().values()) {
                    int totalUses = cd.getUsedBy().values().stream().mapToInt(List::size).sum();
                    String line = String.format("%s,%s,%s,%s,%d,%d,%d,%d,%d\n",
                            csvEscape(cd.getCode()),
                            csvEscape(cd.getType().name()),
                            csvEscape(cd.getReward()),
                            csvEscape(cd.getPlayer() != null ? cd.getPlayer() : ""),
                            cd.getCount(),
                            totalUses,
                            cd.getStartTime(),
                            cd.getEndTime(),
                            cd.getInterval());
                    os.write(line.getBytes(StandardCharsets.UTF_8));
                }
                os.flush();
            }
        }

        private String csvEscape(String value) {
            if (value == null) return "";
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                return "\"" + value.replace("\"", "\"\"") + "\"";
            }
            return value;
        }
    }
}
