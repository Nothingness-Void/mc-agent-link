package world.agentlink.events;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import world.agentlink.AgentLinkMod;

import java.util.function.Supplier;

/**
 * Bridges Forge's server event bus into agent-link event frames.
 * Registered against {@link Mod.EventBusSubscriber.Bus#FORGE} since these are runtime
 * (gameplay) events, not mod-loading events.
 */
@Mod.EventBusSubscriber(modid = AgentLinkMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeEventBridge {

    /**
     * Set by {@link AgentLinkMod} once the server is up. We avoid a hard reference
     * because @EventBusSubscriber uses static methods.
     */
    public static volatile Supplier<world.agentlink.transport.AgentLinkServer> serverSupplier = () -> null;

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        var srv = serverSupplier.get();
        if (srv == null) return;
        ServerPlayer p = event.getPlayer();
        JsonObject d = new JsonObject();
        d.addProperty("player", p.getGameProfile().getName());
        d.addProperty("uuid", p.getUUID().toString());
        d.addProperty("message", event.getMessage().getString());
        EventBroadcaster.emit(srv.sessions(), EventTopics.CHAT, d);
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        var srv = serverSupplier.get();
        if (srv == null) return;
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        JsonObject d = new JsonObject();
        d.addProperty("player", p.getGameProfile().getName());
        d.addProperty("uuid", p.getUUID().toString());
        if (p.connection != null && p.connection.connection != null) {
            d.addProperty("address", String.valueOf(p.connection.connection.getRemoteAddress()));
        }
        EventBroadcaster.emit(srv.sessions(), EventTopics.PLAYER_JOIN, d);
    }

    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        var srv = serverSupplier.get();
        if (srv == null) return;
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        JsonObject d = new JsonObject();
        d.addProperty("player", p.getGameProfile().getName());
        d.addProperty("uuid", p.getUUID().toString());
        EventBroadcaster.emit(srv.sessions(), EventTopics.PLAYER_LEAVE, d);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        var srv = serverSupplier.get();
        if (srv == null) return;
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        JsonObject d = new JsonObject();
        d.addProperty("player", p.getGameProfile().getName());
        d.addProperty("uuid", p.getUUID().toString());
        d.addProperty("cause", event.getSource().getMsgId());
        if (event.getSource().getEntity() != null) {
            d.addProperty("killer", event.getSource().getEntity().getName().getString());
        }
        EventBroadcaster.emit(srv.sessions(), EventTopics.PLAYER_DEATH, d);
    }
}
