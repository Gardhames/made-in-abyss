package dev.madeinabyss.forge.worldgen;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;

/** Owner-thread snapshot of resolved physical inputs. Client assets/shaders are not physical inputs. */
public final class GeneratedPhysicalSemantics {
    private GeneratedPhysicalSemantics() { }
    public record State(int id,String name,int opacity,int emission,boolean air,boolean fluidEmpty,
                        boolean dirt,boolean logs,boolean climbable,int blockKinds,String material,boolean collisionEmpty) { }
    public record Snapshot(List<State> states,boolean alex,boolean opening) {
        public Snapshot {states=List.copyOf(states);}
        public String identity() {
            try {
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest));
                out.writeUTF("resolved-generated-physical-semantics-v1");out.writeBoolean(alex);out.writeBoolean(opening);out.writeInt(states.size());
                for(var state:states){out.writeInt(state.id());out.writeUTF(state.name());out.writeInt(state.opacity());out.writeInt(state.emission());
                    out.writeBoolean(state.air());out.writeBoolean(state.fluidEmpty());out.writeBoolean(state.dirt());out.writeBoolean(state.logs());
                    out.writeBoolean(state.climbable());out.writeInt(state.blockKinds());out.writeUTF(state.material());out.writeBoolean(state.collisionEmpty());}
                out.flush();return java.util.HexFormat.of().formatHex(digest.digest());
            }catch(java.io.IOException|java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("Cannot identify resolved physical semantics",impossible);}
        }
        public void requireCurrent() {
            var current=capture(states.stream().map(State::id).toList(),alex,opening);
            if(!equals(current))throw new IllegalStateException("Resolved physical block semantics changed; rejoin the world for fresh generated authority");
        }
    }
    /** Only fields consumed by the admitted source/modifiers; unrelated registry tags are excluded. */
    public static Snapshot capture(Collection<Integer> ids,boolean alex,boolean opening) {
        var states=new ArrayList<State>();
        for(int id:new TreeSet<>(ids)){
            var state=Block.BLOCK_STATE_REGISTRY.byId(id);
            if(state==null||state.hasBlockEntity()||state.useShapeForLightOcclusion())throw new IllegalArgumentException("Unsupported scalar light state "+id);
            int opacity=state.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            int emission=state.getLightEmission(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
            if(opacity<0||opacity>15||emission<0||emission>15)throw new IllegalArgumentException("Non-scalar native light state "+id);
            boolean geometry=alex||opening;
            int kinds=opening?((state.getBlock() instanceof VineBlock?1:0)|(state.getBlock() instanceof BushBlock?2:0)
                    |(state.getBlock() instanceof LeavesBlock?4:0)|(state.getBlock() instanceof GrowingPlantBlock?8:0)):0;
            var material=alex?WildernessBlockStates.material(state):null;
            states.add(new State(id,state.toString(),opacity,emission,geometry&&state.isAir(),!geometry||state.getFluidState().isEmpty(),
                    alex&&state.is(BlockTags.DIRT),opening&&state.is(BlockTags.LOGS),opening&&state.is(BlockTags.CLIMBABLE),kinds,
                    material==null?"":material.name(),alex&&state.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).isEmpty()));
        }
        return new Snapshot(states,alex,opening);
    }
}
