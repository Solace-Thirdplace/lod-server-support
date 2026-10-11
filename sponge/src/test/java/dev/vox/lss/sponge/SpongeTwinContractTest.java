package dev.vox.lss.sponge;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Sponge tree's same-FQN twins (sponge-support-plan.md §5.1). {@code ScopedCarrier} is
 * loader-surface-free, so it is BYTE-identical to this line's fabric copy (its line flavor
 * travels with it). {@code ChunkSaveDataHook} differs between loaders in javadoc and
 * imports already, so it is pinned by its injection contract instead (the
 * SaveHookContractTest shape): the same target class, method, RETURN point and soft
 * {@code require = 0} as this line's fabric hook.
 */
class SpongeTwinContractTest {

    private static byte[] bytes(String rel) {
        return SpongeTestSupport.read(SpongeTestSupport.repoRoot().resolve(rel)).getBytes();
    }

    @Test
    void scopedCarrierIsByteIdenticalToTheFabricTwin() {
        String rel = "src/main/java/dev/vox/lss/compat/ScopedCarrier.java";
        assertArrayEquals(bytes("fabric/" + rel), bytes("sponge/" + rel),
                "ScopedCarrier twins drifted — apply the change to every tree");
    }

    private static String group(Pattern p, String text, String what) {
        Matcher m = p.matcher(text);
        assertTrue(m.find(), what + " not found");
        return m.group(1);
    }

    @Test
    void chunkSaveDataHookCarriesTheFabricInjectionContract() {
        String rel = "src/main/java/dev/vox/lss/mixin/ChunkSaveDataHook.java";
        String fabric = SpongeTestSupport.read(SpongeTestSupport.repoRoot().resolve("fabric/" + rel));
        String sponge = SpongeTestSupport.read(SpongeTestSupport.repoRoot().resolve("sponge/" + rel));
        var mixin = Pattern.compile("@Mixin\\(([\\w.]+)\\.class\\)");
        // The annotation body up to the method declaration (may span lines on some lines).
        var inject = Pattern.compile("@Inject\\((.*?)\\)\\s*(?:private|public)", Pattern.DOTALL);
        assertEquals(group(mixin, fabric, "fabric @Mixin"), group(mixin, sponge, "sponge @Mixin"));
        String fabricInject = group(inject, fabric, "fabric @Inject").replaceAll("\\s+", "");
        String spongeInject = group(inject, sponge, "sponge @Inject").replaceAll("\\s+", "");
        assertEquals(fabricInject, spongeInject);
        assertTrue(spongeInject.contains("require=0"), "a missing save target must degrade, never crash");
        assertTrue(sponge.contains("LSSServerNetworking.onChunkSaveData("), "the body delegates to the glue");
    }
}
