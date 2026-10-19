package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.forge.probe.deep.GeneratedSceneInventory;
import dev.madeinabyss.forge.probe.deep.GeneratedSceneNetwork;
import dev.madeinabyss.forge.probe.deep.GeneratedSceneDeltaAdmission;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.*;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.*;

/** Wire/admission and real saved/resident global-light correctness, without a native client. */
@GameTestHolder("near_delta")
@PrefixGameTestTemplate(false)
public final class GeneratedSceneDeltaGameTests {
    private static final CubeKey KEY=new CubeKey(-2,-44,-3);

    @GameTest(template="empty",batch="nearGlobalDelta",timeoutTicks=1200)
    public static void immutableSingleCubeDeltaRoundtripRejectsMalformedWithoutReaderAdvance(GameTestHelper h)throws Exception {
        UUID token=UUID.randomUUID();var states=air();states[0]=Block.getId(Blocks.STONE.defaultBlockState());
        var frame=frame(h,new CubeSnapshot(KEY,states));var packet=delta(token,1,frame);
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try{
            GeneratedSceneNetwork.encode(packet,buffer);byte[] encoded=new byte[buffer.readableBytes()];buffer.getBytes(0,encoded);
            var decoded=GeneratedSceneNetwork.decode(buffer);
            h.assertTrue(decoded instanceof GeneratedSceneNetwork.Delta&&decoded.session().equals(token)
                    &&((GeneratedSceneNetwork.Delta)decoded).revision()==1,"Delta preserves its session and ordered revision");
            var roundtrip=((GeneratedSceneNetwork.Delta)decoded).frame();
            h.assertTrue(roundtrip.key().equals(KEY)&&Arrays.equals(frame.bytes(),roundtrip.bytes()),"The complete native cube frame roundtrips");
            byte[] escaped=roundtrip.bytes();escaped[0]=0;
            h.assertTrue(roundtrip.bytes()[0]!=0,"Caller cannot mutate authoritative frame bytes");
            encoded[41]=0;var corrupt=new FriendlyByteBuf(Unpooled.wrappedBuffer(encoded));
            try{
                boolean refused=false;try{GeneratedSceneNetwork.decode(corrupt);}catch(IllegalArgumentException expected){refused=true;}
                h.assertTrue(refused&&corrupt.readerIndex()==0,"Malformed global delta fails transactionally before consuming any wire data");
            }finally{corrupt.release();}
        }finally{buffer.release();}
        h.succeed();
    }

    @GameTest(template="empty",batch="nearGlobalDelta",timeoutTicks=1200)
    public static void staleForeignGapMalformedAndResetCannotAdvanceAdmission(GameTestHelper h)throws Exception {
        UUID token=UUID.randomUUID();var receiver=receiver(token);var frame=frame(h,new CubeSnapshot(KEY,air()));
        var prepared=stage(receiver,delta(token,1,frame),h);
        h.assertTrue(receiver.revision()==0,"Staging cannot acknowledge data before observer admission succeeds");
        Map<CubeKey,int[]> escaped=states(prepared);escaped.get(KEY)[0]=Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        h.assertTrue(states(prepared).get(KEY)[0]==Block.getId(Blocks.AIR.defaultBlockState()),"Prepared maps return guarded full-state copies");
        commit(receiver,prepared);
        h.assertTrue(stage(receiver,delta(token,1,frame),h)==null&&stage(receiver,delta(UUID.randomUUID(),2,frame),h)==null,"Stale and foreign-session messages are ignored");
        boolean refused=false;try{stage(receiver,delta(token,3,frame),h);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused&&receiver.revision()==1,"A stream gap cannot silently omit another global edit");
        byte[] malformed=frame.bytes();malformed[24]=(byte)255;malformed[25]=(byte)255;
        refused=false;try{stage(receiver,delta(token,2,new GeneratedSceneNetwork.Frame(KEY,malformed)),h);}catch(IllegalArgumentException expected){refused=true;}
        h.assertTrue(refused&&receiver.revision()==1,"Actual native-palette validation precedes revision acknowledgement");
        var next=stage(receiver,delta(token,2,frame),h);receiver.reset(token);
        refused=false;try{commit(receiver,next);}catch(IllegalArgumentException|IllegalStateException expected){refused=true;}
        h.assertTrue(refused&&receiver.revision()==0,"Even reset to the same token invalidates an old prepared transaction");
        h.succeed();
    }

