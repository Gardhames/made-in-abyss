package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.TerrainLodClipper;
import dev.madeinabyss.kernel.lod.TerrainLodRefinement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodCompletionSealGameTests {
    @GameTest(template="empty",timeoutTicks=200)
    public static void durableCompletionSealRejectsChangedContentViewAndCorruption(GameTestHelper h)throws Exception {
        var parent=Path.of(System.getProperty("mia.storageRoot", ".local"),"codex","20261013-lod-speed","stage-d","seal-tests");
        Files.createDirectories(parent);var directory=Files.createTempDirectory(parent,"authority-");
        var input=AbyssLodStreamingGameTests.input(1_000_000);
        var seal=new AbyssLodCompletionSeal(directory,"resource-and-world-family","global-edit-revision","geometry-code");
        h.assertTrue(!seal.contains(input),"No completion may be inferred from an empty archive");seal.save(input);
        h.assertTrue(Files.size(seal.file(input))<256,"Seal uses the tiny existing empty TerrainLodDiskCache entry, not a parallel scene format");
        var fresh=new AbyssLodCompletionSeal(directory,"resource-and-world-family","global-edit-revision","geometry-code");
        h.assertTrue(fresh.contains(input),"Independent helper restores persisted completion");
        var runtime=new AbyssLodPipeline.Target("different-runtime",42,input.target().sourceIdentity(),90,91,92,93);
        h.assertTrue(fresh.contains(copy(input,runtime,input.camera(),input.bands(),input.nativeCuts())),"Stable content survives session and runtime counter changes");
        for(var other:List.of(
                new AbyssLodCompletionSeal(directory,"different-resource-or-world","global-edit-revision","geometry-code"),
                new AbyssLodCompletionSeal(directory,"resource-and-world-family","edited-source","geometry-code"),
                new AbyssLodCompletionSeal(directory,"resource-and-world-family","global-edit-revision","changed-algorithm")))
            h.assertTrue(!other.contains(input),"Resource/world, edits and code strictly invalidate completed-view authority");
        var camera=new AbyssLodPipeline.Camera(-.25,input.camera().y(),input.camera().z(),-1,0,0);
        h.assertTrue(!fresh.contains(copy(input,input.target(),camera,input.bands(),input.nativeCuts())),"Exact signed fractional position and direction identify the view");
        h.assertTrue(!fresh.contains(copy(input,input.target(),input.camera(),List.of(new TerrainLodRefinement.Band(256,2)),input.nativeCuts())),"Requested detail bands identify the view");
        h.assertTrue(!fresh.contains(copy(input,input.target(),input.camera(),input.bands(),List.of(new TerrainLodClipper.BoundsLong(0,0,0,1,1,1)))),"Native exclusion geometry identifies the view");
        var source=new AbyssLodPipeline.Target("same-runtime",1,"different-immutable-source",0,0,0,1);
        h.assertTrue(!fresh.contains(copy(input,source,input.camera(),input.bands(),input.nativeCuts())),"Exact source identity cannot be substituted");
        var bytes=Files.readAllBytes(seal.file(input));bytes[bytes.length-1]^=1;Files.write(seal.file(input),bytes);
        h.assertTrue(!new AbyssLodCompletionSeal(directory,"resource-and-world-family","global-edit-revision","geometry-code").contains(input),"Checksum failure cannot suppress exact fallback");
        seal.save(input);Files.write(seal.file(input),new byte[]{1,2,3});
        h.assertTrue(!new AbyssLodCompletionSeal(directory,"resource-and-world-family","global-edit-revision","geometry-code").contains(input),"Truncated marker cannot suppress exact fallback");
        h.succeed();
    }
    private static AbyssLodPipeline.Input copy(AbyssLodPipeline.Input input,AbyssLodPipeline.Target target,AbyssLodPipeline.Camera camera,
            List<TerrainLodRefinement.Band> bands,List<TerrainLodClipper.BoundsLong> cuts){
        return new AbyssLodPipeline.Input(target,camera,input.nearBounds(),input.worldBounds(),bands,cuts,input.terrain(),input.lighting(),input.limits());
    }
}
