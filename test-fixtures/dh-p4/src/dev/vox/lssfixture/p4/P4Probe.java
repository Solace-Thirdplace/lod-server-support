package dev.vox.lssfixture.p4;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Run-owned P4 control; contains no production change and never handles desktop input. */
public final class P4Probe {
    public static boolean done;
    static long started,changed; static int phase;
    public static String mode(){return System.getProperty("lss.p4.case","legacy-menu");}
    public static Object field(Object o,String n) throws Exception {
        Class<?> c=o instanceof Class<?> k?k:o.getClass();
        for(;c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o instanceof Class<?>?null:o);}catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(n);
    }
    public static void write(String name,Object value) throws Exception {
        Path p=Minecraft.getInstance().gameDirectory.toPath().resolve(name),tmp=p.resolveSibling(name+".tmp");
        Files.writeString(tmp,new Gson().toJson(value));Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    public static void pass(Map<String,Object> details) throws Exception {
        var row=new LinkedHashMap<String,Object>(details);row.put("pass",true);row.put("case",mode()); if(Boolean.getBoolean("lss.p4.windowsNative")){row.put("gpu_renderer",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));row.put("gpu_vendor",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR));}
        write("dh-p4.json",row);write("dh-result.json",row);done=true;Minecraft.getInstance().stop();
    }
    public static void tick() {
        if(done)return;long now=System.nanoTime();if(started==0)started=now;
        try {
            if(now-started>(mode().equals("cache-rejoin-range")?3_600_000_000_000L:900_000_000_000L))throw new IllegalStateException("P4 deadline, phase "+phase);
            var mc=Minecraft.getInstance();
            if(System.getProperty("lss.p4.backend", "lss").equals("official")){Class.forName("privatep4.OfficialClient").getMethod("tick",long.class).invoke(null,now);return;}
            if(mode().equals("legacy-menu")){legacy(now);return;}
            if(mc.level==null && mc.screen instanceof TitleScreen && phase==0) {phase=1;changed=now;
                String address=System.getProperty("lss.rig.initialEndpoint","[::1]:25586");
                ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString(address),new ServerData("Private P4",address,ServerData.Type.OTHER),false,null);return;}
            if(mc.level==null||mc.player==null)return;
            // Additional cases use separate controllers with no shared production state.
            if(mode().equals("content")) ContentOracle.tick(now);
            else if((mode().equals("cache-pressure") || mode().equals("cache-rejoin-range"))) CachePressure.tick(now);
            else if(mode().equals("benchmark")) BenchmarkClient.tick(now);
            else if(mode().equals("configuration")) ConfigurationProbe.tick(now);
            else throw new IllegalArgumentException(mode());
        } catch(Throwable failure) {
            try{write("dh-result.json",Map.of("pass",false,"case",mode(),"phase",phase,"error",failure.toString(),"stack",java.util.Arrays.stream(failure.getStackTrace()).limit(64).map(Object::toString).toList()));}catch(Exception ignored){}
            failure.printStackTrace();done=true;Minecraft.getInstance().stop();
        }
    }
    static Map<String,Object> menuOptions;static Object menu;
    static boolean screenshotStarted;
    static final java.util.concurrent.atomic.AtomicBoolean screenshotSaved=new java.util.concurrent.atomic.AtomicBoolean();
    static void legacy(long now) throws Exception {
        var mc=Minecraft.getInstance();
        Class<?> screen=Class.forName("net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI");
        if(phase==0 && mc.screen instanceof TitleScreen) {
            menu=screen.getMethod("createScreen",Screen.class).invoke(null,mc.screen);mc.setScreen((Screen)menu);
            var pages=(List<?>)field(menu,"pages");var selected=new ArrayList<Object>();
            for(var page:pages){String label=((Component)page.getClass().getMethod("getName").invoke(page)).getString();if(label.startsWith("LSS")||label.startsWith("LOD")||label.equals("Far Players"))selected.add(page);}
            if(selected.size()!=2)throw new IllegalStateException("Expected 2 LSS pages, got "+selected.size()+"; "+pages);
            menuOptions=new LinkedHashMap<>();List<String> labels=new ArrayList<>();
            for(var page:selected){labels.add(((Component)page.getClass().getMethod("getName").invoke(page)).getString());
                for(var option:(List<?>)page.getClass().getMethod("getOptions").invoke(page)){
                    var type=Class.forName("net.caffeinemc.mods.sodium.client.gui.options.Option");String name=((Component)type.getMethod("getName").invoke(option)).getString();menuOptions.put(name,option);
                }}
            for(String name:List.of("Use Distant Horizons")) {
                Object option=menuOptions.get(name);if(option==null)throw new IllegalStateException("Missing actual translated option "+name);
                Object control=option.getClass().getMethod("getControl").invoke(option);if(!control.getClass().getSimpleName().equals("TickBoxControl"))throw new IllegalStateException("Not a checkbox: "+name);
            }
            if(menuOptions.size()!=11 || menuOptions.containsKey("Sync on load") || menuOptions.containsKey("Live updates"))throw new IllegalStateException("Unexpected legacy controls: "+menuOptions.keySet());
            Object receive=menuOptions.get("Receive Server LODs");if(receive==null)receive=menuOptions.get("Receive server LODs");
            if(receive==null)throw new IllegalStateException("Missing receive master: "+menuOptions.keySet());
            var optionType=Class.forName("net.caffeinemc.mods.sodium.client.gui.options.Option");optionType.getMethod("setValue",Object.class).invoke(receive,false);
            for(String name:List.of("Use Distant Horizons"))if((boolean)optionType.getMethod("isAvailable").invoke(menuOptions.get(name)))throw new IllegalStateException("Dependency stayed enabled");
            optionType.getMethod("setValue",Object.class).invoke(receive,true);
            for(String name:List.of("Use Distant Horizons")){
                if(!(boolean)optionType.getMethod("isAvailable").invoke(menuOptions.get(name)))throw new IllegalStateException("Dependency stayed disabled");
                optionType.getMethod("setValue",Object.class).invoke(menuOptions.get(name),false);
            }
            var apply=screen.getDeclaredMethod("applyChanges");apply.setAccessible(true);apply.invoke(menu);
            screen.getMethod("setPage",Class.forName("net.caffeinemc.mods.sodium.client.gui.options.OptionPage")).invoke(menu,selected.get(0));
            var layoutRows=new ArrayList<Map<String,Object>>();
            // Audit every page, including General/Quality footer options. Hidden
            // tabs may be paginated; the selected tab and arrows must remain reachable.
            for(Object page:pages) {
                screen.getMethod("setPage",Class.forName("net.caffeinemc.mods.sodium.client.gui.options.OptionPage")).invoke(menu,page);
                var visibleTabs=new ArrayList<Map<String,Object>>();var occupied=new ArrayList<int[]>();boolean selectedVisible=false;
                String pageName=((Component)page.getClass().getMethod("getName").invoke(page)).getString();
                for(Object child:((net.minecraft.client.gui.screens.Screen)menu).children()) {
                    Object dim;
                    try {dim=field(child,"dim");}catch(NoSuchFieldException missing){try{dim=child.getClass().getMethod("getDimensions").invoke(child);}catch(NoSuchMethodException absent){continue;}}
                    int x=(int)dim.getClass().getMethod("x").invoke(dim),y=(int)dim.getClass().getMethod("y").invoke(dim),width=(int)dim.getClass().getMethod("width").invoke(dim),height=(int)dim.getClass().getMethod("height").invoke(dim);
                    boolean button=child.getClass().getSimpleName().equals("FlatButtonWidget");
                    if(button && !(boolean)field(child,"visible"))continue;
                    if(child==field(menu,"donateButton") || child==field(menu,"hideDonateButton"))continue;
                    occupied.add(new int[]{x,y,width,height});
                    if(button && y==6) {
                        Component label=(Component)child.getClass().getMethod("getLabel").invoke(child);
                        if(x<0 || x+width>mc.getWindow().getGuiScaledWidth() || mc.font.width(label)>width-8)throw new IllegalStateException("Visible legacy tab/arrow clips: "+label.getString());
                        for(var tab:visibleTabs) {int left=(int)tab.get("x"),span=(int)tab.get("width");if(x<left+span && left<x+width)throw new IllegalStateException("Legacy tabs/arrows overlap: "+pageName);}
                        selectedVisible|=label.getString().equals(pageName);
                        visibleTabs.add(Map.of("label",label.getString(),"x",x,"width",width,"text_width",mc.font.width(label)));
                    }
                }
                if(!selectedVisible)throw new IllegalStateException("Selected legacy page has no visible tab: "+pageName);
                var donations=new ArrayList<Map<String,Object>>();
                for(String name:List.of("donateButton","hideDonateButton")) {
                    Object button=field(menu,name),dim=field(button,"dim");boolean visible=(boolean)field(button,"visible");
                    int x=(int)dim.getClass().getMethod("x").invoke(dim),y=(int)dim.getClass().getMethod("y").invoke(dim),width=(int)dim.getClass().getMethod("width").invoke(dim),height=(int)dim.getClass().getMethod("height").invoke(dim);
                    if(visible)for(int[] other:occupied)if(x<other[0]+other[2] && other[0]<x+width && y<other[1]+other[3] && other[1]<y+height)throw new IllegalStateException("Donation widget covers page controls: "+pageName);
                    donations.add(Map.of("name",name,"x",x,"y",y,"width",width,"visible",visible));
                }
                layoutRows.add(Map.of("page",pageName,"visible_tabs",visibleTabs,"donation_controls",donations));
            }
            screen.getMethod("setPage",Class.forName("net.caffeinemc.mods.sodium.client.gui.options.OptionPage")).invoke(menu,selected.get(0));
            write("dh-legacy-pages.json",Map.of("pages",labels,"controls",menuOptions.keySet(),"all_page_layouts",layoutRows,"width",mc.getWindow().getGuiScaledWidth(),"height",mc.getWindow().getGuiScaledHeight()));phase=1;changed=now;
        } else if(phase==1 && now-changed>3_000_000_000L) {
            var handle=Class.forName("dev.vox.lss.config.LSSClientConfig").getField("CONFIG").get(null);Object setting=handle.getClass().getMethod("snapshot").invoke(handle),lod=setting.getClass().getMethod("lod").invoke(setting),dh=lod.getClass().getMethod("dh").invoke(lod);
            if(!lod.getClass().getMethod("source").invoke(lod).equals("lss"))throw new IllegalStateException("Use Distant Horizons save failed");
            if(!(boolean)dh.getClass().getMethod("syncOnLoad").invoke(dh)||!(boolean)dh.getClass().getMethod("realTimeUpdates").invoke(dh))throw new IllegalStateException("Menu changed config-only synchronization preferences");
            if(mc.options.guiScale().get()!=0 || mc.getWindow().getGuiScale()<3)throw new IllegalStateException("Auto scale not exercised");
            if(!screenshotStarted) {
                org.lwjgl.opengl.GL11.glFinish();
                Screenshot.grab(mc.gameDirectory,"dh-legacy-menu.png",mc.getMainRenderTarget(),x->screenshotSaved.set(true));
                screenshotStarted=true;return;
            }
            if(!screenshotSaved.get())return;
            if(!Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots/dh-legacy-menu.png")))throw new IllegalStateException("Legacy screenshot did not finish saving");
            pass(Map.of("actual_legacy_sodium",true,"pages",2,"general_dh_checkbox",true,"sync_and_updates_preserved_on",true,"receive_dependency",true,"shared_apply_saved",true,"auto_gui_scale",mc.getWindow().getGuiScale()));
        }
    }
}
