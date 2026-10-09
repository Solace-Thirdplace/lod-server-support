package dev.vox.lss.common.diagnostics;

import com.google.gson.JsonParser;
import dev.vox.lss.common.config.SettingsSchema;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientStatusTextTest {
    @Test void everyRenderedStateAndArgumentHasAllThreeTranslations() throws Exception {
        var languages = new ArrayList<com.google.gson.JsonObject>();
        Path root=Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("fabric/src/main/resources/assets/lss/lang"))) root=root.getParent();
        for (String locale : List.of("en_us", "zh_cn", "zh_tw"))
            languages.add(JsonParser.parseString(Files.readString(root.resolve("fabric/src/main/resources/assets/lss/lang/"+locale+".json"))).getAsJsonObject());
        check(ClientStatusText.lines(null,0), languages);
        for (var status : dev.vox.lss.common.config.SettingsReload.Status.values())
            check(List.of(new ClientStatusText.Message(ClientReloadText.outcomeKey(status),List.of())),languages);
        check(List.of(ClientReloadText.normalization(new dev.vox.lss.common.config.SettingsSchema.Normalization("lod.distance_chunks",-1,0))),languages);
        var defaults=SettingsSchema.client().defaults();
        var settings=ClientSettingsStatus.capture(true,defaults,defaults,defaults,Set.of("integrations.xaero_map.enabled"));
        for (var discovery : ClientStatusSnapshot.Discovery.values())
            for (var integration : ClientStatusSnapshot.Availability.values())
                for (boolean active : new boolean[]{false,true}) {
                    var snapshot=new ClientStatusSnapshot(2,1,100,active,active,active,active,active,active,
                            20,512,128,123,456,2,3,0,20,1,4,discovery,integration,null,DiagnosticVersions.unknown(),settings);
                    check(ClientStatusText.lines(snapshot,90),languages);
                }
        for (var reason : ClientStatusSnapshot.Reason.values()) check(List.of(new ClientStatusText.Message(
                "lss.status.reason."+reason.name().toLowerCase(Locale.ROOT),List.of())),languages);
        for(var reason:DhStatusSnapshot.Reason.values())check(List.of(new ClientStatusText.Message("lss.status.dh.reason."+reason.name().toLowerCase(Locale.ROOT),List.of())),languages);
        for(var gate:dev.vox.lss.common.dh.DhAcquisition.Gate.values())check(List.of(new ClientStatusText.Message("lss.status.dh.wait."+gate.name().toLowerCase(Locale.ROOT),List.of())),languages);
        for(var state:DhStatusSnapshot.State.values()) for(var profile:dev.vox.lss.common.dh.DhProfile.values())
            for(boolean sync:new boolean[]{false,true}) for(boolean live:new boolean[]{false,true}) {
                var dh=new DhStatusSnapshot(profile,state,10,5,2,1,50,45,3,2,1000,100,200,1,2,3,32,1,2,500,true,sync,live,sync,0,0,100,4096,0,0,0,0,DhStatusSnapshot.Reason.NONE,0,0,dev.vox.lss.common.dh.DhAcquisition.Gate.NO_ELIGIBLE_WORK,1L<<25,-1);
                var snapshot=new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION,1,100,true,false,true,false,true,false,
                        0,0,0,0,0,0,-1,0,0,0,0,ClientStatusSnapshot.Discovery.DORMANT,
                        ClientStatusSnapshot.Availability.DISABLED,null,DiagnosticVersions.unknown(),settings,dh);
                check(ClientStatusText.lines(snapshot,200),languages);
                assertTrue(ClientStatusText.lines(snapshot,200).stream().noneMatch(m->m.key().equals("lss.status.renderer")
                        || m.key().equals("lss.status.xaero") || m.key().equals("lss.status.connection")));
                String json=DiagnosticExport.clientJson(snapshot);
                assertTrue(json.contains("\"dh\""));assertTrue(json.contains("\"protocol\": 0"));
                assertTrue(snapshot.lines().getFirst().contains("Distant Horizons"));
                assertTrue(snapshot.lines().stream().anyMatch(line->line.contains("unacknowledged")));
                assertTrue(snapshot.lines().stream().anyMatch(line->line.startsWith("Versions:")));
                assertTrue(snapshot.lines().stream().anyMatch(line->line.startsWith("Settings:")));
            }
    }
    private void check(List<?> values,List<com.google.gson.JsonObject> languages) {
        for(Object value:values) {
            if(value instanceof ClientStatusText.Message message) {
                for(var language:languages) {
                    assertTrue(language.has(message.key()), message.key());
                    String text=language.get(message.key()).getAsString();
                    assertEquals(message.arguments().size(), text.split("%s",-1).length-1, message.key()+": argument count");
                }
                check(message.arguments(),languages);
            } else if (value instanceof List<?> nested) check(nested,languages);
        }
    }
}
