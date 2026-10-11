package dev.vox.lss.sponge;

import org.junit.jupiter.api.Test;
import org.spongepowered.api.util.Tristate;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sponge's permission table equals Paper's plugin.yml (sponge-support-plan.md §1.6): both
 * brand spellings of the privacy and service nodes with the same defaults, and the admin
 * node with NO default (Sponge's op-level fallback = Paper's {@code default: op}). Brand is
 * not loaded in plain JUnit, so the admin node reads the LSS spelling, as plugin.yml does.
 */
class SpongePermissionsTest {

    private static Map<String, String> paperDefaults() {
        var out = new TreeMap<String, String>();
        String yml = SpongeTestSupport.read(SpongeTestSupport.repoRoot().resolve("paper/src/main/resources/plugin.yml"));
        String section = yml.substring(yml.indexOf("\npermissions:") + 1);
        String node = null;
        for (String line : section.split("\n")) {
            if (line.matches("^  [a-z.]+:\\s*$")) node = line.strip().replace(":", "");
            else if (node != null && line.strip().startsWith("default:")) out.put(node, line.strip().substring(8).strip());
        }
        return out;
    }

    @Test
    void nodesAndDefaultsMirrorPaperPluginYml() {
        var sponge = new TreeMap<String, String>();
        for (var node : SpongePermissions.nodes()) {
            Tristate d = node.defaultValue();
            sponge.put(node.id(), d == null ? "op" : d == Tristate.TRUE ? "true" : "false");
        }
        assertEquals(paperDefaults(), sponge);
    }
}
