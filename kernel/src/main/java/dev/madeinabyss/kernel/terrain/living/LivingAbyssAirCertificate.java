package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Immutable v15-only proof of literal AIR. False means unproved; saved edits
 * remain authoritative. Joined phases own disjoint XZ columns and publish only
 * final bits. The exact path consumes already expanded final output footprints. */
public final class LivingAbyssAirCertificate {
    private static final int MIN_XZ=-81,SIDE=162,RAW_Y=-85,FIRST_Y=-84,LAST_Y=19,WORDS=2;
    private static final double SLACK=1e-7;
    private final long[] bits;
    private final int certifiedCubeCount,workers;
    private final long buildNanos;
    /** A caller-proved parent column ceiling; MAX_VALUE refuses. Only used at
     * Y64..319. Implementations must prove every voxel of the whole XZ cube. */
    @FunctionalInterface public interface ParentAirColumn {int firstAirY(int cubeX,int cubeZ);}

    /** Conservative compatibility API: arbitrary dirty inputs retain a full cube halo. */
    public LivingAbyssAirCertificate(TerrainParameters p,LivingAbyssGeometry geometry,Iterable<CubeKey> dirtyCubes){this(p,geometry,dirtyCubes,false);}
    public LivingAbyssAirCertificate(TerrainParameters p,LivingAbyssGeometry geometry,Iterable<CubeKey> dirtyCubes,boolean approvedUpperOpening){
        this(p,geometry,dirtyCubes,approvedUpperOpening,null,true);
    }
    /** Footprints include direct outputs and only their actual read-time support growth. */
    public static LivingAbyssAirCertificate exact(TerrainParameters p,LivingAbyssGeometry geometry,Iterable<CubeKey> footprint,boolean approvedUpperOpening){
        return exact(p,geometry,footprint,approvedUpperOpening,null);
    }
    public static LivingAbyssAirCertificate exact(TerrainParameters p,LivingAbyssGeometry geometry,Iterable<CubeKey> footprint,boolean approvedUpperOpening,ParentAirColumn parentAir){
        return new LivingAbyssAirCertificate(p,geometry,footprint,approvedUpperOpening,parentAir,false);
    }
    /** Frozen feature output plus +Y only when an occupied top row can be read as
     * moss support by the next cube. Non-sturdy/AIR codes may over-veto safely. */
    public static Set<CubeKey> supportingFeatureCubes(LivingAbyssVoxelPlan features){
        var keys=new HashSet<>(Objects.requireNonNull(features).cubeKeys());
        for(var k:features.cubeKeys())if(k.y()<(Integer.MAX_VALUE>>4)){
            int sx=k.x()*16,sy=k.y()*16+15,sz=k.z()*16;
            top:for(int z=0;z<16;z++)for(int x=0;x<16;x++)if(features.at(sx+x,sy,sz+z)!=0){keys.add(new CubeKey(k.x(),k.y()+1,k.z()));break top;}
        }
        return Collections.unmodifiableSet(keys);
    }
    private LivingAbyssAirCertificate(TerrainParameters p,LivingAbyssGeometry geometry,Iterable<CubeKey> dirtyCubes,boolean upper,ParentAirColumn parentAir,boolean legacyHalo){
        long start=System.nanoTime();Objects.requireNonNull(p);Objects.requireNonNull(geometry);Objects.requireNonNull(dirtyCubes);
        if((upper||parentAir!=null)&&(p.cavity().mouthRadius()!=500||p.cavity().firstLayerTopY()!=64))throw new IllegalArgumentException("Upper AIR proof requires approved R500/Y64 parent");
        if(parentAir!=null&&!upper)throw new IllegalArgumentException("Parent sky proof requires approved parent composition");
        if(geometry.wallField()==null){bits=new long[0];certifiedCubeCount=workers=0;buildNanos=System.nanoTime()-start;return;}
        int lastLowY=upper?3:-1;var d=geometry.plan().recipe().domain();
        var limits=new double[lastLowY-FIRST_Y+1];var valid=new boolean[limits.length];
        for(int cy=FIRST_Y;cy<=lastLowY;cy++){
            double limit=Double.POSITIVE_INFINITY;int sy=cy*16;
            if(sy-1<d.wallBottomY()||sy<=d.temporaryFloorY()+8||sy<d.knownLowerY()){limits[cy-FIRST_Y]=Double.NEGATIVE_INFINITY;continue;}
            valid[cy-FIRST_Y]=true;
            for(int y=sy-1;y<=sy+15;y++)limit=Math.min(limit,bareWallLowerBound(p,y));
            limits[cy-FIRST_Y]=Math.nextDown(cy<0?limit-geometry.wallField().maximumProtrusion()-SLACK:Math.min(500,limit)-SLACK);
        }
        var candidates=new long[SIDE*SIDE*WORDS];var dirty=new long[candidates.length];var active=new boolean[SIDE*SIDE];
        double exterior=p.cavity().lowerRadius()+p.noise().wallAmplitude();
        for(int ix=1;ix<SIDE-1;ix++)for(int iz=1;iz<SIDE-1;iz++){
            int x=(ix+MIN_XZ)*16,z=(iz+MIN_XZ)*16;double rmax=maximumRadius(x,z);
            if(rmax>=960||rmax>exterior)continue;
            int at=(ix*SIDE+iz)*WORDS;boolean any=false;
            for(int cy=FIRST_Y;cy<=lastLowY;cy++)if(valid[cy-FIRST_Y]&&((!legacyHalo&&cy<0)||rmax<limits[cy-FIRST_Y])){
                int bit=cy-RAW_Y;candidates[at+(bit>>>6)]|=1L<<(bit&63);any=true;
            }
            if(any){
                if(legacyHalo)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)active[(ix+dx)*SIDE+iz+dz]=true;
                else active[ix*SIDE+iz]=true;
            }
        }
        for(var key:dirtyCubes){int ix=key.x()-MIN_XZ,iz=key.z()-MIN_XZ,bit=key.y()-RAW_Y;
            if(ix>=0&&ix<SIDE&&iz>=0&&iz<SIDE&&bit>=0&&bit<=LAST_Y+1-RAW_Y)dirty[(ix*SIDE+iz)*WORDS+(bit>>>6)]|=1L<<(bit&63);
        }
        var published=new long[candidates.length];int workerCount=Math.max(1,Math.min(12,Runtime.getRuntime().availableProcessors()));
        var pool=new ForkJoinPool(workerCount);
        try{
            pool.invoke(new Columns(0,active.length,column->{
                if(!active[column])return;int sx=(column/SIDE+MIN_XZ)*16,sz=(column%SIDE+MIN_XZ)*16,at=column*WORDS;
                LivingAbyssGeometry.VerticalRange ranges=(lo,hi)->markRange(dirty,at,lo,(long)hi+(legacyHalo?0:1));
                for(int z=sz;z<sz+16;z++)for(int x=sx;x<sx+16;x++)geometry.visitCertificateObstacles(x,z,ranges);
            }));
            pool.invoke(new Columns(0,active.length,column->{
                int ix=column/SIDE,iz=column%SIDE,at=column*WORDS;if(ix==0||ix==SIDE-1||iz==0||iz==SIDE-1)return;
                long lo=dirty[at],hi=dirty[at+1];
                if(legacyHalo){
                    for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){int q=((ix+dx)*SIDE+iz+dz)*WORDS;lo|=dirty[q];hi|=dirty[q+1];}
                    long oldLo=lo;lo|=(oldLo<<1)|(oldLo>>>1)|(hi<<63);hi|=(hi<<1)|(hi>>>1)|(oldLo>>>63);
                }
                int sx=(ix+MIN_XZ)*16,sz=(iz+MIN_XZ)*16;double rmax=maximumRadius(sx,sz);
                for(int cy=FIRST_Y;cy<=lastLowY;cy++){
                    int bit=cy-RAW_Y,word=bit>>>6;long mask=1L<<(bit&63),blocked=word==0?lo:hi;
                    if((candidates[at+word]&mask)==0||(blocked&mask)!=0)continue;
                    if(!legacyHalo&&cy<0&&rmax>=limits[cy-FIRST_Y]&&!insideActualWall(geometry,sx,cy*16,sz))continue;
                    published[at+word]|=mask;
                }
                if(parentAir!=null){
                    int from=parentAir.firstAirY(ix+MIN_XZ,iz+MIN_XZ);
                    for(int cy=4;cy<=LAST_Y;cy++)if(cy*16>=from){int bit=cy-RAW_Y;long mask=1L<<(bit&63);if(((bit<64?lo:hi)&mask)==0)published[at+(bit>>>6)]|=mask;}
                }
            }));
        }finally{pool.shutdown();}
        int count=0;for(long word:published)count+=Long.bitCount(word);
        bits=published;certifiedCubeCount=count;workers=workerCount;buildNanos=System.nanoTime()-start;
    }
    private static double maximumRadius(int x,int z){
        double ax=Math.max(Math.abs((double)x),Math.abs((double)x+15)),az=Math.max(Math.abs((double)z),Math.abs((double)z+15));return Math.sqrt(ax*ax+az*az);
    }
    private static boolean insideActualWall(LivingAbyssGeometry geometry,int sx,int sy,int sz){
        double margin=geometry.wallField().maximumProtrusion()+SLACK;
        for(int z=sz;z<sz+16;z++)for(int x=sx;x<sx+16;x++)
            if(Math.sqrt((double)x*x+(double)z*z)>=Math.nextDown(geometry.minimumWallRadius(x,sy-1,sy+15,z)-margin))return false;
        return true;
    }
    private static void markRange(long[] dirty,int at,long minY,long maxY){
        int lo=(int)Math.max(RAW_Y,minY>>4),hi=(int)Math.min(LAST_Y+1,maxY>>4);
        for(int cy=lo;cy<=hi;cy++){int bit=cy-RAW_Y;dirty[at+(bit>>>6)]|=1L<<(bit&63);}
    }
    /** Outward global sufficient bound; exact column minima recover its rejections.
     * 2^-40 bounds the original interpolated [-1,1] corner rounding; nextDown
     * makes the remaining monotone products/additions outward rounded. */
    public static double bareWallLowerBound(TerrainParameters p,int y){
        var c=p.cavity();if(y>=c.firstLayerTopY())return Math.nextDown(c.mouthRadius());
        double depth=c.firstLayerTopY()-(double)y,f=clamp(depth/(c.firstLayerTopY()-c.declaredLowerBoundY())),e=clamp(depth/p.noise().wavelength());
        double base=c.mouthRadius()+(c.lowerRadius()-c.mouthRadius())*smooth(f),envelope=smooth(e);
        double wall=Math.nextDown(-p.noise().wallAmplitude()*(1+0x1.0p-40));return Math.nextDown(base+Math.nextDown(envelope*wall));
    }
    private static double clamp(double v){return Math.max(0,Math.min(1,v));}
    private static double smooth(double v){return v*v*(3-2*v);}
    public boolean uniformAir(int cx,int cy,int cz){
        int ix=cx-MIN_XZ,iz=cz-MIN_XZ,bit=cy-RAW_Y;
        return bits.length!=0&&ix>=0&&ix<SIDE&&iz>=0&&iz<SIDE&&cy>=FIRST_Y&&cy<=LAST_Y
            &&(bits[(ix*SIDE+iz)*WORDS+(bit>>>6)]&(1L<<(bit&63)))!=0;
    }
    public void visitCertifiedCubes(Consumer<CubeKey> visitor){
        Objects.requireNonNull(visitor);for(int ix=1;ix<SIDE-1;ix++)for(int iz=1;iz<SIDE-1;iz++)for(int cy=FIRST_Y;cy<=LAST_Y;cy++)
            if(uniformAir(ix+MIN_XZ,cy,iz+MIN_XZ))visitor.accept(new CubeKey(ix+MIN_XZ,cy,iz+MIN_XZ));
    }
    public int certifiedCubeCount(){return certifiedCubeCount;}public int workers(){return workers;}
    public long buildNanos(){return buildNanos;}public int residentBytes(){return bits.length*Long.BYTES;}
    private static final class Columns extends RecursiveAction{
        private final int from,to;private final IntConsumer action;
        Columns(int from,int to,IntConsumer action){this.from=from;this.to=to;this.action=action;}
        @Override protected void compute(){if(to-from<=32){for(int i=from;i<to;i++)action.accept(i);}else{int mid=(from+to)>>>1;invokeAll(new Columns(from,mid,action),new Columns(mid,to,action));}}
    }
}
