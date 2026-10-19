package dev.madeinabyss.kernel.lod;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;

/** Bounded pack of exact camera-independent raw detail tiles; never authoritative world data. */
public final class TerrainTileDiskCache {
 private static final int MAGIC=0x4d495450,VERSION=1,MAX_TILES=32768,MAX_BOXES=1_000_000,MAX_BYTES=64*1024*1024;
 private static final TerrainMaterial[] MATERIALS=TerrainMaterial.values();
 public record Key(int x,int y,int z,int endX,int endY,int endZ,int step) {
  public Key {
   if((long)endX-x<0||(long)endX-x>=16||(long)endY-y<0||(long)endY-y>=64||(long)endZ-z<0||(long)endZ-z>=16
     ||step<1||step>64||(step&(step-1))!=0)throw new IllegalArgumentException("Invalid detail tile");
  }
 }
 public record Read(Map<Key,List<TerrainLodColumns.Box>> tiles,String status) { }
 private TerrainTileDiskCache() { }
 private static byte[] sha(byte[] data,int length){try{var d=MessageDigest.getInstance("SHA-256");d.update(data,0,length);return d.digest();}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
 public static void validate(Key key,List<TerrainLodColumns.Box> boxes) {
  TerrainLodColumns.Box prior=null;
  for(var b:boxes){
   if(b.minX()<key.x||b.minY()<key.y||b.minZ()<key.z||b.maxXExclusive()>(long)key.endX+1||b.maxYExclusive()>(long)key.endY+1||b.maxZExclusive()>(long)key.endZ+1
    ||Math.floorMod(b.minX()-key.x,key.step)!=0||Math.floorMod(b.minZ()-key.z,key.step)!=0
    ||b.maxXExclusive()!=Math.min((long)key.endX+1,b.minX()+key.step)||b.maxZExclusive()!=Math.min((long)key.endZ+1,b.minZ()+key.step))throw new IllegalArgumentException("Detail box outside exact tile lattice");
   if(prior!=null&&(b.minX()<prior.minX()||b.minX()==prior.minX()&&(b.minZ()<prior.minZ()||b.minZ()==prior.minZ()&&b.minY()<prior.maxYExclusive())))throw new IllegalArgumentException("Detail order/overlap");
   prior=b;
  }
 }
 public static Read load(Path directory,String identity){
  try{
   var file=TerrainLodDiskCache.file(directory,identity);if(!Files.exists(file))return new Read(Map.of(),"MISS");
   long size=Files.size(file);if(size<108||size>MAX_BYTES)throw new IOException("Tile pack size");
   byte[] bytes;try(var input=Files.newInputStream(file)){bytes=input.readNBytes(MAX_BYTES+1);}
   if(bytes.length<108||bytes.length>MAX_BYTES)throw new IOException("Tile pack actual size");int body=bytes.length-32;
   if(!MessageDigest.isEqual(sha(bytes,body),Arrays.copyOfRange(bytes,body,bytes.length)))throw new IOException("Tile checksum");
   // Keep DataInput's modified UTF and its precise malformed-header errors.
   // Only the short header uses streams; all repeated fixed-width fields are big-endian.
   var header=new DataInputStream(new ByteArrayInputStream(bytes,0,body));
   if(header.readInt()!=MAGIC||header.readInt()!=VERSION)throw new IOException("Tile schema");
   if(!identity.equals(header.readUTF()))return new Read(Map.of(),"KEY_MISS");
   var in=ByteBuffer.wrap(bytes).limit(body).position(body-header.available());
   int count=in.getInt();if(count<0||count>MAX_TILES)throw new IOException("Tile count");
   var tiles=new LinkedHashMap<Key,List<TerrainLodColumns.Box>>();int total=0;
   for(int i=0;i<count;i++){
    var key=new Key(in.getInt(),in.getInt(),in.getInt(),in.getInt(),in.getInt(),in.getInt(),in.getInt());
    int n=in.getInt();if(n<0||n>MAX_BOXES-total||n>in.remaining()/25)throw new IOException("Tile box count");total+=n;
    var boxes=new ArrayList<TerrainLodColumns.Box>(n);
    for(int j=0;j<n;j++){
     int x=in.getInt(),y=in.getInt(),z=in.getInt(),dx=in.getInt(),dy=in.getInt(),dz=in.getInt(),m=Byte.toUnsignedInt(in.get());
     if(m>=MATERIALS.length||dx<1||dy<1||dz<1)throw new IOException("Tile box material/extent");
     boxes.add(new TerrainLodColumns.Box(x,y,z,(long)x+dx,(long)y+dy,(long)z+dz,MATERIALS[m]));
    }
    validate(key,boxes);if(tiles.put(key,List.copyOf(boxes))!=null)throw new IOException("Duplicate tile");
   }
   if(in.hasRemaining())throw new IOException("Trailing tile bytes");
   return new Read(Collections.unmodifiableMap(tiles),"HIT");
  }catch(BufferUnderflowException truncated){return new Read(Map.of(),"INVALID: null");} // legacy EOFException has no message
  catch(IOException|IllegalArgumentException e){return new Read(Map.of(),"INVALID: "+e.getMessage());}
 }
 public static void save(Path directory,String identity,Map<Key,List<TerrainLodColumns.Box>> tiles)throws IOException{
  var target=TerrainLodDiskCache.file(directory,identity);if(tiles.size()>MAX_TILES)throw new IllegalArgumentException("Tile count");
  int total=0;for(var entry:tiles.entrySet()){if(entry.getValue().size()>MAX_BOXES-total)throw new IllegalArgumentException("Tile box budget");total+=entry.getValue().size();validate(entry.getKey(),entry.getValue());}
  var header=new ByteArrayOutputStream();var text=new DataOutputStream(header);text.writeInt(MAGIC);text.writeInt(VERSION);text.writeUTF(identity);text.writeInt(tiles.size());text.flush();
  long size=header.size()+32L*tiles.size()+25L*total;if(size>MAX_BYTES-32)throw new IOException("Tile pack budget");
  var out=ByteBuffer.allocate((int)size);out.put(header.toByteArray());
  for(var entry:tiles.entrySet()){
   var k=entry.getKey();out.putInt(k.x);out.putInt(k.y);out.putInt(k.z);out.putInt(k.endX);out.putInt(k.endY);out.putInt(k.endZ);out.putInt(k.step);out.putInt(entry.getValue().size());
   for(var b:entry.getValue()){out.putInt(Math.toIntExact(b.minX()));out.putInt(Math.toIntExact(b.minY()));out.putInt(Math.toIntExact(b.minZ()));out.putInt(Math.toIntExact(b.maxXExclusive()-b.minX()));out.putInt(Math.toIntExact(b.maxYExclusive()-b.minY()));out.putInt(Math.toIntExact(b.maxZExclusive()-b.minZ()));out.put((byte)b.material().ordinal());}
  }
  var data=out.array();
  Files.createDirectories(directory);var tmp=Files.createTempFile(directory,"tiles-",".tmp");
  try{try(var file=Files.newOutputStream(tmp)){file.write(data);file.write(sha(data,data.length));}Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}
 }
}
