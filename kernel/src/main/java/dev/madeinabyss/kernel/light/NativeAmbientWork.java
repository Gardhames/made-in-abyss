package dev.madeinabyss.kernel.light;
import java.util.ArrayList;
import java.util.List;

/** Small first views own whole-height lattice columns; large windows retain proven Y locality. */
public final class NativeAmbientWork {
    private NativeAmbientWork(){}
    public static List<VoxelSkyLight.Bounds> plan(VoxelSkyLight.Bounds b,int workers) {
        VoxelSkyLight.cellCount(b,40_000_000);
        if(workers<1||workers>4)throw new IllegalArgumentException("Native workers must be1..4");
        var out=new ArrayList<VoxelSkyLight.Bounds>();
        if(b.sizeX()<=80&&b.sizeY()<=80&&b.sizeZ()<=80
                &&Boolean.parseBoolean(System.getProperty("mia.generated.nearAmbientColumns","true"))) {
            long endX=(long)b.minX()+b.sizeX(),endZ=(long)b.minZ()+b.sizeZ();
            for(long z=b.minZ();z<endZ;){long z1=Math.min(endZ,Math.floorDiv(z,32)*32+32);
                for(long x=b.minX();x<endX;){long x1=Math.min(endX,Math.floorDiv(x,32)*32+32);
                    out.add(new VoxelSkyLight.Bounds((int)x,b.minY(),(int)z,(int)(x1-x),b.sizeY(),(int)(z1-z)));x=x1;
                }z=z1;
            }
        } else for(var slab:AlignedVoxelSlabs.plan(b.minY(),b.sizeY(),workers))
            out.add(new VoxelSkyLight.Bounds(b.minX(),slab.minimum(),b.minZ(),b.sizeX(),slab.limit()-slab.minimum(),b.sizeZ()));
        return List.copyOf(out);
    }
}
