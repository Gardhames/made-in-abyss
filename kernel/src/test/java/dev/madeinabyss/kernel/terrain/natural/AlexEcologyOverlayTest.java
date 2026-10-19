package dev.madeinabyss.kernel.terrain.natural;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.madeinabyss.kernel.terrain.natural.AlexEcologyOverlay.*;
import static org.junit.jupiter.api.Assertions.*;

class AlexEcologyOverlayTest {
    private static final Protection NONE=(x,y,z)->false;
    private static final Features OUTSIDE=(x,y,z)->new Feature(false,0);
    private static Source floor(int ground,Voxel material) {return (x,y,z)->y<=ground?material:Voxel.AIR;}
    private static AlexEcologyOverlay overlay(Source s){return new AlexEcologyOverlay(281L,s,NONE,OUTSIDE);}
    private static boolean log(Decoration d){return d.name().contains("_LOG_");}

    @Test void denseSupportedCommunitiesHavePairsAndRareWideCycads() {
        var o=overlay(floor(-17,Voxel.SOIL));var counts=new EnumMap<Decoration,Integer>(Decoration.class);
        for(int x=-64;x<64;x++)for(int z=-64;z<64;z++)o.sampleOverride(x,-16,z).ifPresent(d->counts.merge(d,1,Integer::sum));
        int n=counts.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(n>2000&&n<12000,"ground cover density "+counts);
        assertTrue(counts.getOrDefault(Decoration.CURLY_LOWER,0)>100,counts.toString());
        assertTrue(counts.getOrDefault(Decoration.CYCAD_TOP,0)>0,counts.toString());
        assertTrue(counts.getOrDefault(Decoration.CYCAD_TOP,0)<200,counts.toString());
        System.out.println("Alex ORIGINAL shelf density 128x128: "+counts);
    }

    @Test void curlyPairCrossesNegativeCubeSeamAndWholePairRejectsUpperCutoff() {
        var base=floor(-18,Voxel.SOIL);var o=overlay(base);int[] p=null;
        for(int x=-128;x<128&&p==null;x++)for(int z=-128;z<128;z++)if(o.sampleOverride(x,-17,z).orElse(null)==Decoration.CURLY_LOWER){p=new int[]{x,z};break;}
        assertNotNull(p,"pair required at -17/-16 seam");int xx=p[0],zz=p[1];
        assertEquals(Decoration.CURLY_UPPER,o.sampleOverride(xx,-16,zz).orElse(null));
        for(Voxel stop:List.of(Voxel.UNKNOWN,Voxel.WATER,Voxel.OBSTACLE)){
            var broken=overlay((x,y,z)->x==xx&&z==zz&&y==-16?stop:base.sample(x,y,z));
            assertNotEquals(Decoration.CURLY_LOWER,broken.sampleOverride(xx,-17,zz).orElse(null));
            assertTrue(broken.sampleOverride(xx,-16,zz).isEmpty());
        }
        var protectedPair=new AlexEcologyOverlay(281L,base,(x,y,z)->x==xx&&z==zz&&y==-16,OUTSIDE);
        assertNotEquals(Decoration.CURLY_LOWER,protectedPair.sampleOverride(xx,-17,zz).orElse(null));
    }

    @Test void darkCavesGrowOnlyUnderweedOnSupportedRock() {
        var o=new AlexEcologyOverlay(281L,floor(-609,Voxel.ROCK),NONE,(x,y,z)->new Feature(true,100));int n=0;
        for(int x=-64;x<64;x++)for(int z=-64;z<64;z++){
            var d=o.sampleOverride(x,-608,z);if(d.isPresent()&&!log(d.get())){assertEquals(Decoration.UNDERWEED,d.get());n++;}
        }
        assertTrue(n>500,"supported rock needs underweed");
    }

