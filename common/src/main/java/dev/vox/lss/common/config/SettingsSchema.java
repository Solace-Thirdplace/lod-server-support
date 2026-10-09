package dev.vox.lss.common.config;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Authoritative path/type/default/timing metadata, independent of Minecraft and UI widgets. */
public final class SettingsSchema<T> {
    public enum Timing { H, S, R }
    public enum Platform { ALL, MOD, PAPER }
    public enum Kind { BOOLEAN, INTEGER, NUMBER, STRING, STRING_LIST, STRING_GROUPS, STRING_MAP, INTEGER_MAP }
    record Spec(String path, String legacyKey, Kind kind, Timing timing, Platform platform, boolean sensitive) {}
    public record Descriptor(String path, String legacyKey, Kind kind, Object defaultValue, Timing timing,
                             Platform platform, boolean sensitive, String group, String units,
                             Double minimum, Double maximum, boolean zeroSentinel, String description,
                             String translationKey) {}
    public record Normalization(String path, Object requested, Object effective) {}
    public record Decoded<T>(T configured, T normalized, List<Normalization> normalizations, List<String> inactivePaths) {}
    private final boolean client, paper;
    private final String template;
    private final Function<Map<String,Object>,T> factory;
    private final Function<T,Map<String,Object>> getter;
    private final Map<String,Descriptor> descriptors;
    private final Map<String,Object> defaults;

