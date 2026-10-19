package dev.madeinabyss.kernel.light;

/** Completed exact opacity density; caller transfers array ownership and must never mutate it. */
public final class DenseOpacityVolume {
    private final VoxelSkyLight.Bounds bounds;
    private final byte[] values;
    private DenseOpacityVolume(VoxelSkyLight.Bounds bounds,byte[] values){this.bounds=bounds;this.values=values;}
    /** The caller must have joined every writer and must relinquish all future array writes. */
    public static DenseOpacityVolume takeOwnership(VoxelSkyLight.Bounds bounds,byte[] values,int maximumCells){
        int count=VoxelSkyLight.cellCount(bounds,maximumCells);java.util.Objects.requireNonNull(values);
        if(values.length!=count)throw new IllegalArgumentException("Density length must equal complete bounds volume");
        for(byte value:values)if(value<0||value>15)throw new IllegalArgumentException("Density opacity must be0..15");
        return new DenseOpacityVolume(bounds,values);
    }
    /** -1 means outside this completed volume, never inferred AIR. Does not allocate. */
    public int opacity(int x,int y,int z){
        long xx=(long)x-bounds.minX(),yy=(long)y-bounds.minY(),zz=(long)z-bounds.minZ();
        if(xx<0||xx>=bounds.sizeX()||yy<0||yy>=bounds.sizeY()||zz<0||zz>=bounds.sizeZ())return -1;
        return values[(int)(xx+bounds.sizeX()*(zz+bounds.sizeZ()*yy))];
    }
    public int residentBytes(){return values.length;}
    public VoxelSkyLight.Bounds bounds(){return bounds;}
}
