package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import java.util.Objects;

/** Maximum exposed scalar SKY for a coarse box; no unavailable samples become numeric light. */
public final class LodBoxSkyLight {
    @FunctionalInterface public interface Sampler {int sample(int x,int y,int z);}
    private LodBoxSkyLight() {}
    public static int maximum(TerrainLodColumns.Box box,Sampler opacity,Sampler direct,Sampler diffuse) {
        return maximum(box,Integer.MIN_VALUE,opacity,direct,diffuse);
    }
    public static int maximum(TerrainLodColumns.Box box,int minimumKnownY,Sampler opacity,Sampler direct,Sampler diffuse) {
        Objects.requireNonNull(box);Objects.requireNonNull(opacity);Objects.requireNonNull(direct);Objects.requireNonNull(diffuse);
        long x=box.minX()+(box.maxXExclusive()-box.minX()-1)/2;
        long y=box.minY()+(box.maxYExclusive()-box.minY()-1)/2;
        long z=box.minZ()+(box.maxZExclusive()-box.minZ()-1)/2;
        // A face at the declared lower domain boundary has no known external voxel.
        // Omit that one external probe; unavailable samples inside the domain still throw.
        long[][] coordinates=box.minY()>minimumKnownY
                ?new long[][]{{x,box.maxYExclusive(),z},{box.minX()-1,y,z},{box.maxXExclusive(),y,z},
                    {x,y,box.minZ()-1},{x,y,box.maxZExclusive()},{x,box.minY()-1,z}}
                :new long[][]{{x,box.maxYExclusive(),z},{box.minX()-1,y,z},{box.maxXExclusive(),y,z},
                    {x,y,box.minZ()-1},{x,y,box.maxZExclusive()}};
        var points=new int[coordinates.length][3];
        for(int i=0;i<coordinates.length;i++)for(int axis=0;axis<3;axis++)points[i][axis]=Math.toIntExact(coordinates[i][axis]);
        int sky=0;
        for(var point:points){
            if(value(opacity.sample(point[0],point[1],point[2]))<15)sky=Math.max(sky,value(direct.sample(point[0],point[1],point[2])));
            if(sky==15)break;
        }
        if(sky<14)for(var point:points){
            sky=Math.max(sky,value(diffuse.sample(point[0],point[1],point[2])));
            if(sky>=14)break;
        }
        return sky;
    }
    private static int value(int input){if(input<0||input>15)throw new IllegalArgumentException("Scalar light/opacity must be0..15");return input;}
}
