package dev.vox.lssfixture.dh;

import dev.vox.lss.networking.client.DhClientSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import java.util.*;

/**
 * Measurement scenarios (2026-10-10, review X-16 / §7). Never a correctness gate.
 *
 * <p>{@code load}: fixed cold window, then an unchanged rejoin and a fixed warm window, on
 * either arm (LSS adapter, or the real official DH client). Phase boundaries are written as
 * epoch milliseconds; the supervisor attributes server CPU and proxied bytes to them.
 *
 * <p>{@code movement}: settle, then fly straight at a fixed speed while timing every
 * {@code DhClientSession.tick}, border-pump call and frame (timing mixins), flagging calls
 * that coincide with a chunk crossing.
 */
public final class MeasureProbe {
    private MeasureProbe() {}
    static final String ARM = System.getProperty("lss.rig.arm", "lss");
    private static int phase; private static long phaseAt, coldStart, coldEnd, warmStart;
    private static boolean reconnecting;
    // ---- movement timing (written by mixins on the client thread) ----
    static final List<long[]> dhTicks = new ArrayList<>(), pumpCalls = new ArrayList<>(), frames = new ArrayList<>();
    static volatile boolean recording;
    private static long lastFrame, flightStart; private static int lastChunkX = Integer.MIN_VALUE, crossings;
    private static double flown, startX, startZ;

    static long epoch() { return System.currentTimeMillis(); }
    private static long seconds(String key, long fallback) { return Long.getLong(key, fallback) * 1_000_000_000L; }

    static void tick(long now) throws Exception {
        if (System.getProperty("lss.rig.scenario", "").equals("load")) load(now); else movement(now);
    }

