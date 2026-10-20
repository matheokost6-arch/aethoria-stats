package fr.aethoria.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecture de aethoria-export.json, écrit par le mod Aethoria (noms RP, factions, couronne).
 * Aucune dépendance à Bukkit. Tous les champs peuvent être absents ou null.
 */
public final class AethoriaExport {
    public final long generatedAt;
    /** uuid -> fiche brute (name, firstName, lastName, rpName, faction, role, job, jobLevel). */
    public final Map<String, Map<String, Object>> players = new LinkedHashMap<>();
    public final List<Map<String, Object>> factions = new ArrayList<>();
    public final Map<String, Object> crown;
    /** id de faction -> données réservées aux admins (trésor, claims, cités). */
    public final List<Map<String, Object>> adminFactions = new ArrayList<>();

    private AethoriaExport(long generatedAt, Map<String, Object> crown) {
        this.generatedAt = generatedAt;
        this.crown = crown;
    }

    /** Lève IllegalArgumentException si le texte n'est pas un export lisible. */
    public static AethoriaExport parse(String text) {
        Object root = Json.parse(text);
        if (!(root instanceof Map)) throw new IllegalArgumentException("l'export n'est pas un objet JSON");
        Map<String, Object> m = Json.map(root);
        AethoriaExport ex = new AethoriaExport(Json.num(m.get("generatedAt"), 0), Json.map(m.get("crown")));
        for (Map.Entry<String, Object> e : Json.map(m.get("players")).entrySet()) {
            if (e.getValue() instanceof Map) ex.players.put(e.getKey(), Json.map(e.getValue()));
        }
        for (Object o : Json.list(m.get("factions"))) {
            if (o instanceof Map) ex.factions.add(Json.map(o));
        }
        for (Object o : Json.list(Json.map(m.get("admin")).get("factions"))) {
            if (o instanceof Map) ex.adminFactions.add(Json.map(o));
        }
        return ex;
    }

    public String rpName(String uuid) {
        Map<String, Object> p = players.get(uuid);
        if (p == null) return null;
        String rp = Json.str(p.get("rpName"), null);
        // le mod met le pseudo comme nom RP quand le joueur n'a pas de prénom : rien à ajouter
        return rp == null || rp.equalsIgnoreCase(Json.str(p.get("name"), "")) ? null : rp;
    }

    public String factionId(String uuid) {
        Map<String, Object> p = players.get(uuid);
        return p == null ? null : idString(p.get("faction"));
    }

    public String role(String uuid) {
        Map<String, Object> p = players.get(uuid);
        return p == null ? null : Json.str(p.get("role"), null);
    }

    /** Les identifiants peuvent être des nombres ou des textes selon le mod. */
    public static String idString(Object o) {
        if (o == null) return null;
        if (o instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
        return String.valueOf(o);
    }

    public Map<String, String> factionByUuid() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String u : players.keySet()) {
            String f = factionId(u);
            if (f != null) out.put(u, f);
        }
        return out;
    }
}
