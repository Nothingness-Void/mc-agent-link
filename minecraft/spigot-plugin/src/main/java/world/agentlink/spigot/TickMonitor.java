package world.agentlink.spigot;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lightweight main-thread timing history for server stats and diagnosis. */
public final class TickMonitor {
    private static final int CAPACITY = 600;
    private final JavaPlugin plugin;
    private final ArrayDeque<Double> samples = new ArrayDeque<>();
    private long lastTickNanos;
    private int taskId = -1;

    public TickMonitor(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        lastTickNanos = System.nanoTime();
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            long now = System.nanoTime();
            double mspt = (now - lastTickNanos) / 1_000_000.0;
            lastTickNanos = now;
            synchronized (samples) {
                if (samples.size() >= CAPACITY) samples.removeFirst();
                samples.addLast(Math.max(0.0, mspt));
            }
        }, 1L, 1L);
    }

    public void stop() {
        if (taskId >= 0) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
    }

    public synchronized JsonStats stats() {
        List<Double> values = snapshot();
        double average = values.stream().mapToDouble(Double::doubleValue).average().orElse(50.0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(50.0);
        return new JsonStats(average, max, percentile(values, 0.50), percentile(values, 0.95),
                percentile(values, 0.99), Math.min(20.0, 1000.0 / Math.max(average, 0.001)), values.size());
    }

    private List<Double> snapshot() {
        synchronized (samples) {
            return new ArrayList<>(samples);
        }
    }

    private static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) return 50.0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(p * sorted.size()) - 1));
        return sorted.get(index);
    }

    public record JsonStats(double averageMspt, double maxMspt, double p50Mspt, double p95Mspt,
                            double p99Mspt, double tps, int windowTicks) {}
}
