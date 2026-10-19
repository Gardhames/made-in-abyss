package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import java.util.*;

/** Geometry/light multiset and ordered render input are independent quality gates. */
@PrefixGameTestTemplate(false)
public final class LodSpeedOracleGameTests {
    @GameTest(templateNamespace=MadeInAbyssProbe.MOD_ID,template="empty")
    public static void pageOracleIgnoresOrderButDetectsExactLightGeometryAndProvenance(GameTestHelper h)throws Exception {
        var a=new SurfaceQuadPlan.Owner(new TerrainLodColumns.Box(-129,-2,-1,-128,-1,0,TerrainMaterial.ROCK),7,0,0xff7e7b77);
        var b=new SurfaceQuadPlan.Owner(new TerrainLodColumns.Box(-128,-2,-1,-127,-1,0,TerrainMaterial.ROCK),7,0,0xff7e7b77);
        var face=new SurfaceQuadPlan.Face(0,1,true,-1,-129,-1,-127,0);
        var chunk=new SurfaceQuadPlan.ChunkBounds(-129,-1,-1,-127,-1,0);
        var first=new SurfaceQuadPlan.Prepared(List.of(a,b),List.of(face),List.of(chunk),64,List.of(List.of(0,1)));
        var reordered=new SurfaceQuadPlan.Prepared(List.of(b,a),List.of(new SurfaceQuadPlan.Face(1,1,true,-1,-129,-1,-127,0)),List.of(chunk),64,List.of(List.of(0,1)));
        String original=hash(first);
        h.assertTrue(original.equals(hash(reordered)),"Reordering owner IDs preserves physical provenance and digest");
        h.assertTrue(renderHash(first).equals(renderHash(reordered)),"Transient owner IDs do not change physical render input");
        var darkA=new SurfaceQuadPlan.Owner(a.box(),6,0,a.rgba());var darkB=new SurfaceQuadPlan.Owner(b.box(),6,0,b.rgba());
        h.assertTrue(!original.equals(hash(new SurfaceQuadPlan.Prepared(List.of(darkA,darkB),List.of(face),List.of(chunk),64,List.of(List.of(0,1))))),"One exact SKY level changes digest");
        var litA=new SurfaceQuadPlan.Owner(a.box(),7,3,a.rgba());var litB=new SurfaceQuadPlan.Owner(b.box(),7,3,b.rgba());
        h.assertTrue(!original.equals(hash(new SurfaceQuadPlan.Prepared(List.of(litA,litB),List.of(face),List.of(chunk),64,List.of(List.of(0,1))))),"Exact BLOCK emission changes digest");
        var shifted=new SurfaceQuadPlan.Face(0,1,true,-2,-129,-1,-127,0);
        h.assertTrue(!original.equals(hash(new SurfaceQuadPlan.Prepared(List.of(a,b),List.of(shifted),List.of(chunk),64,List.of(List.of(0,1))))),"One metre of negative Y changes digest");
        h.assertTrue(!original.equals(hash(new SurfaceQuadPlan.Prepared(List.of(a,b),List.of(face),List.of(chunk),64,List.of(List.of(0))))),"Removing physical contributor changes digest even at equal shade");
        var movedChunk=new SurfaceQuadPlan.Prepared(List.of(a,b),List.of(face),List.of(new SurfaceQuadPlan.ChunkBounds(-130,-1,-1,-127,-1,0)),64,List.of(List.of(0,1)));
        h.assertTrue(original.equals(hash(movedChunk))&&!renderHash(first).equals(renderHash(movedChunk)),"Chunk anchor/culling change is visible to the separate render digest");
        var twoFaces=new SurfaceQuadPlan.Prepared(List.of(a,b),List.of(face,shifted),List.of(chunk),128,List.of(List.of(0,1),List.of(0,1)));
        var swappedFaces=new SurfaceQuadPlan.Prepared(List.of(a,b),List.of(shifted,face),List.of(chunk),128,List.of(List.of(0,1),List.of(0,1)));
        h.assertTrue(hash(twoFaces).equals(hash(swappedFaces))&&!renderHash(twoFaces).equals(renderHash(swappedFaces)),"Face order can affect transparency and must remain in the render digest");
        boundedDigestMatchesOriginalUtf8LfIncludingSpill(h);
        h.succeed();
    }
    private static void boundedDigestMatchesOriginalUtf8LfIncludingSpill(GameTestHelper h)throws Exception {
        // Supplementary Unicode sorts before U+E000 in Java String order, but after it
        // in UTF-8 byte order. Cross-run duplicates must collapse exactly like TreeSet.
        var lines=List.of("-10","-1","", "\ue000","\ud83d\ude00","\u0439","-10","0","\u0439","\ue000","a");
        var stream=new LodSpeedCanonicalDigest.Stream();for(var line:lines)stream.add(line);
        h.assertTrue(legacyDigest(lines).equals(stream.finish()),"Streaming keeps original record order, duplicates, UTF-8 encoding and trailing LF");
        var base=java.nio.file.Path.of(System.getProperty("mia.bench.lodOutput",System.getProperty("java.io.tmpdir")));
        java.nio.file.Files.createDirectories(base);var root=java.nio.file.Files.createTempDirectory(base,"oracle-spill-test-");
        try {
            try(var sorted=new LodSpeedCanonicalDigest.SortedUnique(root,2)) {
                for(var line:lines)sorted.add(line);
                h.assertTrue(sorted.spillCount()>1,"Tiny threshold exercises actual cross-file merge");
                var result=sorted.finish();var expected=new TreeSet<>(lines);
                h.assertTrue(result.count()==expected.size()&&result.sha256().equals(legacyDigest(expected)),"Spilled owners equal the original globally sorted distinct UTF-8/LF digest");
            }
            try(var remaining=java.nio.file.Files.list(root)){h.assertTrue(remaining.findAny().isEmpty(),"Successful digest removes only its own temporary files and directory");}
            try {
                try(var sorted=new LodSpeedCanonicalDigest.SortedUnique(root,2)) {
                    sorted.add("negative=-2");sorted.add("negative=-1");
                    h.assertTrue(sorted.spillCount()==1,"Failure cleanup has a real spill to remove");
                    throw new java.io.IOException("controlled oracle failure");
                }
            } catch(java.io.IOException expected) {h.assertTrue(expected.getMessage().equals("controlled oracle failure"),"Cleanup preserves the original failure");}
            try(var remaining=java.nio.file.Files.list(root)){h.assertTrue(remaining.findAny().isEmpty(),"Failure closes and removes known spill files");}
            try(var sorted=new LodSpeedCanonicalDigest.SortedUnique(root,2)) {
                var result=sorted.finish();h.assertTrue(result.count()==0&&result.sha256().equals(legacyDigest(List.of())),"Empty owner set keeps the original empty SHA-256 digest");
            }
        } finally {java.nio.file.Files.deleteIfExists(root);}
    }
    private static String legacyDigest(Collection<String> lines)throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        for(var line:lines){digest.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)'\n');}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String hash(SurfaceQuadPlan.Prepared prepared)throws Exception {
        return oracle(prepared).get("pagesSha256").getAsString();
    }
    private static String renderHash(SurfaceQuadPlan.Prepared prepared)throws Exception {
        return oracle(prepared).get("orderedRenderSha256").getAsString();
    }
    private static com.google.gson.JsonObject oracle(SurfaceQuadPlan.Prepared prepared)throws Exception {
        int count=prepared.faces().size();
        var pages=new SurfaceQuadPlan.PageSet(List.of(new SurfaceQuadPlan.Page(new SurfaceQuadPlan.PageKey(-1,-1,-1),prepared)),64L*count,76L*count,count,count,0);
        var proposal=new AbyssLodPipeline.Proposal(new AbyssLodPipeline.Target("oracle",1,"source",1,1,1,1),1,AbyssLodPipeline.Stage.COMPLETE,
                List.of(),List.of(),List.of(),List.of(),16,0,true,prepared,pages);
        return LodSpeedGameTests.oracle(proposal);
    }
}
