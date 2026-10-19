package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.VoxelSkyLight;
import dev.madeinabyss.kernel.cube.NativeRenderDistance;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntFunction;

/** Exact density transfer only. Native light is intentionally absent from this boundary. */
public final class AbyssNativeDensity {
    private AbyssNativeDensity(){}
    public record Authority(String sourceIdentity,long sourceRevision,long epoch,long nativeRevision){
        public Authority{Objects.requireNonNull(sourceIdentity);if(sourceIdentity.isEmpty())throw new IllegalArgumentException("Full native source identity required");}
    }
    public static final class Data {
        private final Map<CubeKey,int[]> cubes;
        // Allocation-free per-voxel view of the same immutable arrays (LOD terrain sampling hot path).
        private final dev.madeinabyss.kernel.concurrent.FrozenCubeTable<int[]> table;
        private Data(Map<CubeKey,int[]> cubes){this.cubes=Map.copyOf(cubes);table=new dev.madeinabyss.kernel.concurrent.FrozenCubeTable<>(this.cubes);}
        /** Worker-only ownership transfer: full arrays are never mutated after publication.
         * No caller can obtain an array through the exported Data/Snapshot API.
         */
        public static Data fromOwned(VoxelSkyLight.Bounds solveBounds,Map<CubeKey,int[]> cubes){
            Objects.requireNonNull(solveBounds);Objects.requireNonNull(cubes);
            VoxelSkyLight.cellCount(solveBounds,NativeRenderDistance.MAX_CELLS);
            if(cubes.size()>NativeRenderDistance.MAX_CELLS/4096)throw new IllegalArgumentException("Native density cube cap exceeded");
            var complete=new HashMap<CubeKey,int[]>();
            for(var e:cubes.entrySet()){
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                var key=e.getKey();var states=e.getValue();if(key==null||states==null||states.length!=4096)continue;
                long x=(long)key.x()*16,y=(long)key.y()*16,z=(long)key.z()*16;
                if(x<solveBounds.minX()||x+16>(long)solveBounds.minX()+solveBounds.sizeX()||y<solveBounds.minY()||y+16>(long)solveBounds.minY()+solveBounds.sizeY()
                    ||z<solveBounds.minZ()||z+16>(long)solveBounds.minZ()+solveBounds.sizeZ())continue;
                boolean known=true;for(int state:states)if(state<0){known=false;break;}
                if(known)complete.put(key,states);
            }
            return new Data(complete);
        }
    }
    public static final class Snapshot {
        private final Data data;private final Authority authority;private final long publicationRevision;private final IntFunction<TerrainMaterial> mapper;
        public Snapshot(Data data,Authority authority,long publicationRevision,IntFunction<TerrainMaterial> mapper){
            this.data=Objects.requireNonNull(data);this.authority=Objects.requireNonNull(authority);this.publicationRevision=publicationRevision;this.mapper=Objects.requireNonNull(mapper);
        }
        public Authority authority(){return authority;}public long publicationRevision(){return publicationRevision;}
        public Set<CubeKey> wholeCubeKeys(Authority expected){return authority.equals(expected)?data.cubes.keySet():Set.of();}
        public OptionalInt state(Authority expected,int x,int y,int z){
            if(!authority.equals(expected))return OptionalInt.empty();var states=data.cubes.get(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)));
            return states==null?OptionalInt.empty():OptionalInt.of(states[(y&15)<<8|(z&15)<<4|(x&15)]);
        }
        /** Same answer as material(...).orElse(null) without per-voxel key/Optional allocation. */
        public TerrainMaterial materialOrNull(Authority expected,int x,int y,int z){
            if(authority!=expected&&!authority.equals(expected))return null;
            var states=data.table.get(x>>4,y>>4,z>>4);
            return states==null?null:mapper.apply(states[(y&15)<<8|(z&15)<<4|(x&15)]);
        }
        public Optional<TerrainMaterial> material(Authority expected,int x,int y,int z){var state=state(expected,x,y,z);return state.isEmpty()?Optional.empty():Optional.ofNullable(mapper.apply(state.getAsInt()));}
        /** Worker-only RAW conversion of a fully certified tile. No native light or source generation. */
        public Optional<TerrainLodColumns.Result> rawRegion(Authority expected,TerrainLodColumns.Bounds bounds,int step,int maxBoxes){
            Objects.requireNonNull(bounds);if(!authority.equals(expected))return Optional.empty();
            long sx=(long)bounds.maxXInclusive()-bounds.minX()+1,sy=(long)bounds.maxYInclusive()-bounds.minY()+1,sz=(long)bounds.maxZInclusive()-bounds.minZ()+1;
            if(sx!=16||sz!=16||sy<16||sy>64||sy%16!=0||(bounds.minX()&15)!=0||(bounds.minY()&15)!=0||(bounds.minZ()&15)!=0)return Optional.empty();
            if(step<1||step>16||(step&(step-1))!=0||maxBoxes<1)throw new IllegalArgumentException("Invalid exact native RAW parameters");
            var rows=new int[(int)(sy/16)][];int cx=Math.floorDiv(bounds.minX(),16),cy=Math.floorDiv(bounds.minY(),16),cz=Math.floorDiv(bounds.minZ(),16);
            for(int i=0;i<rows.length;i++){rows[i]=data.cubes.get(new CubeKey(cx,cy+i,cz));if(rows[i]==null)return Optional.empty();}
            var known=new HashMap<Integer,TerrainSample.Known>();
            dev.madeinabyss.kernel.terrain.trail.TrailNetwork.SampleSource terrain=(x,y,z)->{
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
                int id=rows[(int)((y-bounds.minY())/16)][((int)y&15)<<8|((int)z&15)<<4|((int)x&15)];
                return known.computeIfAbsent(id,state->new TerrainSample.Known(Objects.requireNonNull(mapper.apply(state),"Native state material missing")));
            };
            // TerrainLodColumns changes XZ sampling only; every vertical block stays sampled.
            long samples=(sx/step)*sy*(sz/step);
            return Optional.of(TerrainLodColumns.build(terrain,bounds,new TerrainLodColumns.Parameters(step,samples,maxBoxes)));
        }
    }
}
