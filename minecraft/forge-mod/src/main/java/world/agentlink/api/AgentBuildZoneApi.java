package world.agentlink.api;

import world.agentlink.sandbox.BuildZones;

import java.util.ArrayList;
import java.util.List;

/** Read-only view of the operator-declared build-zone geometry. */
public final class AgentBuildZoneApi {

    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }

        public boolean contains(Box other) {
            return other != null
                    && contains(other.minX(), other.minY(), other.minZ())
                    && contains(other.maxX(), other.maxY(), other.maxZ());
        }
    }

    public record Zone(String label, String dimension, Box box) {}

    AgentBuildZoneApi() {}

    public List<Zone> all() {
        List<Zone> out = new ArrayList<>();
        for (BuildZones.Zone zone : BuildZones.all()) {
            out.add(fromZone(zone));
        }
        return List.copyOf(out);
    }

    public boolean configured() {
        return BuildZones.anyConfigured();
    }

    public Zone containingPoint(String dimension, int x, int y, int z) {
        return fromZone(BuildZones.findPoint(dimension, x, y, z));
    }

    public Zone containing(String dimension, Box box) {
        if (box == null) return null;
        return fromZone(BuildZones.find(dimension, new world.agentlink.dispatch.ToolArgs.Box(
                box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())));
    }

    public boolean containsPoint(String dimension, int x, int y, int z) {
        return containingPoint(dimension, x, y, z) != null;
    }

    public boolean contains(String dimension, Box box) {
        return containing(dimension, box) != null;
    }

    private static Zone fromZone(BuildZones.Zone zone) {
        if (zone == null) return null;
        var box = zone.box();
        return new Zone(zone.label(), zone.dimension(),
                new Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
    }
}
