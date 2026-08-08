package world.agentlink.events;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import world.agentlink.AgentLinkMod;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.diagnostics.TickIncidentRecorder;

import java.util.function.Supplier;

/**
 * Bridges Forge's server event bus into agent-link.
 *
 * <p>Every event is appended to the {@link EventBuffer} so agents can pull recent activity on
 * demand. If any session has actively subscribed (advanced opt-in), the same event is also pushed
 * live. Default is pull-only — no token spend on idle agents.
 *
 * <h2>Verbose topics and buffer pressure</h2>
 * Block placement and breaking are the events an investigation usually wants, and also the ones a
 * single player with an efficiency-V pickaxe emits hundreds of per minute. Recording them
 * unconditionally would push everything else out of a 4096-entry ring in under a minute, which makes
 * the buffer useless for the questions it answers best. So they are opt-in via
 * {@code events.verbose_topics} in the config, and even when enabled they are rate-limited per
 * player so one mining session cannot starve the rest.
 */
@Mod.EventBusSubscriber(modid = AgentLinkMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeEventBridge {

    /**
     * Set by {@link AgentLinkMod} once the server is up. We avoid a hard reference
     * because @EventBusSubscriber uses static methods.
     */
    public static volatile Supplier<world.agentlink.transport.AgentLinkServer> serverSupplier = () -> null;

    /** Per-player budget for verbose topics, refilled every window. */
    private static final int VERBOSE_PER_WINDOW = 40;
    private static final long VERBOSE_WINDOW_MS = 10_000;
    private static final java.util.Map<java.util.UUID, Budget> VERBOSE_BUDGETS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class Budget {
        private volatile long windowStart;
        private volatile int used;
        private volatile int suppressed;
    }

    private static EventBuffer.Entry record(String topic, JsonObject data) {
        JsonObject payload = data == null ? new JsonObject() : data.deepCopy();
        EventBuffer.Entry entry = EventBuffer.get().append(topic, payload);
        var srv = serverSupplier.get();
        if (srv != null) {
            EventBroadcaster.emit(srv.sessions(), topic, payload);
        }
        return entry;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            TickIncidentRecorder.onTickStart(event.getServer());
        } else if (event.phase == TickEvent.Phase.END) {
            TickIncidentRecorder.onTickEnd(event.getServer());
        }
    }

    /** Public entry so other subsystems (task completion, for instance) can post an event. */
    public static void post(String topic, JsonObject data) {
        record(topic, data);
    }

    /** Public entry for API callers that also need the assigned sequence number. */
    public static EventBuffer.Entry postAndReturn(String topic, JsonObject data) {
        return record(topic, data);
    }

    private static boolean verboseEnabled(String topic) {
        AgentLinkConfig.Snapshot cfg = AgentLinkConfig.get();
        return cfg != null && cfg.eventVerboseTopics().contains(topic);
    }

    /**
     * Rate-limit a verbose event for one actor. Returns true when it should be recorded. The first
     * suppressed event in a window emits a note so the agent knows data is being dropped rather than
     * silently assuming nothing happened.
     */
    private static boolean allowVerbose(java.util.UUID actor, String topic) {
        if (actor == null) return true;
        long now = System.currentTimeMillis();
        Budget budget = VERBOSE_BUDGETS.computeIfAbsent(actor, k -> new Budget());
        synchronized (budget) {
            if (now - budget.windowStart > VERBOSE_WINDOW_MS) {
                if (budget.suppressed > 0) {
                    JsonObject note = new JsonObject();
                    note.addProperty("kind", "verbose_throttled");
                    note.addProperty("actor_uuid", actor.toString());
                    note.addProperty("suppressed", budget.suppressed);
                    note.addProperty("window_ms", VERBOSE_WINDOW_MS);
                    note.addProperty("note", "Verbose events for this player exceeded "
                            + VERBOSE_PER_WINDOW + " per " + (VERBOSE_WINDOW_MS / 1000)
                            + "s and were dropped. Event counts are not a reliable total.");
                    record(EventTopics.SERVER, note);
                }
                budget.windowStart = now;
                budget.used = 0;
                budget.suppressed = 0;
            }
            if (budget.used >= VERBOSE_PER_WINDOW) {
                budget.suppressed++;
                return false;
            }
            budget.used++;
            return true;
        }
    }

    // ------------------------------------------------------------------ 0.4.x topics

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        ServerPlayer p = event.getPlayer();
        JsonObject d = base(p);
        d.addProperty("message", event.getMessage().getString());
        record(EventTopics.CHAT, d);
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        JsonObject d = base(p);
        if (p.connection != null && p.connection.connection != null) {
            d.addProperty("address", String.valueOf(p.connection.connection.getRemoteAddress()));
        }
        d.addProperty("dim", dimOf(p));
        addPos(d, p.blockPosition());
        record(EventTopics.PLAYER_JOIN, d);
    }

    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        JsonObject d = base(p);
        d.addProperty("dim", dimOf(p));
        addPos(d, p.blockPosition());
        record(EventTopics.PLAYER_LEAVE, d);
        VERBOSE_BUDGETS.remove(p.getUUID());
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof ServerPlayer p) {
            JsonObject d = base(p);
            d.addProperty("cause", event.getSource().getMsgId());
            d.addProperty("dim", dimOf(p));
            addPos(d, p.blockPosition());
            Entity killer = event.getSource().getEntity();
            if (killer != null) {
                d.addProperty("killer", killer.getName().getString());
                d.addProperty("killer_type", typeOf(killer));
                d.addProperty("killer_uuid", killer.getUUID().toString());
            }
            // The full death message is what a player sees in chat and reads better in a report
            // than a raw damage-source id.
            d.addProperty("death_message", event.getSource()
                    .getLocalizedDeathMessage(p).getString());
            record(EventTopics.PLAYER_DEATH, d);
            return;
        }
        // Non-player deaths: only record ones a player caused, otherwise every zombie burning at
        // dawn floods the buffer.
        Entity killer = event.getSource().getEntity();
        if (!(killer instanceof Player)) return;
        JsonObject d = new JsonObject();
        d.addProperty("entity_type", typeOf(dead));
        d.addProperty("entity_uuid", dead.getUUID().toString());
        if (dead.hasCustomName()) d.addProperty("custom_name", dead.getCustomName().getString());
        d.addProperty("cause", event.getSource().getMsgId());
        d.addProperty("killer", killer.getName().getString());
        d.addProperty("killer_uuid", killer.getUUID().toString());
        d.addProperty("dim", dimOf(dead));
        addPos(d, dead.blockPosition());
        record(EventTopics.ENTITY_DEATH, d);
    }

    // ------------------------------------------------------------------ 0.5.0 topics

    /**
     * Command usage. The highest-value audit topic: "who ran /gamemode creative" is the first
     * question in most incident reports, and previously agent-link could not answer it at all.
     */
    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        try {
            var source = event.getParseResults().getContext().getSource();
            String input = event.getParseResults().getReader().getString();
            JsonObject d = new JsonObject();
            d.addProperty("command", input);
            d.addProperty("source", source.getTextName());
            ServerPlayer p = source.getPlayer();
            if (p != null) {
                d.addProperty("player", p.getGameProfile().getName());
                d.addProperty("uuid", p.getUUID().toString());
                d.addProperty("dim", dimOf(p));
                addPos(d, p.blockPosition());
                d.addProperty("is_op", p.hasPermissions(2));
            } else {
                d.addProperty("player", "@console");
            }
            d.addProperty("cancelled", event.isCanceled());
            record(EventTopics.COMMAND, d);
        } catch (Throwable ignored) {
            // Never let telemetry break command dispatch.
        }
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        JsonObject d = base(p);
        d.addProperty("dim", dimOf(p));
        addPos(d, p.blockPosition());
        var menu = event.getContainer();
        if (menu != null) {
            ResourceLocation type = BuiltInRegistries.MENU.getKey(menu.getType());
            d.addProperty("menu_type", type == null ? "unknown" : type.toString());
        }
        record(EventTopics.CONTAINER_OPEN, d);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        JsonObject d = base(p);
        d.addProperty("end_conquered", event.isEndConquered());
        d.addProperty("dim", dimOf(p));
        addPos(d, p.blockPosition());
        record(EventTopics.PLAYER_RESPAWN, d);
    }

    @SubscribeEvent
    public static void onDimensionChange(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        JsonObject d = base(p);
        d.addProperty("from_dim", dimOf(p));
        d.addProperty("to_dim", event.getDimension().location().toString());
        addPos(d, p.blockPosition());
        record(EventTopics.DIMENSION_CHANGE, d);
    }

    @SubscribeEvent
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        var advancement = event.getAdvancement();
        // Recipe unlocks fire constantly and are not interesting progression signals.
        if (advancement == null || advancement.getId().getPath().startsWith("recipes/")) return;
        JsonObject d = base(p);
        d.addProperty("advancement", advancement.getId().toString());
        if (advancement.getDisplay() != null) {
            d.addProperty("title", advancement.getDisplay().getTitle().getString());
        }
        record(EventTopics.ADVANCEMENT, d);
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        try {
            var explosion = event.getExplosion();
            JsonObject d = new JsonObject();
            var pos = explosion.getPosition();
            d.addProperty("x", pos.x);
            d.addProperty("y", pos.y);
            d.addProperty("z", pos.z);
            d.addProperty("dim", event.getLevel().dimension().location().toString());
            d.addProperty("blocks_destroyed", event.getAffectedBlocks().size());
            d.addProperty("entities_affected", event.getAffectedEntities().size());
            Entity source = explosion.getDirectSourceEntity();
            if (source != null) {
                d.addProperty("source_type", typeOf(source));
                d.addProperty("source_uuid", source.getUUID().toString());
            }
            LivingEntity cause = explosion.getIndirectSourceEntity();
            if (cause != null) {
                d.addProperty("caused_by", cause.getName().getString());
                d.addProperty("caused_by_uuid", cause.getUUID().toString());
            }
            record(EventTopics.EXPLOSION, d);
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onPlayerHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        // Only meaningful damage; chip damage from fall ticks and starvation is noise.
        if (event.getAmount() < 1.0f) return;
        JsonObject d = base(p);
        d.addProperty("amount", event.getAmount());
        d.addProperty("health_before", p.getHealth());
        d.addProperty("cause", event.getSource().getMsgId());
        Entity attacker = event.getSource().getEntity();
        if (attacker != null) {
            d.addProperty("attacker", attacker.getName().getString());
            d.addProperty("attacker_type", typeOf(attacker));
        }
        d.addProperty("dim", dimOf(p));
        addPos(d, p.blockPosition());
        record(EventTopics.PLAYER_HURT, d);
    }

    // ------------------------------------------------------------------ verbose topics

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!verboseEnabled(EventTopics.BLOCK_PLACE)) return;
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        if (!allowVerbose(p.getUUID(), EventTopics.BLOCK_PLACE)) return;
        JsonObject d = base(p);
        d.addProperty("block", blockIdOf(event.getPlacedBlock()));
        d.addProperty("replaced", blockIdOf(event.getBlockSnapshot().getReplacedBlock()));
        d.addProperty("dim", dimName(event.getLevel()));
        addPos(d, event.getPos());
        record(EventTopics.BLOCK_PLACE, d);
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!verboseEnabled(EventTopics.BLOCK_BREAK)) return;
        Player player = event.getPlayer();
        if (!(player instanceof ServerPlayer p)) return;
        if (!allowVerbose(p.getUUID(), EventTopics.BLOCK_BREAK)) return;
        JsonObject d = base(p);
        d.addProperty("block", blockIdOf(event.getState()));
        d.addProperty("dim", dimName(event.getLevel()));
        addPos(d, event.getPos());
        record(EventTopics.BLOCK_BREAK, d);
    }

    @SubscribeEvent
    public static void onItemPickup(EntityItemPickupEvent event) {
        if (!verboseEnabled(EventTopics.ITEM_PICKUP)) return;
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        if (!allowVerbose(p.getUUID(), EventTopics.ITEM_PICKUP)) return;
        var stack = event.getItem().getItem();
        JsonObject d = base(p);
        d.addProperty("item", itemIdOf(stack));
        d.addProperty("count", stack.getCount());
        d.addProperty("dim", dimOf(p));
        addPos(d, p.blockPosition());
        record(EventTopics.ITEM_PICKUP, d);
    }

    @SubscribeEvent
    public static void onItemDrop(ItemTossEvent event) {
        if (!verboseEnabled(EventTopics.ITEM_DROP)) return;
        if (!(event.getPlayer() instanceof ServerPlayer p)) return;
        if (!allowVerbose(p.getUUID(), EventTopics.ITEM_DROP)) return;
        var stack = event.getEntity().getItem();
        JsonObject d = base(p);
        d.addProperty("item", itemIdOf(stack));
        d.addProperty("count", stack.getCount());
        d.addProperty("dim", dimOf(p));
        addPos(d, p.blockPosition());
        record(EventTopics.ITEM_DROP, d);
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject base(ServerPlayer p) {
        JsonObject d = new JsonObject();
        d.addProperty("player", p.getGameProfile().getName());
        d.addProperty("uuid", p.getUUID().toString());
        return d;
    }

    private static void addPos(JsonObject d, BlockPos pos) {
        if (pos == null) return;
        d.addProperty("x", pos.getX());
        d.addProperty("y", pos.getY());
        d.addProperty("z", pos.getZ());
    }

    private static String dimOf(Entity e) {
        return e.level().dimension().location().toString();
    }

    private static String dimName(net.minecraft.world.level.LevelAccessor level) {
        if (level instanceof Level l) return l.dimension().location().toString();
        return "unknown";
    }

    private static String typeOf(Entity e) {
        ResourceLocation rl = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return rl == null ? "unknown" : rl.toString();
    }

    private static String blockIdOf(BlockState state) {
        if (state == null) return "unknown";
        ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return rl == null ? "unknown" : rl.toString();
    }

    private static String itemIdOf(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "minecraft:air";
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return rl == null ? "unknown" : rl.toString();
    }
}
