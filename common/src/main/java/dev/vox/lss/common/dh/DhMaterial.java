package dev.vox.lss.common.dh;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Registry-independent serialized identity. Registry lookup/fallback belongs to the client adapter. */
public record DhMaterial(String biome, String block, Map<String, String> properties) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public DhMaterial { properties = Map.copyOf(properties); }
    public boolean air() { return properties.isEmpty() && (block.equals("minecraft:air")
            || block.equals("minecraft:cave_air") || block.equals("minecraft:void_air")); }
    public static DhMaterial parse(String encoded) throws DhCodecException {
        return parse(encoded, false);
    }
    /** Official wrappers encode air as AIR and append _STATE_ even to stateless blocks. */
    public static DhMaterial parse(String encoded, DhProfile profile) throws DhCodecException {
        return parse(encoded, !profile.support());
    }
    private static DhMaterial parse(String encoded, boolean official) throws DhCodecException {
        int split = encoded.indexOf("_DH-BSW_");
        if (split < 1 || encoded.indexOf("_DH-BSW_", split + 1) >= 0)
            throw DhInput.malformed("Invalid DH material separator");
        String biome = encoded.substring(0, split), state = encoded.substring(split + 8);
        int marker = state.indexOf("_STATE_");
        String block = marker < 0 ? state : state.substring(0, marker);
        if (official && marker < 0 && block.equals("AIR")) block = "minecraft:air";
        if (!ID.matcher(biome).matches() || !ID.matcher(block).matches())
            throw DhInput.malformed("Invalid DH resource identity");
        var properties = new TreeMap<String, String>();
        if (marker >= 0) {
            int offset = marker + 7;
            if (offset == state.length() && !official) throw DhInput.malformed("Empty DH state properties");
            while (offset < state.length()) {
                int colon = state.indexOf(':', offset + 1), end = state.indexOf('}', offset + 1);
                if (state.charAt(offset) != '{' || colon < 0 || end < colon || colon == offset + 1 || end == colon + 1)
                    throw DhInput.malformed("Invalid DH state property");
                String name = state.substring(offset + 1, colon), value = state.substring(colon + 1, end);
                if (!name.matches("[a-z0-9_]+") || name.length() > 128 || value.length() > 256
                        || value.indexOf('{') >= 0 || value.indexOf(':') >= 0 || properties.size() >= 64
                        || properties.putIfAbsent(name, value) != null)
                    throw DhInput.malformed("Invalid or duplicate DH state property");
                offset = end + 1;
            }
        }
        return new DhMaterial(biome, block, properties);
    }
    public String serialized() {
        var result = new StringBuilder(biome).append("_DH-BSW_").append(block);
        if (!properties.isEmpty()) {
            result.append("_STATE_");
            new TreeMap<>(properties).forEach((key, value) -> result.append('{').append(key).append(':').append(value).append('}'));
        }
        return result.toString();
    }
}
