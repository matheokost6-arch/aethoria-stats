package fr.aethoria.stats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Journal d'audit des actions admin : fichier audit.log (une ligne JSON par action) et copie en mémoire. */
public final class AuditLog {
    private static final int KEEP = 200;
    private final Path file;
    private final List<Map<String, Object>> recent = new ArrayList<>();

    public AuditLog(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = Math.max(0, all.size() - KEEP); i < all.size(); i++) {
                    try {
                        recent.add(Json.map(Json.parse(all.get(i))));
                    } catch (Exception ignored) {
                        // ligne abîmée : ignorée
                    }
                }
            }
        } catch (IOException ignored) {
            // pas d'historique
        }
    }

    public synchronized void add(Map<String, Object> entry) {
        recent.add(entry);
        while (recent.size() > KEEP) recent.remove(0);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(entry) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // le journal en mémoire reste valable
        }
    }

    /** Les plus récentes d'abord, sans adresse IP. */
    public synchronized List<Object> latest(int limit) {
        List<Object> out = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0 && out.size() < limit; i--) {
            Map<String, Object> m = new LinkedHashMap<>(recent.get(i));
            m.remove("ip");
            out.add(m);
        }
        return out;
    }
}
