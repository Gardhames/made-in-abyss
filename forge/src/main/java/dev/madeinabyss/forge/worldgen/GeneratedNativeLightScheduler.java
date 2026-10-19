package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.forge.worldgen.GeneratedNativeLightSolver.Timings;
import dev.madeinabyss.forge.worldgen.GeneratedNativeLightSolver.Result;
import dev.madeinabyss.kernel.concurrent.BoundedOrderedWork;
import dev.madeinabyss.kernel.light.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/** Exact native work scheduling used by client and acceptance. The historical solver stays byte-identical. */
public final class GeneratedNativeLightScheduler {
    private GeneratedNativeLightScheduler(){}
    private record Density(CubeKey key,int[] states,long reused,long sampled){}
    private record Ambient(long queries,long denseReads,long fallbackReads){}
    private record Tile(AlignedVoxelSlabs.Slab x,AlignedVoxelSlabs.Slab y,AlignedVoxelSlabs.Slab z){}
    public static Result solve(GeneratedSkySource source,VoxelSkyLight.Bounds bounds,Map<CubeKey,int[]> previous,
                               ExecutorService phases,int workers,Runnable checkpoint,BiConsumer<String,CubeKey> observer) {
        Objects.requireNonNull(checkpoint);Objects.requireNonNull(observer);checkpoint.run();long start=System.nanoTime();
        int cells=VoxelSkyLight.cellCount(bounds,40_000_000),sx=bounds.sizeX(),sz=bounds.sizeZ(),plane=sx*sz;
        byte[] opacity=new byte[cells],sky=new byte[cells],emission=new byte[cells];
        var tiles=tiles(bounds);observer.accept("DENSITY_BEGIN",null);
        var density=BoundedOrderedWork.map(tiles,workers,phases,1,tile->{
            try(var scope=source.requestCheckpoint(checkpoint)){
                checkpoint.run();CubeKey key=null;int[] row=null;long reused=0,sampled=0;
                if(tile.x.limit()-tile.x.minimum()==16&&tile.y.limit()-tile.y.minimum()==16&&tile.z.limit()-tile.z.minimum()==16) {
                    key=new CubeKey(Math.floorDiv(tile.x.minimum(),16),Math.floorDiv(tile.y.minimum(),16),Math.floorDiv(tile.z.minimum(),16));
                    row=previous.get(key);
                    if(row!=null){if(row.length!=4096)throw new IllegalStateException("Incomplete prior native cube");reused=4096;}
                    else {row=source.captureDerivedCube(key,checkpoint);sampled=4096;}
                    observer.accept("DENSITY_CUBE_READY",key);
                }
                for(int y=tile.y.minimum();y<tile.y.limit();y++)for(int z=tile.z.minimum();z<tile.z.limit();z++){
                    checkpoint.run();int zz=z-bounds.minZ();
                    for(int x=tile.x.minimum();x<tile.x.limit();x++){
                        int xx=x-bounds.minX();
                        int state;if(row!=null)state=row[(y&15)<<8|(z&15)<<4|(x&15)];else{state=source.stateIdForDerivedGeometry(x,y,z);sampled++;}
                        int cell=xx+sx*(zz+sz*(y-bounds.minY()));opacity[cell]=(byte)source.opacity(state);emission[cell]=(byte)source.emission(state);
                    }
                }return new Density(key,row,reused,sampled);
            }
        });
        var cubes=new HashMap<CubeKey,int[]>();long reused=0,sampled=0;
        for(var tile:density){if(tile.key!=null&&cubes.put(tile.key,tile.states)!=null)throw new IllegalStateException("Native cube has multiple writers");reused+=tile.reused;sampled+=tile.sampled;}
        checkpoint.run();source.publishDenseOpacity(bounds,opacity);long densityMillis=millis(start),directStart=System.nanoTime();
        observer.accept("DIRECT_SKY_BEGIN",null);var exposure=new GeneratedSkySource.SkyExposure[sx*sz];int topY=bounds.minY()+bounds.sizeY()-1;
        var columns=new ArrayList<Tile>();
        for(var z:AlignedVoxelSlabs.tiles(bounds.minZ(),sz))for(var x:AlignedVoxelSlabs.tiles(bounds.minX(),sx))columns.add(new Tile(x,null,z));
        BoundedOrderedWork.map(columns,workers,phases,1,tile->{
            try(var scope=source.requestCheckpoint(checkpoint)){
                for(int z=tile.z.minimum();z<tile.z.limit();z++)for(int x=tile.x.minimum();x<tile.x.limit();x++){
                    int xx=x-bounds.minX(),zz=z-bounds.minZ();
                    checkpoint.run();var column=source.nativeColumnExposure(x,z);exposure[xx+sx*zz]=column;
                    DirectSkyColumnSeed.fill(opacity,sky,xx+sx*zz,plane,bounds.sizeY(),column.directSky(topY));
                }return true;
            }
        });
        long directMillis=millis(directStart),ambientStart=System.nanoTime();observer.accept("AMBIENT_BEGIN",null);
        // FULL retains broad Y locality. Small NEAR windows instead spread a thin hot
        // surface over32m XZ owners without subdividing a shared lattice cell further.
        var ambient=BoundedOrderedWork.map(NativeAmbientWork.plan(bounds,workers),workers,phases,1,slab->{
            try(var scope=source.requestCheckpoint(checkpoint)){
                long[] reads=new long[2];long queries=0;int[] visited={0};
                var rayOpacity=source.nativeRayOpacity();
                SkyVisibility.VoxelOpacity lookup=(x,y,z)->{
                    if((visited[0]++&255)==0)checkpoint.run();
                    long xx=(long)x-bounds.minX(),yy=(long)y-bounds.minY(),zz=(long)z-bounds.minZ();
                    if(xx>=0&&xx<sx&&yy>=0&&yy<bounds.sizeY()&&zz>=0&&zz<sz){reads[0]++;return opacity[(int)(xx+sx*(zz+sz*yy))];}
                    reads[1]++;return rayOpacity.opacity(x,y,z);
                };
                int x0=Math.max(1,slab.minX()-bounds.minX()),x1=Math.min(sx-1,Math.toIntExact((long)slab.minX()+slab.sizeX()-bounds.minX()));
                int z0=Math.max(1,slab.minZ()-bounds.minZ()),z1=Math.min(sz-1,Math.toIntExact((long)slab.minZ()+slab.sizeZ()-bounds.minZ()));
                for(int y=Math.max(slab.minY(),bounds.minY()+1);y<Math.min((long)slab.minY()+slab.sizeY(),(long)bounds.minY()+bounds.sizeY()-1);y++)for(int zz=z0;zz<z1;zz++){
                    checkpoint.run();for(int xx=x0;xx<x1;xx++){
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
    private static List<Tile> tiles(VoxelSkyLight.Bounds bounds) {
        var xs=AlignedVoxelSlabs.tiles(bounds.minX(),bounds.sizeX());
        var zs=AlignedVoxelSlabs.tiles(bounds.minZ(),bounds.sizeZ());
        var result=new ArrayList<Tile>();
        for(var y:AlignedVoxelSlabs.tiles(bounds.minY(),bounds.sizeY()))for(var z:zs)for(var x:xs)result.add(new Tile(x,y,z));
        return result;
    }
    private static long millis(long start){return (System.nanoTime()-start)/1_000_000;}
}
