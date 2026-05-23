package world.agentlink;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.transport.AgentLinkServer;

@Mod(AgentLinkMod.MOD_ID)
public class AgentLinkMod {
    public static final String MOD_ID = "agentlink";
    public static final Logger LOG = LogUtils.getLogger();

    private AgentLinkServer server;

    public AgentLinkMod() {
        AgentLinkConfig.load();
        world.agentlink.logs.LogTap.install();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onCommonSetup(FMLCommonSetupEvent event) {
        LOG.info("agent-link {} initializing", AgentLinkConfig.get().version());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        var cfg = AgentLinkConfig.get();
        server = new AgentLinkServer(event.getServer(), cfg);
        try {
            server.start();
            world.agentlink.events.ForgeEventBridge.serverSupplier = () -> server;
            LOG.info("agent-link listening on {}:{}",
                    cfg.allowRemote() ? "0.0.0.0" : "127.0.0.1",
                    cfg.listenPort());
        } catch (Exception e) {
            LOG.error("agent-link failed to start", e);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (server != null) {
            try {
                server.shutdown();
            } catch (Exception e) {
                LOG.warn("agent-link shutdown error", e);
            }
        }
    }
}
