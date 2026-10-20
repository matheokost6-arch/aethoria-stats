package fr.aethoria.stats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Tampon circulaire des dernières lignes de la console, avec attente (long-polling). */
public final class ConsoleBuffer {
    public static final int CAPACITY = 1000;
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]|\\u001B[()][A-Z0-9]");
    private static final Pattern SECTION = Pattern.compile("\\u00A7[0-9a-fk-orA-FK-OR]");
    private static final Pattern IPV4 = Pattern.compile("/?\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d{1,5})?\\b");
    private static final Pattern SETPASS = Pattern.compile("(?i)(setpass\\s+\\S+\\s+)\\S+");

    public static final class Line {
        public final long n;
        public final long ts;
        public final String level;
        public final String text;

        Line(long n, long ts, String level, String text) {
            this.n = n;
            this.ts = ts;
            this.level = level;
            this.text = text;
        }
    }

    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private volatile java.util.function.Consumer<String> listener;
    private long seq;

    /** Nettoie une ligne : couleurs ANSI et §, adresses IP, mots de passe de /astats setpass. */
    public static String clean(String s) {
        if (s == null) return "";
        s = ANSI.matcher(s).replaceAll("");
        s = SECTION.matcher(s).replaceAll("");
        s = SETPASS.matcher(s).replaceAll("$1***");
        s = IPV4.matcher(s).replaceAll("[ip]");
        if (s.length() > 2000) s = s.substring(0, 2000) + "...";
        return s;
    }

    /** Appelé (sur le thread du journal) avec chaque ligne brute : doit rester très rapide. */
    public void setListener(java.util.function.Consumer<String> l) {
        this.listener = l;
    }

    public void add(long ts, String level, String text) {
        java.util.function.Consumer<String> l = listener;
        if (l != null && text != null) {
            try {
                l.accept(text);
            } catch (RuntimeException ignored) {
                // un écouteur ne doit jamais casser le journal
            }
        }
        append(ts, level, text);
    }

    private synchronized void append(long ts, String level, String text) {
        lines.addLast(new Line(++seq, ts, level, clean(text)));
        while (lines.size() > CAPACITY) lines.pollFirst();
        notifyAll();
    }

    public synchronized long last() {
        return seq;
    }

    /**
     * Renvoie les lignes de numéro > since (au plus {@code max}). since < 0 : les {@code max} dernières.
     * Attend jusqu'à {@code waitMs} s'il n'y a rien de nouveau.
     */
    public synchronized List<Line> read(long since, int max, long waitMs) throws InterruptedException {
        if (since >= 0 && seq <= since && waitMs > 0) {
            long end = System.currentTimeMillis() + waitMs;
            long left;
            while (seq <= since && (left = end - System.currentTimeMillis()) > 0) wait(left);
        }
        List<Line> out = new ArrayList<>();
        if (since < 0) {
            Line[] all = lines.toArray(new Line[0]);
            for (int i = Math.max(0, all.length - max); i < all.length; i++) out.add(all[i]);
        } else {
            for (Line l : lines) {
                if (l.n > since) {
                    out.add(l);
                    if (out.size() >= max) break;
                }
            }
        }
        return out;
    }
}
