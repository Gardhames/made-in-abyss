package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.NativeFirstViewPlan;
import dev.madeinabyss.kernel.cube.NativeRenderDistance;
import dev.madeinabyss.kernel.light.VoxelSkyLight;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Immutable worker-owned native payload. No client class or renderer is loaded by this boundary. */
public final class GeneratedNativeWindow {
    private final NativeFirstViewPlan.Stage stage;
    private final long epoch;
    private final int anchorX,anchorY,anchorZ,radius;
    private final VoxelSkyLight.Result sky,block;
    private final Map<CubeKey,int[]> cubes;
    private final dev.madeinabyss.kernel.concurrent.FrozenCubeTable<int[]> stateIndex;
    private final NativeFirstViewPlan.Coverage nearCoverage;
    private final VoxelSkyLight.Bounds drawBounds;

    private GeneratedNativeWindow(NativeFirstViewPlan.Stage stage,long epoch,int x,int y,int z,int radius,
                                    VoxelSkyLight.Result sky,VoxelSkyLight.Result block,Map<CubeKey,int[]> cubes,
                                    NativeFirstViewPlan.Coverage coverage) {
        this.stage=Objects.requireNonNull(stage);this.epoch=epoch;anchorX=x;anchorY=y;anchorZ=z;this.radius=radius;
        this.sky=Objects.requireNonNull(sky);this.block=Objects.requireNonNull(block);
        if(!sky.bounds().equals(block.bounds()))throw new IllegalArgumentException("Native SKY/BLOCK bounds differ");
        var copy=new HashMap<CubeKey,int[]>();
        Objects.requireNonNull(cubes).forEach((key,states)->{
            if(key==null||states==null||states.length!=4096)throw new IllegalArgumentException("Malformed native cube");
            // Arrays are exclusively produced by the completed worker/cache read, then never mutated.
            copy.put(key,states);
        });
        this.cubes=Collections.unmodifiableMap(copy);nearCoverage=coverage;
        stateIndex=new dev.madeinabyss.kernel.concurrent.FrozenCubeTable<>(copy);
        drawBounds=coverage==null?fullDrawBounds(sky.bounds()):coverage.plan().drawBounds();
    }

    public static GeneratedNativeWindow full(long epoch,int x,int y,int z,int radius,
                                              VoxelSkyLight.Result sky,VoxelSkyLight.Result block,
                                              Map<CubeKey,int[]> cubes) {
        return new GeneratedNativeWindow(NativeFirstViewPlan.Stage.FULL,epoch,x,y,z,radius,sky,block,cubes,null);
    }

    /** Constructs only a complete exact interior; scheduling/promotion stays on the client owner. */
    public static Optional<GeneratedNativeWindow> near(NativeFirstViewPlan.Plan plan,long currentEpoch,
                                                       VoxelSkyLight.Result sky,VoxelSkyLight.Result block,
                                                       Map<CubeKey,int[]> cubes) {
        if(cubes==null)return Optional.empty();
        var certificate=NativeFirstViewPlan.certify(plan,currentEpoch,sky,block,(x,y,z)->state(cubes,x,y,z)>=0);
        return certificate.map(c->new GeneratedNativeWindow(NativeFirstViewPlan.Stage.NEAR,plan.authorityEpoch(),
                plan.anchorX(),plan.anchorY(),plan.anchorZ(),plan.effectiveRadius(),sky,block,cubes,c));
    }

    public NativeFirstViewPlan.Stage stage(){return stage;}
    public long epoch(){return epoch;}
    public int radius(){return radius;}
    public int anchorX(){return anchorX;}public int anchorY(){return anchorY;}public int anchorZ(){return anchorZ;}
    public VoxelSkyLight.Bounds solveBounds(){return sky.bounds();}
    public VoxelSkyLight.Bounds drawBounds(){return drawBounds;}
    public int requestedRadius(){return nearCoverage==null?radius:nearCoverage.plan().requestedRadius();}
    public VoxelSkyLight.Result sky(){return sky;}public VoxelSkyLight.Result block(){return block;}
    public Map<CubeKey,int[]> cubes(){return cubes;}
    public int minimumY(){return drawBounds.minY();}
    public int maximumY(){return Math.addExact(drawBounds.minY(),drawBounds.sizeY()-1);}

