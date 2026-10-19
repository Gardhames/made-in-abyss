package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeResidencyService;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.CubeStore;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Opt-in cost measurement of the actual world recipe. Registered only when a manifest is
 * supplied, so the ordinary required GameTest set and its batch layout stay unchanged.
 */
@PrefixGameTestTemplate(false)
public final class GeneratorCostGameTests {
    @Mod.EventBusSubscriber(modid=MadeInAbyssProbe.MOD_ID,bus=Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void register(RegisterGameTestsEvent event){
            if(!System.getProperty("mia.bench.generatorManifest","").isBlank())event.register(GeneratorCostGameTests.class);
        }
    }
    // Own batch: the synchronous measurement must not starve or be starved by timing tests.
    @GameTest(templateNamespace=MadeInAbyssProbe.MOD_ID,template="empty",timeoutTicks=20,batch="miaGeneratorCost")
    public static void measureActualWorldGeneratorCost(GameTestHelper helper) {
        String manifestPath=System.getProperty("mia.bench.generatorManifest","");
        try{
            var manifest=JsonParser.parseString(Files.readString(Path.of(manifestPath),StandardCharsets.UTF_8)).getAsJsonObject();
            var biomes=new FixedBiomeSource(helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
            var out=new JsonObject();out.addProperty("profileId",manifest.get("profileId").getAsString());
            long built=System.nanoTime();
            var profile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),biomes);
            out.addProperty("profileBuildMillis",(System.nanoTime()-built)/1e6);out.addProperty("fingerprint",profile.fingerprint());
            var generator=profile.generator();var feet=profile.feet();
            var random=new Random(20261013L);
            // JIT warm-up on cubes that are not measured later.
            var warm=randomCubes(generator,random,60);for(var key:warm)generator.generateCube(key);
            var anchor=new ArrayList<CubeKey>();var center=CubeKey.fromBlock(feet);
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)anchor.add(new CubeKey(center.x()+dx,center.y()+dy,center.z()+dz));
            var column=new ArrayList<CubeKey>();for(int cy=Math.floorDiv(319,16);cy*16>=generator.minimumKnownY();cy-=3){var key=new CubeKey(center.x(),cy,center.z());if(generator.supportsCube(key))column.add(key);}
            var shaft=new ArrayList<CubeKey>();for(int cy=Math.floorDiv(319,16);cy*16>=generator.minimumKnownY();cy-=6){var key=new CubeKey(0,cy,0);if(generator.supportsCube(key))shaft.add(key);}
            var island=new ArrayList<CubeKey>();for(int i=0;i<24;i++){double a=i*Math.PI/12;int x=(int)(Math.cos(a)*750),z=(int)(Math.sin(a)*750);
                int top=generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,helper.getLevel(),null);island.add(CubeKey.fromBlock(new BlockCoordinate(x,top-1,z)));}
            var sets=new JsonObject();
            sets.add("anchorLedge3x3x3",time(anchor,generator::generateCube));
            sets.add("wallColumnAtAnchorXZ",time(column,generator::generateCube));
            sets.add("shaftCenterAir",time(shaft,generator::generateCube));
            sets.add("islandSurfaceRing750",time(island,generator::generateCube));
            sets.add("randomKnownFull",time(randomCubes(generator,random,150),generator::generateCube));
            // Layer prefixes on independent random sets of the same distribution.
            var caves=profile.caveExpansion();var natural=profile.natural();
            if(natural!=null)sets.add("randomPrefixNaturalSample",time(randomCubes(generator,random,150),key->voxels(key,natural::sample)));
            if(caves!=null)sets.add("randomPrefixCaveSample",time(randomCubes(generator,random,150),key->voxels(key,caves::sample)));
            sets.add("randomFullSampleBlockNoArray",time(randomCubes(generator,random,150),key->voxels(key,generator::sampleBlock)));
            // Decorated ledge breakdown: three parallel lanes along the flight path, one per stage.
            var full=lane(generator,center,8);var cave=lane(generator,center,12);var nat=lane(generator,center,-8);
            sets.add("ledgeLaneFull",time(full,generator::generateCube));
            if(caves!=null)sets.add("ledgeLaneCavePrefix",time(cave,key->voxels(key,caves::sample)));
            if(natural!=null)sets.add("ledgeLaneNaturalPrefix",time(nat,key->voxels(key,natural::sample)));
            out.add("cubeMillis",sets);
            out.add("profiledLedgeLanes",profileLedges(generator,center));
            // Native column surface query as used by structures, spawning and other mods.
            var heights=new ArrayList<Double>();for(int i=0;i<64;i++){int x=random.nextInt(2400)-1200,z=random.nextInt(2400)-1200;long t=System.nanoTime();
                generator.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,helper.getLevel(),null);heights.add((System.nanoTime()-t)/1e6);}
            out.add("getBaseHeightMillis",summary(heights));
            out.add("fallResidency",fall(generator,feet,1));
            out.add("flightResidency",flight(generator,feet,1));
            // Real-time tick simulation: owner-thread work per 50 ms tick with background preparation.
            int bottom=generator.knownLowerY(feet.x(),feet.z())+48;
            var fallPath=new BlockCoordinate[]{new BlockCoordinate(feet.x(),Math.min(300,feet.y()+600),feet.z()),new BlockCoordinate(feet.x(),bottom,feet.z())};
            var flightPath=new BlockCoordinate[]{feet,new BlockCoordinate(feet.x()+640,feet.y(),feet.z())};
            out.add("tickFallSyncMemory",route(generator,fallPath,3.9,new VersionedMemory(),false));
            out.add("tickFallAsyncMemory",route(generator,fallPath,3.9,new VersionedMemory(),true));
            out.add("tickFlightSyncMemory",route(generator,flightPath,1.0,new VersionedMemory(),false));
            out.add("tickFlightAsyncMemory",route(generator,flightPath,1.0,new VersionedMemory(),true));
            var disk=Files.createTempDirectory(Path.of("").toAbsolutePath(),"mia-bench-store-");
            out.add("tickFlightAsyncStableStore",route(generator,flightPath,1.0,new dev.madeinabyss.forge.cube.StableCubeStore(disk,"madeinabyss:bench_generator",1),true));
            // Separate empty directory: both routes measure first visits, never warm saved cubes.
            var asyncDisk=Files.createTempDirectory(Path.of("").toAbsolutePath(),"mia-bench-async-store-");
            out.add("tickFlightAsyncStableStoreAsyncSaving",route(generator,flightPath,1.0,
                    new dev.madeinabyss.forge.cube.StableCubeStore(asyncDisk,"madeinabyss:bench_generator",1),true,true));
            out.addProperty("availableProcessors",Runtime.getRuntime().availableProcessors());
            String text=out.toString();System.out.println("MIA_GENERATOR_COST "+text);
            String output=System.getProperty("mia.bench.generatorOutput","");
            if(!output.isBlank()){var path=Path.of(output);Files.createDirectories(path.getParent());Files.writeString(path,text,StandardCharsets.UTF_8);}
            helper.succeed();
        }catch(Exception failure){throw new IllegalStateException("Generator cost measurement failed: "+failure,failure);}
    }
    private static void voxels(CubeKey key,Sampler sampler){int x0=key.x()*16,y0=key.y()*16,z0=key.z()*16;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)sampler.sample(x0+x,y0+y,z0+z);}
    @FunctionalInterface private interface Sampler{Object sample(int x,int y,int z);}
    /** Distinct stack frame for JFR attribution of decorated ledge generation; fresh lanes avoid memo reuse. */
    private static JsonObject profileLedges(ParameterizedTerrainChunkGenerator generator,CubeKey center){
        var keys=new ArrayList<CubeKey>();for(int dz=16;dz<64;dz+=4)keys.addAll(lane(generator,center,dz));
        return time(keys,generator::generateCube);
    }
    private static List<CubeKey> lane(ParameterizedTerrainChunkGenerator generator,CubeKey center,int dz){
        var result=new ArrayList<CubeKey>();for(int i=0;i<40;i++)for(int dy=-1;dy<=1;dy++){var key=new CubeKey(center.x()+i,center.y()+dy,center.z()+dz);if(generator.supportsCube(key))result.add(key);}
        return result;
    }
    private static List<CubeKey> randomCubes(ParameterizedTerrainChunkGenerator generator,Random random,int count){
        var result=new ArrayList<CubeKey>();int minCy=Math.floorDiv(generator.minimumKnownY(),16)+1,maxCy=Math.floorDiv(319,16);
        while(result.size()<count){var key=new CubeKey(random.nextInt(160)-80,minCy+random.nextInt(maxCy-minCy+1),random.nextInt(160)-80);if(generator.supportsCube(key))result.add(key);}
        return result;
    }
    private static JsonObject time(List<CubeKey> keys,Consumer<CubeKey> action){
        var values=new ArrayList<Double>();for(var key:keys){long t=System.nanoTime();action.accept(key);values.add((System.nanoTime()-t)/1e6);}
        return summary(values);
    }
    private static JsonObject summary(List<Double> values){
        var sorted=new ArrayList<>(values);Collections.sort(sorted);double sum=0;for(double v:sorted)sum+=v;
        var o=new JsonObject();o.addProperty("count",sorted.size());o.addProperty("mean",sorted.isEmpty()?0:sum/sorted.size());
        o.addProperty("p50",at(sorted,.5));o.addProperty("p95",at(sorted,.95));o.addProperty("max",sorted.isEmpty()?0:sorted.get(sorted.size()-1));o.addProperty("total",sum);return o;
    }
    private static double at(List<Double> sorted,double q){return sorted.isEmpty()?0:sorted.get(Math.min(sorted.size()-1,(int)Math.floor(q*(sorted.size()-1))));}
    /** First-visit descent: an empty store means every new cube is generated on the owner thread. */
    private static JsonObject fall(ParameterizedTerrainChunkGenerator generator,BlockCoordinate feet,int radius) throws java.io.IOException{
        var service=new CubeResidencyService(new MemoryStore(),generator::generateCube,27,108,4);
        var values=new ArrayList<Double>();long cubes=0;
        for(int y=Math.min(300,feet.y()+600);y-16>=generator.knownLowerY(feet.x(),feet.z())+48;y-=16){
            var at=new BlockCoordinate(feet.x(),y,feet.z());long t=System.nanoTime();var delta=service.move("bench",at,radius,radius);values.add((System.nanoTime()-t)/1e6);cubes+=delta.added().size();}
        var o=summary(values);o.addProperty("generatedCubes",cubes);o.addProperty("note","one move per 16 m step; vanilla terminal fall ~78 m/s = ~4.9 moves/s");return o;
    }
    private static JsonObject flight(ParameterizedTerrainChunkGenerator generator,BlockCoordinate feet,int radius) throws java.io.IOException{
        var service=new CubeResidencyService(new MemoryStore(),generator::generateCube,27,108,4);
        var values=new ArrayList<Double>();long cubes=0;
        for(int i=0;i<40;i++){var at=new BlockCoordinate(feet.x()+i*16,feet.y(),feet.z());long t=System.nanoTime();var delta=service.move("bench",at,radius,radius);values.add((System.nanoTime()-t)/1e6);cubes+=delta.added().size();}
        var o=summary(values);o.addProperty("generatedCubes",cubes);return o;
    }
    /**
     * Moves a body along a straight path at blocks/tick, one real 50 ms tick at a time. A tick
     * whose swept halo needs an unloaded cube holds the body in place, as allowsMove would.
     */
    private static JsonObject route(ParameterizedTerrainChunkGenerator generator,BlockCoordinate[] path,double speed,
                                    dev.madeinabyss.kernel.cube.VersionedCubeStore store,boolean async) throws java.io.IOException,InterruptedException{
        return route(generator,path,speed,store,async,false);
    }
    private static JsonObject route(ParameterizedTerrainChunkGenerator generator,BlockCoordinate[] path,double speed,
                                    dev.madeinabyss.kernel.cube.VersionedCubeStore store,boolean async,boolean asyncSaving) throws java.io.IOException,InterruptedException{
        var service=new CubeResidencyService(store,generator::generateCube,27,108,4);
        if(asyncSaving)service.enableAsyncSaving(CubeResidencyService.DEFAULT_MAX_PINNED_CUBES,"mia-bench-writer");
        dev.madeinabyss.kernel.cube.AsyncCubeLoader loader=null;
        if(async){loader=new dev.madeinabyss.kernel.cube.AsyncCubeLoader(store,generator::generateCube,2,1024,"mia-bench-loader");service.attachLoader(loader);}
        try{
            double x=path[0].x()+.5,y=path[0].y(),z=path[0].z()+.5;
            double dx=path[1].x()-path[0].x(),dy=path[1].y()-path[0].y(),dz=path[1].z()-path[0].z();double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
            double vx=dx/length*speed,vy=dy/length*speed,vz=dz/length*speed;
            service.move("bench",new BlockCoordinate((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z)),1,1); // admission, untimed
            long admissionLoads=service.loadMetrics().ownerThreadLoads();
            var values=new ArrayList<Double>();int held=0,ticks=0;double travelled=0;
            while(travelled<length&&ticks<2400){
                long started=System.nanoTime();
                var at=new BlockCoordinate((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
                if(async)for(int i=0;i<4;i++){int t=new int[]{0,10,20,40}[i];
                    var c=CubeKey.fromBlock(new BlockCoordinate((int)Math.floor(x+vx*t),(int)Math.floor(y+vy*t),(int)Math.floor(z+vz*t)));
                    var keys=new ArrayList<CubeKey>();for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++)for(int d=-1;d<=1;d++){var k=new CubeKey(c.x()+a,c.y()+b,c.z()+d);if(generator.supportsCube(k))keys.add(k);}
                    service.prefetch(keys,i);}
                if(async)service.tryMove("bench",at,1,1);else service.move("bench",at,1,1);
                values.add((System.nanoTime()-started)/1e6);ticks++;
                // Advance only if the next swept position plus an 8-block halo is resident.
                double nx=x+vx,ny=y+vy,nz=z+vz;boolean ok=true;
                for(double ox:new double[]{-8.3,8.3})for(double oy:new double[]{-8,9.8})for(double oz:new double[]{-8.3,8.3}){
                    var k=CubeKey.fromBlock(new BlockCoordinate((int)Math.floor(nx+ox),(int)Math.floor(ny+oy),(int)Math.floor(nz+oz)));
                    if(generator.supportsCube(k)&&!service.space().isLoaded(k))ok=false;}
                if(ok){x=nx;y=ny;z=nz;travelled+=speed;}else held++;
                long spent=(System.nanoTime()-started)/1_000_000;if(spent<50)Thread.sleep(50-spent);
            }
            var o=summary(values);o.addProperty("ticks",ticks);o.addProperty("heldTicks",held);o.addProperty("over50ms",values.stream().filter(v->v>50).count());
            o.addProperty("over5ms",values.stream().filter(v->v>5).count());
            var m=service.loadMetrics();o.addProperty("preparedLoads",m.preparedLoads());o.addProperty("ownerThreadLoads",m.ownerThreadLoads());o.addProperty("deferredMoves",m.deferredMoves());
            o.addProperty("ownerThreadLoadsAfterAdmission",m.ownerThreadLoads()-admissionLoads);
            o.addProperty("asyncSaving",asyncSaving);o.addProperty("travelled",travelled);o.addProperty("routeLength",length);
            o.addProperty("ownerThreadWritesDuringRoute",service.saveMetrics().ownerThreadWrites());
            long drainStarted=System.nanoTime();service.flush();o.addProperty("flushMillis",(System.nanoTime()-drainStarted)/1e6);
            var saves=service.saveMetrics();
            o.addProperty("saveCompleted",saves.completed());o.addProperty("saveFailures",saves.failures());
            o.addProperty("saveRetries",saves.retries());o.addProperty("saveStaleSnapshots",saves.staleSnapshots());
            o.addProperty("saveBackpressureWrites",saves.backpressureWrites());o.addProperty("savePeakPinnedCubes",saves.peakPinnedCubes());
            o.addProperty("saveBackpressureEvents",saves.backpressureEvents());
            o.addProperty("savePendingAfterFlush",saves.pendingWrites());o.addProperty("savePinnedAfterFlush",saves.pinnedCubes());
            if(loader!=null){var lm=loader.metrics();o.addProperty("loaderGenerated",lm.generated());o.addProperty("loaderStale",lm.stale());o.addProperty("loaderFailures",lm.failures());o.addProperty("loaderLastFailure",lm.lastFailure());}
            return o;
        }finally{try{service.close();}finally{if(loader!=null)loader.close();}}
    }
    private static final class VersionedMemory implements dev.madeinabyss.kernel.cube.VersionedCubeStore {
        private final Map<CubeKey,CubeSnapshot> data=new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<CubeKey,java.util.concurrent.atomic.AtomicLong> versions=new java.util.concurrent.ConcurrentHashMap<>();
        @Override public Optional<CubeSnapshot> read(CubeKey key){return Optional.ofNullable(data.get(key));}
        @Override public void write(CubeSnapshot snapshot){var v=versions.computeIfAbsent(snapshot.key(),k->new java.util.concurrent.atomic.AtomicLong());v.incrementAndGet();try{data.put(snapshot.key(),snapshot);}finally{v.incrementAndGet();}}
        @Override public long version(CubeKey key){var v=versions.get(key);return v==null?0:v.get();}
    }
    private static final class MemoryStore implements CubeStore {
        private final Map<CubeKey,CubeSnapshot> data=new HashMap<>();
        @Override public Optional<CubeSnapshot> read(CubeKey key){return Optional.ofNullable(data.get(key));}
        @Override public void write(CubeSnapshot snapshot){data.put(snapshot.key(),snapshot);}
    }
}
