package dev.vox.lss.common.config;

import java.util.*;

/** Immutable settings; mutable document nodes never enter runtime snapshots. */
public record ClientSettings(Lod lod, Scan scan, Cache cache, Compatibility compatibility, Integrations integrations, FarPlayers farPlayers) {
    public record LodDownload(int maxColumnsPerSecond, boolean adaptiveRate, boolean slowStartOnJoin, boolean ingestBackpressure) {}
    public record LodDh(boolean syncOnLoad, boolean realTimeUpdates) {}
    public record Lod(boolean receive, int distanceChunks, LodDownload download, String source, LodDh dh, boolean voxyBorderFill) {}
    public record Scan(boolean regionOrder, boolean adaptiveCadence, boolean retainCompletedPrefix, boolean quadtree, boolean regionSummaries) {}
    public record Cache(boolean splitByWorld, List<List<String>> addressAliases) {
        public Cache { addressAliases = addressAliases.stream().map(List::copyOf).toList(); }
    }
    public record CompatibilityProtocols(boolean v16, boolean v19) {}
    public record CompatibilityBlockFallbacks(String defaultBlock, Map<String, String> overrides) {
        public CompatibilityBlockFallbacks { overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides)); }
    }
    public record Compatibility(CompatibilityProtocols protocols, boolean v16Generation, CompatibilityBlockFallbacks blockFallbacks) {}
    public record IntegrationsMapIngestion(boolean enabled, boolean backpressure, int netherStoreMb) {}
    public record Integrations(IntegrationsMapIngestion mapIngestion) {}
    public record FarPlayersDistance(int minBlocks, int maxBlocks) {}
    public record FarPlayersSharing(boolean enabled, int maxDistanceBlocks) {}
    public record FarPlayers(boolean enabled, FarPlayersDistance distance, boolean nameTags, boolean fullBright, int renderDistanceBlocks, int animationDistanceBlocks, FarPlayersSharing sharing) {}
    public static ClientSettings fromValues(Map<String,Object> values) { return ClientSettings(values); }
    public Map<String,Object> values() {
        var v = new LinkedHashMap<String,Object>();
        var s = this;
        v.put("lod.source", s.lod().source());
        v.put("lod.dh.sync_on_load", s.lod().dh().syncOnLoad());
        v.put("lod.dh.real_time_updates", s.lod().dh().realTimeUpdates());
        v.put("lod.voxy_border_fill", s.lod().voxyBorderFill());
        v.put("lod.receive", s.lod().receive());
        v.put("lod.distance_chunks", s.lod().distanceChunks());
        v.put("lod.download.max_columns_per_second", s.lod().download().maxColumnsPerSecond());
        v.put("lod.download.adaptive_rate", s.lod().download().adaptiveRate());
        v.put("lod.download.slow_start_on_join", s.lod().download().slowStartOnJoin());
        v.put("lod.download.ingest_backpressure", s.lod().download().ingestBackpressure());
        v.put("scan.region_order", s.scan().regionOrder());
        v.put("scan.adaptive_cadence", s.scan().adaptiveCadence());
        v.put("scan.retain_completed_prefix", s.scan().retainCompletedPrefix());
        v.put("scan.quadtree", s.scan().quadtree());
        v.put("scan.region_summaries", s.scan().regionSummaries());
        v.put("cache.split_by_world", s.cache().splitByWorld());
        v.put("cache.address_aliases", s.cache().addressAliases());
        v.put("compatibility.protocols.v16", s.compatibility().protocols().v16());
        v.put("compatibility.protocols.v19", s.compatibility().protocols().v19());
        v.put("compatibility.v16_generation", s.compatibility().v16Generation());
        v.put("compatibility.block_fallbacks.default", s.compatibility().blockFallbacks().defaultBlock());
        v.put("compatibility.block_fallbacks.overrides", s.compatibility().blockFallbacks().overrides());
        v.put("integrations.map_ingestion.enabled", s.integrations().mapIngestion().enabled());
        v.put("integrations.map_ingestion.backpressure", s.integrations().mapIngestion().backpressure());
        v.put("integrations.map_ingestion.nether_store_mb", s.integrations().mapIngestion().netherStoreMb());
        v.put("far_players.enabled", s.farPlayers().enabled());
        v.put("far_players.distance.min_blocks", s.farPlayers().distance().minBlocks());
        v.put("far_players.distance.max_blocks", s.farPlayers().distance().maxBlocks());
        v.put("far_players.name_tags", s.farPlayers().nameTags());
        v.put("far_players.full_bright", s.farPlayers().fullBright());
        v.put("far_players.render_distance_blocks", s.farPlayers().renderDistanceBlocks());
        v.put("far_players.animation_distance_blocks", s.farPlayers().animationDistanceBlocks());
        v.put("far_players.sharing.enabled", s.farPlayers().sharing().enabled());
        v.put("far_players.sharing.max_distance_blocks", s.farPlayers().sharing().maxDistanceBlocks());
        return Collections.unmodifiableMap(v);
    }
    @SuppressWarnings("unchecked")
    private static LodDownload LodDownload(Map<String,Object> v) {
        return new LodDownload((int)v.get("lod.download.max_columns_per_second"), (boolean)v.get("lod.download.adaptive_rate"), (boolean)v.get("lod.download.slow_start_on_join"), (boolean)v.get("lod.download.ingest_backpressure"));
    }
    @SuppressWarnings("unchecked")
    private static Lod Lod(Map<String,Object> v) {
        return new Lod((boolean)v.get("lod.receive"), (int)v.get("lod.distance_chunks"), LodDownload(v), (String)v.get("lod.source"), new LodDh((boolean)v.get("lod.dh.sync_on_load"), (boolean)v.get("lod.dh.real_time_updates")), (boolean)v.get("lod.voxy_border_fill"));
    }
    @SuppressWarnings("unchecked")
    private static Scan Scan(Map<String,Object> v) {
        return new Scan((boolean)v.get("scan.region_order"), (boolean)v.get("scan.adaptive_cadence"), (boolean)v.get("scan.retain_completed_prefix"), (boolean)v.get("scan.quadtree"), (boolean)v.get("scan.region_summaries"));
    }
    @SuppressWarnings("unchecked")
    private static Cache Cache(Map<String,Object> v) {
        return new Cache((boolean)v.get("cache.split_by_world"), (List<List<String>>)v.get("cache.address_aliases"));
    }
    @SuppressWarnings("unchecked")
    private static CompatibilityProtocols CompatibilityProtocols(Map<String,Object> v) {
        return new CompatibilityProtocols((boolean)v.get("compatibility.protocols.v16"), (boolean)v.get("compatibility.protocols.v19"));
    }
    @SuppressWarnings("unchecked")
    private static CompatibilityBlockFallbacks CompatibilityBlockFallbacks(Map<String,Object> v) {
        return new CompatibilityBlockFallbacks((String)v.get("compatibility.block_fallbacks.default"), (Map<String, String>)v.get("compatibility.block_fallbacks.overrides"));
    }
    @SuppressWarnings("unchecked")
    private static Compatibility Compatibility(Map<String,Object> v) {
        return new Compatibility(CompatibilityProtocols(v), (boolean)v.get("compatibility.v16_generation"), CompatibilityBlockFallbacks(v));
    }
    @SuppressWarnings("unchecked")
    private static IntegrationsMapIngestion IntegrationsMapIngestion(Map<String,Object> v) {
        return new IntegrationsMapIngestion((boolean)v.get("integrations.map_ingestion.enabled"), (boolean)v.get("integrations.map_ingestion.backpressure"),
                (int)v.get("integrations.map_ingestion.nether_store_mb"));
    }
    @SuppressWarnings("unchecked")
    private static Integrations Integrations(Map<String,Object> v) {
        return new Integrations(IntegrationsMapIngestion(v));
    }
    @SuppressWarnings("unchecked")
    private static FarPlayersDistance FarPlayersDistance(Map<String,Object> v) {
        return new FarPlayersDistance((int)v.get("far_players.distance.min_blocks"), (int)v.get("far_players.distance.max_blocks"));
    }
    @SuppressWarnings("unchecked")
    private static FarPlayersSharing FarPlayersSharing(Map<String,Object> v) {
        return new FarPlayersSharing((boolean)v.get("far_players.sharing.enabled"), (int)v.get("far_players.sharing.max_distance_blocks"));
    }
    @SuppressWarnings("unchecked")
    private static FarPlayers FarPlayers(Map<String,Object> v) {
        return new FarPlayers((boolean)v.get("far_players.enabled"), FarPlayersDistance(v), (boolean)v.get("far_players.name_tags"), (boolean)v.get("far_players.full_bright"), (int)v.get("far_players.render_distance_blocks"), (int)v.get("far_players.animation_distance_blocks"), FarPlayersSharing(v));
    }
    @SuppressWarnings("unchecked")
    private static ClientSettings ClientSettings(Map<String,Object> v) {
        return new ClientSettings(Lod(v), Scan(v), Cache(v), Compatibility(v), Integrations(v), FarPlayers(v));
    }
}
