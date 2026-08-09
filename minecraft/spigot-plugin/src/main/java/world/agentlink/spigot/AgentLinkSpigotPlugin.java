package world.agentlink.spigot;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Spigot entry point for the base agent-link connection plugin. */
public final class AgentLinkSpigotPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private SpigotConfig.Snapshot config;
    private TokenStore tokens;
    private EventBuffer events;
    private TickMonitor ticks;
    private ApprovalService approvals;
    private SpigotDispatcher dispatcher;
    private SpigotMcpServer mcp;
    private SpigotWebSocketServer websocket;
    private ModernCompatibility.RuntimeInfo platform;

    @Override
    public void onEnable() {
        config = SpigotConfig.load(this);
        platform = ModernCompatibility.detect();
        getLogger().info("Detected Bukkit platform: " + platform.summary());
        if (!platform.supported()) {
            getLogger().warning("This server is outside the declared modern support range 1.20+. "
                    + "The plugin will continue, but compatibility is not guaranteed.");
        }
        tokens = new TokenStore(getDataFolder().toPath());
        events = new EventBuffer();
        ticks = new TickMonitor(this);
        ticks.start();
        getServer().getPluginManager().registerEvents(new SpigotEventListener(events), this);
        approvals = new ApprovalService(this, config);
        dispatcher = new SpigotDispatcher(this, config, approvals, events, ticks, platform);

        try {
            websocket = new SpigotWebSocketServer(this, config, dispatcher, tokens, events);
            websocket.start();
            getLogger().info("Agent Link WebSocket listening on " + endpoint(config.listenPort()));
        } catch (Exception e) {
            getLogger().severe("Agent Link WebSocket failed to start: " + e.getMessage());
        }
        if (config.mcpEnabled()) {
            try {
                mcp = new SpigotMcpServer(this, config, dispatcher, tokens);
                mcp.start();
                getLogger().info("Agent Link MCP HTTP listening on http://127.0.0.1:" + config.mcpPort() + "/mcp");
                if (mcp.pairingNeeded()) {
                    getLogger().info("Agent Link local setup endpoint (one use, expires at "
                            + Instant.ofEpochMilli(mcp.pairExpiresAtMs()) + "): " + mcp.setupLink());
                } else {
                    getLogger().info("Agent Link pairing already exists; setup endpoint suppressed. "
                            + "Run /agentlink pair to pair another agent.");
                }
            } catch (Exception e) {
                getLogger().severe("Agent Link MCP HTTP failed to start: " + e.getMessage());
            }
        }
        if (getCommand("agentlink") != null) {
            getCommand("agentlink").setExecutor(this);
            getCommand("agentlink").setTabCompleter(this);
        }
        getLogger().info("Agent Link Spigot enabled. No /agent command, GUI, request queue, or steer API is registered.");
    }

    @Override
    public void onDisable() {
        if (mcp != null) mcp.stop();
        if (websocket != null) {
            try {
                websocket.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (dispatcher != null) dispatcher.shutdown();
        if (approvals != null) approvals.stop();
        if (ticks != null) ticks.stop();
    }

    public SpigotMcpServer mcpServer() {
        return mcp;
    }

    public SpigotConfig.Snapshot configSnapshot() {
        return config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("agentlink.admin") && !sender.isOp() && !sender.getName().equals("CONSOLE")) {
            sender.sendMessage(ChatColor.RED + "[Agent Link] You need agentlink.admin or OP.");
            return true;
        }
        String subcommand = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "pair" -> sendPair(sender, TokenStore.Tier.CONSOLE);
            case "pair-guest" -> sendPair(sender, TokenStore.Tier.GUEST);
            case "status" -> status(sender);
            case "tokens" -> listTokens(sender);
            case "revoke" -> revoke(sender, args);
            case "approvals" -> approvals(sender);
            case "approve" -> approval(sender, args, true);
            case "deny" -> approval(sender, args, false);
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(prefix() + "Configuration reloaded from disk. Restart the server to apply port/token changes.");
            }
            default -> sender.sendMessage(prefix() + "Usage: /agentlink <pair|pair-guest|status|tokens|revoke|approvals|approve|deny|reload>");
        }
        return true;
    }

    private void sendPair(CommandSender sender, TokenStore.Tier tier) {
        if (mcp == null) {
            sender.sendMessage(ChatColor.RED + prefix() + "MCP HTTP is not running.");
            return;
        }
        SpigotMcpServer.SetupLink link = mcp.refreshSetupLink(tier);
        sender.sendMessage(ChatColor.AQUA + prefix() + "Local setup endpoint refreshed for " + tier.name().toLowerCase(Locale.ROOT)
                + "; expires at " + Instant.ofEpochMilli(link.expiresAtMs()) + ":");
        sender.sendMessage(ChatColor.GRAY + link.link());
    }

    private void status(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + prefix() + "Agent Link is enabled.");
        sender.sendMessage(ChatColor.GRAY + "WebSocket: " + (websocket == null ? "stopped" : endpoint(config.listenPort())));
        sender.sendMessage(ChatColor.GRAY + "MCP HTTP: " + (mcp == null ? "stopped" : "http://127.0.0.1:" + config.mcpPort() + "/mcp"));
        sender.sendMessage(ChatColor.GRAY + "Registered in-game commands: /agentlink only (no /agent).");
    }

    private void listTokens(CommandSender sender) {
        List<TokenStore.Entry> entries = tokens.list();
        sender.sendMessage(ChatColor.AQUA + prefix() + "Issued token hashes: " + entries.size());
        for (TokenStore.Entry entry : entries) {
            sender.sendMessage(ChatColor.GRAY + entry.hash().substring(0, Math.min(12, entry.hash().length())) + "... ["
                    + entry.tier().name().toLowerCase(Locale.ROOT) + "] " + entry.label());
        }
    }

    private void revoke(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + prefix() + "Usage: /agentlink revoke <hash-prefix>");
            return;
        }
        sender.sendMessage(ChatColor.AQUA + prefix() + "Revoked " + tokens.revokeByPrefix(args[1]) + " token(s).");
    }

    private void approvals(CommandSender sender) {
        List<String> pending = approvals.pendingSummary();
        sender.sendMessage(ChatColor.AQUA + prefix() + "Pending approvals: " + pending.size());
        pending.forEach(value -> sender.sendMessage(ChatColor.GRAY + value));
    }

    private void approval(CommandSender sender, String[] args, boolean approve) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + prefix() + "Usage: /agentlink " + (approve ? "approve" : "deny") + " <id>");
            return;
        }
        String message = approve ? approvals.approve(args[1], sender) : approvals.deny(args[1], sender);
        sender.sendMessage((message.startsWith("Approved") || message.startsWith("Denied") ? ChatColor.AQUA : ChatColor.RED)
                + prefix() + message);
    }

    private static String endpoint(int port) {
        return "127.0.0.1:" + port;
    }

    private static String prefix() {
        return "[Agent Link] ";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> values = List.of("pair", "pair-guest", "status", "tokens", "revoke", "approvals", "approve", "deny", "reload");
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
