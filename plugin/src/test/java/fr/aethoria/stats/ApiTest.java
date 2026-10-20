package fr.aethoria.stats;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test de l'API sans serveur Minecraft : authentification, rôles, force brute, CORS, console en direct.
 * Usage : java -cp ... fr.aethoria.stats.ApiTest            (tests)
 *         java -cp ... fr.aethoria.stats.ApiTest serve 8766 stats.json   (faux serveur pour la page)
 */
public final class ApiTest {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static String base;
    private static int failures;

    static final class Fake implements HttpApi.Backend {
        final AuditLog audit;
        final String stats;
        final List<String> done = new ArrayList<>();

        Fake(AuditLog a, String stats) {
            this.audit = a;
            this.stats = stats;
        }

        public String statsJson() { return stats; }

        public String playersJson() { return "{\"players\":[{\"name\":\"Aldric\"}]}"; }

        public String historyJson() { return "{\"points\":[]}"; }

        public Map<String, Object> action(Auth.Session who, Map<String, Object> req) {
            done.add(who.name + ":" + req.get("action") + ":" + req.get("target"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("message", "Fait : " + req.get("action"));
            return m;
        }

        public List<Object> players() {
            List<Object> l = new ArrayList<>();
            String[][] ps = {{"Aldric", "20", "18"}, {"Jehanne", "11.5", "6"}, {"Kaelen", "3", "20"}};
            for (String[] p : ps) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p[0]);
                m.put("uuid", "00000000-0000-0000-0000-00000000000" + p[0].length());
                m.put("health", Double.parseDouble(p[1]));
                m.put("maxHealth", 20);
                m.put("food", Integer.parseInt(p[2]));
                m.put("world", "world");
                m.put("x", 120);
                m.put("y", 64);
                m.put("z", -340);
                m.put("gamemode", "survival");
                m.put("ping", 42);
                m.put("muted", p[0].equals("Kaelen"));
                m.put("mutedUntil", 0);
                m.put("frozen", false);
                m.put("op", false);
                m.put("loggedIn", true);
                l.add(m);
            }
            return l;
        }

        public Map<String, Object> sanctions() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bans", List.of(Map.of("name", "Griefer42", "reason", "Destruction", "until", 0, "created", 1, "by", "Admin")));
            m.put("mutes", List.of(Map.of("name", "Kaelen", "reason", "Spam", "until", 0, "by", "Modo")));
            m.put("frozen", List.of());
            return m;
        }

        public List<Object> audit(int limit) { return audit.latest(limit); }

