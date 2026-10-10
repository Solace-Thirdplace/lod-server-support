package dev.vox.lss.common.dh;

import com.google.gson.GsonBuilder;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Offline allocation/CPU probe using retained frames; never packaged or a GPU/server benchmark. */
public final class DhFragmentAllocationProbe {
    private static final com.sun.management.ThreadMXBean METER =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long THREAD = Thread.currentThread().threadId();
    // The persisted DTO cache phase was removed with the cache (receipts are timestamps only).
    private static final String[] PHASES = {"capture", "envelope", "assembly", "flatten", "terrain"};
    private static volatile String consumed;
    private record Row(String directory, DhProfile profile, int first, int last) {}
    private static final class Sample {
        final long[] allocated = new long[PHASES.length], cpu = new long[PHASES.length];
        long peak, fragmentBytes;
        int fragments;
        String normalized;
        void phase(int phase, long bytes, long nanos) {
            allocated[phase] += METER.getThreadAllocatedBytes(THREAD) - bytes;
            cpu[phase] += METER.getCurrentThreadCpuTime() - nanos;
        }
    }
    public static void main(String[] args) throws Exception {
        // args[1] (scratch) is unused since the cache phase was removed; kept for CLI compatibility.
        Path corpus = Path.of(args[0]);
        int rounds = Integer.parseInt(args[2]), samples = Integer.parseInt(args[3]);
        if (rounds < 1 || rounds > 1000 || samples < 1 || samples > 20)
            throw new IllegalArgumentException("bounded rounds/samples required");
        if (!METER.isThreadAllocatedMemorySupported() || !METER.isCurrentThreadCpuTimeSupported())
            throw new IllegalStateException("allocation/CPU counters unavailable");
        METER.setThreadAllocatedMemoryEnabled(true);METER.setThreadCpuTimeEnabled(true);
        var rows = List.of(new Row("dh-official-3.3.3-neoforge-partial",DhProfile.OFFICIAL_16,4,6),
                new Row("dh-official-3.2.0-b-neoforge",DhProfile.OFFICIAL_15,4,6),
                new Row("dh-support-0.14.0-meadow",DhProfile.SUPPORT_15,3,6),
                new Row("dh-support-0.14.0-ocean",DhProfile.SUPPORT_15,3,4));
        var results = new ArrayList<Object>();var hashes = new TreeMap<String,String>();
        for (var row : rows) {
            var frames = new ArrayList<byte[]>();
            for (int i=row.first;i<=row.last;i++) {
                var path=corpus.resolve(row.directory).resolve(String.format("received-%04d.bin",i));
                byte[] bytes=Files.readAllBytes(path);frames.add(bytes);
                hashes.put(corpus.relativize(path).toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
            var codec=new DhProtocolCodec(row.profile,DhLimits.DEFAULT);
            var split=(DhMessage.Split)codec.decode(frames.getFirst());
            var position=DhPosition.unpack(ByteBuffer.wrap(split.bytes()).getLong());
            var reply=(DhMessage.Response)codec.decode(frames.getLast());
            var shape=new DhTerrainTile.WorldShape(-64,384,true);
            for(int i=0;i<30;i++) replay(frames,codec,row.profile,position,reply,shape,new Sample());
            for(int sample=0;sample<samples;sample++) {
                var stats=new Sample();
                for(int i=0;i<rounds;i++)replay(frames,codec,row.profile,position,reply,shape,stats);
                var phases=new LinkedHashMap<String,Object>();
                for(int i=0;i<PHASES.length;i++)phases.put(PHASES[i],Map.of("allocated_bytes_per_tile",stats.allocated[i]/(double)rounds,
                        "cpu_ns_per_tile",stats.cpu[i]/(double)rounds));
                results.add(Map.of("corpus",row.directory,"sample",sample,"rounds",rounds,"phases",phases,
                        "peak_owned_bytes",stats.peak,"fragment_bytes",stats.fragmentBytes,"fragments",stats.fragments,"normalized",stats.normalized));
            }
        }
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "java",System.getProperty("java.version"),"warmup_per_corpus",30,"corpus_sha256",hashes,"results",results,
                "scope","offline Java allocation and thread CPU; no GPU, network, generation or frame-time claim")));
    }
    private static void replay(List<byte[]> frames,DhProtocolCodec codec,DhProfile profile,DhPosition position,
            DhMessage.Response reply,DhTerrainTile.WorldShape shape,Sample stats) throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,budget)) {
            assembly.expect(reply.requestId(),position);int fragments=0;long fragmentBytes=0;
            for(int i=0;i<frames.size()-1;i++) {
                long bytes=METER.getThreadAllocatedBytes(THREAD),cpu=METER.getCurrentThreadCpuTime();
                try(var capture=budget.reserve(frames.get(i).length+256L)) {
                    byte[] captured=frames.get(i).clone();stats.phase(0,bytes,cpu);
                    bytes=METER.getThreadAllocatedBytes(THREAD);cpu=METER.getCurrentThreadCpuTime();
                    try(var message=codec.decodeOwned(captured,budget)) {
                        stats.phase(1,bytes,cpu);var split=(DhMessage.Split)message.message();
                        fragments++;fragmentBytes+=split.length();
                        bytes=METER.getThreadAllocatedBytes(THREAD);cpu=METER.getCurrentThreadCpuTime();
                        assembly.accept(split);stats.phase(2,bytes,cpu);
                    }
                }
            }
            long bytes=METER.getThreadAllocatedBytes(THREAD),cpu=METER.getCurrentThreadCpuTime();
            try(var complete=assembly.response(reply)) {
                stats.phase(3,bytes,cpu);bytes=METER.getThreadAllocatedBytes(THREAD);cpu=METER.getCurrentThreadCpuTime();
                try(var tile=complete.decode(new DhTerrainDecoder(DhLimits.DEFAULT),profile,shape)) {
                    stats.phase(4,bytes,cpu);
                    // Hashing is outside timing; equality is checked by the before/after driver.
                    consumed=tile.normalizedSha256();stats.normalized=consumed;
                }
            }
            stats.peak=Math.max(stats.peak,budget.peak());stats.fragmentBytes=fragmentBytes;stats.fragments=fragments;
        }
        if(budget.used()!=0)throw new IllegalStateException("lease leak in offline replay");
    }
}