    @Test void wholeDeadfallRejectsMissingSupportObstacleAndProtectedEndpoint() {
        var base=floor(-17,Voxel.SOIL);var o=overlay(base);List<int[]> positions=new ArrayList<>();Decoration kind=null;
        outer:for(int x=-64;x<64;x++)for(int z=-64;z<64;z++){
            Decoration d=o.sampleOverride(x,-16,z).orElse(null);if(d!=null&&log(d)){kind=d;int dx=d.name().endsWith("X")?1:0,dz=1-dx;
                int ax=x,az=z;while(o.sampleOverride(ax-dx,-16,az-dz).orElse(null)==d){ax-=dx;az-=dz;}
                while(o.sampleOverride(ax,-16,az).orElse(null)==d){positions.add(new int[]{ax,az});ax+=dx;az+=dz;}break outer;
            }
        }
        assertTrue(positions.size()>=3&&positions.size()<=4, "deadfall exists");int[] end=positions.get(positions.size()-1);
        for(Voxel bad:List.of(Voxel.AIR,Voxel.UNKNOWN,Voxel.WATER)){
            var broken=overlay((x,y,z)->x==end[0]&&z==end[1]&&y==-17?bad:base.sample(x,y,z));
            for(int[] p:positions)assertFalse(logOrFalse(broken.sampleOverride(p[0],-16,p[1])));
        }
        var blocked=overlay((x,y,z)->x==end[0]&&z==end[1]&&y==-16?Voxel.OBSTACLE:base.sample(x,y,z));
        var guarded=new AlexEcologyOverlay(281L,base,(x,y,z)->x==end[0]&&z==end[1]&&y==-16,OUTSIDE);
        var boundary=new AlexEcologyOverlay(281L,base,NONE,(x,y,z)->new Feature(true,x==end[0]&&z==end[1]?25:23));
        for(int[] p:positions){assertFalse(logOrFalse(blocked.sampleOverride(p[0],-16,p[1])));assertFalse(logOrFalse(guarded.sampleOverride(p[0],-16,p[1])));assertFalse(logOrFalse(boundary.sampleOverride(p[0],-16,p[1])));}
    }
    private static boolean logOrFalse(Optional<Decoration> d){return d.isPresent()&&log(d.get());}

    @Test void vinesHaveOneHeadAndContinuousSupportedBodiesWithoutCollision() {
        Source cave=(x,y,z)->y>=-10?Voxel.ROCK:Voxel.AIR;
        var o=new AlexEcologyOverlay(281L,cave,NONE,(x,y,z)->new Feature(true,8));int[] found=null;
        outer:for(int x=-64;x<64;x++)for(int z=-64;z<64;z++)for(int y=-15;y<=-12;y++)if(o.sampleOverride(x,y,z).orElse(null)==Decoration.VINE_HEAD){found=new int[]{x,y,z};break outer;}
        assertNotNull(found);int x=found[0],bottom=found[1],z=found[2];assertTrue(-10-bottom>=2&&-10-bottom<=5);
        for(int y=bottom+1;y<-10;y++)assertEquals(Decoration.VINE_BODY,o.sampleOverride(x,y,z).orElse(null));
        for(Voxel bad:List.of(Voxel.UNKNOWN,Voxel.OBSTACLE,Voxel.WATER)){
            var b=new AlexEcologyOverlay(281L,(xx,yy,zz)->xx==x&&zz==z&&yy==-11?bad:cave.sample(xx,yy,zz),NONE,(xx,yy,zz)->new Feature(true,8));
            for(int y=bottom;y<-10;y++)assertFalse(b.sampleOverride(x,y,z).filter(d->d==Decoration.VINE_HEAD||d==Decoration.VINE_BODY).isPresent());
        }
    }

    @Test void waterUnknownAndObstaclesNeverChangeAndExtremeCoordinatesDoNotWrap() {
        for(Voxel v:List.of(Voxel.WATER,Voxel.UNKNOWN,Voxel.OBSTACLE)){
            var o=overlay((x,y,z)->v);for(int x=-10;x<=10;x++)assertTrue(o.sampleOverride(x,Integer.MIN_VALUE,x,v).isEmpty());
        }
        AtomicInteger calls=new AtomicInteger();var o=overlay((x,y,z)->{calls.incrementAndGet();return Voxel.AIR;});
        for(int x=-32;x<32;x++)for(int z=-32;z<32;z++)assertTrue(o.sampleOverride(x,Integer.MAX_VALUE,z,Voxel.AIR).isEmpty());
        assertTrue(calls.get()<20000,"bounded local probes "+calls.get());
    }

