package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.EntityType;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Generic registry-paginator. {@link RegistryListTool#name()} delegates to a constructor flavor. */
public class RegistryListTool implements Tool {

    public enum Kind {
        BLOCK("list_block_ids"),
        ITEM("list_item_ids"),
        ENTITY("list_entity_ids"),
        BIOME("list_biome_ids");

        final String toolName;
        Kind(String n) { this.toolName = n; }
    }

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;

    private final Kind kind;

    public RegistryListTool(Kind kind) {
        this.kind = kind;
    }

    @Override
    public String name() {
        return kind.toolName;
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String filter = args.has("filter") && !args.get("filter").isJsonNull()
                ? args.get("filter").getAsString().trim().toLowerCase(Locale.ROOT)
                : "";
        int page = args.has("page") && !args.get("page").isJsonNull() ? args.get("page").getAsInt() : 0;
        if (page < 0) page = 0;
        int pageSize = args.has("page_size") && !args.get("page_size").isJsonNull()
                ? args.get("page_size").getAsInt() : DEFAULT_PAGE_SIZE;
        if (pageSize <= 0) pageSize = DEFAULT_PAGE_SIZE;
        if (pageSize > MAX_PAGE_SIZE) pageSize = MAX_PAGE_SIZE;

        List<String> matches = collectMatches(filter);

        int totalMatches = matches.size();
        int from = Math.min(page * pageSize, totalMatches);
        int to = Math.min(from + pageSize, totalMatches);

        JsonArray ids = new JsonArray();
        for (int i = from; i < to; i++) ids.add(matches.get(i));

        JsonObject r = new JsonObject();
        r.addProperty("registry", kind.name().toLowerCase(Locale.ROOT));
        r.addProperty("filter", filter);
        r.addProperty("total_matches", totalMatches);
        r.addProperty("page", page);
        r.addProperty("page_size", pageSize);
        r.addProperty("returned", ids.size());
        r.addProperty("has_more", to < totalMatches);
        r.add("ids", ids);
        return r;
    }

    private List<String> collectMatches(String filter) {
        List<String> matches = new ArrayList<>();
        switch (kind) {
            case BLOCK -> append(matches, BuiltInRegistries.BLOCK, filter);
            case ITEM -> append(matches, BuiltInRegistries.ITEM, filter);
            case ENTITY -> append(matches, BuiltInRegistries.ENTITY_TYPE, filter);
            case BIOME -> {
                // BIOME registry is dynamic; this falls back to vanilla static registry which still
                // covers >95% of cases. Datapack-added biomes won't show up.
                for (var key : net.minecraft.world.level.biome.Biomes.class.getFields()) {
                    try {
                        Object val = key.get(null);
                        if (val instanceof net.minecraft.resources.ResourceKey<?> rk) {
                            String s = rk.location().toString();
                            if (filter.isEmpty() || s.contains(filter)) matches.add(s);
                        }
                    } catch (IllegalAccessException ignored) {}
                }
                java.util.Collections.sort(matches);
            }
        }
        return matches;
    }

    private static <T> void append(List<String> out, Registry<T> registry, String filter) {
        for (ResourceLocation rl : registry.keySet()) {
            String s = rl.toString();
            if (filter.isEmpty() || s.contains(filter)) out.add(s);
        }
        java.util.Collections.sort(out);
    }
}
