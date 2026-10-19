package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.probe.deep.GeneratedNativeWindow;
import dev.madeinabyss.kernel.cube.*;
import dev.madeinabyss.kernel.light.VoxelSkyLight;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;

@GameTestHolder("near_scope")
@PrefixGameTestTemplate(false)
public final class T6NativePreparationGameTests {
    private static final String PREFILTER="dev.madeinabyss.forge.probe.deep.GeneratedNativeMeshPrefilter";
    private record Fixture(GeneratedNativeWindow window,List<CubeKey> ordered){}
    @GameTest(template="empty",timeoutTicks=1200)
    public static void workerPrefilterKeepsEverySignedModelFluidMaskAndOrder(GameTestHelper h)throws Exception {
        type(PREFILTER); // RED is a missing behavior assertion, never a client/GL classloading error.
        var f=fixture();var ids=(int[])stat(PREFILTER,"discover",f.window.cubes(),Block.BLOCK_STATE_REGISTRY.size(),(Runnable)()->{});
        var builder=stat(PREFILTER,"rules",ids,Block.BLOCK_STATE_REGISTRY.size());while(!(boolean)call(builder,"step")){}
        var rules=call(builder,"finish");var pool=Executors.newSingleThreadExecutor();
        try {
            var actual=(List<?>)pool.submit(()->stat(PREFILTER,"scan",f.window,f.ordered,rules,32,32,32,-16.0,-80.0,-16.0,2_097_152,(Runnable)()->{})).get();
            var expected=legacy(f.window,f.ordered,32,32,32,-16,-80,-16);long entries=0,visits=0;
            h.assertTrue(actual.size()==expected.size(),"All ordered cube completions, including empty cubes, survive");
            for(int i=0;i<actual.size();i++) {
                var a=actual.get(i);var e=expected.get(i);int[] values=(int[])call(a,"entries");entries+=values.length;
                h.assertTrue(f.ordered.get(i).equals(call(a,"key"))&&Objects.equals(e.get("outside"),call(a,"outside"))
                        &&Objects.equals(e.get("skipped"),call(a,"skipped"))&&Arrays.equals((int[])e.get("entries"),values),"Every candidate, six-bit exposure, fluid visit and y/z/x order is exact at "+i);
                visits+=(long)e.get("visits");
            }
            h.assertTrue(entries<visits/2,"Hidden solid blocks are actually removed from owner work");
            h.assertTrue(stat(PREFILTER,"scan",f.window,f.ordered,rules,32,32,32,-16.0,-80.0,-16.0,1,(Runnable)()->{})==null,"Bounded overflow falls back before any candidate is published");
            System.out.println("MIA_T6_PREFILTER_FIXTURE visited="+visits+" candidates="+entries);h.succeed();
        } finally {pool.shutdownNow();}
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void prefilterCancellationAndUnknownNeverProducePartialAir(GameTestHelper h)throws Exception {
        type(PREFILTER);var f=fixture();var ids=(int[])stat(PREFILTER,"discover",f.window.cubes(),Block.BLOCK_STATE_REGISTRY.size(),(Runnable)()->{});
        var builder=stat(PREFILTER,"rules",ids,Block.BLOCK_STATE_REGISTRY.size());while(!(boolean)call(builder,"step")){}var rules=call(builder,"finish");
        var checks=new AtomicInteger();boolean cancelled=false;
        try{stat(PREFILTER,"scan",f.window,f.ordered,rules,32,32,32,-16.0,-80.0,-16.0,2_097_152,(Runnable)()->{if(checks.incrementAndGet()==4)throw new CancellationException("retired");});}
        catch(CancellationException expected){cancelled=true;}
        h.assertTrue(cancelled&&checks.get()==4,"A cancelled private scan never returns a partial candidate");
        var missing=new HashMap<>(f.window.cubes());missing.remove(new CubeKey(-1,-5,-1));
        var w=GeneratedNativeWindow.full(9,-16,-80,-16,32,f.window.sky(),f.window.block(),missing);
        boolean refused=false;try{stat(PREFILTER,"scan",w,f.ordered.stream().filter(missing::containsKey).toList(),rules,32,32,32,-16.0,-80.0,-16.0,2_097_152,(Runnable)()->{});}
        catch(IllegalStateException expected){refused=true;}
        h.assertTrue(refused,"Missing adjacent geometry remains UNKNOWN, never an AIR or hidden face");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=2400)
    public static void exactObservationReuseCannotCertifySavedEditsOrBlockTheirRestoration(GameTestHelper h)throws Exception {
        String name="dev.madeinabyss.forge.worldgen.GeneratedObservationExpectations";type(name);
        var profile=LivingAbyssGameTests.profile(h);var a=new CubeKey(17,-60,32);var b=new CubeKey(18,-60,32);
        int[] edited=new int[4096];Arrays.fill(edited,Block.getId(Blocks.GLOWSTONE.defaultBlockState()));
        var saved=Map.of(b,edited);var source=new GeneratedSkySource(profile,saved);var oracleA=profile.generator().generateCube(a).states();
        source.captureDerivedCube(a,()->{});long hits=source.exactTileHits();var calls=new AtomicInteger();
        Supplier<int[]> generator=()->{calls.incrementAndGet();return profile.generator().generateCube(a).states();};
        var actual=(int[])stat(name,"expected",a,saved,Map.of(),source,generator,(Runnable)()->{});
        h.assertTrue(Arrays.equals(oracleA,actual)&&calls.get()==0&&source.exactTileHits()>hits,"An existing exact procedural tile eliminates duplicate generation");
        var restored=profile.generator().generateCube(b).states();h.assertTrue(!Arrays.equals(edited,restored),"Fixture is a real saved edit");
        var queue=new GeneratedObservationQueue(16,32,16);queue.baseline(saved);queue.observe(Map.of(b,restored));
        var compared=GeneratedObservationQueue.compare(queue.takeBatch(),key->(int[])stat(name,"expected",key,saved,Map.of(b,edited),source,(Supplier<int[]>)()->{calls.incrementAndGet();return profile.generator().generateCube(key).states();},(Runnable)()->{}));
        var commit=queue.commit(compared);
        h.assertTrue(calls.get()==1&&commit.geometryChanged()&&queue.retained().isEmpty(),"Saved-source and published edited density cannot be their own procedural oracle; restoration removes the override");h.succeed();
    }
    private static Fixture fixture() {
        var bounds=new VoxelSkyLight.Bounds(-64,-128,-64,96,96,96);int cells=96*96*96;var lo=new byte[cells];Arrays.fill(lo,(byte)15);
        var sky=VoxelSkyLight.restore(bounds,lo,lo,2_000_000);var zero=new byte[cells];var block=VoxelSkyLight.restore(bounds,zero,zero,2_000_000);
        var cubes=new HashMap<CubeKey,int[]>();int stone=Block.getId(Blocks.STONE.defaultBlockState());
        int[] palette={Block.getId(Blocks.AIR.defaultBlockState()),Block.getId(Blocks.CAVE_AIR.defaultBlockState()),Block.getId(Blocks.GLASS.defaultBlockState()),
                Block.getId(Blocks.OAK_LEAVES.defaultBlockState()),Block.getId(Blocks.WATER.defaultBlockState()),Block.getId(Blocks.FERN.defaultBlockState()),
                Block.getId(Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true))};
        for(int cy=-8;cy<-2;cy++)for(int cz=-4;cz<2;cz++)for(int cx=-4;cx<2;cx++){
            int[] states=new int[4096];Arrays.fill(states,stone);
            for(int i=0;i<4096;i++)if((i>>>8)==7&&(i&7)==0)states[i]=palette[Math.floorMod(cx*7+cy*3+cz*11+(i>>>4),palette.length)];
            cubes.put(new CubeKey(cx,cy,cz),states);
        }
        var w=GeneratedNativeWindow.full(9,-16,-80,-16,32,sky,block,cubes);var ordered=new ArrayList<CubeKey>();
        for(var key:cubes.keySet())if(w.intersectsDraw(key))ordered.add(key);
        ordered.sort(Comparator.comparingDouble(k->{double x=k.x()*16.0+24,y=k.y()*16.0+88,z=k.z()*16.0+24;return x*x+y*y+z*z;}));return new Fixture(w,ordered);
    }
    /** Independent old owner loop; no new rule, palette or neighborhood helper is used. */
    public static List<Map<String,Object>> legacy(GeneratedNativeWindow w,List<CubeKey> ordered,int r,int down,int up,double x,double y,double z) {
        var out=new ArrayList<Map<String,Object>>();int air=Block.getId(Blocks.AIR.defaultBlockState());
        for(var key:ordered){int outside=0;long visits=0;var values=new ArrayList<Integer>();
            double x0=key.x()*16.0+.5,x1=x0+15,z0=key.z()*16.0+.5,z1=z0+15,dx=x<x0?x0-x:x>x1?x-x1:0,dz=z<z0?z0-z:z>z1?z-z1:0;
            boolean skipped=dx*dx+dz*dz>(r+8.0)*(r+8.0)||key.y()*16.0+16<y-down-2||key.y()*16.0>y+up+2;
            if(!skipped)for(int i=0;i<4096;i++) {int id=w.cubes().get(key)[i];if(id==air)continue;visits++;
                int bx=key.x()*16+(i&15),by=key.y()*16+(i>>>8),bz=key.z()*16+((i>>>4)&15);
                if(!w.allowsBlock(bx,by,bz,x,y,z,down,up)){outside++;continue;}
                var state=Block.BLOCK_STATE_REGISTRY.byId(id);if(state==null)throw new IllegalStateException("Unknown state");
                boolean fluid=!state.getFluidState().isEmpty(),model=IslandSurfaceBlockStates.admitsNativeModel(state,true);int mask=0;
                if(model)for(var d:Direction.values()){int n=w.neighborState((long)bx+d.getStepX(),(long)by+d.getStepY(),(long)bz+d.getStepZ());var neighbor=Block.BLOCK_STATE_REGISTRY.byId(n);
                    if(n==air||!state.skipRendering(neighbor,d)&&!neighbor.canOcclude())mask|=1<<d.ordinal();}
                if(fluid||mask!=0)values.add(i<<8|(fluid?64:0)|mask);
            }
            out.add(Map.of("outside",outside,"skipped",skipped,"entries",values.stream().mapToInt(Integer::intValue).toArray(),"visits",visits));
        }return out;
    }
    private static Class<?> type(String name){try{return Class.forName(name);}catch(ClassNotFoundException missing){throw new AssertionError("Missing exact native preparation behavior: "+name,missing);}}
    private static Object stat(String name,String method,Object...args){return invoke(type(name),null,method,args);}
    private static Object call(Object target,String method,Object...args){return invoke(target.getClass(),target,method,args);}
    private static Object invoke(Class<?> type,Object target,String name,Object[] args){
        try{for(var m:type.getMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length)return m.invoke(target,args);throw new AssertionError("Missing behavior "+name);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
}
