package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.worldgen.GeneratedSkySource;
import dev.madeinabyss.forge.worldgen.ParameterizedTerrainChunkGenerator;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodScalarAuthorityGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void oneBlockEditRecomputesAffectedScalarAndRestoresUnaffectedUpperPage(GameTestHelper h)throws Exception {
        var generator=generator(h);var cube=new CubeKey(-1,-4,-1);var pristine=generator.generateCube(cube).states();
        var baseSource=new GeneratedSkySource(generator,Map.of());
        var visitedSource=new GeneratedSkySource(generator,Map.of(cube,pristine));
        var changed=pristine.clone();int index=(15<<4)|15;
        h.assertTrue(changed[index]==Block.getId(Blocks.AIR.defaultBlockState()),"The single roof hole starts as AIR");
        changed[index]=Block.getId(Blocks.STONE.defaultBlockState());
        var editedSource=new GeneratedSkySource(generator,Map.of(cube,changed));
        var clean=AbyssLodScalarAuthority.create("authority-fixture-world","resource-a",baseSource);
        var visited=AbyssLodScalarAuthority.create("authority-fixture-world","resource-a",visitedSource);
        var edited=AbyssLodScalarAuthority.create("authority-fixture-world","resource-a",editedSource);
        h.assertTrue(clean.globalRevision().equals(visited.globalRevision())&&visited.editedCubes()==0,"Pristine saved visits do not change any authority");
        h.assertTrue(!clean.globalRevision().equals(edited.globalRevision())&&edited.editedCubes()==1,"Every real block edit changes global source revision");
        h.assertTrue(clean.familyIdentity().equals(edited.familyIdentity()),"Same palette and sky ceiling retain the archive family across block edits");
        var lower=box(-1,-65,-1);var upper=box(-17,128,-1);
        var firstLight=new AbyssLodPhysicalLighting(baseSource);var editedLight=new AbyssLodPhysicalLighting(editedSource);
        var oldLower=firstLight.light(lower);var newLower=editedLight.light(lower);
        var oldUpper=firstLight.light(upper);var newUpper=editedLight.light(upper);
        h.assertTrue(oldLower.sky()==15&&newLower.sky()<15,"One roof voxel changes real original32 SKY: before="+oldLower+", after="+newLower);
        h.assertTrue(oldUpper.equals(newUpper),"Edit below the conservative dependency floor cannot change original32 above it");
        var bounds=new TerrainLodColumns.Bounds(-128,-256,-128,127,319,127);
        Path parent=Path.of(System.getProperty("mia.storageRoot", ".local"),"codex","20261013-lod-speed","stage-d","authority-tests");
        Files.createDirectories(parent);var directory=Files.createTempDirectory(parent,"selective-");
        try(var archive=new AbyssLodScalarArchive(directory,clean.familyIdentity(),AbyssLodPhysicalLighting.codeIdentity(),bounds,1000,clean::pageIdentity)) {
            archive.put(lower,oldLower);archive.put(upper,oldUpper);
        }
        try(var archive=new AbyssLodScalarArchive(directory,edited.familyIdentity(),AbyssLodPhysicalLighting.codeIdentity(),bounds,1000,edited::pageIdentity)) {
            h.assertTrue(archive.get(lower)==null,"Affected lower page cannot restore stale original32 after the block edit");
            h.assertTrue(newUpper.equals(archive.get(upper)),"Unaffected upper page restores its exact value from the previous disk archive");
            archive.put(lower,newLower);
        }
        var recreated=new GeneratedSkySource(generator,Map.of(cube,changed));
        var restarted=AbyssLodScalarAuthority.create("authority-fixture-world","resource-a",recreated);
        h.assertTrue(restarted.familyIdentity().equals(edited.familyIdentity())&&restarted.globalRevision().equals(edited.globalRevision()),"Independent source construction does not introduce runtime revision into durable authority");
        try(var archive=new AbyssLodScalarArchive(directory,restarted.familyIdentity(),AbyssLodPhysicalLighting.codeIdentity(),bounds,1000,restarted::pageIdentity)) {
            h.assertTrue(newLower.equals(archive.get(lower))&&newUpper.equals(archive.get(upper)),"Both affected recomputed and unaffected retained pages match a fresh exact evaluator on restart");
        }
        h.assertTrue(!clean.pageIdentity(-128,-128,-128).equals(edited.pageIdentity(-128,-128,-128)),"Signed lower owner page invalidated");
        h.assertTrue(clean.pageIdentity(-128,128,-128).equals(edited.pageIdentity(-128,128,-128)),"Signed upper owner page retained");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void scalarPageAuthorityUsesConservativeSignedBoundaryAndStrictGlobalInputs(GameTestHelper h) {
        var none=metadata("base","revision-a",Map.of());var empty=new AbyssLodScalarAuthority("world","resources","code",none);
        var below=new AbyssLodScalarAuthority("world","resources","code",metadata("base","revision-b",Map.of(new CubeKey(1_000_000,-12,-1_000_000),"edit")));
        var boundary=new AbyssLodScalarAuthority("world","resources","code",metadata("base","revision-c",Map.of(new CubeKey(1_000_000,-11,-1_000_000),"edit")));
        String reference=empty.pageIdentity(-128,-128,-128);
        h.assertTrue(reference.equals(below.pageIdentity(-128,-128,-128)),"Cube maxY=-177 is below the conservative -161 dependency cutoff");
        h.assertTrue(!reference.equals(boundary.pageIdentity(-128,-128,-128)),"Cube maxY=-161 at the signed cutoff invalidates even at distant XZ: original32 is not radius32");
        h.assertTrue(boundary.pageIdentity(-128,0,-128).equals(empty.pageIdentity(-128,0,-128)),"The same edit does not affect owners on the next upper page");
        for(var other:new AbyssLodScalarAuthority[]{
                new AbyssLodScalarAuthority("other-world","resources","code",none),
                new AbyssLodScalarAuthority("world","other-resources","code",none),
                new AbyssLodScalarAuthority("world","resources","other-code",none),
                new AbyssLodScalarAuthority("world","resources","code",metadata("other-palette-or-sky-ceiling","revision-a",Map.of()))})
            h.assertTrue(!reference.equals(other.pageIdentity(-128,-128,-128)),"World, resource, physical algorithm and base palette/ceiling changes invalidate every page");
        var tallBox=new TerrainLodColumns.Box(-1,-128,-1,1024,256,1024,TerrainMaterial.ROCK);
        h.assertTrue(Math.floorDiv(tallBox.minY(),128)==-1&&!reference.equals(boundary.pageIdentity(-128,-128,-128)),"Authority uses owner minimum and remains conservative for boxes spanning multiple pages");
        boolean rejected=false;try{empty.pageIdentity(-1,-128,0);}catch(IllegalArgumentException expected){rejected=true;}
        h.assertTrue(rejected,"Page minima must use floorDiv alignment, including negative coordinates");
        h.succeed();
    }
    private static GeneratedSkySource.CacheAuthority metadata(String base,String revision,Map<CubeKey,String> edits){return new GeneratedSkySource.CacheAuthority(base,revision,edits);}
    private static TerrainLodColumns.Box box(long x,long y,long z){return new TerrainLodColumns.Box(x,y,z,x+1,y+1,z+1,TerrainMaterial.ROCK);}
    private static ParameterizedTerrainChunkGenerator generator(GameTestHelper h){
        var parameters=new TerrainParameters(20261003,new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
                new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),new TerrainParameters.Noise(140,20,60,70));
        return new ParameterizedTerrainChunkGenerator(parameters,new FixedBiomeSource(h.getLevel().registryAccess()
                .registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)),-64,384,
                (x,y,z)->new TerrainSample.Known((y==-64&&(x!=-1||z!=-1))||(x==-1&&z==-1&&y==-65)||(x==-17&&z==-1&&y==128)?TerrainMaterial.ROCK:TerrainMaterial.AIR));
    }
}
