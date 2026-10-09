package dev.vox.lss.common.dh;

import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.*;
import java.util.*;

/** Rebuildable cache of verified source DTOs, never a claim that Voxy persisted an ingest. Worker-only IO. */
public final class DhTerrainCache implements AutoCloseable {
    public static final String DIRECTORY = "!dh-terrain-v1";
    public static final String LEGACY_DIRECTORY = "dh-terrain-v1";
    public static final int MAX_FILES = 8192;
    public static final long MAX_BYTES = 256L << 20;
    // VERSION covers storage/decoder layout. Bump CONVERSION_VERSION whenever
    // DhColumnConverter changes output; old identities then require fresh source.
    public static final int CONVERSION_VERSION = 4;
    private static final int MAGIC = 0x44485443, VERSION = 2, HEADER = 96;
    private static final Object IO_LOCK = new Object();
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final Map<Path, Index> INDEXES = new HashMap<>();
    private static final Map<Path, Map<DhTerrainCache, Set<Path>>> PROTECTIONS = new HashMap<>();
    private final Set<Path> protectedFiles = new HashSet<>();
    private static final long PENDING_MAX_AGE = 3_600_000L;
    private static final class Index {
        final Map<Path, Meta> files = new HashMap<>();
        long revision;
    }
    private record Meta(long bytes, long usedAt) {}
    private static final ThreadLocal<Map<Path,Guard>> GUARDS=ThreadLocal.withInitial(HashMap::new);
    private final Path root, directory;
    private final byte[] identity;
    private final int maxFiles;
    private final long maxBytes;

