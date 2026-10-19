package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Isolated opt-in namespace: measure the actual generator without unrelated tests. */
@GameTestHolder("madeinabyss_a2")
@PrefixGameTestTemplate(false)
public final class LivingAbyssA2GameTests {
    @GameTest(template="empty",timeoutTicks=1200)
    public static void exactCubeShaThenPairedWallCost(GameTestHelper h)throws Exception {
        if(System.getProperty("mia.bench.v15A2Mode","").equals("aircert")){LivingAbyssAirCertificateGameTests.exhaustiveAudit(h);return;}
        if(System.getProperty("mia.bench.v15A2Mode","").equals("tile-cancel")){GeneratedExactTileGameTests.cancelledPreparedTileDoesNotPublishAndThreadScopeCloses(h);return;}
        if(System.getProperty("mia.bench.v15A2Mode","").equals("cover")){LivingAbyssPart3aGameTests.nativeGroundCoverSurvivesOnItsActualSupportAndKeepsDoubleHalves(h);return;}
        if(System.getProperty("mia.bench.v15A2Mode","").equals("traits")){LivingAbyssPart3aGameTests.nativeLeafDistanceUpdatesAndDripFrustumsRemainInTheExplicitPalette(h);return;}
        if(System.getProperty("mia.bench.v15A2Mode","").equals("atlas")){LivingAbyssAtlasGameTests.atlas(h);return;}
        if(System.getProperty("mia.bench.v15A2Mode","").equals("cost")){LivingAbyssCostGameTests.exportV15AndMeasureZonesAndArchetypesAgainstV14(h);return;}
        if(System.getProperty("mia.bench.v15A2Mode","compare").equals("lod")){LivingAbyssPart2GameTests.optionalFullShaftGeometryAndExactOwnerWorkloadAudit(h);return;}
        var profile=LivingAbyssGameTests.profile(h);var g=profile.generator();var living=g.livingAbyss();
        var root=Path.of(System.getProperty("mia.bench.v15Output"));Files.createDirectories(root);
        var mode=System.getProperty("mia.bench.v15A2Mode","compare");var reference=Path.of(System.getProperty("mia.bench.v15A2Reference"));
        var keys=new LinkedHashSet<CubeKey>();
        for(int y:new int[]{-16,-128,-320,-624,-976,-1296})for(int angle:new int[]{0,90,180,270,359}){
            double a=Math.toRadians(angle);int x=(int)(650*Math.sin(a)),z=(int)(-650*Math.cos(a));double r=living.geometry().radius(x,y,z);
            x=(int)Math.round((r+1)*Math.sin(a));z=(int)Math.round(-(r+1)*Math.cos(a));keys.add(CubeKey.fromBlock(new BlockCoordinate(x,y,z)));}
        for(var q:living.plan().ledges())if(keys.size()<45)keys.add(CubeKey.fromBlock(new BlockCoordinate(q.x(),q.y(),q.z())));
        for(var q:living.spires().spires())if(keys.size()<55)keys.add(CubeKey.fromBlock(new BlockCoordinate(q.x(),(q.topY()+q.baseY())/2,q.z())));
        // Extra diagonal and centre/domain seams, beyond the previous 55 cubes.
        keys.add(new CubeKey(26,-20,-27));keys.add(new CubeKey(-28,-40,-28));keys.add(new CubeKey(-1,-9,-1));keys.add(new CubeKey(0,-84,0));
        var rows=new JsonArray();
        int equivalence=0;
        for(var key:keys){var digest=java.security.MessageDigest.getInstance("SHA-256");var bytes=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest));var states=g.generateCube(key).states();
            if(equivalence++<8)for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++)h.assertTrue(states[(ly<<8)|(lz<<4)|lx]==Block.getId(g.sampleBlock(key.x()*16+lx,key.y()*16+ly,key.z()*16+lz)),"prepared/scalar block equality across signed cube seams");
            for(int id:states)bytes.writeUTF(Block.stateById(id).toString());bytes.flush();
            var row=new JsonObject();row.addProperty("x",key.x());row.addProperty("y",key.y());row.addProperty("z",key.z());row.addProperty("sha256",HexFormat.of().formatHex(digest.digest()));rows.add(row);}
        var result=new JsonObject();result.addProperty("cubeCount",rows.size());result.addProperty("blockCount",4096*rows.size());result.add("cubes",rows);
        Files.writeString(root.resolve("cube-sha.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
        if(mode.equals("record")||mode.equals("final-audit")){if(Files.exists(reference))throw new IllegalStateException("Refusing to overwrite cube baseline "+reference);Files.createDirectories(reference.getParent());Files.writeString(reference,result.toString());}
        else {var original=JsonParser.parseString(Files.readString(reference)).getAsJsonObject();h.assertTrue(original.getAsJsonArray("cubes").equals(rows),"SHA of all original 55+ cubes must be exact BEFORE benchmarking");}
        System.out.println("MIA_V15_A2_SHA mode="+mode+" cubes="+rows.size()+" blocks="+(4096*rows.size()));
        if(mode.equals("final-audit")){
            var fresh=new GeneratedPreviewProfile(LivingAbyssGameTests.manifest(),"first-layer-v15-living-abyss",LivingAbyssGameTests.biomes(h)).generator();
            var reverse=new ArrayList<>(keys);Collections.reverse(reverse);
            for(var key:reverse){var digest=java.security.MessageDigest.getInstance("SHA-256");var bytes=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest));
                for(int id:fresh.generateCube(key).states())bytes.writeUTF(Block.stateById(id).toString());bytes.flush();var actual=HexFormat.of().formatHex(digest.digest());
                var expected=rows.asList().stream().map(JsonElement::getAsJsonObject).filter(q->q.get("x").getAsInt()==key.x()&&q.get("y").getAsInt()==key.y()&&q.get("z").getAsInt()==key.z()).findFirst().orElseThrow();
                h.assertTrue(actual.equals(expected.get("sha256").getAsString()),"fresh sampler and reverse cube order must match new v15 SHA base");}
            System.out.println("MIA_V15_FINAL_SHA freshSamplerReverseOrder="+rows.size()+" blocks="+(4096*rows.size()));
            LivingAbyssPart2GameTests.optionalFullShaftGeometryAndExactOwnerWorkloadAudit(h);return;
        }
        LivingAbyssCostGameTests.exportV15AndMeasureZonesAndArchetypesAgainstV14(h);
    }
}
