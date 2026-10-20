package fr.aethoria.stats;

/** Mesure le TPS réel : une tâche appelée à chaque tick compte les ticks par seconde réelle. */
public final class TickMonitor implements Runnable {
    private static final int BINS = 900; // 15 min

    private final double[] tpsBins = new double[BINS];
    private int count;
    private int idx;
    private long binStart = System.nanoTime();
    private int ticks;
    private long lastTick = binStart;
    private double msptAvg = 50;
    private double minBin = 20;
    private long ignoreUntil;

    @Override
    public void run() {
        long now = System.nanoTime();
        if (now < ignoreUntil) { // démarrage : on ne mesure pas
            ticks = 0;
            binStart = now;
            lastTick = now;
            return;
        }
        ticks++;
        double interval = (now - lastTick) / 1e6;
        lastTick = now;
        if (interval < 2000) msptAvg += (interval - msptAvg) * 0.01;
        long elapsed = now - binStart;
        if (elapsed >= 1_000_000_000L) {
            tpsBins[idx] = Math.min(20.0, ticks * 1e9 / elapsed);
            if (tpsBins[idx] < minBin) minBin = tpsBins[idx];
            idx = (idx + 1) % BINS;
            if (count < BINS) count++;
            ticks = 0;
            binStart = now;
        }
    }

    /** Moyenne du TPS sur les {@code seconds} dernières secondes (20 si pas encore de mesure). */
    public double tps(int seconds) {
        int n = Math.min(seconds, count);
        if (n == 0) return 20.0;
        double sum = 0;
        for (int i = 1; i <= n; i++) sum += tpsBins[(idx - i + BINS) % BINS];
        return sum / n;
    }

    /** Efface les mesures et ignore les {@code ms} millisecondes qui suivent (chargement du serveur). */
    public void ignoreFor(long ms) {
        java.util.Arrays.fill(tpsBins, 0);
        count = 0;
        idx = 0;
        minBin = 20;
        msptAvg = 50;
        ticks = 0;
        binStart = System.nanoTime();
        lastTick = binStart;
        ignoreUntil = binStart + ms * 1_000_000L;
    }

    /** Plus bas TPS d'une seconde depuis le dernier appel (puis remise à 20). */
    public double takeMin() {
        double m = minBin;
        minBin = 20;
        return m;
    }

    /** Durée moyenne entre deux ticks, en ms (approximation du MSPT : 50 = serveur à la limite de 20 TPS). */
    public double mspt() {
        return msptAvg;
    }
}
