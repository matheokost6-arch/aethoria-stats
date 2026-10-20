package fr.aethoria.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Construit les JSON publics : stats.json (résumé léger), players.json (fiches) et history.json (courbes).
 * Aucune adresse IP, aucun contenu de chat.
 */
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

    /** État du serveur au moment de la construction (rempli par le thread principal). */
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
        /** Secondes restantes de la période de démarrage (mesures ignorées), 0 si terminée. */
        public int warmupSec;
        public final List<OnlinePlayer> online = new ArrayList<>();
        public final List<WorldInfo> worlds = new ArrayList<>();
    }

    private static final String[] BOARDS = {"playtime", "kills", "mobKills", "deaths", "broken", "placed", "distance"};

    public static String pseudo(String uuid) {
        return "Joueur-" + String.format("%04x", uuid.hashCode() & 0xffff);
    }

    private static ToLongFunction<StatsData.PlayerRec> metric(StatsData d, String key, long now) {
        switch (key) {
            case "playtime": return r -> d.playMsNow(r, now);
            case "kills": return r -> r.kills;
            case "mobKills": return r -> r.mobKills;
            case "deaths": return r -> r.deaths;
            case "broken": return r -> r.broken;
            case "placed": return r -> r.placed;
            case "distance": return r -> r.distanceCm / 100;
            default: return r -> 0;
        }
    }

    /** Temps de jeu sur les {@code days} derniers jours (aujourd'hui compris). */
    private static long playOver(StatsData d, StatsData.PlayerRec r, long now, long today, int days) {
        long sum = 0;
        for (Map.Entry<Long, Long> e : r.playByDay.tailMap(today - days + 1).entrySet()) sum += e.getValue();
        // le temps de la session en cours, pas encore compté
        Long st = d.active.get(r.uuid);
        if (st != null && now > st) sum += now - st;
        return sum;
    }

    public static Map<String, Object> build(StatsData d, Live live, long now, boolean hideNames) {
        return build(d, live, now, hideNames, null);
    }

    /** Ajoute le nom RP (et la faction) d'un joueur dans une entrée JSON, si l'export du mod est présent. */
    private static void rp(Map<String, Object> m, AethoriaExport ex, String uuid) {
        if (ex == null) return;
        String r = ex.rpName(uuid);
        if (r != null) m.put("rp", r);
    }

    public static Map<String, Object> build(StatsData d, Live live, long now, boolean hideNames, AethoriaExport ex) {
        Map<String, Object> out = new LinkedHashMap<>();
        long today = d.dayOf(now);
        out.put("version", 2);
        out.put("generatedAt", now);
        out.put("timezone", d.zone.getId());
        Map<String, Object> exInfo = new LinkedHashMap<>();
        exInfo.put("available", ex != null);
        exInfo.put("generatedAt", ex == null ? 0 : ex.generatedAt);
        exInfo.put("factions", ex == null ? 0 : ex.factions.size());
        out.put("export", exInfo);

        Map<String, Object> srv = new LinkedHashMap<>();
        srv.put("version", live.serverVersion);
        srv.put("bukkit", live.bukkitVersion);
        srv.put("plugins", live.pluginCount);
        srv.put("startedAt", live.startedAt);
        srv.put("uptimeMs", Math.max(0, now - live.startedAt));
        srv.put("bootMs", d.bootMs);
        srv.put("warmupSec", live.warmupSec);
        srv.put("cores", live.cores);
        srv.put("ramUsedMb", live.ramUsedMb);
        srv.put("ramMaxMb", live.ramMaxMb);
        // TPS des dernières 24 h, d'après les échantillons de 5 minutes
        double sum = 0, mn = 20, mx = 0;
        int n = 0;
        List<double[]> spikes = new ArrayList<>();
        for (double[] p : d.history) {
            if (p[0] < now - 86_400_000L) continue;
            n++;
            sum += p[2];
            mx = Math.max(mx, p[2]);
            double low = p.length > 4 ? p[4] : p[2];
            mn = Math.min(mn, low);
            if (low < 18) spikes.add(new double[] {p[0], low});
        }
        Map<String, Object> t24 = new LinkedHashMap<>();
        t24.put("min", n > 0 ? round(mn, 1) : null);
        t24.put("max", n > 0 ? round(mx, 1) : null);
        t24.put("avg", n > 0 ? round(sum / n, 2) : null);
        srv.put("tps24h", t24);
        spikes.sort(Comparator.comparingDouble(a -> a[1]));
        List<Object> sp = new ArrayList<>();
        for (int i = 0; i < Math.min(5, spikes.size()); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", (long) spikes.get(i)[0]);
            m.put("tps", round(spikes.get(i)[1], 1));
            sp.add(m);
        }
        srv.put("lagSpikes", sp);
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
            rp(m, ex, o.uuid);
            Long s = d.since.get(o.uuid);
            m.put("since", s != null ? s : now);
            m.put("ping", o.ping >= 0 ? (Object) o.ping : null);
            onl.add(m);
        }
        pl.put("list", onl);
        pl.put("peakByHour", d.peakByHour);
        List<Object> asd = new ArrayList<>();
        for (long k = today - 13; k <= today; k++) {
            long[] st = d.sessionStats.get(k);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", LocalDate.ofEpochDay(k).toString());
            m.put("minutes", st != null && st[1] > 0 ? round(st[0] / 60000.0 / st[1], 1) : 0);
            m.put("n", st != null ? st[1] : 0);
            asd.add(m);
        }
        pl.put("avgSessionByDay", asd);
        out.put("players", pl);

        // nouveaux joueurs, répartition, anniversaires
        List<StatsData.PlayerRec> all = new ArrayList<>(d.players.values());
        int newToday = 0, new7 = 0, new30 = 0;
        int mixNew = 0, mixRegular = 0, mixLost = 0, mixOld = 0;
        Map<Long, Integer> newByDay = new LinkedHashMap<>();
        for (long k = today - 29; k <= today; k++) newByDay.put(k, 0);
        List<Object> birthdays = new ArrayList<>();
        LocalDate td = LocalDate.ofEpochDay(today);
        for (StatsData.PlayerRec r : all) {
            long fd = d.dayOf(r.first);
            if (fd == today) newToday++;
            if (fd > today - 7) new7++;
            if (fd > today - 30) new30++;
            if (newByDay.containsKey(fd)) newByDay.merge(fd, 1, Integer::sum);
            int distinct = 0;
            for (long day : r.days) if (day > today - 7) distinct++;
            boolean lost = r.days.size() <= 1 && r.sessions <= 1 && !d.active.containsKey(r.uuid) && d.lastNow(r, now) < now - 3 * 86_400_000L && fd < today - 3;
            if (fd > today - 7) mixNew++;
            else if (distinct >= 3) mixRegular++;
            else if (lost) mixLost++;
            else mixOld++;
            LocalDate fdate = LocalDate.ofEpochDay(fd);
            long months = ChronoUnit.MONTHS.between(fdate, td);
            if (months >= 1 && fdate.plusMonths(months).equals(td) && birthdays.size() < 8 && (months < 12 || months % 12 == 0)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name(r.uuid, r.name, hideNames));
                rp(m, ex, r.uuid);
                m.put("text", months < 12 ? months + " mois" : (months / 12) + (months / 12 > 1 ? " ans" : " an"));
                birthdays.add(m);
            }
        }
        List<StatsData.PlayerRec> byFirst = new ArrayList<>(all);
        byFirst.sort(Comparator.comparingLong((StatsData.PlayerRec r) -> r.first).reversed());
        List<Object> recent = new ArrayList<>();
        for (int i = 0; i < Math.min(10, byFirst.size()); i++) {
            StatsData.PlayerRec r = byFirst.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name(r.uuid, r.name, hideNames));
            rp(m, ex, r.uuid);
            m.put("first", r.first);
            recent.add(m);
        }
        Map<String, Object> nw = new LinkedHashMap<>();
        nw.put("today", newToday);
        nw.put("d7", new7);
        nw.put("d30", new30);
        nw.put("total", all.size());
        nw.put("recent", recent);
        nw.put("anniversaries", birthdays);
        out.put("newPlayers", nw);

        Map<String, Object> mix = new LinkedHashMap<>();
        mix.put("new", mixNew);
        mix.put("regular", mixRegular);
        mix.put("old", mixOld);
        mix.put("lost", mixLost);
        mix.put("total", all.size());

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
        ret.put("mix", mix);
        out.put("retention", ret);

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
        List<Object> chat = new ArrayList<>();
        for (long k = today - 13; k <= today; k++) chat.add(dayEntry(k, d.chatByDay.getOrDefault(k, 0)));
        act.put("chatByDay", chat);
        out.put("activity", act);

        List<Object> nbd = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : newByDay.entrySet()) nbd.add(dayEntry(e.getKey(), e.getValue()));
        out.put("newByDay", nbd);
        List<Object> sbd = new ArrayList<>();
        for (long k = today - 29; k <= today; k++) sbd.add(dayEntry(k, d.sessionsByDay.getOrDefault(k, 0)));
        out.put("sessionsByDay", sbd);

        // classements (top 15)
        Map<String, Object> lb = new LinkedHashMap<>();
        for (String b : BOARDS) lb.put(b, top(all, hideNames, metric(d, b, now), ex));
        lb.put("playtimeDay", top(all, hideNames, r -> playOver(d, r, now, today, 1), ex));
        lb.put("playtimeWeek", top(all, hideNames, r -> playOver(d, r, now, today, 7), ex));
        lb.put("playtimeMonth", top(all, hideNames, r -> playOver(d, r, now, today, 30), ex));
        out.put("leaderboards", lb);

        // records
        Map<String, Object> rc = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("ms", d.longestMs);
        ls.put("name", hideNames ? hideIn(d.longestName, all) : d.longestName);
        ls.put("at", d.longestAt);
        rc.put("longestSession", ls);
        rc.put("peakPlayers", rec);
        StatsData.PlayerRec wk = null;
        long wkMs = 0;
        for (StatsData.PlayerRec r : all) {
            long v = playOver(d, r, now, today, 7);
            if (v > wkMs) {
                wkMs = v;
                wk = r;
            }
        }
        Map<String, Object> wt = new LinkedHashMap<>();
        wt.put("name", wk == null ? null : name(wk.uuid, wk.name, hideNames));
        if (wk != null) rp(wt, ex, wk.uuid);
        wt.put("ms", wkMs);
        rc.put("weekTop", wt);
        out.put("records", rc);

        // vie du royaume
        Map<String, Object> game = new LinkedHashMap<>();
        game.put("deathCauses", topMap(d.deathCauses, 10, "label"));
        game.put("killers", topMap(d.killers, 8, "type"));
        game.put("mobsKilled", topMap(d.mobsKilled, 10, "type"));
        game.put("commands", topMap(d.commands, 10, "name"));
        List<Object> adv = new ArrayList<>();
        for (int i = d.advancements.size() - 1; i >= 0 && adv.size() < 15; i--) {
            String[] a = d.advancements.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", Long.parseLong(a[0]));
            m.put("name", hideNames ? hideIn(a[1], all) : a[1]);
            m.put("key", a[2]);
            adv.add(m);
        }
        game.put("advancements", adv);
        long clToday = 0, clTotal = 0;
        for (long[] c : d.clearlag) {
            clTotal += c[1];
            if (d.dayOf(c[0]) == today) clToday += c[1];
        }
        Map<String, Object> cl = new LinkedHashMap<>();
        cl.put("runs", d.clearlag.size());
        cl.put("removedToday", clToday);
        cl.put("removedRecent", clTotal);
        if (!d.clearlag.isEmpty()) {
            long[] lastC = d.clearlag.get(d.clearlag.size() - 1);
            cl.put("lastAt", lastC[0]);
            cl.put("lastRemoved", lastC[1]);
        }
        game.put("clearlag", cl);
        out.put("game", game);

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

    /** Fiches joueurs (les 500 premiers au temps de jeu), avec leur rang dans chaque classement. */
    public static Map<String, Object> players(StatsData d, long now, boolean hideNames) {
        return players(d, now, hideNames, null);
    }

    public static Map<String, Object> players(StatsData d, long now, boolean hideNames, AethoriaExport ex) {
        long today = d.dayOf(now);
        List<StatsData.PlayerRec> all = new ArrayList<>(d.players.values());
        Map<String, Map<String, Integer>> ranks = new LinkedHashMap<>();
        for (String b : BOARDS) {
            ToLongFunction<StatsData.PlayerRec> f = metric(d, b, now);
            List<StatsData.PlayerRec> l = new ArrayList<>(all);
            l.sort(Comparator.comparingLong(f).reversed());
            Map<String, Integer> m = new LinkedHashMap<>();
            for (int i = 0; i < l.size(); i++) m.put(l.get(i).uuid, f.applyAsLong(l.get(i)) > 0 ? i + 1 : 0);
            ranks.put(b, m);
        }
        List<StatsData.PlayerRec> byPlay = new ArrayList<>(all);
        byPlay.sort(Comparator.comparingLong((StatsData.PlayerRec r) -> d.playMsNow(r, now)).reversed());
        List<Object> pls = new ArrayList<>();
        for (int i = 0; i < Math.min(500, byPlay.size()); i++) {
            StatsData.PlayerRec r = byPlay.get(i);
            long pm = d.playMsNow(r, now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name(r.uuid, r.name, hideNames));
            if (ex != null && ex.players.containsKey(r.uuid)) {
                Map<String, Object> ep = ex.players.get(r.uuid);
                rp(m, ex, r.uuid);
                m.put("faction", ex.factionId(r.uuid));
                m.put("role", ex.role(r.uuid));
                m.put("job", Json.str(ep.get("job"), null));
                m.put("jobLevel", ep.get("jobLevel") instanceof Number n ? (Object) n.longValue() : null);
            }
            m.put("avatar", !hideNames);
            m.put("first", r.first);
            m.put("last", d.lastNow(r, now));
            m.put("playMs", pm);
            m.put("sessions", r.sessions);
            m.put("avgSessionMs", r.sessions > 0 ? pm / r.sessions : 0);
            m.put("maxSessionMs", r.maxSessionMs);
            m.put("streak", streak(r, today));
            long d30 = 0;
            for (long day : r.days) if (day > today - 30) d30++;
            m.put("days30", d30);
            m.put("weekMs", playOver(d, r, now, today, 7));
            m.put("hours", r.hoursMs.clone());
            m.put("deaths", r.deaths);
            m.put("kills", r.kills);
            m.put("mobKills", r.mobKills);
            m.put("broken", r.broken);
            m.put("placed", r.placed);
            m.put("distanceM", r.distanceCm / 100);
            m.put("online", d.active.containsKey(r.uuid));
            Map<String, Object> rk = new LinkedHashMap<>();
            for (String b : BOARDS) rk.put(b, ranks.get(b).get(r.uuid));
            m.put("ranks", rk);
            pls.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", 2);
        out.put("generatedAt", now);
        out.put("total", all.size());
        out.put("players", pls);
        return out;
    }

    /** Courbes : un point toutes les 5 minutes sur 14 jours. */
    public static Map<String, Object> history(StatsData d, long now) {
        Map<String, Object> hist = new LinkedHashMap<>();
        hist.put("version", 2);
        hist.put("generatedAt", now);
        hist.put("intervalSec", StatsData.SAMPLE_MINUTES * 60);
        hist.put("fields", List.of("ts", "players", "tps", "ramMb", "tpsMin", "chunks", "entities"));
        hist.put("points", d.history);
        return hist;
    }

    /** Jours d'affilée jusqu'à aujourd'hui (ou hier si le joueur n'est pas encore venu aujourd'hui). */
    static int streak(StatsData.PlayerRec r, long today) {
        int n = r.days.size();
        if (n == 0) return 0;
        long last = r.days.get(n - 1);
        if (last < today - 1) return 0;
        int s = 1;
        for (int i = n - 2; i >= 0; i--) {
            if (r.days.get(i) == r.days.get(i + 1) - 1) s++;
            else break;
        }
        return s;
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

    private static List<Object> top(List<StatsData.PlayerRec> all, boolean hide, ToLongFunction<StatsData.PlayerRec> f, AethoriaExport ex) {
        List<StatsData.PlayerRec> l = new ArrayList<>(all);
        l.sort(Comparator.comparingLong(f).reversed());
        List<Object> out = new ArrayList<>();
        for (StatsData.PlayerRec r : l) {
            long v = f.applyAsLong(r);
            if (v <= 0 || out.size() >= 15) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name(r.uuid, r.name, hide));
            rp(m, ex, r.uuid);
            m.put("value", v);
            out.add(m);
        }
        return out;
    }

    private static List<Object> topMap(Map<String, Integer> src, int max, String key) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(src.entrySet());
        l.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, l.size()); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(key, l.get(i).getKey());
            m.put("n", l.get(i).getValue());
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

    /**
     * Royaumes publics : factions, membres (noms RP), diplomatie, couronne et statistiques calculées ici.
     * Ne contient jamais la partie « admin » de l'export, ni d'UUID.
     */
    public static Map<String, Object> factions(StatsData d, AethoriaExport ex, long now, boolean hide) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", 2);
        out.put("generatedAt", now);
        out.put("exportAt", ex.generatedAt);
        Map<String, String> factionName = new LinkedHashMap<>();
        for (Map<String, Object> f : ex.factions) factionName.put(ExportIds.id(f), Json.str(f.get("name"), "?"));
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> f : ex.factions) {
            String id = ExportIds.id(f);
            Map<String, Object> o = new LinkedHashMap<>();
            for (String k : new String[] {"id", "name", "color", "tag", "description", "leaderRpName", "members", "founded", "cities",
                "skillsUnlocked", "skillsTotal", "era", "isKing", "suzerain", "vassals", "allies", "wars", "banner"}) {
                if (f.containsKey(k)) o.put(k, f.get(k));
            }
            // uuid -> membres (jamais d'UUID dans la sortie)
            List<String> uuids = new ArrayList<>();
            for (Object u : Json.list(f.get("memberUuids"))) uuids.add(String.valueOf(u));
            if (uuids.isEmpty()) {
                for (Map.Entry<String, String> e : ex.factionByUuid().entrySet()) if (id.equals(e.getValue())) uuids.add(e.getKey());
            }
            String leader = ExportIds.str(f.get("leader"));
            StatsData.PlayerRec lr = leader == null ? null : d.players.get(leader);
            Map<String, Object> lp = leader == null ? null : ex.players.get(leader);
            o.put("leaderName", lr != null ? name(lr.uuid, lr.name, hide) : (lp != null && !hide ? Json.str(lp.get("name"), null) : null));
            long playMs = 0;
            int active7 = 0, onlineNow = 0, new7 = 0;
            List<Object> ml = new ArrayList<>();
            for (String u : uuids) {
                StatsData.PlayerRec r = d.players.get(u);
                Map<String, Object> ep = ex.players.get(u);
                Map<String, Object> m = new LinkedHashMap<>();
                String pseudo = r != null ? name(u, r.name, hide) : (ep != null && !hide ? Json.str(ep.get("name"), "?") : pseudo(u));
                m.put("name", pseudo);
                m.put("rp", ex.rpName(u));
                m.put("role", ex.role(u));
                m.put("job", ep == null ? null : Json.str(ep.get("job"), null));
                boolean on = d.active.containsKey(u);
                m.put("online", on);
                if (r != null) {
                    long pm = d.playMsNow(r, now);
                    long last = d.lastNow(r, now);
                    playMs += pm;
                    if (last >= now - 7 * 86_400_000L) active7++;
                    m.put("last", last);
                    m.put("playMs", pm);
                }
                if (on) onlineNow++;
                String[] fo = d.factionOf.get(u);
                if (fo != null && Long.parseLong(fo[1]) > 0 && Long.parseLong(fo[1]) >= now - 7 * 86_400_000L) new7++;
                ml.add(m);
            }
            o.put("memberList", ml);
            if (!o.containsKey("members")) o.put("members", uuids.size());
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("playMs", playMs);
            st.put("active7d", active7);
            st.put("online", onlineNow);
            st.put("new7d", new7);
            o.put("stats", st);
            list.add(o);
        }
        out.put("factions", list);
        Map<String, Object> crown = new LinkedHashMap<>();
        for (String k : new String[] {"kingRpName", "factionId", "termEnd", "phase", "royalTreeUnlocked", "era"}) {
            if (ex.crown.containsKey(k)) crown.put(k, ex.crown.get(k));
        }
        String king = ExportIds.str(ex.crown.get("kingUuid"));
        if (king != null) {
            StatsData.PlayerRec kr = d.players.get(king);
            Map<String, Object> kp = ex.players.get(king);
            crown.put("kingName", kr != null ? name(kr.uuid, kr.name, hide) : (kp != null && !hide ? Json.str(kp.get("name"), null) : null));
            if (!crown.containsKey("kingRpName") || crown.get("kingRpName") == null) crown.put("kingRpName", ex.rpName(king));
        }
        out.put("crown", ex.crown.isEmpty() ? null : crown);
        return out;
    }

    /** Partie réservée aux admins : trésor, claims, cités avec coordonnées. */
    public static Map<String, Object> adminFactions(AethoriaExport ex, long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("available", ex != null);
        out.put("generatedAt", now);
        List<Object> l = new ArrayList<>();
        if (ex != null) {
            Map<String, String> names = new LinkedHashMap<>();
            for (Map<String, Object> f : ex.factions) names.put(ExportIds.id(f), Json.str(f.get("name"), "?"));
            for (Map<String, Object> a : ex.adminFactions) {
                Map<String, Object> m = new LinkedHashMap<>(a);
                m.put("factionName", names.getOrDefault(ExportIds.id(a), "?"));
                l.add(m);
            }
        }
        out.put("factions", l);
        return out;
    }

    /** Pour les tests : formate un instant. */
    static String iso(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }
}
