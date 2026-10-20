package fr.aethoria.stats;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Données persistantes. Aucune dépendance à Bukkit, et le temps est toujours passé en
 * paramètre : tout est testable. Ne doit être modifié que depuis un seul thread (le principal).
 */
public final class StatsData {
    public static final int SAMPLE_MINUTES = 5;
    public static final int HISTORY_MAX = 14 * 24 * 60 / SAMPLE_MINUTES;
    public static final int LOG_MAX = 50;
    public static final int DAYS_KEPT = 40;

    public static final class PlayerRec {
        public String uuid;
        public String name;
        public long first;
        public long last;
        public long playMs;
        public int sessions;
        public int deaths;
        public int kills;
        public int mobKills;
        public long broken;
        public long placed;
        public long distanceCm;
        /** Jours (epochDay) où le joueur a joué, les plus récents en dernier. */
        public final ArrayList<Long> days = new ArrayList<>();
    }

    public static final class LogEntry {
        public final long ts;
        public final String type;
        public final String text;

        public LogEntry(long ts, String type, String text) {
            this.ts = ts;
            this.type = type;
            this.text = text;
        }
    }

    public final ZoneId zone;
    public final Map<String, PlayerRec> players = new LinkedHashMap<>();
    /** Sessions en cours : uuid -> dernier point de contrôle (non persisté). */
    public final Map<String, Long> active = new HashMap<>();
    /** Début réel des sessions en cours (non persisté). */
    public final Map<String, Long> since = new HashMap<>();

    public int recordCount;
    public long recordAt;
    public long peakDay = Long.MIN_VALUE;
    public int peakToday;
    /** Minutes-joueurs par créneau : index = jourSemaine(lundi = 0) * 24 + heure. */
    public final double[] heat = new double[168];
    public final TreeMap<Long, Integer> sessionsByDay = new TreeMap<>();
    /** Points {horodatage, joueurs, tps, ramMo}. */
    public final ArrayList<double[]> history = new ArrayList<>();
    public final ArrayList<LogEntry> log = new ArrayList<>();

    public StatsData(ZoneId zone) {
        this.zone = zone;
    }

    // ---- temps ----

    public long dayOf(long ms) {
        return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay();
    }

    // ---- joueurs ----

    public PlayerRec rec(String uuid, String name, long first, long now) {
        PlayerRec r = players.get(uuid);
        if (r == null) {
            r = new PlayerRec();
            r.uuid = uuid;
            r.first = first > 0 ? first : now;
            r.last = r.first;
            players.put(uuid, r);
        }
        if (name != null) r.name = name;
        return r;
    }

    /** Démarre une session. Renvoie false si elle était déjà en cours. */
    public boolean startSession(String uuid, String name, long first, long now) {
        PlayerRec r = rec(uuid, name, first, now);
        if (active.containsKey(uuid)) return false;
        active.put(uuid, now);
        since.put(uuid, now);
        r.sessions++;
        r.last = now;
        sessionsByDay.merge(dayOf(now), 1, Integer::sum);
        markDay(r, now);
        return true;
    }

    public void endSession(String uuid, long now) {
        PlayerRec r = players.get(uuid);
        if (r != null && active.containsKey(uuid)) checkpoint(r, now);
        active.remove(uuid);
        since.remove(uuid);
    }

    private void checkpoint(PlayerRec r, long now) {
        Long st = active.get(r.uuid);
        if (st == null) return;
        if (now > st) r.playMs += now - st;
        active.put(r.uuid, now);
        r.last = now;
        markDay(r, now);
    }

    public void checkpointAll(long now) {
        for (String u : new ArrayList<>(active.keySet())) {
            PlayerRec r = players.get(u);
            if (r != null) checkpoint(r, now);
        }
    }

    private void markDay(PlayerRec r, long now) {
        long d = dayOf(now);
        int n = r.days.size();
        if (n == 0 || r.days.get(n - 1) != d) {
            r.days.add(d);
            while (r.days.size() > DAYS_KEPT) r.days.remove(0);
        }
    }

    public long playMsNow(PlayerRec r, long now) {
        Long st = active.get(r.uuid);
        return r.playMs + (st != null && now > st ? now - st : 0);
    }

    public long lastNow(PlayerRec r, long now) {
        return active.containsKey(r.uuid) ? now : r.last;
    }

    // ---- échantillons / records ----

    /** Met à jour pic du jour et record. Renvoie true si un ancien record vient d'être battu. */
    public boolean noteOnline(int online, long now) {
        long d = dayOf(now);
        if (d != peakDay) {
            peakDay = d;
            peakToday = 0;
        }
        if (online > peakToday) peakToday = online;
        if (online > recordCount) {
            boolean beaten = recordCount > 0;
            recordCount = online;
            recordAt = now;
            return beaten;
        }
        return false;
    }

