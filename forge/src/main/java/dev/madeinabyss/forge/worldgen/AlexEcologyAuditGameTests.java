package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonParser;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.file.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AlexEcologyAuditGameTests {
    @GameTest(template="empty",timeoutTicks=2400)
    public static void optionalActualEcologyProfileAudit(GameTestHelper h) throws Exception {
        String configured=System.getenv("MIA_ALEX_ECOLOGY_AUDIT_PROFILE");
        if(configured==null||configured.isBlank()){h.succeed();return;}
        Path file=Path.of(configured).toAbsolutePath();
        var manifest=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        h.assertTrue(manifest.has("alexEcology"),"explicit ecology profile required");
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var actual=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),biomes);
        String baselineFile=System.getenv("MIA_ALEX_ECOLOGY_BASELINE_PROFILE");
        var before=baselineFile==null||baselineFile.isBlank()?manifest.deepCopy():JsonParser.parseString(Files.readString(Path.of(baselineFile))).getAsJsonObject();
        before.remove("alexEcology");
        if(baselineFile==null||baselineFile.isBlank())before.addProperty("profileId","first-layer-v11-caves");
        var baseline=new GeneratedPreviewProfile(before,before.get("profileId").getAsString(),biomes);
        h.assertTrue(!actual.relativeStoreRoot().equals(baseline.relativeStoreRoot()),"new ecology namespace must differ from legacy caves");
        AlexEcologyDiagnostics.export(actual,baseline,h.getLevel(),file.getParent().resolve("audit"));
        h.succeed();
    }
}
