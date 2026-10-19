package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.concurrent.BoundedOrderedWork;
import dev.madeinabyss.kernel.light.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/** Common exact native solve: the client and headless acceptance execute this same code. */
public final class GeneratedNativeLightSolver {
    private GeneratedNativeLightSolver(){}
    public record Timings(long densityMillis,long directSkyMillis,long ambientSeedMillis,long skyPropagationMillis,
                          long blockPropagationMillis,long ambientSeedQueries,int directSkyColumns,long reusedDensityCells,
                          long sampledDensityCells,long denseOpacityQueries,long fallbackOpacityQueries){}
    public record Result(VoxelSkyLight.Result sky,VoxelSkyLight.Result block,Map<CubeKey,int[]> cubes,
                         GeneratedSkySource.SkyExposure[] exposure,Timings timings,long millis){}
    private record Density(Map<CubeKey,int[]> cubes,long reused,long sampled){}
    private record Ambient(long queries,long denseReads,long fallbackReads){}
    public static Result solve(GeneratedSkySource source,VoxelSkyLight.Bounds bounds,Map<CubeKey,int[]> previous,
                               ExecutorService phases,int workers,Runnable checkpoint,BiConsumer<String,CubeKey> observer) {
        Objects.requireNonNull(checkpoint);Objects.requireNonNull(observer);checkpoint.run();long start=System.nanoTime();
        int cells=VoxelSkyLight.cellCount(bounds,40_000_000),sx=bounds.sizeX(),sz=bounds.sizeZ(),plane=sx*sz;
        byte[] opacity=new byte[cells],sky=new byte[cells],emission=new byte[cells];
        var slabs=AlignedVoxelSlabs.plan(bounds.minY(),bounds.sizeY(),workers);observer.accept("DENSITY_BEGIN",null);
        var density=BoundedOrderedWork.map(slabs,workers,phases,slab->{
            try(var scope=source.requestCheckpoint(checkpoint)){
                var cubes=new HashMap<CubeKey,int[]>();long reused=0,sampled=0;
                for(int y=slab.minimum();y<slab.limit();y++)for(int zz=0;zz<sz;zz++){
                    checkpoint.run();int z=bounds.minZ()+zz,lastX=Integer.MIN_VALUE;int[] row=null;
                    for(int xx=0;xx<sx;xx++){
                        int x=bounds.minX()+xx,cx=Math.floorDiv(x,16);
                        if(cx!=lastX){lastX=cx;var key=new CubeKey(cx,Math.floorDiv(y,16),Math.floorDiv(z,16));
                            long bx=(long)cx*16,by=(long)key.y()*16,bz=(long)key.z()*16;
                            boolean complete=bx>=bounds.minX()&&bx+15<(long)bounds.minX()+sx&&by>=bounds.minY()&&by+15<(long)bounds.minY()+bounds.sizeY()&&bz>=bounds.minZ()&&bz+15<(long)bounds.minZ()+sz;
                            row=complete?cubes.get(key):null;
                            if(complete&&row==null){row=previous.get(key);if(row!=null){if(row.length!=4096)throw new IllegalStateException("Incomplete prior native cube");reused+=4096;}
                                else {row=source.captureDerivedCube(key,checkpoint);sampled+=4096;}cubes.put(key,row);observer.accept("DENSITY_CUBE_READY",key);}
                        }
                        int state;if(row!=null)state=row[(y&15)<<8|(z&15)<<4|(x&15)];else{state=source.stateIdForDerivedGeometry(x,y,z);sampled++;}
                        int cell=xx+sx*(zz+sz*(y-bounds.minY()));opacity[cell]=(byte)source.opacity(state);emission[cell]=(byte)source.emission(state);
                    }
                }return new Density(cubes,reused,sampled);
            }
        });
        var cubes=new HashMap<CubeKey,int[]>();long reused=0,sampled=0;
        for(var slab:density){for(var entry:slab.cubes.entrySet())if(cubes.put(entry.getKey(),entry.getValue())!=null)throw new IllegalStateException("Native cube has multiple writers");reused+=slab.reused;sampled+=slab.sampled;}
        checkpoint.run();source.publishDenseOpacity(bounds,opacity);long densityMillis=millis(start),directStart=System.nanoTime();
        observer.accept("DIRECT_SKY_BEGIN",null);var exposure=new GeneratedSkySource.SkyExposure[sx*sz];int topY=bounds.minY()+bounds.sizeY()-1;
        BoundedOrderedWork.map(AlignedVoxelSlabs.plan(0,sz,workers),workers,phases,slab->{
            try(var scope=source.requestCheckpoint(checkpoint)){
                for(int zz=slab.minimum();zz<slab.limit();zz++)for(int xx=0;xx<sx;xx++){
                    checkpoint.run();var column=source.nativeColumnExposure(bounds.minX()+xx,bounds.minZ()+zz);exposure[xx+sx*zz]=column;
                    DirectSkyColumnSeed.fill(opacity,sky,xx+sx*zz,plane,bounds.sizeY(),column.directSky(topY));
                }return true;
            }
        });
        long directMillis=millis(directStart),ambientStart=System.nanoTime();observer.accept("AMBIENT_BEGIN",null);
        var ambient=BoundedOrderedWork.map(slabs,workers,phases,slab->{
            try(var scope=source.requestCheckpoint(checkpoint)){
                long[] reads=new long[2];long queries=0;int[] visited={0};
                var rayOpacity=source.nativeRayOpacity();
                SkyVisibility.VoxelOpacity lookup=(x,y,z)->{
                    if((visited[0]++&255)==0)checkpoint.run();
                    long xx=(long)x-bounds.minX(),yy=(long)y-bounds.minY(),zz=(long)z-bounds.minZ();
                    if(xx>=0&&xx<sx&&yy>=0&&yy<bounds.sizeY()&&zz>=0&&zz<sz){reads[0]++;return opacity[(int)(xx+sx*(zz+sz*yy))];}
                    reads[1]++;return rayOpacity.opacity(x,y,z);
                };
                for(int y=Math.max(slab.minimum(),bounds.minY()+1);y<Math.min(slab.limit(),bounds.minY()+bounds.sizeY()-1);y++)for(int zz=1;zz<sz-1;zz++){
                    checkpoint.run();for(int xx=1;xx<sx-1;xx++){
                        int i=xx+sx*(zz+sz*(y-bounds.minY()));if(opacity[i]>=15||sky[i]==15)continue;
                        if(opacity[i-1]>=15||opacity[i+1]>=15||opacity[i-sx]>=15||opacity[i+sx]>=15||opacity[i-plane]>=15||opacity[i+plane]>=15){
                            queries++;sky[i]=(byte)Math.max(sky[i],source.ambientSkyCoarse(bounds.minX()+xx,y,bounds.minZ()+zz,lookup,sky[i]));
                        }
                    }
                }return new Ambient(queries,reads[0],reads[1]);
            }
        });
        long queries=0,denseReads=0,fallback=0;for(var a:ambient){queries+=a.queries;denseReads+=a.denseReads;fallback+=a.fallbackReads;}
        long ambientMillis=millis(ambientStart),skyStart=System.nanoTime();checkpoint.run();observer.accept("SKY_PROPAGATION_BEGIN",null);
        var skyResult=VoxelSkyLight.solveExpanded(bounds,opacity,sky,40_000_000,checkpoint);long skyMillis=millis(skyStart),blockStart=System.nanoTime();
        observer.accept("BLOCK_PROPAGATION_BEGIN",null);var blockResult=VoxelSkyLight.solveBlockExpanded(bounds,opacity,emission,40_000_000,checkpoint);
        long blockMillis=millis(blockStart);checkpoint.run();observer.accept("LIGHT_READY",null);
        return new Result(skyResult,blockResult,Map.copyOf(cubes),exposure,
                new Timings(densityMillis,directMillis,ambientMillis,skyMillis,blockMillis,queries,sx*sz,reused,sampled,denseReads,fallback),millis(start));
    }
    private static long millis(long start){return (System.nanoTime()-start)/1_000_000;}
}
