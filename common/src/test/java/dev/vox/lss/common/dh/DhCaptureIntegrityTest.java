package dev.vox.lss.common.dh;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/** Producer hashes are frozen independently from decoder expectations. */
class DhCaptureIntegrityTest {
    @Test void everyFrozenFrameAndDtoMatchesItsRecordedProducerHash() throws Exception {
        var loader=getClass();
        try(var in=loader.getResourceAsStream("/dh-corpus/capture-sha256.json")) {
            assertNotNull(in);
            var entries=JsonParser.parseString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(entries.size()>=34);
            for(var entry:entries.entrySet())try(var capture=loader.getResourceAsStream("/dh-corpus/"+entry.getKey())) {
                assertNotNull(capture,entry.getKey());
                assertEquals(entry.getValue().getAsString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(capture.readAllBytes())),entry.getKey());
            }
        }
    }
}
