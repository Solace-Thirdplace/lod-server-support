package dev.vox.lss.common.dh;

/** Detect supported server control frames without guessing a terrain decoder. */
public final class DhProtocolDiscovery {
    private DhProtocolDiscovery() {}

    public static int version(byte[] frame) throws DhCodecException {
        if(frame.length<4)throw DhInput.malformed("Truncated DH header");
        return (frame[0]&255)<<8 | frame[1]&255;
    }

    /**
     * Configs distinguish version 16. A version-15 rejection distinguishes the
     * official short string prefix from Support's four-byte prefix. ASCII level
     * keys/configs alone are ambiguous, so they never choose between those families.
     */
    public static DhProfile advertised(byte[] frame) throws DhCodecException {
        int version=version(frame),kind=(frame[2]&255)<<8 | frame[3]&255;
        if(kind!=1 && kind!=2 && kind!=4)return null;
        if(version==16) {
            new DhProtocolCodec(DhProfile.OFFICIAL_16,DhLimits.DEFAULT).decode(frame);
            return DhProfile.OFFICIAL_16;
        }
        if(version!=15)return null;
        DhProfile found=null;
        for(var profile:new DhProfile[]{DhProfile.OFFICIAL_15,DhProfile.SUPPORT_15}) {
            try {
                new DhProtocolCodec(profile,DhLimits.DEFAULT).decode(frame);
                if(found!=null)return null;
                found=profile;
            } catch(DhCodecException ignored) { /* Only a complete valid decode is an advertisement. */ }
        }
        return found;
    }
}
