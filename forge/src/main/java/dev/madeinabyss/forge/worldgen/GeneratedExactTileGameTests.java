package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.light.SkyVisibility;
import dev.madeinabyss.kernel.light.VoxelSkyLight;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;

/** Independent unprepared sampling is the admission oracle for prepared immutable tiles. */
@GameTestHolder("near_stage1")
@PrefixGameTestTemplate(false)
public final class GeneratedExactTileGameTests {
    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void nativeRayCursorReusesKnownTileWithoutRepeatingPointProofs(GameTestHelper h)throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var key=new CubeKey(-2,-27,-3);
        int[] saved=new int[4096];Arrays.fill(saved,Block.getId(Blocks.AIR.defaultBlockState()));
        saved[0]=Block.getId(Blocks.STONE.defaultBlockState());saved[17]=Block.getId(Blocks.WATER.defaultBlockState());
        saved[256]=Block.getId(Blocks.OAK_LEAVES.defaultBlockState());
        var source=new GeneratedSkySource(profile,Map.of(key,saved));capture(source,key,()->{});
        long hits=source.exactTileHits();var checks=new AtomicInteger();
        try(var ignored=scope(source,checks::incrementAndGet)) {
            var cursor=nativeRay(source);
            for(int i=0;i<1024;i++) {
                int x=-32+(i&15),y=-432+(i>>8),z=-48+((i>>4)&15);
                h.assertTrue(cursor.opacity(x,y,z)==source.opacity(saved[i]),"Resident cursor preserves every saved AIR/solid/partial-opacity cell");
            }
        }
        h.assertTrue(checks.get()<=8,"One complete known tile proof is reused; cooperative checks remain periodic rather than per-point: "+checks.get());
        h.assertTrue(source.exactTileHits()-hits<=8,"A resident ray cursor must not perform1024 shared tile/map lookups");h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void nativeRayCursorPreservesSignedBoundariesAndGlobalSavedOccluders(GameTestHelper h)throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var saved=new HashMap<CubeKey,int[]>();
        for(var key:List.of(new CubeKey(-1,-57,-1),new CubeKey(0,-57,-1),new CubeKey(-1,-56,-1),new CubeKey(-1,-57,0))) {
            var values=new int[4096];for(int i=0;i<4096;i++)values[i]=Block.getId((i%3==0?Blocks.WATER:i%3==1?Blocks.OAK_LEAVES:Blocks.AIR).defaultBlockState());saved.put(key,values);
        }
        // This roof is outside an80-cube NEAR solve atY=-900; original32 rays still see it.
        var roof=new int[4096];Arrays.fill(roof,Block.getId(Blocks.STONE.defaultBlockState()));saved.put(new CubeKey(0,-53,0),roof);
        var source=new GeneratedSkySource(profile,saved);var oracle=cold(profile,saved);var cursor=nativeRay(source);
        int[][] points={{-16,-912,-16},{-1,-897,-1},{0,-912,-1},{15,-897,-1},{-1,-896,-1},{-1,-897,0},{-17,-900,-17}};
        for(var p:points)h.assertTrue(cursor.opacity(p[0],p[1],p[2])==oracle.opacity(oracle.stateIdUncached(p[0],p[1],p[2])),"Cursor crosses signed XYZ cube boundaries with exact saved/procedural authority");
        double expected=SkyVisibility.visibleFraction(8,-900,8,oracle.maxY(),100000,(x,y,z)->oracle.opacity(oracle.stateIdUncached(x,y,z)));
        double actual=SkyVisibility.visibleFraction(8,-900,8,source.maxY(),100000,cursor);
        h.assertTrue(Double.doubleToLongBits(actual)==Double.doubleToLongBits(expected),"Original32 global rays preserve out-of-solve saved occluders");
        h.assertTrue(expected<1.0,"Global saved roof actually affects irradiance");h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void nativeRayCursorCancelsResidentScanWithoutRetainingClosedTargetScope(GameTestHelper h)throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var key=new CubeKey(-1,-57,-1);var saved=new int[4096];Arrays.fill(saved,Block.getId(Blocks.AIR.defaultBlockState()));
        var source=new GeneratedSkySource(profile,Map.of(key,saved));var cursor=nativeRay(source);cursor.opacity(-1,-900,-1);
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();int sampled=0;boolean refused=false;
        try(var ignored=scope(source,()->{if(cancelled.get())throw new CancellationException("old ray target");})) {
            cancelled.set(true);
            try{for(;sampled<257;sampled++)cursor.opacity(-1,-900,-1);}catch(CancellationException expected){refused=true;}
        }
        h.assertTrue(refused&&sampled<=256,"Cached AIR path reaches cooperative target cancellation within256 positions");
        h.assertTrue(cursor.opacity(-1,-900,-1)==0,"Cursor does not retain closed target callback in source or worker ThreadLocal");
        var holder=Blocks.STONE.builtInRegistryHolder();var original=holder.tags().toList();
        try {
            var changed=new HashSet<>(original);if(!changed.remove(net.minecraft.tags.BlockTags.LOGS))changed.add(net.minecraft.tags.BlockTags.LOGS);holder.bindTags(changed);
            try{source.verifyPhysicalSemantics();}catch(IllegalStateException expected){}
            h.assertTrue(source.physicalAuthorityRetired(),"Actual physical change retires the source");
            refused=false;try{cursor.opacity(-1,-900,-1);}catch(CancellationException expected){refused=true;}
            h.assertTrue(refused,"Every cached cursor hit checks physical authority before returning");
        } finally {holder.bindTags(original);}
        h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void preparedCubesEqualUnpreparedOracleInV14V15DenseFloraAndSignedEdges(GameTestHelper h)throws Exception {
        var old=LivingAbyssGameTests.parentManifest();var v14=new GeneratedPreviewProfile(old,old.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        var v15=LivingAbyssGameTests.profile(h);long compared=0,flora=0;
        for(var profile:List.of(v14,v15)) {
            // Separate generator instances keep prepared arithmetic/thread-local memo history
            // from making its own baseline agree through a previously cached support answer.
            var referenceManifest=profile==v15?LivingAbyssGameTests.manifest():old.deepCopy();
            var reference=new GeneratedPreviewProfile(referenceManifest,profile.id(),LivingAbyssGameTests.biomes(h));
            var source=new GeneratedSkySource(reference,Map.of());var tiled=new GeneratedSkySource(profile,Map.of());var keys=new LinkedHashSet<CubeKey>();
            keys.addAll(List.of(new CubeKey(-2,-27,-3),new CubeKey(23,-20,-1),new CubeKey(-33,-48,6),new CubeKey(-1,-57,-1),new CubeKey(-61,-80,-1),new CubeKey(59,-20,-1)));
            if(profile==v15) {
                var trees=profile.generator().livingAbyss().flora().trees();
                for(int sx:new int[]{-1,1})for(int sz:new int[]{-1,1}) {
                    var tree=trees.stream().filter(t->Integer.signum(t.x())==sx&&Integer.signum(t.z())==sz).findFirst().orElseThrow();
                    for(int dy:new int[]{0,8,16})keys.add(new CubeKey(Math.floorDiv(tree.x(),16),Math.floorDiv(tree.y()+dy,16),Math.floorDiv(tree.z(),16)));
                }
                keys.add(new CubeKey(-1,-89,-1));
            }
            for(var key:keys) {
                h.assertTrue(profile.generator().supportsCube(key),"Oracle fixture is a complete known cube: "+key);
                var expected=unprepared(source,key);var prepared=profile.generator().generateCube(key).states();
                h.assertTrue(Arrays.equals(expected,prepared),"Prepared generator must equal independent unprepared stateIdUncached at "+profile.id()+" "+key+firstDifference(expected,prepared));
                h.assertTrue(Arrays.equals(expected,capture(tiled,key,()->{})),"Actual immutable density tile, including uniform CENTRE_AIR, retains the independent full-state oracle at "+key);
                for(int id:expected)if(Block.stateById(id).is(net.minecraft.tags.BlockTags.LEAVES)||Block.stateById(id).is(Blocks.VINE)||Block.stateById(id).is(Blocks.MOSS_CARPET))flora++;
                compared+=4096;
            }
        }
        h.assertTrue(flora>100,"Dense actual leaf/vine/moss states make preparation oracle non-vacuous: "+flora);
        System.out.println("MIA_NEAR_PREPARED_ORACLE compared="+compared+" flora="+flora+" v14="+v14.fingerprint()+" v15="+v15.fingerprint());h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void densityAndAmbientQueriesReuseOneCompleteExactV15Tile(GameTestHelper h)throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var source=new GeneratedSkySource(profile,Map.of());
        var tree=profile.generator().livingAbyss().flora().trees().stream().filter(t->t.x()<0&&t.z()<0).findFirst().orElseThrow();
        var key=new CubeKey(Math.floorDiv(tree.x(),16),Math.floorDiv(tree.y()+8,16),Math.floorDiv(tree.z(),16));var expected=unprepared(source,key);
        h.assertTrue(Arrays.equals(expected,capture(source,key,()->{})),"Native bulk density entry preserves the independent full-state oracle");
        var ray=nativeRay(source);
        for(int pass=0;pass<2;pass++)for(int i=0;i<4096;i++) {
            int x=key.x()*16+(i&15),y=key.y()*16+(i>>8),z=key.z()*16+((i>>4)&15);
            h.assertTrue(source.stateIdForDerivedGeometry(x,y,z)==expected[i],"Full-state density tile preserves exact registry identity");
            h.assertTrue(ray.opacity(x,y,z)==source.opacity(expected[i]),"Explicit native ambient consumer uses the exact density tile");
        }
        h.assertTrue(metric(source,"exactTileBuilds")>0,"Exact immutable cube tiles must replace repeated procedural sampling");
        h.assertTrue(metric(source,"exactTileBuilds")==1&&metric(source,"exactTileHits")>0,"Explicit density and native rays share one published complete tile");
        h.assertTrue(metric(source,"exactTileResidentBytes")>0&&metric(source,"exactTileResidentBytes")<=64L*1024*1024,"Source tile payload stays within64MiB");
        int[] captured=capture(source,key,()->{});h.assertTrue(Arrays.equals(expected,captured),"Actual bulk density callback equals unprepared oracle");captured[0]=Integer.MAX_VALUE;
        h.assertTrue(capture(source,key,()->{})[0]==expected[0],"Caller-owned density array cannot mutate the immutable tile");h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void savedTransparentEmissiveAndAirVoxelsWinAgainstPreparedGeometry(GameTestHelper h)throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var key=new CubeKey(-2,-27,-3);var values=new int[4096];Arrays.fill(values,Block.getId(Blocks.AIR.defaultBlockState()));
        var fixtures=List.of(Blocks.STONE,Blocks.WATER,Blocks.OAK_LEAVES,Blocks.MOSS_CARPET,Blocks.TORCH);
        for(int i=0;i<fixtures.size();i++)values[i]=Block.getId(fixtures.get(i).defaultBlockState());var expected=values.clone();
        var source=new GeneratedSkySource(profile,Map.of(key,values));Arrays.fill(values,Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()));
        h.assertTrue(Arrays.equals(expected,capture(source,key,()->{})),"Saved whole cube remains immutable authority before prepareCube");
        for(int i=0;i<4096;i++) {
            int x=key.x()*16+(i&15),y=key.y()*16+(i>>8),z=key.z()*16+((i>>4)&15);
            h.assertTrue(source.stateIdForDerivedGeometry(x,y,z)==expected[i]&&source.opacityFast(x,y,z)==source.opacity(expected[i]),"Tiles retain saved state/opacity including unchanged AIR");
        }
        h.assertTrue(source.emission(expected[4])>0&&source.opacity(expected[4])==0,"Emissive transparent saved fixture is non-vacuous");h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void partialSpatialBoundaryKeepsKnownVoxelAndRefusesUnknownNeighbour(GameTestHelper h)throws Exception {
        var old=LivingAbyssGameTests.parentManifest();var v14=new GeneratedPreviewProfile(old,old.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        for(var profile:List.of(v14,LivingAbyssGameTests.profile(h))) {
            var source=new GeneratedSkySource(profile,Map.of());int y=profile==v14?-1290:-1430;
            h.assertTrue(!profile.generator().supportsCube(new CubeKey(-60,Math.floorDiv(y,16),0)),"Whole boundary cube includes unavailable inland data");
            int id=source.stateIdUncached(-960,y,0);
            h.assertTrue(source.stateIdForDerivedGeometry(-960,y,0)==id&&source.opacityFast(-960,y,0)==source.opacity(id),"Known exterior voxel cannot be refused merely because its cube crosses UNKNOWN");
            var cursor=nativeRay(source);h.assertTrue(cursor.opacity(-960,y,0)==source.opacity(id),"Cursor keeps known boundary voxel without certifying its incomplete cube");
            boolean refused=false;try{source.stateIdForDerivedGeometry(-959,y,0);}catch(TerrainUnavailableException expected){refused=true;}
            h.assertTrue(refused,"Adjacent inland UNKNOWN never becomes AIR in an exact tile");
            refused=false;try{cursor.opacity(-959,y,0);}catch(TerrainUnavailableException expected){refused=true;}
            h.assertTrue(refused,"Known-point fallback cannot let a later UNKNOWN cursor position become AIR");
        }h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void cancelledPreparedTileDoesNotPublishAndThreadScopeCloses(GameTestHelper h)throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var source=new GeneratedSkySource(profile,Map.of());
        // T5 proves the old (-33,-48,6) fixture uniformly AIR. Keep this assertion
        // inside a real scan-row fill; a callback scheduled for check30 cannot
        // cancel a uniform operation that has already completed at check9.
        var key=new CubeKey(42,-48,6);var checks=new AtomicInteger();
        h.assertTrue(Arrays.stream(unprepared(source,key)).anyMatch(id->id!=Block.getId(Blocks.AIR.defaultBlockState())),"Scan-row cancellation fixture contains a real non-AIR voxel");
        var living=profile.generator().livingAbyss();
        try{h.assertTrue(!living.prepareCube(key.x()*16,key.y()*16,key.z()*16),"Scan-row fixture cannot take uniform AIR preparation");}finally{living.finishCube();}
        boolean cancelled=false;try(var ignored=scope(source,()->{if(checks.incrementAndGet()==30)throw new CancellationException("obsolete target");})) {
            capture(source,key,()->{});
        }catch(CancellationException expected){cancelled=true;}
        System.out.println("MIA_T5_TILE_CANCEL key="+key+" checks="+checks.get()+" cancelled="+cancelled+" resident="+metric(source,"exactTileResidentTiles"));
        h.assertTrue(cancelled&&checks.get()==30,"Prepared fill reaches cooperative checkpoints inside scan rows");
        h.assertTrue(metric(source,"exactTileResidentTiles")==0,"Cancelled partial cube is never admitted");
        h.assertTrue(Arrays.equals(unprepared(source,key),capture(source,key,()->{})),"Fresh target retries after cancelled prepared scope and cleanup");
        var fresh=new GeneratedSkySource(LivingAbyssGameTests.profile(h),Map.of());var columns=new AtomicInteger();
        cancelled=false;try(var ignored=scope(fresh,()->{if(columns.incrementAndGet()==4)throw new CancellationException("new column target");})){fresh.nativeColumnExposure(480,-16);}catch(CancellationException expected){cancelled=true;}
        h.assertTrue(cancelled&&fresh.cachedColumnExposure(480,-16).isEmpty(),"Cancelled global column scan cannot publish partial direct exposure");
        verifyCertifiedUniformTileCancellation(h,profile);h.succeed();
    }
    private static void verifyCertifiedUniformTileCancellation(GameTestHelper h,GeneratedPreviewProfile profile)throws Exception {
        var key=new CubeKey(-33,-48,6);
        h.assertTrue(profile.generator().livingAbyss().airCertificate().uniformAir(key.x(),key.y(),key.z()),"Exercise the newly certified former scan-row fixture");
        // Current calls7/8 are the uniform pre-return and cache pre-publication
        // checkpoints. Both must abort without admitting a completed tile.
        for(int cancelAt:new int[]{7,8}){
            var source=new GeneratedSkySource(profile,Map.of());var checks=new AtomicInteger();boolean cancelled=false;
            try(var ignored=scope(source,()->{if(checks.incrementAndGet()==cancelAt)throw new CancellationException("obsolete uniform target");})){capture(source,key,()->{});}
            catch(CancellationException expected){cancelled=true;}
            h.assertTrue(cancelled&&checks.get()==cancelAt&&metric(source,"exactTileResidentTiles")==0,"Uniform preparation/candidate publication observes cancellation at "+cancelAt);
            for(int state:capture(source,key,()->{}))h.assertTrue(state==Block.getId(Blocks.AIR.defaultBlockState()),"Closed cancelled uniform scope permits a complete exact retry");
        }
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void explicitColdSourceBypassesReuseWithoutChangingAuthority(GameTestHelper h)throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var normal=new GeneratedSkySource(profile,Map.of());var cold=cold(profile,Map.of());var key=new CubeKey(-1,-57,-1);
        for(int pass=0;pass<2;pass++) {
            h.assertTrue(Arrays.equals(capture(normal,key,()->{}),capture(cold,key,()->{})),"Cold prepared density preserves exact full states");
            h.assertTrue(normal.directSkyFast(-17,-500,-33)==cold.directSkyFast(-17,-500,-33),"Cold global direct exposure matches warm reuse");
        }
        h.assertTrue(metric(cold,"exactTileResidentTiles")==0&&cold.cachedSkyColumns()==0&&cold.cachedVisibilityProbes()==0,"Cold source cannot read or populate tile/probe/column caches");
        h.assertTrue(normal.cacheGeometryIdentity().equals(cold.cacheGeometryIdentity()),"Cache bypass never changes physical geometry authority");h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void geometryIdentityDefersDerivedWritesUntilExplicitPersistence(GameTestHelper h)throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var key=new CubeKey(-33,-48,6);var edited=new int[4096];Arrays.fill(edited,Block.getId(Blocks.AIR.defaultBlockState()));edited[0]=Block.getId(Blocks.TORCH.defaultBlockState());
        var parent=java.nio.file.Files.createTempDirectory("mia-near-exact-identity-");
        try {
            var source=new GeneratedSkySource(profile,Map.of(key,edited));source.restoreLightCache(parent.resolve("normal"),profile.fingerprint());source.cacheGeometryIdentity();
            try(var paths=java.nio.file.Files.walk(parent)){h.assertTrue(paths.noneMatch(java.nio.file.Files::isRegularFile),"Derived identity/restore cannot synchronously write procedural digests before light publication");}
            source.persistLightCache();
            try(var paths=java.nio.file.Files.walk(parent)){h.assertTrue(paths.anyMatch(java.nio.file.Files::isRegularFile),"Explicit background persistence flushes complete procedural/sky derived snapshots");}
            var bypass=cold(profile,Map.of(key,edited));bypass.restoreLightCache(parent.resolve("cold"),profile.fingerprint());bypass.persistLightCache();
            h.assertTrue(!java.nio.file.Files.exists(parent.resolve("cold")),"Explicit cold source performs no derived disk read/write admission");
        } finally {try(var paths=java.nio.file.Files.walk(parent)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}}
        h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void freshColdBenchmarkSkipsDiskRestoreButKeepsInSolveMemoization(GameTestHelper h)throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var parent=java.nio.file.Files.createTempDirectory("mia-near-exact-cold-");
        try {
            var warm=new GeneratedSkySource(profile,Map.of());warm.restoreLightCache(parent,profile.fingerprint());int expected=warm.directSkyFast(-17,-500,-33);warm.persistLightCache();
            var fresh=new GeneratedSkySource(profile,Map.of());fresh.disableDerivedDiskRestore();fresh.restoreLightCache(parent,profile.fingerprint());
            h.assertTrue(fresh.cachedSkyColumns()==0&&fresh.cachedVisibilityProbes()==0,"True cold benchmark cannot restore warm disk values");
            var key=new CubeKey(-1,-57,-1);capture(fresh,key,()->{});capture(fresh,key,()->{});
            h.assertTrue(metric(fresh,"exactTileBuilds")==1&&metric(fresh,"exactTileHits")>0,"Empty cold source builds then reuses tiles during the same solve");
            h.assertTrue(fresh.nativeColumnExposure(-17,-33).directSky(-500)==expected&&fresh.nativeColumnExposure(-17,-33).directSky(-500)==expected,"Fresh-cold explicit native exposure remains unchanged");
            h.assertTrue(fresh.skyColumnBuilds()==1&&fresh.cachedSkyColumns()==1,"In-solve column memo remains enabled during a true cold run");
        } finally {try(var paths=java.nio.file.Files.walk(parent)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}}
        h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void physicalResourceChangeCannotServeRetiredTileOrColumnHits(GameTestHelper h)throws Exception {
        var manifest=LivingAbyssGameTests.parentManifest();var profile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        var source=new GeneratedSkySource(profile,Map.of());var key=new CubeKey(-1,-57,-1);capture(source,key,()->{});source.columnExposure(-17,-33);
        var holder=Blocks.STONE.builtInRegistryHolder();var original=holder.tags().toList();
        try {
            var changed=new HashSet<>(original);if(!changed.remove(net.minecraft.tags.BlockTags.LOGS))changed.add(net.minecraft.tags.BlockTags.LOGS);holder.bindTags(changed);
            boolean refused=false;try{source.verifyPhysicalSemantics();}catch(IllegalStateException expected){refused=true;}
            h.assertTrue(refused&&source.physicalAuthorityRetired(),"Resolved physical LOGS classification retires immutable source authority");
            for(Runnable query:List.<Runnable>of(()->capture(source,key,()->{}),()->source.stateIdForDerivedGeometry(-1,-900,-1),()->source.opacityFast(-1,-900,-1),()->source.columnExposure(-17,-33))) {
                refused=false;try{query.run();}catch(CancellationException expected){refused=true;}
                h.assertTrue(refused,"Even cached exact tile/column/certificate hits must reject retired physical authority");
            }
        } finally {holder.bindTags(original);}
        h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void initializedRestoreReturnsWhileBackgroundPersistenceMonitorIsHeld(GameTestHelper h)throws Exception {
        var profile=LivingAbyssGameTests.profile(h);var source=new GeneratedSkySource(profile,Map.of());var parent=java.nio.file.Files.createTempDirectory("mia-near-restore-lock-");
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var returned=new java.util.concurrent.CountDownLatch(1);var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread writer=new Thread(()->{synchronized(source){held.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Persistence monitor timed out");}catch(Throwable e){failure.set(e);}}},"near-restore-monitor-owner");
        Thread reader=new Thread(()->{try{source.restoreLightCache(parent,profile.fingerprint());}catch(Throwable e){failure.set(e);}finally{returned.countDown();}},"near-cached-restore-reader");
        boolean nonblocking=false;
        try {
            source.restoreLightCache(parent,profile.fingerprint());writer.start();h.assertTrue(held.await(2,java.util.concurrent.TimeUnit.SECONDS),"Persistence owner actually holds source monitor");
            reader.start();nonblocking=returned.await(1,java.util.concurrent.TimeUnit.SECONDS);
        } finally {release.countDown();writer.join(2000);if(reader.getState()!=Thread.State.NEW)reader.join(2000);try(var paths=java.nio.file.Files.walk(parent)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}}
        h.assertTrue(!writer.isAlive()&&!reader.isAlive()&&failure.get()==null,"Controlled restore/persistence workers finish without side effects: "+failure.get());
        h.assertTrue(nonblocking,"Initialized restore cannot block a newer target behind unrelated background derived writes");h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void v14V15Original32DiffuseAndGlobalColumnsEqualUncachedOracle(GameTestHelper h)throws Exception {
        var old=LivingAbyssGameTests.parentManifest();var v14=new GeneratedPreviewProfile(old,old.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        for(var profile:List.of(v14,LivingAbyssGameTests.profile(h))) {
            var source=new GeneratedSkySource(profile,Map.of());var nativeColumns=new GeneratedSkySource(profile,Map.of());var oracle=cold(profile,Map.of());
            int[][] points={{-17,-431,-33},{481,-328,-1},{-560,-749,104},{0,-900,0}};
            for(var p:points) {
                int expected=oracle.directSkyFast(p[0],p[1],p[2]);h.assertTrue(source.directSkyFast(p[0],p[1],p[2])==expected,"Generic exact point columns retain global sky dependencies");
                h.assertTrue(nativeColumns.nativeColumnExposure(p[0],p[2]).directSky(p[1])==expected,"Explicit native prepared columns equal independent raw direct-SKY oracle");
                double fraction=SkyVisibility.visibleFraction(p[0],p[1],p[2],oracle.maxY(),100000,(x,y,z)->oracle.opacity(oracle.stateIdUncached(x,y,z)));
                double actual=SkyVisibility.visibleFraction(p[0],p[1],p[2],source.maxY(),100000,source::opacityFast);
                h.assertTrue(Double.doubleToLongBits(fraction)==Double.doubleToLongBits(actual),"All original32 directions retain exact occlusion/transmittance at "+Arrays.toString(p));
                double cursor=SkyVisibility.visibleFraction(p[0],p[1],p[2],source.maxY(),100000,nativeRay(source));
                h.assertTrue(Double.doubleToLongBits(fraction)==Double.doubleToLongBits(cursor),"Worker-owned ray cursor retains all original32 voxel traversals");
                h.assertTrue(source.ambientSky(p[0],p[1],p[2])==oracle.ambientSky(p[0],p[1],p[2]),"Ambient lattice/partial-opacity fallback matches uncached reference");
            }
        }h.succeed();
    }

    @GameTest(template="empty",batch="nearExactTiles",timeoutTicks=1200)
    public static void exactSavedTilesPreserveSkyBlockAndPackedModelFluidStencil(GameTestHelper h)throws Exception {
        var old=LivingAbyssGameTests.parentManifest();var v14=new GeneratedPreviewProfile(old,old.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        var key=new CubeKey(-2,-27,-3);var saved=new int[4096];Arrays.fill(saved,Block.getId(Blocks.AIR.defaultBlockState()));
        for(int z=1;z<=4;z++)for(int x=1;x<=4;x++)saved[(8<<8)|(z<<4)|x]=Block.getId(Blocks.STONE.defaultBlockState());
        saved[(10<<8)|(2<<4)|2]=Block.getId(Blocks.TORCH.defaultBlockState());
        saved[(10<<8)|(2<<4)|3]=Block.getId(Blocks.WATER.defaultBlockState());
        saved[(11<<8)|(3<<4)|3]=Block.getId(Blocks.OAK_LEAVES.defaultBlockState());
        var bounds=new VoxelSkyLight.Bounds(-31,-424,-47,4,4,4);
        for(var profile:List.of(v14,LivingAbyssGameTests.profile(h))) {
            var tiled=new GeneratedSkySource(profile,Map.of(key,saved));var oracle=cold(profile,Map.of(key,saved));
            byte[][] inputs=new byte[6][64];int cells=0;
            for(int y=0;y<4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++) {
                int xx=bounds.minX()+x,yy=bounds.minY()+y,zz=bounds.minZ()+z;
                int id=tiled.stateIdForDerivedGeometry(xx,yy,zz),expected=oracle.stateIdUncached(xx,yy,zz);
                h.assertTrue(id==expected,"Model/fluid full states are exact independently of opacity");
                inputs[0][cells]=(byte)tiled.opacityFast(xx,yy,zz);inputs[1][cells]=(byte)oracle.opacity(expected);
                inputs[2][cells]=(byte)tiled.ambientSky(xx,yy,zz);inputs[3][cells]=(byte)oracle.ambientSky(xx,yy,zz);
                inputs[4][cells]=(byte)tiled.emission(id);inputs[5][cells]=(byte)oracle.emission(expected);cells++;
            }
            for(int pair=0;pair<6;pair+=2)h.assertTrue(Arrays.equals(inputs[pair],inputs[pair+1]),"Every opacity/SKY seed/emission input retains exact value");
            var sky=VoxelSkyLight.solve(bounds,inputs[0],inputs[2],64);var skyOracle=VoxelSkyLight.solve(bounds,inputs[1],inputs[3],64);
            var block=VoxelSkyLight.solveBlock(bounds,inputs[0],inputs[4],64);var blockOracle=VoxelSkyLight.solveBlock(bounds,inputs[1],inputs[5],64);
            h.assertTrue(Arrays.equals(sky.lowerSnapshot(),skyOracle.lowerSnapshot())&&Arrays.equals(sky.upperSnapshot(),skyOracle.upperSnapshot()),"Poblock SKY and uncertainty stencil equal reference");
            h.assertTrue(Arrays.equals(block.lowerSnapshot(),blockOracle.lowerSnapshot())&&Arrays.equals(block.upperSnapshot(),blockOracle.upperSnapshot()),"Poblock BLOCK and uncertainty stencil equal reference");
            var s=sky.lowerSnapshot();var b=block.lowerSnapshot();var so=skyOracle.lowerSnapshot();var bo=blockOracle.lowerSnapshot();
            for(int i=0;i<64;i++)h.assertTrue(((s[i]<<20)|(b[i]<<4))==((so[i]<<20)|(bo[i]<<4)),"Packed model/fluid light remains identical at every stencil entry");
        }h.succeed();
    }

    private static int[] unprepared(GeneratedSkySource source,CubeKey key) {
        int[] result=new int[4096];for(int i=0;i<result.length;i++)result[i]=source.stateIdUncached(key.x()*16+(i&15),key.y()*16+(i>>8),key.z()*16+((i>>4)&15));return result;
    }
    private static String firstDifference(int[] a,int[] b){for(int i=0;i<a.length;i++)if(a[i]!=b[i])return " index="+i+" expected="+Block.stateById(a[i])+" actual="+Block.stateById(b[i]);return "";}
    private static long metric(GeneratedSkySource source,String name){return switch(name){case "exactTileBuilds"->source.exactTileBuilds();case "exactTileHits"->source.exactTileHits();case "exactTileResidentBytes"->source.exactTileResidentBytes();case "exactTileResidentTiles"->source.exactTileResidentTiles();default->throw new AssertionError(name);};}
    private static int[] capture(GeneratedSkySource source,CubeKey key,Runnable checkpoint){return source.captureDerivedCube(key,checkpoint);}
    private static GeneratedSkySource.CheckpointScope scope(GeneratedSkySource source,Runnable checkpoint){return source.requestCheckpoint(checkpoint);}
    private static GeneratedSkySource cold(GeneratedPreviewProfile profile,Map<CubeKey,int[]> saved){return new GeneratedSkySource(profile,saved,true);}
    private static SkyVisibility.VoxelOpacity nativeRay(GeneratedSkySource source){return source.nativeRayOpacity();}
}