    /** Échantillon périodique (toutes les 5 min). Renvoie true si un record est battu. */
    public boolean sample(long now, int online, double tps, double ramMb) {
        checkpointAll(now);
        ZonedDateTime z = Instant.ofEpochMilli(now).atZone(zone);
        int idx = (z.getDayOfWeek().getValue() - 1) * 24 + z.getHour();
        heat[idx] += online * (double) SAMPLE_MINUTES;
        history.add(new double[] {now, online, Math.round(tps * 100) / 100.0, Math.round(ramMb)});
        while (history.size() > HISTORY_MAX) history.remove(0);
        sessionsByDay.headMap(dayOf(now) - 60).clear();
        return noteOnline(online, now);
    }

    public void log(long now, String type, String text) {
        log.add(new LogEntry(now, type, text));
        while (log.size() > LOG_MAX) log.remove(0);
    }

    // ---- persistance ----

    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("count", recordCount);
        rc.put("at", recordAt);
        rc.put("peakDay", peakDay == Long.MIN_VALUE ? 0 : peakDay);
        rc.put("peakToday", peakToday);
        root.put("record", rc);
        Map<String, Object> ps = new LinkedHashMap<>();
        for (PlayerRec r : players.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.name);
            m.put("first", r.first);
            m.put("last", r.last);
            m.put("playMs", r.playMs);
            m.put("sessions", r.sessions);
            m.put("deaths", r.deaths);
            m.put("kills", r.kills);
            m.put("mobKills", r.mobKills);
            m.put("broken", r.broken);
            m.put("placed", r.placed);
            m.put("dist", r.distanceCm);
            m.put("days", r.days);
            ps.put(r.uuid, m);
        }
        root.put("players", ps);
        root.put("heat", heat);
        Map<String, Object> sb = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> e : sessionsByDay.entrySet()) sb.put(String.valueOf(e.getKey()), e.getValue());
        root.put("sessionsByDay", sb);
        root.put("history", history);
        List<Object> lg = new ArrayList<>();
        for (LogEntry e : log) lg.add(List.of(e.ts, e.type, e.text));
        root.put("log", lg);
        return root;
    }

    public static StatsData fromMap(Map<String, Object> root, ZoneId zone) {
        StatsData d = new StatsData(zone);
        Map<String, Object> rc = Json.map(root.get("record"));
        d.recordCount = (int) Json.num(rc.get("count"), 0);
        d.recordAt = Json.num(rc.get("at"), 0);
        long pd = Json.num(rc.get("peakDay"), 0);
        d.peakDay = pd == 0 ? Long.MIN_VALUE : pd;
        d.peakToday = (int) Json.num(rc.get("peakToday"), 0);
        for (Map.Entry<String, Object> e : Json.map(root.get("players")).entrySet()) {
            Map<String, Object> m = Json.map(e.getValue());
            PlayerRec r = new PlayerRec();
            r.uuid = e.getKey();
            r.name = Json.str(m.get("name"), "?");
            r.first = Json.num(m.get("first"), 0);
            r.last = Json.num(m.get("last"), 0);
            r.playMs = Json.num(m.get("playMs"), 0);
            r.sessions = (int) Json.num(m.get("sessions"), 0);
            r.deaths = (int) Json.num(m.get("deaths"), 0);
            r.kills = (int) Json.num(m.get("kills"), 0);
            r.mobKills = (int) Json.num(m.get("mobKills"), 0);
            r.broken = Json.num(m.get("broken"), 0);
            r.placed = Json.num(m.get("placed"), 0);
            r.distanceCm = Json.num(m.get("dist"), 0);
            for (Object o : Json.list(m.get("days"))) r.days.add(Json.num(o, 0));
            d.players.put(r.uuid, r);
        }
        List<Object> h = Json.list(root.get("heat"));
        for (int i = 0; i < d.heat.length && i < h.size(); i++) d.heat[i] = Json.dbl(h.get(i), 0);
        for (Map.Entry<String, Object> e : Json.map(root.get("sessionsByDay")).entrySet()) {
            d.sessionsByDay.put(Long.parseLong(e.getKey()), (int) Json.num(e.getValue(), 0));
        }
        for (Object o : Json.list(root.get("history"))) {
            List<Object> p = Json.list(o);
            if (p.size() >= 4) {
                d.history.add(new double[] {Json.dbl(p.get(0), 0), Json.dbl(p.get(1), 0), Json.dbl(p.get(2), 0), Json.dbl(p.get(3), 0)});
            }
        }
        for (Object o : Json.list(root.get("log"))) {
            List<Object> p = Json.list(o);
            if (p.size() >= 3) d.log.add(new LogEntry(Json.num(p.get(0), 0), Json.str(p.get(1), ""), Json.str(p.get(2), "")));
        }
        return d;
    }
}
