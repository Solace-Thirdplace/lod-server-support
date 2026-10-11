package dev.vox.lss.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** MC-free harness entry point: exactly the product schema, migration, codec and safe file writes. */
public final class SettingsCli {
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().serializeNulls().create();
    private SettingsCli(){}
    public static void main(String[] args) {
        try {System.out.println(JSON.toJson(run(args)));}
        catch(Exception e){System.err.println("Settings operation failed: "+e.getMessage());System.exit(2);}
    }
    static Object run(String[] args)throws Exception {
        if(args.length==0)throw new SettingsException("Use schema, create, validate, edit, or migrate");
        String operation=args[0],side="server",platform="mod",prefix="lss";Path path=null;var changes=new LinkedHashMap<String,String>();
        for(int i=1;i<args.length;i++) {
            if(i+1==args.length)throw new SettingsException("Missing value for "+args[i]);
            switch(args[i]) {
                case "--side"->side=args[++i];case "--platform"->platform=args[++i];case "--prefix"->prefix=args[++i];
                case "--path"->path=Path.of(args[++i]);
                case "--set"->{String assignment=args[++i];int equals=assignment.indexOf('=');if(equals<=0)throw new SettingsException("Use --set YAML.path=JSON_VALUE");String key=assignment.substring(0,equals);if(changes.putIfAbsent(key,assignment.substring(equals+1))!=null)throw new SettingsException("Duplicate edit path: "+key);}
                default->throw new SettingsException("Unknown argument: "+args[i]);
            }
        }
        if(!Set.of("server","client").contains(side))throw new SettingsException("Side must be server or client");
        if(!Set.of("mod","paper").contains(platform))throw new SettingsException("Platform must be mod or paper");
        if(operation.equals("schema")) {
            var all=new LinkedHashMap<String,Object>();all.put("config_version",1);
            all.put("server",inventory(SettingsSchema.server(false)));all.put("paper",inventory(SettingsSchema.server(true)));all.put("client",inventory(SettingsSchema.client()));return all;
        }
        if(path==null)throw new SettingsException("An explicit --path is required");
        if(side.equals("client"))return file(operation,path,prefix,SettingsSchema.client(),changes);
        return file(operation,path,prefix,SettingsSchema.server(platform.equals("paper")),changes);
    }
    private static Map<String,Object> inventory(SettingsSchema<?> schema) {
        var out=new LinkedHashMap<String,Object>();out.put("side",schema.side());out.put("platform",schema.isClient()?"client":schema.isPaper()?"paper":"mod");out.put("descriptors",schema.descriptors());out.put("aliases",schema.aliases());out.put("document",schema.template());return out;
    }
    private static <T> Object file(String operation,Path path,String prefix,SettingsSchema<T> schema,Map<String,String> encoded)throws Exception {
        SettingsStore<T> store=operation.equals("migrate")?new SettingsStore<>(path,prefix,schema):SettingsStore.atPath(path,schema);
        YamlSettingsCodec.Document<T> document;
        switch(operation) {
            case "create","migrate"->document=store.initialize();
            case "validate","read"->document=store.read();
            case "edit","stage"->{
                var old=operation.equals("stage")?store.initialize():store.read();var changes=new LinkedHashMap<String,Object>();
                if(operation.equals("stage"))for(var descriptor:schema.descriptors())if(schema.applicable(descriptor))changes.put(descriptor.path(),descriptor.defaultValue());
                // Renamed groups: alias keys are applied under their canonical path, and an
                // explicit value replaces a staged default (canonicalChanges rejects conflicts).
                var explicit=new LinkedHashMap<String,Object>();
                for(var e:encoded.entrySet()) {
                    var wrapper=LegacySettingsMigration.readJson(("{\"value\":"+e.getValue()+"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    explicit.put(e.getKey(),argumentNumbers(wrapper.get("value")));
                }
                changes.putAll(schema.canonicalChanges(explicit));
                document=store.saveDraft(old.hash(),changes);
            }
            default->throw new SettingsException("Unknown settings operation: "+operation);
        }
        var result=new LinkedHashMap<String,Object>();result.put("ok",true);result.put("hash",document.hash());
        result.put("normalized_paths",document.normalizations().stream().map(SettingsSchema.Normalization::path).toList());result.put("inactive_paths",document.inactivePaths());
        if(operation.equals("read")){result.put("configured",schema.values(document.configured()));result.put("normalized",schema.values(document.normalized()));}
        result.put("message",operation.equals("edit")?"Saved to disk; run the appropriate reload command to activate":"Settings document is valid");return result;
    }
    private static Object argumentNumbers(Object value) {
        if(value instanceof Number number){var n=new BigDecimal(number.toString());try{return n.intValueExact();}catch(ArithmeticException ignored){return n;}}
        if(value instanceof Map<?,?> map){var out=new LinkedHashMap<String,Object>();map.forEach((k,v)->out.put((String)k,argumentNumbers(v)));return out;}
        if(value instanceof List<?> list)return list.stream().map(SettingsCli::argumentNumbers).toList();
        return value;
    }
}
