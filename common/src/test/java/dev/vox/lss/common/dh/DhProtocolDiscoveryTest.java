package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class DhProtocolDiscoveryTest {
    static byte[] official(DhProfile profile) throws Exception {
        return new DhProtocolCodec(profile,DhLimits.DEFAULT).encode(new DhMessage.CloseReason("Incompatible protocol version"));
    }
    static byte[] support() throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeShort(15);out.writeShort(1);
            String reason="Server protocol is outdated";
            out.writeInt(reason.length());out.write(reason.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
    @Test void defensiveEncoderControlsAndSupportPrefixDistinguishTheFamilies() throws Exception {
        assertEquals(DhProfile.OFFICIAL_15,DhProtocolDiscovery.advertised(official(DhProfile.OFFICIAL_15)));
        assertEquals(DhProfile.SUPPORT_15,DhProtocolDiscovery.advertised(support()));
        assertEquals(DhProfile.OFFICIAL_16,DhProtocolDiscovery.advertised(official(DhProfile.OFFICIAL_16)));
    }
    @Test void anAsciiVersion15ConfigNeverGuessesItsProducerFamily() throws Exception {
        var cfg=new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,20,false,128,true,2048,50,500);
        byte[] old=new DhProtocolCodec(DhProfile.OFFICIAL_15,DhLimits.DEFAULT).encode(cfg);
        assertNull(DhProtocolDiscovery.advertised(old));
        assertEquals(DhProfile.OFFICIAL_16,DhProtocolDiscovery.advertised(
                new DhProtocolCodec(DhProfile.OFFICIAL_16,DhLimits.DEFAULT).encode(cfg)));
    }
    @Test void incompleteOrUnownedDataNeverChoosesAProtocol() throws Exception {
        assertThrows(DhCodecException.class,()->DhProtocolDiscovery.version(new byte[3]));
        assertNull(DhProtocolDiscovery.advertised(new byte[]{0,15,0,10}));
        assertNull(DhProtocolDiscovery.advertised(new byte[]{0,15,0,1,0,5,'a'}));
        assertNull(DhProtocolDiscovery.advertised(new byte[]{0,17,0,1}));
    }
}
