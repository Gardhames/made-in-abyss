package dev.madeinabyss.kernel.terrain.natural;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import java.util.Objects;
import java.util.Optional;

/** ORIGINAL cave dressing over an immutable carved source. No RNG state, world cache or
 * dependency on other decorations for support. Saved cubes must remain authoritative.
 * Mineral faces replace rock; only supported short details occupy air. This is not canon flora. */
public final class CaveInteriorOverlay {
    @FunctionalInterface public interface Source { TerrainSample sample(int x,int y,int z); }
    @FunctionalInterface public interface Protection { boolean contains(int x,int y,int z); }
    @FunctionalInterface public interface Features { Feature featureAt(int x,int y,int z); }
    public record Feature(boolean cave,double entranceDistance,boolean passage) {}
    public enum Decoration { CALCITE, DRIPSTONE_BLOCK, MOSS, FERN, DRIPSTONE_UP,
        DRIPSTONE_DOWN, LICHEN_NORTH, LICHEN_SOUTH, LICHEN_EAST, LICHEN_WEST, WATER }
    private static final int[][] SIDES={{0,0,-1},{0,0,1},{1,0,0},{-1,0,0}};
    private static final Decoration[] LICHEN={Decoration.LICHEN_NORTH,Decoration.LICHEN_SOUTH,Decoration.LICHEN_EAST,Decoration.LICHEN_WEST};
    private final long seed;
    private final Source source;
    private final Protection protection;
    private final Features features;
    public CaveInteriorOverlay(long seed,Source source,Protection protection,Features features){
        this.seed=seed;this.source=Objects.requireNonNull(source);this.protection=Objects.requireNonNull(protection);this.features=Objects.requireNonNull(features);
    }
    public Optional<Decoration> sampleOverride(int x,int y,int z){
        Feature f=features.featureAt(x,y,z);
        if(!f.cave()||protection.contains(x,y,z))return Optional.empty();
        return sampleEligible(x,y,z,f,source.sample(x,y,z));
    }
    public Optional<Decoration> sampleOverride(int x,int y,int z,TerrainSample base){
        Feature f=features.featureAt(x,y,z);
        if(!f.cave()||protection.contains(x,y,z))return Optional.empty();
        return sampleEligible(x,y,z,f,Objects.requireNonNull(base));
    }
    /** Terrain-only fast path: almost all cells reject before features or neighbour sampling.
     * Admitted anchors use the exact dressing decision, including precedence and safety guards. */
    public boolean waterAt(int x,int y,int z,TerrainSample base){
        Objects.requireNonNull(base);
        if(!(base instanceof TerrainSample.Known k)||(k.material()!=TerrainMaterial.ROCK&&k.material()!=TerrainMaterial.AIR)
            ||!poolAnchor(x,y,z))return false;
        return sampleOverride(x,y,z,base).filter(v->v==Decoration.WATER).isPresent();
    }
    private Optional<Decoration> sampleEligible(int x,int y,int z,Feature f,TerrainSample base){
        if(!(base instanceof TerrainSample.Known known)||known.material()==TerrainMaterial.WATER)return Optional.empty();
        TerrainMaterial m=known.material();
        // Any unavailable/wet direct neighbour vetoes dressing; never reinterpret UNKNOWN as air.
        for(int[] d:SIDES)if(unsafe(material((long)x+d[0],y,(long)z+d[2])))return Optional.empty();
        TerrainMaterial below=material(x,(long)y-1,z),above=material(x,(long)y+1,z);
        if(unsafe(below)||unsafe(above))return Optional.empty();
        if(m==TerrainMaterial.ROCK){
            if(basinAt(x,y,z))return Optional.of(Decoration.WATER);
            if(!exposed(x,y,z))return Optional.empty();
            if(moss(x,y,z,f))return Optional.of(Decoration.MOSS);
            double patch=random(Math.floorDiv(x,8),Math.floorDiv(y,6),Math.floorDiv(z,8),3);
            return patch<.32?Optional.of(Decoration.CALCITE):patch<.72?Optional.of(Decoration.DRIPSTONE_BLOCK):Optional.empty();
        }
        if(m!=TerrainMaterial.AIR||f.passage())return Optional.empty();
        if(below==TerrainMaterial.ROCK&&supported(x,y,z,0,-1,0)&&solid(material(x,(long)y-2,z))&&above==TerrainMaterial.AIR&&air(x,(long)y+2,z)){
            Feature ground=features.featureAt(x,y-1,z);
            if(ground.cave()&&moss(x,y-1,z,ground)&&random(x,y,z,5)<.28
                &&sampleOverride(x,y-1,z).filter(v->v==Decoration.MOSS).isPresent())return Optional.of(Decoration.FERN);
            if(f.entranceDistance()>18&&random(Math.floorDiv(x,5),Math.floorDiv(y,5),Math.floorDiv(z,5),6)<.45&&random(x,y,z,7)<.12)return Optional.of(Decoration.DRIPSTONE_UP);
            if(f.entranceDistance()>18&&poolAnchor(x,y,z)&&allHorizontalRock(x,y,z))return Optional.of(Decoration.WATER);
        }
        if(f.entranceDistance()>18&&above==TerrainMaterial.ROCK&&supported(x,y,z,0,1,0)&&solid(material(x,(long)y+2,z))
            &&below==TerrainMaterial.AIR&&air(x,(long)y-2,z)&&air(x,(long)y-3,z)&&random(x,y,z,8)<.10)return Optional.of(Decoration.DRIPSTONE_DOWN);
        if(random(Math.floorDiv(x,7),Math.floorDiv(y,9),Math.floorDiv(z,7),9)<.35&&random(x,y,z,10)<.22)
            for(int i=0;i<SIDES.length;i++){int[] d=SIDES[i];if(supported(x,y,z,d[0],0,d[2])
                &&solid(material((long)x+2*d[0],y,(long)z+2*d[2])))return Optional.of(LICHEN[i]);}
        return Optional.empty();
    }
    private boolean moss(int x,int y,int z,Feature f){return Double.isFinite(f.entranceDistance())&&f.entranceDistance()>=0&&f.entranceDistance()<=18
        &&!protection.contains(x,y,z)&&EcologyMaskFields.moisture(seed,x,y,z)>.42&&random(Math.floorDiv(x,5),Math.floorDiv(y,6),Math.floorDiv(z,5),4)<.70;}
    private boolean poolAnchor(int x,int y,int z){
        long cx=Math.floorDiv(x,8),cz=Math.floorDiv(z,8);
        return x==cx*8+2+(int)(random(cx,0,cz,11)*4)&&z==cz*8+2+(int)(random(cx,0,cz,12)*4)
            &&random(cx,Math.floorDiv(y,8),cz,13)<.20;
    }
    private boolean allHorizontalRock(int x,int y,int z){for(int[] d:SIDES)if(!supported(x,y,z,d[0],0,d[2]))return false;return true;}
    /** Independent water decision allows support checks against the composed result without recursion. */
    private boolean basinAt(int x,int y,int z){
        if(!poolAnchor(x,y,z))return false;
        Feature f=features.featureAt(x,y,z);
        if(!f.cave()||f.passage()||f.entranceDistance()<=18||protection.contains(x,y,z)
            ||material(x,y,z)!=TerrainMaterial.ROCK||!air(x,(long)y+1,z)||!air(x,(long)y+2,z)
            ||!rawSupported(x,y,z,0,-1,0))return false;
        for(int[] d:SIDES)if(!rawSupported(x,y,z,d[0],0,d[2]))return false;
        return true;
    }
    private boolean exposed(int x,int y,int z){if(air(x,(long)y-1,z)||air(x,(long)y+1,z))return true;for(int[] d:SIDES)if(air((long)x+d[0],y,(long)z+d[2]))return true;return false;}
    private boolean supported(int x,int y,int z,int dx,int dy,int dz){
        if(!rawSupported(x,y,z,dx,dy,dz))return false;
        return !basinAt(x+dx,y+dy,z+dz);
    }
    private boolean rawSupported(int x,int y,int z,int dx,int dy,int dz){
        long xx=(long)x+dx,yy=(long)y+dy,zz=(long)z+dz;
        return valid(xx)&&valid(yy)&&valid(zz)&&!protection.contains((int)xx,(int)yy,(int)zz)&&material(xx,yy,zz)==TerrainMaterial.ROCK;
    }
    private boolean air(long x,long y,long z){return material(x,y,z)==TerrainMaterial.AIR;}
    private static boolean solid(TerrainMaterial m){return m==TerrainMaterial.ROCK||m==TerrainMaterial.SOIL||m==TerrainMaterial.GRASS;}
    private static boolean unsafe(TerrainMaterial m){return m==null||m==TerrainMaterial.WATER;}
    private TerrainMaterial material(long x,long y,long z){if(!valid(x)||!valid(y)||!valid(z))return null;var s=source.sample((int)x,(int)y,(int)z);return s instanceof TerrainSample.Known k?k.material():null;}
    private static boolean valid(long n){return n>=Integer.MIN_VALUE&&n<=Integer.MAX_VALUE;}
    private double random(long x,long y,long z,long salt){long bits=mix(seed^mix(x)^Long.rotateLeft(mix(y),21)^Long.rotateLeft(mix(z),42)^mix(salt));return (bits>>>11)*0x1.0p-53;}
    private static long mix(long n){n=(n^(n>>>30))*0xbf58476d1ce4e5b9L;n=(n^(n>>>27))*0x94d049bb133111ebL;return n^(n>>>31);}
}
