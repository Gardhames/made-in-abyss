package dev.madeinabyss.kernel.volume;

import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Independent, bounded, atomic 128m page files next to the DH cache. Not world data. */
public final class VoxelVolumeArchive {
    private static final int MAGIC=0x4d495656,SCHEMA=2,MAX_FILE=16*1024*1024;
    public record Authority(String fingerprint,String generationSha,String physical,String source,String code) {
        public Authority {for(String part:List.of(fingerprint,generationSha,physical,source,code))if(part.isBlank())throw new IllegalArgumentException("Full volume authority required");}
        public String scope(VoxelPalette palette){return TerrainLodDiskCache.identity("voxel-volume-v2",fingerprint,generationSha,physical,source,code,palette.identity());}
    }
    public record PageKey(int x,int y,int z) {
        public static PageKey ofBrick(int x,int y,int z){return new PageKey(Math.floorDiv(x,8),Math.floorDiv(y,8),Math.floorDiv(z,8));}
        public int brickX(int slot){return Math.addExact(Math.multiplyExact(x,8),slot&7);}
        public int brickY(int slot){return Math.addExact(Math.multiplyExact(y,8),slot>>>6);}
        public int brickZ(int slot){return Math.addExact(Math.multiplyExact(z,8),(slot>>>3)&7);}
        public static int slot(int x,int y,int z){return ((y&7)<<6)|((z&7)<<3)|(x&7);}
    }
    public record Page(VoxelBrick[] bricks,long[] revisions) {
        public Page {if(bricks.length!=512||revisions.length!=512)throw new IllegalArgumentException("128m directory requires512 slots");bricks=bricks.clone();revisions=revisions.clone();}
        @Override public VoxelBrick[] bricks(){return bricks.clone();}
        @Override public long[] revisions(){return revisions.clone();}
    }
    public record Read(Page page,String status) {}
    private final Path directory;
    private final String scope;
    private final VoxelPalette palette;
    public VoxelVolumeArchive(Path dhCache,Authority authority,VoxelPalette palette)throws IOException {
        this.palette=Objects.requireNonNull(palette);scope=authority.scope(palette);
        directory=Objects.requireNonNull(dhCache).resolve("voxel-volume-v2").resolve(scope);Files.createDirectories(directory);
        // The manifest stores stable names and physical values, never registry integers.
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);out.writeInt(SCHEMA);out.writeUTF(scope);
            for(String value:List.of(authority.fingerprint(),authority.generationSha(),authority.physical(),authority.source(),authority.code()))out.writeUTF(value);
            out.writeInt(palette.size());for(var e:palette.entries()){out.writeUTF(e.state());out.writeByte(e.opacity());out.writeByte(e.emission());out.writeBoolean(e.air());out.writeBoolean(e.fluid());out.writeBoolean(e.collisionEmpty());out.writeInt(e.material()==null?-1:e.material().ordinal());}
        }
        byte[] expected=bytes.toByteArray();Path manifest=directory.resolve("manifest.bin");
        if(Files.exists(manifest)){if(Files.size(manifest)!=expected.length||!Arrays.equals(Files.readAllBytes(manifest),expected))throw new IOException("Volume manifest identity mismatch");}
        else atomicWrite(manifest,expected);
    }
    public String scope(){return scope;}
    public Path directory(){return directory;}
    public Path pageFile(PageKey key){return directory.resolve(key.x()+"_"+key.y()+"_"+key.z()+".vvp");}
    public Read read(PageKey key) {
        Path file=pageFile(key);if(!Files.exists(file))return new Read(null,"MISS");
        try {
            long length=Files.size(file);if(length<40||length>MAX_FILE)throw new IOException("Page file size outside bound");
            byte[] bytes=Files.readAllBytes(file);int n=bytes.length-32;
            if(!MessageDigest.isEqual(sha(bytes,n),Arrays.copyOfRange(bytes,n,bytes.length)))throw new IOException("Page checksum mismatch");
            var inflater=new InflaterInputStream(new ByteArrayInputStream(bytes,0,n));
            // Bound decompressed input too: corrupt compression cannot allocate arbitrary payloads.
            var in=new DataInputStream(new LimitedInput(inflater,MAX_FILE));
            if(in.readInt()!=MAGIC||in.readInt()!=SCHEMA||!scope.equals(in.readUTF()))throw new IOException("Page authority mismatch");
            if(in.readInt()!=key.x()||in.readInt()!=key.y()||in.readInt()!=key.z())throw new IOException("Page coordinate mismatch");
            int count=in.readUnsignedShort();if(count>512)throw new IOException("Page slot count");
            VoxelBrick[] bricks=new VoxelBrick[512];long[] revisions=new long[512];
            for(int i=0;i<count;i++){int slot=in.readUnsignedShort();long revision=in.readLong();if(slot>=512||bricks[slot]!=null||revision<0)throw new IOException("Invalid page slot/revision");
                var brick=VoxelBrick.read(in,palette.size());for(int p=0;p<4096;p++){int state=brick.stateAt(p);if(state>=0&&brick.opacityAt(p)!=palette.entry(state).opacity())throw new IOException("Palette/opacity mismatch");}
                bricks[slot]=brick;revisions[slot]=revision;}
            if(in.read()!=-1)throw new IOException("Trailing page data");in.close();return new Read(new Page(bricks,revisions),"HIT");
        }catch(IOException|IllegalArgumentException failed){return new Read(null,"INVALID:"+failed.getMessage());}
    }
    public long write(PageKey key,Page page)throws IOException {
        var bytes=new ByteArrayOutputStream();var zip=new Deflater(1);
        try(var out=new DataOutputStream(new DeflaterOutputStream(bytes,zip))) {
            out.writeInt(MAGIC);out.writeInt(SCHEMA);out.writeUTF(scope);out.writeInt(key.x());out.writeInt(key.y());out.writeInt(key.z());
            var bricks=page.bricks;var revisions=page.revisions;int count=0;for(var brick:bricks)if(brick!=null)count++;out.writeShort(count);
            for(int i=0;i<512;i++)if(bricks[i]!=null){if(revisions[i]<0)throw new IllegalArgumentException("Negative brick revision");out.writeShort(i);out.writeLong(revisions[i]);bricks[i].write(out);}
        }finally{zip.end();}
        byte[] compressed=bytes.toByteArray();if(compressed.length+32>MAX_FILE)throw new IOException("Compressed volume page exceeds bound");
        var all=new ByteArrayOutputStream();all.write(compressed);all.write(sha(compressed,compressed.length));byte[] payload=all.toByteArray();atomicWrite(pageFile(key),payload);return payload.length;
    }
    private static byte[] sha(byte[] bytes,int count) {
        try{var digest=MessageDigest.getInstance("SHA-256");digest.update(bytes,0,count);return digest.digest();}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void atomicWrite(Path file,byte[] bytes)throws IOException {
        Path temp=Files.createTempFile(file.getParent(),"volume-",".tmp");try{Files.write(temp,bytes);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(temp);}
    }
    private static final class LimitedInput extends FilterInputStream {
        private int remaining;
        LimitedInput(InputStream in,int limit){super(in);remaining=limit;}
        @Override public int read()throws IOException{if(remaining==0)throw new IOException("Decompressed page exceeds bound");int b=super.read();if(b>=0)remaining--;return b;}
        @Override public int read(byte[] b,int off,int len)throws IOException{if(remaining==0)throw new IOException("Decompressed page exceeds bound");int n=super.read(b,off,Math.min(len,remaining));if(n>0)remaining-=n;return n;}
    }
}
