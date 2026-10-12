package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * "Is a map the only reader?" (post-0.17 review): map ingestion is on in new settings files, so
 * a client with only a map mod downloads LODs. Two decisions must tell that apart from Voxy (or
 * another LSSApi reader): the LAN host self-handshake (map ingestion is multiplayer-only — a
 * map-only host must not generate a LOD disc around itself) and the column cache's map-only mark
 * (a map-only download must not answer up_to_date for a later Voxy install).
 */
class ModCompatMapConsumersTest {
    private final VoxelColumnConsumer other = (level, dimension, x, z, data) -> {};
    private XaeroSession bridge;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @AfterEach
    void tearDown() {
        LSSApi.removeColumnConsumer(this.other);
        if (this.bridge != null) LSSApi.removeColumnConsumer(this.bridge.consumer);
        XaeroSession.resetFacadeForTest();
    }

    private void installXaero() throws Exception {
        this.bridge = new XaeroSession(XaeroBindings.resolve(Class::forName), null,
                () -> true, () -> true, LSSApi::registerColumnConsumer, LSSApi::removeColumnConsumer,
                () -> true, (d, x, z) -> {});
        XaeroSession.instance = this.bridge;
        this.bridge.maybeRegister();
    }

    @Test
    void noConsumersIsNeitherMapOnlyNorAnotherReader() {
        assertFalse(LSSApi.hasVoxelConsumers(), "premise: no consumer leaked from another suite");
        assertFalse(ModCompat.isMapOnlyIngestion());
        assertFalse(ModCompat.hasNonMapConsumers());
    }

    @Test
    void aMapAloneIsMapOnly() throws Exception {
        installXaero();
        assertTrue(ModCompat.isMapOnlyIngestion());
        assertFalse(ModCompat.hasNonMapConsumers(), "a map-only LAN host stays silent");
    }

    @Test
    void anotherReaderBesideTheMapIsNotMapOnly() throws Exception {
        installXaero();
        LSSApi.registerColumnConsumer(this.other);
        assertFalse(ModCompat.isMapOnlyIngestion());
        assertTrue(ModCompat.hasNonMapConsumers());
    }

    @Test
    void aReaderWithoutAnyMapIsNotMapOnly() {
        LSSApi.registerColumnConsumer(this.other);
        assertFalse(ModCompat.isMapOnlyIngestion());
        assertTrue(ModCompat.hasNonMapConsumers());
    }

    @Test
    void theLanHostHandshakeAndTheCacheMarkUseThesePredicates() throws Exception {
        String glue = Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ClientNetGlue.java"));
        int trigger = glue.indexOf("public static void triggerHostHandshake()");
        String body = glue.substring(trigger, glue.indexOf("\n    }\n", trigger));
        assertTrue(body.contains("hostHandshakeHasConsumer()"), body);
        assertFalse(body.contains("LSSApi.hasVoxelConsumers()"), "a map consumer alone must not trigger it");
        assertTrue(glue.contains("return dev.vox.lss.compat.ModCompat.hasNonMapConsumers();"));
        String cache = Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ColumnCacheStore.java"));
        assertTrue(cache.contains("dev.vox.lss.compat.ModCompat::isMapOnlyIngestion"));
    }
}
