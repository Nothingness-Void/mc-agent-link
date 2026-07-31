package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.sandbox.BuildZones;
import world.agentlink.transport.ClientSession;
import world.agentlink.world.BlockWriter;

/**
 * Place one block, with undo.
 *
 * <p>Accepts the full vanilla block grammar including properties and block-entity NBT, so
 * {@code minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",Count:1b}]}} places a
 * populated, correctly-oriented chest in a single call. Previously this needed
 * {@code run_console_command "/setblock ..."} — which gave no undo, no structured result, and had
 * to be approved as an arbitrary console command.
 */
public class SetBlockTool implements Tool {

    private final MinecraftServer mc;

    public SetBlockTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "set_block";
    }

    /**
     * Declared footprint for the build-zone check. Runs on the transport thread before approval,
     * so it must not touch world state — parsing coordinates is safe, resolving the level is not
     * needed here since we only compare ids.
     */
    @Override
    public void declareScope(JsonObject args) {
        try {
            ToolArgs.IntPos pos = args.has("pos")
                    ? ToolArgs.requireIntPos(args, "pos")
                    : ToolArgs.requireFlatIntPos(args);
            String dim = ToolArgs.optString(args, "dim", Dimensions.DEFAULT);
            BuildZones.declareScope(args, dim, ToolArgs.box(pos, pos));
        } catch (ToolException ignored) {
            // Malformed args: no scope declared, so the call falls through to normal approval and
            // the tool body reports the real validation error.
        }
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = Dimensions.resolve(mc, args);
        ToolArgs.IntPos p = args.has("pos")
                ? ToolArgs.requireIntPos(args, "pos")
                : ToolArgs.requireFlatIntPos(args);
        String spec = ToolArgs.requireString(args, "block");
        BlockWriter.ParsedBlock parsed = BlockWriter.parseBlock(spec);

        BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
        if (!level.isInWorldBounds(pos)) {
            throw new ToolException("INVALID_ARGS",
                    "Position is outside the world height range (" + level.getMinBuildHeight()
                            + ".." + (level.getMinBuildHeight() + level.getHeight() - 1) + ")");
        }

        String before = BlockWriter.idOf(level.getBlockState(pos));
        BlockWriter.Batch batch = new BlockWriter.Batch(level, "set_block " + spec, true);
        boolean changed = batch.set(pos, parsed.state(), parsed.nbt());
        batch.commit();

        JsonObject r = new JsonObject();
        r.addProperty("dim", Dimensions.idOf(level));
        r.add("pos", p.toJson());
        r.addProperty("block", BlockWriter.idOf(parsed.state()));
        r.addProperty("previous_block", before);
        r.addProperty("changed", changed);
        r.addProperty("undo_depth", BlockWriter.undoDepth());
        return r;
    }
}
