package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.worldgen.IslandSurfaceBlockStates;
import dev.madeinabyss.kernel.concurrent.FrozenCubeTable;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.NativeFirstViewPlan;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Private immutable numeric preparation. Models, colors, liquid and vertices remain on the owner. */
public final class GeneratedNativeMeshPrefilter {
    private GeneratedNativeMeshPrefilter(){}
    /** Arrays transfer exclusively from the completed worker to the owner; neither mutates them. */
    public record Candidates(CubeKey key,int[] entries,int outside,boolean skipped){}
    public static int[] discover(Map<CubeKey,int[]> cubes,int registrySize,Runnable checkpoint) {
        if(registrySize<1)throw new IllegalArgumentException("Missing native palette");var ids=new BitSet(registrySize);
        for(var states:cubes.values()) {checkpoint.run();if(states.length!=4096)throw new IllegalArgumentException("Incomplete native cube");
            for(int i=0;i<4096;i++){if((i&255)==0)checkpoint.run();int id=states[i];
                // UNKNOWN outside DRAW must not cause an earlier refusal; the exact scan
                // refuses it only if the original owner would actually query that state.
                if(id>=0&&id<registrySize)ids.set(id);
            }
        }checkpoint.run();return ids.stream().toArray();
    }
    public static RuleBuilder rules(int[] ids,int registrySize){return new RuleBuilder(ids,registrySize);}
    /** One bounded palette row per owner step. No native callback is ever invoked by scan(). */
    public static final class RuleBuilder {
        private final int[] index;private final BlockState[] states;private final boolean[] model,fluid;private final byte[][] masks;
        private final int air=Block.getId(Blocks.AIR.defaultBlockState());private int row;
        private RuleBuilder(int[] ids,int registrySize){
            index=new int[registrySize];Arrays.fill(index,-1);states=new BlockState[ids.length];model=new boolean[ids.length];fluid=new boolean[ids.length];masks=new byte[ids.length][ids.length];
            for(int i=0;i<ids.length;i++){int id=ids[i];if(id<0||id>=registrySize||index[id]>=0)throw new IllegalArgumentException("Invalid native palette entry");
                index[id]=i;states[i]=Block.BLOCK_STATE_REGISTRY.byId(id);if(states[i]==null)throw new IllegalStateException("Unknown native palette state");}
        }
        public boolean step(){
            if(row==states.length)return true;int i=row++;var state=states[i];
            fluid[i]=!state.getFluidState().isEmpty();model[i]=IslandSurfaceBlockStates.admitsNativeModel(state,true);
            if(model[i])for(int j=0;j<states.length;j++) {
                var neighbor=states[j];int mask=0;
                if(Block.getId(neighbor)==air)mask=63;
                else if(!neighbor.canOcclude())for(var direction:Direction.values())if(!state.skipRendering(neighbor,direction))mask|=1<<direction.ordinal();
                masks[i][j]=(byte)mask;
            }return row==states.length;
        }
        public Rules finish(){if(row!=states.length)throw new IllegalStateException("Incomplete native palette rules");return new Rules(air,index,model,fluid,masks);}
    }
    /** Contains numbers/booleans only; no BlockState, client, live level or atlas reference. */
    public static final class Rules {
        private final int air;private final int[] index;private final boolean[] model,fluid;private final byte[][] masks;
        private Rules(int air,int[] index,boolean[] model,boolean[] fluid,byte[][] masks){this.air=air;this.index=index;this.model=model;this.fluid=fluid;this.masks=masks;}
        private int index(int id){int i=id>=0&&id<index.length?index[id]:-1;if(i<0)throw new IllegalStateException("Unknown staged native palette state "+id);return i;}
        private int face(int current,int neighbor,Direction direction){return masks[current][index(neighbor)]&(1<<direction.ordinal());}
    }
    /** Returns null on the memory cap, for an exact fallback to the old owner loop. */
    public static List<Candidates> scan(GeneratedNativeWindow window,List<CubeKey> ordered,Rules rules,
            int radius,int down,int up,double x,double y,double z,int maximumEntries,Runnable checkpoint) {
        if(maximumEntries<1)throw new IllegalArgumentException("Native candidate budget must be positive");
        var snapshots=window.cubes();var table=new FrozenCubeTable<int[]>(snapshots);var out=new ArrayList<Candidates>(ordered.size());int total=0;int[] entries=new int[4096];
        for(var key:ordered){checkpoint.run();int count=0,outside=0;boolean skipped=false;
            if(window.stage()==NativeFirstViewPlan.Stage.FULL){
                double x0=key.x()*16.0+.5,x1=x0+15,z0=key.z()*16.0+.5,z1=z0+15;
                double dx=x<x0?x0-x:x>x1?x-x1:0,dz=z<z0?z0-z:z>z1?z-z1:0;
                skipped=dx*dx+dz*dz>(radius+8.0)*(radius+8.0)||key.y()*16.0+16<y-down-2||key.y()*16.0>y+up+2;
            }
            if(!skipped){
                int[] states=snapshots.get(key);if(states==null)throw new IllegalStateException("Unknown staged native cube");
                int cx=key.x(),cy=key.y(),cz=key.z();
                int[] below=table.get(cx,cy-1,cz),above=table.get(cx,cy+1,cz),north=table.get(cx,cy,cz-1),south=table.get(cx,cy,cz+1),west=table.get(cx-1,cy,cz),east=table.get(cx+1,cy,cz);
                for(int i=0;i<4096;i++){
                    if((i&255)==0)checkpoint.run();int id=states[i];if(id==rules.air)continue;
                    int lx=i&15,ly=i>>>8,lz=(i>>>4)&15,bx=cx*16+lx,by=cy*16+ly,bz=cz*16+lz;
                    if(!window.allowsBlock(bx,by,bz,x,y,z,down,up)){outside++;continue;}
                    int current=rules.index(id),mask=0;
                    if(rules.model[current]){
                        mask|=rules.face(current,ly>0?states[i-256]:at(below,i+3840),Direction.DOWN);
                        mask|=rules.face(current,ly<15?states[i+256]:at(above,i-3840),Direction.UP);
                        mask|=rules.face(current,lz>0?states[i-16]:at(north,i+240),Direction.NORTH);
                        mask|=rules.face(current,lz<15?states[i+16]:at(south,i-240),Direction.SOUTH);
                        mask|=rules.face(current,lx>0?states[i-1]:at(west,i+15),Direction.WEST);
                        mask|=rules.face(current,lx<15?states[i+1]:at(east,i-15),Direction.EAST);
                    }
                    if(mask!=0||rules.fluid[current]){if(++total>maximumEntries)return null;entries[count++]=i<<8|(rules.fluid[current]?64:0)|mask;}
                }
            }
            out.add(new Candidates(key,Arrays.copyOf(entries,count),outside,skipped));
        }checkpoint.run();return List.copyOf(out);
    }
    private static int at(int[] states,int index){if(states==null)throw new IllegalStateException("Unknown staged native neighbor cube");return states[index];}
}
