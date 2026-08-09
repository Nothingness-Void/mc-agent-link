package world.agentlink;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import world.agentlink.agent.AgentLinkCommand;
import world.agentlink.approval.AgentToolApproval;
import world.agentlink.audit.AuditLog;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.diagnostics.IncidentLedger;
import world.agentlink.diagnostics.TickIncidentRecorder;
import world.agentlink.transport.AgentLinkServer;
import world.agentlink.transport.mcp.McpHttpServer;

@Mod(AgentLinkMod.MOD_ID)
public class AgentLinkMod {
    public static final String MOD_ID = "agentlink";
    public static final Logger LOG = LogUtils.getLogger();
    private static volatile MinecraftServer currentServer;

    private AgentLinkServer server;
    private McpHttpServer mcpServer;

    public AgentLinkMod(IEventBus modBus, ModContainer ignoredContainer) {
        AgentLinkConfig.load();
        world.agentlink.logs.LogTap.install();
        modBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOG.info("agent-link {} initializing", AgentLinkConfig.get().version());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        currentServer = event.getServer();
        var cfg = AgentLinkConfig.get();
        IncidentLedger.start(event.getServer());
        AgentToolApproval.start(event.getServer(), cfg);
        if (cfg.auditEnabled()) {
            AuditLog.start(event.getServer());
        }
        world.agentlink.task.TaskManager.start(event.getServer());
        server = new AgentLinkServer(event.getServer(), cfg);
        try {
            server.start();
            world.agentlink.events.ForgeEventBridge.serverSupplier = () -> server;
            String host = cfg.allowRemote() ? "0.0.0.0" : "127.0.0.1";
            LOG.info("agent-link listening on {}:{}", host, cfg.listenPort());

            if (cfg.mcpEnabled()) {
                try {
                    mcpServer = new McpHttpServer(cfg, server.dispatcher());
                    mcpServer.start();
                    LOG.info("agent-link MCP HTTP listening on http://{}:{}/mcp",
                            host, cfg.mcpListenPort());
                    if (mcpServer.pairingNeeded()) {
                        LOG.info("agent-link local setup endpoint (send this URL to your AI agent, one use, expires at {}): {}",
                                java.time.Instant.ofEpochMilli(mcpServer.pairExpiresAtMs()), mcpServer.setupLink());
                    } else {
                        LOG.info("agent-link pairing already exists; setup endpoint suppressed. "
                                + "Run /agentlink pair to pair another agent.");
                    }
                } catch (Exception e) {
                    LOG.error("agent-link MCP HTTP failed to start (WebSocket transport still up)", e);
                }
            }
        } catch (Exception e) {
            LOG.error("agent-link failed to start", e);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        AgentLinkCommand.register(event, () -> mcpServer);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        currentServer = null;
        TickIncidentRecorder.stop(event.getServer());
        IncidentLedger.stopCurrent(true);
        // Stop accepting work before the world starts tearing down: a task mid-slice would
        // otherwise keep writing blocks into a level that is being unloaded.
        world.agentlink.task.TaskManager.stop();
        AgentToolApproval.stopCurrent();
        AuditLog.stop();
        if (mcpServer != null) {
            try {
                mcpServer.stop();
            } catch (Exception e) {
                LOG.warn("agent-link MCP HTTP shutdown error", e);
            }
        }
        if (server != null) {
            try {
                world.agentlink.dispatch.RequestDispatcher.clearCurrent(server.dispatcher());
                server.dispatcher().shutdownWorkers();
                server.shutdown();
            } catch (Exception e) {
                LOG.warn("agent-link shutdown error", e);
            }
        }
    }

    /** Internal lifecycle hook used by the stable {@code world.agentlink.api} facade. */
    public static MinecraftServer currentServer() {
        return currentServer;
    }
}