    private static void load(long now) throws Exception {
        var mc = Minecraft.getInstance();
        if (phase == 0) { if (mc.level == null || mc.player == null) return; phase = 1; phaseAt = now; coldStart = epoch();
            Probe.write("dh-load-phase.json", "{\"phase\":\"cold\",\"epoch_ms\":" + coldStart + "}"); return; }
        if (phase == 1) {
            if (now - phaseAt < seconds("lss.rig.coldSeconds", 420)) return;
            coldEnd = epoch(); snapshot("cold");
            mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE); mc.setScreen(new TitleScreen()); phase = 2; phaseAt = now; return;
        }
        if (phase == 2) {
            if (!reconnecting && now - phaseAt > 3_000_000_000L) {
                reconnecting = true; String address = System.getProperty("lss.rig.initialEndpoint");
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
                        new ServerData("DH load rejoin", address, ServerData.Type.OTHER), false, null);
            }
            if (mc.level != null && mc.player != null) { phase = 3; phaseAt = now; warmStart = epoch();
                Probe.write("dh-load-phase.json", "{\"phase\":\"warm\",\"epoch_ms\":" + warmStart + "}"); }
            return;
        }
        if (phase == 3 && now - phaseAt >= seconds("lss.rig.warmSeconds", 180)) {
            long warmEnd = epoch(); snapshot("warm");
            Probe.write("dh-load.json", "{\"pass\":true,\"arm\":\"" + ARM + "\",\"cold_start_ms\":" + coldStart + ",\"cold_end_ms\":" + coldEnd
                    + ",\"warm_start_ms\":" + warmStart + ",\"warm_end_ms\":" + warmEnd + "}");
            Probe.write("dh-result.json", "{\"pass\":true,\"case\":\"load\",\"arm\":\"" + ARM + "\"}");
            phase = 4; Probe.done = true; mc.stop();
        }
    }

    private static void snapshot(String name) throws Exception {
        if (!ARM.equals("lss")) return;
        var d = DhClientSession.diagnostics();
        Probe.write("dh-load-" + name + ".json", "{\"requested\":" + d.requestedTiles() + ",\"enqueued\":" + d.enqueuedTiles()
                + ",\"columns\":" + d.enqueuedColumns() + ",\"wire_bytes\":" + d.wireBytes() + ",\"stored_receipts\":" + d.storedReceipts()
                + ",\"restored\":" + d.restoredReceipts() + ",\"state\":\"" + d.state() + "\",\"activity\":\"" + d.activity() + "\"}");
    }

    private static void movement(long now) throws Exception {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        var player = mc.player;
        if (phase == 0) { phase = 1; phaseAt = now; player.getAbilities().flying = true; player.onUpdateAbilities(); return; }
        if (phase == 1) {
            player.setDeltaMovement(0, 0, 0);
            if (now - phaseAt < seconds("lss.rig.settleSeconds", 120)) return;
            phase = 2; flightStart = now; recording = true; lastChunkX = player.chunkPosition().x(); startX = player.getX(); startZ = player.getZ(); return;
        }
        if (phase == 2) {
            double speed = Double.parseDouble(System.getProperty("lss.rig.speed", "1.5")); // blocks per tick (~30 b/s)
            // A server-side teleport per tick: client-only position changes are rubber-banded
            // back by the server's moved-wrongly check, so the player would never cross a chunk.
            flown += speed;
            mc.getConnection().sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.2f 120 %.2f", startX + flown, startZ));
            int cx = player.chunkPosition().x(); if (cx != lastChunkX) { crossings++; lastChunkX = cx; }
            if (flown < Double.parseDouble(System.getProperty("lss.rig.flightBlocks", "1536"))) return;
            recording = false; phase = 3;
            var out = new LinkedHashMap<String, Object>();
            out.put("pass", true); out.put("arm", ARM); out.put("crossings", crossings);
            out.put("flight_seconds", (now - flightStart) / 1e9);
            out.put("dh_tick_ms", stats(dhTicks)); out.put("dh_tick_on_crossing_ms", stats(filter(dhTicks, true)));
            out.put("dh_tick_off_crossing_ms", stats(filter(dhTicks, false)));
            out.put("dh_tick_top", top(dhTicks)); out.put("border_pump_ms", stats(pumpCalls)); out.put("border_pump_top", top(pumpCalls));
            out.put("frame_interval_ms", stats(frames)); out.put("frame_top", top(frames));
            out.put("dh_state_after", String.valueOf(DhClientSession.diagnostics().state()));
            Probe.write("dh-movement-metrics.json", new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(out));
            Probe.write("dh-result.json", "{\"pass\":true,\"case\":\"movement\",\"arm\":\"" + ARM + "\"}");
            Probe.done = true; mc.stop();
        }
    }

    // ---- timing hooks (client thread) ----
    private static long dhStart, pumpStart; private static int startChunk;
    public static void dhTickStart() { if (recording) { dhStart = System.nanoTime(); startChunk = chunkX(); } }
    public static void dhTickEnd() { if (recording && dhStart != 0) { synchronized (dhTicks) { dhTicks.add(new long[]{System.nanoTime() - dhStart, crossedSinceLast(dhTicks)}); } dhStart = 0; } }
    public static void pumpStart() { if (recording) pumpStart = System.nanoTime(); }
    public static void pumpEnd() { if (recording && pumpStart != 0) { synchronized (pumpCalls) { pumpCalls.add(new long[]{System.nanoTime() - pumpStart, 0}); } pumpStart = 0; } }
    public static void frame() {
        long now = System.nanoTime();
        if (recording && lastFrame != 0) synchronized (frames) { frames.add(new long[]{now - lastFrame, 0}); }
        lastFrame = now;
    }
    private static int lastTickChunk = Integer.MIN_VALUE;
    private static long crossedSinceLast(List<long[]> ignored) {
        int cx = chunkX(); boolean crossed = lastTickChunk != Integer.MIN_VALUE && cx != lastTickChunk; lastTickChunk = cx; return crossed ? 1 : 0;
    }
    private static int chunkX() { var p = Minecraft.getInstance().player; return p == null ? 0 : p.chunkPosition().x(); }
    private static List<long[]> filter(List<long[]> rows, boolean crossing) {
        synchronized (rows) { return rows.stream().filter(r -> (r[1] == 1) == crossing).toList(); }
    }
    private static Map<String, Object> stats(List<long[]> rows) {
        double[] ms; synchronized (rows) { ms = rows.stream().mapToDouble(r -> r[0] / 1e6).sorted().toArray(); }
        var out = new LinkedHashMap<String, Object>(); out.put("samples", ms.length);
        if (ms.length == 0) return out;
        out.put("p50", q(ms, .5)); out.put("p95", q(ms, .95)); out.put("p99", q(ms, .99)); out.put("max", ms[ms.length - 1]);
        out.put("mean", Arrays.stream(ms).average().orElse(0)); out.put("over_5ms", Arrays.stream(ms).filter(v -> v > 5).count());
        out.put("over_16ms", Arrays.stream(ms).filter(v -> v > 16).count());
        return out;
    }
    private static List<Map<String, Object>> top(List<long[]> rows) {
        List<long[]> copy; synchronized (rows) { copy = new ArrayList<>(rows); }
        copy.sort((a, b) -> Long.compare(b[0], a[0]));
        var out = new ArrayList<Map<String, Object>>();
        for (var r : copy.subList(0, Math.min(10, copy.size()))) out.add(Map.of("ms", r[0] / 1e6, "on_crossing", r[1] == 1));
        return out;
    }
    private static double q(double[] a, double p) { return a[Math.min(a.length - 1, (int) Math.ceil(a.length * p) - 1)]; }
}
