package fr.aethoria.stats;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Actions d'administration, toujours exécutées sur le thread principal. */
public final class AdminBackend implements HttpApi.Backend {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]{1,16}");
    private static final String[] BAD_EFFECTS = {"POISON", "WITHER", "HUNGER", "SLOW", "WEAKNESS", "BLINDNESS", "CONFUSION",
        "SLOW_DIGGING", "LEVITATION", "BAD_OMEN", "DARKNESS", "HARM", "UNLUCK"};

    private final Plugin plugin;
    private final Supplier<StatsData> data;
    private final Supplier<String> stats;
    private final Supplier<String> playersJ;
    private final Supplier<String> historyJ;
    private final Moderation moderation;
    private final AuditLog audit;
    private final Consumer<Moderation> saveModeration;
    private final java.util.function.Predicate<UUID> counted;

    public AdminBackend(Plugin plugin, Supplier<StatsData> data, Supplier<String> stats, Supplier<String> playersJ, Supplier<String> historyJ,
                        Moderation moderation, AuditLog audit,
                        Consumer<Moderation> saveModeration, java.util.function.Predicate<UUID> counted) {
        this.plugin = plugin;
        this.data = data;
        this.stats = stats;
        this.playersJ = playersJ;
        this.historyJ = historyJ;
        this.moderation = moderation;
        this.audit = audit;
        this.saveModeration = saveModeration;
        this.counted = counted;
    }

    @Override
    public String statsJson() {
        return stats.get();
    }

    @Override
    public String playersJson() {
        return playersJ.get();
    }

    @Override
    public String historyJson() {
        return historyJ.get();
    }

    @Override
    public void audit(Map<String, Object> entry) {
        audit.add(entry);
    }

    @Override
    public List<Object> audit(int limit) {
        return audit.latest(limit);
    }

    private <T> T onMain(Callable<T> c) throws Exception {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, c).get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Le serveur n'a pas répondu à temps.");
        } catch (ExecutionException e) {
            Throwable t = e.getCause() != null ? e.getCause() : e;
            if (t instanceof Exception ex) throw ex;
            throw e;
        }
    }

    // ---- lecture ----

    @Override
    public List<Object> players() {
        try {
            return onMain(() -> {
                long now = System.currentTimeMillis();
                List<Object> out = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String u = p.getUniqueId().toString();
                    AttributeInstance maxA = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    m.put("name", p.getName());
                    m.put("uuid", u);
                    m.put("health", Math.round(p.getHealth() * 10) / 10.0);
                    m.put("maxHealth", maxA != null ? maxA.getValue() : 20);
                    m.put("food", p.getFoodLevel());
                    m.put("world", p.getWorld().getName());
                    m.put("x", p.getLocation().getBlockX());
                    m.put("y", p.getLocation().getBlockY());
                    m.put("z", p.getLocation().getBlockZ());
                    m.put("gamemode", p.getGameMode().name().toLowerCase());
                    int ping = -1;
                    try {
                        ping = p.getPing();
                    } catch (Throwable ignored) {
                        // ping indisponible
                    }
                    m.put("ping", ping);
                    Moderation.Mute mu = moderation.muteOf(u, now);
                    m.put("muted", mu != null);
                    m.put("mutedUntil", mu != null ? mu.until : 0);
                    m.put("frozen", moderation.isFrozen(u));
                    m.put("op", p.isOp());
                    m.put("loggedIn", counted.test(p.getUniqueId()));
                    out.add(m);
                }
                return out;
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, Object> sanctions() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            onMain(() -> {
                List<Object> bans = new ArrayList<>();
                BanList bl = Bukkit.getBanList(BanList.Type.NAME);
                for (Object o : bl.getBanEntries()) {
                    BanEntry be = (BanEntry) o;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", be.getTarget());
                    m.put("reason", be.getReason() == null ? "" : ConsoleBuffer.clean(be.getReason()));
                    m.put("until", be.getExpiration() == null ? 0 : be.getExpiration().getTime());
                    m.put("created", be.getCreated() == null ? 0 : be.getCreated().getTime());
                    m.put("by", be.getSource() == null ? "" : be.getSource());
                    bans.add(m);
                }
                out.put("bans", bans);
                List<Object> mutes = new ArrayList<>();
                for (Moderation.Mute mu : moderation.activeMutes(System.currentTimeMillis())) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", mu.name);
                    m.put("reason", mu.reason);
                    m.put("until", mu.until);
                    m.put("by", mu.by);
                    mutes.add(m);
                }
                out.put("mutes", mutes);
                List<Object> fr = new ArrayList<>(moderation.frozenAll().values());
                out.put("frozen", fr);
                return null;
            });
        } catch (Exception e) {
            out.put("bans", new ArrayList<>());
            out.put("mutes", new ArrayList<>());
            out.put("frozen", new ArrayList<>());
        }
        return out;
    }

    // ---- actions ----

    private static Map<String, Object> ok(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", msg);
        return m;
    }

    private static Map<String, Object> ko(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }

    private static String text(Object o, int max) {
        String s = Json.str(o, "").replaceAll("[\\p{Cntrl}§]", "").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    @Override
    public Map<String, Object> action(Auth.Session who, Map<String, Object> req) {
        try {
            return onMain(() -> doAction(who, req));
        } catch (Exception e) {
            return ko(e.getMessage() == null ? "Échec." : e.getMessage());
        }
    }

    private Map<String, Object> doAction(Auth.Session who, Map<String, Object> req) {
        String action = Json.str(req.get("action"), "");
        String target = Json.str(req.get("target"), "").trim();
        String reason = text(req.get("reason"), 200);
        String by = who.name;

        // actions sans cible
        switch (action) {
            case "broadcast": {
                String msg = text(req.get("message"), 200);
                if (msg.isEmpty()) return ko("Écris un message.");
                Bukkit.broadcastMessage("§6§l[Aethoria] §e" + msg);
                return ok("Annonce envoyée.");
            }
            case "save-all":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
                return ok("Sauvegarde du monde lancée.");
            case "clearlag":
                return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lagg clear") ? ok("Nettoyage des entités lancé.") : ko("Commande ClearLag introuvable.");
            case "weather": {
                World w = world(req);
                if (w == null) return ko("Monde introuvable.");
                switch (Json.str(req.get("value"), "")) {
                    case "clear" -> { w.setStorm(false); w.setThundering(false); }
                    case "rain" -> { w.setStorm(true); w.setThundering(false); }
                    case "thunder" -> { w.setStorm(true); w.setThundering(true); }
                    default -> { return ko("Météo inconnue (clear, rain, thunder)."); }
                }
                return ok("Météo changée dans " + w.getName() + ".");
            }
            case "time": {
                World w = world(req);
                if (w == null) return ko("Monde introuvable.");
                String v = Json.str(req.get("value"), "");
                long t;
                switch (v) {
                    case "day" -> t = 1000;
                    case "noon" -> t = 6000;
                    case "night" -> t = 13000;
                    case "midnight" -> t = 18000;
                    default -> {
                        try {
                            t = Long.parseLong(v);
                        } catch (NumberFormatException e) {
                            return ko("Heure inconnue (day, noon, night, midnight ou 0 à 24000).");
                        }
                        if (t < 0 || t > 24000) return ko("L'heure doit être entre 0 et 24000.");
                    }
                }
                w.setTime(t);
                return ok("Heure changée dans " + w.getName() + ".");
            }
            case "console": {
                String cmd = Json.str(req.get("command"), "").trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (cmd.isEmpty() || cmd.length() > 300) return ko("Commande vide ou trop longue.");
                if (cmd.toLowerCase().matches("astats\\s+setpass.*")) return ko("Change les mots de passe depuis la console du serveur, pas d'ici.");
                boolean done = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                return done ? ok("Commande envoyée.") : ko("Commande inconnue ou refusée.");
            }
            default:
                break;
        }

        if (!NAME.matcher(target).matches()) return ko("Pseudo invalide.");
        Player p = Bukkit.getPlayerExact(target);
        long now = System.currentTimeMillis();

        switch (action) {
            case "ban": {
                long dur = HttpApi.durationMs(Json.str(req.get("duration"), ""));
                if (dur < 0) return ko("Durée invalide (ex. 30m, 2h, 7d).");
                String why = reason.isEmpty() ? "Banni par un modérateur" : reason;
                Date until = dur == 0 ? null : new Date(now + dur);
                Bukkit.getBanList(BanList.Type.NAME).addBan(p != null ? p.getName() : target, why, until, by);
                if (p != null) p.kickPlayer("§cTu as été banni.\n§f" + why);
                return ok(target + " est banni" + (dur == 0 ? " définitivement." : " pour " + Json.str(req.get("duration"), "") + "."));
            }
            case "unban": {
                BanList bl = Bukkit.getBanList(BanList.Type.NAME);
                if (!bl.isBanned(target)) return ko(target + " n'est pas banni.");
                bl.pardon(target);
                return ok(target + " est débanni.");
            }
            case "mute":
            case "unmute": {
                String uuid = p != null ? p.getUniqueId().toString() : knownUuid(target);
                if (uuid == null) return ko("Joueur inconnu : il doit s'être déjà connecté.");
                String name = p != null ? p.getName() : knownName(uuid);
                if (action.equals("unmute")) {
                    if (!moderation.unmute(uuid)) return ko(name + " n'est pas muet.");
                    saveModeration.accept(moderation);
                    if (p != null) p.sendMessage("§aTu peux de nouveau écrire dans le chat.");
                    return ok(name + " peut de nouveau parler.");
                }
                long dur = HttpApi.durationMs(Json.str(req.get("duration"), ""));
                if (dur < 0) return ko("Durée invalide (ex. 30m, 2h, 7d).");
                moderation.mute(new Moderation.Mute(uuid, name, dur == 0 ? 0 : now + dur, reason, by));
                saveModeration.accept(moderation);
                if (p != null) p.sendMessage("§cTu as été rendu muet" + (reason.isEmpty() ? "." : " : " + reason));
                return ok(name + " est muet" + (dur == 0 ? " sans limite." : " pour " + Json.str(req.get("duration"), "") + "."));
            }
            default:
                break;
        }

        // les actions qui suivent exigent un joueur connecté
        if (p == null) return ko(target + " n'est pas connecté.");
        switch (action) {
            case "heal": {
                AttributeInstance a = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                p.setHealth(a != null ? a.getValue() : 20.0);
                p.setFireTicks(0);
                for (String n : BAD_EFFECTS) {
                    PotionEffectType t = PotionEffectType.getByName(n);
                    if (t != null) p.removePotionEffect(t);
                }
                return ok(p.getName() + " est soigné.");
            }
            case "feed":
                p.setFoodLevel(20);
                p.setSaturation(20f);
                return ok(p.getName() + " n'a plus faim.");
            case "kick": {
                p.kickPlayer("§cTu as été exclu du serveur.\n§f" + (reason.isEmpty() ? "Exclu par un modérateur" : reason));
                return ok(target + " est exclu.");
            }
            case "tp-spawn":
                p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
                return ok(p.getName() + " est ramené au spawn.");
            case "clear-effects":
                for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
                return ok("Effets retirés à " + p.getName() + ".");
            case "gamemode": {
                GameMode gm;
                switch (Json.str(req.get("mode"), "")) {
                    case "survival" -> gm = GameMode.SURVIVAL;
                    case "creative" -> gm = GameMode.CREATIVE;
                    case "adventure" -> gm = GameMode.ADVENTURE;
                    case "spectator" -> gm = GameMode.SPECTATOR;
                    default -> { return ko("Mode inconnu (survival, creative, adventure, spectator)."); }
                }
                p.setGameMode(gm);
                return ok(p.getName() + " est en mode " + gm.name().toLowerCase() + ".");
            }
            case "message": {
                String msg = text(req.get("message"), 200);
                if (msg.isEmpty()) return ko("Écris un message.");
                p.sendMessage("§6[Staff] §f" + msg);
                return ok("Message envoyé à " + p.getName() + ".");
            }
            case "freeze":
                moderation.freeze(p.getUniqueId().toString(), p.getName());
                saveModeration.accept(moderation);
                p.sendMessage("§bTu es immobilisé par un modérateur.");
                return ok(p.getName() + " est immobilisé.");
            case "unfreeze":
                if (!moderation.unfreeze(p.getUniqueId().toString())) return ko(p.getName() + " n'est pas immobilisé.");
                saveModeration.accept(moderation);
                p.sendMessage("§aTu peux de nouveau bouger.");
                return ok(p.getName() + " peut de nouveau bouger.");
            default:
                return ko("Action inconnue.");
        }
    }

    private static World world(Map<String, Object> req) {
        String w = Json.str(req.get("world"), "");
        if (w.isEmpty()) return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        return Bukkit.getWorld(w);
    }

    private String knownUuid(String name) {
        for (StatsData.PlayerRec r : data.get().players.values()) {
            if (name.equalsIgnoreCase(r.name)) return r.uuid;
        }
        return null;
    }

    private String knownName(String uuid) {
        StatsData.PlayerRec r = data.get().players.get(uuid);
        return r != null ? r.name : uuid;
    }
}
