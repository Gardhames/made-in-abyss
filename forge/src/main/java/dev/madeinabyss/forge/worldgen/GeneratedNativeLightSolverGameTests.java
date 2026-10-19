package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.*;
import dev.madeinabyss.kernel.light.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;

/** Exercises the common solver used by the real native client, without loading client/OpenGL. */
@GameTestHolder("near_stage1")
@PrefixGameTestTemplate(false)
public final class GeneratedNativeLightSolverGameTests {
    @GameTest(template="empty",batch="nearSharedSolver",timeoutTicks=2400)
    public static void actualSharedSolverRetainsUnpreparedSkyBlockAndDrawStencil(GameTestHelper h)throws Exception {
        verifyUnprepared(h,fixture(h));
    }
    @GameTest(template="empty",batch="nearSharedSolver",timeoutTicks=2400)
    public static void actualDenseV15SharedSolverRetainsIndependentSkyBlockAndDrawStencil(GameTestHelper h)throws Exception {
        verifyUnprepared(h,denseV15Fixture(h));
    }
    private static void verifyUnprepared(GameTestHelper h,Fixture f)throws Exception {
        var phases=Executors.newFixedThreadPool(4);
        try {
            var result=run(f.source,f.bounds,phases,()->{},(stage,key)->{});
            var sky=(VoxelSkyLight.Result)get(result,"sky");var block=(VoxelSkyLight.Result)get(result,"block");
            byte[] opacity=new byte[512000],seed=new byte[512000],emission=new byte[512000];int sx=80,plane=6400;
            var oracle=f.oracle;
            for(int y=0;y<80;y++)for(int z=0;z<80;z++)for(int x=0;x<80;x++) {
                int id=oracle.stateIdUncached(f.bounds.minX()+x,f.bounds.minY()+y,f.bounds.minZ()+z),i=x+sx*(z+80*y);
                opacity[i]=(byte)oracle.opacity(id);emission[i]=(byte)oracle.emission(id);
            }
            for(int z=0;z<80;z++)for(int x=0;x<80;x++)DirectSkyColumnSeed.fill(opacity,seed,x+sx*z,plane,80,oracle.columnExposure(f.bounds.minX()+x,f.bounds.minZ()+z).directSky(f.bounds.minY()+79));
            SkyVisibility.VoxelOpacity lookup=(x,y,z)->{
                int xx=x-f.bounds.minX(),yy=y-f.bounds.minY(),zz=z-f.bounds.minZ();
                return xx>=0&&xx<80&&yy>=0&&yy<80&&zz>=0&&zz<80?opacity[xx+80*(zz+80*yy)]:oracle.opacityFast(x,y,z);
            };
            for(int y=1;y<79;y++)for(int z=1;z<79;z++)for(int x=1;x<79;x++) {
                int i=x+80*(z+80*y);if(opacity[i]>=15||seed[i]==15)continue;
                if(opacity[i-1]>=15||opacity[i+1]>=15||opacity[i-80]>=15||opacity[i+80]>=15||opacity[i-plane]>=15||opacity[i+plane]>=15)
                    seed[i]=(byte)Math.max(seed[i],oracle.ambientSky(f.bounds.minX()+x,f.bounds.minY()+y,f.bounds.minZ()+z,lookup,seed[i]));
            }
            var expectedSky=VoxelSkyLight.solveExpanded(f.bounds,opacity,seed,512000);var expectedBlock=VoxelSkyLight.solveBlockExpanded(f.bounds,opacity,emission,512000);
            h.assertTrue(Arrays.equals(expectedSky.lowerSnapshot(),sky.lowerSnapshot())&&Arrays.equals(expectedSky.upperSnapshot(),sky.upperSnapshot()),"Shared SKY lower/upper arrays equal independent unprepared oracle");
            h.assertTrue(Arrays.equals(expectedBlock.lowerSnapshot(),block.lowerSnapshot())&&Arrays.equals(expectedBlock.upperSnapshot(),block.upperSnapshot()),"Shared BLOCK lower/upper arrays equal independent unprepared oracle");
            var cubes=(Map<CubeKey,int[]>)get(result,"cubes");var window=dev.madeinabyss.forge.probe.deep.GeneratedNativeWindow.near(f.plan,7,sky,block,cubes).orElseThrow();
            var d=f.plan.drawBounds();int checked=0;
            for(int y=d.minY()-1;y<=d.minY()+48;y++)for(int z=d.minZ()-1;z<=d.minZ()+48;z++)for(int x=d.minX()-1;x<=d.minX()+48;x++){
                int packed=(expectedBlock.value(x,y,z).orElseThrow()<<4)|(expectedSky.value(x,y,z).orElseThrow()<<20);
                h.assertTrue(window.packed(x,y,z).orElseThrow()==packed,"Exact model/fluid stencil must not change");checked++;
            }
            System.out.println("MIA_NEAR_SHARED_ORACLE pairs="+checked+" cubes="+cubes.size()+" v15="+(f.source.livingRecipe()!=null));h.succeed();
        }finally{phases.shutdownNow();}
    }
    @GameTest(template="empty",batch="nearSharedSolver",timeoutTicks=2400)
    public static void actualSharedCancellationJoinsEveryWriterBeforeReturning(GameTestHelper h)throws Exception {
        var f=fixture(h);var phases=(ThreadPoolExecutor)Executors.newFixedThreadPool(4);var checks=new AtomicInteger();
        try {
            boolean cancelled=false;
            try{run(f.source,f.bounds,phases,()->{if(checks.incrementAndGet()>200)throw new CancellationException("new camera target");},(stage,key)->{});}
            catch(CancellationException expected){cancelled=true;}
            h.assertTrue(cancelled,"An obsolete real density solve must cancel inside its scans");
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(phases.getActiveCount()>0&&System.nanoTime()<end)Thread.yield();
            h.assertTrue(phases.getActiveCount()==0,"Actual writers, not cancelled Futures, have exited");h.succeed();
        }finally{phases.shutdownNow();}
    }
    private record Fixture(GeneratedSkySource source,GeneratedSkySource oracle,VoxelSkyLight.Bounds bounds,NativeFirstViewPlan.Plan plan){}
    private static Fixture fixture(GameTestHelper h)throws Exception{
        var manifest=LivingAbyssGameTests.parentManifest();var profile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        var plan=NativeFirstViewPlan.near(4,-.1,-330,500,-5000,320,7,512000).orElseThrow();var b=plan.solveBounds();
        var saved=new HashMap<CubeKey,int[]>();int air=Block.getId(Blocks.AIR.defaultBlockState());
        for(int y=b.minY();y<b.minY()+80;y+=16)for(int z=b.minZ();z<b.minZ()+80;z+=16)for(int x=b.minX();x<b.minX()+80;x+=16){var states=new int[4096];Arrays.fill(states,air);saved.put(new CubeKey(x/16,y/16,z/16),states);}
        var key=new CubeKey(-1,-21,31);var states=saved.get(key);
        for(int z=4;z<8;z++)for(int x=4;x<8;x++)states[(3<<8)|(z<<4)|x]=Block.getId(Blocks.STONE.defaultBlockState());
        states[(5<<8)|(6<<4)|6]=Block.getId(Blocks.GLOWSTONE.defaultBlockState());states[(5<<8)|(6<<4)|7]=Block.getId(Blocks.WATER.defaultBlockState());states[(6<<8)|(6<<4)|7]=Block.getId(Blocks.OAK_LEAVES.defaultBlockState());
        return new Fixture(new GeneratedSkySource(profile,saved),new GeneratedSkySource(profile,saved,true),b,plan);
    }
    private static Fixture denseV15Fixture(GameTestHelper h)throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var manifest=LivingAbyssGameTests.manifest();
        var independentProfile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        var source=new GeneratedSkySource(profile,Map.of());GeneratedSkySource oracle;
        // Cache only independently sampled unprepared scalar values/probes. Never reuse prepared tiles.
        String previous=System.getProperty("mia.generated.exactTiles");
        try{System.setProperty("mia.generated.exactTiles","false");oracle=new GeneratedSkySource(independentProfile,Map.of());}
        finally{if(previous==null)System.clearProperty("mia.generated.exactTiles");else System.setProperty("mia.generated.exactTiles",previous);}
        var plan=NativeFirstViewPlan.near(4,296.765,-330,-326.207,-1424,320,7,512000).orElseThrow();
        return new Fixture(source,oracle,plan.solveBounds(),plan);
    }
    private static Object run(GeneratedSkySource source,VoxelSkyLight.Bounds bounds,ExecutorService phases,Runnable check,BiConsumer<String,CubeKey> observer){
        try{return Class.forName("dev.madeinabyss.forge.worldgen.GeneratedNativeLightScheduler").getMethod("solve",GeneratedSkySource.class,VoxelSkyLight.Bounds.class,Map.class,ExecutorService.class,int.class,Runnable.class,BiConsumer.class).invoke(null,source,bounds,Map.of(),phases,4,check,observer);}
        catch(InvocationTargetException failed){if(failed.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(failed.getCause());}
        catch(ReflectiveOperationException missing){throw new AssertionError("Shared actual native light solver is not implemented",missing);}
    }
    private static Object get(Object value,String name)throws Exception{return value.getClass().getMethod(name).invoke(value);}
}
