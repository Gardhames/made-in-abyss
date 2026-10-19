package dev.madeinabyss.kernel.terrain.living;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Python's independent scalar implementation owns the expected numbers.
 * Fixtures include negative lattice cells, the angular seam, band and cube edges. */
class LivingAbyssRevision155OracleTest {
    private static final String BASE="/diagnostics/rev155/";
    private static LivingAbyssRevision155 compiled;
    private static Properties properties()throws IOException{var p=new Properties();try(var in=resource("fixture.properties")){p.load(in);}return p;}
    private static InputStream resource(String name){return Objects.requireNonNull(LivingAbyssRevision155OracleTest.class.getResourceAsStream(BASE+name),"Generate oracle fixture "+name);}
    private static List<Map<String,String>> rows(String name)throws IOException{
        try(var in=new BufferedReader(new InputStreamReader(resource(name+".tsv"),StandardCharsets.UTF_8))){
            String[] header=in.readLine().split("\t",-1);var result=new ArrayList<Map<String,String>>();String line;
            while((line=in.readLine())!=null){if(line.isBlank()||line.startsWith("#"))continue;String[] values=line.split("\t",-1);assertEquals(header.length,values.length,line);
                var row=new LinkedHashMap<String,String>();for(int i=0;i<header.length;i++)row.put(header[i],values[i]);result.add(row);}
            assertFalse(result.isEmpty(),name);return result;
        }
    }
    private static double d(Map<String,String> r,String name){return Double.parseDouble(r.get(name));}
    private static int i(Map<String,String> r,String name){return Integer.parseInt(r.get(name));}
    private static synchronized LivingAbyssRevision155 compiled()throws IOException{
        if(compiled==null){var p=properties();long seed=Long.parseLong(p.getProperty("seed"));double wet=Double.parseDouble(p.getProperty("wet"));
            var old=LivingAbyssGeometryTest.recipe(seed);var r=new LivingAbyssRecipe(old.schema(),"15.5-stage-a","RECONSTRUCTION",seed,old.domain(),old.fields(),old.archetypes(),old.newLedges(),old.cascade());
            compiled=new LivingAbyssRevision155(r,new LivingAbyssFields(seed,wet,r.fields().zoneBoundaries(),25,22.5,45));}
        return compiled;
    }
    @Test void profilesPlansSignedNoiseAndQuantilesMatchIndependentNumbers()throws Exception{
        var p=properties();long seed=Long.parseLong(p.getProperty("seed"));
        for(var r:rows("profile"))assertEquals(d(r,"value"),LivingAbyssMath155.profile(i(r,"kind"),d(r,"tau"),i(r,"steps")),2e-12,r.toString());
        for(var r:rows("plan"))assertEquals(d(r,"value"),LivingAbyssMath155.plan(d(r,"projection"),d(r,"s"),d(r,"lob"),d(r,"bay")),2e-10,r.toString());
        for(var r:rows("noise"))assertEquals(d(r,"value"),LivingAbyssMath155.noise(Long.parseLong(r.get("seed")),d(r,"theta"),d(r,"depth"),d(r,"cellX"),d(r,"cellY"),Long.parseLong(r.get("salt")),d(r,"radius")),2e-12,r.toString());
        for(var r:rows("secondaryThreshold"))assertEquals(d(r,"value"),LivingAbyssStrata155.secondaryThreshold(d(r,"theta"),d(r,"d")),2e-9,r.toString());
    }
    @Test void fullStrataSequenceAndSignedFaciesMatchPython()throws Exception{
        var s=compiled().strata();var bands=rows("bands");assertEquals(bands.size(),s.bands().size());
        for(int index=0;index<bands.size();index++){var r=bands.get(index);var b=s.bands().get(index);
            assertEquals(d(r,"top"),b.top(),2e-10);assertEquals(d(r,"bottom"),b.bottom(),2e-10);assertEquals(i(r,"zone"),b.zone());
            assertEquals(r.get("primary"),b.primary());assertEquals(r.get("secondary"),b.secondary());assertEquals(Boolean.parseBoolean(r.get("replace")),b.replace());
            for(int side=0;side<4;side++){assertEquals(d(r,"threshold"+side),b.thresholds().get(side),2e-12);assertEquals(r.get("substitute"+side),b.substitutes().get(side));}
            assertEquals(index,s.bandIndex(b.top()));if(index>0)assertEquals(index-1,s.bandIndex(Math.nextDown(b.top())));
        }
        for(var r:rows("materials"))assertEquals(r.get("material"),s.material(d(r,"theta"),i(r,"x"),i(r,"y"),i(r,"z")),r.toString());
        for(var r:rows("hardness"))assertEquals(d(r,"value"),s.hardness(d(r,"theta"),d(r,"d")),2e-12,r.toString());
    }
    @Test void real3dShapeAndOrientedTipsMatchIndependentReducedListOracle()throws Exception{
        var s=compiled();var shapeRows=rows("shape");assertTrue(shapeRows.size()>3000);
        for(var r:shapeRows)assertEquals(i(r,"kind"),s.shape(d(r,"theta"),d(r,"r"),d(r,"d")),r.toString());
        for(var r:rows("samples"))assertEquals(i(r,"kind"),s.sample(i(r,"x"),i(r,"y"),i(r,"z")),r.toString());
    }
    @Test void vaultsPortholesReliefAndHallBoundariesMatchIndependentOracle()throws Exception{
        var s=compiled();
        for(var r:rows("openingCuts")){var h=s.halls().get(i(r,"hallIndex"));var a=h.arches().get(i(r,"openingIndex"));
            assertEquals(Boolean.parseBoolean(r.get("value")),s.openingCut(a,d(r,"theta"),d(r,"d")),r.toString());}
        for(var r:rows("relief"))assertEquals(d(r,"value"),s.relief(d(r,"theta"),d(r,"d"),d(r,"thickness"),Long.parseLong(r.get("salt"))),2e-9,r.toString());
        for(var r:rows("envelopes"))assertEquals(d(r,"value"),LivingAbyssRevision155.envelope(d(r,"s"),d(r,"v")),2e-12,r.toString());
    }
}
