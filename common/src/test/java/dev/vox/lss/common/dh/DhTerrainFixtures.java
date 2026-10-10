package dev.vox.lss.common.dh;

import com.github.luben.zstd.Zstd;
import org.tukaani.xz.*;
import java.io.*;
import java.util.*;

/** Handwritten structural controls, deliberately distinct from the frozen wire-capture corpus. */
final class DhTerrainFixtures {
    @FunctionalInterface interface Body { void write(DataOutputStream out) throws IOException; }
    static byte[] bytes(Body body) throws IOException {
        var bytes=new ByteArrayOutputStream(); try (var out=new DataOutputStream(bytes)) { body.write(out); }
        return bytes.toByteArray();
    }
    static void varint(DataOutputStream out,int value) throws IOException {
        long v=value&0xffffffffL;
        do { int b=(int)(v&127); v>>>=7; out.writeByte(v==0?b:b|128); } while(v!=0);
    }
    static long point(int id,int bottom,int height,int light) {
        return id | ((long)height<<32) | ((long)bottom<<44) | ((long)light<<56);
    }
    static long[][] columns() {
        var result=new long[4096][];
        for(int c=0;c<4096;c++) {
            int bottom=c%7,light=c%17==0?0:0x3f;
            result[c]=new long[]{point(0,bottom,64-bottom,light)};
        }
        result[1000]=new long[0];
        result[2000]=new long[]{point(1,0,64,15)};
        // One contiguous second span follows a discontinuous first; prediction
        // also crosses columns with nonzero bottoms and an unknown column.
        result[77]=new long[]{point(0,32,32,15),point(0,4,28,0)};
        return result;
    }
    static byte[][] rawBlobs(int format,long[][] columns,String[] mappings) throws IOException {
        byte[][] blobs=new byte[8][];
        if(format==1) {
            blobs[0]=bytes(out->{for(long[] col:columns){out.writeShort(col.length);for(long p:col)out.writeLong(p);}});
            for(int i=1;i<=4;i++)blobs[i]=new byte[0];
        } else {
            int[][] b={{1,63,1,63},{0,64,0,1},{0,64,63,64},{63,64,0,64},{0,1,0,64}};
            for(int i=0;i<5;i++) {
                var points=new ArrayList<Long>();
                var counts=new ByteArrayOutputStream();
                var countOut=new DataOutputStream(counts);
                for(int x=b[i][0];x<b[i][1];x++)for(int z=b[i][2];z<b[i][3];z++){
                    long[] col=columns[x*64+z];varint(countOut,col.length);for(long p:col)points.add(p);
                }
                var ids=new ByteArrayOutputStream();var heights=new ByteArrayOutputStream();
                var deltas=new ByteArrayOutputStream();var lights=new ByteArrayOutputStream();
                var idOut=new DataOutputStream(ids);var heightOut=new DataOutputStream(heights);
                var deltaOut=new DataOutputStream(deltas);int previous=0;
                for(long p:points) {
                    int height=(int)(p>>>32&4095),bottom=(int)(p>>>44&4095);
                    int light=(int)(p>>>56&255),error=bottom-previous+height;
                    varint(idOut,((int)p<<2)|(light==0?0:2)|(error==0?0:1));
                    varint(heightOut,height);
                    if(error!=0)varint(deltaOut,(error<<1)^(error>>31));
                    if(light!=0)lights.write(light);
                    previous=bottom;
                }
                var combined=new ByteArrayOutputStream();
                counts.writeTo(combined);ids.writeTo(combined);heights.writeTo(combined);
                deltas.writeTo(combined);lights.writeTo(combined);
                blobs[i]=combined.toByteArray();
            }
        }
        blobs[5]=new byte[4096];Arrays.fill(blobs[5],(byte)9);
        blobs[6]=new byte[4096];
        blobs[7]=bytes(out->{out.writeInt(mappings.length);for(String m:mappings)out.writeUTF(m);});
        return blobs;
    }
    static byte[] compress(byte[] raw,int mode,boolean unfinished) throws IOException {
        if(raw.length==0)return raw;
        if(mode==0)return raw;
        if(mode==4)return Zstd.compress(raw,3);
        var bytes=new ByteArrayOutputStream();
        var xz=new XZOutputStream(bytes,new LZMA2Options(3),XZ.CHECK_CRC64);
        xz.write(raw);
        if(unfinished){xz.flush();byte[] result=bytes.toByteArray();xz.close();return result;}
        xz.close();return bytes.toByteArray();
    }
    static byte[] dto(int format,int mode,DhProfile profile,byte[][] blobs,boolean unfinished) throws IOException {
        byte[][] compressed=new byte[8][];
        for(int i=0;i<8;i++)compressed[i]=compress(blobs[i],mode,unfinished);
        return bytes(out->{
            out.writeLong(0xffffffffffffff06L);out.writeInt(0);
            for(byte[] blob:compressed){out.writeInt(blob.length);out.write(blob);}
            out.writeByte(format);out.writeByte(mode);out.writeBoolean(true);out.writeBoolean(false);
            if(profile.regenerationFlag())out.writeBoolean(true);
            out.writeLong(profile.support()?456:123);out.writeLong(profile.support()?123:456);
        });
    }
    static byte[] tile(int format,int mode,DhProfile profile) throws IOException {
        return dto(format,mode,profile,rawBlobs(format,columns(),new String[]{
                "minecraft:plains_DH-BSW_minecraft:stone","minecraft:plains_DH-BSW_minecraft:air"}),profile.support());
    }
}
