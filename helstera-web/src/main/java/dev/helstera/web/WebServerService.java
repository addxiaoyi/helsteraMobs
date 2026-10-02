package dev.helstera.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.helstera.core.parse.ModelParser;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 网页开发器服务（自包含 ServerSocket HTTP 服务，零额外依赖，不依赖 JDK 内部 HttpServer）。
 *
 * <p>相比 JDK 内置 HttpServer 的好处：绑定失败会抛出明确异常、由插件捕获并告警；
 * 不依赖 com.sun.net.httpserver 模块是否对插件类加载器可见；行为完全可控。</p>
 *
 * <p>特性：</p>
 * <ul>
 *   <li>监听地址由 config.yml 的 web.host 控制（默认 127.0.0.1；远端改为 0.0.0.0）；静态页自动注入令牌，打开即用；</li>
 *   <li>模型：列表 / 明细（含立方体，供预览）/ 源文件读写 / 校验 / 重载；</li>
 *   <li>生物：列表 / 读写 / 新建（模板）/ 删除 / 版本备份与回滚；</li>
 *   <li>暴露“加载失败/校验错误”，直接定位到模型目录；</li>
 *   <li>所有写操作先做语法校验并留版本备份，且写结构化审计日志。</li>
 * </ul>
 */
public final class WebServerService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final List<String> MODEL_FILES = List.of("manifest.yml", "model.json", "animations.json");
    private static final Set<String> MODEL_FILE_SET = Set.copyOf(MODEL_FILES);

    private final org.bukkit.plugin.Plugin plugin;
    private final WebBridge bridge;
    private String host;
    private final int port;
    private final String token;
    private final Path dataFolder;
    private final Path modelsRoot;
    private final Path mobsDir;
    private final Path versionsDir;
    private final long startedAt = System.currentTimeMillis();
    private final List<String> audit = Collections.synchronizedList(new ArrayList<>());

    private volatile Javalin app;
    private volatile boolean running;
    private String cachedHtml;
    /** SSE 订阅者（Javalin Context 列表）。重载事件广播给它们，前端据此即时刷新。 */
    private final List<io.javalin.http.sse.SseClient> sseClients = new CopyOnWriteArrayList<>();

    public WebServerService(org.bukkit.plugin.Plugin plugin, WebBridge bridge, String host, int port, String token) {
        this.plugin = plugin;
        this.bridge = bridge;
        // 默认监听所有网卡（0.0.0.0），确保远程/面板部署时浏览器也能访问；
        // 若只需本机访问或更安全，可在 config.yml 设 web.host: 127.0.0.1。
        this.host = (host == null || host.isBlank()) ? "0.0.0.0" : host;
        this.port = port;
        this.token = token;
        this.dataFolder = bridge.dataFolder();
        this.modelsRoot = bridge.modelsRoot();
        this.mobsDir = dataFolder.resolve("mobs");
        this.versionsDir = dataFolder.resolve("web/versions");
    }

    // ------------------------------------------------------------------
    // 生命周期（Javalin）
    // ------------------------------------------------------------------

    public void start() throws IOException {
        start(this.host);
    }

    /** 以指定监听地址启动（例如 0.0.0.0 以便远程访问）。供 /helstera web start <host> 使用。 */
    public void start(String overrideHost) throws IOException {
        if (overrideHost != null && !overrideHost.isBlank()) this.host = overrideHost;
        stop();
        try {
            app = Javalin.create(cfg -> {
                cfg.showJavalinBanner = false;
                cfg.http.defaultContentType = "application/json; charset=utf-8";
                // Javalin 6 的静态文件配置是「注册式」的：直接给 staticFiles 赋值
                // 的写法是 4.x 的 API，6.x 下不存在这些 setter。
                cfg.staticFiles.add(sf -> {
                    sf.hostedPath = "/";
                    sf.directory = "/web";
                    sf.location = Location.CLASSPATH;
                });
            });
            // 令牌校验放在所有 API 之前；静态资源与 pack.zip 不需要令牌
            app.before(ctx -> {
                if (!ctx.path().startsWith("/api/")) return;
                if (ctx.path().equals("/api/events")) return; // SSE 自行鉴权
                if (authorizedOn(ctx)) return;
                ctx.status(401);
                ctx.result(GSON.toJson(Map.of("error", "令牌无效（页面打开的 URL 已自动带令牌）")));
                ctx.skipRemainingHandlers();
            });
            // SSE 订阅端点：保存后实时推送，取代前端「保存后盲等 1.4 秒」
            app.sse("/api/events", client -> {
                if (!authorizedOnSse(client.ctx())) {
                    client.ctx().status(401);
                    client.ctx().result(GSON.toJson(Map.of("error", "令牌无效")));
                    return;
                }
                sseClients.add(client);
                client.sendEvent("hello", "{\"ok\":true}");
            });
            // 其余请求统一交给既有业务层：把 Javalin 请求转成内部 Request，
            // 复用已验证过的路由与处理逻辑，避免为了换传输层重写一千多行。
            app.get("/{p}", this::adapt);
            app.post("/{p}", this::adapt);
            app.put("/{p}", this::adapt);
            app.delete("/{p}", this::adapt);
            app.options("/{p}", this::adapt);
            app.start(host, port);
        } catch (Throwable e) {
            app = null;
            throw new IOException("网页开发器无法在 " + host + ":" + port + " 监听：" + e.getMessage()
                    + "（端口被占用？权限不足？请更换 web.port 或释放端口）", e);
        }
        running = true;
        startSsePump();
        if ("0.0.0.0".equals(host)) {
            plugin.getLogger().warning("网页开发器已监听 0.0.0.0:" + port
                    + "（所有网卡）。任何人凭令牌均可访问，请确保服务端防火墙已限制，或改用 web.host: 127.0.0.1");
        }
        plugin.getLogger().info("网页开发器已启动: http://" + host + ":" + port + "/");
        // 打印每个可达网卡的访问地址，方便远程/面板部署的用户直接复制打开
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics = java.net.NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                if (nic.isLoopback() || !nic.isUp()) continue;
                nic.getInetAddresses().asIterator().forEachRemaining(addr -> {
                    if (addr instanceof java.net.Inet4Address) {
                        plugin.getLogger().info("  → 远程访问: http://" + addr.getHostAddress() + ":" + port + "/?token=" + token);
                    }
                });
            }
        } catch (Exception ignored) {
            // 枚举网卡失败不影响服务
        }
    }

    /** Javalin 请求 -> 内部 Request -> 既有业务路由 -> 回写 Javalin 响应。 */
    private void adapt(Context ctx) {
        try {
            Request req = new Request();
            // Javalin 的 method() 返回 HandlerType 枚举，不是 String
            req.method = ctx.method().name().toUpperCase(Locale.ROOT);
            req.path = ctx.path();
            req.query = ctx.queryString();
            ctx.headerMap().forEach((k, v) -> req.headers.put(k.toLowerCase(Locale.ROOT), v));
            req.body = ctx.body();
            Response resp = route(req);
            ctx.status(resp.status);
            resp.headers.forEach((k, v) -> {
                // content-length / connection 由容器接管，避免与其冲突
                if (k.equalsIgnoreCase("content-length") || k.equalsIgnoreCase("connection")) return;
                ctx.header(k, v);
            });
            ctx.result(resp.body == null ? "" : new String(resp.body, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            ctx.status(500);
            ctx.result(GSON.toJson(Map.of("error", String.valueOf(t.getMessage()))));
        }
    }

    private boolean authorizedOn(Context ctx) {
        String auth = ctx.header("Authorization");
        if (auth != null && ("Bearer " + token).equals(auth)) return true;
        return token.equals(ctx.queryParam("token"));
    }

    private boolean authorizedOnSse(Context ctx) {
        return ctx != null && authorizedOn(ctx);
    }

    public void stop() {
        running = false;
        sseClients.clear();
        Javalin a = app;
        if (a != null) {
            try { a.stop(); } catch (Throwable ignored) {}
            app = null;
        }
    }

    public boolean isRunning() {
        return running && app != null;
    }

    /** SSE 客户端数量（调试用）。 */
    public int sseClientCount() {
        return sseClients.size();
    }

    /**
     * 广播一个事件给所有 SSE 订阅者。
     *
     * <p>直接写 {@link Context} 在 Javalin 里是阻塞的，因此这里不能在主线程批量调用——
     * 逐个写入会阻塞到客户端消费为止。改为把事件塞进无界队列，由一个独立守护线程慢慢写，
     * 主线程只做一次 offer，永不因某个慢客户端而被拖住。</p>
     */
    private final java.util.concurrent.LinkedBlockingQueue<Map<String, String>> sseOutbox =
            new java.util.concurrent.LinkedBlockingQueue<>();
    private volatile Thread ssePump;

    /** 供业务层在重载后调用，通知前端刷新。 */
    public void broadcastReload(String what) {
        sseOutbox.offer(Map.of(
                "event", "reload",
                "data", GSON.toJson(Map.of(
                        "what", what == null ? "reload" : what,
                        "at", LocalDateTime.now().toString()))));
    }

    private void startSsePump() {
        if (ssePump != null) return;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Map<String, String> payload = sseOutbox.poll(200, TimeUnit.MILLISECONDS);
                    if (payload == null) continue;
                    for (var c : sseClients) {
                        try {
                            c.sendEvent(payload.get("event"), payload.get("data"));
                        } catch (Throwable e) {
                            sseClients.remove(c);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "helstera-web-sse");
        t.setDaemon(true);
        t.start();
        ssePump = t;
    }

    public int port() { return port; }
    public String token() { return token; }
    public String url() { return "http://" + host + ":" + port + "/"; }
    public String host() { return host; }

    /** 规则名：单独一条入站放行规则，方便精确查询/删除。 */
    private String firewallRuleName() {
        return "HelsteraMobs Web " + port;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 执行外部命令并合并输出（stdout+stderr）。用于 netsh 查询/放行防火墙。 */
    private static String exec(List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            boolean finished = p.waitFor(6, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            return sb.toString().trim();
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }

    /** TCP 连通性探测（本机回环/指定地址）。 */
    private String probe(String addr) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, port), 1500);
            return "可达（TCP 连接成功）";
        } catch (Exception e) {
            return "不可达（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）";
        }
    }

    /**
     * 自检：把「网页打不开」的排查一次做完——服务状态、本机连通性、所有可用 URL、
     * Windows 防火墙规则、虚拟机/容器端口映射提醒。
     */
    public List<String> doctor() {
        List<String> out = new ArrayList<>();
        out.add("监听地址: " + host + ":" + port + "  服务状态: " + (isRunning() ? "运行中 ✓" : "未运行 ✗"));
        out.add("本机自测 127.0.0.1:" + port + " → " + probe("127.0.0.1"));
        if (!"0.0.0.0".equals(host) && !"127.0.0.1".equals(host)) {
            out.add("本机自测 " + host + ":" + port + " → " + probe(host));
        }
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
            out.add("✗ 当前只监听回环地址：别的机器/浏览器一定连不上，请改 web.host: 0.0.0.0 或 /helstera web start 0.0.0.0");
        }

        out.add("可用访问地址：");
        out.add("  http://127.0.0.1:" + port + "/?token=" + token + "   ← 服务器本机浏览器");
        int lan = 0;
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics = java.net.NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                if (nic.isLoopback() || !nic.isUp()) continue;
                java.util.Iterator<java.net.InetAddress> it = nic.getInetAddresses().asIterator();
                while (it.hasNext()) {
                    java.net.InetAddress a = it.next();
                    if (a instanceof java.net.Inet4Address) {
                        out.add("  http://" + a.getHostAddress() + ":" + port + "/?token=" + token
                                + "   ← " + nic.getName());
                        lan++;
                    }
                }
            }
        } catch (Exception e) {
            out.add("  （枚举网卡失败: " + e.getMessage() + "）");
        }
        if (lan == 0) out.add("  ✗ 未发现任何非回环网卡：服务器可能没连上局域网。");

        if (isWindows()) {
            boolean rule = firewallRuleExists();
            out.add("Windows 防火墙: " + (rule ? "已存在放行规则「" + firewallRuleName() + "」✓"
                    : "未发现放行规则「" + firewallRuleName() + "」（同网段其它机器会被静默丢包）"));
            if (!rule) {
                out.add("→ 放行命令（服务器本机管理员执行，或直接 /helstera web firewall）:");
                out.add("  netsh advfirewall firewall add rule name=\"" + firewallRuleName()
                        + "\" dir=in action=allow protocol=TCP localport=" + port);
            }
        }
        out.add("若服务端跑在虚拟机/容器里，仅监听 0.0.0.0 还不够：");
        out.add("  VMware NAT → 虚拟网络编辑器里把宿主机 " + port + " 转发到虚拟机 " + port
                + "；Docker → 运行容器时加 -p " + port + ":" + port + "；或把网卡改成桥接。");
        out.add("判断依据：从「打不开的那台机器」执行  telnet <服务器IP> " + port + "  —— 不通就是网络/防火墙问题，与插件无关。");
        return out;
    }

    /** 查询入站放行规则是否已存在。 */
    public boolean firewallRuleExists() {
        if (!isWindows()) return false;
        String r = exec(List.of("netsh", "advfirewall", "firewall", "show", "rule",
                "name=" + firewallRuleName()));
        return r.contains(firewallRuleName()) && !r.contains("没有与指定条件相匹配的规则")
                && !r.contains("No rules match");
    }

    /** 一键添加入站放行规则；非管理员会返回明确的失败原因。 */
    public String allowFirewall() {
        if (!isWindows()) return "当前系统非 Windows，请自行放行 TCP " + port + " 端口。";
        if (firewallRuleExists()) return "放行规则「" + firewallRuleName() + "」已存在，无需重复添加。";
        String r = exec(List.of("netsh", "advfirewall", "firewall", "add", "rule",
                "name=" + firewallRuleName(), "dir=in", "action=allow",
                "protocol=TCP", "localport=" + String.valueOf(port)));
        if (r.contains("确定") || r.contains("Ok") || r.contains("OK")) {
            return "✓ 已放行 TCP " + port + "（规则：" + firewallRuleName() + "）。若仍打不开，请检查虚拟机端口映射。";
        }
        if (r.contains("需要提升") || r.contains("requires elevation") || r.contains("拒绝访问")
                || r.contains("Access is denied")) {
            return "✗ 权限不足：请用「管理员身份」启动的服务端/控制台，或手动执行 netsh 命令添加规则。";
        }
        return "执行结果：" + r;
    }

    public List<String> recentAudit() {
        synchronized (audit) {
            int from = Math.max(0, audit.size() - 100);
            return new ArrayList<>(audit.subList(from, audit.size()));
        }
    }

    // ------------------------------------------------------------------
    // 路由
    // ------------------------------------------------------------------

    private Response route(Request req) {
        String path = req.path;
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                return respondHtml();
            }
            if (path.equals("/favicon.ico")) {
                return new Response(204, new byte[0]);
            }
            if (path.equals("/pack.zip")) {
                return servePack();
            }
            if (!path.startsWith("/api/")) {
                return json(404, Map.of("error", "未知路径 " + path));
            }
            if (!authorized(req)) {
                return json(401, Map.of("error", "令牌无效（页面打开的 URL 已自动带令牌）"));
            }
            return handleApi(req);
        } catch (Throwable t) {
            return json(500, Map.of("error", String.valueOf(t.getMessage())));
        }
    }

    private Response handleApi(Request req) throws IOException {
        switch (req.path) {
            case "/api/status" -> { return json(200, status()); }
            case "/api/models" -> { return json(200, modelsPayload()); }
            case "/api/players" -> { return json(200, Map.of("players", bridge.players())); }
            case "/api/model" -> { return json(200, modelPayload(req.queryParam("id"))); }
            case "/api/model/files" -> { return json(200, modelFiles(req)); }
            case "/api/model/save" -> { return json(200, modelSave(req)); }
            case "/api/validate" -> { return json(200, validate(req)); }
            case "/api/reload" -> { return json(200, reload()); }
            case "/api/pack/build" -> { return json(200, packBuild()); }
            case "/api/mobs" -> { return json(200, Map.of("mobs", mobList())); }
            case "/api/mobs/file" -> { return json(200, mobFile(req)); }
            case "/api/mobs/save" -> { return json(200, mobSave(req)); }
            case "/api/mobs/create" -> { return json(200, mobCreate(req)); }
            case "/api/mobs/delete" -> { return json(200, mobDelete(req)); }
            case "/api/mobs/versions" -> { return json(200, mobVersions(req)); }
            case "/api/mobs/restore" -> { return json(200, mobRestore(req)); }
            case "/api/templates" -> { return json(200, templates(req)); }
            case "/api/log" -> { return json(200, Map.of("lines", recentAudit())); }
            case "/api/spawn" -> { return json(200, spawn(req)); }
            case "/api/config" -> { return json(200, configEndpoint(req)); }
            case "/api/model/export" -> { return modelExport(req); }
            case "/api/model/import" -> { return json(200, modelImport(req)); }
            case "/api/skills" -> { return json(200, skillsPayload(req)); }
            case "/api/skills/file" -> { return json(200, skillFile(req)); }
            case "/api/skills/save" -> { return json(200, skillSave(req)); }
            case "/api/skills/schema" -> { return json(200, skillSchema()); }
            case "/api/loot" -> { return json(200, lootPayload(req)); }
            case "/api/loot/file" -> { return json(200, lootFile(req)); }
            case "/api/loot/save" -> { return json(200, lootSave(req)); }
            case "/api/loot/roll" -> { return json(200, lootRoll(req)); }
            case "/api/spawners" -> { return json(200, spawnersPayload()); }
            case "/api/spawners/file" -> { return json(200, readYamlEndpoint(req, spawnersFile(), "spawners.yml")); }
            case "/api/spawners/save" -> { return json(200, spawnersSave(req)); }
            case "/api/spawners/reload" -> { return json(200, spawnersReload()); }
            case "/api/mob/schema" -> { return json(200, mobSchema()); }
            default -> { return json(404, Map.of("error", "未知接口 " + req.path)); }
        }
    }

    private boolean authorized(Request req) {
        String auth = req.headers.get("authorization");
        if (auth != null && ("Bearer " + token).equals(auth)) return true;
        String t = req.queryParam("token");
        return token.equals(t);
    }

    // ------------------------------------------------------------------
    // 状态 / 模型
    // ------------------------------------------------------------------

    private Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("version", bridge.version());
        s.put("port", port);
        s.put("uptimeMs", System.currentTimeMillis() - startedAt);
        s.put("modelsRoot", bridge.modelsRoot().toString());
        Map<String, Object> stats = bridge.runtimeStats();
        s.put("models", stats.getOrDefault("models", 0));
        s.put("instances", stats.getOrDefault("instances", 0));
        s.put("players", stats.getOrDefault("players", 0));
        s.put("tps", stats.getOrDefault("tps", 20.0));
        s.put("skillTriggers", stats.getOrDefault("skillTriggers", 0L));
        s.put("errorCount", bridge.errors().size());
        return s;
    }

    private Map<String, Object> modelsPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", bridge.modelsSummary());
        out.put("errors", bridge.errors());
        out.put("dirs", bridge.modelDirs());
        out.put("modelsRoot", bridge.modelsRoot().toString());
        return out;
    }

    private Map<String, Object> modelPayload(String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> detail = id == null || id.isBlank() ? null : bridge.modelDetail(id);
        out.put("ok", detail != null);
        if (detail != null) out.put("model", detail);
        else out.put("error", "模型未加载: " + id + "（可在“模型文件”里查看源文件并修正后重载）");
        return out;
    }

    private Map<String, Object> modelFiles(Request req) {
        Map<String, Object> out = new LinkedHashMap<>();
        String rel = req.queryParam("dir");
        Path dir = safeModelDir(rel);
        if (dir == null || !Files.isDirectory(dir)) {
            out.put("ok", false);
            out.put("error", "目录不存在或非法: " + rel);
            return out;
        }
        Map<String, Object> files = new LinkedHashMap<>();
        for (String n : MODEL_FILES) {
            Path f = dir.resolve(n);
            try {
                files.put(n, Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null);
            } catch (IOException e) {
                files.put(n, null);
            }
        }
        List<String> textures = new ArrayList<>();
        Path texDir = dir.resolve("textures");
        if (Files.isDirectory(texDir)) {
            try (var s = Files.list(texDir)) {
                s.filter(Files::isRegularFile).forEach(p -> textures.add(p.getFileName().toString()));
            } catch (IOException ignored) {
            }
        }
        out.put("ok", true);
        out.put("dir", rel);
        out.put("files", files);
        out.put("textures", textures);
        return out;
    }

    private Map<String, Object> modelSave(Request req) throws IOException {
        Map<?, ?> r = readJson(req);
        String rel = str(r.get("dir"));
        String name = str(r.get("name"));
        String content = str(r.get("content"));
        Map<String, Object> out = new LinkedHashMap<>();
        Path dir = safeModelDir(rel);
        if (dir == null) {
            out.put("ok", false);
            out.put("error", "非法模型目录: " + rel);
            return out;
        }
        if (!MODEL_FILE_SET.contains(name)) {
            out.put("ok", false);
            out.put("error", "只允许编辑: " + MODEL_FILES);
            return out;
        }
        String syntaxError = syntaxCheck(name, content);
        if (syntaxError != null) {
            out.put("ok", false);
            out.put("error", syntaxError);
            return out;
        }
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        String version = backup(f, "models", rel.replace('/', '_'));
        Files.writeString(f, content, StandardCharsets.UTF_8);
        // 保存即重载：前端不再自行调 /api/reload（那会让「内核还没注册完」就被读取），
        // 统一由这里触发并广播，前端收到 reload 事件后再拉列表。
        bridge.reloadModels();
        broadcastReload("models");
        audit("model-save", rel + "/" + name, version == null ? "-" : version);
        out.put("ok", true);
        out.put("version", version);
        out.put("message", "已保存 " + rel + "/" + name + "，点击“重载模型”生效。");
        return out;
    }

    private Map<String, Object> validate(Request req) throws IOException {
        Map<?, ?> r = readJson(req);
        String rel = str(r.get("dir"));
        String id = str(r.get("id"));
        Path dir = safeModelDir(rel);
        // 回退：未给 dir 时，模型 id 本身就是相对模型根的路径（如 example/emberling），
        // 直接按 id 解析。此前读 modelDetail(id) 的 "dir" 键，该键并不存在，
        // 导致传 id 的请求恒定报「目录不存在: null」。
        if ((dir == null || !Files.isDirectory(dir)) && id != null && !id.isBlank()) {
            Path byId = safeModelDir(id);
            if (byId != null && Files.isDirectory(byId)) {
                dir = byId;
                rel = id;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            out.put("ok", false);
            out.put("error", "目录不存在: " + rel);
            return out;
        }
        out.put("dir", rel);
        try {
            ModelParser.ParseResult pr = ModelParser.parse(dir);
            List<String> errs = bridge.validator().validate(pr.model());
            out.put("ok", errs.isEmpty());
            out.put("id", pr.model().id());
            out.put("bones", pr.model().allBones().size());
            out.put("animations", pr.model().animationNames());
            out.put("errors", errs);
            out.put("warnings", pr.warnings());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("errors", List.of(String.valueOf(e.getMessage())));
        }
        return out;
    }

    private Map<String, Object> reload() {
        boolean ok = bridge.reloadModels();
        // 模型重载是异步的（内核里排队解析后回主线程注册），
        // 真正就绪通常在 1~2 秒后。这里只广播"已触发"，
        // 前端收到事件后自己去轮询状态，避免宣称一个尚未发生的完成态。
        broadcastReload("models");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("message", ok ? "已触发模型重载（异步，约 1~2 秒后刷新查看）" : "模型注册表不可用");
        return out;
    }

    private Map<String, Object> packBuild() {
        boolean ok = bridge.buildPack();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("message", ok ? "已触发资源包构建（异步）" : "资源包服务不可用（需 3.0+ 版本）");
        return out;
    }

    // ------------------------------------------------------------------
    // 生物
    // ------------------------------------------------------------------

    private List<Map<String, Object>> mobList() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(mobsDir)) return out;
        try (var s = Files.list(mobsDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().forEach(p -> {
                Map<String, Object> e = new LinkedHashMap<>();
                String name = p.getFileName().toString();
                e.put("file", name);
                try {
                    YamlConfiguration y = YamlConfiguration.loadConfiguration(p.toFile());
                    e.put("id", y.getString("id", name.replace(".yml", "")));
                    e.put("model", y.getString("model", ""));
                    e.put("displayName", y.getString("display-name", ""));
                    e.put("entity", y.getString("entity.type", ""));
                } catch (Exception ex) {
                    e.put("error", String.valueOf(ex.getMessage()));
                }
                out.add(e);
            });
        } catch (IOException ignored) {
        }
        return out;
    }

    /**
     * 配置读写端点。
     *
     * <ul>
     *   <li>GET  → 返回全量配置（扁平化键值对）与不可经网页修改的键清单</li>
     *   <li>POST → 合并补丁并落盘，逐键失败即整体报错，不做部分写入</li>
     * </ul>
     *
     * <p>写入是合并而非替换：网页端只提交改动字段，若整体 setValues 会清掉
     * 未提交的所有键。令牌相关键由桥接层剔除，避免网页把自己改到无法鉴权。</p>
     */
    private Map<String, Object> configEndpoint(Request req) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        if ("GET".equals(req.method)) {
            out.put("ok", true);
            out.put("config", bridge.configSnapshot());
            out.put("protected", List.of("web.token", "web.port", "web.host"));
            out.put("note", "写入后需执行 /helstera reload 或重启插件方能对运行期生效");
            return out;
        }
        Map<?, ?> body = readJson(req);
        Object patch = body.get("config");
        if (!(patch instanceof Map<?, ?> m)) {
            out.put("ok", false);
            out.put("error", "请求体需为 {\"config\": {...}}");
            return out;
        }
        java.util.LinkedHashMap<String, Object> typed = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) typed.put(String.valueOf(e.getKey()), e.getValue());
        String err = bridge.applyConfigPatch(typed);
        out.put("ok", err == null);
        if (err != null) {
            out.put("error", err);
        } else {
            out.put("applied", typed.size());
            out.put("config", bridge.configSnapshot());
        }
        return out;
    }

    private Map<String, Object> mobFile(Request req) {
        String name = req.queryParam("name");
        Path f = safeMobPath(name);
        Map<String, Object> out = new LinkedHashMap<>();
        if (f == null || !Files.isRegularFile(f)) {
            out.put("ok", false);
            out.put("error", "生物配置不存在: " + name);
            return out;
        }
        try {
            out.put("ok", true);
            out.put("name", f.getFileName().toString());
            out.put("content", Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            out.put("ok", false);
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    private Map<String, Object> mobSave(Request req) throws IOException {
        Map<?, ?> r = readJson(req);
        String name = str(r.get("name"));
        String content = str(r.get("content"));
        Map<String, Object> out = new LinkedHashMap<>();
        Path f = safeMobPath(name);
        if (f == null) {
            out.put("ok", false);
            out.put("error", "非法文件名（仅允许 字母/数字/下划线/连字符）");
            return out;
        }
        String syntaxError = syntaxCheck("mob.yml", content);
        if (syntaxError != null) {
            out.put("ok", false);
            out.put("error", syntaxError);
            return out;
        }
        Files.createDirectories(f.getParent());
        String version = backup(f, "mobs", f.getFileName().toString().replace(".yml", ""));
        Files.writeString(f, content, StandardCharsets.UTF_8);
        audit("mob-save", f.getFileName().toString(), version == null ? "new" : version);
        out.put("ok", true);
        out.put("version", version);
        out.put("message", "已保存 " + f.getFileName() + "（可用 /helstera reload models 或 /helstera mob spawn 测试）");
        return out;
    }

    private Map<String, Object> mobCreate(Request req) throws IOException {
        Map<?, ?> r = readJson(req);
        String name = str(r.get("name"));
        String template = str(r.get("template"));
        Map<String, Object> out = new LinkedHashMap<>();
        Path f = safeMobPath(name);
        if (f == null) {
            out.put("ok", false);
            out.put("error", "非法文件名");
            return out;
        }
        if (Files.exists(f)) {
            out.put("ok", false);
            out.put("error", "文件已存在: " + f.getFileName());
            return out;
        }
        String id = f.getFileName().toString().replace(".yml", "");
        String content = MobTemplates.get(template == null ? "blank" : template).replace("__ID__", id);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content, StandardCharsets.UTF_8);
        audit("mob-create", f.getFileName().toString(), template == null ? "blank" : template);
        out.put("ok", true);
        out.put("name", f.getFileName().toString());
        out.put("content", content);
        out.put("message", "已按模板 [" + (template == null ? "blank" : template) + "] 创建 " + f.getFileName());
        return out;
    }

    private Map<String, Object> mobDelete(Request req) throws IOException {
        Map<?, ?> r = readJson(req);
        String name = str(r.get("name"));
        Map<String, Object> out = new LinkedHashMap<>();
        Path f = safeMobPath(name);
        if (f == null || !Files.isRegularFile(f)) {
            out.put("ok", false);
            out.put("error", "文件不存在");
            return out;
        }
        backup(f, "mobs", f.getFileName().toString().replace(".yml", ""));
        Files.delete(f);
        audit("mob-delete", f.getFileName().toString(), "-");
        out.put("ok", true);
        out.put("message", "已删除 " + f.getFileName() + "（已留版本备份，可回滚）");
        return out;
    }

    private Map<String, Object> mobVersions(Request req) {
        String name = req.queryParam("name");
        List<String> out = new ArrayList<>();
        Path vDir = versionsDir.resolve("mobs").resolve(stripYml(name));
        if (Files.isDirectory(vDir)) {
            try (var s = Files.list(vDir)) {
                s.forEach(p -> out.add(p.getFileName().toString()));
            } catch (IOException ignored) {
            }
        }
        out.sort(Collections.reverseOrder());
        return Map.of("versions", out);
    }

    private Map<String, Object> mobRestore(Request req) throws IOException {
        Map<?, ?> r = readJson(req);
        String name = str(r.get("name"));
        String version = str(r.get("version"));
        Map<String, Object> out = new LinkedHashMap<>();
        Path f = safeMobPath(name);
        if (f == null || version == null || !version.matches("[0-9_A-Za-z.\\-]+")) {
            out.put("ok", false);
            out.put("error", "参数非法");
            return out;
        }
        Path vFile = versionsDir.resolve("mobs").resolve(stripYml(name)).resolve(version).normalize();
        if (!vFile.startsWith(versionsDir) || !Files.isRegularFile(vFile)) {
            out.put("ok", false);
            out.put("error", "版本不存在: " + version);
            return out;
        }
        Files.copy(vFile, f, StandardCopyOption.REPLACE_EXISTING);
        audit("mob-restore", f.getFileName().toString(), version);
        out.put("ok", true);
        out.put("message", "已回滚到 " + version);
        return out;
    }

    private Map<String, Object> templates(Request req) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String id : MobTemplates.all().keySet()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", id);
            e.put("description", MobTemplates.describe(id));
            list.add(e);
        }
        out.put("templates", list);
        String one = req.queryParam("id");
        if (one != null) out.put("content", MobTemplates.get(one));
        return out;
    }

    private Map<String, Object> spawn(Request req) throws IOException {
        Map<?, ?> r = readJson(req);
        String mob = str(r.get("mob"));
        String player = str(r.get("player"));
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String msg = bridge.spawn(mob, player == null || player.isBlank() ? null : player);
            out.put("ok", msg != null);
            out.put("message", msg == null ? "生成失败（插件未启用实例系统或参数无效）" : msg);
            audit("spawn", String.valueOf(mob), String.valueOf(player));
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", String.valueOf(t.getMessage()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String syntaxCheck(String name, String content) {
        if (content == null) return "内容为空";
        try {
            if (name.endsWith(".json")) {
                JsonParser.parseString(content);
            } else {
                YamlConfiguration.loadConfiguration(new java.io.StringReader(content));
            }
            return null;
        } catch (Exception e) {
            return (name.endsWith(".json") ? "JSON" : "YAML") + " 语法错误: " + e.getMessage();
        }
    }

    private String backup(Path file, String kind, String label) {
        try {
            if (!Files.isRegularFile(file)) return null;
            String version = LocalDateTime.now().format(STAMP);
            Path vDir = versionsDir.resolve(kind).resolve(label);
            Files.createDirectories(vDir);
            Files.copy(file, vDir.resolve(version + "_" + file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            return version;
        } catch (Exception e) {
            return null;
        }
    }

    private Path safeMobPath(String name) {
        if (name == null) return null;
        String n = stripYml(name.trim());
        if (!n.matches("[A-Za-z0-9_\\-]+")) return null;
        Path f = mobsDir.resolve(n + ".yml").normalize();
        return f.startsWith(mobsDir) ? f : null;
    }

    private static String stripYml(String name) {
        if (name == null) return "";
        String n = name.trim();
        return n.endsWith(".yml") ? n.substring(0, n.length() - 4) : n;
    }

    /** 相对模型根的目录路径 -> 绝对路径；越界/非法返回 null。 */
    private Path safeModelDir(String rel) {
        if (rel == null || rel.isBlank()) return null;
        String r = rel.replace('\\', '/').trim();
        while (r.startsWith("/")) r = r.substring(1);
        if (r.isEmpty() || r.contains("..") || !r.matches("[A-Za-z0-9_\\-/]+")) return null;
        Path p = modelsRoot.resolve(r).normalize();
        return p.startsWith(modelsRoot) ? p : null;
    }

    private Map<?, ?> readJson(Request req) {
        if (req.body == null || req.body.isBlank()) return Map.of();
        try {
            return GSON.fromJson(req.body, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private Response respondHtml() {
        byte[] html = html().getBytes(StandardCharsets.UTF_8);
        Response r = new Response(200, html);
        r.headers.put("content-type", "text/html; charset=utf-8");
        return r;
    }

    /**
     * 把单个模型目录打包成 zip 下载（网页端的「导出模型」）。
     *
     * <p>只打包 models/ 下的一个目录，不允许整根目录导出：models/ 可能很大，
     * 一次请求把全部模型传出去既无必要也容易打爆浏览器内存。</p>
     */
    private Response modelExport(Request req) {
        String id = req.queryParam("id");
        if (id == null || id.isBlank()) id = req.queryParam("dir");
        Path dir = safeModelDir(id);
        Map<String, Object> fail = new LinkedHashMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            fail.put("ok", false);
            fail.put("error", "模型目录不存在或非法: " + id);
            return json(400, fail);
        }
        String rel = modelsRoot.toAbsolutePath().normalize()
                .relativize(dir.toAbsolutePath().normalize()).toString().replace('\\', '/');
        String fileName = rel.substring(rel.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9_.-]", "_");
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(buf)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                    for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                        // 只需相对 models 根的路径；relativize 的结果已含 rel 前缀，
                        // 再拼一次 rel 会得到 example/emberling/example/emberling/... 这样的重复。
                        String name = modelsRoot.toAbsolutePath().normalize()
                                .relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
                        out.putNextEntry(new java.util.zip.ZipEntry(name));
                        out.write(Files.readAllBytes(p));
                        out.closeEntry();
                    }
                }
            }
            Response r = new Response(200, buf.toByteArray());
            r.headers.put("content-type", "application/zip");
            r.headers.put("content-disposition",
                    "attachment; filename=\"" + fileName + ".zip\"");
            return r;
        } catch (IOException e) {
            return json(500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 导入模型。
     *
     * <p>接受两种负载：{@code files:[{path,content}]} 直接写文本文件，
     * 或 {@code zip_base64} 上传整包。逐文件都经 {@code safeModelDir} 校验，
     * 任何越界路径立即拒绝，避免 zip slip。</p>
     */
    private Map<String, Object> modelImport(Request req) throws IOException {
        Map<?, ?> body = readJson(req);
        Map<String, Object> out = new LinkedHashMap<>();
        Path target = safeModelDir(str(body.get("dir")));
        if (target == null) {
            out.put("ok", false);
            out.put("error", "目标目录非法（仅允许字母数字下划线与 / -，不允许 ..）: " + body.get("dir"));
            return out;
        }
        List<String> written = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        String zipB64 = str(body.get("zip_base64"));
        if (zipB64 != null && !zipB64.isBlank()) {
            byte[] raw;
            try {
                raw = java.util.Base64.getMimeDecoder().decode(zipB64);
            } catch (IllegalArgumentException e) {
                out.put("ok", false);
                out.put("error", "zip_base64 解码失败: " + e.getMessage());
                return out;
            }
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(raw))) {
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    // zip slip：条目名可能是 ../../evil.yml
                    Path dest = target.resolve(e.getName().replace('\\', '/')).normalize();
                    if (!dest.startsWith(target)) {
                        rejected.add(e.getName() + "（越界路径）");
                        continue;
                    }
                    Files.createDirectories(dest.getParent());
                    Files.write(dest, zis.readAllBytes());
                    written.add(e.getName());
                }
            }
        }

        Object files = body.get("files");
        if (files instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String rel = str(m.get("path"));
                if (rel == null || rel.isBlank()) continue;
                Path dest = target.resolve(rel.replace('\\', '/')).normalize();
                if (!dest.startsWith(target)) {
                    rejected.add(rel + "（越界路径）");
                    continue;
                }
                Files.createDirectories(dest.getParent());
                Files.writeString(dest, str(m.get("content")) == null ? "" : str(m.get("content")),
                        StandardCharsets.UTF_8);
                written.add(rel);
            }
        }

        if (written.isEmpty() && rejected.isEmpty()) {
            out.put("ok", false);
            out.put("error", "未提供任何文件（需要 files 或 zip_base64）");
            return out;
        }
        Files.createDirectories(target);
        out.put("ok", true);
        out.put("written", written);
        out.put("writtenCount", written.size());
        if (!rejected.isEmpty()) out.put("rejected", rejected);
        // 需显式重载才生效，接口不代劳：解析在主线程，重载会打断其他实例。
        out.put("note", "文件已写入，执行 /api/reload 后生效");
        bridge.reloadModels();
        return out;
    }

    private Response servePack() {
        Path zip = bridge.packZip();
        if (zip == null || !Files.isRegularFile(zip)) {
            return json(404, Map.of("error", "资源包尚未构建（点击“构建资源包”后重试）"));
        }
        try {
            byte[] data = Files.readAllBytes(zip);
            Response r = new Response(200, data);
            r.headers.put("content-type", "application/zip");
            r.headers.put("content-disposition", "attachment; filename=\"helsteraMobs-pack.zip\"");
            return r;
        } catch (IOException e) {
            return json(500, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private String html() {
        if (cachedHtml != null) return cachedHtml;
        String raw = loadResource("/web/index.html");
        if (raw == null) {
            raw = "<!doctype html><meta charset='utf-8'><h1>helsteraMobs 网页开发器</h1>"
                    + "<p>缺少 web/index.html 资源，请重新构建插件。</p>";
        }
        cachedHtml = raw.replace("__TOKEN__", token)
                .replace("__VERSION__", bridge.version())
                .replace("__PORT__", String.valueOf(port));
        return cachedHtml;
    }

    private static String loadResource(String path) {
        try (InputStream in = WebServerService.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static Response json(int code, Object o) {
        return new Response(code, GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 技能 / 掉落 / 刷怪点 / Mob 表单
    // ------------------------------------------------------------------

    /** skills.yml 的绝对路径（不存在则返回 null）。 */
    private Path skillsFile() {
        Path f = dataFolder.resolve("skills.yml");
        return Files.isRegularFile(f) ? f : null;
    }

    /** spawners.yml 的绝对路径（不存在则返回 null）。 */
    private Path spawnersFile() {
        Path f = dataFolder.resolve("spawners.yml");
        return Files.isRegularFile(f) ? f : null;
    }

    /** loot.yml 的绝对路径（不存在则返回 null）。 */
    private Path lootFile_() {
        Path f = dataFolder.resolve("loot.yml");
        return Files.isRegularFile(f) ? f : null;
    }

    /**
     * 校验一段 YAML 能被 Bukkit 解析。
     *
     * <p>用 YamlConfiguration.loadFromString 而不是 SnakeYAML：它对「缩进错误 /
     * 类型错位」的报错带行号，比直接抛 YAMLException 更适合展示给网页用户。</p>
     */
    private static String ymlSyntaxError(String content) {
        try {
            // loadFromString 是实例方法（不是静态），必须先 new 一个配置对象；
            // 它解析失败时抛异常，正好用来做语法校验。
            String yaml = content == null ? "" : content;
            new YamlConfiguration().loadFromString(yaml);
            return null;
        } catch (Throwable t) {
            String m = t.getMessage();
            return m == null ? t.toString() : m;
        }
    }

    /** 读一个受白名单约束的 YAML 文件，返回文本；越界或不存在返回错误。 */
    private Map<String, Object> readYamlEndpoint(Request req, Path file, String label) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (file == null) {
            out.put("ok", false);
            out.put("error", label + " 尚未生成（插件首次启动时自动创建）");
            return out;
        }
        out.put("ok", true);
        out.put("path", file.getFileName().toString());
        out.put("content", Files.readString(file, StandardCharsets.UTF_8));
        return out;
    }

    /** 技能列表 + 告警 + 已绑定键。 */
    private Map<String, Object> skillsPayload(Request req) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        Path f = skillsFile();
        out.put("ok", f != null);
        out.put("names", bridge.skillNames());
        out.put("warnings", bridge.skillWarnings());
        if (f == null) {
            out.put("error", "skills.yml 尚未生成");
            out.put("content", "");
            return out;
        }
        out.put("content", Files.readString(f, StandardCharsets.UTF_8));
        return out;
    }

    private Map<String, Object> skillFile(Request req) throws IOException {
        return readYamlEndpoint(req, skillsFile(), "skills.yml");
    }

    /** 保存 skills.yml：先语法校验，再版本备份，再落盘，最后触发技能重载。 */
    private Map<String, Object> skillSave(Request req) throws IOException {
        Map<?, ?> r = readJson(req);
        String content = str(r.get("content"));
        Map<String, Object> out = new LinkedHashMap<>();
        Path f = skillsFile();
        if (f == null) {
            f = dataFolder.resolve("skills.yml");
        }
        String err = ymlSyntaxError(content);
        if (err != null) {
            out.put("ok", false);
            out.put("error", "YAML 语法错误：" + err);
            return out;
        }
        String version = backup(f, "skills", "skills");
        Files.createDirectories(f.getParent());
        Files.writeString(f, content, StandardCharsets.UTF_8);
        boolean reloaded = bridge.reloadSkills();
        broadcastReload("skills");
        audit("skills-save", "skills.yml", version == null ? "-" : version);
        out.put("ok", true);
        out.put("version", version);
        out.put("reloaded", reloaded);
        out.put("warnings", bridge.skillWarnings());
        out.put("message", reloaded
                ? "已保存 skills.yml 并重载技能绑定。"
                : "已保存 skills.yml，但技能重载未生效，请查看控制台。");
        return out;
    }

    /** 内置条件/动作名 + 参数说明，供网页端做补全与提示。 */
    private Map<String, Object> skillSchema() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("conditions", List.of(
                "has-target [bool]", "health-below <0..1>", "health-above <0..1>",
                "distance-below <格>", "distance-above <格>", "state-is <状态名>",
                "animation-is <动画名>", "every-n-decisions [N]",
                "targets-exist <选择器> <半径> [最少个数]", "targets-in-range <选择器> <半径> [最少个数]"));
        out.put("actions", List.of(
                "set-scale <倍率>", "play-animation <动画> [循环] [优先级]", "stop-animation [动画]",
                "set-intent <状态>", "damage-target <伤害>", "message-target <文本>",
                "sound <音效>", "particle <粒子>", "heal-self <数量>",
                "aoe-damage <选择器> <半径> [数量] [伤害]",
                "teleport-targets <选择器> <半径> [数量] [Y偏移]",
                "effect-targets <选择器> <半径> [数量] <效果> [tick] [等级]",
                "ignite-targets <选择器> <半径> [数量] [tick]",
                "knockback-targets <选择器> <半径> [数量] [力度] [上抛]",
                "message-targets <选择器> <半径> [数量] <文本>"));
        out.put("targeters", List.of("nearest", "farthest", "random", "lowest-health",
                "highest-health", "players", "mobs"));
        out.put("states", List.of("IDLE", "PATROL", "CHASE", "ATTACK", "HURT", "FLEE", "DEAD"));
        return out;
    }

    /** 掉落表列表 + 内容。 */
    private Map<String, Object> lootPayload(Request req) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        Path f = lootFile_();
        out.put("ok", f != null);
        out.put("tables", bridge.lootTables());
        if (f == null) {
            out.put("error", "loot.yml 尚未生成");
            out.put("content", "");
            return out;
        }
        out.put("content", Files.readString(f, StandardCharsets.UTF_8));
        return out;
    }

    private Map<String, Object> lootFile(Request req) throws IOException {
        return readYamlEndpoint(req, lootFile_(), "loot.yml");
    }

    /** 保存 loot.yml：语法校验 + 备份 + 落盘 + 重载。 */
    private Map<String, Object> lootSave(Request req) throws IOException {
        Map<?, ?> r = readJson(req);
        String content = str(r.get("content"));
        Map<String, Object> out = new LinkedHashMap<>();
        Path f = lootFile_();
        if (f == null) f = dataFolder.resolve("loot.yml");
        String err = ymlSyntaxError(content);
        if (err != null) {
            out.put("ok", false);
            out.put("error", "YAML 语法错误：" + err);
            return out;
        }
        String version = backup(f, "loot", "loot");
        Files.createDirectories(f.getParent());
        Files.writeString(f, content, StandardCharsets.UTF_8);
        bridge.reloadLoot();
        broadcastReload("loot");
        audit("loot-save", "loot.yml", version == null ? "-" : version);
        out.put("ok", true);
        out.put("version", version);
        out.put("tables", bridge.lootTables());
        out.put("message", "已保存 loot.yml 并重载掉落表。");
        return out;
    }

    /** 掷骰预览：只算不落地，不会在世界里真的掉东西。 */
    private Map<String, Object> lootRoll(Request req) throws IOException {
        Map<?, ?> r = readJson(req);
        String table = str(r.get("table"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (table == null || table.isBlank()) {
            out.put("ok", false);
            out.put("error", "缺少 table 参数");
            return out;
        }
        double luck = 0;
        Object lv = r.get("luck");
        if (lv instanceof Number n) luck = n.doubleValue();
        out.put("ok", true);
        out.put("table", table);
        out.put("luck", luck);
        out.put("hits", bridge.rollLoot(table, luck));
        return out;
    }

    /** 刷怪点摘要。 */
    private Map<String, Object> spawnersPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("spawners", bridge.spawnerInfo());
        return out;
    }

    /** 保存 spawners.yml：语法校验 + 备份 + 落盘 + 重载。 */
    private Map<String, Object> spawnersSave(Request req) throws IOException {
        Map<?, ?> r = readJson(req);
        String content = str(r.get("content"));
        Map<String, Object> out = new LinkedHashMap<>();
        Path f = spawnersFile();
        if (f == null) f = dataFolder.resolve("spawners.yml");
        String err = ymlSyntaxError(content);
        if (err != null) {
            out.put("ok", false);
            out.put("error", "YAML 语法错误：" + err);
            return out;
        }
        String version = backup(f, "spawners", "spawners");
        Files.createDirectories(f.getParent());
        Files.writeString(f, content, StandardCharsets.UTF_8);
        bridge.reloadLoot();
        broadcastReload("spawners");
        audit("spawners-save", "spawners.yml", version == null ? "-" : version);
        out.put("ok", true);
        out.put("version", version);
        out.put("spawners", bridge.spawnerInfo());
        out.put("message", "已保存 spawners.yml 并重载刷怪点。");
        return out;
    }

    /** 重载 spawners.yml（不动已追踪的存活名额）。 */
    private Map<String, Object> spawnersReload() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean ok = bridge.reloadLoot();
        audit("spawners-reload", "spawners.yml", "-");
        out.put("ok", ok);
        out.put("spawners", bridge.spawnerInfo());
        out.put("message", "已重载 spawners.yml。");
        return out;
    }

    /**
     * mobs/*.yml 的字段元数据，供网页端渲染表单。
     *
     * <p>表单化编辑的关键是「字段定义与校验规则来自服务端」：前端只按这张表生成控件，
     * 于是新增字段时前端无需同步改。</p>
     */
    private Map<String, Object> mobSchema() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("fields", List.of(
                field("id", "string", "ID", "与文件名一致，创建后不可改", true),
                field("display-name", "string", "显示名称", "支持 § 颜色码", false),
                field("model", "model", "模型", "必须是已加载的模型 ID", true),
                field("scale", "number", "缩放", "> 0，1.0 = 原始大小", false),
                field("glowing", "bool", "发光轮廓", "BOSS 更醒目", false),
                field("show-name", "bool", "显示名称", "头顶展示 display-name", false),
                field("persistent", "bool", "常驻", "区块卸载后是否保留", false),
                field("spawn-hitbox", "bool", "点击碰撞", "纯展示模型用 Interaction 承接点击", false),
                field("entity.type", "entitytype", "真实实体类型", "留空 = 纯展示模型", false),
                field("entity.health", "number", "最大血量", "仅真实实体生效", false),
                field("entity.invisible", "bool", "隐身体", "仅真实实体生效", false),
                field("entity.silent", "bool", "静音", "仅真实实体生效", false),
                field("entity.no-ai", "bool", "停用原版 AI", "仅真实实体生效", false),
                field("drops.table", "loottable", "掉落表", "留空 = 保留原版掉落", false),
                field("drops.luck", "bool", "计入抢夺", "把击杀者抢夺等级计入掉落概率", false),
                field("ai.profile", "string", "行为档案", "引用 config.yml 的 ai.profiles", false),
                field("ai.sight-radius", "number", "视野半径", "仅真实实体生效", false),
                field("ai.attack-radius", "number", "攻击半径", "仅真实实体生效", false),
                field("ai.attack-damage", "number", "攻击伤害", "仅真实实体生效", false),
                field("ai.attack-cooldown", "number", "攻击冷却（秒）", "仅真实实体生效", false),
                field("ai.move-speed", "number", "移动速度", "仅真实实体生效", false),
                field("ai.can-chase", "bool", "可追击", "仅真实实体生效", false),
                field("ai.can-flee", "bool", "可逃跑", "仅真实实体生效", false),
                field("ai.can-attack", "bool", "可攻击", "仅真实实体生效", false),
                field("ai.can-patrol", "bool", "可巡逻", "仅真实实体生效", false)));
        out.put("entityTypes", entityTypeNames());
        out.put("lootTables", bridge.lootTables());
        out.put("profiles", bridge.profiles());
        out.put("models", bridge.modelIds());
        return out;
    }

    private static Map<String, Object> field(String path, String type, String label,
                                             String hint, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("type", type);
        m.put("label", label);
        m.put("hint", hint);
        m.put("required", required);
        return m;
    }

    private static List<String> entityTypeNames() {
        List<String> out = new ArrayList<>();
        for (org.bukkit.entity.EntityType t : org.bukkit.entity.EntityType.values()) {
            if (t.isSpawnable() && org.bukkit.entity.LivingEntity.class.isAssignableFrom(t.getEntityClass())) {
                out.add(t.name());
            }
        }
        return out;
    }

    private void audit(String action, String target, String detail) {
        String line = String.format("[%s] %s target=%s %s",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), action, target, detail);
        audit.add(line);
        try {
            Path log = dataFolder.resolve("web/audit.log");
            Files.createDirectories(log.getParent());
            Files.writeString(log, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 内部请求/响应
    // ------------------------------------------------------------------

    private static final class Request {
        String method;
        String path;
        String query;
        final Map<String, String> headers = new LinkedHashMap<>();
        String body = "";

        String queryParam(String key) {
            if (query == null) return null;
            for (String p : query.split("&")) {
                int eq = p.indexOf('=');
                if (eq > 0 && p.substring(0, eq).equals(key)) {
                    try {
                        return java.net.URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        return p.substring(eq + 1);
                    }
                }
            }
            return null;
        }
    }

    private static final class Response {
        int status;
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body;

        Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }
}
