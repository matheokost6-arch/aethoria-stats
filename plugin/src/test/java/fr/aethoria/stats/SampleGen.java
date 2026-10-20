package fr.aethoria.stats;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Test sans serveur : simule 30 jours d'activité avec des joueurs fictifs, vérifie le sérialiseur
 * et la persistance, puis écrit un stats.json réaliste.
 * Usage : java -cp build/classes:build/test-classes fr.aethoria.stats.SampleGen sortie.json
 */
public final class SampleGen {
    private static final String[] NAMES = {
        "Aldric", "Berenger", "Cedric_le_Roux", "Dame_Isaure", "Eudes", "Frere_Tuck", "Gauvain", "Hildegarde",
        "Ivanhoe92", "Jehanne", "Kaelen", "Lancelin", "Mathilde_B", "Nimue", "Odilon", "Perceval_44", "Quentin_Fer",
        "Rosalinde", "Sire_Aubin", "Tristan_XL", "Ulrich", "Vivienne", "Wulfric", "Xanthe", "Yvain", "Zacharie",
        "BaronNoir", "LaForgeronne", "ArcherLynx", "Mage_Cendre", "Corbeau_Gris", "DameLoup", "FerDeLance", "Griffon_07",
        "Hugues_Capet", "Isengrin", "JeanDuBois", "Kriss_Archer", "Louve_Blanche", "Mordred_FR", "Nonna", "Orlando",
        "Pixel_Knight", "QueenMab", "Roland_le_Preux", "Saphir", "Templier_13", "Urbain", "Valdemar", "Wyvern_Bleue",
        "Ysolde", "Zephyr_77", "Alienor", "Bertrade", "Clotaire", "Dagobert_II", "Enguerrand", "Foulques", "Godefroy",
        "Hersende", "Ingeburge", "Jourdain", "Lothaire", "Melusine", "Norbert", "Ogier", "Pepin_Court", "Raoul"
    };

