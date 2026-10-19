package dev.madeinabyss.kernel.terrain.natural;

import java.util.Optional;
import java.util.Objects;

/** ORIGINAL primordial shelf, cave-mouth and interior communities, over the composed source.
 * Stateless global XYZ decisions: no world scan/cache, no writes, no unknown-to-air conversion.
 * Source includes existing vegetation/water; support never relies on this overlay's output.
 * Structures reserve their footprint before plant scatter and reject as a whole on obstruction. */
public final class AlexEcologyOverlay {
    public enum Voxel { AIR, WATER, ROCK, SOIL, GRASS, PLANT, OBSTACLE, UNKNOWN }
    public enum Decoration { MOSS, MUD, LIMESTONE, GALENA, COPROLITH, AMBER, PEWEN_PINES,
        UNDERWEED, CURLY_LOWER, CURLY_UPPER, CYCAD_TOP, PEWEN_LOG_X, PEWEN_LOG_Z,
        THORNWOOD_LOG_X, THORNWOOD_LOG_Z, VINE_BODY, VINE_HEAD }
    @FunctionalInterface public interface Source { Voxel sample(int x,int y,int z); }
    @FunctionalInterface public interface Protection { boolean contains(int x,int y,int z); }
    @FunctionalInterface public interface Features { Feature featureAt(int x,int y,int z); }
    public record Feature(boolean cave,double entranceDistance) {}
    public enum Community { PRIMORDIAL_SHELF_GROVE, WET_CAVE_MOUTH, MINERAL_INTERIOR, DARK_UNDERWEED }
    private final long seed;
    private final Source source;
    private final Protection protection;
    private final Features features;
    public AlexEcologyOverlay(long seed,Source source,Protection protection,Features features) {
        this.seed=seed;this.source=Objects.requireNonNull(source);this.protection=Objects.requireNonNull(protection);this.features=Objects.requireNonNull(features);
    }
    public Community communityAt(int x,int y,int z) {
        Feature f=features.featureAt(x,y,z);
        if(!f.cave())return Community.PRIMORDIAL_SHELF_GROVE;
        if(lit(f))return Community.WET_CAVE_MOUTH;
        return moisture(x,y,z)>.48?Community.DARK_UNDERWEED:Community.MINERAL_INTERIOR;
    }
    public Optional<Decoration> sampleOverride(int x,int y,int z) {
        if(protection.contains(x,y,z))return Optional.empty();
        return sampleOverride(x,y,z,source.sample(x,y,z));
    }
    public Optional<Decoration> sampleOverride(int x,int y,int z,Voxel current) {
        Objects.requireNonNull(current);
        if(protection.contains(x,y,z))return Optional.empty();
        if(solid(current))return Optional.ofNullable(surface(x,y,z,current));
        if(!replaceable(current))return Optional.empty();
        Decoration d=deadfall(x,y,z);
        if(d==null)d=vine(x,y,z);
        if(d!=null)return Optional.of(d);
        // Even rejected structures retain the small reserved footprint, avoiding overlap and
        // order-dependent pair/vine fragments. Reservation is coordinate-only and cheap.
        if(reserved(x,z))return Optional.empty();
        if(cycadCandidate(x,z))return cycad(x,y,z)?Optional.of(Decoration.CYCAD_TOP):Optional.empty();
        if(y>Integer.MIN_VALUE&&curlyCandidate(x,y-1,z)&&curly(x,y-1,z))return Optional.of(Decoration.CURLY_UPPER);
        if(curlyCandidate(x,y,z)&&curly(x,y,z))return Optional.of(Decoration.CURLY_LOWER);
        double r=random(x,y,z,20);
        if(r>.64)return Optional.empty(); // before any floor or feature sampling
        Voxel floor=voxel(x,(long)y-1,z);
        if(!solid(floor)||!supported(x,(long)y-1,z))return Optional.empty();
        Feature f=features.featureAt(x,y,z);
        double wet=moisture(x,y,z),patch=random(Math.floorDiv(x,7),Math.floorDiv(y,5),Math.floorDiv(z,7),21);
        if(!lit(f))return r<.10+.18*wet&&patch<.85?Optional.of(Decoration.UNDERWEED):Optional.empty();
        if(floor==Voxel.ROCK)return r<.18+.22*wet?Optional.of(Decoration.UNDERWEED):Optional.empty();
        if(r<.25+.30*wet&&patch<.92&&clear(x,(long)y+1,z))
            return Optional.of(wet>.55&&r<.22?Decoration.UNDERWEED:Decoration.PEWEN_PINES);
        return Optional.empty();
    }
    private Decoration surface(int x,int y,int z,Voxel current) {
        double patch=random(Math.floorDiv(x,4),Math.floorDiv(y,4),Math.floorDiv(z,4),30);
        double fine=random(x,y,z,31);
        if(fine>.72)return null;
        if(clear(x,(long)y+1,z)&&supported(x,(long)y-1,z)) {
            Feature f=features.featureAt(x,y,z);double wet=moisture(x,y,z);
            if(lit(f)&&patch<.82&&fine<.40+.25*wet)return current==Voxel.ROCK&&wet>.56&&fine>.40?Decoration.MUD:Decoration.MOSS;
            if(f.cave()&&wet>.57&&patch<.4&&fine<.38)return Decoration.MOSS;
        }
        if(current!=Voxel.ROCK||patch>.26)return null;
        // Surface-only ore patches; never excavate geometry or replace water.
        if(!(clear((long)x+1,y,z)||clear((long)x-1,y,z)||clear(x,(long)y+1,z)
                ||clear(x,(long)y-1,z)||clear(x,y,(long)z+1)||clear(x,y,(long)z-1)))return null;
        double species=random(Math.floorDiv(x,4),Math.floorDiv(y,4),Math.floorDiv(z,4),32);
        return species<.55?Decoration.LIMESTONE:species<.78?Decoration.GALENA:species<.91?Decoration.COPROLITH:Decoration.AMBER;
    }
    private boolean curlyCandidate(int x,int y,int z){return random(x,y,z,22)<.17;}
    private boolean curly(int x,int y,int z) {
        Voxel soil=voxel(x,(long)y-1,z);
        return fertile(soil)&&lit(features.featureAt(x,y,z))&&supported(x,(long)y-1,z)
            &&clear(x,y,z)&&clear(x,(long)y+1,z);
    }
    private boolean cycadCandidate(int x,int z){return cellKind(x,z)>=.32&&cellKind(x,z)<.38&&x==cell(x)+3&&z==cell(z)+3;}
    private boolean cycad(int x,int y,int z) {
        if(!lit(features.featureAt(x,y,z)))return false;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            long xx=(long)x+dx,zz=(long)z+dz;
            if(!fertile(voxel(xx,(long)y-1,zz))||!supported(xx,(long)y-1,zz)||!clear(xx,y,zz)||!clear(xx,(long)y+1,zz))return false;
        }
        return true;
    }
    private Decoration deadfall(int x,int y,int z) {
        if(cellKind(x,z)>=.12)return null;
        long cx=cell(x),cz=cell(z);boolean alongX=random(cx,0,cz,12)<.5;
        long ax=cx+1+(int)(random(cx,0,cz,13)*3),az=cz+1+(int)(random(cx,0,cz,14)*3);
        int length=3+(int)(random(cx,y,cz,15)*2);
        long offset=alongX?(long)x-ax:(long)z-az;
        if(offset<0||offset>=length||(alongX?z!=az:x!=ax)||random(cx,y,cz,16)>.24)return null;
        for(int i=0;i<length;i++){
            long xx=ax+(alongX?i:0),zz=az+(alongX?0:i);
            if(!clear(xx,y,zz)||!supported(xx,(long)y-1,zz))return null;
            if(!lit(features.featureAt((int)xx,y,(int)zz)))return null;
        }
        boolean pewen=random(cx,y,cz,17)<.6;
        return pewen?(alongX?Decoration.PEWEN_LOG_X:Decoration.PEWEN_LOG_Z):(alongX?Decoration.THORNWOOD_LOG_X:Decoration.THORNWOOD_LOG_Z);
    }
    private Decoration vine(int x,int y,int z) {
        if(!vineColumn(x,z))return null;
        // Bounded possible ceilings 1..5 above; hash before Source probes.
        for(int offset=1;offset<=5;offset++){
            long top=(long)y+offset;if(!valid(top)||random(x,top,z,18)>.30)continue;
            int length=2+(int)(random(x,top,z,19)*4);if(offset>length)continue;
            if(!solid(voxel(x,top,z))||!supported(x,top,z))continue;
            Feature f=features.featureAt(x,(int)top,z);if(!f.cave()||!lit(f))continue;
            boolean ok=true;for(int i=1;i<=length;i++)if(!clear(x,top-i,z)){ok=false;break;}
            if(ok)return offset==length?Decoration.VINE_HEAD:Decoration.VINE_BODY;
        }
        return null;
    }
    private boolean reserved(int x,int z) {
        double k=cellKind(x,z);long cx=cell(x),cz=cell(z);
        if(k<.12){boolean alongX=random(cx,0,cz,12)<.5;
            long ax=cx+1+(int)(random(cx,0,cz,13)*3),az=cz+1+(int)(random(cx,0,cz,14)*3);
            long offset=alongX?(long)x-ax:(long)z-az;
            return offset>=0&&offset<4&&(alongX?z==az:x==ax);
        }
        if(k<.32)return vineColumn(x,z);
        return k<.38&&Math.abs((long)x-(cx+3))<=1&&Math.abs((long)z-(cz+3))<=1&&!cycadCandidate(x,z);
    }
    private boolean vineColumn(int x,int z){double k=cellKind(x,z);long cx=cell(x),cz=cell(z);
        return k>=.12&&k<.32&&x==cx+(int)(random(cx,0,cz,23)*8)&&z==cz+(int)(random(cx,0,cz,24)*8);}
    private double cellKind(int x,int z){return random(Math.floorDiv(x,8),0,Math.floorDiv(z,8),11);}
    private static long cell(int n){return (long)Math.floorDiv(n,8)*8;}
    private double moisture(int x,int y,int z){return EcologyMaskFields.moisture(seed,x,y,z);}
    private static boolean lit(Feature f){return !f.cave()||(Double.isFinite(f.entranceDistance())&&f.entranceDistance()>=0&&f.entranceDistance()<=24);}
    private boolean supported(long x,long y,long z){return available(x,y,z)&&solid(voxel(x,y,z));}
    private boolean clear(long x,long y,long z){return available(x,y,z)&&replaceable(voxel(x,y,z));}
    private boolean available(long x,long y,long z){return valid(x)&&valid(y)&&valid(z)&&!protection.contains((int)x,(int)y,(int)z);}
    private Voxel voxel(long x,long y,long z){if(!valid(x)||!valid(y)||!valid(z))return Voxel.UNKNOWN;return Objects.requireNonNullElse(source.sample((int)x,(int)y,(int)z),Voxel.UNKNOWN);}
    private static boolean fertile(Voxel v){return v==Voxel.SOIL||v==Voxel.GRASS;}
    private static boolean solid(Voxel v){return v==Voxel.ROCK||fertile(v);}
    private static boolean replaceable(Voxel v){return v==Voxel.AIR||v==Voxel.PLANT;}
    private static boolean valid(long n){return n>=Integer.MIN_VALUE&&n<=Integer.MAX_VALUE;}
    private double random(long x,long y,long z,long salt){long bits=mix(seed^mix(x)^Long.rotateLeft(mix(y),21)^Long.rotateLeft(mix(z),42)^mix(salt));return (bits>>>11)*0x1.0p-53;}
    private static long mix(long n){n=(n^(n>>>30))*0xbf58476d1ce4e5b9L;n=(n^(n>>>27))*0x94d049bb133111ebL;return n^(n>>>31);}
}