    @GameTest(template="empty",batch="nearGlobalDelta",timeoutTicks=1200)
    public static void rapidCubeEditsCoalesceWithoutOldComparisonRollback(GameTestHelper h)throws Exception {
        UUID token=UUID.randomUUID();var receiver=receiver(token);var observations=new GeneratedObservationQueue(8192,8192,64);
        int[] canonical=air();observations.baseline(Map.of(KEY,canonical));
        var first=canonical.clone();first[0]=Block.getId(Blocks.STONE.defaultBlockState());
        var p=stage(receiver,delta(token,1,frame(h,new CubeSnapshot(KEY,first))),h);observations.observe(states(p));commit(receiver,p);
        var older=GeneratedObservationQueue.compare(observations.takeBatch(),key->canonical);
        var latest=canonical.clone();latest[0]=Block.getId(Blocks.GLOWSTONE.defaultBlockState());
        p=stage(receiver,delta(token,2,frame(h,new CubeSnapshot(KEY,latest))),h);observations.observe(states(p));commit(receiver,p);
        observations.commit(older);
        h.assertTrue(observations.pendingCount()==1,"Older in-flight classification cannot remove the newer edit");
        var accepted=observations.commit(GeneratedObservationQueue.compare(observations.takeBatch(),key->canonical));
        h.assertTrue(accepted.geometryChanged()&&observations.retained().get(KEY)[0]==latest[0],"Only the newest authoritative whole cube reaches source refresh");
        p=stage(receiver,delta(token,3,frame(h,new CubeSnapshot(KEY,canonical))),h);observations.observe(states(p));commit(receiver,p);
        accepted=observations.commit(GeneratedObservationQueue.compare(observations.takeBatch(),key->canonical));
        h.assertTrue(accepted.geometryChanged()&&!observations.retained().containsKey(KEY)&&receiver.revision()==3,"Restoration of exact procedural geometry invalidates old light and prunes only the derived override");
        h.succeed();
    }