    public record Key(String address, String server, String world, String dimension, long seed,
                      DhProfile profile, DhTerrainTile.WorldShape shape, String conversionInputs) {
        public Key(String address, String server, String world, String dimension, long seed,
                   DhProfile profile, DhTerrainTile.WorldShape shape) {
            this(address,server,world,dimension,seed,profile,shape,"legacy");
        }
        public Key {
            for (String value : List.of(address, server, world, dimension))
                if (value.length() > 4096) throw new IllegalArgumentException("DH cache identity too long");
            Objects.requireNonNull(profile); Objects.requireNonNull(shape);
            Objects.requireNonNull(conversionInputs);
            if(conversionInputs.length()>4096)throw new IllegalArgumentException("DH conversion identity too long");
        }
        byte[] digest() {
            try {
                var bytes = new ByteArrayOutputStream();
                try (var out = new DataOutputStream(bytes)) {
                    out.writeInt(VERSION);out.writeInt(CONVERSION_VERSION);
                    for (String value : List.of(address, server, world, dimension, profile.name())) out.writeUTF(value);
                    out.writeLong(seed); out.writeInt(shape.minY()); out.writeInt(shape.height()); out.writeBoolean(shape.sky());
                    out.writeUTF(conversionInputs);
                }
                return hash(bytes.toByteArray());
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
    }
    public DhTerrainCache(Path cacheRoot, Key key) { this(cacheRoot, key, MAX_FILES, MAX_BYTES); }
    DhTerrainCache(Path cacheRoot, Key key, int maxFiles, long maxBytes) {
        if (maxFiles < 1 || maxBytes < HEADER) throw new IllegalArgumentException("Invalid DH cache quota");
        root = cacheRoot.resolve(DIRECTORY).toAbsolutePath().normalize();
        identity = key.digest();
        directory = root.resolve(addressHash(key.address()) + "." + HexFormat.of().formatHex(identity));
        this.maxFiles = maxFiles; this.maxBytes = maxBytes;
    }
    /** Names are only candidates: reading verifies identity, position, size and the full payload hash. */
    public Set<DhPosition> positions() throws IOException {
        synchronized (IO_LOCK) {
            try(var ownership=guard(false)) {
            checkDirectories(false);
            trim(index());
            if (!Files.isDirectory(directory, NOFOLLOW)) return Set.of();
            var result = new HashSet<DhPosition>();
            try (var files = Files.newDirectoryStream(directory)) {
                for (Path file : files) {
                    var position = position(file);
                    if (position != null && Files.isRegularFile(file, NOFOLLOW)) result.add(position);
                    if (result.size() > maxFiles) throw new IOException("DH cache entry limit exceeded");
                }
            }
            return result;
            }
}
    }
    public Entry read(DhPosition position, DhMemoryBudget budget) throws IOException, DhCodecException {
        synchronized (IO_LOCK) {
            Entry result=null;
            try(var ownership=guard(false)) {
            checkDirectories(false);
            Path file = file(position);
            if (!Files.isRegularFile(file, NOFOLLOW)) return null;
            long size = Files.size(file);
            if (size < HEADER || size > HEADER + DhLimits.DEFAULT.tileBytes()) { remove(position); return null; }
            var lease = budget.reserve(size + 64);
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file, NOFOLLOW)))) {
                if (in.readInt() != MAGIC || in.readInt() != VERSION
                        || !MessageDigest.isEqual(in.readNBytes(32), identity) || in.readLong() != position.packed())
                    throw new IOException("Invalid DH cache identity");
                long timestamp = in.readLong(); int length = in.readInt(); byte[] checksum = in.readNBytes(32);
                if(in.readInt()!=CONVERSION_VERSION)throw new IOException("Obsolete DH conversion semantics");
                if (timestamp < 0 || length < 1 || length != size - HEADER) throw new IOException("Invalid DH cache length/timestamp");
                byte[] data = in.readNBytes(length);
                if (data.length != length || in.read() != -1 || !MessageDigest.isEqual(hash(data), checksum))
                    throw new IOException("Invalid DH cache checksum");
                try {touch(position);}catch(IOException ignored) { /* a read-only valid file is still usable */ }
                result=new Entry(position, timestamp, data, budget, lease);
                return result;
            } catch (IOException e) {
                lease.close();
                try { remove(position); } catch (IOException ignored) { e.addSuppressed(ignored); }
                return null; // Corrupt or lost cache data must cause a full request, never an unchanged receipt.
            } catch (RuntimeException | Error e) { lease.close(); throw e; }
            } catch(IOException failure) {
                // A lock/channel close failure after a successful read must not
                // strand a lease for an Entry the caller never receives.
                if(result!=null)result.close();
                throw failure;
            }
}
    }
    /** Caller has decoded and verified this DTO. Only complete source tiles are eligible. */
    public boolean write(DhPosition position, long timestamp, byte[] data) throws IOException {
        synchronized (IO_LOCK) {
            try(var ownership=guard(true)) {
            // Invalidate the previous version BEFORE replacement or delivery. A failed
            // write must never leave an older source available for the next connection.
            remove(position);
            if (timestamp < 0 || data.length < 1 || data.length > DhLimits.DEFAULT.tileBytes()
                    || (long) data.length + HEADER > maxBytes) return false;
            checkDirectories(true);
            var index = index();
            // Reserve capacity before committing; failed eviction cannot accumulate declined files.
            if(!trim(index,maxFiles-1,maxBytes-HEADER-data.length))return false;
            Path temporary = Files.createTempFile(directory, ".pending-", ".tmp");
            try {
                try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                    out.writeInt(MAGIC); out.writeInt(VERSION); out.write(identity); out.writeLong(position.packed());
                    out.writeLong(timestamp); out.writeInt(data.length); out.write(hash(data)); out.writeInt(CONVERSION_VERSION);out.write(data);
                }
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                Files.move(temporary, file(position), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                index.files.put(file(position), new Meta(Files.size(file(position)), System.currentTimeMillis()));
                trim(index);
                return Files.isRegularFile(file(position), NOFOLLOW);
            } finally { Files.deleteIfExists(temporary); }
            }
}
    }
    public void remove(DhPosition position) throws IOException {
        synchronized (IO_LOCK) {
            try(var ownership=guard(false)) {
            checkDirectories(false); Files.deleteIfExists(file(position));protectedFiles.remove(file(position));
            var index = INDEXES.get(root); if (index != null) index.files.remove(file(position));
            }
}
    }
    /** Explicit reset clears every dimension/profile/seed bucket for this address. Null clears all DH buckets. */
    public static void clear(Path cacheRoot, String address) throws IOException {
        synchronized (IO_LOCK) {
            if(Files.exists(cacheRoot,NOFOLLOW) && !realDirectory(cacheRoot))return;
            IOException failure=null;
            for(String name:List.of(DIRECTORY,LEGACY_DIRECTORY)) {
                Path root=cacheRoot.resolve(name);
                if(Files.notExists(root,NOFOLLOW) || !realDirectory(root))continue;
                Path marker=root.resolve("quota.lock");
                if(Files.exists(marker,NOFOLLOW)) {
                    var a=Files.readAttributes(marker,java.nio.file.attribute.BasicFileAttributes.class,NOFOLLOW);
                    if(!a.isRegularFile() || a.isSymbolicLink() || a.isOther())continue;
                }
                try {clearRoot(root,address);}catch(IOException e) {failure=e;}
            }
            if(failure!=null)throw failure;
        }
    }
    private static void clearRoot(Path root,String address) throws IOException {
        root=root.toAbsolutePath().normalize();
        requireDirectory(root);
        try(var ownership=lockRoot(root,true)) {clearOwnedDirectories(root,address);}
    }
    private static void clearOwnedDirectories(Path root,String address) throws IOException {
        requireDirectory(root); INDEXES.remove(root);
        IOException failure=null;
        try(var directories=Files.newDirectoryStream(root)) {
            for(Path directory:directories) {
                if(!ownedDirectory(directory) || address!=null && !directory.getFileName().toString().startsWith(addressHash(address)+"."))continue;
                try {
                    if(!realDirectory(directory))continue;
                    try(var files=Files.newDirectoryStream(directory)) {
                        for(Path file:files)if(position(file)!=null || pending(file))Files.delete(file);
                    }
                    try {Files.delete(directory);}catch(DirectoryNotEmptyException ignored) { /* preserve foreign contents */ }
                } catch(IOException e) {failure=e;}
            }
        }
        if(failure!=null)throw failure;
    }
    /** Keep this connection's not-yet-validated working set from eviction cascades. */
    public void protect(Collection<DhPosition> positions) {
        synchronized (IO_LOCK) {
            protectedFiles.clear();
            for (var position : positions) protectedFiles.add(file(position));
            PROTECTIONS.computeIfAbsent(root,ignored->new WeakHashMap<>()).put(this,protectedFiles);
        }
    }
    /** A validated/accepted tile no longer needs to pin its source while waiting for a response. */
    public void unprotect(DhPosition position) {
        synchronized (IO_LOCK) {protectedFiles.remove(file(position));}
    }
    @Override public void close() {
        synchronized(IO_LOCK) {
            protectedFiles.clear();
            var owners=PROTECTIONS.get(root);if(owners!=null){owners.remove(this);if(owners.isEmpty())PROTECTIONS.remove(root);}
        }
    }
    public void invalidateAll() throws IOException {
        synchronized(IO_LOCK) {
            try(var ownership=guard(false)) {
            checkDirectories(false);
            if(!Files.isDirectory(directory,NOFOLLOW))return;
            try(var files=Files.newDirectoryStream(directory)) {
                for(var file:files)if(position(file)!=null) {
                    Files.delete(file);var index=INDEXES.get(root);if(index!=null)index.files.remove(file);
                }
            }
            protectedFiles.clear();
            }
}
    }
    public void touch(DhPosition position) throws IOException {
        synchronized (IO_LOCK) {
            try(var ownership=guard(false)) {
            checkDirectories(false); var file = file(position);
            if (!Files.isRegularFile(file, NOFOLLOW)) return;
            long now = System.currentTimeMillis();
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(now));
            index().files.put(file, new Meta(Files.size(file), now));
            }
}
    }
    /** Build one shared quota index per root, rather than enumerate every bucket per write. */
    private Index index() throws IOException {
        var existing = INDEXES.get(root); if (existing != null) return existing;
        var result = new Index();
        var held=GUARDS.get().get(root);if(held!=null)result.revision=held.revision;
        if (Files.isDirectory(root, NOFOLLOW)) try (var directories = Files.newDirectoryStream(root)) {
            for (Path dir : directories) {
                if (!ownedDirectory(dir) || !Files.isDirectory(dir, NOFOLLOW)) continue;
                try {requireDirectory(dir);}catch(IOException unsafe) {continue;}
                try (var entries = Files.newDirectoryStream(dir)) {
                    for (Path entry : entries) {
                        if (!Files.isRegularFile(entry, NOFOLLOW)) continue;
                        boolean pending = pending(entry);
                        if (position(entry) == null && !pending) continue;
                        long age = Files.getLastModifiedTime(entry, NOFOLLOW).toMillis();
                        if (pending && System.currentTimeMillis() - age > PENDING_MAX_AGE) {
                            Files.delete(entry); continue;
                        }
                        result.files.put(entry, new Meta(Files.size(entry), age));
                    }
                }
            }
        }
        if(INDEXES.size()>=32)INDEXES.keySet().removeIf(p->!PROTECTIONS.containsKey(p));
        INDEXES.put(root, result); return result;
    }
    private static boolean pending(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith(".pending-") && name.endsWith(".tmp");
    }
    private void trim(Index index) throws IOException {trim(index,maxFiles,maxBytes);}
    private boolean trim(Index index,int fileLimit,long byteLimit) throws IOException {
        long size = index.files.values().stream().mapToLong(Meta::bytes).sum();
        int count = index.files.size();
        if (count <= fileLimit && size <= byteLimit) return true;
        var files = new ArrayList<>(index.files.keySet());
        // One LRU across all buckets. Every live owner's working set is protected,
        // including when another cache instance performs the trim.
        files.sort(Comparator.comparingLong(p -> index.files.get(p).usedAt()));
        var protectedByAll=new HashSet<Path>();
        var owners=PROTECTIONS.get(root);if(owners!=null)owners.values().forEach(protectedByAll::addAll);
        for (Path file : files) {
            if (count <= fileLimit && size <= byteLimit) break;
            if (protectedByAll.contains(file)) continue;
            var meta = index.files.get(file);
            try {Files.deleteIfExists(file);}catch(IOException undeletable) {continue;}
            index.files.remove(file); size -= meta.bytes(); count--;
        }
        return count<=fileLimit && size<=byteLimit;
    }
    /** Cross-JVM writes use one owned, no-follow lock and revision. An external
     * writer invalidates the rebuildable index; no directory walk on local writes. */
    private Guard guard(boolean create) throws IOException {
        checkDirectories(create);
        return lockRoot(root);
    }
    private static Guard lockRoot(Path root) throws IOException {return lockRoot(root,false);}
    private static Guard lockRoot(Path root,boolean repairRevision) throws IOException {
        var held=GUARDS.get().get(root);if(held!=null){held.depth++;return held;}
        if(!Files.exists(root,NOFOLLOW))return new Guard(root,null,null,0);
        requireDirectory(root);
        Path marker=root.resolve("quota.lock");
        if(Files.exists(marker,NOFOLLOW)) {
            var attrs=Files.readAttributes(marker,java.nio.file.attribute.BasicFileAttributes.class,NOFOLLOW);
            if(!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther())throw new IOException("Unsafe DH quota lock");
        }
        var channel=FileChannel.open(marker,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE,NOFOLLOW);
        java.nio.channels.FileLock lock=null;
        try {
            long deadline=System.nanoTime()+2_000_000_000L;
            do {
                try {lock=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException busy) { /* bounded wait */ }
                if(lock!=null)break;
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("DH quota lock interrupted");
                try {Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("DH quota lock interrupted");}
            } while(System.nanoTime()<deadline);
            if(lock==null)throw new IOException("DH quota lock busy");
            var bytes=java.nio.ByteBuffer.allocate(8);channel.position(0);
            long old=0;if(channel.size()!=0) {
                boolean valid=channel.size()==8 && channel.read(bytes)==8;
                if(valid){bytes.flip();old=bytes.getLong();valid=old>=0;}
                if(!valid){
                    if(!repairRevision)throw new IOException("Invalid DH quota revision");
                    old=0;INDEXES.remove(root); // Repair only the owned regular inode while holding its lock.
                }
            }
            var index=INDEXES.get(root);if(index!=null && index.revision!=old)INDEXES.remove(root);
            long revision=old==Long.MAX_VALUE?0:old+1;
            bytes.clear();bytes.putLong(revision).flip();channel.position(0);while(bytes.hasRemaining())channel.write(bytes);channel.truncate(8);
            index=INDEXES.get(root);if(index!=null)index.revision=revision;
            var result=new Guard(root,channel,lock,revision);GUARDS.get().put(root,result);return result;
        } catch(IOException | RuntimeException failure) {if(lock!=null)lock.close();channel.close();throw failure;}
    }
    private static final class Guard implements AutoCloseable {
        final Path root;
        final FileChannel channel;final java.nio.channels.FileLock lock;final long revision;int depth=1;
        Guard(Path root,FileChannel channel,java.nio.channels.FileLock lock,long revision){this.root=root;this.channel=channel;this.lock=lock;this.revision=revision;}
        public void close() throws IOException {
            if(--depth!=0 || channel==null)return;
            GUARDS.get().remove(root);
            try {lock.close();}finally {channel.close();}
        }
    }
    private void checkDirectories(boolean create) throws IOException {
        // Refuse symlinked roots/buckets. The cache only owns its named child of the adopted cache root.
        Path parent = root.getParent();
        Path legacy=parent.resolve(LEGACY_DIRECTORY);
        if(Files.isDirectory(legacy,NOFOLLOW)) {
            requireDirectory(legacy);
            if(Files.exists(parent,NOFOLLOW))requireDirectory(parent);
            try(var buckets=Files.newDirectoryStream(legacy)) {
                for(Path bucket:buckets) {
                    if(!ownedDirectory(bucket) || !Files.isDirectory(bucket,NOFOLLOW))continue;
                    requireDirectory(bucket);
                    if(Files.notExists(root,NOFOLLOW))Files.createDirectory(root);
                    requireDirectory(root);
                    Path target=root.resolve(bucket.getFileName());
                    if(Files.notExists(target,NOFOLLOW))Files.move(bucket,target,StandardCopyOption.ATOMIC_MOVE);
                }
            }
        }
        if (create) Files.createDirectories(parent);
        if (Files.exists(parent, NOFOLLOW)) requireDirectory(parent);
        if (create && Files.notExists(root, NOFOLLOW)) Files.createDirectory(root);
        if (Files.exists(root, NOFOLLOW)) requireDirectory(root);
        if (create && Files.notExists(directory, NOFOLLOW)) Files.createDirectory(directory);
        if (Files.exists(directory, NOFOLLOW)) requireDirectory(directory);
    }
    private static boolean realDirectory(Path path) throws IOException {
        var a=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,NOFOLLOW);
        return a.isDirectory() && !a.isSymbolicLink() && !a.isOther();
    }
    private static void requireDirectory(Path path) throws IOException {
        var attributes=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,NOFOLLOW);
        // Windows junctions are directories AND "other" reparse points, not
        // symbolic links. Refuse them in every root, adoption and quota walk.
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
            throw new IOException("DH cache directory is not a real directory");
    }
    private static boolean ownedDirectory(Path path) { return path.getFileName().toString().matches("[0-9a-f]{64}\\.[0-9a-f]{64}"); }
    private Path file(DhPosition p) { return directory.resolve(String.format(Locale.ROOT, "%016x.dht", p.packed())); }
    private static DhPosition position(Path file) {
        String name = file.getFileName().toString();
        if (!name.matches("[0-9a-f]{16}\\.dht")) return null;
        DhPosition p = DhPosition.unpack(Long.parseUnsignedLong(name.substring(0, 16), 16));
        return p.detail() == 6 ? p : null;
    }
    private static String addressHash(String address) { return HexFormat.of().formatHex(hash(address.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    private static byte[] hash(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static final class Entry implements AutoCloseable {
        private final DhPosition position;
        private final long timestamp;
        private final DhMemoryBudget budget;
        private final DhMemoryBudget.Lease lease;
        private byte[] data;
        private Entry(DhPosition position, long timestamp, byte[] data, DhMemoryBudget budget, DhMemoryBudget.Lease lease) {
            this.position = position; this.timestamp = timestamp; this.data = data; this.budget = budget; this.lease = lease;
        }
        public long timestamp() { return timestamp; }
        public DhTerrainTile decode(DhTerrainDecoder decoder, DhProfile profile, DhTerrainTile.WorldShape shape) throws DhCodecException {
            if (data == null) throw new IllegalStateException("DH cached DTO released");
            return decoder.decode(data, profile, position, shape, budget);
        }
        @Override public void close() { data = null; lease.close(); }
    }
}
