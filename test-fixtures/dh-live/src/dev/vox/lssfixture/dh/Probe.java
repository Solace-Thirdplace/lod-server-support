package dev.vox.lssfixture.dh;

import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.dh.*;
import dev.vox.lss.compat.DhVoxyBridge;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.client.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only observer; all terrain acquisition and ingestion use production code. */
public final class Probe {
    public static volatile boolean suppressRender;
    record Cell(int x,int y,int z,BlockState state) {}
    record Column(DhPosition tile,List<Cell> cells) {}
    static final Map<Long,Column> expected=new LinkedHashMap<>();
    static int phase;
    static long started=System.nanoTime(),changed,logged;
    static boolean connecting,done;
    static boolean positioned;
    static float viewYaw=90,viewPitch=15;
    static Object previous;
    static long baseline;
    static final AtomicLong requests=new AtomicLong(),conditionalRequests=new AtomicLong(),unchangedReplies=new AtomicLong();
    static final Map<Integer,Long> conditional=new java.util.concurrent.ConcurrentHashMap<>();
    static DhSessionPolicy overworldPolicy;
    static Object originalConnection;
    static Object sodiumConfig;
    static Map<String,Object> menuOptions;
    static long disabledDueReceipts;
    static volatile long lastTerrainRequest;
    static final Map<Integer,DhPosition> missingCandidates=new java.util.concurrent.ConcurrentHashMap<>();
    static final Map<DhPosition,Long> missingTiles=new java.util.concurrent.ConcurrentHashMap<>();
    static long offRequests,offObservedMillis;
    static long observedRetiredBytes,noSkyBaseline;
    static final AtomicLong noSkySamples=new AtomicLong();
    static final AtomicLong conversionCpu=new AtomicLong(),conversionWall=new AtomicLong(),conversionSamples=new AtomicLong(),conversionAllocation=new AtomicLong(),unobservedAirSamples=new AtomicLong();
    static final java.lang.management.ThreadMXBean threads=java.lang.management.ManagementFactory.getThreadMXBean();
    static final ThreadLocal<long[]> conversionStart=new ThreadLocal<>();
    public static void conversionStarted() {
        conversionStart.set(new long[]{System.nanoTime(),threads.isCurrentThreadCpuTimeSupported()?threads.getCurrentThreadCpuTime():-1, allocated()});
    }
    private static long allocated() {
        return threads instanceof com.sun.management.ThreadMXBean allocation && allocation.isThreadAllocatedMemoryEnabled()
                ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
    }
    public static void conversionFinished() {
        long[] start=conversionStart.get();conversionStart.remove();if(start==null)return;
        conversionWall.addAndGet(System.nanoTime()-start[0]);conversionSamples.incrementAndGet();
        if(start[1]>=0)conversionCpu.addAndGet(Math.max(0,threads.getCurrentThreadCpuTime()-start[1]));
        if(start[2]>=0)conversionAllocation.addAndGet(Math.max(0,allocated()-start[2]));
    }
    static void resourceEvidence() throws Exception {
        var gson=new com.google.gson.Gson();
        write("dh-resources.json",gson.toJson(Map.of("diagnostics",DhClientSession.diagnostics(),
                "successful_conversion_calls",conversionSamples.get(),"conversion_cpu_nanos",conversionCpu.get(),
                "conversion_wall_nanos",conversionWall.get(),"conversion_allocated_bytes",conversionAllocation.get(),
                "absent_air_samples_not_validated",unobservedAirSamples.get(),"hardware_frame_time_measured",false)));
    }
    static void absence(Object session,long now) throws Exception {
        var config=(DhMessage.SessionConfig)field(session,"config");
        if(config==null)return;
        if(config.generationDistance()>=40 || DhClientSession.diagnostics().effectiveRange()!=40)
            throw new IllegalStateException("Missing-terrain premise needs sync range 40 beyond generation reach");
        if(missingTiles.size()>=8 && now-lastTerrainRequest>30_000_000_000L
                && now-Collections.min(missingTiles.values())>130_000_000_000L) {
            write("dh-result.json","{\"pass\":true,\"unreachable_absence\":true,\"periodic_polling_removed\":true}");
            done=true;Minecraft.getInstance().stop();
        }
    }
    static void sodiumApply(Map<String,Integer> values) throws Exception {
        var mc=Minecraft.getInstance();
        if(mc.level==null || mc.getConnection()==null)throw new IllegalStateException("In-session Sodium Apply requires a live connection");
        Class<?> screen=Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
        var opened=(net.minecraft.client.gui.screens.Screen)screen.getMethod("createScreen",net.minecraft.client.gui.screens.Screen.class).invoke(null,mc.screen);
        mc.setScreen(opened);
        Object config=Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager").getField("CONFIG").get(null);
        for(var entry:values.entrySet()) {
            Object option=config.getClass().getMethod("getOption",net.minecraft.resources.ResourceLocation.class)
                    .invoke(config,net.minecraft.resources.ResourceLocation.parse(entry.getKey()));
            Object value=entry.getKey().equals("lss:lod_source") ? entry.getValue()!=0 : entry.getValue();
            option.getClass().getMethod("modifyValue",Object.class).invoke(option,value);
        }
        config.getClass().getMethod("applyAllOptions").invoke(config);
        mc.setScreen(null);
    }
    static long dueWithin(Object s,long deadline) throws Exception {
        var receipts=(Map<?,?>)field(field(s,"policy"),"receipts");long count=0;
        for(Object r:receipts.values())if((boolean)field(r,"snapshot") && !(boolean)field(r,"generate")
                && (int)field(r,"failures")==0 && !(boolean)field(r,"held") && (long)field(r,"due")<=deadline)count++;
        return count;
    }
    static long successfulSnapshots(Object s) throws Exception {
        var receipts=(Map<?,?>)field(field(s,"policy"),"receipts");long count=0;
        for(Object r:receipts.values())if((boolean)field(r,"snapshot") && !(boolean)field(r,"generate")
                && (int)field(r,"failures")==0)count++;
        return count;
    }
    static void menu(long now) throws Exception {
        var mc=Minecraft.getInstance();
        if(phase==0 && mc.screen instanceof TitleScreen) {
            Class<?> screen=Class.forName("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen");
            var opened=(net.minecraft.client.gui.screens.Screen)screen.getMethod("createScreen",net.minecraft.client.gui.screens.Screen.class).invoke(null,mc.screen);
            mc.setScreen(opened);
            sodiumConfig=Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager").getField("CONFIG").get(null);
            var options=(Map<?,?>)field(sodiumConfig,"options");
            long count=options.keySet().stream().filter(id->id.toString().startsWith("lss:")).count();
            if(count!=11)throw new IllegalStateException("Actual Sodium menu has "+count+" LSS options");
            if(options.keySet().stream().anyMatch(id->Set.of("lss:dh_profile","lss:dh_sync_on_load","lss:dh_real_time_updates").contains(id.toString())))throw new IllegalStateException("A config-only DH control remains in the menu");
            menuOptions=new LinkedHashMap<>();
            for(String id:List.of("lss:lod_source")) {
                Object o=sodiumConfig.getClass().getMethod("getOption",net.minecraft.resources.ResourceLocation.class)
                        .invoke(sodiumConfig,net.minecraft.resources.ResourceLocation.parse(id));
                String expected=switch(id) {
                    case "lss:lod_source" -> "BooleanOption";
                    default -> "BooleanOption";
                };
                if(o==null || !o.getClass().getSimpleName().equals(expected))throw new IllegalStateException("Wrong actual Sodium control "+id);
                String name=((net.minecraft.network.chat.Component)o.getClass().getMethod("getName").invoke(o)).getString();
                if(!name.equals("Allow DH Server Terrain"))throw new IllegalStateException("Unexpected DH fallback name: "+name);
                menuOptions.put(id,o);
            }
            List<?> mods=(List<?>)sodiumConfig.getClass().getMethod("getModOptions").invoke(sodiumConfig);
            Object mod=mods.stream().filter(m->{try{return m.getClass().getMethod("configId").invoke(m).equals("lss");}catch(Exception e){throw new RuntimeException(e);}}).findFirst().orElseThrow();
            var pages=(List<?>)mod.getClass().getMethod("pages").invoke(mod);
            Class<?> pageType=Class.forName("net.caffeinemc.mods.sodium.client.config.structure.Page");
            var names=new ArrayList<String>();
            for(Object p:pages) names.add(((net.minecraft.network.chat.Component)pageType.getMethod("name").invoke(p)).getString());
            if(!names.equals(List.of("General","Far Players")))throw new IllegalStateException("Unexpected LSS pages: "+names);
            Object page=pages.get(0);
            Object receive=sodiumConfig.getClass().getMethod("getOption",net.minecraft.resources.ResourceLocation.class)
                    .invoke(sodiumConfig,net.minecraft.resources.ResourceLocation.parse("lss:receive_server_lods"));
            receive.getClass().getMethod("modifyValue",Object.class).invoke(receive,false);
            for(Object option:menuOptions.values())if((boolean)option.getClass().getMethod("isEnabled").invoke(option))
                throw new IllegalStateException("DH option did not follow the General page master");
            receive.getClass().getMethod("modifyValue",Object.class).invoke(receive,true);
            for(Object option:menuOptions.values())if(!(boolean)option.getClass().getMethod("isEnabled").invoke(option))
                throw new IllegalStateException("DH option did not re-enable with the General page master");
            screen.getMethod("jumpToPage",Class.forName("net.caffeinemc.mods.sodium.client.config.structure.Page")).invoke(opened,page);
            if(mc.options.guiScale().get()!=0 || mc.getWindow().getGuiScale()<3)throw new IllegalStateException("Menu must exercise Auto GUI scale in a 1280x720 window");
            Object[] values={true};int i=0;
            for(Object option:menuOptions.values())if(!option.getClass().getMethod("getAppliedValue").invoke(option).equals(values[i++]))
                throw new IllegalStateException("Sodium did not load the active DH settings");
            changed=now;phase=1;
        } else if(phase==1 && now-changed>3_000_000_000L) {
            Screenshot.grab(mc.gameDirectory,"dh-menu.png",mc.getMainRenderTarget(),ignored->{});
            if(ClientStatus.latest()==null || ClientStatus.latest().dh()==null)throw new IllegalStateException("Missing DH status snapshot");
            ClientCommandActions.exportDiagnostics(ignored->{});
            Object[] values={true};int i=0;
            for(Object option:menuOptions.values())option.getClass().getMethod("modifyValue",Object.class).invoke(option,values[i++]);
            phase=2;changed=now;
        } else if(phase==2 && now-changed>3_000_000_000L) {
            Screenshot.grab(mc.gameDirectory,"dh-menu-longest.png",mc.getMainRenderTarget(),ignored->{});
            Object[] values={false};int i=0;
            for(Object option:menuOptions.values())option.getClass().getMethod("modifyValue",Object.class).invoke(option,values[i++]);
            sodiumConfig.getClass().getMethod("applyAllOptions").invoke(sodiumConfig);
            phase=3;changed=now;
        } else if(phase==3 && LSSClientConfig.CONFIG.configured().lod().source().equals("lss")
                && LSSClientConfig.CONFIG.snapshot().lod().dh().syncOnLoad() && LSSClientConfig.CONFIG.snapshot().lod().dh().realTimeUpdates()) {
            Path directory=mc.gameDirectory.toPath().resolve("lss-diagnostics");
            if(!Files.isDirectory(directory))return;
            List<Path> reports;try(var files=Files.list(directory)){reports=files.filter(p->p.getFileName().toString().endsWith(".json")).toList();}
            if(reports.isEmpty())return;
            var report=com.google.gson.JsonParser.parseString(Files.readString(reports.getFirst())).getAsJsonObject();
            if(!report.has("dh") || report.get("protocol").getAsInt()!=0 || report.get("negotiated").getAsBoolean()
                    || report.getAsJsonObject("dh").get("profile").getAsString().equals("OFFICIAL_16")==false)
                throw new IllegalStateException("DH export confused native negotiation/source");
            write("dh-menu.json","{\"pass\":true,\"actual_sodium\":true,\"gui_scale_setting\":0,\"gui_scale_actual\":"+mc.getWindow().getGuiScale()+",\"lss_controls\":11,\"general_dh_fallback\":true,\"reception_gate\":true,\"sync_and_updates_config_only\":true,\"sync_and_updates_preserved_on\":true,\"source_checkbox\":true,\"automatic_protocol\":true,\"source_saved\":\"lss\",\"diagnostics_export\":true}");
            write("dh-result.json","{\"pass\":true,\"menu\":true,\"sodium_apply\":true,\"diagnostics_export\":true}");done=true;mc.stop();
        }
    }
    public static void sent(Object owner,DhMessage message) {
        if(System.getProperty("lss.rig.scenario","").equals("updates")) UpdatesProbe.sent(owner,message);
        if(System.getProperty("lss.rig.scenario","").equals("service")) ServiceProbe.sent(owner,message);
        if(Set.of("content","rejoin").contains(System.getProperty("lss.rig.scenario",""))) {ContentProbe.sent(owner,message);return;}
        if(message instanceof DhMessage.Request r) {
            requests.incrementAndGet();
            lastTerrainRequest=System.nanoTime();
            if(System.getProperty("lss.rig.scenario","").equals("absence") && r.timestamp()!=null && r.timestamp()==0) {
                if(missingTiles.containsKey(r.position()))throw new IllegalStateException("Unreachable empty tile was re-polled: "+r.position());
                try {
                    var s=field(DhClientSession.class,"session");
                    var cfg=(DhMessage.SessionConfig)field(s,"config");var player=Minecraft.getInstance().player.chunkPosition();
                    if(Math.max(Math.abs(r.position().x()*4+2-player.x),Math.abs(r.position().z()*4+2-player.z))>Math.min(32,cfg.generationDistance()))
                        missingCandidates.put(r.requestId(),r.position());
                } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
                catch(Exception e) {throw new IllegalStateException(e);}
            }
            if(r.timestamp()!=null && r.timestamp()>0) {conditionalRequests.incrementAndGet();conditional.put(r.requestId(),r.timestamp());}
        } else if(message instanceof DhMessage.Cancel c) conditional.remove(c.requestId());
    }
    public static void transportFrame(int type) {
        if(System.getProperty("lss.rig.scenario","").equals("updates") && type==9)UpdatesProbe.pushFrames.incrementAndGet();
    }
    public static void received(byte[] bytes) throws Exception {
        if(System.getProperty("lss.rig.scenario","").equals("updates")) UpdatesProbe.received(bytes);
        if(Set.of("content","rejoin").contains(System.getProperty("lss.rig.scenario",""))) {ContentProbe.received(bytes);return;}
        if(System.getProperty("lss.rig.scenario","").equals("service")) return;
        // Inspect only responses, avoiding duplicate terrain-fragment allocations.
        if(bytes.length<4 || bytes[2]!=0 || bytes[3]!=8) return;
        var response=(DhMessage.Response)new DhProtocolCodec(DhProfile.OFFICIAL_16,DhLimits.DEFAULT).decode(bytes);
        if(conditional.remove(response.requestId())!=null && response.bufferId()==null) unchangedReplies.incrementAndGet();
        var missing=missingCandidates.remove(response.requestId());
        if(missing!=null && response.bufferId()==null)missingTiles.put(missing,System.nanoTime());
    }
    static void settings(Map<String,Object> values) throws Exception {
        var cfg=LSSClientConfig.CONFIG;var store=cfg.store();store.saveDraft(store.read().hash(),values);
        cfg.reload(ignored->{});
    }
    static void retired(Object s) throws Exception {
        observedRetiredBytes=((DhMemoryBudget)field(s,"budget")).used();
        if((boolean)field(s,"active") || observedRetiredBytes!=0)
            throw new IllegalStateException("Retired epoch retained DH memory/work");
    }
    static synchronized void aimAtDistantColumn(Object s) throws Exception {
        var mc=Minecraft.getInstance();var policy=(DhSessionPolicy)field(s,"policy");
        int best=-1,bx=0,bz=0;
        for(var e:expected.entrySet()) {
            int cx=(int)(long)e.getKey(),cz=(int)(e.getKey()>>32);
            int distance=cx*cx+cz*cz;
            if(policy.eligible(cx,cz) && policy.timestamp(e.getValue().tile())>0 && distance>best
                    && e.getValue().cells().stream().anyMatch(c->!c.state().isAir() && c.state().getFluidState().isEmpty())) {
                best=distance;bx=cx;bz=cz;
            }
        }
        if(best<36) throw new IllegalStateException("No sufficiently distant accepted solid column for view");
        double dx=bx*16+8,dz=bz*16+8;
        viewYaw=(float)Math.toDegrees(Math.atan2(-dx,dz));viewPitch=(float)Math.toDegrees(Math.atan2(40,Math.hypot(dx,dz)));
        write("dh-view.json","{\"player\":[0,100,0],\"target_chunk\":["+bx+","+bz+"],\"vanilla_authority\":"+!policy.eligible(bx,bz)+",\"yaw\":"+viewYaw+",\"pitch\":"+viewPitch+"}");
    }
    static void rendering(boolean enabled) throws Exception {
        // Voxy's config flag is consulted at renderer creation, not each draw.
        // The test-only mixin suppresses actual geometry passes for this control.
        suppressRender=!enabled;
    }
    static void renderEvidence() throws Exception {
        Class<?> stats=Class.forName("me.cortex.voxy.client.RenderStatistics");
        int[] quads=(int[])stats.getField("quadCount").get(null);
        int[] visible=(int[])stats.getField("visibleSections").get(null);
        long total=0; for(int q:quads) total+=q;
        if(total<=0) throw new IllegalStateException("No rendered Voxy quads after settling");
        String renderer=org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
        boolean software=renderer!=null && renderer.toLowerCase(Locale.ROOT).contains("llvmpipe");
        if(!software)throw new IllegalStateException("Private render control is not llvmpipe");
        write("dh-render.json","{\"quads\":"+total+",\"quad_counts\":"+Arrays.toString(quads)+",\"visible_sections\":"+Arrays.toString(visible)+",\"software_control\":"+software+"}");
    }
    static Object field(Object o,String name) throws Exception {
        Field f=(o instanceof Class<?> c?c:o.getClass()).getDeclaredField(name); f.setAccessible(true);
        return f.get(o instanceof Class<?>?null:o);
    }
    static long count(Object s,String name) throws Exception { return ((AtomicLong)field(s,name)).get(); }
    static void write(String name,String text) throws Exception {
        Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve(name),text);
    }
    public static synchronized void converted(DhTerrainTile tile,int c,VoxelColumnData data) {
        if(done) return;
        if(System.getProperty("lss.rig.scenario","").equals("rejoin")) {RejoinProbe.converted(tile);return;}
        if(System.getProperty("lss.rig.scenario","").equals("content")) {ContentProbe.converted(tile,c,data);return;}
        if(System.getProperty("lss.rig.scenario","").equals("updates")) UpdatesProbe.converted(tile);
        OceanLightProbe.converted(tile,c,data);
        if(!tile.world().sky()) for(var section:data.sections())
            for(int y=0;y<16;y++) {
                if(section.skyLight()==null || section.skyLight().get(2,y,2)!=0)
                    throw new IllegalStateException("No-sky dimension carried skylight");
                noSkySamples.incrementAndGet();
            }
        int cx=tile.position().x()*4+c/4,cz=tile.position().z()*4+c%4;
        long key=(cx & 0xffffffffL)|((long)cz<<32);
        // Later generation may legitimately refine a prior sync snapshot. Keep
        // observations for existing columns current even when the sample cap fills.
        if(expected.size()>=32 && !expected.containsKey(key)) return;
        var cells=new ArrayList<Cell>();
        for(var section:data.sections()) for(int x=0;x<16;x+=5) for(int z=0;z<16;z+=5)
            for(int y=0;y<16;y+=3)
                cells.add(new Cell(cx*16+x,section.sectionY()*16+y,cz*16+z,section.section().getBlockState(x,y,z)));
        expected.put(key,new Column(tile.position(),cells));
    }
    static synchronized int[] readback(Object s) throws Exception {
        var mc=Minecraft.getInstance(); var policy=(DhSessionPolicy)field(s,"policy");
        Class<?> identifier=Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
        Object id=identifier.getMethod("of",Level.class).invoke(null,mc.level);
        Object engine=identifier.getMethod("getNullable").invoke(id);
        if(engine==null) throw new IllegalStateException("No live Voxy world");
        Class<?> ec=Class.forName("me.cortex.voxy.common.world.WorldEngine"),sc=Class.forName("me.cortex.voxy.common.world.WorldSection");
        Class<?> mapperType=Class.forName("me.cortex.voxy.common.world.other.Mapper");
        Object mapper=ec.getMethod("getMapper").invoke(engine);
        Method acquire=ec.getMethod("acquireIfExists",int.class,int.class,int.class,int.class);
        Method copy=sc.getMethod("copyData"),release=sc.getMethod("release");
        Method getId=mapperType.getMethod("getBlockId",long.class),getState=mapperType.getMethod("getBlockStateFromBlockId",int.class);
        Map<String,long[]> snapshots=new HashMap<>(); Map<Integer,BlockState> states=new HashMap<>();
        Set<String> absent=new HashSet<>();
        int samples=0,solids=0,columns=0;
        Set<DhPosition> inFlight=new HashSet<>();
        for(Object request:((Map<?,?>)field(s,"pending")).values()) {
            inFlight.add((DhPosition)field(request,"position"));
        }
        for(var entry:expected.entrySet()) {
            int cx=(int)(long)entry.getKey(),cz=(int)(entry.getKey()>>32);
            var column=entry.getValue();
            Object receipt=((Map<?,?>)field(policy,"receipts")).get(column.tile());
            if(!policy.eligible(cx,cz) || receipt==null || !(boolean)field(receipt,"snapshot") || inFlight.contains(column.tile())) continue;
            columns++;
            for(var cell:column.cells()) {
                String key=(cell.x()>>5)+":"+(cell.y()>>5)+":"+(cell.z()>>5);
                if(absent.contains(key)) {
                    if(!cell.state().isAir())throw new IllegalStateException("Expected solid Voxy section is missing: "+key);
                    unobservedAirSamples.incrementAndGet();continue;
                }
                long[] values=snapshots.get(key);
                if(values==null) {
                    Object section=acquire.invoke(engine,0,cell.x()>>5,cell.y()>>5,cell.z()>>5);
                    if(section==null) {
                        if(!cell.state().isAir())throw new IllegalStateException("Expected solid Voxy section is missing: "+key);
                        // Voxy may omit an all-air section. Do not count absence as
                        // an air match or as storage evidence. Content/removal uses
                        // its separate strict sentinel-backed observer.
                        absent.add(key);unobservedAirSamples.incrementAndGet();continue;
                    }
                    try { values=(long[])copy.invoke(section); } finally { release.invoke(section); }
                    snapshots.put(key,values);
                }
                long value=values[((cell.y()&31)<<10)|((cell.z()&31)<<5)|(cell.x()&31)];
                int block=(int)getId.invoke(null,value);
                BlockState actual=states.get(block);
                if(actual==null) { actual=(BlockState)getState.invoke(mapper,block); states.put(block,actual); }
                // The actual Voxy voxelizer canonicalizes every isAir() state to
                // its zero/air entry. Preserve exact state checks for other blocks.
                if(actual!=cell.state() && !(actual.isAir()&&cell.state().isAir())) throw new IllegalStateException("Readback mismatch at "+cell.x()+","+cell.y()+","+cell.z()+": "+cell.state()+" vs "+actual);
                samples++; if(!actual.isAir() && actual.getFluidState().isEmpty()) solids++;
            }
        }
        if(columns<4 || solids<100) throw new IllegalStateException("Insufficient solid readback: "+columns+" columns, "+solids+" solid samples");
        return new int[]{samples,solids,columns,snapshots.size()};
    }
    static synchronized void pruneVanillaObservations(Object session) throws Exception {
        // Capture precedes the main-thread handoff. A vanilla chunk can arrive
        // meanwhile; keep the bounded sample set available for later DH columns.
        var policy=(DhSessionPolicy)field(session,"policy");
        expected.keySet().removeIf(key->!policy.eligible((int)(long)key,(int)(key>>32)));
    }
    public static void tick() {
        if(done || System.getProperty("lss.rig.runId","").isEmpty()) return;
        var mc=Minecraft.getInstance(); long now=System.nanoTime();
        try {
            if(now-started>(System.getProperty("lss.rig.scenario","").equals("content")?1_800_000_000_000L:600_000_000_000L)) throw new IllegalStateException("Live test deadline in phase "+phase+"; "+DhClientSession.status());
            if(System.getProperty("lss.rig.scenario","").equals("menu")) {menu(now);return;}
            if(!connecting && mc.screen instanceof TitleScreen) {
                // The backend bakes HAS_STATISTICS into shaders at construction.
                // Enabling it after joining leaves zero-filled counters forever.
                Class.forName("me.cortex.voxy.client.RenderStatistics").getField("enabled").setBoolean(null,true);
                connecting=true; String addr=System.getProperty("lss.rig.initialEndpoint");
                ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString(addr),new ServerData("DH private test",addr,ServerData.Type.OTHER),false,null);
            }
            if(now-logged>1_000_000_000L) { logged=now; write("dh-status.txt",phase+" "+DhClientSession.status()); }
            if(System.getProperty("lss.rig.scenario","").equals("boundary")) {BoundaryProbe.tick(now);return;}
            if(System.getProperty("lss.rig.scenario","").equals("updates")) {UpdatesProbe.tick(now);return;}
            if(System.getProperty("lss.rig.scenario","").equals("rejoin")) {RejoinProbe.tick(now);return;}
            if(System.getProperty("lss.rig.scenario","").equals("content")) {ContentProbe.tick(now);return;}
            if(System.getProperty("lss.rig.scenario","").equals("race")) {RaceProbe.tick(now);return;}
            if(System.getProperty("lss.rig.scenario","").equals("service")) {ServiceProbe.tick(now);return;}
            if(mc.level==null || mc.player==null) return;
            if(!positioned) {
                positioned=true; changed=now; mc.options.hideGui=true;
                mc.player.getAbilities().flying=true; mc.player.onUpdateAbilities();
                mc.getConnection().sendCommand("tp @s 0 100 0 90 15");
            }
            if(phase==0 || phase==3 || phase==4 || phase==5) {
                mc.player.getAbilities().flying=true;
                mc.player.setPos(0,100,0);mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                mc.player.setYRot(viewYaw);mc.player.setXRot(viewPitch);
                mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            }
            Object s=field(DhClientSession.class,"session");
            if(s!=null && !DhClientSession.running()) throw new IllegalStateException(DhClientSession.status());
            if(s==null && phase!=8 && phase!=14) return;
            if(System.getProperty("lss.rig.scenario","").equals("absence")) {absence(s,now);return;}
            if(phase==0 && now-changed>55_000_000_000L && count(s,"tiles")>=3 && DhVoxyBridge.backlog()==0) {
                int[] r=readback(s);
                write("dh-readback.json","{\"samples\":"+r[0]+",\"solid_samples\":"+r[1]+",\"columns\":"+r[2]+",\"sections\":"+r[3]+"}");
                aimAtDistantColumn(s);changed=now;phase=5;
            } else if(phase==5 && now-changed>3_000_000_000L) {
                renderEvidence();
                Screenshot.grab(mc.gameDirectory,"dh-render-on.png",mc.getMainRenderTarget(),ignored->{});
                rendering(false); changed=now; phase=3;
            } else if(phase==3 && now-changed>3_000_000_000L) {
                Screenshot.grab(mc.gameDirectory,"dh-render-off.png",mc.getMainRenderTarget(),ignored->{});
                rendering(true); changed=now; phase=4;
            } else if(phase==4 && now-changed>3_000_000_000L) {
                renderEvidence();
                Screenshot.grab(mc.gameDirectory,"dh-render-restored.png",mc.getMainRenderTarget(),ignored->{});
                previous=s; baseline=count(s,"tiles"); changed=now; phase=1;
                mc.getConnection().sendCommand("tp @s 512 100 0 90 15");
            } else if(phase==1 && now-changed>20_000_000_000L && count(s,"tiles")>baseline) {
                write("dh-movement.json","{\"pass\":true,\"new_tiles\":"+(count(s,"tiles")-baseline)+"}");
                DhClientSession.drainForReset();
                if(((DhMemoryBudget)field(s,"budget")).used()!=0) throw new IllegalStateException("Reset did not return DH memory");
                ClientCommandActions.runReset(ignored->{},true,false);
                phase=2; changed=now;
            } else if(phase==2 && s!=previous && count(s,"tiles")>=2 && now-changed>10_000_000_000L) {
                retired(previous);overworldPolicy=(DhSessionPolicy)field(s,"policy");originalConnection=mc.getConnection();
                write("dh-reset.json","{\"pass\":true,\"old_budget\":"+observedRetiredBytes+",\"new_session\":true}");
                // One save: two back-to-back saves race the first asynchronous reload
                // ("changed on disk"), and the second reload is then refused as busy.
                settings(Map.of("lod.distance_chunks",8,"lod.dh.sync_on_load",false));phase=15;changed=now;
            } else if(phase==15 && !LSSClientConfig.CONFIG.snapshot().lod().dh().syncOnLoad()
                    && !(boolean)field(s,"sentSync")) {
                // Observe the reloaded preference on a client tick before the
                // second reload. Back-to-back saves can supersede the first reload.
                settings(Map.of("lod.dh.sync_on_load",true));phase=6;changed=now;
            } else if(phase==6 && LSSClientConfig.CONFIG.snapshot().lod().dh().syncOnLoad()
                    && conditionalRequests.get()>0 && unchangedReplies.get()>0
                    && ((Map<?,?>)field(s,"pending")).isEmpty() && DhVoxyBridge.backlog()==0) {
                disabledDueReceipts=successfulSnapshots(s);
                if(disabledDueReceipts==0)throw new IllegalStateException("Disable control has no accepted snapshot");
                if(dueWithin(s,now+45_000_000_000L)!=0)throw new IllegalStateException("A settled snapshot has a periodic receipt timer");
                var snapshot=ClientStatus.latest();
                if(snapshot==null || snapshot.dh()==null || snapshot.dh().state()!=dev.vox.lss.common.diagnostics.DhStatusSnapshot.State.READY)
                    throw new IllegalStateException("Expected live READY DH diagnostics");
                ClientCommandActions.exportDiagnostics(ignored->{});
                settings(Map.of("lod.dh.sync_on_load",false));phase=7;changed=now;baseline=conditionalRequests.get();
            } else if(phase==7 && !LSSClientConfig.CONFIG.snapshot().lod().dh().syncOnLoad() && now-changed>45_000_000_000L) {
                if(conditionalRequests.get()!=baseline) throw new IllegalStateException("Synchronization continued after hot disable");
                if(dueWithin(s,Long.MAX_VALUE)!=0)throw new IllegalStateException("Hot disable left a successful receipt timer active");
                Path directory=mc.gameDirectory.toPath().resolve("lss-diagnostics");
                boolean exported=false;
                try(var files=Files.list(directory)) {
                    for(Path file:files.filter(p->p.toString().endsWith(".json")).toList()) {
                        var report=com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                        if(report.getAsJsonObject("dh").get("state").getAsString().equals("READY")
                                && report.get("protocol").getAsInt()==0 && !report.get("negotiated").getAsBoolean())exported=true;
                    }
                }
                if(!exported)throw new IllegalStateException("No live READY diagnostics export completed");
                write("dh-refresh.json","{\"pass\":true,\"behavior\":\"sync_on_load_without_periodic_timers\",\"in_session_config_reload\":true,\"hot_enable\":true,\"hot_disable\":true,\"conditional_requests\":"+conditionalRequests
                        +",\"unchanged_replies\":"+unchangedReplies+",\"accepted_snapshots_before_disable\":"+disabledDueReceipts
                        +",\"disable_observation_ms\":"+(now-changed)/1_000_000L+",\"ready_diagnostics_export\":"+exported+"}");
                previous=s;settings(Map.of("lod.receive",false));phase=8;changed=now;baseline=requests.get();
            } else if(phase==8 && !LSSClientConfig.CONFIG.receiveServerLods() && s==null && now-changed>3_000_000_000L) {
                retired(previous);if(requests.get()!=baseline)throw new IllegalStateException("Receive-off sent terrain requests");
                settings(Map.of("lod.receive",true));phase=9;changed=now;
            } else if(phase==9 && s!=previous && field(s,"levelKey")!=null && now-changed>3_000_000_000L) {
                if(field(s,"policy")!=overworldPolicy)throw new IllegalStateException("Receive toggle lost session receipts");
                write("dh-reception.json","{\"pass\":true,\"retired_budget\":"+observedRetiredBytes+",\"receipt_policy_retained\":true,\"ordinary_connected\":true}");
                noSkyBaseline=noSkySamples.get();previous=s;mc.getConnection().sendCommand("execute in minecraft:the_nether run tp @s 0 100 0");phase=10;changed=now;
            } else if(phase==10 && mc.level.dimension()==Level.NETHER && s!=previous && count(s,"tiles")>=1) {
                retired(previous);if(field(s,"policy")==overworldPolicy)throw new IllegalStateException("Nether reused Overworld receipts");
                long samples=noSkySamples.get()-noSkyBaseline;if(samples<=0)throw new IllegalStateException("No actual Nether sky samples");
                write("dh-nether.json","{\"pass\":true,\"tiles\":"+count(s,"tiles")+",\"old_budget\":"+observedRetiredBytes+",\"zero_sky_samples\":"+samples+"}");
                noSkyBaseline=noSkySamples.get();previous=s;mc.getConnection().sendCommand("execute in minecraft:the_end run tp @s 0 100 0");phase=11;changed=now;
            } else if(phase==11 && mc.level.dimension()==Level.END && s!=previous && count(s,"tiles")>=1) {
                retired(previous);long samples=noSkySamples.get()-noSkyBaseline;if(samples<=0)throw new IllegalStateException("No actual End sky samples");
                write("dh-end.json","{\"pass\":true,\"tiles\":"+count(s,"tiles")+",\"old_budget\":"+observedRetiredBytes+",\"zero_sky_samples\":"+samples+"}");
                previous=s;mc.getConnection().sendCommand("execute in minecraft:overworld run tp @s 512 100 0");phase=12;changed=now;
            } else if(phase==12 && mc.level.dimension()==Level.OVERWORLD && s!=previous && field(s,"levelKey")!=null && now-changed>3_000_000_000L) {
                retired(previous);if(field(s,"policy")!=overworldPolicy)throw new IllegalStateException("Portal return lost Overworld authority");
                sodiumApply(Map.of("lss:lod_source",0));phase=13;changed=now;
            } else if(phase==13 && LSSClientConfig.CONFIG.configured().lod().source().equals("lss")) {
                if(!DhClientSession.selected())throw new IllegalStateException("Source switched before physical reconnect");
                previous=s;baseline=requests.get();
                mc.level.disconnect();mc.disconnect();
                String addr=System.getProperty("lss.rig.initialEndpoint");
                ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(addr),new ServerData("DH private test",addr,ServerData.Type.OTHER),false,null);
                phase=14;changed=now;
            } else if(phase==14 && !DhClientSession.selected() && mc.getConnection()!=originalConnection && now-changed>5_000_000_000L) {
                retired(previous);
                if(s!=null || ClientNetGlue.hasReceivedSessionConfig() || ClientNetGlue.getRequestManager()!=null
                        || requests.get()!=baseline)throw new IllegalStateException("Native no-service/source-switch isolation failed");
                write("dh-source-switch.json","{\"pass\":true,\"reconnect_required\":true,\"native_source\":true,\"no_lss_server\":true,\"dh_requests_stopped\":true}");
                write("dh-result.json","{\"pass\":true,\"readback\":true,\"render_comparison\":true,\"movement\":true,\"reset\":true,\"refresh\":true,\"receive_toggle\":true,\"nether\":true,\"end\":true,\"portal_return\":true,\"source_switch\":true,\"ordinary_connected\":true}");
                done=true;mc.stop();
            }
        } catch(Throwable e) {
            e.printStackTrace(); done=true;
            try { write("dh-result.json","{\"pass\":false,\"error\":\""+e.toString().replace("\\","/").replace("\"","'")+"\"}"); } catch(Exception ignored) {}
            mc.stop();
        }
    }
}
