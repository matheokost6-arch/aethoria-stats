package fr.aethoria.stats;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Envoie stats.json dans un Gist GitHub, hors du thread principal. */
public final class Uploader {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AethoriaStats-http");
        t.setDaemon(true);
        return t;
    });
    private final Consumer<String> warn;
    private final Consumer<String> info;

    private volatile String gistId = "";
    private volatile String token = "";
    private volatile String fileName = "stats.json";

    public volatile long lastOk;
    public volatile long lastAttempt;
    public volatile int lastCode;
    public volatile String lastError = "";
    public volatile int failures;
    public volatile long lastDurationMs;
    private volatile boolean busy;
    private String lastLogged = "";

    public Uploader(Consumer<String> warn, Consumer<String> info) {
        this.warn = warn;
        this.info = info;
    }

    public void configure(String gistId, String token, String fileName) {
        this.gistId = gistId == null ? "" : gistId.trim();
        this.token = token == null ? "" : token.trim();
        this.fileName = fileName == null || fileName.isBlank() ? "stats.json" : fileName.trim();
    }

    public boolean isConfigured() {
        return !gistId.isEmpty() && !token.isEmpty();
    }

    public boolean isBusy() {
        return busy;
    }

    /** Lance l'envoi en arrière-plan. Ignoré si un envoi est déjà en cours. */
    public void send(String statsJson) {
        if (!isConfigured() || busy) return;
        busy = true;
        final String id = gistId, tok = token, file = fileName;
        exec.execute(() -> {
            long t0 = System.currentTimeMillis();
            lastAttempt = t0;
            try {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("content", statsJson);
                Map<String, Object> files = new LinkedHashMap<>();
                files.put(file, f);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("files", files);
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/gists/" + id))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + tok)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "AethoriaStats")
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(Json.write(body)))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                lastCode = resp.statusCode();
                if (lastCode / 100 == 2) {
                    lastOk = System.currentTimeMillis();
                    lastError = "";
                    failures = 0;
                    if (!lastLogged.isEmpty()) {
                        info.accept("Envoi des stats rétabli.");
                        lastLogged = "";
                    }
                } else {
                    fail("HTTP " + lastCode + hint(lastCode));
                }
            } catch (Exception e) {
                lastCode = 0;
                fail(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            } finally {
                lastDurationMs = System.currentTimeMillis() - t0;
                busy = false;
            }
        });
    }

    private void fail(String msg) {
        failures++;
        lastError = msg;
        if (!msg.equals(lastLogged)) { // une seule fois par type d'erreur
            lastLogged = msg;
            warn.accept("Envoi des stats impossible (" + msg + "). Les prochains échecs identiques ne seront plus affichés.");
        }
    }

    private static String hint(int code) {
        return switch (code) {
            case 401 -> " (token invalide ou expiré)";
            case 403 -> " (permission Gists manquante ou limite atteinte)";
            case 404 -> " (gist introuvable : vérifier gist-id et le droit sur ce gist)";
            case 422 -> " (contenu refusé)";
            default -> "";
        };
    }

    /** Nombre de cycles à sauter après des échecs répétés (0 à 4). */
    public int backoff() {
        return Math.min(failures, 5) <= 1 ? 0 : Math.min(failures, 5) - 1;
    }

    public void shutdown() {
        exec.shutdown();
    }
}
