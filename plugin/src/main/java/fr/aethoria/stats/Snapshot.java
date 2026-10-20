package fr.aethoria.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/** Construit le contenu de stats.json à partir des données et de l'état vivant du serveur. */
public final class Snapshot {
    private Snapshot() {}

    public static final class OnlinePlayer {
        public String uuid;
        public String name;
        public int ping = -1;
    }

    public static final class WorldInfo {
        public String name;
        public String env;
        public int chunks;
        public int entities;
        public int living;
        public int items;
        public long time;
        public String weather;
    }

    /** État du serveur au moment de l'envoi (rempli par le thread principal). */
    public static final class Live {
        public String serverVersion = "";
        public String bukkitVersion = "";
        public int pluginCount;
        public long startedAt;
        public int cores;
        public long ramUsedMb;
        public long ramMaxMb;
        public int maxPlayers;
        public double tps1 = 20, tps5 = 20, tps15 = 20, mspt = 50;
        public final List<OnlinePlayer> online = new ArrayList<>();
        public final List<WorldInfo> worlds = new ArrayList<>();
    }

    public static String pseudo(String uuid) {
        return "Joueur-" + String.format("%04x", uuid.hashCode() & 0xffff);
    }

    public static Map<String, Object> build(StatsData d, Live live, long now, boolean hideNames) {
        Map<String, Object> out = new LinkedHashMap<>();
        long today = d.dayOf(now);
        out.put("version", 1);
        out.put("generatedAt", now);
        out.put("timezone", d.zone.getId());

        Map<String, Object> srv = new LinkedHashMap<>();
        srv.put("version", live.serverVersion);
        srv.put("bukkit", live.bukkitVersion);
        srv.put("plugins", live.pluginCount);
        srv.put("startedAt", live.startedAt);
        srv.put("uptimeMs", Math.max(0, now - live.startedAt));
        srv.put("cores", live.cores);
        srv.put("ramUsedMb", live.ramUsedMb);
        srv.put("ramMaxMb", live.ramMaxMb);
        out.put("server", srv);

        Map<String, Object> tps = new LinkedHashMap<>();
        tps.put("m1", round(live.tps1, 2));
        tps.put("m5", round(live.tps5, 2));
        tps.put("m15", round(live.tps15, 2));
        tps.put("mspt", round(live.mspt, 1));
        out.put("tps", tps);

        // joueurs en ligne
        Map<String, Object> pl = new LinkedHashMap<>();
        pl.put("online", live.online.size());
        pl.put("max", live.maxPlayers);
        pl.put("peakToday", d.peakDay == today ? d.peakToday : 0);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("count", d.recordCount);
        rec.put("at", d.recordAt);
        pl.put("record", rec);
        List<Object> onl = new ArrayList<>();
        for (OnlinePlayer o : live.online) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name(o.uuid, o.name, hideNames));
            Long s = d.since.get(o.uuid);
            m.put("since", s != null ? s : now);
            m.put("ping", o.ping >= 0 ? (Object) o.ping : null);
            onl.add(m);
        }
        pl.put("list", onl);
        out.put("players", pl);

        // nouveaux joueurs et activité
        List<StatsData.PlayerRec> all = new ArrayList<>(d.players.values());
        int newToday = 0, new7 = 0, new30 = 0;
        Map<Long, Integer> newByDay = new LinkedHashMap<>();
        for (long k = today - 29; k <= today; k++) newByDay.put(k, 0);
        for (StatsData.PlayerRec r : all) {
            long fd = d.dayOf(r.first);
            if (fd == today) newToday++;
            if (fd > today - 7) new7++;
            if (fd > today - 30) new30++;
            if (newByDay.containsKey(fd)) newByDay.merge(fd, 1, Integer::sum);
        }
        List<StatsData.PlayerRec> byFirst = new ArrayList<>(all);
        byFirst.sort(Comparator.comparingLong((StatsData.PlayerRec r) -> r.first).reversed());
        List<Object> recent = new ArrayList<>();
        for (int i = 0; i < Math.min(10, byFirst.size()); i++) {
            StatsData.PlayerRec r = byFirst.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name(r.uuid, r.name, hideNames));
            m.put("first", r.first);
            recent.add(m);
        }
        Map<String, Object> nw = new LinkedHashMap<>();
        nw.put("today", newToday);
        nw.put("d7", new7);
        nw.put("d30", new30);
        nw.put("total", all.size());
        nw.put("recent", recent);
        out.put("newPlayers", nw);

        // rétention
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("d1", retention(d, all, now, today, 1));
        ret.put("d7", retention(d, all, now, today, 7));
        ret.put("d30", retention(d, all, now, today, 30));
        int a24 = 0, a7 = 0, a30 = 0, regulars = 0;
        for (StatsData.PlayerRec r : all) {
            long last = d.lastNow(r, now);
            if (last >= now - 86_400_000L) a24++;
            if (last >= now - 7 * 86_400_000L) a7++;
            if (last >= now - 30 * 86_400_000L) a30++;
            int distinct = 0;
            for (long day : r.days) if (day > today - 7) distinct++;
            if (distinct >= 3) regulars++;
        }
        ret.put("active24h", a24);
        ret.put("active7d", a7);
        ret.put("active30d", a30);
        ret.put("regulars", regulars);
        out.put("retention", ret);

        // historique
        Map<String, Object> hist = new LinkedHashMap<>();
        hist.put("intervalSec", StatsData.SAMPLE_MINUTES * 60);
        hist.put("fields", List.of("ts", "players", "tps", "ramMb"));
        hist.put("points", d.history);
        out.put("history", hist);

        // heatmap : lignes = jours (lundi en premier), colonnes = heures
        List<Object> grid = new ArrayList<>();
        double[] byHour = new double[24];
        double[] byDow = new double[7];
        for (int w = 0; w < 7; w++) {
            double[] row = new double[24];
            for (int h = 0; h < 24; h++) {
                double v = Math.round(d.heat[w * 24 + h]);
                row[h] = v;
                byHour[h] += v;
                byDow[w] += v;
            }
            grid.add(row);
        }
        Map<String, Object> act = new LinkedHashMap<>();
        act.put("unit", "minutes-joueurs");
        act.put("heatmap", grid);
        act.put("byHour", byHour);
        act.put("byWeekday", byDow);
        out.put("activity", act);

        List<Object> nbd = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : newByDay.entrySet()) nbd.add(dayEntry(e.getKey(), e.getValue()));
        out.put("newByDay", nbd);
        List<Object> sbd = new ArrayList<>();
        for (long k = today - 29; k <= today; k++) sbd.add(dayEntry(k, d.sessionsByDay.getOrDefault(k, 0)));
        out.put("sessionsByDay", sbd);

        // classements
        Map<String, Object> lb = new LinkedHashMap<>();
        lb.put("playtime", top(d, all, now, hideNames, r -> d.playMsNow(r, now)));
        lb.put("kills", top(d, all, now, hideNames, r -> r.kills));
        lb.put("mobKills", top(d, all, now, hideNames, r -> r.mobKills));
        lb.put("deaths", top(d, all, now, hideNames, r -> r.deaths));
        lb.put("broken", top(d, all, now, hideNames, r -> r.broken));
        lb.put("placed", top(d, all, now, hideNames, r -> r.placed));
        lb.put("distance", top(d, all, now, hideNames, r -> r.distanceCm / 100));
        out.put("leaderboards", lb);

        // fiches joueurs (les 300 premiers au temps de jeu)
        List<StatsData.PlayerRec> byPlay = new ArrayList<>(all);
        byPlay.sort(Comparator.comparingLong((StatsData.PlayerRec r) -> d.playMsNow(r, now)).reversed());
        List<Object> pls = new ArrayList<>();
        for (int i = 0; i < Math.min(300, byPlay.size()); i++) {
            StatsData.PlayerRec r = byPlay.get(i);
            long pm = d.playMsNow(r, now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name(r.uuid, r.name, hideNames));
            m.put("first", r.first);
            m.put("last", d.lastNow(r, now));
            m.put("playMs", pm);
            m.put("sessions", r.sessions);
            m.put("avgSessionMs", r.sessions > 0 ? pm / r.sessions : 0);
            m.put("deaths", r.deaths);
            m.put("kills", r.kills);
            m.put("mobKills", r.mobKills);
            m.put("broken", r.broken);
            m.put("placed", r.placed);
            m.put("distanceM", r.distanceCm / 100);
            m.put("online", d.active.containsKey(r.uuid));
            pls.add(m);
        }
        out.put("playerList", pls);

        // mondes
        List<Object> ws = new ArrayList<>();
        for (WorldInfo w : live.worlds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", w.name);
            m.put("env", w.env);
            m.put("chunks", w.chunks);
            m.put("entities", w.entities);
            m.put("living", w.living);
            m.put("items", w.items);
            m.put("time", w.time);
            m.put("weather", w.weather);
            ws.add(m);
        }
        out.put("worlds", ws);

        // journal, du plus récent au plus ancien
        List<Object> lg = new ArrayList<>();
        for (int i = d.log.size() - 1; i >= 0; i--) {
            StatsData.LogEntry e = d.log.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", e.ts);
            m.put("type", e.type);
            m.put("text", hideNames ? hideIn(e.text, all) : e.text);
            lg.add(m);
        }
        out.put("log", lg);
        return out;
    }

    private static Map<String, Object> retention(StatsData d, List<StatsData.PlayerRec> all, long now, long today, int n) {
        int cohort = 0, back = 0;
        for (StatsData.PlayerRec r : all) {
            long fd = d.dayOf(r.first);
            if (fd > today - n || fd < today - 120) continue;
            cohort++;
            if (d.dayOf(d.lastNow(r, now)) >= fd + n) back++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cohort", cohort);
        m.put("returned", back);
        m.put("rate", cohort > 0 ? (Object) round(100.0 * back / cohort, 1) : null);
        return m;
    }

    private static List<Object> top(StatsData d, List<StatsData.PlayerRec> all, long now, boolean hide, ToLongFunction<StatsData.PlayerRec> f) {
        List<StatsData.PlayerRec> l = new ArrayList<>(all);
        l.sort(Comparator.comparingLong(f).reversed());
        List<Object> out = new ArrayList<>();
        for (StatsData.PlayerRec r : l) {
            long v = f.applyAsLong(r);
            if (v <= 0 || out.size() >= 15) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name(r.uuid, r.name, hide));
            m.put("value", v);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> dayEntry(long epochDay, int n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", LocalDate.ofEpochDay(epochDay).toString());
        m.put("n", n);
        return m;
    }

    private static String name(String uuid, String name, boolean hide) {
        return hide ? pseudo(uuid) : name;
    }

    private static String hideIn(String text, List<StatsData.PlayerRec> all) {
        String t = text;
        for (StatsData.PlayerRec r : all) {
            if (r.name != null && r.name.length() > 2 && t.contains(r.name)) t = t.replace(r.name, pseudo(r.uuid));
        }
        return t;
    }

    private static double round(double v, int dec) {
        double p = Math.pow(10, dec);
        return Math.round(v * p) / p;
    }

    /** Pour les tests : formate un instant. */
    static String iso(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }
}
