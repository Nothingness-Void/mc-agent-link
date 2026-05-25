package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

public class GetPlayerInfoTool implements Tool {
    private final MinecraftServer mc;

    public GetPlayerInfoTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_player_info";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String name = RequestDispatcher.requireString(args, "name");
        ServerPlayer p = mc.getPlayerList().getPlayerByName(name);
        if (p == null) {
            throw new ToolException("INVALID_ARGS", "Player not online: " + name);
        }

        JsonObject r = new JsonObject();
        r.addProperty("name", p.getGameProfile().getName());
        r.addProperty("uuid", p.getUUID().toString());

        JsonArray pos = new JsonArray();
        pos.add(p.getX());
        pos.add(p.getY());
        pos.add(p.getZ());
        r.add("pos", pos);

        JsonObject blockPos = new JsonObject();
        blockPos.addProperty("x", p.getBlockX());
        blockPos.addProperty("y", p.getBlockY());
        blockPos.addProperty("z", p.getBlockZ());
        r.add("block_pos", blockPos);

        r.addProperty("dim", p.level().dimension().location().toString());

        // Orientation
        float yaw = p.getYRot();
        float pitch = p.getXRot();
        r.addProperty("yaw", yaw);
        r.addProperty("pitch", pitch);
        r.addProperty("facing", facing(yaw));

        Vec3 look = p.getLookAngle();
        JsonObject lookVec = new JsonObject();
        lookVec.addProperty("x", look.x);
        lookVec.addProperty("y", look.y);
        lookVec.addProperty("z", look.z);
        r.add("look_vector", lookVec);
        r.addProperty("look_axis", lookAxis(look));

        // Vitals
        r.addProperty("health", p.getHealth());
        r.addProperty("max_health", p.getMaxHealth());
        r.addProperty("absorption", p.getAbsorptionAmount());
        r.addProperty("food", p.getFoodData().getFoodLevel());
        r.addProperty("saturation", p.getFoodData().getSaturationLevel());
        r.addProperty("air", p.getAirSupply());
        r.addProperty("max_air", p.getMaxAirSupply());
        r.addProperty("on_fire", p.isOnFire());
        r.addProperty("fire_ticks", p.getRemainingFireTicks());
        r.addProperty("on_ground", p.onGround());
        r.addProperty("in_water", p.isInWater());
        r.addProperty("sneaking", p.isShiftKeyDown());
        r.addProperty("sprinting", p.isSprinting());

        // Progression
        r.addProperty("xp_level", p.experienceLevel);
        r.addProperty("xp_progress", p.experienceProgress);
        r.addProperty("xp_total", p.totalExperience);

        // Game mode + meta
        r.addProperty("gamemode", p.gameMode.getGameModeForPlayer().getName());
        r.addProperty("ping", p.latency);
        r.addProperty("op", mc.getPlayerList().isOp(p.getGameProfile()));
        r.addProperty("main_arm", p.getMainArm() == HumanoidArm.RIGHT ? "right" : "left");

        // Held items
        Inventory inv = p.getInventory();
        r.addProperty("selected_slot", inv.selected);
        r.add("held_main", itemSummary(p.getMainHandItem()));
        r.add("held_off", itemSummary(p.getOffhandItem()));

        // Status effects
        JsonArray effects = new JsonArray();
        for (MobEffectInstance e : p.getActiveEffects()) {
            JsonObject eo = new JsonObject();
            ResourceLocation rl = BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect());
            eo.addProperty("id", rl == null ? "minecraft:unknown" : rl.toString());
            eo.addProperty("amplifier", e.getAmplifier());
            eo.addProperty("duration_ticks", e.getDuration());
            eo.addProperty("ambient", e.isAmbient());
            eo.addProperty("visible", e.isVisible());
            effects.add(eo);
        }
        r.add("effects", effects);

        // Look-target ray (5-block reach for build-context; agents that need more should call raycast).
        BlockHitResult hit = rayBlock(p, 5.0);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = hit.getBlockPos();
            ServerLevel level = (ServerLevel) p.level();
            BlockState state = level.getBlockState(bp);
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            JsonObject lt = new JsonObject();
            JsonObject ltPos = new JsonObject();
            ltPos.addProperty("x", bp.getX());
            ltPos.addProperty("y", bp.getY());
            ltPos.addProperty("z", bp.getZ());
            lt.add("pos", ltPos);
            lt.addProperty("block", blockId == null ? "minecraft:unknown" : blockId.toString());
            lt.addProperty("face", hit.getDirection().getName());
            lt.addProperty("distance", hit.getLocation().distanceTo(p.getEyePosition()));
            r.add("look_target", lt);
        } else {
            r.add("look_target", JsonNull.INSTANCE);
        }

        return r;
    }

    private static JsonObject itemSummary(ItemStack stack) {
        JsonObject o = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            o.addProperty("id", "minecraft:air");
            o.addProperty("count", 0);
            return o;
        }
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        o.addProperty("id", rl == null ? "minecraft:unknown" : rl.toString());
        o.addProperty("count", stack.getCount());
        if (stack.isDamageableItem()) {
            o.addProperty("damage", stack.getDamageValue());
            o.addProperty("max_damage", stack.getMaxDamage());
        }
        if (stack.hasCustomHoverName()) {
            o.addProperty("custom_name", stack.getHoverName().getString());
        }
        if (stack.getTag() != null) {
            o.addProperty("has_nbt", true);
        }
        return o;
    }

    private static String facing(float yaw) {
        // Vanilla yaw: 0 = south, 90 = west, 180 = north, -90/270 = east
        float y = ((yaw % 360) + 360) % 360;
        if (y >= 315 || y < 45) return "south";
        if (y < 135) return "west";
        if (y < 225) return "north";
        return "east";
    }

    private static String lookAxis(Vec3 look) {
        double ax = Math.abs(look.x);
        double ay = Math.abs(look.y);
        double az = Math.abs(look.z);
        if (ay >= ax && ay >= az) return look.y > 0 ? "up" : "down";
        if (ax >= az) return look.x > 0 ? "east" : "west";
        return look.z > 0 ? "south" : "north";
    }

    private static BlockHitResult rayBlock(ServerPlayer p, double distance) {
        Vec3 from = p.getEyePosition();
        Vec3 to = from.add(p.getLookAngle().scale(distance));
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p);
        return p.level().clip(ctx);
    }
}