    @GameTest(template="empty",batch="nearGlobalDelta",timeoutTicks=1200)
    public static void otherPlayersResidentCeilingWinsSavedBootstrapAndGlobalSkyMemos(GameTestHelper h)throws Exception {
        var manifest=LivingAbyssGameTests.parentManifest();var profile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        int x=-24,y=-800,z=-35;var baseline=new HashMap<CubeKey,int[]>();
        for(int cy=-51;cy<20;cy++)baseline.put(new CubeKey(-2,cy,-3),air());
        var aPhysical=new SparseCubeSpace();var aVisual=new SparseCubeSpace();
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)aPhysical.loadEmpty(new CubeKey(-2+dx,-50+dy,-3+dz));
        for(var key:DirectionalVisualWindow.keys(new BlockCoordinate(x,y,z)))aVisual.loadEmpty(key);
        long physicalGeneration=aPhysical.contentGeneration(),visualGeneration=aVisual.contentGeneration();
        h.assertTrue(!aPhysical.isLoaded(KEY)&&!aVisual.isLoaded(KEY),"B's ceiling lies above A's entire physical and visual windows");
        var before=new GeneratedSkySource(profile,baseline);int exposed=before.directSkyFast(x,y,z);
        before.ambientSky(x,y,z,before::opacityFast,0);
        h.assertTrue(exposed==15&&before.cachedSkyColumns()>0&&before.cachedVisibilityProbes()>0,"A's global column and ray memo really begin warmed");
        Path base=Path.of(System.getProperty("mia.storageRoot", ".local") + "/codex/20261014-near-blocks-stage1/fixtures/global-delta").toAbsolutePath().normalize();Files.createDirectories(base);
        var root=Files.createTempDirectory(base,"bootstrap-");var store=new StableCubeStore(root,profile.generatorId(),1);
        store.write(new CubeSnapshot(KEY,air()));byte[] saved=Files.readAllBytes(store.path(KEY));
        var bResident=new SparseCubeSpace();bResident.load(new CubeSnapshot(KEY,air()));
        var edits=new HashMap<BlockCoordinate,Integer>();for(int zz=-48;zz<-32;zz++)for(int xx=-32;xx<-16;xx++)edits.put(new BlockCoordinate(xx,-704,zz),Block.getId(Blocks.STONE.defaultBlockState()));
        bResident.applyBatch(edits);var inventory=GeneratedSceneInventory.capture(biomes(h),store);
        h.assertTrue(inventory.cubes().get(0).states()[0]==Block.getId(Blocks.AIR.defaultBlockState()),"Actual disk bootstrap predates B's committed resident edit");
        UUID token=UUID.randomUUID();var receiver=receiver(token);var observations=new GeneratedObservationQueue(8192,8192,64);observations.baseline(baseline);
        var p=stage(receiver,delta(token,1,frame(h,bResident.snapshot(KEY).orElseThrow())),h);observations.observe(states(p));commit(receiver,p);
        var changed=observations.commit(GeneratedObservationQueue.compare(observations.takeBatch(),key->baseline.get(key)));
        h.assertTrue(changed.geometryChanged(),"Validated off-window resident delta requires a new global source");
        var fresh=new GeneratedSkySource(profile,observations.retained());
        var referenceProfile=new GeneratedPreviewProfile(manifest.deepCopy(),profile.id(),LivingAbyssGameTests.biomes(h));
        var oracle=new GeneratedSkySource(referenceProfile,observations.retained(),true);
        h.assertTrue(fresh.directSkyFast(x,y,z)==0&&fresh.directSkyFast(x,y,z)==oracle.directSkyFast(x,y,z),"Off-window ceiling changes actual direct SKY despite the old warmed column");
        h.assertTrue(fresh.ambientSky(x,y,z)==oracle.ambientSky(x,y,z)&&fresh.ambientSky(x,y,z)<exposed,"Fresh global ambient response equals the independent uncached oracle");
        h.assertTrue(before.directSkyFast(x,y,z)==15,"The old immutable memo would be stale without source replacement");
        p=stage(receiver,delta(token,2,frame(h,new CubeSnapshot(KEY,air()))),h);observations.observe(states(p));commit(receiver,p);
        observations.commit(GeneratedObservationQueue.compare(observations.takeBatch(),key->baseline.get(key)));
        h.assertTrue(new GeneratedSkySource(profile,observations.retained()).directSkyFast(x,y,z)==15,"Removing B's ceiling restores global SKY");
        h.assertTrue(aPhysical.loadedCubeCount()==27&&aVisual.loadedCubeCount()==243&&aPhysical.contentGeneration()==physicalGeneration&&aVisual.contentGeneration()==visualGeneration,"Global data never replaces or expands A's two residency windows");
        h.assertTrue(Arrays.equals(saved,Files.readAllBytes(store.path(KEY))),"Read-only delta/bootstrap never rewrites the saved cube");
        System.out.println("MIA_GLOBAL_DELTA_CEILING "+root);h.succeed();
    }

    private static int[] air(){int[] result=new int[4096];Arrays.fill(result,Block.getId(Blocks.AIR.defaultBlockState()));return result;}
    private static Registry<Biome> biomes(GameTestHelper h){return h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);}
    private static GeneratedSceneNetwork.Frame frame(GameTestHelper h,CubeSnapshot snapshot){
        return GeneratedSceneNetwork.frame(biomes(h),snapshot);
    }
    private static GeneratedSceneNetwork.Delta delta(UUID token,long revision,GeneratedSceneNetwork.Frame frame) {
        return new GeneratedSceneNetwork.Delta(token,revision,frame);
    }
    private static GeneratedSceneDeltaAdmission receiver(UUID token) {
        var result=new GeneratedSceneDeltaAdmission();result.reset(token);return result;
    }
    private static GeneratedSceneDeltaAdmission.Prepared stage(GeneratedSceneDeltaAdmission receiver,GeneratedSceneNetwork.Delta delta,GameTestHelper h) {
        return receiver.stage(delta,biomes(h));
    }
    private static void commit(GeneratedSceneDeltaAdmission receiver,GeneratedSceneDeltaAdmission.Prepared prepared){receiver.commit(prepared);}
    private static Map<CubeKey,int[]> states(GeneratedSceneDeltaAdmission.Prepared prepared){return prepared.statesMap();}
}
