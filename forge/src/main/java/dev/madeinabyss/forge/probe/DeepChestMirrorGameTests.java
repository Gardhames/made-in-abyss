package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepChestClientMirror;
import dev.madeinabyss.forge.probe.deep.DeepChestPackets;
import dev.madeinabyss.forge.cube.ChestBlockEntityCodec;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.BlockCoordinate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeepChestMirrorGameTests {
    private static final BlockPos POS=new BlockPos(-1,-19999,-1);
    @GameTest(template="empty")
    public static void metadataMirrorUsesActualChestAndNativeLidAnimation(GameTestHelper h) throws Exception {
        var space=space();var chest=new ChestBlockEntity(POS,Blocks.CHEST.defaultBlockState());var saved=ChestBlockEntityCodec.encode(chest);
        var metadata=new DeepChestPackets.Mirror(POS,Block.getId(chest.getBlockState()),saved.data());
        var cache=DeepChestClientMirror.prepare(h.getLevel(),space,List.of(metadata));
        try {
            DeepChestClientMirror.publish(h.getLevel(),cache);var nativeChest=DeepChestClientMirror.get(h.getLevel(),POS);
            h.assertTrue(nativeChest.getBlockPos().equals(POS) && nativeChest.getLevel()==h.getLevel(),"native chest mirror retains actual signed position and level");
            nativeChest.triggerEvent(1,1);
            for(int i=0;i<10;i++)ChestBlockEntity.lidAnimateTick(h.getLevel(),POS,nativeChest.getBlockState(),nativeChest);
            h.assertTrue(nativeChest.getOpenNess(1)>.9f,"actual native lid progresses");
            var next=DeepChestClientMirror.prepare(h.getLevel(),space,List.of(metadata));DeepChestClientMirror.publish(h.getLevel(),next);
            h.assertTrue(DeepChestClientMirror.get(h.getLevel(),POS)==nativeChest,"unchanged window reuses animated native instance");
        }finally{DeepChestClientMirror.clear(h.getLevel());}h.succeed();
    }
    @GameTest(template="empty")
    public static void badMirrorCannotReplacePreviouslyPublishedChest(GameTestHelper h) throws Exception {
        var space=space();var chest=new ChestBlockEntity(POS,Blocks.CHEST.defaultBlockState());var saved=ChestBlockEntityCodec.encode(chest);
        var metadata=new DeepChestPackets.Mirror(POS,Block.getId(chest.getBlockState()),saved.data());
        var cache=DeepChestClientMirror.prepare(h.getLevel(),space,List.of(metadata));DeepChestClientMirror.publish(h.getLevel(),cache);
        try {
            var original=DeepChestClientMirror.get(h.getLevel(),POS);boolean rejected=false;
            try{DeepChestClientMirror.prepare(h.getLevel(),space,List.of());}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && DeepChestClientMirror.get(h.getLevel(),POS)==original,"missing chest metadata rejects entire replacement");
            rejected=false;try{DeepChestClientMirror.prepare(h.getLevel(),space,List.of(new DeepChestPackets.Mirror(POS,0,saved.data())));}catch(IllegalArgumentException expected){rejected=true;}
            h.assertTrue(rejected && DeepChestClientMirror.get(h.getLevel(),POS)==original,"wrong state identity rejects entire replacement");
            h.assertTrue(DeepChestClientMirror.get(h.getLevel(),new BlockPos(-2,-19999,-1))==null,"known AIR means absent native chest");
            rejected=false;try{DeepChestClientMirror.get(h.getLevel(),new BlockPos(100,-19999,-1));}catch(IllegalStateException expected){rejected=true;}
            h.assertTrue(rejected,"unknown remains strict");
        }finally{DeepChestClientMirror.clear(h.getLevel());}h.succeed();
    }
    @GameTest(template="empty")
    public static void metadataNameChangesPreserveNativeIdentityAndOpenLid(GameTestHelper h) throws Exception {
        var space=space();var source=new ChestBlockEntity(POS,Blocks.CHEST.defaultBlockState());
        var initial=DeepChestClientMirror.prepare(h.getLevel(),space,List.of(new DeepChestPackets.Mirror(POS,Block.getId(source.getBlockState()),ChestBlockEntityCodec.encode(source).data())));
        DeepChestClientMirror.publish(h.getLevel(),initial);
        try {
            var original=DeepChestClientMirror.get(h.getLevel(),POS);original.triggerEvent(1,1);
            for(int i=0;i<10;i++)ChestBlockEntity.lidAnimateTick(h.getLevel(),POS,original.getBlockState(),original);
            float openness=original.getOpenNess(1);h.assertTrue(openness>.9f,"real native lid fully opened before metadata updates");
            for(String name:new String[]{"Abyss cargo","Renamed cargo",null}) {
                source.setCustomName(name==null?null:Component.literal(name));
                var next=DeepChestClientMirror.prepare(h.getLevel(),space,List.of(new DeepChestPackets.Mirror(POS,Block.getId(source.getBlockState()),ChestBlockEntityCodec.encode(source).data())));
                var temporary=next.chests().get(0);h.assertTrue(temporary!=original,"prepare stages a distinct decoded native candidate");
                DeepChestClientMirror.publish(h.getLevel(),next);var updated=DeepChestClientMirror.get(h.getLevel(),POS);
                h.assertTrue(updated==original && updated.getLevel()==h.getLevel() && !updated.isRemoved(),"same state metadata update retains native identity");
                h.assertTrue(updated.getOpenNess(1)==openness,"metadata update never resets animated lid");
                h.assertTrue(name==null?updated.getCustomName()==null:updated.getCustomName()!=null && updated.getCustomName().getString().equals(name),"custom name add rename and removal applied exactly");
                h.assertTrue(updated.getDisplayName().getString().equals(name==null?Component.translatable("container.chest").getString():name),"native display name reflects update or ordinary fallback");
                h.assertTrue(updated.isEmpty(),"visual metadata update contains no private inventory");
                h.assertTrue(temporary.isRemoved() && temporary.getLevel()==null,"superseded decoded native candidate invalidated");
            }
            original.triggerEvent(1,0);for(int i=0;i<10;i++)ChestBlockEntity.lidAnimateTick(h.getLevel(),POS,original.getBlockState(),original);
            h.assertTrue(original.getOpenNess(1)==0,"same retained native lid closes normally after metadata changes");
        }finally{DeepChestClientMirror.clear(h.getLevel());}h.succeed();
    }
    private static SparseCubeSpace space(){var space=new SparseCubeSpace();int[] states=new int[4096];java.util.Arrays.fill(states,Block.getId(Blocks.AIR.defaultBlockState()));space.load(new CubeSnapshot(new CubeKey(-1,-1250,-1),states));space.setBlock(new BlockCoordinate(-1,-19999,-1),Block.getId(Blocks.CHEST.defaultBlockState()));return space;}
}