    private static final String[] COMMANDS = {"spawn", "home", "sethome", "tpa", "msg", "warp", "kit", "shop", "balance", "pay", "claim", "trade", "help", "rules", "map"};
    private static final String[] CAUSES = {"Combat (monstre ou joueur)", "Chute", "Feu et lave", "Explosion", "Flèche ou projectile", "Noyade", "Faim", "Étouffement", "Le vide", "Magie et poisons"};
    private static final String[] KILLERS = {"ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "ENDERMAN", "PLAYER", "WITCH", "DROWNED", "PILLAGER", "WOLF"};
    private static final String[] MOBS = {"ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "COW", "PIG", "SHEEP", "CHICKEN", "ENDERMAN", "SLIME", "BAT", "ZOMBIFIED_PIGLIN", "WITCH", "DROWNED", "PHANTOM"};
    private static final String[] ADV = {"story/mine_stone", "story/upgrade_tools", "story/smelt_iron", "story/obtain_armor", "story/enter_the_nether", "story/follow_ender_eye", "nether/find_fortress", "nether/obtain_crying_obsidian", "end/kill_dragon", "adventure/kill_a_mob", "adventure/trade", "adventure/sleep_in_bed", "husbandry/breed_an_animal", "husbandry/plant_seed", "husbandry/tame_an_animal"};

    private static final String[] FACTIONS = {"Maison de Valmont", "Ordre d'Ombrelune", "Clan du Cormoran", "Hautefort", "Brumevert", "Les Sans-Terre"};
    private static final String[] COLORS = {"#b3402a", "#5a4a8a", "#1f6b2c", "#8a5a00", "#2a7a8a", "#6a6a6a"};
    private static final String[] TAGS = {"VAL", "OMB", "COR", "HAU", "BRU", "SAN"};
    private static final String[] DESCR = {"Vieille maison de chevaliers, gardienne de la grande route du sud.", "Moines-soldats qui veillent sur les étoiles et sur les secrets.", "Marins et pêcheurs de la côte, redoutés pour leurs navires.", "Bâtisseurs de la forteresse du nord, fiers de leurs remparts.", "Gens des forêts, éleveurs et herboristes, amis des loups.", "Exilés et aventuriers sans bannière, unis par la débrouille."};
    private static final String[] FIRST = {"Aldric", "Berenger", "Cedric", "Isaure", "Eudes", "Gauvain", "Hildegarde", "Jehanne", "Kaelen", "Lancelin", "Mathilde", "Nimue", "Odilon", "Perceval", "Rosalinde", "Tristan", "Ulrich", "Vivienne", "Wulfric", "Yvain"};
    private static final String[] PLACE = {"Valmont", "Ombrelune", "Cormoran", "Hautefort", "Brumevert", "la Combe", "Roc-Haut", "Fontclaire", "Pierrefeu", "Belair"};
    private static final String[] JOBS = {"Mineur", "Forgeron", "Fermier", "Marchand", "Bûcheron", "Pêcheur", "Alchimiste"};

    /** Fabrique un export fictif conforme au schéma du mod, avec les mêmes joueurs que la simulation. */
    static String fakeExport(StatsData d, long now, Random rnd) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("generatedAt", now - 20_000);
        List<String> uuids = new ArrayList<>(d.players.keySet());
        Map<String, Object> players = new LinkedHashMap<>();
        List<List<String>> members = new ArrayList<>();
        for (int f = 0; f < FACTIONS.length; f++) members.add(new ArrayList<>());
        for (int i = 0; i < uuids.size(); i++) {
            String u = uuids.get(i);
            int f = i < 60 ? i % FACTIONS.length : -1; // les 8 derniers n'ont pas de faction
            Map<String, Object> p = new LinkedHashMap<>();
            String pseudo = d.players.get(u).name;
            String fn = FIRST[i % FIRST.length];
            String ln = "de " + PLACE[(i * 7 + 3) % PLACE.length];
            p.put("name", pseudo);
            p.put("firstName", fn);
            p.put("lastName", ln);
            p.put("rpName", i % 11 == 10 ? null : fn + " " + ln);
            p.put("faction", f < 0 ? null : (Object) (long) (f + 1));
            int rank = f < 0 ? 0 : members.get(f).size();
            p.put("role", f < 0 ? null : rank == 0 ? "chef" : rank < 3 ? "officier" : "membre");
            boolean job = rnd.nextDouble() < 0.7;
            p.put("job", job ? JOBS[rnd.nextInt(JOBS.length)] : null);
            p.put("jobLevel", job ? (Object) (long) (1 + rnd.nextInt(40)) : null);
            players.put(u, p);
            if (f >= 0) members.get(f).add(u);
        }
        root.put("players", players);
        List<Object> factions = new ArrayList<>();
        List<Object> adminF = new ArrayList<>();
        for (int f = 0; f < FACTIONS.length; f++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", (long) (f + 1));
            m.put("name", FACTIONS[f]);
            m.put("color", COLORS[f]);
            m.put("tag", TAGS[f]);
            m.put("description", DESCR[f]);
            String leader = members.get(f).get(0);
            m.put("leader", leader);
            m.put("leaderRpName", ((Map<?, ?>) players.get(leader)).get("rpName"));
            m.put("members", members.get(f).size());
            m.put("memberUuids", members.get(f));
            m.put("founded", now - (long) (8 + rnd.nextInt(25)) * 86_400_000L);
            int cities = 1 + rnd.nextInt(5);
            m.put("cities", cities);
            m.put("skillsUnlocked", 5 + rnd.nextInt(25));
            m.put("skillsTotal", 40);
            m.put("era", f == 0 ? "Moyen Âge" : f < 3 ? "Âge du fer" : "Antiquité");
            m.put("isKing", f == 0);
            m.put("suzerain", f == 3 || f == 4 ? (Object) 1L : null);
            m.put("vassals", f == 0 ? List.of(4L, 5L) : List.of());
            m.put("allies", f == 1 ? List.of(3L) : f == 2 ? List.of(2L) : List.of());
            m.put("wars", f == 5 ? List.of(1L) : f == 0 ? List.of(6L) : List.of());
            m.put("banner", null);
            factions.add(m);
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", (long) (f + 1));
            a.put("treasury", 400 + rnd.nextInt(9000));
            a.put("claims", 12 + rnd.nextInt(150));
            List<Object> cl = new ArrayList<>();
            String[] cn = {"Haute-Tour", "Port-Brume", "Val-Clair", "Rocamont", "Aubépine"};
            for (int c = 0; c < cities; c++) {
                Map<String, Object> city = new LinkedHashMap<>();
                city.put("name", cn[c % cn.length] + (c >= cn.length ? " II" : ""));
                city.put("level", 1 + rnd.nextInt(5));
                city.put("x", rnd.nextInt(4000) - 2000);
                city.put("z", rnd.nextInt(4000) - 2000);
                city.put("dimension", "minecraft:overworld");
                cl.add(city);
            }
            a.put("cities", cl);
            adminF.add(a);
        }
        root.put("factions", factions);
        Map<String, Object> crown = new LinkedHashMap<>();
        String king = members.get(0).get(0);
        crown.put("kingUuid", king);
        crown.put("kingRpName", ((Map<?, ?>) players.get(king)).get("rpName"));
        crown.put("factionId", 1L);
        crown.put("termEnd", now + 12L * 86_400_000L);
        crown.put("phase", "règne");
        crown.put("royalTreeUnlocked", true);
        crown.put("era", "Moyen Âge");
        root.put("crown", crown);
        Map<String, Object> admin = new LinkedHashMap<>();
        admin.put("factions", adminF);
        root.put("admin", admin);
        return Json.write(root);
    }

    private static final class Sim {
        String uuid, name;
        long joinAt;
        long leaveAt;
        double propensity;
        int shift;
        double sessionSteps;
        boolean online;
    }

    public static void main(String[] args) throws Exception {
        ZoneId zone = ZoneId.of("Europe/Paris");
        Random rnd = new Random(42);
        long step = StatsData.SAMPLE_MINUTES * 60_000L;
        long now = System.currentTimeMillis() / step * step;
        long start = now - 30L * 86_400_000L;
        StatsData d = new StatsData(zone);

        List<Sim> sims = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            Sim s = new Sim();
            s.name = NAMES[i];
            s.uuid = new java.util.UUID(0xA37E0000L + i, 0x1000L * (i + 7)).toString();
            // plus de nouveaux ces derniers jours (le serveur grandit)
            double u = rnd.nextDouble();
            s.joinAt = start + (long) ((1 - Math.pow(u, 1.8)) * 29.8 * 86_400_000L);
            double churn = rnd.nextDouble();
            s.propensity = churn < 0.35 ? 0.03 + rnd.nextDouble() * 0.08 : 0.2 + rnd.nextDouble() * 0.8;
            s.shift = rnd.nextInt(5) - 2;
            s.sessionSteps = 6 + rnd.nextDouble() * 30;
            sims.add(s);
        }
        // les 4 premiers joueurs sont d'anciens habitués
        for (int i = 0; i < 4; i++) {
            sims.get(i).joinAt = start - 10 * 86_400_000L;
            sims.get(i).propensity = 0.9;
        }

        long lastRecordLog = 0;
        for (long t = start; t <= now; t += step) {
            ZonedDateTime z = java.time.Instant.ofEpochMilli(t).atZone(zone);
            int hour = z.getHour();
            int dow = z.getDayOfWeek().getValue();
            double weekend = dow >= 6 ? 1.5 : 1.0;
            for (Sim s : sims) {
                if (t < s.joinAt) continue;
                int h = (hour + s.shift + 24) % 24;
                double daily = 0.08 + 0.92 * Math.exp(-Math.pow((h - 21) / 3.2, 2)) + 0.25 * Math.exp(-Math.pow((h - 15) / 3.0, 2));
                double daysSince = (t - s.joinAt) / 86_400_000.0;
                double fade = s.propensity < 0.15 ? Math.exp(-daysSince / 2.5) : 1.0;
                if (!s.online) {
                    boolean first = t - s.joinAt < step;
                    if (first || rnd.nextDouble() < 0.012 * s.propensity * daily * weekend * fade * 6) {
                        long first0 = first ? t : s.joinAt;
                        boolean fresh = d.startSession(s.uuid, s.name, first0, t);
                        if (fresh) {
                            s.online = true;
                            boolean brandNew = d.players.get(s.uuid).sessions == 1 && t - s.joinAt < step;
                            d.log(t, brandNew ? "first" : "join",
                                    brandNew ? s.name + " découvre le royaume pour la première fois" : s.name + " s'est connecté");
                            if (d.noteOnline(d.active.size(), t)) d.log(t, "record", "Nouveau record : " + d.recordCount + " joueurs en ligne");
                        }
                    }
                } else {
                    StatsData.PlayerRec r = d.players.get(s.uuid);
                    if (rnd.nextDouble() < 1.0 / s.sessionSteps) {
                        d.endSession(s.uuid, t);
                        s.online = false;
                        d.log(t, "quit", s.name + " s'est déconnecté");
                    } else {
                        int mk = (int) (rnd.nextDouble() * 6);
                        r.mobKills += mk;
                        for (int q = 0; q < mk; q++) StatsData.bump(d.mobsKilled, MOBS[(int) (Math.pow(rnd.nextDouble(), 1.7) * MOBS.length)], 80);
                        if (rnd.nextDouble() < 0.35) d.chatByDay.merge(d.dayOf(t), 1 + rnd.nextInt(3), Integer::sum);
                        if (rnd.nextDouble() < 0.12) {
                            StatsData.bump(d.commands, COMMANDS[(int) (Math.pow(rnd.nextDouble(), 1.6) * COMMANDS.length)], 150);
                            d.commandsByDay.merge(d.dayOf(t), 1, Integer::sum);
                        }
                        if (rnd.nextDouble() < 0.004) d.addAdvancement(t, s.name, ADV[rnd.nextInt(ADV.length)]);
                        if (rnd.nextDouble() < 0.02) {
                            r.deaths++;
                            StatsData.bump(d.deathCauses, CAUSES[(int) (Math.pow(rnd.nextDouble(), 1.8) * CAUSES.length)], 40);
                            StatsData.bump(d.killers, KILLERS[(int) (Math.pow(rnd.nextDouble(), 1.6) * KILLERS.length)], 60);
                            String[] causes = {" a été tué par un Zombie", " est tombé de trop haut", " a été abattu par un Squelette", " a brûlé dans la lave", " a été explosé par un Creeper"};
                            d.log(t, "death", s.name + causes[rnd.nextInt(causes.length)]);
                        }
                        if (rnd.nextDouble() < 0.004) r.kills++;
                        r.broken += (long) (rnd.nextDouble() * 140);
                        r.placed += (long) (rnd.nextDouble() * 90);
                        r.distanceCm += (long) (rnd.nextDouble() * 60_000);
                    }
                }
            }
            double load = d.active.size();
            double tps = Math.min(20.0, 20.0 - Math.max(0, load - 7) * 0.08 - (rnd.nextDouble() < 0.01 ? 2.5 : 0) - rnd.nextDouble() * 0.15);
            double ram = 2800 + 1500 * Math.abs(Math.sin(t / 5_400_000.0)) + load * 55 + rnd.nextDouble() * 80;
            if (t % (30 * 60_000L) == 0) d.addClearlag(t, 90 + rnd.nextInt(260));
            double minTps = Math.max(2, tps - rnd.nextDouble() * (rnd.nextDouble() < 0.03 ? 9 : 1.5));
            if (d.sample(t, d.active.size(), tps, ram, minTps, 380 + (int) load * 9 + rnd.nextInt(20), 1100 + (int) load * 40 + rnd.nextInt(120)) && t - lastRecordLog > 0) {
                d.log(t, "record", "Nouveau record : " + d.recordCount + " joueurs en ligne");
            }
        }

        d.bootMs = 41_300;
        String exportJson = fakeExport(d, now, rnd);
        AethoriaExport ex = AethoriaExport.parse(exportJson);
        // deux membres arrivent après la première lecture : ils comptent comme « nouveaux membres »
        Map<String, String> baseline = ex.factionByUuid();
        List<String> later = new ArrayList<>(baseline.keySet()).subList(3, 5);
        Map<String, String> early = new LinkedHashMap<>(baseline);
        later.forEach(early::remove);
        d.observeFactions(early, now - 8 * 86_400_000L);
        d.observeFactions(baseline, now - 2 * 86_400_000L);
        // persistance : aller-retour sans perte
        String persisted = Json.write(d.toMap());
        StatsData back = StatsData.fromMap(Json.map(Json.parse(persisted)), zone);
        check(Json.write(back.toMap()).equals(persisted), "aller-retour data.json");
        check(back.players.size() == d.players.size(), "même nombre de joueurs");

        Snapshot.Live live = new Snapshot.Live();
        live.serverVersion = "git-Mohist-1.20.1-fictif (MC: 1.20.1)";
        live.bukkitVersion = "1.20.1-R0.1-SNAPSHOT";
        live.pluginCount = 5;
        live.startedAt = now - (3L * 86_400_000L + 7 * 3_600_000L + 21 * 60_000L);
        live.cores = 4;
        live.ramUsedMb = 4120;
        live.ramMaxMb = 8192;
        live.maxPlayers = 40;
        live.tps1 = 19.97;
        live.tps5 = 19.91;
        live.tps15 = 19.84;
        live.mspt = 49.6;
        for (String u : d.active.keySet()) {
            Snapshot.OnlinePlayer o = new Snapshot.OnlinePlayer();
            o.uuid = u;
            o.name = d.players.get(u).name;
            o.ping = 18 + rnd.nextInt(90);
            live.online.add(o);
        }
        String[][] ws = {{"world", "NORMAL", "412", "1480", "611", "93", "5300", "clair"},
                         {"world_nether", "NETHER", "96", "340", "212", "11", "18000", "clair"},
                         {"world_the_end", "THE_END", "41", "58", "9", "0", "6000", "clair"}};
        for (String[] w : ws) {
            Snapshot.WorldInfo wi = new Snapshot.WorldInfo();
            wi.name = w[0];
            wi.env = w[1];
            wi.chunks = Integer.parseInt(w[2]);
            wi.entities = Integer.parseInt(w[3]);
            wi.living = Integer.parseInt(w[4]);
            wi.items = Integer.parseInt(w[5]);
            wi.time = Long.parseLong(w[6]);
            wi.weather = w[7];
            live.worlds.add(wi);
        }

        Map<String, Object> snap = Snapshot.build(d, live, now, false, ex);
        String json = Json.write(snap);
        Object parsed = Json.parse(json);
        check(parsed instanceof Map, "stats.json est un objet JSON");
        check(Json.write(parsed).equals(json), "stats.json relu = écrit");
        for (String k : new String[] {"server", "tps", "players", "newPlayers", "retention", "activity",
                "newByDay", "sessionsByDay", "leaderboards", "records", "game", "worlds", "log"}) {
            check(Json.map(parsed).containsKey(k), "clé " + k);
        }
        String low = json.toLowerCase();
        check(!low.contains("\"ip\"") && !low.matches("(?s).*\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b.*"), "aucune IP");
        check(d.history.size() == StatsData.HISTORY_MAX, "historique plafonné à 14 jours (" + d.history.size() + ")");
        // pseudonymisation
        String hidden = Json.write(Snapshot.build(d, live, now, true));
        check(!hidden.contains("Aldric") && hidden.contains("Joueur-"), "hide-player-names");

        Map<String, Object> pl = Json.map(Json.map(parsed).get("players"));
        Map<String, Object> ret = Json.map(Json.map(parsed).get("retention"));
        System.out.println("Joueurs connus : " + d.players.size() + ", en ligne : " + pl.get("online") + ", record : " + d.recordCount);
        System.out.println("Rétention : " + ret);
        System.out.println("Taille stats.json : " + json.getBytes(StandardCharsets.UTF_8).length + " octets");
        String playersJson = Json.write(Snapshot.players(d, now, false, ex));
        String factionsJson = Json.write(Snapshot.factions(d, ex, now, false));
        String adminJson = Json.write(Snapshot.adminFactions(ex, now));
        Map<String, Object> fm = Json.map(Json.parse(factionsJson));
        check(Json.list(fm.get("factions")).size() == 6 && fm.get("crown") != null, "royaumes : 6 factions et la couronne");
        check(!factionsJson.contains("treasury") && !factionsJson.contains("claims") && !factionsJson.contains("\"x\":") && !factionsJson.contains("memberUuids") && !factionsJson.contains("kingUuid"), "royaumes publics : pas de partie admin, pas d'UUID");
        check(!factionsJson.matches("(?s).*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*") && !playersJson.matches("(?s).*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*"), "aucun UUID dans les JSON publics");
        check(adminJson.contains("treasury") && adminJson.contains("Haute-Tour"), "partie admin : trésor et cités");
        long new7 = 0;
        for (Object o : Json.list(fm.get("factions"))) new7 += Json.num(Json.map(Json.map(o).get("stats")).get("new7d"), 0);
        check(new7 == 2, "nouveaux membres sur 7 jours : " + new7);
        check(playersJson.contains("\"rp\":\"Aldric de Valmont\"") || playersJson.contains("\"rp\":"), "noms RP dans players.json");
        check(json.contains("\"rp\":") && json.contains("\"export\":{\"available\":true"), "noms RP et export dans stats.json");
        String noExport = Json.write(Snapshot.build(d, live, now, false));
        check(!noExport.contains("\"rp\":") && noExport.contains("\"available\":false"), "sans export : aucun champ RP, tout reste valide");
        try {
            AethoriaExport.parse("{pas du json");
            check(false, "export cassé refusé");
        } catch (RuntimeException e) {
            check(true, "export cassé refusé proprement");
        }
        check(AethoriaExport.parse("{\"version\":1}").factions.isEmpty(), "export vide accepté");
        String historyJson = Json.write(Snapshot.history(d, now));
        check(Json.map(Json.parse(playersJson)).containsKey("players") && Json.list(Json.map(Json.parse(playersJson)).get("players")).size() == d.players.size(), "players.json : une fiche par joueur");
        check(Json.list(Json.map(Json.parse(historyJson)).get("points")).size() == StatsData.HISTORY_MAX, "history.json : 14 jours de points");
        check(!playersJson.toLowerCase().contains("\"ip\"") && !historyJson.contains("."  + "0.0.1"), "players.json et history.json sans IP");
        // vérifications des nouveaux champs
        Map<String, Object> game = Json.map(Json.map(parsed).get("game"));
        check(!Json.list(game.get("deathCauses")).isEmpty() && !Json.list(game.get("mobsKilled")).isEmpty() && !Json.list(game.get("commands")).isEmpty(), "morts, monstres et commandes comptés");
        check(Json.num(Json.map(Json.map(Json.map(parsed).get("records")).get("longestSession")).get("ms"), 0) > 0, "plus longue session");
        check(Json.map(Json.map(parsed).get("server")).containsKey("lagSpikes") && Json.map(Json.map(Json.map(parsed).get("server")).get("tps24h")).get("min") != null, "TPS du jour et pics de lag");
        if (args.length > 0) {
            Path out = Path.of(args[0]);
            Files.writeString(out, json, StandardCharsets.UTF_8);
            Files.writeString(out.resolveSibling("players.sample.json"), playersJson, StandardCharsets.UTF_8);
            Files.writeString(out.resolveSibling("history.sample.json"), historyJson, StandardCharsets.UTF_8);
            Files.writeString(out.resolveSibling("factions.sample.json"), factionsJson, StandardCharsets.UTF_8);
            Files.writeString(out.resolveSibling("factions-admin.sample.json"), adminJson, StandardCharsets.UTF_8);
            Files.writeString(out.resolveSibling("aethoria-export.sample.json"), exportJson, StandardCharsets.UTF_8);
            System.out.println("Écrit : " + args[0] + " (+ players et history) ; players " + playersJson.length() + " o, history " + historyJson.length() + " o");
        }
        System.out.println("TOUS LES TESTS OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("ÉCHEC : " + what);
        System.out.println("ok  " + what);
    }
}
