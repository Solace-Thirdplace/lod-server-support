package dev.vox.lss.common.dh;

/** Family matters as well as protocol: DH Support prefixes UTF-8 strings with UTF-16 length. */
public enum DhProfile {
    SUPPORT_15(15, true), OFFICIAL_15(15, false), OFFICIAL_16(16, false);
    private final int version;
    private final boolean support;
    DhProfile(int version, boolean support) { this.version = version; this.support = support; }
    public int version() { return version; }
    public boolean support() { return support; }
    public boolean regenerationFlag() { return version == 16; }
}
