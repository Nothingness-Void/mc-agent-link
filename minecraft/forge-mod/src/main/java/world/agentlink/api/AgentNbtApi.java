package world.agentlink.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import world.agentlink.dispatch.ToolException;
import world.agentlink.nbt.NbtJson;

import java.util.List;

/** Lossless SNBT, JSON projection, and vanilla NBT-path operations for addon mods. */
public final class AgentNbtApi {

    AgentNbtApi() {}

    /** Convert a native tag to the same agent-readable JSON projection used by MCP tools. */
    public JsonElement toJson(Tag tag) {
        return NbtJson.toJson(tag);
    }

    /** Convert a native tag to canonical, lossless SNBT. */
    public String toSnbt(Tag tag) {
        return NbtJson.toSnbt(tag);
    }

    /** Return both the JSON projection and canonical SNBT in the standard tool envelope. */
    public JsonObject envelope(Tag tag) {
        return NbtJson.envelope(tag);
    }

    /** Parse a compound using Minecraft's own SNBT grammar. */
    public CompoundTag parseCompound(String snbt) throws AgentApiException {
        try {
            return NbtJson.parseSnbtCompound(snbt);
        } catch (ToolException ex) {
            throw translate(ex);
        }
    }

    /** Parse any scalar, list, or compound SNBT value. */
    public Tag parseValue(String snbt) throws AgentApiException {
        try {
            return NbtJson.parseSnbtValue(snbt);
        } catch (ToolException ex) {
            throw translate(ex);
        }
    }

    /** Convert an agent JSON value back to a native tag, honoring the {@code __types} hints. */
    public Tag fromJson(JsonElement value) throws AgentApiException {
        try {
            return NbtJson.fromJson(value);
        } catch (ToolException ex) {
            throw translate(ex);
        }
    }

    /** Compile a vanilla NBT path such as {@code Items[0].tag.display.Name}. */
    public NbtPathArgument.NbtPath parsePath(String path) throws AgentApiException {
        try {
            return NbtJson.parsePath(path);
        } catch (ToolException ex) {
            throw translate(ex);
        }
    }

    /** Resolve a vanilla NBT path. An empty result means that the path matched nothing. */
    public List<Tag> resolvePath(Tag root, String path) throws AgentApiException {
        try {
            return NbtJson.resolvePath(root, path);
        } catch (ToolException ex) {
            throw translate(ex);
        }
    }

    private static AgentApiException translate(ToolException ex) {
        return new AgentApiException(ex.code(), ex.getMessage(), ex);
    }
}