    public static SettingsSchema<ServerSettings> server(boolean paper) { return new SettingsSchema<>(false,paper,ServerSettings::fromValues,ServerSettings::values); }
    public static SettingsSchema<ClientSettings> client() { return new SettingsSchema<>(true,false,ClientSettings::fromValues,ClientSettings::values); }
    private SettingsSchema(boolean client, boolean paper, Function<Map<String,Object>,T> factory, Function<T,Map<String,Object>> getter) {
        this.client=client; this.paper=paper; this.factory=factory; this.getter=getter;
        template=resource(client ? "client" : paper ? "paper" : "server");
        var fullTree=new LinkedHashMap<>(YamlSettingsCodec.readTree(resource(client?"client":"server").getBytes(StandardCharsets.UTF_8)));
        if (!client) fullTree.put("paper",YamlSettingsCodec.readTree(resource("paper").getBytes(StandardCharsets.UTF_8)).get("paper"));
        var specs=SettingsSchemaSpecs.specs(client);
        var defs=new LinkedHashMap<String,Object>();
        var descs=new LinkedHashMap<String,Descriptor>();
        for (Spec spec:specs) {
            Object raw=lookup(fullTree,spec.path());
            if(client && raw==null)raw=switch(spec.path()) {
                case "lod.source" -> "lss";
                case "lod.dh.sync_on_load", "lod.dh.real_time_updates" -> true;
                default -> null;
            };
            if (raw==null && spec.path().endsWith("by_world")) raw=Map.of();
            if (paper && spec.path().equals("storage.lod_store.resweep_interval_seconds")) raw=300;
            Object value=typed(spec,raw);
            defs.put(spec.path(),value);
            double[] range=range(spec.path(),client);
            String inline=inlineComment(template,spec.path());
            if(client && inline.isEmpty())inline=switch(spec.path()) {
                case "lod.source" -> "[reconnect] lss = native only; distant_horizons = allow automatic DH fallback when native LSS is absent.";
                case "lod.dh.sync_on_load" -> "Check saved DH terrain timestamps when loading it, if the server permits synchronization.";
                case "lod.dh.real_time_updates" -> "Receive server-pushed DH terrain changes when allowed; no periodic polling.";
                default -> "";
            };
            if (inline.isEmpty()) inline=inlineComment(resource("server"),spec.path());
            if (inline.isEmpty()) inline=inlineComment(resource("paper"),spec.path());
            descs.put(spec.path(),new Descriptor(spec.path(),spec.legacyKey(),spec.kind(),value,spec.timing(),spec.platform(),spec.sensitive(),group(spec.path()),units(spec.path()),
                    range==null?null:range[0],range==null?null:range[1],range!=null&&range[2]==1,inline,spec.path().equals("lod.source")?"lss.config.lod_source":"lss.settings."+spec.path()));
        }
        defaults=Collections.unmodifiableMap(defs); descriptors=Collections.unmodifiableMap(descs);
    }
    public boolean isClient() { return client; }
    public boolean isPaper() { return paper; }
    public String side() { return client?"client":"server"; }
    public String template() { return template; }
    public T defaults() { return factory.apply(defaults); }
    public List<Descriptor> descriptors() { return List.copyOf(descriptors.values()); }
    public Map<String,Descriptor> descriptorsByPath() { return descriptors; }
    public Map<String,Object> values(T settings) { return getter.apply(settings); }
    public Map<String,Object> defaultValues() { return defaults; }
    /** Strict flattened-map entry point used by UI drafts and immutable snapshot merges. */
    public Decoded<T> fromValues(Map<String,Object> values) {
        var configured=new LinkedHashMap<>(defaults);
        for (var entry:values.entrySet()) {
            Descriptor d=descriptors.get(entry.getKey());
            if (d==null) throw new SettingsException("Unknown settings path: "+entry.getKey());
            configured.put(d.path(),typed(new Spec(d.path(),d.legacyKey(),d.kind(),d.timing(),d.platform(),d.sensitive()),entry.getValue()));
        }
        var normalized=new LinkedHashMap<>(configured);
        for (Descriptor d:descriptors.values()) {
            Object v=normalized.get(d.path());
            if (d.minimum()!=null && v instanceof Number n) {
                double x=n.doubleValue();
                x=d.zeroSentinel()&&x<=0 ? 0 : Math.max(d.minimum(),Math.min(d.maximum(),x));
                if (d.kind()==Kind.INTEGER) normalized.put(d.path(),(int)x); else normalized.put(d.path(),x);
            }
            if (d.kind()==Kind.INTEGER_MAP) {
                @SuppressWarnings("unchecked") var map=(Map<String,Integer>)v;
                var clamped=new LinkedHashMap<String,Integer>();
                map.forEach((key,val)->clamped.put(key,Math.max(1,Math.min(2048,val))));
                normalized.put(d.path(),Collections.unmodifiableMap(clamped));
            }
        }
        if (!client) cap(normalized,"generation.concurrency.per_player","generation.concurrency.global",false);
        cap(normalized,"far_players.distance.min_blocks","far_players.distance.max_blocks",client);
        var changes=new ArrayList<Normalization>();
        for (String p:configured.keySet()) if (!Objects.equals(configured.get(p),normalized.get(p))) {
            Descriptor d=descriptors.get(p);
            changes.add(new Normalization(p,d.sensitive()?"<private>":configured.get(p),d.sensitive()?"<private>":normalized.get(p)));
        }
        return new Decoded<>(factory.apply(configured),factory.apply(normalized),List.copyOf(changes),List.of());
    }
    public Decoded<T> decode(Map<String,Object> tree) {
        Object version=tree.get("config_version");
        if (!(version instanceof Number n) || version instanceof BigDecimal || version instanceof Double || version instanceof Float || decimal(n,"config_version").compareTo(BigDecimal.ONE)!=0) throw new SettingsException("config_version must be the integer 1; newer versions are not supported");
        var flat=new LinkedHashMap<String,Object>();
        flatten(tree,"",flat);
        flat.remove("config_version");
        var parsed=fromValues(flat);
        var inactive=new ArrayList<String>();
        for (String path:flat.keySet()) if (!applicable(descriptors.get(path))) inactive.add(path);
        return new Decoded<>(parsed.configured(),parsed.normalized(),parsed.normalizations(),List.copyOf(inactive));
    }
    public boolean applicable(Descriptor d) { return client || d.platform()==Platform.ALL || (paper?d.platform()==Platform.PAPER:d.platform()==Platform.MOD); }
    private void flatten(Map<String,Object> tree,String prefix,Map<String,Object> out) {
        for (var e:tree.entrySet()) {
            // Dots belong to the programmatic path API, not YAML group keys.
            // Dynamic maps are descriptor leaves and never recurse through this method.
            if(e.getKey().contains(".")) throw new SettingsException("Use nested YAML mappings instead of dotted keys at "
                    +(prefix.isEmpty()?e.getKey():prefix+"."+e.getKey()));
            String p=prefix.isEmpty()?e.getKey():prefix+"."+e.getKey();
            // Local DH timer retirement: tolerate its existing YAML without restoring a runtime setting.
            if (client && (p.equals("lod.dh.refresh_seconds") || p.equals("lod.dh.profile"))) continue;
            if (p.equals("config_version")||descriptors.containsKey(p)) out.put(p,e.getValue());
            else {
                boolean knownGroup=descriptors.keySet().stream().anyMatch(k->k.startsWith(p+"."));
                if (!knownGroup) throw new SettingsException("Unknown settings path: "+p);
                if (!(e.getValue() instanceof Map<?,?> m)) throw new SettingsException("Mapping required at "+p);
                @SuppressWarnings("unchecked") var nested=(Map<String,Object>)m;
                flatten(nested,p,out);
            }
        }
    }
    private static Object typed(Spec d,Object value) {
        String p=d.path();
        if (value==null) throw new SettingsException("Null is not allowed at "+p);
        return switch(d.kind()) {
            case BOOLEAN -> { if (!(value instanceof Boolean)) throw type(p,"boolean true/false"); yield value; }
            case INTEGER -> { if(value instanceof BigDecimal||value instanceof Double||value instanceof Float)throw type(p,"integer"); yield integer(value,p); }
            case NUMBER -> { if (!(value instanceof Number n)) throw type(p,"finite number"); double v=n.doubleValue(); if (!Double.isFinite(v)) throw type(p,"finite number"); yield v; }
            case STRING -> {
                if (!(value instanceof String s)) throw type(p,"string");
                if (p.equals("lod.source") && !Set.of("lss", "distant_horizons").contains(s))
                    throw new SettingsException("lod.source must be lss or distant_horizons");
                if (p.equals("far_players.mode")&&!Set.of("off","on","opt_in").contains(s)) throw type(p,"off, opt_in or on");
                if (p.equals("privacy.xray.mode")&&!Set.of("auto","on","off").contains(s)) throw type(p,"auto, on or off");
                if (p.equals("compatibility.block_fallbacks.default")&&s.isBlank()) throw type(p,"nonblank block identifier");
                yield s;
            }
            case STRING_LIST -> stringList(value,p);
            case STRING_GROUPS -> {
                if (!(value instanceof List<?> list)) throw type(p,"list of address lists");
                var groups=new ArrayList<List<String>>();
                for(int i=0;i<list.size();i++) groups.add(stringList(list.get(i),p+"["+i+"]"));
                yield List.copyOf(groups);
            }
            case STRING_MAP, INTEGER_MAP -> {
                if (!(value instanceof Map<?,?> map)) throw type(p,"mapping");
                var result=new LinkedHashMap<String,Object>();
                for(var e:map.entrySet()) {
                    if (!(e.getKey() instanceof String key)||key.isBlank()||key.length()>256) throw type(p,"nonblank mapping keys up to 256 characters");
                    if(p.endsWith("by_dimension")&&!canonicalIdentifier(key)) throw type(p,"fully qualified dimension identifiers");
                    if(d.kind()==Kind.INTEGER_MAP) {
                        if(e.getValue() instanceof BigDecimal||e.getValue() instanceof Double||e.getValue() instanceof Float)throw type(p+"[entry]","integer");
                        result.put(key,integer(e.getValue(),p+"[entry]"));
                    }
                    else { if (!(e.getValue() instanceof String s)||s.isBlank()) throw type(p,"nonblank string mapping values"); result.put(key,s); }
                }
                yield Collections.unmodifiableMap(result);
            }
        };
    }
    private static List<String> stringList(Object value,String p) {
        if (!(value instanceof List<?> list)) throw type(p,"list of strings");
        var result=new ArrayList<String>();
        for(int i=0;i<list.size();i++) { if (!(list.get(i) instanceof String s)) throw type(p+"["+i+"]","string"); result.add(s); }
        return List.copyOf(result);
    }
    static int integer(Object value,String p) {
        if (!(value instanceof Number n)) throw type(p,"integer");
        try { return decimal(n,p).intValueExact(); } catch(ArithmeticException e) { throw type(p,"32-bit integer without a fractional part"); }
    }
    private static BigDecimal decimal(Number n,String p) {
        try { return new BigDecimal(n.toString()); } catch(NumberFormatException e) { throw type(p,"finite number"); }
    }
    public static boolean canonicalIdentifier(String s) { return s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"); }
    private static SettingsException type(String path,String type) { return new SettingsException("Expected "+type+" at "+path); }
    private static void cap(Map<String,Object> values,String min,String max,boolean zeroUnbounded) {
        int lo=(Integer)values.get(min),hi=(Integer)values.get(max);
        if (!zeroUnbounded||hi>0) values.put(min,Math.min(lo,hi));
    }
    static Object lookup(Map<String,Object> map,String path) {
        Object v=map;
        for(String key:path.split("\\.")) { if (!(v instanceof Map<?,?> m)) return null; v=m.get(key); }
        return v;
    }
    private static String resource(String name) {
        try(var stream=SettingsSchema.class.getResourceAsStream("/dev/vox/lss/settings/"+name+".yaml")) {
            if(stream==null) throw new IllegalStateException("Missing settings template "+name);
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    private static String group(String path) {
        if(path.startsWith("generation."))return "generation";
        if(path.startsWith("far_players.distance."))return "far_players.distance";
        if(path.startsWith("lod.distance."))return "lod.distance";
        return path.substring(0,path.lastIndexOf('.'));
    }
    private static String units(String path) {
        if(path.endsWith("mib_per_dimension"))return "mib_per_dimension";
        for(String suffix:List.of("mib_per_second","columns_per_second","seconds","ticks","chunks","blocks","mib")) if(path.endsWith(suffix))return suffix;
        return "";
    }
    private static String inlineComment(String text,String path) {
        var stack=new ArrayList<String>();
        for(String line:text.split("\\R")) {
            String stripped=line.stripLeading(); if(stripped.startsWith("#")||stripped.isBlank()||stripped.startsWith("-"))continue;
            int colon=stripped.indexOf(':');if(colon<0||stripped.startsWith("\""))continue;
            int depth=(line.length()-stripped.length())/2;while(stack.size()>depth)stack.remove(stack.size()-1);
            stack.add(stripped.substring(0,colon));
            if(String.join(".",stack).equals(path)) { int hash=stripped.indexOf('#');return hash<0?"":stripped.substring(hash+1).trim(); }
        }
        return "";
    }
    private static double[] range(String p,boolean client) {
        if(p.endsWith("by_dimension")||p.endsWith("by_world"))return new double[]{1,2048,0};
        if(p.endsWith("mib_per_second"))return new double[]{1.0/1024,1024,0};
        if(p.equals("lod.distance.default_chunks"))return new double[]{1,2048,0};
        if(p.equals("lod.distance_chunks"))return new double[]{0,2048,0};
        if(p.startsWith("generation.concurrency."))return new double[]{1,512,0};
        if(p.equals("generation.timeout_ticks"))return new double[]{20,12000,0};
        if(p.equals("updates.dirty_broadcast_interval_ticks"))return new double[]{20,6000,1};
        if(p.equals("network.send_queue_limit_per_player"))return new double[]{1,100000,0};
        if(p.equals("storage.disk.reader_threads")||p.equals("storage.disk.max_concurrent_reads"))return new double[]{1,64,1};
        if(p.equals("storage.timestamp_cache_mib_per_dimension"))return new double[]{1,512,1};
        if(p.equals("storage.miss_memo_ttl_seconds"))return new double[]{0,60,0};
        if(p.equals("storage.lod_store.max_size_mib"))return new double[]{64,1048576,1};
        if(p.equals("storage.lod_store.resweep_interval_seconds"))return new double[]{0,3600,0};
        if(p.equals("storage.lod_store.backfill.columns_per_second"))return new double[]{10,1000,0};
        if(p.equals("privacy.xray.max_y_blocks"))return new double[]{-2048,2048,0};
        if(p.equals("far_players.update_interval_ticks"))return new double[]{2,100,0};
        if(p.equals("far_players.distance.max_blocks"))return new double[]{client?0:128,16384,0};
        if(p.startsWith("far_players.")&&p.endsWith("blocks"))return new double[]{0,16384,0};
        if(p.equals("lod.download.max_columns_per_second"))return new double[]{10,100000,1};
        return null;
    }
}
