package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.DeepCubeDomain;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.SparseCubeSpace;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Actual vanilla item callbacks; neither a synthetic motion integrator nor a rendered proxy. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NativeItemEntityGameTests {
    @GameTest(template="empty")
    public static void unknownWholeNativeTickLeavesPositionMotionAndNbtUntouched(GameTestHelper h) {
        var level=h.getLevel(); var space=new SparseCubeSpace();
        space.loadEmpty(new CubeKey(0,-1250,0)); DeepCubeDomain.attach(level,space,false);
        var item=new ItemEntity(level,15.9,-19999.5,15.9,new ItemStack(Items.DIAMOND,17));
        item.setDeltaMovement(new Vec3(.25,-.2,.25)); item.setUnlimitedLifetime(); item.setNeverPickUp();
        var before=nativeTag(item);
        try {
            boolean threw=false; try { item.tick(); } catch(RuntimeException error) { threw=true; }
            h.assertTrue(!threw,"whole ItemEntity.tick must preflight UNKNOWN before native fluid/collision reads");
            h.assertTrue(before.equals(nativeTag(item)),"UNKNOWN tick preserves native Pos/Motion/Age and complete saved NBT");
        } finally { item.discard(); DeepCubeDomain.detach(level); }
        h.succeed();
    }

    @GameTest(template="empty")
    public static void actualNativeGravityLandsOnDeepNegativeCoordinateFloor(GameTestHelper h) {
        var level=h.getLevel(); var space=new SparseCubeSpace();
        for(int x=-1;x<=1;x++) for(int y=-1251;y<=-1249;y++) for(int z=-1;z<=1;z++) space.loadEmpty(new CubeKey(x,y,z));
        for(int x=-16;x<16;x++) for(int z=-16;z<16;z++) space.setBlock(new BlockCoordinate(x,-20000,z),Block.getId(Blocks.STONE.defaultBlockState()));
        DeepCubeDomain.attach(level,space,false);
        var item=new ItemEntity(level,-.5,-19996.25,-.5,new ItemStack(Items.DIAMOND,17));
        item.setDeltaMovement(Vec3.ZERO); item.setUnlimitedLifetime(); item.setNeverPickUp();
        try {
            for(int i=0;i<60;i++) item.tick();
            h.assertTrue(item.onGround() && Math.abs(item.getY()+19999)<1e-7,"native gravity and collision settle on actual Y-20000 block top");
            h.assertTrue(item.getX()<0 && item.getZ()<0 && item.getItem().getCount()==17 && !item.isRemoved(),"negative XYZ remains real and stack is neither lost nor duplicated");
            h.assertTrue(level.getEntity(item.getUUID())==null,"manual native callback fixture is not registered in vanilla column persistence");
        } finally { item.discard(); DeepCubeDomain.detach(level); }
        h.succeed();
    }
    private static CompoundTag nativeTag(ItemEntity item) { return item.saveWithoutId(new CompoundTag()); }
}
