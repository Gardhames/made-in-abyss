package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.io.*;
import java.security.*;
import java.util.*;

/**
 * Frozen true-3D support index. A support voxel owns its faces, including across
 * cube boundaries. The geometry callback must be one immutable generation phase;
 * no callback, mutable voxel buffer or interpolated light is retained by this index.
 */
public final class LivingAbyssSupportedFaces155B2 {
    public enum Normal {
        UP(0,1,0),DOWN(0,-1,0),WEST(-1,0,0),EAST(1,0,0),NORTH(0,0,-1),SOUTH(0,0,1);
        public final int dx,dy,dz;
        Normal(int dx,int dy,int dz){this.dx=dx;this.dy=dy;this.dz=dz;}
        public int mask(){return 1<<ordinal();}
    }
    public enum Role {SHAFT_LEDGE,THROUGH_INNER,THROUGH_OUTER,OUTER_LEDGE,HALL_FLOOR,WALL,SPELEOTHEM,EARTHFORM}
    public record Owner(String id,Role role) {
        public Owner {if(id==null||id.isBlank()||role==null)throw new IllegalArgumentException("Support owner required");}
    }
    public record Cell(boolean known,boolean air,int sturdyMask,Owner owner) {
        public static final Cell AIR=new Cell(true,true,0,null),UNKNOWN=new Cell(false,false,0,null);
        public Cell {
            if(sturdyMask<0||sturdyMask>63||(!known&&(air||sturdyMask!=0||owner!=null))||
                    (air&&(sturdyMask!=0||owner!=null))||(sturdyMask!=0&&owner==null))
                throw new IllegalArgumentException("Inconsistent support cell");
        }
        public static Cell solid(Owner owner){return new Cell(true,false,63,owner);}
    }
    @FunctionalInterface public interface Geometry {Cell cell(int x,int y,int z);}
    public record Face(int x,int y,int z,Normal normal,Owner owner,int clearance,String geometryIdentity) {
        public Face {
            if(normal==null||owner==null||clearance<1||geometryIdentity==null||geometryIdentity.isBlank())
                throw new IllegalArgumentException("Complete known support face required");
        }
        public int airX(){return Math.addExact(x,normal.dx);}
        public int airY(){return Math.addExact(y,normal.dy);}
        public int airZ(){return Math.addExact(z,normal.dz);}
    }
    private static final Comparator<CubeKey> CUBE_ORDER=Comparator.comparingInt(CubeKey::x)
            .thenComparingInt(CubeKey::y).thenComparingInt(CubeKey::z);
    private final Map<CubeKey,List<Face>> cubes;
    private final List<Face> faces;
    private final String digest;
    private LivingAbyssSupportedFaces155B2(String identity,Map<CubeKey,List<Face>> input){
        cubes=Collections.unmodifiableMap(new LinkedHashMap<>(input));
        faces=input.values().stream().flatMap(Collection::stream).toList();
        try {
            var md=MessageDigest.getInstance("SHA-256");
            try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),md))){
                out.writeUTF("supported-faces-155-b2-v1");out.writeUTF(identity);out.writeInt(faces.size());
                for(var f:faces){
                    out.writeInt(f.x);out.writeInt(f.y);out.writeInt(f.z);out.writeInt(f.normal.ordinal());
                    out.writeUTF(f.owner.id);out.writeInt(f.owner.role.ordinal());out.writeInt(f.clearance);
                }
            }
            digest=HexFormat.of().formatHex(md.digest());
        }catch(IOException|NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    /**
     * Only the requested support cubes are enumerated; the geometry must also
     * answer halo and clearance queries. UNKNOWN and fluids never count as air.
     * Clearance is a bounded ray along the outward face normal, not a heightmap.
     * Full stamp footprints must additionally be collision-checked at placement.
     */
    public static LivingAbyssSupportedFaces155B2 scan(String identity,Collection<CubeKey> requested,Geometry geometry,int clearanceLimit){
        if(identity==null||identity.isBlank()||clearanceLimit<1)throw new IllegalArgumentException("Phase and positive clearance bound required");
        Objects.requireNonNull(geometry);Objects.requireNonNull(requested);
        var keys=new TreeSet<CubeKey>(CUBE_ORDER);keys.addAll(requested);
        var result=new LinkedHashMap<CubeKey,List<Face>>();
        for(var key:keys){
            int bx=Math.multiplyExact(key.x(),16),by=Math.multiplyExact(key.y(),16),bz=Math.multiplyExact(key.z(),16);
            Math.addExact(bx,15);Math.addExact(by,15);Math.addExact(bz,15);
            var local=new ArrayList<Face>();
            for(int x=bx;x-bx<16;x++)for(int y=by;y-by<16;y++)for(int z=bz;z-bz<16;z++){
                var support=Objects.requireNonNull(geometry.cell(x,y,z));
                if(!support.known||support.sturdyMask==0)continue;
                for(var normal:Normal.values()){
                    if((support.sturdyMask&normal.mask())==0)continue;
                    int count=0,xx=x,yy=y,zz=z;
                    while(count<clearanceLimit){
                        xx=Math.addExact(xx,normal.dx);yy=Math.addExact(yy,normal.dy);zz=Math.addExact(zz,normal.dz);
                        var neighbor=Objects.requireNonNull(geometry.cell(xx,yy,zz));
                        if(!neighbor.known||!neighbor.air)break;
                        count++;
                    }
                    if(count>0)local.add(new Face(x,y,z,normal,support.owner,count,identity));
                }
            }
            if(!local.isEmpty())result.put(key,List.copyOf(local));
        }
        return new LivingAbyssSupportedFaces155B2(identity,result);
    }
    public List<Face> faces(){return faces;}
    public List<Face> inCube(CubeKey key){return cubes.getOrDefault(key,List.of());}
    public Set<CubeKey> cubeKeys(){return cubes.keySet();}
    public String digest(){return digest;}
}
