package dev.madeinabyss.kernel.terrain.natural;

import dev.madeinabyss.kernel.terrain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CliffTerrainSamplerTest {
    private static TerrainParameters parameters() {
        return new TerrainParameters(20261008,
            new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
            new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),
            new TerrainParameters.Noise(140,20,60,70));
    }
    private CliffTerrainSampler sampler(CliffTerrainSampler.Protection mask) {
        var p=parameters(); var base=new ParametricTerrainSampler(p);
        return new CliffTerrainSampler(p,CliffParameters.proposal(p.seed()),base::sample,mask);
    }
    @Test void coherentWeatheringReachesEveryAzimuthAndIncludesShallowPockets() {
        var p=parameters();var base=new ParametricTerrainSampler(p);var cliff=sampler((x,y,z)->false);
        int pockets=0,fractures=0,changed=0;
        for(int sector=0;sector<16;sector++) {
            double angle=sector*2*Math.PI/16; int sectorChanges=0;
            for(int y=-80;y>=-1200;y-=3) {
                int rx=(int)Math.round(550*Math.cos(angle)), rz=(int)Math.round(550*Math.sin(angle));
                double radius=base.cavityRadius(rx,y,rz);
                int x=(int)Math.round((radius+1)*Math.cos(angle)),z=(int)Math.round((radius+1)*Math.sin(angle));
                var feature=cliff.featureAt(x,y,z);
                if(feature.pocket()>0.25)pockets++;
                if(feature.fracture()>0.5)fractures++;
                if(!base.sample(x,y,z).equals(cliff.sample(x,y,z)))sectorChanges++;
                assertTrue(feature.recessMetres()>=0 && feature.recessMetres()<=CliffParameters.proposal(p.seed()).maximumRecessMetres());
            }
            assertTrue(sectorChanges>5,"weathering absent at azimuth "+sector);
            changed+=sectorChanges;
        }
        assertTrue(pockets>20,"shallow coherent pocket alcoves missing");
        assertTrue(fractures>20,"joint fractures missing");
        assertTrue(changed>100);
    }
    @Test void mouthUnknownAllProtectedCoordinatesAndNonrockRemainExact() {
        var p=parameters();var base=new ParametricTerrainSampler(p);var protectedAll=sampler((x,y,z)->true);
        var cliff=sampler((x,y,z)->false);
        for(int y:new int[]{Integer.MIN_VALUE,-1287,-1286,-1000,-400,32,63,64,65,Integer.MAX_VALUE})
            for(int x:new int[]{-650,-500,0,500,650,Integer.MAX_VALUE}) {
                assertEquals(base.sample(x,y,0),protectedAll.sample(x,y,0));
                if(y>=32 || y<-1286)assertEquals(base.sample(x,y,0),cliff.sample(x,y,0));
            }
        for(TerrainMaterial material:new TerrainMaterial[]{TerrainMaterial.GRASS,TerrainMaterial.SOIL,TerrainMaterial.WATER,TerrainMaterial.AIR}) {
            var original=new TerrainSample.Known(material);
            var wrapped=new CliffTerrainSampler(p,CliffParameters.proposal(p.seed()),(x,y,z)->original,(x,y,z)->false);
            assertSame(original,wrapped.sample(550,-800,0));
        }
    }
    @Test void signedCoordinatesCubeOrderAndIndependentInstancesAreDeterministic() {
        var a=sampler((x,y,z)->false);var b=sampler((x,y,z)->false);
        var points=new ArrayList<int[]>();var expected=new HashMap<String,TerrainSample>();
        for(int x=-609;x<-577;x++)for(int y=-817;y<-785;y+=2)for(int z=-33;z<0;z+=3){
            int[] point={x,y,z};points.add(point);expected.put(Arrays.toString(point),a.sample(x,y,z));
        }
        Collections.shuffle(points,new Random(456));
        for(var point:points)assertEquals(expected.get(Arrays.toString(point)),b.sample(point[0],point[1],point[2]));
        for(int axis:new int[]{-1024,-16,-1,0,15,16,1024}) {
            var f=a.featureAt(axis,-513,-590);var g=a.featureAt(axis+1,-513,-590);
            assertTrue(Math.abs(f.recessMetres()-g.recessMetres())<3,"one metre must not cross a section discontinuity");
        }
    }
    @Test void baselineDelegationIsExactlyOnceEvenAtExtremeCoordinates() {
        int[] calls={0};var p=parameters();
        var s=new CliffTerrainSampler(p,CliffParameters.proposal(p.seed()),(x,y,z)->{calls[0]++;return new TerrainSample.Known(TerrainMaterial.ROCK);},(x,y,z)->false);
        for(int[] q:new int[][]{{550,-800,0},{Integer.MAX_VALUE,-800,Integer.MIN_VALUE},{-16,-16,-16}}){
            int before=calls[0];s.sample(q[0],q[1],q[2]);assertEquals(before+1,calls[0]);
        }
    }
    @Test void recipesRejectUnboundedAndUndersampledFeatures() {
        assertThrows(IllegalArgumentException.class,()->new CliffParameters(1,Double.NaN,64,96,18,4,32,8,12,32));
        assertThrows(IllegalArgumentException.class,()->new CliffParameters(1,70,64,96,18,4,32,8,12,32));
        assertThrows(IllegalArgumentException.class,()->new CliffParameters(1,8,0.5,96,18,4,32,8,12,32));
    }
    @Test void pocketWidthIsAnEffectiveRecipeInputAndLargeShelfBodiesStayExact() {
        var p=parameters();var base=new ParametricTerrainSampler(p);var a=sampler((x,y,z)->false);
        var old=CliffParameters.proposal(p.seed());
        var narrow=new CliffParameters(old.seed(),old.maximumRecessMetres(),old.ribSpacingMetres(),old.verticalRibScaleMetres(),
            old.strataSpacingMetres(),old.fractureWidthMetres(),old.pocketSpacingMetres(),old.pocketHeightMetres(),9,old.mouthBlendMetres());
        var b=new CliffTerrainSampler(p,narrow,base::sample,(x,y,z)->false);int differences=0,preserved=0;
        for(int y=-100;y>-1250;y-=5)for(int azimuth=0;azimuth<24;azimuth++){
            double angle=azimuth*Math.PI*2/24;int rx=(int)Math.round(550*Math.cos(angle)),rz=(int)Math.round(550*Math.sin(angle));
            double wall=base.cavityRadius(rx,y,rz);
            int x=(int)Math.round((wall+1)*Math.cos(angle)),z=(int)Math.round((wall+1)*Math.sin(angle));
            if(Math.abs(a.featureAt(x,y,z).pocket()-b.featureAt(x,y,z).pocket())>0.05)differences++;
            double fraction=(64.0-y)/(64+1286);double main=500+150*fraction*fraction*(3-2*fraction);
            if(wall<main-84){assertEquals(base.sample(x,y,z),a.sample(x,y,z));preserved++;}
        }
        assertTrue(differences>20,"pocket width must affect the morphology");
        assertTrue(preserved>20,"representative broad shelf protection was not exercised");
    }
    public static void main(String[] args)throws Exception {
        var tests=new CliffTerrainSamplerTest();int passed=0;
        for(var method:CliffTerrainSamplerTest.class.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)) {
            try{method.invoke(tests);System.out.println("PASS "+method.getName());passed++;}
            catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError(method.getName(),e.getCause());}
        }
        System.out.println("Cliff standalone tests passed: "+passed);
    }
}