    @Test void samplingOrderAndSeedRemainIndependent() {
        var a=overlay(floor(-17,Voxel.GRASS));var b=overlay(floor(-17,Voxel.GRASS));List<int[]> points=new ArrayList<>();
        for(int x=-25;x<25;x++)for(int z=-25;z<25;z++)for(int y=-17;y<=-15;y++)points.add(new int[]{x,y,z});
        var expected=new HashMap<String,Optional<Decoration>>();for(int[] p:points)expected.put(Arrays.toString(p),a.sampleOverride(p[0],p[1],p[2]));
        Collections.shuffle(points,new Random(73));for(int[] p:points)assertEquals(expected.get(Arrays.toString(p)),b.sampleOverride(p[0],p[1],p[2]));
        var other=new AlexEcologyOverlay(282L,floor(-17,Voxel.GRASS),NONE,OUTSIDE);
        assertTrue(points.stream().anyMatch(p->!other.sampleOverride(p[0],p[1],p[2]).equals(expected.get(Arrays.toString(p)))));
    }

    @Test void cycadRequiresEntireWideClearanceIncludingProtectedAndUnknownEdges() {
        Source base=floor(-17,Voxel.GRASS);var o=overlay(base);int[] found=null;
        outer:for(int x=-64;x<64;x++)for(int z=-64;z<64;z++)if(o.sampleOverride(x,-16,z).orElse(null)==Decoration.CYCAD_TOP){found=new int[]{x,z};break outer;}
        assertNotNull(found);int cx=found[0],cz=found[1];
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            int xx=cx+dx,zz=cz+dz;
            for(Voxel bad:List.of(Voxel.UNKNOWN,Voxel.OBSTACLE,Voxel.WATER)){
                var broken=overlay((x,y,z)->x==xx&&z==zz&&y==-15?bad:base.sample(x,y,z));
                assertTrue(broken.sampleOverride(cx,-16,cz).isEmpty(),"whole wide cycad rejects "+bad);
            }
            var guarded=new AlexEcologyOverlay(281L,base,(x,y,z)->x==xx&&z==zz&&y==-15,OUTSIDE);
            assertTrue(guarded.sampleOverride(cx,-16,cz).isEmpty());
        }
    }

    @Test void soilSupportsRemainPlantCompatibleAndOreGroupsOnlyReplaceExposedRock() {
        var soil=overlay(floor(-17,Voxel.SOIL));
        var rock=new AlexEcologyOverlay(281L,floor(-17,Voxel.ROCK),NONE,(x,y,z)->new Feature(true,100));
        var seen=EnumSet.noneOf(Decoration.class);
        for(int x=-64;x<64;x++)for(int z=-64;z<64;z++){
            var d=soil.sampleOverride(x,-17,z);d.ifPresent(v->assertEquals(Decoration.MOSS,v));
            rock.sampleOverride(x,-17,z).ifPresent(seen::add);
            assertTrue(rock.sampleOverride(x,-18,z).isEmpty(),"buried rock remains immutable");
        }
        assertTrue(seen.containsAll(EnumSet.of(Decoration.LIMESTONE,Decoration.GALENA,Decoration.COPROLITH,Decoration.AMBER)),seen.toString());
        var unsupported=new AlexEcologyOverlay(281L,(x,y,z)->y==-17?Voxel.SOIL:Voxel.AIR,NONE,OUTSIDE);
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++)assertTrue(unsupported.sampleOverride(x,-17,z).isEmpty());
    }

    @Test void fullSignedXyzDoesNotAliasAt4096AndCommunityMatchesEntranceDepth() {
        var a=overlay(floor(-17,Voxel.GRASS));var deep=overlay(floor(-4113,Voxel.GRASS));int differences=0;
        for(int x=-32;x<32;x++)for(int z=-32;z<32;z++)if(!a.sampleOverride(x,-16,z).equals(deep.sampleOverride(x,-4112,z)))differences++;
        assertTrue(differences>1000,"Y must not be packed/truncated "+differences);
        var mouth=new AlexEcologyOverlay(281L,floor(0,Voxel.ROCK),NONE,(x,y,z)->new Feature(true,12));
        assertEquals(Community.WET_CAVE_MOUTH,mouth.communityAt(-16,-16,-16));
        assertEquals(Community.PRIMORDIAL_SHELF_GROVE,a.communityAt(-16,-16,-16));
    }
}
