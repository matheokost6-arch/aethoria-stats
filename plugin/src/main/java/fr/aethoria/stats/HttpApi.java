package fr.aethoria.stats;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Petite API HTTP (JDK uniquement) : stats publiques et panneau d'administration. */
public final class HttpApi {

    /** Ce dont l'API a besoin du serveur (implémenté par le plugin, ou par un faux pour les tests). */
    public interface Backend {
        /** JSON public des statistiques (déjà construit, sans appel bloquant). */
        String statsJson();

        /** JSON public des fiches joueurs. */
        String playersJson();

        /** JSON public de l'historique (courbes). */
        String historyJson();

        /** JSON public des royaumes (sans la partie admin). */
        String factionsJson();

        /** Trésor, claims et cités des factions : réservé aux comptes connectés. */
        String adminFactionsJson();

        /** Exécute une action. Renvoie {ok:boolean, message|error:String}. */
        Map<String, Object> action(Auth.Session who, Map<String, Object> req);

        List<Object> players();

        Map<String, Object> sanctions();

        List<Object> audit(int limit);

        void audit(Map<String, Object> entry);
    }

    public static final Set<String> MODO_ACTIONS = Set.of("heal", "feed", "kick", "ban", "unban", "mute", "unmute",
            "tp-spawn", "clear-effects", "gamemode", "message", "freeze", "unfreeze", "broadcast", "save-all",
            "weather", "time", "clearlag");
    public static final Set<String> ADMIN_ONLY = Set.of("console");

    /** Droit d'un rôle pour une action. modo : pas de console libre, pas de ban définitif. */
    public static String denied(String role, Map<String, Object> req) {
        String action = Json.str(req.get("action"), "");
        if ("admin".equals(role)) {
            return MODO_ACTIONS.contains(action) || ADMIN_ONLY.contains(action) ? null : "Action inconnue.";
        }
        if (!"modo".equals(role)) return "Rôle inconnu.";
        if (!MODO_ACTIONS.contains(action)) return ADMIN_ONLY.contains(action) ? "Réservé aux admins." : "Action inconnue.";
        if ("ban".equals(action) && durationMs(Json.str(req.get("duration"), "")) <= 0) {
            return "Le bannissement définitif est réservé aux admins : choisis une durée.";
        }
        return null;
    }

