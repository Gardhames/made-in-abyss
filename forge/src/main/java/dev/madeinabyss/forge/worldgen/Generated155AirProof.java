package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.volume.B1AirBounds;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Optional derived AIR proof for the exact frozen B1 composition. No terrain mutation. */
public final class Generated155AirProof {
    private static final String FP="3af600402e26dd456daf516a782ac7d5b94ecc3b2d22628d4d256d505c9238ae";
    private static final String SHA="d992fb787da808feb9aa530b9a89acecdf485c72f55edae7beedd41953cf7013";
    private final GeneratedSkySource source;
    private final B1AirBounds bounds;
    private final IslandSurfaceGeneration island;
    private record XZ(int x,int z){}
    private final ConcurrentHashMap<XZ,Integer> top=new ConcurrentHashMap<>();
    public Generated155AirProof(GeneratedPreviewProfile profile,GeneratedSkySource source){
        if(!FP.equals(profile.fingerprint())||!SHA.equals(LivingAbyssGeneration.algorithmSha256()))throw new IllegalArgumentException("Unproved B1 recipe/code");
        var living=profile.generator().livingAbyss();if(living==null||living.revision155()==null||!living.revision155().stageB()||!living.revision155().floorSupportFinished()||living.recipeValue().domain().temporaryFloorY()+8>=-1350)throw new IllegalArgumentException("Unproved B1 composition");
        this.source=Objects.requireNonNull(source);var shape=living.revision155();bounds=new B1AirBounds(shape.ledges(),shape.halls(),shape.speleothems(),shape.floorFeatures());island=profile.generator().islandSurface();
    }
    /** 0=refused,1=old actual centre proof,2=new shaft envelope,3=owned parent sky. */
    public int kind(CubeKey key){
        source.requirePhysicalAuthority();if(source.volumeCubeOverridden(key))return 0;int old=source.uniformVolumeState(key);
        if(old>=0&&source.opacity(old)==0&&net.minecraft.world.level.block.Block.stateById(old).isAir())return 1;
        if(bounds.guaranteedAir(key))return 2;
        long x=(long)key.x()*16,y=(long)key.y()*16,z=(long)key.z()*16;
        if(island==null||y<=0||x<Integer.MIN_VALUE||x+15>Integer.MAX_VALUE||z<Integer.MIN_VALUE||z+15>Integer.MAX_VALUE)return 0;
        long nearX=x>0?x:x+15<0?x+15:0,nearZ=z>0?z:z+15<0?z+15:0;
        if((double)nearX*nearX+(double)nearZ*nearZ<250000)return 0;
        // B1 delegates all positiveY to parent; the island owns R>=500 finally.
        // Its terrain ends at floor(surfaceHeight), plants have the explicit local
        // guard y<=top+8 (including neighbouring crowns), water ends at0.
        int ceiling=top.computeIfAbsent(new XZ(key.x(),key.z()),k->{int maximum=Integer.MIN_VALUE;
            for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++)maximum=Math.max(maximum,Math.addExact((int)Math.floor(island.surfaceHeight((int)x+dx,(int)z+dz)),8));return maximum;});
        return y>ceiling?3:0;
    }
    public boolean guaranteedAir(CubeKey key){return kind(key)!=0;}
    public Map<String,Object> status(){return Map.of("shaft",bounds.status(),"ownedIslandXZBounds",top.size(),"fingerprint",FP,"generationSha",SHA);}
}
