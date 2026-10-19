package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.SkyVisibility;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.*;

/** Real state/light consumers; scalar, saved and signed-boundary oracles are independent. */
@GameTestHolder("near_scope")
@PrefixGameTestTemplate(false)
public final class T7GeneratorCostGameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void leadingAirExposureUsesCubeCheckpointsRatherThan1700VoxelSamples(GameTestHelper h)throws Exception {
        var p=LivingAbyssGameTests.profile(h);var s=new GeneratedSkySource(p,Map.of());var calls=new AtomicInteger();
        try(var ignored=s.requestCheckpoint(calls::incrementAndGet)){s.columnExposure(-1,-1);}
        h.assertTrue(calls.get()<650,"Certified shaft AIR must avoid repeated voxel work; checkpoints="+calls.get());
        var raw=new GeneratedSkySource(p,Map.of(),true);
        h.assertTrue(s.rainHeight(-1,-1)==raw.rainHeight(-1,-1),"Skipping literal AIR preserves first non-AIR rain height");
        for(int y=s.knownLowerY(-1,-1);y<s.maxY();y++)h.assertTrue(s.directSkyFast(-1,y,-1)==raw.directSkyFast(-1,y,-1),"Every direct-SKY level remains exact atY="+y);
        System.out.println("MIA_T7_COLUMN_CHECKPOINTS "+calls.get());h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void signedSavedGlassWaterLeavesAndAirKeepAllColumnStatesAndSky(GameTestHelper h)throws Exception {
        var p=LivingAbyssGameTests.profile(h);var key=new CubeKey(-1,-40,-1);int air=Block.getId(Blocks.AIR.defaultBlockState());
        var saved=new int[4096];Arrays.fill(saved,air);
        saved[(15<<8)|(15<<4)|15]=Block.getId(Blocks.GLASS.defaultBlockState());
        saved[(12<<8)|(15<<4)|15]=Block.getId(Blocks.WATER.defaultBlockState());
        saved[(10<<8)|(14<<4)|14]=Block.getId(Blocks.OAK_LEAVES.defaultBlockState());
        var source=new GeneratedSkySource(p,Map.of(key,saved));var nativeSource=new GeneratedSkySource(p,Map.of(key,saved));var raw=new GeneratedSkySource(p,Map.of(key,saved),true);
        int compared=0;
        for(int[] c:new int[][]{{-1,-1},{-2,-2},{0,0},{-17,-33},{1120,0},{-1121,-1}}){
            int x=c[0],z=c[1],lo=raw.knownLowerY(x,z),light=15,rain=lo;
            var nativeColumn=nativeSource.nativeColumnExposure(x,z);
            for(int y=raw.maxY()-1;y>=lo;y--){
                int expected=raw.stateIdUncached(x,y,z),o=raw.opacity(expected);
                if(rain==lo&&expected!=air)rain=y+1;
                if(!(light==15&&o==0))light=Math.max(0,light-Math.max(1,o));
                h.assertTrue(source.stateId(x,y,z)==expected,"Full-state column preserves saved/procedural state at "+x+","+y+","+z);
                h.assertTrue(source.directSkyFast(x,y,z)==light&&nativeColumn.directSky(y)==light,"Full and native exposure preserve exact attenuation at "+x+","+y+","+z);compared++;
            }
            h.assertTrue(source.rainHeight(x,z)==rain&&nativeColumn.rainHeight()==rain,"Saved zero-opacity GLASS still blocks rain before WATER/LEAVES");
        }
        h.assertTrue(source.rainHeight(-1,-1)==-624,"Literal-AIR certificate cannot hide a saved glass roof");
        System.out.println("MIA_T7_EXACT_COLUMNS states="+compared);h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void cubeAirApiUsesSignedCubeCoordinatesAndRefusesUnknown(GameTestHelper h)throws Exception {
        var p=LivingAbyssGameTests.profile(h);var g=p.generator();var living=g.livingAbyss();
        var api=method(h,g.getClass(),"uniformAirCube",int.class,int.class,int.class);
        var direct=method(h,living.getClass(),"uniformAirCube",int.class,int.class,int.class);
        for(var k:List.of(new CubeKey(-1,-30,-1),new CubeKey(0,-30,0),new CubeKey(70,19,0))){
            h.assertTrue((boolean)api.invoke(g,k.x(),k.y(),k.z())&&(boolean)direct.invoke(living,k.x(),k.y(),k.z()),"Public cube proof agrees with actual certificate at "+k);
        }
        for(var k:List.of(new CubeKey(-1,-90,-1),new CubeKey(Integer.MAX_VALUE,0,0),new CubeKey(0,Integer.MIN_VALUE,0)))
            h.assertTrue(!(boolean)api.invoke(g,k.x(),k.y(),k.z()),"Unproved/UNKNOWN/out-of-domain cubes refuse "+k);
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void preparedPredicatesAndFrozenFeaturesMatchScalarInEveryTraversalOrder(GameTestHelper h)throws Exception {
        var p=LivingAbyssGameTests.profile(h);var g=p.generator();var living=g.livingAbyss();
        var base=living.getClass().getDeclaredMethod("baseBlock",int.class,int.class,int.class,boolean.class);base.setAccessible(true);
        var rocks=living.getClass().getDeclaredField("rocks");rocks.setAccessible(true);
        for(Object value:((Map<?,?>)rocks.get(living)).values()){
            var state=(BlockState)value;
            h.assertTrue(state.isSolid()&&!state.isAir()&&sturdy(state),"Every alternate rock preserves STONE's branch, air and sturdy-up predicates: "+state);
        }
        var keys=new LinkedHashSet<CubeKey>(List.of(new CubeKey(42,-48,6),new CubeKey(-33,-48,6),new CubeKey(-1,-89,-1),new CubeKey(59,-20,-1),new CubeKey(-61,-80,-1)));
        for(var plan:List.of(living.flora().features(),living.water().voxels())){
            var sorted=new ArrayList<>(plan.cubeKeys());sorted.sort(Comparator.comparingInt(CubeKey::x).thenComparingInt(CubeKey::y).thenComparingInt(CubeKey::z));
            for(int i=0;i<Math.min(12,sorted.size());i++)keys.add(sorted.get(i*sorted.size()/Math.min(12,sorted.size())));
        }
        for(var q:living.plan().ledges().subList(0,Math.min(12,living.plan().ledges().size())))keys.add(new CubeKey(q.x()>>4,q.y()>>4,q.z()>>4));
        var rows=new JsonArray();long compared=0;
        for(var k:keys){if(!g.supportsCube(k))continue;int sx=k.x()*16,sy=k.y()*16,sz=k.z()*16;var expected=new int[4096];
            living.finishCube();
            for(int i=0;i<4096;i++){
                int x=sx+(i&15),y=sy+(i>>8),z=sz+((i>>4)&15);expected[i]=Block.getId(g.sampleBlock(x,y,z));
                if((i&31)==0){var a=(BlockState)base.invoke(living,x,y,z,false);var b=(BlockState)base.invoke(living,x,y,z,true);
                    h.assertTrue(a.isAir()==b.isAir()&&sturdy(a)==sturdy(b),"Material selection must preserve both cached predicates at "+x+","+y+","+z);}
            }
            h.assertTrue(Arrays.equals(expected,g.generateCube(k).states()),"Actual generateCube matches independent scalar before/after memo at "+k);
            for(int order=0;order<2;order++)try{
                living.prepareCube(sx,sy,sz);
                for(int n=0;n<4096;n++){int i=order==0?4095-n:(n*2053)&4095;
                    h.assertTrue(expected[i]==Block.getId(g.sampleBlock(sx+(i&15),sy+(i>>8),sz+((i>>4)&15))),"Reverse/shuffled support history cannot change a final state at "+k+":"+i);compared++;}
            }finally{living.finishCube();}
            var digest=java.security.MessageDigest.getInstance("SHA-256");var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest));
            for(int id:expected)out.writeUTF(Block.stateById(id).toString());out.flush();
            var row=new JsonObject();row.addProperty("cube",k.toString());row.addProperty("sha256",HexFormat.of().formatHex(digest.digest()));rows.add(row);
        }
        var file=Path.of(System.getProperty("mia.storageRoot", ".local") + "/codex/20261015-perf-t7/validation/scalar-fixtures-before.json");
        if(Files.exists(file))h.assertTrue(JsonParser.parseString(Files.readString(file)).equals(rows),"Scalar output must match captured pre-T7 canonical state hashes");else Files.writeString(file,rows.toString());
        System.out.println("MIA_T7_PREPARED cubes="+rows.size()+" traversalComparisons="+compared);h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void skippedCubesStillCancelRetireAndRefuseUnknownBeforePublication(GameTestHelper h)throws Exception {
        var p=LivingAbyssGameTests.profile(h);
        for(boolean full:new boolean[]{false,true}){
            var s=new GeneratedSkySource(p,Map.of());var calls=new AtomicInteger();boolean cancelled=false;
            try(var ignored=s.requestCheckpoint(()->{if(calls.incrementAndGet()==4)throw new CancellationException("superseded column");})){
                if(full)s.stateId(-1,-900,-1);else s.columnExposure(-1,-1);
            }catch(CancellationException expected){cancelled=true;}
            h.assertTrue(cancelled&&s.cachedColumnExposure(-1,-1).isEmpty(),"Neither cancelled path publishes incomplete global exposure");
            h.assertTrue(s.stateId(-1,-900,-1)==Block.getId(p.generator().sampleBlock(-1,-900,-1)),"Fresh request recovers after cancelled cube scan");
        }
        var s=new GeneratedSkySource(p,Map.of());boolean unknown=false;
        try{s.stateId(-1,s.knownLowerY(-1,-1)-1,-1);}catch(TerrainUnavailableException expected){unknown=true;}
        h.assertTrue(unknown,"AIR proof cannot replace UNKNOWN below the spatial domain");
        var tagHolder=Blocks.STONE.builtInRegistryHolder();var tags=tagHolder.tags().toList();var ray=s.nativeRayOpacity();
        try{
            var changed=new HashSet<>(tags);if(!changed.remove(net.minecraft.tags.BlockTags.LOGS))changed.add(net.minecraft.tags.BlockTags.LOGS);tagHolder.bindTags(changed);
            try{s.verifyPhysicalSemantics();}catch(IllegalStateException expected){}
            boolean refused=false;try{s.columnExposure(-1,-1);}catch(CancellationException expected){refused=true;}
            h.assertTrue(refused,"Cached/proved columns retain physical authority retirement");
            refused=false;try{SkyVisibility.visibleFraction(-1,-900,-1,s.maxY(),100000,ray);}catch(CancellationException expected){refused=true;}
            h.assertTrue(refused,"Certified ray cubes retain physical authority retirement");
        }finally{tagHolder.bindTags(tags);}
        h.succeed();
    }
    private static boolean sturdy(BlockState s){return s.isFaceSturdy(EmptyBlockGetter.INSTANCE,BlockPos.ZERO,Direction.UP);}
    private static Method method(GameTestHelper h,Class<?> c,String name,Class<?>... args)throws Exception{
        try{return c.getMethod(name,args);}catch(NoSuchMethodException missing){h.fail("Missing exact cube proof API: "+c.getSimpleName()+"."+name);throw missing;}
    }
}
