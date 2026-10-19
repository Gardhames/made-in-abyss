package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.forge.probe.client.ClientDeepVisual;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.forge.probe.deep.DeepVisualNetwork;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedBoundaryGameTests {
    @GameTest(template="empty")
    public static void generatedSkyAboveNativeCeilingKeepsCubeOwnership(GameTestHelper helper) {
        var level=helper.getLevel();var space=new SparseCubeSpace();
        for(int x=-1;x<=1;x++)for(int y=18;y<=26;y++)for(int z=-1;z<=1;z++)space.loadEmpty(new CubeKey(x,y,z));
        var marker=new BlockPos(5,340,5);space.setBlock(new BlockCoordinate(5,340,5),net.minecraft.world.level.block.Block.getId(Blocks.STONE.defaultBlockState()));
        try {
            DeepCubeDomain.attachGenerated(level,space,false);
            for(int y:new int[]{310,319,320,340,400}) {
                helper.assertTrue(DeepCubeDomain.isDeep(level,y),"generated ownership must cross native ceiling at "+y);
                var bounds=new net.minecraft.world.phys.AABB(5,y,5,6,y+2,6);
                helper.assertTrue(DeepCubeDomain.knownGeneratedHalo(level,space,bounds),"known sky halo cannot demote generated world at "+y);
                helper.assertTrue(DeepCubeDomain.allowsMove(level,bounds),"loaded sky remains traversable at "+y);
            }
            helper.assertTrue(level.getBlockState(marker).is(Blocks.STONE),"cube state above320 must not become native AIR");
            helper.assertTrue(!DeepCubeDomain.knownGeneratedHalo(level,space,new net.minecraft.world.phys.AABB(5,450,5,6,452,6)),"unloaded sky is still UNKNOWN");
        }finally {DeepCubeDomain.detach(level);}
        helper.succeed();
    }
    @GameTest(template="empty")
    public static void generatedLightAcrossNativeMinKeepsCubeAuthorityWithoutDuplicateInputs(GameTestHelper helper) {
        var level=helper.getLevel();var nativeSource=new BlockPos(10008,-63,10008);var before=level.getBlockState(nativeSource);
        var space=new SparseCubeSpace();
        // Keep the native capture away from the GameTest framework's beacon/model-data fixtures.
        for(int x=624;x<=626;x++)for(int y=-6;y<=-2;y++)for(int z=624;z<=626;z++)space.loadEmpty(new CubeKey(x,y,z));
        level.setBlock(nativeSource,Blocks.TORCH.defaultBlockState(),2);
        try {
            DeepCubeDomain.attachGenerated(level,space,true);
            for(int y:new int[]{-65,-63,-60})helper.assertTrue(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,new BlockPos(10008,y,10008))==0,
                    "generated known AIR has confirmed zero BLOCK light across native min; old host torch is not an input");
        } finally {DeepCubeDomain.detach(level);level.setBlock(nativeSource,before,2);}
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void nativeChunkCompletionRetainsItsOwnNeighborsAndRestoresCubeOwnership(GameTestHelper helper) throws ReflectiveOperationException {
        var level=helper.getLevel();var position=helper.absolutePos(new BlockPos(1,2,1));
        var chunk=level.getChunkAt(position);var before=chunk.getBlockState(position);
        for(var pending:chunk.getPostProcessing())
            helper.assertTrue(pending==null||pending.isEmpty(),"isolated native completion fixture has no preexisting postprocessing");
        var space=new SparseCubeSpace();space.loadEmpty(new CubeKey(-200,-200,-200));
        level.setBlock(position,Blocks.OAK_FENCE.defaultBlockState(),2);
        chunk.addPackedPostProcess(net.minecraft.world.level.chunk.ProtoChunk.packOffsetCoordinates(position),chunk.getSectionIndex(position.getY()));
        try {
            DeepCubeDomain.attachGenerated(level,space,false);
            var callback=net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("lambda$prepareTickingChunk$41",net.minecraft.world.level.chunk.LevelChunk.class);
            callback.setAccessible(true);
            // Exercise the actual native future-completion entry, including neighbor reads and writes.
            try {callback.invoke(level.getChunkSource().chunkMap,chunk);}
            catch(java.lang.reflect.InvocationTargetException failed) {
                if(failed.getCause() instanceof RuntimeException cause)throw cause;
                throw failed;
            }
            helper.assertTrue(chunk.getBlockState(position).is(Blocks.OAK_FENCE),"host state remains owned by its actual native chunk");
            helper.assertTrue(chunk.getPostProcessing()[chunk.getSectionIndex(position.getY())].isEmpty(),"native postprocessing really completed");
            helper.assertTrue(DeepCubeDomain.generated(level),"completion scope restores generated ownership");
            try {level.getBlockState(position);throw new AssertionError("unknown gameplay cube must remain unavailable");}
            catch(dev.madeinabyss.forge.cube.CubeBlockView.MissingCubeException expected) {}
        } finally {
            DeepCubeDomain.detach(level);
            for(var pending:chunk.getPostProcessing())if(pending!=null)pending.clear();
            level.setBlock(position,before,2);
        }
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void generatedKnownHaloCrossesNativeMinWithoutReplacingSavedNativeBlocks(GameTestHelper helper) {
        var level=helper.getLevel();var old=new BlockPos(5,-64,5);var before=level.getBlockState(old);
        var space=new SparseCubeSpace();
        for(int x=-1;x<=1;x++)for(int y=-6;y<=-2;y++)for(int z=-1;z<=1;z++)space.loadEmpty(new CubeKey(x,y,z));
        var body=EntityType.PIG.create(level);if(body==null)throw new AssertionError("native entity factory");
        body.setNoAi(true);body.setNoGravity(true);body.setPos(5.5,-66,5.5);
        level.setBlock(old,Blocks.BEDROCK.defaultBlockState(),2);
        try {
            DeepCubeDomain.attachGenerated(level,space,false);
            helper.assertTrue(DeepCubeDomain.isDeep(level,old),"accepted generated domain owns known data above native min");
            helper.assertTrue(level.getBlockState(old).isAir(),"generated AIR overrides native bedrock reversibly");
            helper.assertTrue(DeepCubeDomain.collisionColumn(level,null).getBlockState(old).isAir(),"native collision column uses same signed cube state");
            DeepCubeDomain.readNativeHost(level,()->{
                helper.assertTrue(level.getBlockState(old).is(Blocks.BEDROCK),"host packet callback reads actual retained native state");
                helper.assertTrue(DeepCubeDomain.generated(level),"native backing scope must keep the host simulation paused");
            });
            helper.assertTrue(level.getBlockState(old).isAir(),"native callback scope does not leak into gameplay");
            try {DeepCubeDomain.readNativeHost(level,()->{throw new IllegalStateException("packet decode refused");});}
            catch(IllegalStateException expected) {helper.assertTrue(expected.getMessage().equals("packet decode refused"),"original packet failure retained");}
            helper.assertTrue(level.getBlockState(old).isAir(),"failed native packet callback also restores strict cube routing");
            body.move(MoverType.SELF,new Vec3(0,6,0));
            helper.assertTrue(Math.abs(body.getY()+60)<1e-6,"actual native movement crosses -64 in generated known halo");
            helper.assertTrue(DeepCubeDomain.knownGeneratedHalo(level,space,body.getBoundingBox()),"actual moved camera/body halo is known");
            helper.assertTrue(!DeepCubeDomain.knownGeneratedHalo(level,space,new net.minecraft.world.phys.AABB(90,214,-109,90,214,-109)),"old ordinary camera cannot be admitted into a distant generated window");
            var missing=new SparseCubeSpace();DeepCubeDomain.attachGenerated(level,missing,false);
            helper.assertTrue(!DeepCubeDomain.allowsMove(level,body.getBoundingBox()),"unloaded generated coordinates remain unavailable");
            DeepCubeDomain.detach(level);
            helper.assertTrue(level.getBlockState(old).is(Blocks.BEDROCK),"ordinary saved native bedrock retained byte/state authority after detach");
        } finally {DeepCubeDomain.detach(level);body.discard();level.setBlock(old,before,2);}
        helper.succeed();
    }

    @GameTest(template="empty")
    public static void boundedFirstLayerVisualWindowKeepsKnownBottomWithoutInventingUnknownCubes(GameTestHelper helper) {
        var level=helper.getLevel();var center=new CubeKey(0,-75,0);int floor=-80;var token=UUID.randomUUID();
        var expected=DeepVisualNetwork.windowKeys(center).stream().filter(k->k.y()>=floor).collect(java.util.stream.Collectors.toSet());
        helper.assertTrue(expected.size()==99,"independent clipped fixture size");
        var frames=new ArrayList<byte[]>();var biomes=level.registryAccess().registryOrThrow(Registries.BIOME);
        for(var key:expected){var section=new LevelChunkSection(biomes);var buffer=new FriendlyByteBuf(Unpooled.buffer());
            try{CubeSectionPacketCodec.encode(buffer,key,section);var bytes=new byte[buffer.readableBytes()];buffer.readBytes(bytes);frames.add(bytes);}finally{buffer.release();}}
        var window=new DeepVisualNetwork.Window(token,1,true,center,List.of(),List.copyOf(frames),floor);
        try{
            DeepVisualNetwork.validate(window);ClientDeepVisual.begin(token,level);ClientDeepVisual.receive(level,window);
            var scene=ClientDeepVisual.space(level).orElseThrow();
            helper.assertTrue(scene.loadedCubeCount()==99,"known visual scene can stop at its actual lower boundary");
            helper.assertTrue(scene.block(new BlockCoordinate(5,-1200,5)).isPresent(),"first-layer view remains available below old -945 guard");
            helper.assertTrue(!scene.isLoaded(new CubeKey(0,-81,0)),"unknown lower cube is absent, not converted to air");
        }finally{ClientDeepVisual.clear();}
        helper.succeed();
    }
}
