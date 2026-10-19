package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.client.compat.LodBlockStateMaterials;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.living.LivingAbyssPlan;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/** Regression for the visited/native admission path, rather than a second audit palette. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LivingAbyssLodPaletteGameTests {
    @GameTest(template="empty",batch="aV15Palette",timeoutTicks=1200)
    public static void everyExplicitAndSampledV15StateIsAcceptedByBothNativeAdapters(GameTestHelper h) throws Throwable {
        var generator=LivingAbyssGameTests.profile(h).generator();
        var living=generator.livingAbyss();
        var states=new LinkedHashSet<BlockState>(living.palette());
        states.addAll(LivingAbyssBlockStates.palette());
        states.addAll(Blocks.VINE.getStateDefinition().getPossibleStates());
        states.addAll(IslandSurfaceBlockStates.palette());
        states.addAll(HabitatBlockStates.palette());
        for(var kind:dev.madeinabyss.kernel.terrain.natural.NaturalBlock.values())states.add(NaturalBlockStates.state(kind));
        var parentManifest=LivingAbyssGameTests.parentManifest();
        var parent=new GeneratedPreviewProfile(parentManifest,parentManifest.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h)).generator();
        if(parent.wilderness()!=null)states.addAll(parent.wilderness().palette().palette());
        if(parent.caveExpansion()!=null)states.addAll(parent.caveExpansion().palette());
        if(parent.alexEcology()!=null)states.addAll(parent.alexEcology().palette());
        int explicit=states.size();
        var cubes=new LinkedHashSet<CubeKey>();
        var zones=new HashSet<Integer>();
        int[] depths={60,285,625,975,1250};
        for(int depth:depths)for(int angle=0;angle<360;angle+=30){
            double radians=Math.toRadians(angle);
            double radius=living.geometry().wallBoundaryAt(angle,-depth);
            int x=(int)Math.round((radius+1)*Math.sin(radians)),z=(int)Math.round(-(radius+1)*Math.cos(radians));
            zones.add(living.plan().fields().zone(x,-depth,z));
            add(cubes,x,-depth,z);add(cubes,x,-depth-16,z);
        }
        var archetypes=EnumSet.noneOf(LivingAbyssPlan.Archetype.class);
        for(var ledge:living.plan().ledges())if(ledge.archetype()!=null&&archetypes.add(ledge.archetype())){
            add(cubes,ledge.x(),ledge.y(),ledge.z());
            add(cubes,ledge.x(),ledge.y()-32,ledge.z());
        }
        for(var spire:living.spires().spires()){
            add(cubes,spire.x(),spire.baseY(),spire.z());
            add(cubes,spire.x(),(spire.baseY()+spire.topY())/2,spire.z());
            add(cubes,spire.x(),spire.topY(),spire.z());
            for(var tree:spire.trees())add(cubes,tree.x(),tree.y()+tree.height()-1,tree.z());
        }
        for(var vine:living.green().vines().stream().limit(16).toList()){
            add(cubes,vine.x(),vine.topY(),vine.z());
            add(cubes,vine.x(),vine.topY()-vine.masks().size()+1,vine.z());
        }
        for(int y:new int[]{-1,-120,-1286,-1350,-1400,-1424})add(cubes,-1,y,-1);
        for(int depth:new int[]{120,450,800,1150,1350}){
            add(cubes,-640,-depth,-16);add(cubes,-640,-depth-1,0);
        }
        add(cubes,960,-64,0);add(cubes,-960,-64,-1);add(cubes,-1200,0,-1200);
        for(var cube:cubes)for(int id:generator.generateCube(cube).states())states.add(Block.stateById(id));
        h.assertTrue(zones.size()==5&&archetypes.size()==7,"all five zones and seven ledge archetypes were sampled");
        h.assertTrue(cubes.stream().anyMatch(q->q.x()<0&&q.z()<0)&&cubes.stream().anyMatch(q->q.x()>0&&q.z()>0),"signed cube quadrants included");
        var rejected=new ArrayList<String>();
        for(String adapter:List.of("AbyssLodAdapter","DhPreviewAdapter"))try(var probe=new HeadlessLodAdapterMaterials(adapter)){
            var path=probe.material;
            for(var state:states)try {
                var material=(TerrainMaterial)path.invokeExact(state);
                h.assertTrue(material!=null,"native material is defined for "+state);
                h.assertTrue(material==LodBlockStateMaterials.material(state),adapter+" uses the canonical audit material for "+state);
            }catch(IllegalArgumentException unsupported){rejected.add(adapter+": "+unsupported.getMessage());}
        }
        System.out.println("MIA_V15_LOD_PALETTE explicit="+explicit+" cubes="+cubes.size()+" voxels="+(4096L*cubes.size())+" states="+states.size()+" zones="+zones.size()+" archetypes="+archetypes.size()+" rejected="+rejected);
        h.assertTrue(rejected.isEmpty(),"v15 native palette is not closed: "+rejected);
        System.out.println("MIA_V15_LOD_PALETTE_IDENTITY fingerprint="+LivingAbyssGameTests.profile(h).fingerprint()+" algorithm="+LivingAbyssGeneration.algorithmSha256());
        h.succeed();
    }

    @GameTest(template="empty",batch="aV15Palette",timeoutTicks=1200)
    public static void bothAdaptersRetainMaterialSemanticsAndRefuseUnrelatedStates(GameTestHelper h) throws Throwable {
        // Resolve the author palette on the server owner, just as profile admission does.
        LivingAbyssGameTests.profile(h);
        var expected=new LinkedHashMap<BlockState,TerrainMaterial>();
        expected.put(Blocks.WHITE_TERRACOTTA.defaultBlockState(),TerrainMaterial.ROCK);
        expected.put(Blocks.STONE_BRICKS.defaultBlockState(),TerrainMaterial.ROCK);
        expected.put(Blocks.MOSSY_COBBLESTONE.defaultBlockState(),TerrainMaterial.ROCK);
        expected.put(Blocks.DIRT.defaultBlockState(),TerrainMaterial.SOIL);
        expected.put(Blocks.GRASS_BLOCK.defaultBlockState(),TerrainMaterial.GRASS);
        expected.put(Blocks.MOSS_BLOCK.defaultBlockState(),TerrainMaterial.GRASS);
        expected.put(Blocks.BIRCH_LOG.defaultBlockState(),TerrainMaterial.SOIL);
        expected.put(NaturalBlockStates.state(dev.madeinabyss.kernel.terrain.natural.NaturalBlock.BIRCH_LEAVES),TerrainMaterial.GRASS);
        expected.put(Blocks.GRASS.defaultBlockState(),TerrainMaterial.AIR);
        expected.put(Blocks.FERN.defaultBlockState(),TerrainMaterial.AIR);
        expected.put(Blocks.WATER.defaultBlockState(),TerrainMaterial.WATER);
        for(var vine:Blocks.VINE.getStateDefinition().getPossibleStates())expected.put(vine,TerrainMaterial.GRASS);
        // Independently retain the four historical NaturalBlock plant approximations.
        expected.put(Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH,true),TerrainMaterial.AIR);
        expected.put(Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH,true),TerrainMaterial.AIR);
        expected.put(Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST,true),TerrainMaterial.AIR);
        expected.put(Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST,true),TerrainMaterial.AIR);
        for(String adapter:List.of("AbyssLodAdapter","DhPreviewAdapter"))try(var probe=new HeadlessLodAdapterMaterials(adapter)){
            var path=probe.material;
            for(var entry:expected.entrySet()){
                var material=(TerrainMaterial)path.invokeExact(entry.getKey());
                h.assertTrue(material==entry.getValue(),adapter+" material semantics for "+entry.getKey()+": "+material);
            }
            boolean refused=false;
            try {var ignored=(TerrainMaterial)path.invokeExact(Blocks.DIAMOND_BLOCK.defaultBlockState());}
            catch(IllegalArgumentException unsupported){refused=true;}
            h.assertTrue(refused,adapter+" must still refuse unapproved visited states");
        }
        h.succeed();
    }

    private static void add(Set<CubeKey> cubes,int x,int y,int z){cubes.add(CubeKey.fromBlock(new BlockCoordinate(x,y,z)));}
}
