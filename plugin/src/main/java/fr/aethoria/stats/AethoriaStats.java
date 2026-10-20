package fr.aethoria.stats;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Collecte légère des statistiques du serveur et envoi vers un Gist GitHub. */
public final class AethoriaStats extends JavaPlugin implements Listener, CommandExecutor {
    private static final long SAMPLE_TICKS = StatsData.SAMPLE_MINUTES * 60L * 20L;

    private StatsData data;
    private final TickMonitor ticks = new TickMonitor();
    private final Uploader uploader = new Uploader(m -> getLogger().warning(m), m -> getLogger().info(m));
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AethoriaStats-io");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, StatsData.PlayerRec> online = new HashMap<>();
    private List<Material> blockMaterials = List.of();
    private List<Snapshot.WorldInfo> worldCache = List.of();
    private boolean authMe;
    private boolean hideNames;
    private long startedAt;
    private int sendIntervalSec = 120;
    private int skipCycles;
    private Path dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.json").toPath();
        startedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
        readConfig();
        loadData();

        List<Material> blocks = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.name().startsWith("LEGACY_") && m.isBlock()) blocks.add(m);
        }
        blockMaterials = blocks;

        getServer().getPluginManager().registerEvents(this, this);
        authMe = hookAuthMe();
        var cmd = getCommand("astats");
        if (cmd != null) cmd.setExecutor(this);

        Bukkit.getScheduler().runTaskTimer(this, ticks, 1L, 1L);
        // les joueurs déjà connectés (rechargement du plugin)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!authMe || isAuthenticated(p)) startSession(p);
        }
        if (data.players.isEmpty()) seedKnownPlayers();

        Bukkit.getScheduler().runTaskLater(this, this::refreshWorlds, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::samplePeriodic, 20L * 60, SAMPLE_TICKS);
        long period = Math.max(30, sendIntervalSec) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::sendCycle, 20L * 20, period);

        if (!uploader.isConfigured()) {
            getLogger().warning("gist-id ou github-token vide dans config.yml : collecte active, envoi désactivé.");
        }
        getLogger().info("AethoriaStats actif" + (authMe ? " (sessions comptées après connexion AuthMe)." : "."));
    }

    @Override
    public void onDisable() {
        long now = System.currentTimeMillis();
        for (String u : new ArrayList<>(data.active.keySet())) data.endSession(u, now);
        online.clear();
        try {
            writeData(Json.write(data.toMap()));
        } catch (Exception e) {
            getLogger().warning("Sauvegarde finale impossible : " + e.getMessage());
        }
        io.shutdown();
        uploader.shutdown();
    }

    private void readConfig() {
        reloadConfig();
        var c = getConfig();
        hideNames = c.getBoolean("hide-player-names", false);
        sendIntervalSec = c.getInt("send-interval-seconds", 120);
        uploader.configure(c.getString("gist-id", ""), c.getString("github-token", ""), c.getString("gist-file", "stats.json"));
    }

    private ZoneId zone() {
        String tz = getConfig().getString("timezone", "");
        try {
            if (tz != null && !tz.isBlank()) return ZoneId.of(tz.trim());
        } catch (Exception e) {
            getLogger().warning("Fuseau horaire inconnu : " + tz);
        }
        return ZoneId.systemDefault();
    }

    // ---- données ----

    private void loadData() {
        data = new StatsData(zone());
        if (!Files.exists(dataFile)) return;
        try {
            String text = Files.readString(dataFile, StandardCharsets.UTF_8);
            data = StatsData.fromMap(Json.map(Json.parse(text)), zone());
        } catch (Exception e) {
            getLogger().warning("data.json illisible (" + e.getMessage() + ") : copie en data.json.bak, départ à zéro.");
            try {
                Files.move(dataFile, dataFile.resolveSibling("data.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                // tant pis
            }
            data = new StatsData(zone());
        }
    }

    private void saveAsync() {
        final String text = Json.write(data.toMap());
        io.execute(() -> {
            try {
                writeData(text);
            } catch (Exception e) {
                getLogger().warning("Sauvegarde impossible : " + e.getMessage());
            }
        });
    }

    private void writeData(String text) throws Exception {
        Files.createDirectories(dataFile.getParent());
        Path tmp = dataFile.resolveSibling("data.json.tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Premier démarrage : reprend les joueurs déjà connus du serveur (dates de première/dernière visite). */
    private void seedKnownPlayers() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<String[]> found = new ArrayList<>();
            try {
                for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                    if (op.getName() == null || !op.hasPlayedBefore()) continue;
                    found.add(new String[] {op.getUniqueId().toString(), op.getName(),
                            String.valueOf(op.getFirstPlayed()), String.valueOf(op.getLastPlayed())});
                }
            } catch (Exception e) {
                getLogger().warning("Lecture des anciens joueurs impossible : " + e.getMessage());
            }
            Bukkit.getScheduler().runTask(this, () -> {
                long now = System.currentTimeMillis();
                int n = 0;
                for (String[] f : found) {
                    if (data.players.containsKey(f[0])) continue;
                    long first = Long.parseLong(f[2]);
                    long last = Long.parseLong(f[3]);
                    StatsData.PlayerRec r = data.rec(f[0], f[1], first, now);
                    r.last = last > 0 ? last : r.first;
                    r.days.add(data.dayOf(r.last));
                    n++;
                }
                if (n > 0) getLogger().info(n + " joueur(s) déjà connus repris dans les stats.");
            });
        });
    }

    // ---- sessions ----

    private boolean hookAuthMe() {
        if (Bukkit.getPluginManager().getPlugin("AuthMe") == null) return false;
        try {
            Class<?> cls = Class.forName("fr.xephi.authme.events.LoginEvent", true,
                    Bukkit.getPluginManager().getPlugin("AuthMe").getClass().getClassLoader());
            var getPlayer = cls.getMethod("getPlayer");
            Bukkit.getPluginManager().registerEvent(cls.asSubclass(Event.class), this, EventPriority.MONITOR,
                    (listener, event) -> {
                        try {
                            if (cls.isInstance(event)) startSession((Player) getPlayer.invoke(event));
                        } catch (Exception e) {
                            getLogger().warning("LoginEvent AuthMe : " + e.getMessage());
                        }
                    }, this);
            return true;
        } catch (Throwable t) {
            getLogger().warning("AuthMe présent mais LoginEvent inaccessible (" + t + ") : sessions comptées dès l'arrivée.");
            return false;
        }
    }

    private boolean isAuthenticated(Player p) {
        try {
            Class<?> api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isAuthenticated", Player.class).invoke(inst, p);
        } catch (Throwable t) {
            return true;
        }
    }

    private void startSession(Player p) {
        long now = System.currentTimeMillis();
        String uuid = p.getUniqueId().toString();
        boolean known = data.players.containsKey(uuid);
        long first = p.getFirstPlayed() > 0 ? p.getFirstPlayed() : now;
        if (!data.startSession(uuid, p.getName(), first, now)) return;
        StatsData.PlayerRec r = data.players.get(uuid);
        online.put(p.getUniqueId(), r);
        refreshStats(p, r, true);
        boolean brandNew = !known && now - first < 10 * 60_000L;
        data.log(now, brandNew ? "first" : "join", brandNew ? p.getName() + " découvre le royaume pour la première fois" : p.getName() + " s'est connecté");
        if (data.noteOnline(data.active.size(), now)) {
            data.log(now, "record", "Nouveau record : " + data.recordCount + " joueurs en ligne");
        }
    }

    private void endSession(Player p) {
        StatsData.PlayerRec r = online.remove(p.getUniqueId());
        if (r == null) return;
        long now = System.currentTimeMillis();
        refreshStats(p, r, false);
        long before = r.playMs;
        data.endSession(r.uuid, now);
        long mins = Math.max(0, (r.playMs - before) / 60_000L);
        data.log(now, "quit", p.getName() + " s'est déconnecté (" + mins + " min depuis le dernier point)");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!authMe) startSession(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        endSession(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        String msg = e.getDeathMessage();
        if (msg == null || msg.isBlank()) msg = e.getEntity().getName() + " est mort";
        StatsData.PlayerRec r = online.get(e.getEntity().getUniqueId());
        if (r != null) r.deaths++;
        data.log(System.currentTimeMillis(), "death", msg);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        StatsData.PlayerRec r = online.get(e.getPlayer().getUniqueId());
        if (r != null) r.broken++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        StatsData.PlayerRec r = online.get(e.getPlayer().getUniqueId());
        if (r != null) r.placed++;
    }

    // ---- statistiques Bukkit ----

    private static final String[] DISTANCE_STATS = {
        "WALK_ONE_CM", "SPRINT_ONE_CM", "CROUCH_ONE_CM", "SWIM_ONE_CM", "FLY_ONE_CM", "AVIATE_ONE_CM",
        "BOAT_ONE_CM", "MINECART_ONE_CM", "HORSE_ONE_CM", "PIG_ONE_CM", "STRIDER_ONE_CM",
        "WALK_ON_WATER_ONE_CM", "WALK_UNDER_WATER_ONE_CM", "CLIMB_ONE_CM"
    };

    private void refreshStats(Player p, StatsData.PlayerRec r, boolean withBlocks) {
        try {
            r.deaths = Math.max(r.deaths, p.getStatistic(Statistic.DEATHS));
            r.kills = p.getStatistic(Statistic.PLAYER_KILLS);
            r.mobKills = p.getStatistic(Statistic.MOB_KILLS);
            long dist = 0;
            for (String s : DISTANCE_STATS) {
                try {
                    dist += p.getStatistic(Statistic.valueOf(s));
                } catch (IllegalArgumentException ignored) {
                    // statistique absente de cette version
                }
            }
            r.distanceCm = dist;
            if (withBlocks) {
                long broken = 0, placed = 0;
                for (Material m : blockMaterials) {
                    try {
                        broken += p.getStatistic(Statistic.MINE_BLOCK, m);
                        placed += p.getStatistic(Statistic.USE_ITEM, m);
                    } catch (IllegalArgumentException ignored) {
                        // matériau sans statistique
                    }
                }
                r.broken = Math.max(r.broken, broken);
                r.placed = Math.max(r.placed, placed);
            }
        } catch (Exception e) {
            getLogger().fine("Statistiques de " + p.getName() + " : " + e.getMessage());
        }
    }

    // ---- tâches périodiques (thread principal) ----

    private void samplePeriodic() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            StatsData.PlayerRec r = online.get(p.getUniqueId());
            if (r != null) refreshStats(p, r, false);
        }
        refreshWorlds();
        Runtime rt = Runtime.getRuntime();
        double ramMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
        if (data.sample(now, data.active.size(), ticks.tps(60), ramMb)) {
            data.log(now, "record", "Nouveau record : " + data.recordCount + " joueurs en ligne");
        }
        saveAsync();
    }

    private void refreshWorlds() {
        List<Snapshot.WorldInfo> list = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            Snapshot.WorldInfo wi = new Snapshot.WorldInfo();
            wi.name = w.getName();
            wi.env = w.getEnvironment().name();
            wi.chunks = w.getLoadedChunks().length;
            int ent = 0, living = 0, items = 0;
            for (Entity en : w.getEntities()) {
                ent++;
                if (en instanceof Item) items++;
                else if (en instanceof LivingEntity && !(en instanceof Player)) living++;
            }
            wi.entities = ent;
            wi.living = living;
            wi.items = items;
            wi.time = w.getTime();
            wi.weather = w.isThundering() ? "orage" : (w.hasStorm() ? "pluie" : "clair");
            list.add(wi);
        }
        worldCache = list;
    }

    private Snapshot.Live live() {
        Snapshot.Live l = new Snapshot.Live();
        l.serverVersion = Bukkit.getVersion();
        l.bukkitVersion = Bukkit.getBukkitVersion();
        l.pluginCount = Bukkit.getPluginManager().getPlugins().length;
        l.startedAt = startedAt;
        l.cores = Runtime.getRuntime().availableProcessors();
        Runtime rt = Runtime.getRuntime();
        l.ramUsedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        l.ramMaxMb = rt.maxMemory() / 1048576L;
        l.maxPlayers = Bukkit.getMaxPlayers();
        l.tps1 = ticks.tps(60);
        l.tps5 = ticks.tps(300);
        l.tps15 = ticks.tps(900);
        l.mspt = ticks.mspt();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!online.containsKey(p.getUniqueId())) continue;
            Snapshot.OnlinePlayer o = new Snapshot.OnlinePlayer();
            o.uuid = p.getUniqueId().toString();
            o.name = p.getName();
            try {
                o.ping = p.getPing();
            } catch (Throwable ignored) {
                o.ping = -1;
            }
            l.online.add(o);
        }
        l.worlds.addAll(worldCache);
        return l;
    }

    private String buildJson() {
        long now = System.currentTimeMillis();
        data.checkpointAll(now);
        return Json.write(Snapshot.build(data, live(), now, hideNames));
    }

    private void sendCycle() {
        if (!uploader.isConfigured()) return;
        if (skipCycles > 0) {
            skipCycles--;
            return;
        }
        uploader.send(buildJson());
        skipCycles = uploader.backoff();
    }

    // ---- commande ----

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (!s.hasPermission("aethoria.stats.admin")) {
            s.sendMessage("Tu n'as pas la permission.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            readConfig();
            s.sendMessage("Configuration relue.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("send")) {
            if (!uploader.isConfigured()) {
                s.sendMessage("Envoi impossible : gist-id ou github-token vide dans config.yml.");
            } else if (uploader.isBusy()) {
                s.sendMessage("Un envoi est déjà en cours.");
            } else {
                refreshWorlds();
                skipCycles = 0;
                uploader.send(buildJson());
                s.sendMessage("Envoi lancé. Tape /astats pour voir le résultat.");
            }
            return true;
        }
        long now = System.currentTimeMillis();
        s.sendMessage("AethoriaStats : " + data.active.size() + " joueur(s) comptés, " + data.players.size() + " connus.");
        if (!uploader.isConfigured()) {
            s.sendMessage("Envoi désactivé (gist-id ou github-token vide).");
        } else if (uploader.lastOk > 0) {
            s.sendMessage("Dernier envoi réussi : il y a " + (now - uploader.lastOk) / 1000 + " s (" + uploader.lastDurationMs + " ms).");
        } else {
            s.sendMessage("Aucun envoi réussi pour l'instant.");
        }
        if (!uploader.lastError.isEmpty()) {
            s.sendMessage("Dernière erreur : " + uploader.lastError + " (échecs de suite : " + uploader.failures + ")");
        }
        s.sendMessage("Commandes : /astats send (forcer un envoi), /astats reload (relire config.yml).");
        return true;
    }
}