    public boolean readyFor(int targetRadius){return nearCoverage==null?radius==targetRadius:
            Math.min(nearCoverage.plan().requestedRadius(),NativeRenderDistance.MAX_CHUNKS*16)==targetRadius;}
    public boolean nativeReady(double x,double y,double z,int targetRadius,long currentEpoch) {
        if(epoch!=currentEpoch||!signedCoordinate(x)||!signedCoordinate(y)||!signedCoordinate(z)
                ||!readyFor(targetRadius)||!contains(drawBounds,x,y,z))return false;
        if(nearCoverage!=null){double dx=x-anchorX,dz=z-anchorZ;return dx*dx+dz*dz<=radius*(double)radius;}
        return anchorX==anchor(x)&&anchorY==anchor(y)&&anchorZ==anchor(z);
    }
    public boolean allowsBlock(int bx,int by,int bz,double x,double y,double z,int down,int up) {
        if(!contains(drawBounds,bx,by,bz))return false;
        if(nearCoverage!=null)return true;
        double dx=bx+.5-x,dz=bz+.5-z;
        return dx*dx+dz*dz<=(radius+8.0)*(radius+8.0)&&by+1>=y-down-2&&by<=y+up+2;
    }
    public boolean intersectsDraw(CubeKey key) {
        long x=(long)key.x()*16,y=(long)key.y()*16,z=(long)key.z()*16;
        return x+16>drawBounds.minX()&&x<(long)drawBounds.minX()+drawBounds.sizeX()
                &&y+16>drawBounds.minY()&&y<(long)drawBounds.minY()+drawBounds.sizeY()
                &&z+16>drawBounds.minZ()&&z<(long)drawBounds.minZ()+drawBounds.sizeZ();
    }
    public boolean wholeCubeInDraw(CubeKey key) {
        long x=(long)key.x()*16,y=(long)key.y()*16,z=(long)key.z()*16;
        return x>=drawBounds.minX()&&x+16<=(long)drawBounds.minX()+drawBounds.sizeX()
                &&y>=drawBounds.minY()&&y+16<=(long)drawBounds.minY()+drawBounds.sizeY()
                &&z>=drawBounds.minZ()&&z+16<=(long)drawBounds.minZ()+drawBounds.sizeZ();
    }
    /** Missing geometry refuses the candidate; it is never an occluded face or an AIR id. */
    public int neighborState(long x,long y,long z) {
        if(x<Integer.MIN_VALUE||x>Integer.MAX_VALUE||y<Integer.MIN_VALUE||y>Integer.MAX_VALUE
                ||z<Integer.MIN_VALUE||z>Integer.MAX_VALUE)throw new IllegalStateException("Native neighbor outside signed XYZ");
        int ix=(int)x,iy=(int)y,iz=(int)z;
        int[] states=stateIndex.get(Math.floorDiv(ix,16),Math.floorDiv(iy,16),Math.floorDiv(iz,16));
        int id=states==null?-1:states[(iy&15)<<8|(iz&15)<<4|(ix&15)];
        if(id<0)throw new IllegalStateException("Unknown generated native neighbor "+x+","+y+","+z);
        return id;
    }
    public OptionalInt packed(int x,int y,int z) {
        var s=sky.value(x,y,z);var b=block.value(x,y,z);
        return s.isPresent()&&b.isPresent()?OptionalInt.of((b.getAsInt()<<4)|(s.getAsInt()<<20)):OptionalInt.empty();
    }
    private static int state(Map<CubeKey,int[]> cubes,int x,int y,int z) {
        int[] values=cubes.get(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)));
        return values==null||values.length!=4096?-1:values[(y&15)<<8|(z&15)<<4|(x&15)];
    }
    private static int anchor(double value) {
        double floor=Math.floor(value);
        if(floor<Integer.MIN_VALUE||floor>Integer.MAX_VALUE)throw new IllegalArgumentException("Camera outside signed XYZ");
        return Math.floorDiv((int)floor,16)*16;
    }
    private static boolean signedCoordinate(double value){return Double.isFinite(value)
            &&Math.floor(value)>=Integer.MIN_VALUE&&Math.floor(value)<=Integer.MAX_VALUE;}
    private static boolean contains(VoxelSkyLight.Bounds b,double x,double y,double z) {
        return x>=b.minX()&&x<(long)b.minX()+b.sizeX()&&y>=b.minY()&&y<(long)b.minY()+b.sizeY()
                &&z>=b.minZ()&&z<(long)b.minZ()+b.sizeZ();
    }
    /** All queried face light is at least15 cells from a possibly clipped solve boundary. */
    private static VoxelSkyLight.Bounds fullDrawBounds(VoxelSkyLight.Bounds b) {
        long[] min={b.minX(),b.minY(),b.minZ()},size={b.sizeX(),b.sizeY(),b.sizeZ()};
        int[] start=new int[3],length=new int[3];
        for(int axis=0;axis<3;axis++) {
            long firstCube=-Math.floorDiv(-min[axis],16)*16,lastCube=Math.floorDiv(min[axis]+size[axis],16)*16;
            long lo=Math.max(min[axis]+16,firstCube+2),hi=Math.min(min[axis]+size[axis]-16,lastCube-2);
            if(hi<=lo)throw new IllegalStateException("No certified native DRAW interior");
            start[axis]=Math.toIntExact(lo);length[axis]=Math.toIntExact(hi-lo);
        }
        return new VoxelSkyLight.Bounds(start[0],start[1],start[2],length[0],length[1],length[2]);
    }
}
