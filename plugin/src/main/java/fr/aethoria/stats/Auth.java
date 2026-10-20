package fr.aethoria.stats;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Comptes admin (PBKDF2), jetons de session et protection contre la force brute. Sans dépendance Bukkit. */
public final class Auth {
    public static final int ITERATIONS = 210_000;
    public static final int MAX_FAILS = 5;
    public static final long BLOCK_MS = 15 * 60_000L;
    private static final int MAX_SESSIONS = 50;
    private static final SecureRandom RNG = new SecureRandom();
    private static final String DUMMY = hash("dummy-password-for-timing");

    public static final class Account {
        public final String name;
        public final String hash;
        public final String role;

        public Account(String name, String hash, String role) {
            this.name = name;
            this.hash = hash;
            this.role = role;
        }
    }

    public static final class Session {
        public final String token;
        public final String name;
        public final String role;
        public final long expires;

        Session(String token, String name, String role, long expires) {
            this.token = token;
            this.name = name;
            this.role = role;
            this.expires = expires;
        }
    }

    /** Résultat d'une tentative de connexion. */
    public static final class LoginResult {
        public Session session;
        public boolean blocked;
        public long retryAfterSec;
    }

    private volatile Map<String, Account> accounts = new HashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Long>> fails = new ConcurrentHashMap<>();
    private final Map<String, Long> blockedUntil = new ConcurrentHashMap<>();
    private final long sessionMs;

    public Auth(long sessionMs) {
        this.sessionMs = sessionMs;
    }

    public void setAccounts(Map<String, Account> accs) {
        this.accounts = accs;
        // les sessions d'un compte supprimé ou dont le rôle a changé sont coupées
        sessions.values().removeIf(s -> {
            Account a = accs.get(s.name.toLowerCase());
            return a == null || !a.role.equals(s.role);
        });
    }

    public boolean hasAccounts() {
        return !accounts.isEmpty();
    }

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] h = pbkdf2(password.toCharArray(), salt, ITERATIONS);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(h);
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] p = stored.split("\\$");
            if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
            int iter = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] want = Base64.getDecoder().decode(p[3]);
            byte[] got = pbkdf2(password.toCharArray(), salt, iter);
            return MessageDigest.isEqual(want, got);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] pw, byte[] salt, int iter) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw, salt, iter, 256)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Secondes restantes de blocage pour cette IP, 0 si elle peut essayer. */
    public long blockedFor(String ip, long now) {
        Long until = blockedUntil.get(ip);
        if (until == null) return 0;
        if (until <= now) {
            blockedUntil.remove(ip);
            fails.remove(ip);
            return 0;
        }
        return (until - now + 999) / 1000;
    }

    public LoginResult login(String name, String password, String ip, long now) {
        LoginResult res = new LoginResult();
        long wait = blockedFor(ip, now);
        if (wait > 0) {
            res.blocked = true;
            res.retryAfterSec = wait;
            return res;
        }
        Account a = name == null ? null : accounts.get(name.toLowerCase());
        // même coût que le compte existe ou non
        boolean ok = verify(password == null ? "" : password, a != null ? a.hash : DUMMY) && a != null;
        if (!ok) {
            ArrayDeque<Long> q = fails.computeIfAbsent(ip, k -> new ArrayDeque<>());
            synchronized (q) {
                while (!q.isEmpty() && q.peekFirst() < now - BLOCK_MS) q.pollFirst();
                q.addLast(now);
                if (q.size() >= MAX_FAILS) {
                    blockedUntil.put(ip, now + BLOCK_MS);
                    q.clear();
                }
            }
            return res;
        }
        fails.remove(ip);
        purge(now);
        if (sessions.size() >= MAX_SESSIONS) return res;
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Session s = new Session(token, a.name, a.role, now + sessionMs);
        sessions.put(token, s);
        res.session = s;
        return res;
    }

    public Session check(String token, long now) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (s.expires <= now) {
            sessions.remove(token);
            return null;
        }
        return s;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    private void purge(long now) {
        sessions.values().removeIf(s -> s.expires <= now);
        blockedUntil.values().removeIf(t -> t <= now);
    }

    public static boolean validRole(String r) {
        return "admin".equals(r) || "modo".equals(r);
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
