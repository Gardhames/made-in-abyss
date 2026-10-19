package dev.madeinabyss.kernel.lod;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;

/** Pre-T3 oracle, copied before production edits.
 * Bounded pack of exact camera-independent raw detail tiles; never authoritative world data. */
final class T3ReferenceTerrainTileDiskCache {
 private static final int MAGIC=0x4d495450,VERSION=1,MAX_TILES=32768,MAX_BOXES=1_000_000,MAX_BYTES=64*1024*1024;
 public record Key(int x,int y,int z,int endX,int endY,int endZ,int step) {
  public Key {
   if((long)endX-x<0||(long)endX-x>=16||(long)endY-y<0||(long)endY-y>=64||(long)endZ-z<0||(long)endZ-z>=16
     ||step<1||step>64||(step&(step-1))!=0)throw new IllegalArgumentException("Invalid detail tile");
  }
 }
 public record Read(Map<Key,List<TerrainLodColumns.Box>> tiles,String status) { }
 private T3ReferenceTerrainTileDiskCache() { }
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
   var in=new DataInputStream(new ByteArrayInputStream(bytes,0,body));
   if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Tile schema");
   if(!identity.equals(in.readUTF()))return new Read(Map.of(),"KEY_MISS");
   int count=in.readInt();if(count<0||count>MAX_TILES)throw new IOException("Tile count");
   var tiles=new LinkedHashMap<Key,List<TerrainLodColumns.Box>>();int total=0;
   for(int i=0;i<count;i++){
    var key=new Key(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());
    int n=in.readInt();if(n<0||n>MAX_BOXES-total||n>in.available()/25)throw new IOException("Tile box count");total+=n;
    var boxes=new ArrayList<TerrainLodColumns.Box>(n);
    for(int j=0;j<n;j++){
     int x=in.readInt(),y=in.readInt(),z=in.readInt(),dx=in.readInt(),dy=in.readInt(),dz=in.readInt(),m=in.readUnsignedByte();
     if(m>=TerrainMaterial.values().length||dx<1||dy<1||dz<1)throw new IOException("Tile box material/extent");
     boxes.add(new TerrainLodColumns.Box(x,y,z,(long)x+dx,(long)y+dy,(long)z+dz,TerrainMaterial.values()[m]));
    }
    validate(key,boxes);if(tiles.put(key,List.copyOf(boxes))!=null)throw new IOException("Duplicate tile");
   }
   if(in.available()!=0)throw new IOException("Trailing tile bytes");
   return new Read(Collections.unmodifiableMap(tiles),"HIT");
  }catch(IOException|IllegalArgumentException e){return new Read(Map.of(),"INVALID: "+e.getMessage());}
 }
 public static void save(Path directory,String identity,Map<Key,List<TerrainLodColumns.Box>> tiles)throws IOException{
  var target=TerrainLodDiskCache.file(directory,identity);if(tiles.size()>MAX_TILES)throw new IllegalArgumentException("Tile count");
  int total=0;for(var entry:tiles.entrySet()){if(entry.getValue().size()>MAX_BOXES-total)throw new IllegalArgumentException("Tile box budget");total+=entry.getValue().size();validate(entry.getKey(),entry.getValue());}
  var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(identity);out.writeInt(tiles.size());
  for(var entry:tiles.entrySet()){
   var k=entry.getKey();out.writeInt(k.x);out.writeInt(k.y);out.writeInt(k.z);out.writeInt(k.endX);out.writeInt(k.endY);out.writeInt(k.endZ);out.writeInt(k.step);out.writeInt(entry.getValue().size());
   for(var b:entry.getValue()){out.writeInt(Math.toIntExact(b.minX()));out.writeInt(Math.toIntExact(b.minY()));out.writeInt(Math.toIntExact(b.minZ()));out.writeInt(Math.toIntExact(b.maxXExclusive()-b.minX()));out.writeInt(Math.toIntExact(b.maxYExclusive()-b.minY()));out.writeInt(Math.toIntExact(b.maxZExclusive()-b.minZ()));out.writeByte(b.material().ordinal());}
  }
  out.flush();var data=bytes.toByteArray();if(data.length>MAX_BYTES-32)throw new IOException("Tile pack budget");
  Files.createDirectories(directory);var tmp=Files.createTempFile(directory,"tiles-",".tmp");
  try{try(var file=Files.newOutputStream(tmp)){file.write(data);file.write(sha(data,data.length));}Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(tmp);}
 }
}
