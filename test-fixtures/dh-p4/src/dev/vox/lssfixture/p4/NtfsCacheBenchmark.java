package dev.vox.lssfixture.p4;
import dev.vox.lss.common.dh.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.lang.management.*;
import java.util.*;
/** Headless current-production NTFS cache costs. All paths belong to the supplied new attempt. */
public final class NtfsCacheBenchmark {
 static final List<Long> writeNs=new ArrayList<>(),readNs=new ArrayList<>();
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);if(!root.isAbsolute() || Files.exists(root))throw new IllegalArgumentException("New absolute cache root required");Files.createDirectories(root);
  if(!Files.getFileStore(root).type().equalsIgnoreCase("NTFS"))throw new IllegalStateException("Measured filesystem is not NTFS");
  var shape=new DhTerrainTile.WorldShape(args.length>3?Integer.parseInt(args[3]):-128,args.length>4?Integer.parseInt(args[4]):512,true);
  byte[] original=Files.readAllBytes(Path.of(args[1]));var initial=DhPosition.unpack(ByteBuffer.wrap(original).getLong());var owned=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
  try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(original,DhProfile.OFFICIAL_16,initial,shape,owned)){for(int c=0;c<4096;c++)if(tile.coverage(c)!=DhTerrainTile.Coverage.COMPLETE)throw new IllegalArgumentException("Complete real source required");}
  if(owned.used()!=0)throw new IllegalStateException("source validation retained budget");
  long began=System.nanoTime(),cpu=((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
  var buckets=new ArrayList<DhTerrainCache>();for(String dim:List.of("owned-a","owned-b","owned-c"))buckets.add(new DhTerrainCache(root,new DhTerrainCache.Key("owned-ntfs","owned-ntfs","owned-ntfs",dim,0,DhProfile.OFFICIAL_16,shape)));
  var positions=new ArrayList<DhPosition>();int total=DhTerrainCache.MAX_FILES+128;
  for(int i=0;i<total;i++){
   var p=new DhPosition(6,10000+i,10000);positions.add(p);ByteBuffer.wrap(original).putLong(p.packed());long start=System.nanoTime();if(!buckets.get(i%3).write(p,i+1,original))throw new IllegalStateException("NTFS write declined");writeNs.add(System.nanoTime()-start);
  }
  long files,bytes;try(var walk=Files.walk(root)){var names=walk.filter(Files::isRegularFile).filter(f->f.toString().endsWith(".dht")).toList();files=names.size();bytes=0;for(var f:names)bytes+=Files.size(f);}
  long expectedFiles=Math.min(DhTerrainCache.MAX_FILES,DhTerrainCache.MAX_BYTES/(original.length+96));
  if(files!=expectedFiles || bytes>DhTerrainCache.MAX_BYTES)throw new IllegalStateException("NTFS global quota failed: "+files+" != "+expectedFiles);
  long coldIndex=0;var index=DhTerrainCache.class.getDeclaredField("INDEXES");index.setAccessible(true);((Map<?,?>)index.get(null)).clear();long start=System.nanoTime();int indexed=0;for(var b:buckets)indexed+=b.positions().size();coldIndex=System.nanoTime()-start;if(indexed!=files)throw new IllegalStateException("cold index lost files");
  for(int i=total-256;i<total;i++){start=System.nanoTime();try(var entry=buckets.get(i%3).read(positions.get(i),owned)){if(entry==null || entry.timestamp()!=i+1)throw new IllegalStateException("NTFS readback identity failed");}readNs.add(System.nanoTime()-start);}
  if(owned.used()!=0)throw new IllegalStateException("NTFS reads retained budget");
  // A separate four-record quota isolates the fully protected branch from foreign-bucket eviction.
  var ctor=DhTerrainCache.class.getDeclaredConstructor(Path.class,DhTerrainCache.Key.class,int.class,long.class);ctor.setAccessible(true);
  var pinned=(DhTerrainCache)ctor.newInstance(root.resolve("pinned-case"),new DhTerrainCache.Key("owned-pinned","owned-pinned","owned-pinned","owned",0,DhProfile.OFFICIAL_16,shape),4,1L<<20);
  for(int i=0;i<4;i++){var p=new DhPosition(6,20000+i,0);ByteBuffer.wrap(original).putLong(p.packed());if(!pinned.write(p,1,original))throw new IllegalStateException("pinned premise");}
  pinned.protect(pinned.positions());var rejected=new DhPosition(6,999999,999999);ByteBuffer.wrap(original).putLong(rejected.packed());boolean declined=!pinned.write(rejected,100000,original);
  if(!declined)throw new IllegalStateException("NTFS fully protected quota did not decline");pinned.protect(Set.of());if(!pinned.write(rejected,100001,original))throw new IllegalStateException("NTFS pressure did not recover");
  Path file;try(var walk=Files.walk(root)){file=walk.filter(Files::isRegularFile).filter(f->f.toString().endsWith(".dht")).filter(f->{try{return ByteBuffer.wrap(Files.readAllBytes(f)).getLong(40)==rejected.packed();}catch(Exception e){throw new RuntimeException(e);}}).findFirst().orElseThrow();}
  @SuppressWarnings({"rawtypes","unchecked"}) var noShare=(OpenOption)Enum.valueOf((Class)Class.forName("com.sun.nio.file.ExtendedOpenOption"),"NOSHARE_DELETE");
  boolean sharingRefusal=false;
  try(var locked=Files.newByteChannel(file,Set.of(StandardOpenOption.READ,noShare))){try{pinned.write(rejected,100002,original);}catch(java.io.IOException expected){sharingRefusal=true;}}
  if(!sharingRefusal)throw new IllegalStateException("Owned NTFS share lock did not block replacement");
  if(!pinned.write(rejected,100003,original))throw new IllegalStateException("NTFS share lock recovery failed");try(var entry=pinned.read(rejected,owned)){if(entry==null || entry.timestamp()!=100003)throw new IllegalStateException("NTFS replacement remained stale");}
  var row=new LinkedHashMap<String,Object>();row.put("pass",true);row.put("filesystem",Files.getFileStore(root).type());row.put("real_source_dto_bytes",original.length);row.put("global_files",files);row.put("global_bytes",bytes);row.put("writes",total);row.put("limiting_quota",files<DhTerrainCache.MAX_FILES?"bytes":"files");row.put("write_nanos",writeNs);row.put("read_nanos",readNs);row.put("cold_index_nanos",coldIndex);row.put("write_read_pressure_wall_seconds",(System.nanoTime()-began)/1e9);row.put("whole_process_cpu_nanos",((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime()-cpu);row.put("owned_share_lock_refusal_and_recovery",sharingRefusal);row.put("fully_pinned_decline_and_release",declined);row.put("scope","Synthetic positions across three private identity buckets using a validated complete real-source DTO. Production per-record force(true), hashes, atomic rename, quota and index; not 8192 real server downloads.");
  Files.writeString(Path.of(args[2]),new com.google.gson.Gson().toJson(row));
 }
}
