package dev.helstera.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import dev.helstera.core.parse.ModelParser;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
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

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Thread acceptThread;
    private volatile boolean running;
    private String cachedHtml;

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
    // 生命周期（ServerSocket 实现）
    // ------------------------------------------------------------------

    public void start() throws IOException {
        start(this.host);
    }

    /** 以指定监听地址启动（例如 0.0.0.0 以便远程访问）。供 /helstera web start <host> 使用。 */
    public void start(String overrideHost) throws IOException {
        if (overrideHost != null && !overrideHost.isBlank()) this.host = overrideHost;
        stop();
        InetSocketAddress bind;
        if ("0.0.0.0".equals(host)) {
            bind = new InetSocketAddress((InetAddress) null, port); // 通配符：监听所有网卡
        } else {
            bind = new InetSocketAddress(InetAddress.getByName(host), port);
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        try {
            serverSocket.bind(bind);
        } catch (IOException e) {
            throw new IOException("网页开发器无法在 " + host + ":" + port + " 监听：" + e.getMessage()
                    + "（端口被占用？权限不足？请更换 web.port 或释放端口）", e);
        }
        running = true;
        executor = Executors.newFixedThreadPool(4);
        acceptThread = new Thread(this::acceptLoop, "helstera-web");
        acceptThread.setDaemon(true);
        acceptThread.start();
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

    public void stop() {
        running = false;
        if (acceptThread != null) {
            try { acceptThread.interrupt(); } catch (Throwable ignored) {}
            acceptThread = null;
        }
        if (executor != null) {
            try { executor.shutdownNow(); } catch (Throwable ignored) {}
            executor = null;
        }
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
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
    // 连接处理
    // ------------------------------------------------------------------

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket sock = serverSocket.accept();
                if (executor != null) executor.submit(() -> handleConnection(sock));
            } catch (IOException e) {
                if (running) plugin.getLogger().warning("网页开发器 accept 出错: " + e.getMessage());
            }
        }
    }

    private void handleConnection(Socket sock) {
        try (Socket s = sock;
             InputStream in = s.getInputStream();
             OutputStream out = s.getOutputStream()) {
            // 注意：必须全程从同一个 InputStream 逐字节读取，不能混用 BufferedReader（缓冲会吞掉请求体）。
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeResponse(out, new Response(400, "Bad Request".getBytes(StandardCharsets.UTF_8)));
                return;
            }
            Request req = new Request();
            req.method = parts[0].toUpperCase(Locale.ROOT);
            String fp = parts[1];
            int qi = fp.indexOf('?');
            if (qi >= 0) {
                req.path = fp.substring(0, qi);
                req.query = fp.substring(qi + 1);
            } else {
                req.path = fp;
            }
            String line;
            int contentLength = 0;
            while (!(line = readLine(in)).isEmpty()) {
                int ci = line.indexOf(':');
                if (ci > 0) {
                    String k = line.substring(0, ci).trim().toLowerCase(Locale.ROOT);
                    String v = line.substring(ci + 1).trim();
                    req.headers.put(k, v);
                    if (k.equals("content-length")) {
                        try { contentLength = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            if (contentLength > 0) {
                byte[] buf = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(buf, read, contentLength - read);
                    if (n < 0) break;
                    read += n;
                }
                req.body = new String(buf, 0, read, StandardCharsets.UTF_8);
            }
            writeResponse(out, route(req));
        } catch (Exception e) {
            // 单连接异常不影响服务
        }
    }

    /** 从 InputStream 逐字节读取一行（到 \n 为止），不含行结束符。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') continue;
            bos.write(c);
        }
        return bos.toString(StandardCharsets.UTF_8);
    }

    private void writeResponse(OutputStream out, Response resp) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(resp.status).append(' ').append(statusText(resp.status)).append("\r\n");
        if (!resp.headers.containsKey("content-type")) {
            resp.headers.put("content-type", "application/json; charset=utf-8");
        }
        resp.headers.put("content-length", String.valueOf(resp.body == null ? 0 : resp.body.length));
        resp.headers.put("connection", "close");
        for (var e : resp.headers.entrySet()) {
            head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (resp.body != null && resp.body.length > 0) out.write(resp.body);
        out.flush();
    }

    private static String statusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "Status";
        };
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
