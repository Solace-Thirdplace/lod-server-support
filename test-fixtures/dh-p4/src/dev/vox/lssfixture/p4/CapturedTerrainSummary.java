package dev.vox.lssfixture.p4;
import dev.vox.lss.common.dh.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.io.*;
import java.util.*;
/** Canonical replay of received real bytes, outside measured intervals. */
public final class CapturedTerrainSummary {
 public static void main(String[] args)throws Exception {
  var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());var limits=DhLimits.DEFAULT;var codec=new DhProtocolCodec(DhProfile.OFFICIAL_16,limits);var decoder=new DhTerrainDecoder(limits);var rows=new TreeMap<String,Object>();long bytes=0;int packets=0;
  try(var assembly=new DhAssembly(limits,budget)) {
   assembly.expect(999999,new DhPosition(6,0,0));
   for(Path f:Files.list(Path.of(args[0])).sorted().toList()){
    if(!f.getFileName().toString().startsWith("received-"))continue;byte[] raw=Files.readAllBytes(f);var msg=codec.decode(raw);
    if(msg instanceof DhMessage.Split split){bytes+=raw.length;packets++;assembly.accept(split);}
    if(msg instanceof DhMessage.Response response){bytes+=raw.length;packets++;if(response.bufferId()==null)throw new IllegalStateException("Empty fixed-area response");
     assembly.expect(response.requestId(),new DhPosition(6,0,0));try(var complete=assembly.response(response)){
      var field=complete.getClass().getDeclaredField("data");field.setAccessible(true);byte[] dto=(byte[])field.get(complete);var pos=DhPosition.unpack(ByteBuffer.wrap(dto).getLong());
      try(var tile=decoder.decode(dto,DhProfile.OFFICIAL_16,pos,new DhTerrainTile.WorldShape(-64,384,true),budget)){
       if(rows.containsKey(pos.x()+","+pos.z()))throw new IllegalStateException("duplicate source tile");
       var used=new TreeSet<String>();for(int c=0;c<4096;c++){if(tile.coverage(c)!=DhTerrainTile.Coverage.COMPLETE)throw new IllegalStateException("incomplete actual source");for(int n=0;n<tile.spanCount(c);n++)used.add(key(tile.materials().get(tile.span(c,n).material())));}
       var canonical=new ArrayList<>(used);var ids=new int[tile.materials().size()];for(int i=0;i<ids.length;i++)ids[i]=canonical.indexOf(key(tile.materials().get(i)));
       var digest=MessageDigest.getInstance("SHA-256");try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){for(String key:canonical)out.writeUTF(key);
        long[] column=new long[384];for(int c=0;c<4096;c++){Arrays.fill(column,-1);for(int n=0;n<tile.spanCount(c);n++){var p=tile.span(c,n);long word=((long)ids[p.material()]<<8)|(p.skyLight()|(p.blockLight()<<4));Arrays.fill(column,p.bottom(),p.bottom()+p.height(),word);}for(long point:column){if(point<0)throw new IllegalStateException("source gap");out.writeLong(point);}}
       }
       rows.put(pos.x()+","+pos.z(),Map.of("canonical_blocks_biomes_raw_light_sha256",HexFormat.of().formatHex(digest.digest()),"complete_columns",4096,"generation_steps",java.util.stream.IntStream.range(0,4096).map(tile::generation).distinct().boxed().toList()));
      }
     }
    }
   }
  }if(budget.used()!=0)throw new IllegalStateException("replay retained memory");
  Files.writeString(Path.of(args[1]),new com.google.gson.Gson().toJson(Map.of("tiles",rows,"terrain_payload_bytes",bytes,"terrain_payload_packets",packets,"source","received real frames, replayed after measurement; canonical material IDs ignore palette ordering")));
 }
 static String key(DhMaterial m){return m.biome()+"|"+m.block()+"|"+new TreeMap<>(m.properties());}
}
