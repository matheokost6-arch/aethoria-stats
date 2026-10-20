package fr.aethoria.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Muets et joueurs immobilisés, persistés dans moderation.json. Lisible depuis n'importe quel thread. */
public final class Moderation {
    public static final class Mute {
        public final String uuid;
        public final String name;
        public final long until; // 0 = définitif
        public final String reason;
        public final String by;

        public Mute(String uuid, String name, long until, String reason, String by) {
            this.uuid = uuid;
            this.name = name;
            this.until = until;
            this.reason = reason;
            this.by = by;
        }
    }

    private final Map<String, Mute> mutes = new ConcurrentHashMap<>();
    private final Map<String, String> frozen = new ConcurrentHashMap<>(); // uuid -> nom

    public void mute(Mute m) {
        mutes.put(m.uuid, m);
    }

    public boolean unmute(String uuid) {
        return mutes.remove(uuid) != null;
    }

    /** Renvoie le mute actif, ou null (et oublie un mute expiré). */
    public Mute muteOf(String uuid, long now) {
        Mute m = mutes.get(uuid);
        if (m == null) return null;
        if (m.until != 0 && m.until <= now) {
            mutes.remove(uuid);
            return null;
        }
        return m;
    }

    public List<Mute> activeMutes(long now) {
        List<Mute> out = new ArrayList<>();
        for (String u : new ArrayList<>(mutes.keySet())) {
            Mute m = muteOf(u, now);
            if (m != null) out.add(m);
        }
        return out;
    }

    public void freeze(String uuid, String name) {
        frozen.put(uuid, name);
    }

    public boolean unfreeze(String uuid) {
        return frozen.remove(uuid) != null;
    }

    public boolean isFrozen(String uuid) {
        return frozen.containsKey(uuid);
    }

    public Map<String, String> frozenAll() {
        return new LinkedHashMap<>(frozen);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> ms = new ArrayList<>();
        for (Mute m : mutes.values()) ms.add(List.of(m.uuid, m.name, m.until, m.reason, m.by));
        root.put("mutes", ms);
        root.put("frozen", new LinkedHashMap<>(frozen));
        return root;
    }

    public void load(Map<String, Object> root) {
        mutes.clear();
        frozen.clear();
        for (Object o : Json.list(root.get("mutes"))) {
            List<Object> p = Json.list(o);
            if (p.size() >= 5) {
                Mute m = new Mute(Json.str(p.get(0), ""), Json.str(p.get(1), "?"), Json.num(p.get(2), 0), Json.str(p.get(3), ""), Json.str(p.get(4), ""));
                mutes.put(m.uuid, m);
            }
        }
        for (Map.Entry<String, Object> e : Json.map(root.get("frozen")).entrySet()) frozen.put(e.getKey(), Json.str(e.getValue(), "?"));
    }
}
