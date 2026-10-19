package dev.madeinabyss.kernel.terrain.living;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LivingAbyssWallTest {
    @Test void exactWallFieldAndMaterialDigestMatchesApprovedRevision153() throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest));
        for(long seed:new long[]{20261003,-20261003}){var r=recipe(seed);var wall=new LivingAbyssWall(seed,650,r.wall());
            for(int i=0;i<12000;i++){double theta=(i*17.071)%720-360,depth=(i*13.125)%1400-25;int zone=i%5;
                out.writeDouble(wall.protrusion(theta,depth));for(double w:wall.weights(zone,theta,depth))out.writeDouble(w);
                int x=(int)Math.round(650*Math.sin(Math.toRadians(theta))),z=(int)Math.round(-650*Math.cos(Math.toRadians(theta)));
                out.writeUTF(wall.material(zone,x,-(int)Math.round(depth),z));}
        }out.flush();String actual=java.util.HexFormat.of().formatHex(digest.digest());
        // Rebased from v15.2 after the explicitly approved section21 weights/patch pick.
        assertEquals("da97dd957bf0510fde7a6507eb7d557ec431b42844c4d3a4634c7d5aaf699563",actual,"exact field and choices across signed/zone/lattice boundaries");
    }
    static LivingAbyssRecipe recipe(long seed){
        var old=LivingAbyssGeometryTest.recipe(seed);
        var palettes=List.of(palette("stone",36,"andesite",32,"calcite",6,"dripstone_block",7,"white_terracotta",2,"mossy_cobblestone",12),
            palette("stone",36,"andesite",32,"calcite",6,"dripstone_block",7,"white_terracotta",2,"tuff",7,"mossy_cobblestone",10),
            palette("stone",35,"tuff",25,"andesite",15,"calcite",10,"dripstone_block",8,"moss_block",7),
            palette("stone",80,"tuff",20,"deepslate",0),palette("stone",50,"tuff",10,"deepslate",40));
        return new LivingAbyssRecipe(1,"15.2-part2","RECONSTRUCTION",seed,old.domain(),old.fields(),old.archetypes(),old.newLedges(),old.cascade(),
            new LivingAbyssRecipe.Wall(6,20,1.2,18,8,3.5,60,8,90,10,5,16,30,34,10,9,5,palettes),
            new LivingAbyssRecipe.Curtains(20,40,6,30,7,35,.18,5,9,3,3,6,1.5,.5),
            new LivingAbyssRecipe.Spires(new int[]{12,45},new int[]{40,220},new int[]{10,140},new int[]{2,5},new double[]{.3,1,1.5},2.5,1.5,.82,12),
            new LivingAbyssRecipe.BiomeColors("#5E8F34","#4F8030","#3F9FB0"));
    }
    private static Map<String,Double> palette(Object... entries){var p=new LinkedHashMap<String,Double>();for(int i=0;i<entries.length;i+=2)p.put((String)entries[i],((Number)entries[i+1]).doubleValue());return p;}
    @Test void wrappedFieldHasNoAngularSeamAtAnySignedDepth(){
        for(long seed:new long[]{0,-20261003}){
            var wall=new LivingAbyssWall(seed,650,recipe(seed).wall());double min=Double.POSITIVE_INFINITY,max=0;
            for(double y=-32;y<1400;y+=7.25){assertEquals(wall.protrusion(0,y),wall.protrusion(360,y),1e-12);
                assertEquals(wall.protrusion(-.000001,y),wall.protrusion(359.999999,y),1e-12);
                assertTrue(Math.abs(wall.protrusion(.000001,y)-wall.protrusion(359.999999,y))<.0001);
                double h=wall.protrusion(y,y);assertTrue(h>=0&&h<=23.7);min=Math.min(min,h);max=Math.max(max,h);
            }assertTrue(max-min>8,"non-vacuous slabs and buttresses");
        }
    }
    @Test void perBlockChoicesAreRepeatableAcrossNegativeCubeBoundaries(){
        var r=recipe(-20261003);var a=new LivingAbyssWall(r.seed(),650,r.wall());var b=new LivingAbyssWall(r.seed(),650,r.wall());
        var points=new ArrayList<int[]>();for(int x=-33;x<=1;x++)for(int z=-641;z<=-623;z++)for(int y=-337;y<=-319;y++)points.add(new int[]{x,y,z});
        var expected=points.stream().map(p->a.material(1,p[0],p[1],p[2])).toList();Collections.reverse(points);
        var actual=points.stream().map(p->b.material(1,p[0],p[1],p[2])).toList();Collections.reverse(actual=new ArrayList<>(actual));assertEquals(expected,actual);assertTrue(new HashSet<>(expected).size()>=6);
    }
    @Test void actualPaletteSharesMatchRevision153PythonScalarOracleOnSameFixedSamples() throws Exception {
        String fixture;
        try(var in=getClass().getResourceAsStream("/terrain/living/wall-material-oracle.tsv")){fixture=new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        var reference=java.nio.file.Path.of("../tools/landscape/v15_wall_proto.py");
        var sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(reference)));
        assertEquals("# prototype-sha256="+sha,fixture.lines().findFirst().orElseThrow(),"oracle must come from the actual current Claude prototype");
        assertTrue(fixture.contains("# width=4084 height=1350 arc-step=8 depth-step=2"));
        long[] seeds={0,1,15,1502,20261003,20261013,2147483647L,4294967295L};int[] lo={0,120,450,800,1150},hi={120,450,800,1150,1350};
        var expected=new ArrayList<Map<String,Double>>();for(int zone=0;zone<5;zone++)expected.add(new TreeMap<>());
        for(String line:fixture.lines().filter(s->!s.startsWith("#")&&!s.isBlank()).toList()){var parts=line.split("\t");expected.get(Integer.parseInt(parts[0])).put(parts[1],Double.parseDouble(parts[2]));}
        var failures=new ArrayList<String>();
        for(int zone=0;zone<5;zone++){
            var count=new TreeMap<String,Long>();long points=0;
            for(long seed:seeds){var r=recipe(seed);var wall=new LivingAbyssWall(seed,650,r.wall());
                for(int arc=0;arc<4084;arc+=8){double theta=arc*2*Math.PI/4084;int x=(int)Math.floor(650*Math.sin(theta)+.5),z=(int)Math.floor(-650*Math.cos(theta)+.5);
                    for(int depth=lo[zone];depth<hi[zone];depth+=2){count.merge(wall.material(zone,x,-depth,z),1L,Long::sum);points++;}}
            }
            for(var item:expected.get(zone).entrySet()){double actual=100*count.getOrDefault(item.getKey(),0L)/(double)points,delta=Math.abs(actual-item.getValue());
                System.out.printf(Locale.ROOT,"MIA_V15_MATERIAL zone=%d material=%s points=%d python=%.6f kernel=%.6f deltaPP=%.6f%n",zone,item.getKey(),points,item.getValue(),actual,delta);
                if(delta>3)failures.add("zone "+zone+" "+item.getKey()+" Python="+item.getValue()+" kernel="+actual);
            }assertEquals(expected.get(zone).keySet(),count.keySet(),"no extra/absent palette material");
        }assertTrue(failures.isEmpty(),String.join("; ",failures));
    }
    @Test void vaneSupportRejectsHangingAirAndChecksEachFaceAtSignedPositions(){
        LivingAbyssPlan.Solid support=(x,y,z)->x==-17&&y>=-40&&y<=-32&&z==-16;
        assertEquals(8,LivingAbyssGreenPlan.supportMask(support,-18,-33,-16));assertEquals(0,LivingAbyssGreenPlan.supportMask(support,-18,-41,-16));
    }
}