    /** "30m", "2h", "7d" en ms ; 0 si vide ou définitif ; -1 si invalide. */
    public static long durationMs(String d) {
        if (d == null || d.isBlank() || d.equalsIgnoreCase("perm") || d.equalsIgnoreCase("definitif")) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,5})([mhd])").matcher(d.trim().toLowerCase());
        if (!m.matches()) return -1;
        long n = Long.parseLong(m.group(1));
        if (n <= 0) return -1;
        return n * switch (m.group(2)) {
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            default -> 86_400_000L;
        };
    }

    private final Backend backend;
    private final Auth auth;
    private final ConsoleBuffer console;
    private final Set<String> origins;
    private final Consumer<String> warn;
    private final Semaphore pollSlots = new Semaphore(8);
    private HttpServer server;
    private ExecutorService pool;

    public HttpApi(Backend backend, Auth auth, ConsoleBuffer console, Set<String> allowedOrigins, Consumer<String> warn) {
        this.backend = backend;
        this.auth = auth;
        this.console = console;
        this.origins = allowedOrigins;
        this.warn = warn;
    }

    public void start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind), port), 32);
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "AethoriaStats-api-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    // ---- CORS ----

    private boolean originAllowed(String o) {
        if (o == null) return false;
        if (origins.contains(o)) return true;
        return o.matches("http://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?");
    }

    private void cors(HttpExchange ex) {
        String o = ex.getRequestHeaders().getFirst("Origin");
        var h = ex.getResponseHeaders();
        h.add("Vary", "Origin");
        if (originAllowed(o)) {
            h.set("Access-Control-Allow-Origin", o);
            h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            h.set("Access-Control-Max-Age", "600");
        }
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
    }

    // ---- aides ----

    private static String clientIp(HttpExchange ex) {
        String remote = ex.getRemoteAddress().getAddress().getHostAddress();
        boolean local = ex.getRemoteAddress().getAddress().isLoopbackAddress();
        if (local) {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] p = xff.split(",");
                String ip = p[p.length - 1].trim();
                if (!ip.isEmpty() && ip.length() < 64) return ip;
            }
        }
        return remote;
    }

    private static void send(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = (body instanceof String s ? s : Json.write(body)).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }

    private static Map<String, Object> readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(16 * 1024 + 1);
            if (b.length > 16 * 1024) throw new IllegalArgumentException("Requête trop grande.");
            if (b.length == 0) return new LinkedHashMap<>();
            return Json.map(Json.parse(new String(b, StandardCharsets.UTF_8)));
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            try {
                q.put(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8), java.net.URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // paramètre mal formé : ignoré
            }
        }
        return q;
    }

    private Auth.Session session(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        return auth.check(h.substring(7).trim(), System.currentTimeMillis());
    }

    // ---- routes ----

    private void handle(HttpExchange ex) throws IOException {
        try {
            cors(ex);
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String origin = ex.getRequestHeaders().getFirst("Origin");
            if (method.equals("OPTIONS")) {
                ex.sendResponseHeaders(originAllowed(origin) ? 204 : 403, -1);
                return;
            }
            boolean admin = path.startsWith("/api/admin/") || path.equals("/api/login") || path.equals("/api/logout");
            if (admin && origin != null && !originAllowed(origin)) {
                send(ex, 403, err("Origine non autorisée."));
                return;
            }
            if (path.equals("/api/stats") && method.equals("GET")) {
                send(ex, 200, backend.statsJson());
            } else if (path.equals("/api/players") && method.equals("GET")) {
                send(ex, 200, backend.playersJson());
            } else if (path.equals("/api/factions") && method.equals("GET")) {
                send(ex, 200, backend.factionsJson());
            } else if (path.equals("/api/history") && method.equals("GET")) {
                send(ex, 200, backend.historyJson());
            } else if (path.equals("/api/ping") && method.equals("GET")) {
                send(ex, 200, "{\"ok\":true}");
            } else if (path.equals("/api/login") && method.equals("POST")) {
                login(ex);
            } else if (path.equals("/api/logout") && method.equals("POST")) {
                String h = ex.getRequestHeaders().getFirst("Authorization");
                if (h != null && h.startsWith("Bearer ")) auth.logout(h.substring(7).trim());
                send(ex, 200, "{\"ok\":true}");
            } else if (admin) {
                adminRoute(ex, method, path);
            } else {
                send(ex, 404, err("Introuvable."));
            }
        } catch (IllegalArgumentException e) {
            send(ex, 400, err(e.getMessage() == null ? "Requête invalide." : e.getMessage()));
        } catch (Exception e) {
            warn.accept("API : " + e);
            try {
                send(ex, 500, err("Erreur interne."));
            } catch (Exception ignored) {
                // connexion déjà fermée
            }
        } finally {
            ex.close();
        }
    }

    private void login(HttpExchange ex) throws IOException {
        Map<String, Object> b = readBody(ex);
        String ip = clientIp(ex);
        Auth.LoginResult r = auth.login(Json.str(b.get("name"), ""), Json.str(b.get("password"), ""), ip, System.currentTimeMillis());
        if (r.blocked) {
            ex.getResponseHeaders().set("Retry-After", String.valueOf(r.retryAfterSec));
            Map<String, Object> m = err("Trop d'essais. Réessaie dans " + (r.retryAfterSec + 59) / 60 + " min.");
            m.put("retryAfterSec", r.retryAfterSec);
            send(ex, 429, m);
            return;
        }
        if (r.session == null) {
            audit(null, "login-failed", Json.str(b.get("name"), "?"), "", false, ip);
            send(ex, 401, err("Nom ou mot de passe incorrect."));
            return;
        }
        audit(r.session, "login", r.session.name, "", true, ip);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("token", r.session.token);
        m.put("name", r.session.name);
        m.put("role", r.session.role);
        m.put("expiresAt", r.session.expires);
        send(ex, 200, m);
    }

    private void audit(Auth.Session s, String action, String target, String detail, boolean ok, String ip) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", System.currentTimeMillis());
        e.put("who", s == null ? "-" : s.name);
        e.put("role", s == null ? "-" : s.role);
        e.put("action", action);
        e.put("target", target);
        e.put("detail", detail);
        e.put("ok", ok);
        e.put("ip", ip);
        backend.audit(e);
    }

    private void adminRoute(HttpExchange ex, String method, String path) throws Exception {
        Auth.Session s = session(ex);
        if (s == null) {
            send(ex, 401, err("Connexion requise ou session expirée."));
            return;
        }
        switch (path) {
            case "/api/admin/me" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", true);
                m.put("name", s.name);
                m.put("role", s.role);
                m.put("expiresAt", s.expires);
                send(ex, 200, m);
            }
            case "/api/admin/players" -> send(ex, 200, Map.of("ok", true, "players", backend.players()));
            case "/api/admin/sanctions" -> {
                Map<String, Object> m = new LinkedHashMap<>(backend.sanctions());
                m.put("ok", true);
                send(ex, 200, m);
            }
            case "/api/admin/audit" -> {
                int lim = 100;
                try {
                    lim = Math.max(1, Math.min(200, Integer.parseInt(query(ex).getOrDefault("limit", "100"))));
                } catch (NumberFormatException ignored) {
                    // valeur par défaut
                }
                send(ex, 200, Map.of("ok", true, "entries", backend.audit(lim)));
            }
            case "/api/admin/console" -> consoleRoute(ex, s);
            case "/api/admin/factions" -> send(ex, 200, backend.adminFactionsJson());
            case "/api/admin/action" -> {
                if (!method.equals("POST")) {
                    send(ex, 405, err("POST attendu."));
                    return;
                }
                Map<String, Object> req = readBody(ex);
                String no = denied(s.role, req);
                String action = Json.str(req.get("action"), "?");
                String target = Json.str(req.get("target"), "");
                if (no != null) {
                    audit(s, action, target, "refusé : " + no, false, clientIp(ex));
                    send(ex, 403, err(no));
                    return;
                }
                Map<String, Object> res;
                try {
                    res = backend.action(s, req);
                } catch (Exception e) {
                    res = err("Échec : " + e.getMessage());
                }
                boolean ok = Boolean.TRUE.equals(res.get("ok"));
                String detail = "console".equals(action) ? ConsoleBuffer.clean(Json.str(req.get("command"), "")) : describe(req);
                audit(s, action, target, detail + (ok ? "" : " -> " + Json.str(res.get("error"), "")), ok, clientIp(ex));
                send(ex, ok ? 200 : 400, res);
            }
            default -> send(ex, 404, err("Introuvable."));
        }
    }

    private static String describe(Map<String, Object> req) {
        List<String> parts = new ArrayList<>();
        for (String k : new String[] {"reason", "duration", "message", "mode", "value", "world"}) {
            String v = Json.str(req.get(k), "");
            if (!v.isEmpty()) parts.add(k + "=" + (v.length() > 120 ? v.substring(0, 120) + "..." : v));
        }
        return String.join(", ", parts);
    }

    private void consoleRoute(HttpExchange ex, Auth.Session s) throws Exception {
        long since = -1;
        try {
            since = Long.parseLong(query(ex).getOrDefault("since", "-1"));
        } catch (NumberFormatException ignored) {
            // -1 : les dernières lignes
        }
        if (!pollSlots.tryAcquire()) {
            send(ex, 429, err("Trop de consoles ouvertes."));
            return;
        }
        try {
            List<ConsoleBuffer.Line> lines = console.read(since, 500, 25_000);
            List<Object> out = new ArrayList<>();
            for (ConsoleBuffer.Line l : lines) out.add(List.of(l.n, l.ts, l.level, l.text));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("next", lines.isEmpty() ? Math.max(since, console.last()) : lines.get(lines.size() - 1).n);
            m.put("fields", List.of("n", "ts", "level", "text"));
            m.put("lines", out);
            send(ex, 200, m);
        } finally {
            pollSlots.release();
        }
    }
}
