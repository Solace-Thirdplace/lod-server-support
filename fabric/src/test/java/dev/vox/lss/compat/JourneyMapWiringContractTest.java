package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vox.lss.testutil.SourcePaths;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * Wiring pins for JourneyMap map ingestion (the {@link XaeroWiringContractTest} family):
 * the backend hangs off {@code ModCompat} call sites in shared glue — delete one and every
 * {@code JourneyMapSessionTest} case stays green while the live backend never runs (never
 * detected, never pumped, or a disconnect that leaks the session's queue into the next
 * server's map). Source pins, loader-neutral.
 */
class JourneyMapWiringContractTest {

    private static String modCompat() throws IOException {
        return Files.readString(SourcePaths.mainSource("dev/vox/lss/compat/ModCompat.java"));
    }

    @Test
    void modCompatForwardsToTheBackendOnlyWhenJourneyMapIsInstalled() throws IOException {
        String src = modCompat();
        assertTrue(src.contains("isModLoaded(\"journeymap\")"),
                "init must gate on the journeymap mod id (identical on both loaders)");
        for (String call : new String[]{"JourneyMapCompat.init()", "JourneyMapCompat.clientTick()",
                "JourneyMapCompat.onDisconnect()", "JourneyMapCompat.retireClientAcquisition()",
                "JourneyMapCompat.isArmed()", "JourneyMapCompat.isSupported()", "JourneyMapCompat.diagLine()",
                "JourneyMapCompat.cachedStatus(discoveryComplete)"}) {
            assertTrue(src.contains(call), "ModCompat lost " + call);
        }
        // Every forward is gated: without JourneyMap installed its classes must never load.
        for (String line : src.split("\n")) {
            if (line.contains("JourneyMapCompat.") && !line.contains("journeyMapLoaded")
                    && !line.contains("JourneyMapCompat.init()")) {
                throw new AssertionError("an ungated JourneyMap forward: " + line.strip());
            }
        }
    }

    @Test
    void theAliasGateCountsBothMapStores() throws IOException {
        String src = modCompat();
        int body = src.indexOf("public static boolean isMapStoreArmed()");
        assertTrue(body >= 0);
        String method = src.substring(body, src.indexOf('}', body));
        assertTrue(method.contains("XaeroMapCompat.isArmed()") && method.contains("JourneyMapCompat.isArmed()"),
                "an armed JourneyMap must veto alias keying exactly like Xaero");
    }

    @Test
    void theMenuSupportFactSeesJourneyMap() throws IOException {
        String ctx = Files.readString(SourcePaths.mainSource("dev/vox/lss/config/menu/MenuContext.java"));
        assertTrue(ctx.contains("ModCompat.isJourneyMapSupported()"),
                "the map-ingestion option must be enabled when only JourneyMap is installed");
    }

    @Test
    void statusAndDiagCarryJourneyMap() throws IOException {
        String status = Files.readString(SourcePaths.mainSource("dev/vox/lss/networking/client/ClientStatus.java"));
        assertTrue(status.contains("ModCompat.cachedJourneyMapStatus()"));
        assertTrue(status.contains("ModCompat.journeyMapDiagLine()"));
        String actions = Files.readString(SourcePaths.mainSource("dev/vox/lss/networking/client/ClientCommandActions.java"));
        assertTrue(actions.contains("details.journeyMapDiagLine()"), "/lss diag must print the JourneyMap line");
    }

    /** The height facts are PER-LINE data (26.x: getMinY/getMaxY()+1, exclusive) — edited on
     *  each line's port, pinned here because Tier 1 cannot construct a ClientLevel. */
    @Test
    void theLineSurfacePassesThisLinesHeightExpression() throws IOException {
        String src = Files.readString(SourcePaths.mainSource("dev/vox/lss/compat/JourneyMapLineSurface.java"));
        assertTrue(src.contains("level.getMinSectionY(), level.getSectionsCount(), level.getMinY(), level.getMaxY() + 1"),
                "a lost +1 writes an inclusive max: a build-limit top renders as void (plan review M2)");
    }
}
