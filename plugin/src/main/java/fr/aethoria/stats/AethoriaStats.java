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
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
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
    private static final long WARMUP_MS = 120_000L;
    private volatile long readyAt = Long.MAX_VALUE;
    private int sendIntervalSec = 120;
    private int skipCycles;
    private Path dataFile;

    private final ConsoleBuffer console = new ConsoleBuffer();
    private Log4jTap tap;
    private Auth auth;
    private HttpApi api;
    private final Moderation moderation = new Moderation();
    private final java.util.concurrent.atomic.AtomicLong chatPending = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ConcurrentLinkedQueue<long[]> clearPending = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile String latestPlayers = "{\"players\":[]}";
    private volatile String latestHistory = "{\"points\":[]}";
    private volatile String latestStats = "{\"version\":1,\"starting\":true}";

    @Override
    public void onEnable() {
        try {
            tap = Log4jTap.attach(console);
        } catch (Throwable t) {
            tap = null;
        }
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.json").toPath();
        startedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
        readConfig();
        loadData();
        loadModeration();

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
        // la première tâche du scheduler s'exécute quand le serveur a fini de charger : c'est là que le chrono démarre
        Bukkit.getScheduler().runTask(this, this::markReady);
        // les joueurs déjà connectés (rechargement du plugin)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!authMe || isAuthenticated(p)) startSession(p);
        }
        if (data.players.isEmpty()) seedKnownPlayers();

        Bukkit.getScheduler().runTaskLater(this, this::refreshWorlds, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::samplePeriodic, 20L * 60, SAMPLE_TICKS);
        long period = Math.max(30, sendIntervalSec) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::sendCycle, 20L * 20, period);

        hookConsoleForClearLag();
        Bukkit.getScheduler().runTaskTimer(this, () -> latestStats = buildJson(), 60L,
                Math.max(5, getConfig().getInt("api.stats-refresh-seconds", 20)) * 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshHeavyJson, 100L, 60L * 20L);
        startApi();
        getLogger().info("AethoriaStats actif" + (authMe ? " (sessions comptées après connexion AuthMe)." : "."));
    }

    @Override
    public void onDisable() {
        if (api != null) api.stop();
        if (tap != null) tap.detach();
        long now = System.currentTimeMillis();
        for (String u : new ArrayList<>(data.active.keySet())) data.endSession(u, now);
        online.clear();
        try {
            writeData(Json.write(data.toMap()));
        } catch (Exception e) {
            getLogger().warning("Sauvegarde finale impossible : " + e.getMessage());
        }
        saveModeration(moderation);
        io.shutdown();
        uploader.shutdown();
    }

    // ---- API HTTP ----

    private void startApi() {
        if (!getConfig().getBoolean("api.enabled", true)) return;
        if (!auth.hasAccounts()) {
            getLogger().warning("Aucun compte admin : depuis la console, tape /astats setpass <nom> <mot de passe> (10 caractères minimum).");
        }
        try {
            var origins = new java.util.HashSet<>(getConfig().getStringList("api.allowed-origins"));
            var audit = new AuditLog(new File(getDataFolder(), "audit.log").toPath());
            var backend = new AdminBackend(this, () -> data, () -> latestStats, () -> latestPlayers, () -> latestHistory, moderation, audit, this::saveModeration,
                    u -> online.containsKey(u));
            api = new HttpApi(backend, auth, console, origins, m -> getLogger().warning(m));
            String bind = getConfig().getString("api.bind", "127.0.0.1");
            int port = getConfig().getInt("api.port", 8765);
            api.start(bind, port);
            getLogger().info("API sur http://" + bind + ":" + port + " (stats publiques : /api/stats).");
        } catch (Exception e) {
            getLogger().warning("API HTTP non démarrée : " + e);
            api = null;
        }
    }

    private void loadModeration() {
        Path f = new File(getDataFolder(), "moderation.json").toPath();
        if (!Files.exists(f)) return;
        try {
            moderation.load(Json.map(Json.parse(Files.readString(f, StandardCharsets.UTF_8))));
        } catch (Exception e) {
            getLogger().warning("moderation.json illisible : " + e.getMessage());
        }
    }

    private void saveModeration(Moderation m) {
        final String text = Json.write(m.toMap());
        Runnable job = () -> {
            try {
                Path f = new File(getDataFolder(), "moderation.json").toPath();
                Files.createDirectories(f.getParent());
                Path tmp = f.resolveSibling("moderation.json.tmp");
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                getLogger().warning("Sauvegarde de moderation.json impossible : " + e.getMessage());
            }
        };
        if (io.isShutdown()) job.run();
        else io.execute(job);
    }

    private void loadAccounts() {
        Map<String, Auth.Account> accs = new HashMap<>();
        for (Map<?, ?> m : getConfig().getMapList("admins")) {
            String name = String.valueOf(m.get("name"));
            Object h = m.get("hash");
            Object r = m.get("role");
            String role = r == null ? "admin" : String.valueOf(r);
            if (h == null || !Auth.validRole(role)) {
                getLogger().warning("Compte admin ignoré (hash ou rôle manquant) : " + name);
                continue;
            }
            accs.put(name.toLowerCase(), new Auth.Account(name, String.valueOf(h), role));
        }
        auth.setAccounts(accs);
    }

    private void setPass(CommandSender s, String[] a) {
        if (!(s instanceof ConsoleCommandSender)) {
            s.sendMessage("Cette commande se tape uniquement dans la console du serveur.");
            return;
        }
        if (a.length < 3 || a.length > 4) {
            s.sendMessage("Usage : /astats setpass <nom> <mot de passe> [admin|modo]");
            return;
        }
        String name = a[1], pass = a[2];
        if (!name.matches("[A-Za-z0-9_.-]{2,32}")) {
            s.sendMessage("Nom invalide (lettres, chiffres, _ . - ; 2 à 32 caractères).");
            return;
        }
        if (pass.length() < 10) {
            s.sendMessage("Mot de passe trop court : 10 caractères minimum.");
            return;
        }
        String role = a.length == 4 ? a[3].toLowerCase() : null;
        if (role != null && !Auth.validRole(role)) {
            s.sendMessage("Rôle inconnu : admin ou modo.");
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        boolean found = false;
        for (Map<?, ?> m : getConfig().getMapList("admins")) {
            Map<String, Object> e = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) e.put(String.valueOf(en.getKey()), en.getValue());
            if (name.equalsIgnoreCase(String.valueOf(e.get("name")))) {
                e.put("hash", Auth.hash(pass));
                if (role != null) e.put("role", role);
                found = true;
            }
            list.add(e);
        }
        if (!found) {
            Map<String, Object> e = new java.util.LinkedHashMap<>();
            e.put("name", name);
            e.put("hash", Auth.hash(pass));
            e.put("role", role == null ? "admin" : role);
            list.add(e);
        }
        getConfig().set("admins", list);
        saveConfig();
        loadAccounts();
        s.sendMessage((found ? "Mot de passe mis à jour pour " : "Compte créé : ") + name + ".");
    }

    // ---- modération : muets et joueurs immobilisés ----

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Moderation.Mute m = moderation.muteOf(e.getPlayer().getUniqueId().toString(), System.currentTimeMillis());
        if (m == null) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage("§cTu es muet" + (m.until == 0 ? "." : " pendant encore " + Math.max(1, (m.until - System.currentTimeMillis()) / 60_000) + " min.")
                + (m.reason.isEmpty() ? "" : " Raison : " + m.reason));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandMute(PlayerCommandPreprocessEvent e) {
        if (moderation.muteOf(e.getPlayer().getUniqueId().toString(), System.currentTimeMillis()) == null) return;
        String c = e.getMessage().toLowerCase().split("\\s+")[0];
        if (c.matches("/(msg|tell|w|whisper|r|reply|me|say|mail|t|m)") || c.matches("/[a-z0-9_]+:(msg|tell|w|me|say)")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cTu es muet : cette commande est bloquée.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrozenMove(PlayerMoveEvent e) {
        if (!moderation.isFrozen(e.getPlayer().getUniqueId().toString())) return;
        var f = e.getFrom();
        var t = e.getTo();
        if (t == null) return;
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()) {
            t.setX(f.getX());
            t.setY(f.getY());
            t.setZ(f.getZ());
            e.setTo(t);
        }
    }

    private void readConfig() {
        reloadConfig();
        var c = getConfig();
        hideNames = c.getBoolean("hide-player-names", false);
        sendIntervalSec = c.getInt("send-interval-seconds", 120);
        auth = auth == null ? new Auth(Math.max(1, c.getInt("api.session-hours", 12)) * 3_600_000L) : auth;
        loadAccounts();
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
        recordDeathCause(e.getEntity());
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

    // ---- vie du royaume : chat (nombre seulement), commandes (nom seul), morts, monstres, succès ----

    private static final java.util.Set<String> SECRET_COMMANDS = java.util.Set.of("login", "l", "log", "register", "reg",
            "changepassword", "cp", "unregister", "email", "captcha", "verification", "totp", "2fa");
    private static final java.util.regex.Pattern CLEARLAG = java.util.regex.Pattern.compile(
            "(?i)(?:clear\\s?lag.*?removed\\s+(\\d+))|(?:removed\\s+(\\d+)\\s+(?:entities|entit))|(?:balayeurs.*?:\\s*(\\d+))");

    private void hookConsoleForClearLag() {
        console.setListener(text -> {
            if (text.length() > 300) return;
            java.util.regex.Matcher m = CLEARLAG.matcher(text);
            if (m.find()) {
                String n = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                try {
                    clearPending.add(new long[] {System.currentTimeMillis(), Long.parseLong(n)});
                } catch (NumberFormatException ignored) {
                    // nombre illisible : ignoré
                }
            }
        });
    }

    /** Reprend sur le thread principal ce qui a été compté sur d'autres threads. */
    private void drainPending() {
        long n = chatPending.getAndSet(0);
        long now = System.currentTimeMillis();
        if (n > 0) data.chatByDay.merge(data.dayOf(now), (int) n, Integer::sum);
        long[] c;
        while ((c = clearPending.poll()) != null) data.addClearlag(c[0], c[1]);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChatCount(AsyncPlayerChatEvent e) {
        chatPending.incrementAndGet(); // le contenu du message n'est jamais lu
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandCount(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        if (msg.length() < 2) return;
        int sp = msg.indexOf(' ');
        String name = (sp < 0 ? msg.substring(1) : msg.substring(1, sp)).toLowerCase();
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        if (name.isEmpty() || name.length() > 24 || SECRET_COMMANDS.contains(name) || !name.matches("[a-z0-9_.-]+")) return;
        StatsData.bump(data.commands, name, 150);
        data.commandsByDay.merge(data.dayOf(System.currentTimeMillis()), 1, Integer::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        String key = e.getAdvancement().getKey().getKey();
        if (key.startsWith("recipes/") || !online.containsKey(e.getPlayer().getUniqueId())) return;
        data.addAdvancement(System.currentTimeMillis(), e.getPlayer().getName(), key);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobKilled(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player || e.getEntity().getKiller() == null) return;
        StatsData.bump(data.mobsKilled, e.getEntityType().name(), 80);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent e) {
        if (e.getType() == ServerLoadEvent.LoadType.STARTUP) {
            data.bootMs = Math.max(0, System.currentTimeMillis() - startedAt);
            markReady();
        }
    }

    /** Le serveur est prêt : on repart de zéro et on ignore les 2 premières minutes. */
    private void markReady() {
        readyAt = System.currentTimeMillis();
        ticks.ignoreFor(WARMUP_MS);
    }

    private void recordDeathCause(Player p) {
        EntityDamageEvent ev = p.getLastDamageCause();
        String label = "Autre";
        if (ev != null) {
            switch (ev.getCause()) {
                case FALL -> label = "Chute";
                case FIRE, FIRE_TICK, LAVA, HOT_FLOOR -> label = "Feu et lave";
                case DROWNING -> label = "Noyade";
                case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> label = "Combat (monstre ou joueur)";
                case PROJECTILE -> label = "Flèche ou projectile";
                case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> label = "Explosion";
                case STARVATION -> label = "Faim";
                case SUFFOCATION, CRAMMING -> label = "Étouffement";
                case VOID -> label = "Le vide";
                case LIGHTNING -> label = "Foudre";
                case MAGIC, POISON, WITHER, DRAGON_BREATH -> label = "Magie et poisons";
                case CONTACT -> label = "Cactus et ronces";
                case FALLING_BLOCK -> label = "Bloc qui tombe";
                case FREEZE -> label = "Froid";
                case SUICIDE -> label = "Commande";
                default -> label = "Autre";
            }
            if (ev instanceof EntityDamageByEntityEvent by) {
                Entity damager = by.getDamager();
                if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) damager = shooter;
                StatsData.bump(data.killers, damager instanceof Player ? "PLAYER" : damager.getType().name(), 60);
            }
        }
        StatsData.bump(data.deathCauses, label, 40);
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
        int chunks = 0, ents = 0;
        for (Snapshot.WorldInfo w : worldCache) {
            chunks += w.chunks;
            ents += w.entities;
        }
        drainPending();
        if (data.sample(now, data.active.size(), ticks.tps(300), ramMb, ticks.takeMin(), chunks, ents)) {
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
        long left = readyAt == Long.MAX_VALUE ? WARMUP_MS : readyAt + WARMUP_MS - System.currentTimeMillis();
        l.warmupSec = (int) Math.max(0, (left + 999) / 1000);
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

    private void refreshHeavyJson() {
        long now = System.currentTimeMillis();
        drainPending();
        data.checkpointAll(now);
        latestPlayers = Json.write(Snapshot.players(data, now, hideNames));
        latestHistory = Json.write(Snapshot.history(data, now));
    }

    private String buildJson() {
        long now = System.currentTimeMillis();
        drainPending();
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
        if (args.length > 0 && args[0].equalsIgnoreCase("setpass")) {
            setPass(s, args);
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
        s.sendMessage(api != null ? "API active sur le port " + api.port() + "." : "API arrêtée (voir config.yml, section api).");
        s.sendMessage("Commandes : /astats setpass <nom> <mdp> [admin|modo] (console), /astats send, /astats reload.");
        return true;
    }
}
