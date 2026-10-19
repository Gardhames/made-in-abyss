package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.NativeLightWindowCache;
import dev.madeinabyss.kernel.light.VoxelSkyLight;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real source metadata and native-file IO, with deterministic retirement/quarantine ordering. */
@GameTestHolder("near_stage1")
@PrefixGameTestTemplate(false)
public final class GeneratedNativeCachePersistenceGameTests {
    private static final String KEY="23".repeat(32);

    // Removing the common fence lets quarantine finish between real probe and native IO.
    @GameTest(template="empty",batch="nearNativePersistence",timeoutTicks=400)
    public static void quarantineWaitsForNativeIoAndCannotRecreateRetiredNamespace(GameTestHelper h)throws Exception {
        var f=fixture(h);var nativeEntered=new CountDownLatch(1);var releaseNative=new CountDownLatch(1);
        var quarantineEntered=new CountDownLatch(1);var quarantineDone=new CountDownLatch(1);
        var failures=new AtomicReference<Throwable>();boolean quarantineOvertookNative=false;
        Thread writer=new Thread(()->{
            try{GeneratedNativeCachePersistence.write(f.source,()->{
                nativeEntered.countDown();await(releaseNative);
                NativeLightWindowCache.write(f.windows,KEY,f.entry);
            });}catch(Throwable failure){failures.compareAndSet(null,failure);}
        },"near-native-cache-writer");
        Thread quarantine=new Thread(()->{
            quarantineEntered.countDown();
            try{f.source.quarantineLightCaches();}
            catch(Throwable failure){failures.compareAndSet(null,failure);}
            finally{quarantineDone.countDown();}
        },"near-native-cache-quarantine");
        try{
            writer.start();h.assertTrue(nativeEntered.await(3,TimeUnit.SECONDS),"Writer reaches actual native IO after source persistence");
            h.assertTrue(hasFile(f.sky),"Actual source metadata was persisted before the native boundary");
            retire(f.source);quarantine.start();
            h.assertTrue(quarantineEntered.await(3,TimeUnit.SECONDS),"Retired source's quarantine actually starts");
            quarantineOvertookNative=quarantineBeforeRelease(quarantine,quarantineDone);
        }finally{
            releaseNative.countDown();writer.join(3000);quarantine.join(3000);
        }
        h.assertTrue(!writer.isAlive()&&!quarantine.isAlive()&&failures.get()==null,"Actual IO workers exit without errors: "+failures.get());
        h.assertTrue(!quarantineOvertookNative,"Quarantine must wait for the admitted native write, not only source.persistLightCache");
        h.assertTrue(!Files.exists(f.sky),"The retired namespace cannot be recreated after quarantine");
        Path preserved=quarantined(f.root);
        var read=NativeLightWindowCache.read(preserved.resolve("native-windows"),KEY,f.entry.bounds(),4096,Block.BLOCK_STATE_REGISTRY.size());
        h.assertTrue(read.entry().isPresent(),"Quarantine preserves the completed actual native file after the writer exits: "+read.status());
        h.succeed();
    }

    // A revoked source cannot invoke native IO, even after the public quarantine has returned.
    @GameTest(template="empty",batch="nearNativePersistence",timeoutTicks=400)
    public static void alreadyRetiredSourceCannotWriteIntoQuarantinedNamespace(GameTestHelper h)throws Exception {
        var f=fixture(h);f.source.persistLightCache();h.assertTrue(hasFile(f.sky),"Real metadata exists before retirement");
        retire(f.source);f.source.quarantineLightCaches();var calls=new AtomicInteger();boolean refused=false;
        try{GeneratedNativeCachePersistence.write(f.source,()->{calls.incrementAndGet();NativeLightWindowCache.write(f.windows,KEY,f.entry);});}
        catch(CancellationException expected){refused=true;}
        h.assertTrue(refused&&calls.get()==0,"Retired physical authority must refuse before native file IO");
        h.assertTrue(!Files.exists(f.sky)&&hasFile(quarantined(f.root)),"Old cache stays quarantined, with metadata preserved");
        h.succeed();
    }

