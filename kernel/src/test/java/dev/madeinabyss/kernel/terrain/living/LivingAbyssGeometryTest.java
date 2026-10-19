package dev.madeinabyss.kernel.terrain.living;
import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class LivingAbyssGeometryTest {
    static LivingAbyssRecipe recipe(long seed) {
        return new LivingAbyssRecipe(1,"15.1-part1","RECONSTRUCTION",seed,new LivingAbyssRecipe.Domain(-1424,-1350,-1400,380,-120),
            new LivingAbyssRecipe.Fields(new double[]{120,450,800,1150,1350},25,22.5,45,20),
            new LivingAbyssRecipe.Archetypes(new double[]{20,18,14,14,14,8,12},new double[][]{{.8,1,2.5,1,1.2,1,1},{1,1,1,1,1.8,1.5,1.5},{1.8,.6,.5,1.5,1,1,1},{.7,2.5,1,1,1,1,1}},
                new double[]{20,0,0,0,45,0,35},new double[]{0,50,0,0,20,0,30},.3,2,3,new int[]{2,5},new int[]{3,5},new int[]{2,4},new int[]{2,6},new int[]{6,12}),
            new LivingAbyssRecipe.NewLedges(1.15,.4),new LivingAbyssRecipe.Cascade(new int[]{4,5},140,new int[]{250,520},6,150,4,new int[]{880,1030},new int[]{6,14},"SUPPORTED_BOWL_OR_CAVE_DRAIN"));
    }
    static TerrainParameters parameters(long seed) {return new TerrainParameters(seed,
        new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
        new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),new TerrainParameters.Noise(140,20,60,70));}
    static LivingAbyssPlan build(long seed) {
        var p=parameters(seed);var old=new CompiledTerrainSampler(p);var c=p.cavity();
        var wall=new CompiledTerrainSampler(new TerrainParameters(seed,p.surface(),new TerrainParameters.Cavity(500,64,-1286,650,180,170,0),p.noise()));
        LivingAbyssPlan.Radius opening=(x,y,z)->wall.cavityRadius(x,y,z)-LivingAbyssPlan.retention(y)*(wall.cavityRadius(x,y,z)-old.cavityRadius(x,y,z));
        var f=new LivingAbyssFields(seed,20,new double[]{120,450,800,1150,1350},25,22.5,45);
        return new LivingAbyssPlan(p,recipe(seed),f,(x,y,z)->Math.hypot(x,z)>=opening.at(x,y,z),opening,wall::cavityRadius);
    }
    @Test void deterministicCatalogueAndAllSevenArchetypesWithActualAdjacency() {
        var a=build(20261003);var b=build(20261003);assertEquals(a.digest(),b.digest());assertEquals(a.ledges(),b.ledges());
        assertTrue(a.ledges().size()>50);for(var type:LivingAbyssPlan.Archetype.values())assertTrue(a.ledges().stream().anyMatch(q->q.archetype()==type),type.name());
        var ids=new HashMap<String,LivingAbyssPlan.Ledge>();a.ledges().forEach(q->{assertFalse(ids.containsKey(q.id()));ids.put(q.id(),q);});
        for(var n:a.neighbours()){var x=ids.get(n.upper());var y=ids.get(n.lower());assertTrue(x.archetype()!=y.archetype()||(x.cascade()&&y.cascade()));}
        long cascades=a.ledges().stream().filter(LivingAbyssPlan.Ledge::cascade).count();assertTrue(cascades>=4&&cascades<=5);
    }
    @Test void signedEmptyCenterBottomHaloAndWorkerCacheCannotAlterGeometry() throws Exception {
        for(long seed:new long[]{0,-20261003}) {
            var p=parameters(seed);var plan=build(seed);var g=new LivingAbyssGeometry(p,plan);
            for(int boundaryY:new int[]{-120,-121,-1349,-1350})for(int x:new int[]{-379,-1,0,1,379})
                assertEquals(LivingAbyssGeometry.Kind.AIR,g.sample(x,boundaryY,0),"inclusive signed R380 domain endpoint");
            for(int y=-120;y>=-1350;y-=13)for(int angle=0;angle<360;angle+=5){double t=Math.toRadians(angle);int x=(int)(379*Math.sin(t)),z=(int)(-379*Math.cos(t));assertEquals(LivingAbyssGeometry.Kind.AIR,g.sample(x,y,z));}
            for(int x:new int[]{-959,-500,-1,0,1,500,959})for(int z:new int[]{-959,-1,0,1,959}){
                assertEquals(LivingAbyssGeometry.Kind.ROCK,g.sample(x,-1424,z));assertThrows(IllegalArgumentException.class,()->g.sample(x,-1425,z));
                assertTrue(g.floorTop(x,z)>=-1408&&g.floorTop(x,z)<=-1392);
            }
            var points=new ArrayList<int[]>();var random=new Random(827);for(int i=0;i<1200;i++)points.add(new int[]{random.nextInt(1441)-720,-120-random.nextInt(1230),random.nextInt(1441)-720});
            var expected=points.stream().map(q->g.sample(q[0],q[1],q[2])).toList();var pool=Executors.newFixedThreadPool(2);
            try {for(var result:pool.invokeAll(List.of(()->points.stream().map(q->g.sample(q[0],q[1],q[2])).toList(),()->points.stream().map(q->g.sample(q[0],q[1],q[2])).toList())))assertEquals(expected,result.get());}
            finally {pool.shutdownNow();}
        }
    }
    @Test void recipesDefensivelyOwnWeightsAndRejectInvalidRanges() {
        var a=recipe(827).archetypes();double[] w=a.base();w[0]=100000;assertEquals(20,a.base()[0]);double[][] m=a.sideMultipliers();m[0][0]=99;assertEquals(.8,a.sideMultipliers()[0][0]);
        assertThrows(IllegalArgumentException.class,()->new LivingAbyssRecipe.Domain(-1286,-1350,-1400,380,-120));
        double[] weights=LivingAbyssPlan.weights(3,new double[]{.5,.5,0,0},a);assertEquals(0,weights[1]);assertEquals(0,weights[2]);assertTrue(weights[4]>0);
    }
}
