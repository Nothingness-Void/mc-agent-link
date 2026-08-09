package world.agentlink.spigot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Guest-token approval flow. This is connection safety, not an in-game agent command system. */
public final class ApprovalService {
    public record Decision(boolean approved, String reason) {}
    private record Pending(String id, String tool, boolean adminOnly, CompletableFuture<Decision> future,
                           ScheduledFuture<?> timeout) {}

    private static final Gson GSON = new Gson();

    private final JavaPlugin plugin;
    private final SpigotConfig.Snapshot config;
    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public ApprovalService(JavaPlugin plugin, SpigotConfig.Snapshot config) {
        this.plugin = plugin;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "agent-link-spigot-approval-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<Decision> request(String toolName, JsonObject args, CallTier tier) {
        return request(toolName, args, tier, true);
    }

    public CompletableFuture<Decision> request(String toolName, JsonObject args, CallTier tier,
                                               boolean mutating) {
        if (tier == CallTier.CONSOLE || !mutating) {
            return CompletableFuture.completedFuture(new Decision(true, tier == CallTier.CONSOLE
                    ? "console-tier token" : "read-only tool"));
        }
        if (!config.approvalEnabled()) {
            return CompletableFuture.completedFuture(new Decision(true, "approval disabled"));
        }
        String normalized = normalize(toolName);
        boolean adminOnly = matches(config.adminOnlyTools(), normalized);
        if (!adminOnly && matches(config.autoAllowTools(), normalized)) {
            return CompletableFuture.completedFuture(new Decision(true, "auto allowed"));
        }
        if (!adminOnly && matches(config.trustedTools(), normalized)) {
            return CompletableFuture.completedFuture(new Decision(true, "trusted tool"));
        }

        List<? extends Player> approvers = Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.isOp() || player.hasPermission("agentlink.approve"))
                .filter(player -> !adminOnly || player.hasPermission("agentlink.admin"))
                .toList();
        if (approvers.isEmpty()) {
            String reason = adminOnly
                    ? "No online Agent Link admin can approve this tool."
                    : "No online OP or Agent Link approver can approve this tool.";
            return CompletableFuture.completedFuture(new Decision(false, reason));
        }

        String id = "approval-" + sequence.getAndIncrement();
        CompletableFuture<Decision> future = new CompletableFuture<>();
        ScheduledFuture<?> timeout = scheduler.schedule(() -> timeout(id),
                config.approvalTimeoutSeconds(), TimeUnit.SECONDS);
        pending.put(id, new Pending(id, normalized, adminOnly, future, timeout));
        String preview = GSON.toJson(args == null ? new JsonObject() : args);
        if (preview.length() > 420) preview = preview.substring(0, 420) + "...";
        String finalPreview = preview;
        Bukkit.getScheduler().runTask(plugin, () -> {
            String header = ChatColor.GOLD + "[Agent Link] Guest tool approval required: "
                    + ChatColor.YELLOW + normalized + ChatColor.GRAY + " " + finalPreview;
            String actions = ChatColor.AQUA + "/agentlink approve " + id
                    + ChatColor.GRAY + " or " + ChatColor.RED + "/agentlink deny " + id;
            for (Player player : approvers) {
                player.sendMessage(header);
                player.sendMessage(actions);
            }
        });
        plugin.getLogger().info("Guest tool approval queued: " + id + " for " + normalized);
        return future;
    }

    public String approve(String id, CommandSender actor) {
        Pending item = pending.get(id);
        if (item == null) return "No pending approval with id " + id + ".";
        if (!authorized(actor, item.adminOnly())) {
            return "You are not authorized to approve this request.";
        }
        if (!pending.remove(id, item)) return "Approval was already handled.";
        item.timeout().cancel(false);
        item.future().complete(new Decision(true, "approved by " + actor.getName()));
        return "Approved " + id + ".";
    }

    public String deny(String id, CommandSender actor) {
        Pending item = pending.get(id);
        if (item == null) return "No pending approval with id " + id + ".";
        if (!authorized(actor, item.adminOnly())) {
            return "You are not authorized to deny this request.";
        }
        if (!pending.remove(id, item)) return "Approval was already handled.";
        item.timeout().cancel(false);
        item.future().complete(new Decision(false, "denied by " + actor.getName()));
        return "Denied " + id + ".";
    }

    public List<String> pendingSummary() {
        List<String> result = new ArrayList<>();
        for (Pending item : pending.values()) {
            result.add(item.id() + " " + item.tool() + (item.adminOnly() ? " [admin]" : ""));
        }
        result.sort(String::compareTo);
        return result;
    }

    public void stop() {
        scheduler.shutdownNow();
        for (Pending item : pending.values()) {
            item.future().complete(new Decision(false, "server stopping"));
        }
        pending.clear();
    }

    private void timeout(String id) {
        Pending item = pending.remove(id);
        if (item != null) item.future().complete(new Decision(false, "approval timed out"));
    }

    private boolean authorized(CommandSender sender, boolean adminOnly) {
        if (!(sender instanceof Player)) return true;
        return sender.hasPermission(adminOnly ? "agentlink.admin" : "agentlink.approve")
                || (!adminOnly && sender.isOp());
    }

    private static boolean matches(List<String> rules, String tool) {
        for (String rule : rules) {
            if (rule == null) continue;
            String normalized = rule.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("*") || normalized.equals(tool)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