    // Swallowing an IOException or retaining the source monitor would hide failed persistence.
    @GameTest(template="empty",batch="nearNativePersistence",timeoutTicks=400)
    public static void actualNativeIoFailurePropagatesAndReleasesQuarantineFence(GameTestHelper h)throws Exception {
        var f=fixture(h);Path blocker=f.root.resolve("native-directory-is-a-file");Files.writeString(blocker,"owned IO failure fixture");
        var actualFailure=new AtomicReference<IOException>();IOException observed=null;
        try{GeneratedNativeCachePersistence.write(f.source,()->{
            try{NativeLightWindowCache.write(blocker,KEY,f.entry);}
            catch(IOException failure){actualFailure.set(failure);throw failure;}
        });}catch(IOException expected){observed=expected;}
        h.assertTrue(observed!=null&&observed==actualFailure.get(),"The exact failure from real native cache IO reaches the caller");
        h.assertTrue(Files.isRegularFile(blocker)&&hasFile(f.sky),"Native failure does not destroy the earlier real source snapshot");
        retire(f.source);var failure=new AtomicReference<Throwable>();
        Thread quarantine=new Thread(()->{try{f.source.quarantineLightCaches();}catch(Throwable e){failure.set(e);}},"near-failed-native-quarantine");
        quarantine.start();quarantine.join(3000);
        h.assertTrue(!quarantine.isAlive()&&failure.get()==null&&!Files.exists(f.sky),"Exceptional native IO releases the actual quarantine monitor: "+failure.get());
        h.succeed();
    }

    private record Fixture(GeneratedSkySource source,Path root,Path sky,Path windows,NativeLightWindowCache.Entry entry){}
    private static Fixture fixture(GameTestHelper h)throws Exception {
        var manifest=LivingAbyssGameTests.parentManifest();var profile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        var source=new GeneratedSkySource(profile,Map.of());
        Path base=Path.of(System.getProperty("mia.storageRoot", ".local") + "/codex/20261014-near-blocks-stage1/fixtures/native-cache-persistence").toAbsolutePath().normalize();
        Files.createDirectories(base);Path root=Files.createTempDirectory(base,"fence-");Path sky=root.resolve("sky-probes");
        source.restoreLightCache(sky,profile.fingerprint());source.columnExposure(-17,-33);
        var bounds=new VoxelSkyLight.Bounds(-16,-336,-16,16,16,16);var key=new CubeKey(-1,-21,-1);
        var states=source.captureDerivedCube(key,()->{});var exact=VoxelSkyLight.restore(bounds,new byte[4096],new byte[4096],4096);
        var entry=new NativeLightWindowCache.Entry(bounds,exact,exact,Map.of(key,states));
        System.out.println("MIA_NATIVE_CACHE_FENCE_FIXTURE "+root);
        return new Fixture(source,root,sky,sky.resolve("native-windows"),entry);
    }

    /** Tags are changed only by the GameTest owner and restored before another test can run. */
    private static void retire(GeneratedSkySource source) {
        var holder=Blocks.STONE.builtInRegistryHolder();var original=holder.tags().toList();
        try{
            var changed=new HashSet<>(original);if(!changed.remove(BlockTags.LOGS))changed.add(BlockTags.LOGS);holder.bindTags(changed);
            boolean refused=false;try{source.verifyPhysicalSemantics();}catch(IllegalStateException expected){refused=true;}
            if(!refused||!source.physicalAuthorityRetired())throw new AssertionError("Real resolved physical tags must retire this source");
        }finally{holder.bindTags(original);}
    }
    private static boolean hasFile(Path directory)throws IOException {
        try(var paths=Files.walk(directory)){return paths.anyMatch(Files::isRegularFile);}
    }
    private static Path quarantined(Path root)throws IOException {
        try(var paths=Files.list(root)){return paths.filter(p->p.getFileName().toString().startsWith("sky-probes.quarantined-")).findFirst().orElseThrow();}
    }
    private static boolean quarantineBeforeRelease(Thread worker,CountDownLatch done)throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(done.getCount()!=0&&worker.getState()!=Thread.State.BLOCKED&&System.nanoTime()<deadline)Thread.sleep(1);
        if(done.getCount()!=0&&worker.getState()!=Thread.State.BLOCKED)throw new AssertionError("Controlled quarantine neither reached the source monitor nor completed");
        return done.getCount()==0;
    }
    private static void await(CountDownLatch release)throws IOException {
        try{if(!release.await(6,TimeUnit.SECONDS))throw new IOException("Native writer release deadline expired");}
        catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("Native writer interrupted",cancelled);}
    }

}
