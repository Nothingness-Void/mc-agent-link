package world.agentlink.spigot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModernCompatibilityTest {
    @Test
    void parsesBukkitAndPaperVersionStrings() {
        assertEquals(new ModernCompatibility.Version(1, 20, 1),
                ModernCompatibility.parseMinecraftVersion("1.20.1-R0.1-SNAPSHOT"));
        assertEquals(new ModernCompatibility.Version(1, 21, 11),
                ModernCompatibility.parseMinecraftVersion("git-Paper-123 (MC: 1.21.11)"));
    }

    @Test
    void rejectsMissingOrMalformedVersions() {
        assertNull(ModernCompatibility.parseMinecraftVersion(null));
        assertNull(ModernCompatibility.parseMinecraftVersion("not-a-minecraft-version"));
    }

    @Test
    void comparesModernVersionBoundaries() {
        assertEquals(0, new ModernCompatibility.Version(1, 20, 0)
                .compareTo(new ModernCompatibility.Version(1, 20, 0)));
        assertEquals(1, new ModernCompatibility.Version(1, 21, 1)
                .compareTo(new ModernCompatibility.Version(1, 20, 6)));
    }
}