        public void audit(Map<String, Object> e) { audit.add(e); }
    }

    static void check(boolean ok, String what) {
        if (ok) {
            System.out.println("ok  " + what);
        } else {
            failures++;
            System.out.println("ECHEC  " + what);
        }
    }

    static HttpResponse<String> call(String method, String path, String body, String token, String origin, String xff) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(40));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (origin != null) b.header("Origin", origin);
        if (xff != null) b.header("X-Forwarded-For", xff);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static String login(String name, String pw, String xff) throws Exception {
        HttpResponse<String> r = call("POST", "/api/login", Json.write(Map.of("name", name, "password", pw)), null, "http://localhost:3000", xff);
        if (r.statusCode() != 200) return null;
        return Json.str(Json.map(Json.parse(r.body())).get("token"), null);
    }

    static String act(String action, String target, String extra) {
        return "{\"action\":\"" + action + "\",\"target\":\"" + target + "\"" + extra + "}";
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("astats-test");
        AuditLog audit = new AuditLog(tmp.resolve("audit.log"));
        Auth auth = new Auth(12 * 3_600_000L);
        Map<String, Auth.Account> accs = new java.util.HashMap<>();
        accs.put("chef", new Auth.Account("Chef", Auth.hash("mot-de-passe-solide"), "admin"));
        accs.put("modo1", new Auth.Account("Modo1", Auth.hash("autre-mot-de-passe"), "modo"));
        auth.setAccounts(accs);
        ConsoleBuffer console = new ConsoleBuffer();
        for (int i = 0; i < 5; i++) console.add(System.currentTimeMillis(), "INFO", "[Server thread/INFO]: Ligne de démarrage " + i);
        console.add(System.currentTimeMillis(), "WARN", "Can't keep up! Is the server overloaded?");
        String statsText = args.length >= 3 ? Files.readString(Path.of(args[2]), StandardCharsets.UTF_8) : "{\"version\":1,\"players\":{\"online\":2}}";
        Fake fake = new Fake(audit, statsText);
        HttpApi api = new HttpApi(fake, auth, console, Set.of("https://matheokost6-arch.github.io"), m -> System.out.println("warn: " + m));

        if (args.length >= 2 && args[0].equals("serve")) {
            api.start("127.0.0.1", Integer.parseInt(args[1]));
            System.out.println("Faux serveur sur le port " + api.port() + " (chef / mot-de-passe-solide, modo1 / autre-mot-de-passe)");
            Thread t = new Thread(() -> {
                int i = 0;
                while (true) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    console.add(System.currentTimeMillis(), i % 7 == 0 ? "WARN" : "INFO", "[Server thread/INFO]: Aldric a dit bonjour (" + (++i) + ")");
                }
            });
            t.setDaemon(false);
            t.start();
            return;
        }

        api.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + api.port();

        // --- hash ---
        String h = Auth.hash("abcdefghij");
        check(h.startsWith("pbkdf2$210000$") && !h.contains("abcdefghij"), "hash PBKDF2 salé, sans mot de passe en clair");
        check(Auth.verify("abcdefghij", h) && !Auth.verify("abcdefghik", h) && !Auth.verify("x", "n'importe quoi"), "vérification du hash");
        check(!Auth.hash("abcdefghij").equals(h), "sel différent à chaque hash");

        // --- nettoyage console ---
        String c = ConsoleBuffer.clean("\u001B[32;1mVert\u001B[m §aOK Shadow[/192.168.1.20:5555] logged in /astats setpass chef SecretTresLong");
        check(!c.contains("\u001B") && !c.contains("§") && c.contains("[ip]") && !c.contains("192.168") && !c.contains("SecretTresLong") && c.contains("setpass chef ***"),
                "console : ANSI, §, IP et mot de passe retirés (" + c + ")");
        check(ConsoleBuffer.clean("Version 1.20.1 build 4.5").contains("1.20.1"), "console : les numéros de version restent");

        // --- public ---
        HttpResponse<String> r = call("GET", "/api/stats", null, null, "https://matheokost6-arch.github.io", null);
        check(r.statusCode() == 200 && "https://matheokost6-arch.github.io".equals(r.headers().firstValue("Access-Control-Allow-Origin").orElse("")), "GET /api/stats public + CORS pour l'origine autorisée");
        r = call("GET", "/api/players", null, null, null, null);
        check(r.statusCode() == 200 && r.body().contains("Aldric"), "GET /api/players public");
        r = call("GET", "/api/history", null, null, null, null);
        check(r.statusCode() == 200 && r.body().contains("points"), "GET /api/history public");
        r = call("GET", "/api/stats", null, null, "https://evil.example", null);
        check(r.statusCode() == 200 && r.headers().firstValue("Access-Control-Allow-Origin").isEmpty(), "pas de CORS pour une origine inconnue");
        r = call("GET", "/api/stats", null, null, "http://localhost:5500", null);
        check(r.headers().firstValue("Access-Control-Allow-Origin").orElse("").equals("http://localhost:5500"), "CORS pour localhost:*");
        r = call("OPTIONS", "/api/admin/action", null, null, "https://matheokost6-arch.github.io", null);
        check(r.statusCode() == 204 && r.headers().firstValue("Access-Control-Allow-Headers").orElse("").contains("Authorization"), "pré-vol OPTIONS");
        check(!r.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("DELETE"), "méthodes limitées");

        // --- authentification ---
        r = call("GET", "/api/admin/players", null, null, null, null);
        check(r.statusCode() == 401, "admin sans jeton : 401");
        r = call("GET", "/api/admin/players", null, "faux-jeton", null, null);
        check(r.statusCode() == 401, "jeton inconnu : 401");
        r = call("POST", "/api/login", "{\"name\":\"chef\",\"password\":\"mauvais\"}", null, null, null);
        check(r.statusCode() == 401, "mauvais mot de passe : 401");
        r = call("POST", "/api/login", "{\"name\":\"inconnu\",\"password\":\"mauvais\"}", null, null, null);
        check(r.statusCode() == 401 && r.body().contains("Nom ou mot de passe"), "compte inconnu : même message");
        String tAdmin = login("Chef", "mot-de-passe-solide", null);
        String tModo = login("modo1", "autre-mot-de-passe", null);
        check(tAdmin != null && tAdmin.length() >= 42 && !tAdmin.equals(tModo), "jetons aléatoires de 32 octets");
        r = call("GET", "/api/admin/me", null, tAdmin, null, null);
        check(r.statusCode() == 200 && r.body().contains("\"role\":\"admin\""), "/api/admin/me");
        r = call("POST", "/api/login", "{\"name\":\"chef\",\"password\":\"mot-de-passe-solide\"}", null, "https://evil.example", null);
        check(r.statusCode() == 403, "connexion refusée depuis une origine inconnue");

        // --- infos admin ---
        r = call("GET", "/api/admin/players", null, tAdmin, null, null);
        check(r.statusCode() == 200 && r.body().contains("\"health\":11.5") && !r.body().toLowerCase().contains("\"ip\""), "liste des joueurs (vie, faim, position...), sans IP");
        r = call("GET", "/api/admin/sanctions", null, tModo, null, null);
        check(r.statusCode() == 200 && r.body().contains("Griefer42"), "bannis et muets");

        // --- actions et rôles ---
        r = call("POST", "/api/admin/action", act("heal", "Aldric", ""), tModo, null, null);
        check(r.statusCode() == 200, "modo : soigner");
        r = call("POST", "/api/admin/action", act("ban", "Aldric", ",\"reason\":\"test\""), tModo, null, null);
        check(r.statusCode() == 403, "modo : ban définitif refusé");
        r = call("POST", "/api/admin/action", act("ban", "Aldric", ",\"duration\":\"1d\""), tModo, null, null);
        check(r.statusCode() == 200, "modo : ban d'un jour accepté");
        r = call("POST", "/api/admin/action", "{\"action\":\"console\",\"command\":\"op Moi\"}", tModo, null, null);
        check(r.statusCode() == 403, "modo : console libre refusée");
        r = call("POST", "/api/admin/action", "{\"action\":\"console\",\"command\":\"list\"}", tAdmin, null, null);
        check(r.statusCode() == 200, "admin : console libre");
        r = call("POST", "/api/admin/action", act("ban", "Aldric", ""), tAdmin, null, null);
        check(r.statusCode() == 200, "admin : ban définitif");
        r = call("POST", "/api/admin/action", act("detruire", "Aldric", ""), tAdmin, null, null);
        check(r.statusCode() == 403, "action inconnue refusée");
        check(HttpApi.durationMs("30m") == 1_800_000L && HttpApi.durationMs("7d") == 604_800_000L && HttpApi.durationMs("") == 0 && HttpApi.durationMs("abc") == -1, "durées");

        // --- audit ---
        r = call("GET", "/api/admin/audit?limit=50", null, tModo, null, null);
        check(r.statusCode() == 200 && r.body().contains("\"action\":\"ban\"") && r.body().contains("refusé") && !r.body().contains("\"ip\""), "journal d'audit (refus compris, sans IP)");
        String file = Files.readString(tmp.resolve("audit.log"), StandardCharsets.UTF_8);
        check(file.split("\n").length >= 8 && file.contains("\"ip\""), "audit.log écrit sur disque (" + file.split("\n").length + " lignes)");

        // --- console en direct ---
        r = call("GET", "/api/admin/console?since=-1", null, tAdmin, null, null);
        Map<String, Object> cm = Json.map(Json.parse(r.body()));
        long next = Json.num(cm.get("next"), -1);
        check(r.statusCode() == 200 && Json.list(cm.get("lines")).size() == 6 && next == 6, "console : dernières lignes");
        long t0 = System.currentTimeMillis();
        Thread feeder = new Thread(() -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
                return;
            }
            console.add(System.currentTimeMillis(), "INFO", "Nouvelle ligne en direct");
        });
        feeder.start();
        r = call("GET", "/api/admin/console?since=" + next, null, tAdmin, null, null);
        long waited = System.currentTimeMillis() - t0;
        check(r.statusCode() == 200 && r.body().contains("Nouvelle ligne en direct") && waited >= 600 && waited < 5000, "console : long-polling réveillé en " + waited + " ms");
        t0 = System.currentTimeMillis();
        r = call("GET", "/api/admin/console?since=" + console.last(), null, tAdmin, null, null);
        waited = System.currentTimeMillis() - t0;
        check(r.statusCode() == 200 && waited >= 24_000 && waited < 27_000 && Json.list(Json.map(Json.parse(r.body())).get("lines")).isEmpty(), "console : attente de 25 s sans nouveauté (" + waited + " ms)");

        // --- force brute, par IP (X-Forwarded-For lu depuis 127.0.0.1) ---
        int last = 0;
        for (int i = 0; i < 5; i++) {
            r = call("POST", "/api/login", "{\"name\":\"chef\",\"password\":\"non" + i + "\"}", null, null, "203.0.113.9");
            last = r.statusCode();
        }
        check(last == 401, "5 échecs : le 5e est encore un 401");
        r = call("POST", "/api/login", "{\"name\":\"chef\",\"password\":\"mot-de-passe-solide\"}", null, null, "203.0.113.9");
        check(r.statusCode() == 429 && r.headers().firstValue("Retry-After").isPresent(), "IP bloquée même avec le bon mot de passe (429)");
        r = call("POST", "/api/login", "{\"name\":\"chef\",\"password\":\"mot-de-passe-solide\"}", null, null, "203.0.113.77");
        check(r.statusCode() == 200, "une autre IP n'est pas bloquée");
        long now = System.currentTimeMillis();
        check(auth.blockedFor("203.0.113.9", now) > 800 && auth.blockedFor("203.0.113.9", now + Auth.BLOCK_MS + 5000) == 0, "blocage de 15 min, puis levé");

        // --- déconnexion ---
        call("POST", "/api/logout", "{}", tAdmin, null, null);
        r = call("GET", "/api/admin/me", null, tAdmin, null, null);
        check(r.statusCode() == 401, "déconnexion : le jeton ne marche plus");
        Auth shortLived = new Auth(1);
        shortLived.setAccounts(accs);
        Auth.Session s = shortLived.login("chef", "mot-de-passe-solide", "1.1.1.1", 1000).session;
        check(s != null && shortLived.check(s.token, 1000) != null && shortLived.check(s.token, 5000) == null, "expiration du jeton");

        api.stop();
        System.out.println(failures == 0 ? "TOUS LES TESTS API OK" : failures + " ECHEC(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
