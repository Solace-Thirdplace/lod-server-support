package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DhLegacyEvidenceTest {
    @Test void capturedOfficialTerrainProvidesPositiveFamilyEvidence() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        var codec=new DhProtocolCodec(DhProfile.OFFICIAL_15,DhLimits.DEFAULT);
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,budget)) {
            assembly.allowPush(true);
            for(int i:new int[]{4,5})assembly.accept((DhMessage.Split)codec.decode(bytes("dh-official-3.2.0-b-neoforge",i)));
            var reply=(DhMessage.Response)codec.decode(bytes("dh-official-3.2.0-b-neoforge",6));
            try(var complete=assembly.push(new DhMessage.PartialUpdate("test",reply.bufferId(),List.of()),System.nanoTime())) {
                assertEquals(DhProfile.OFFICIAL_15,complete.legacyEvidence(new DhTerrainDecoder(DhLimits.DEFAULT),new DhTerrainTile.WorldShape(-64,384,true)));
            }
        }
        assertEquals(0,budget.used());
    }
    private byte[] bytes(String corpus,int number) throws Exception {
        try(var in=getClass().getResourceAsStream("/dh-corpus/"+corpus+"/"+String.format("received-%04d.bin",number))) {assertNotNull(in);return in.readAllBytes();}
    }
}
