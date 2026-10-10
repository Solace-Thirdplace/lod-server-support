package dev.vox.lss.common.dh;

import java.io.IOException;

/** Contained codec failures; callers decide session policy without inspecting payload text. */
public final class DhCodecException extends IOException {
    public enum Kind { MALFORMED, UNSUPPORTED, RESOURCE_LIMIT, PROTOCOL_MISMATCH, UNOWNED, EXPIRED }
    private final Kind kind;

    public DhCodecException(Kind kind, String message) { super(message); this.kind = kind; }
    public DhCodecException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public Kind kind() { return kind; }
}
