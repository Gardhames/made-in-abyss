package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AbyssLodSurfacePriorityTest {
    private static AbyssLodSurfacePriority.SurfaceIndex boundedIndex(List<TerrainLodColumns.Box> raw,TerrainLodColumns.Bounds bounds){
        return AbyssLodSurfacePriority.index(raw,1000,16,bounds);
    }
    @Test void acquisitionCutsAreUnknownNeighboursRatherThanRefinementSurfaces(){
        var bounds=new TerrainLodColumns.Bounds(-32,-128,-16,-1,-65,15);
        var raw=List.of(new TerrainLodColumns.Box(-32,-128,-16,0,-64,16,TerrainMaterial.ROCK));
        assertTrue(boundedIndex(raw,bounds).boundaries().isEmpty(),
                "All six faces close the acquisition domain; none witnesses known AIR outside it");
        assertEquals(6,AbyssLodSurfacePriority.index(raw,1000,16).boundaries().size(),
                "An explicitly unbounded caller still retains its complete boundary hints");
    }
    @Test void sharedWaterFacesDoNotRequestInteriorRefinement(){
        var bounds=new TerrainLodColumns.Bounds(-64,-128,-64,63,63,63);
        var raw=List.of(new TerrainLodColumns.Box(-32,-64,0,-16,-48,16,TerrainMaterial.WATER),
                new TerrainLodColumns.Box(-16,-64,0,0,-48,16,TerrainMaterial.WATER));
        var hints=boundedIndex(raw,bounds).boundaries();
        assertEquals(10,hints.size(),"Two water cells expose ten outer faces, not twelve");
        assertFalse(hints.stream().anyMatch(b->b.maxXExclusive()-b.minX()==1&&(b.minX()==-17||b.minX()==-16)),
                "Only the WATER/WATER interface disappears");
    }
    @Test void bothOpaqueAndWaterSidesOfAnUnderwaterShoreStayWitnessed(){
        var bounds=new TerrainLodColumns.Bounds(-64,-128,-64,63,63,63);
        var raw=List.of(new TerrainLodColumns.Box(-16,-64,0,0,-48,16,TerrainMaterial.WATER),
                new TerrainLodColumns.Box(0,-64,0,16,-48,16,TerrainMaterial.ROCK));
        var hints=boundedIndex(raw,bounds).boundaries();
        assertEquals(12,hints.size());
        assertTrue(hints.contains(new TerrainLodColumns.Box(-1,-64,0,0,-48,16,TerrainMaterial.WATER)));
        assertTrue(hints.contains(new TerrainLodColumns.Box(0,-64,0,1,-48,16,TerrainMaterial.ROCK)));
    }
    @Test void tangentialDomainContactCannotHideAOneBlockThinInteriorRoof(){
        var bounds=new TerrainLodColumns.Bounds(-32,-128,-32,31,-1,31);
        var roof=new TerrainLodColumns.Box(-32,-127,0,-16,-126,16,TerrainMaterial.ROCK);
        var hints=boundedIndex(List.of(roof),bounds).boundaries();
        assertEquals(2,hints.stream().filter(roof::equals).count(),
                "Both Y faces are internal even though they touch the X domain boundary");
        assertFalse(hints.contains(new TerrainLodColumns.Box(-32,-127,0,-31,-126,16,TerrainMaterial.ROCK)));
        assertTrue(hints.contains(new TerrainLodColumns.Box(-17,-127,0,-16,-126,16,TerrainMaterial.ROCK)));
    }
    @Test void distributedFourMetreWallCoveragePrecedesAThousandNearbyFineJobs(){
        var desired=new ArrayList<TerrainLodRefinement.Region>();
        for(int x=0;x<16;x++)for(int y=0;y<4;y++)for(int z=0;z<16;z++)desired.add(new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds(x*16,y*64,z*16+64,x*16+15,y*64+63,z*16+79),1));
        var opposite=new ArrayList<TerrainLodRefinement.Region>();for(int x=-4;x<4;x++)for(int y=-4;y<4;y++)opposite.add(new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds(x*16,y*64,-768,x*16+15,y*64+63,-753),4));
        desired.addAll(opposite);var ordered=AbyssLodSurfacePriority.distributed(desired,0,0,0,0,0,1,4096);
        int first=-1;for(int i=0;i<ordered.jobs().size();i++)if(ordered.jobs().get(i).bounds().minZ()==-768){first=i;break;}
        assertTrue(first>=0&&first<64,"Angular source4m coverage must reach the opposite wall before exhausting nearby1m volume");
        assertTrue(ordered.jobs().containsAll(opposite),"The bootstrap is the whole witnessed wall, not one ceremonial tile per angular sector");
        assertTrue(ordered.jobs().containsAll(desired.subList(0,1024)),"Declared near1m work remains after distributed coverage");
        for(var r:opposite)assertFalse(TerrainLodColumns.build((x,y,z)->new TerrainSample.Known(TerrainMaterial.ROCK),r.bounds(),new TerrainLodColumns.Parameters(4,2048,1000)).boxes().isEmpty());
    }
    private static TerrainLodRefinement.Plan plan(){var b=new TerrainLodColumns.Bounds(-128,-64,-128,127,63,127);
        var r=new TerrainLodRefinement.Region(b,1);return new TerrainLodRefinement.Plan(List.of(r),Optional.empty(),r.sampledVoxels());}
    private static TerrainLodColumns.Box wall(int x){return new TerrainLodColumns.Box(x,0,0,x+16,32,16,TerrainMaterial.ROCK);}
    private static List<TerrainLodRefinement.Region> choose(List<TerrainLodColumns.Box> raw,List<TerrainLodClipper.BoundsLong> cuts){
        return AbyssLodSurfacePriority.prefix(plan(),raw,cuts,0,0,0,8);}
    @Test void firstExactGridReachesRealWallBeforeEightEmptyCameraVolumes(){
        var chosen=choose(List.of(wall(64)),List.of());
        assertEquals(64,chosen.get(0).bounds().minX());long positives=0;
        for(var r:chosen)positives+=TerrainLodColumns.build((x,y,z)->new TerrainSample.Known(
                x==71&&z==7&&y>=0&&y<32?TerrainMaterial.ROCK:TerrainMaterial.AIR),r.bounds(),
                new TerrainLodColumns.Parameters(r.horizontalStep(),131072,400000)).boxes().size();
        assertTrue(positives>0,"Actual source witness must be reached by the initial exact prefix");
    }
    @Test void closestUncoveredSurfaceWinsRegardlessOfRawInputOrder(){
        var a=choose(List.of(wall(96),wall(64)),List.of());var b=choose(List.of(wall(64),wall(96)),List.of());
        assertEquals(a,b);assertEquals(64,a.get(0).bounds().minX());
        var cut=new TerrainLodClipper.BoundsLong(64,0,0,80,32,16);
        assertEquals(96,choose(List.of(wall(64),wall(96)),List.of(cut)).get(0).bounds().minX());
    }
    @Test void wholeSignedSourceGridsStayBoundedAndMissingProxyNeverDeletesThinFeaturePlan(){
        var selected=choose(List.of(),List.of());assertEquals(8,new HashSet<>(selected).size());long samples=0;
        for(var r:selected){var b=r.bounds();assertEquals(1,r.horizontalStep());
            assertEquals(0,Math.floorMod(b.minX(),16));assertEquals(0,Math.floorMod(b.minY(),64));assertEquals(0,Math.floorMod(b.minZ(),16));
            assertTrue((long)b.maxXInclusive()+1-b.minX()<=16);assertTrue((long)b.maxYInclusive()+1-b.minY()<=64);samples+=r.sampledVoxels();}
        assertTrue(samples<=8L*16*64*16);
        // Thin source feature missed by coarse midpoint remains in the full requested plan.
        var bounds=plan().regions().get(0).bounds();assertTrue(113>=bounds.minX()&&113<=bounds.maxXInclusive());
    }
    @Test void positiveSurfaceInViewPrecedesCloserProxyBehindCamera(){
        var front=new TerrainLodColumns.Box(0,0,96,16,32,112,TerrainMaterial.ROCK);
        var behind=new TerrainLodColumns.Box(0,0,-32,16,32,-16,TerrainMaterial.ROCK);
        var selected=AbyssLodSurfacePriority.prefix(plan(),List.of(behind,front),List.of(),0,0,0,0,0,1,8);
        assertEquals(96,selected.get(0).bounds().minZ(),"Forward proxy ordering reaches the actual viewed wall before a nearby rear wall");
    }
    @Test void frontalSourceWallPrecedesBehindTilesOfOneLongNearbyFace(){
        var world=new TerrainLodColumns.Bounds(-128,-64,-1024,127,-1,511);
        var region=new TerrainLodRefinement.Region(world,1);
        var requested=new TerrainLodRefinement.Plan(List.of(region),Optional.empty(),region.sampledVoxels());
        var side=new TerrainLodColumns.Box(-32,-64,-1024,-16,0,512,TerrainMaterial.ROCK);
        var front=new TerrainLodColumns.Box(64,-64,192,112,0,208,TerrainMaterial.ROCK);
        var selected=AbyssLodSurfacePriority.prefix(world,requested,List.of(side,front),List.of(),0,-32,0,-.6,0,.8,4096);
        int frontIndex=-1,behindIndex=-1;
        for(int i=0;i<selected.size();i++){var b=selected.get(i).bounds();
            if(frontIndex<0&&b.minX()>=64&&b.maxXInclusive()<112&&b.minZ()==192)frontIndex=i;
            if(behindIndex<0&&b.minX()>=-32&&b.maxXInclusive()<-16&&b.maxZInclusive()<-512)behindIndex=i;}
        assertTrue(frontIndex>=0&&behindIndex>=0,"Both actual exposed source regions remain in the bounded progressive plan");
        var job=selected.get(frontIndex);var fine=TerrainLodColumns.build((x,y,z)->new TerrainSample.Known(
                x>=64&&x<112&&z>=192&&z<208&&y>=-64&&y<0?TerrainMaterial.ROCK:TerrainMaterial.AIR),job.bounds(),
                new TerrainLodColumns.Parameters(1,job.sampledVoxels(),16384));
        assertFalse(fine.boxes().isEmpty(),"The frontal job contains actual fine geometry, not only a priority label");
        assertTrue(frontIndex<behindIndex,"Per-tile FOV must not label a1024m rear face tail with the nearby face's forward priority");
    }
    @Test void firstWitnessedWallOutsideOldCameraFineBandStillGetsOneOrTwoMetreSourceGrid(){
        var world=new TerrainLodColumns.Bounds(-1024,-128,-1024,1023,127,1023);
        var requested=TerrainLodRefinement.plan(world,0,0,0,List.of(new TerrainLodRefinement.Band(128,1)),20000000,100);
        var proxy=new TerrainLodColumns.Box(0,0,512,16,32,528,TerrainMaterial.ROCK);
        var selected=AbyssLodSurfacePriority.prefix(world,requested,List.of(proxy),List.of(),0,0,0,0,0,1,8);
        assertEquals(512,selected.get(0).bounds().minZ());assertTrue(selected.get(0).horizontalStep()<=2);
    }
    @Test void progressivePrefixSamplesExposedSolidBoundaryInsteadOfBuriedInteriorVolume(){
        var world=new TerrainLodColumns.Bounds(-64,-256,-64,79,319,79);
        Rock source=(x,y,z)->inside(world,x,y,z);
        var raw=TerrainLodColumns.build((x,y,z)->new TerrainSample.Known(source.present(x,y,z)?TerrainMaterial.ROCK:TerrainMaterial.AIR),
                world,new TerrainLodColumns.Parameters(16,100000,1000));
        var region=new TerrainLodRefinement.Region(world,1);
        var requested=new TerrainLodRefinement.Plan(List.of(region),Optional.empty(),region.sampledVoxels());
        var selected=AbyssLodSurfacePriority.prefix(world,requested,raw.boxes(),List.of(),7,31,7,0,0,1,4096);
        assertFalse(selected.isEmpty());
        assertTrue(selected.stream().allMatch(r->hasExposedSourceVoxel(r,source)),
                "A fully buried source grid is not a surface job, even when its RAW column is solid");
        for(int axis=0;axis<3;axis++)for(boolean positive:new boolean[]{false,true}){
            final int a=axis;final boolean p=positive;
            assertTrue(selected.stream().anyMatch(r->touches(world,r.bounds(),a,p)),"All six signed outer boundaries remain planned");
        }
        assertTrue(selected.size()<729,"Surface extraction must reduce the actual9x9x9 fine-grid volume");
    }
    @Test void signedCaveInteriorFacesDoorAndRoofStayRequestedWithoutBuriedRockJobs(){
        var world=new TerrainLodColumns.Bounds(-64,-128,-64,63,127,63);
        Rock source=(x,y,z)->inside(world,x,y,z)
                &&!(x>=-16&&x<=15&&y>=-64&&y<=63&&z>=-16&&z<=15)
                &&!(x>=-16&&x<=15&&y>=-32&&y<=-1&&z>=16);
        var raw=TerrainLodColumns.build((x,y,z)->new TerrainSample.Known(source.present(x,y,z)?TerrainMaterial.ROCK:TerrainMaterial.AIR),
                world,new TerrainLodColumns.Parameters(16,100000,1000));
        var region=new TerrainLodRefinement.Region(world,1);
        var requested=new TerrainLodRefinement.Plan(List.of(region),Optional.empty(),region.sampledVoxels());
        var selected=AbyssLodSurfacePriority.prefix(world,requested,raw.boxes(),List.of(),-1,-1,-1,0,0,1,4096);
        assertFalse(selected.isEmpty());
        assertTrue(selected.stream().allMatch(r->hasExposedSourceVoxel(r,source)),
                "Only true unit-source boundary grids are hinted; no interior ROCK or empty camera fill is a progressive surface");
        for(var point:new long[][]{{-17,-1,-1},{16,-1,-1},{0,64,0},{0,-65,0},{0,0,32}})
            assertTrue(selected.stream().anyMatch(r->contains(r.bounds(),point)),"Cave walls, negative floor, roof and open-entry support remain fine jobs");
        assertEquals(selected,new ArrayList<>(AbyssLodSurfacePriority.prefix(world,requested,reversed(raw.boxes()),List.of(),-1,-1,-1,0,0,1,4096)),
                "Face-owner input order cannot change the actual requested grids");
    }
    @FunctionalInterface private interface Rock {boolean present(long x,long y,long z);}
    private static boolean inside(TerrainLodColumns.Bounds b,long x,long y,long z){return x>=b.minX()&&x<=b.maxXInclusive()
            &&y>=b.minY()&&y<=b.maxYInclusive()&&z>=b.minZ()&&z<=b.maxZInclusive();}
    private static boolean hasExposedSourceVoxel(TerrainLodRefinement.Region r,Rock source){var b=r.bounds();
        for(long x=b.minX();x<=b.maxXInclusive();x++)for(long y=b.minY();y<=b.maxYInclusive();y++)for(long z=b.minZ();z<=b.maxZInclusive();z++)
            if(source.present(x,y,z)&&(!source.present(x-1,y,z)||!source.present(x+1,y,z)||!source.present(x,y-1,z)
                    ||!source.present(x,y+1,z)||!source.present(x,y,z-1)||!source.present(x,y,z+1)))return true;
        return false;
    }
    private static boolean touches(TerrainLodColumns.Bounds world,TerrainLodColumns.Bounds b,int axis,boolean positive){return switch(axis){
        case 0->positive?b.maxXInclusive()==world.maxXInclusive():b.minX()==world.minX();
        case 1->positive?b.maxYInclusive()==world.maxYInclusive():b.minY()==world.minY();
        default->positive?b.maxZInclusive()==world.maxZInclusive():b.minZ()==world.minZ();};}
    private static boolean contains(TerrainLodColumns.Bounds b,long[] p){return inside(b,p[0],p[1],p[2]);}
    private static List<TerrainLodColumns.Box> reversed(List<TerrainLodColumns.Box> boxes){var result=new ArrayList<>(boxes);Collections.reverse(result);return result;}
    @Test void oneAngularPerFinerBoundsSignedDetailDelayAndPreservesAllAngularSectorsAndQuality(){
        var desired=new ArrayList<TerrainLodRefinement.Region>();
        for(int i=0;i<128;i++)desired.add(new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds(-512+i*16,-128,-256,-497+i*16,-65,-241),i%2==0?1:2));
        for(int elevation=0;elevation<3;elevation++)for(int az=0;az<8;az++){
            double angle=(az+.5)*Math.PI/4;int x=-128+(int)Math.floor(Math.cos(angle)*512/16)*16,z=-128+(int)Math.floor(Math.sin(angle)*512/16)*16;
            int y=elevation==0?-704:elevation==2?512:-64;
            desired.add(new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds(x,y,z,x+15,y+63,z+15),4));
        }
        var distributed=AbyssLodSurfacePriority.distributed(desired,-128,-64,-128,-1,0,1,512,1);
        for(int rank=0;rank<128;rank++)assertTrue(distributed.jobs().indexOf(desired.get(rank))+1<=2*(rank+1),"One angular job may delay but cannot starve an exact declared fine grid");
        var sectors=new HashSet<Integer>();for(var job:distributed.jobs().subList(0,49))if(job.horizontalStep()==4){var b=job.bounds();double dx=((long)b.minX()+b.maxXInclusive()+1)*.5+128,dy=((long)b.minY()+b.maxYInclusive()+1)*.5+64,dz=((long)b.minZ()+b.maxZInclusive()+1)*.5+128;
            int az=Math.floorMod((int)Math.floor(Math.atan2(dz,dx)/(Math.PI/4)),8);double h=Math.hypot(dx,dz);sectors.add((dy<-h*.577?0:dy>h*.577?2:1)*8+az);}
        assertEquals(24,sectors.size());assertEquals(24,distributed.angularSectors());assertEquals(0,distributed.undispatchedJobs());
        assertTrue(distributed.jobs().containsAll(desired));for(var job:desired)assertTrue(distributed.jobs().contains(new TerrainLodRefinement.Region(job.bounds(),4)));
    }
    @Test void explicitAngularCadenceValidatesBoundsAndSixteenRetainsDefaultOrdering(){
        var desired=List.of(new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds(-16,-64,-16,-1,-1,-1),1),new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds(0,0,0,15,63,15),2));
        assertEquals(AbyssLodSurfacePriority.distributed(desired,-1,-1,-1,0,0,1,8),AbyssLodSurfacePriority.distributed(desired,-1,-1,-1,0,0,1,8,16));
        assertThrows(IllegalArgumentException.class,()->AbyssLodSurfacePriority.distributed(desired,-1,-1,-1,0,0,1,8,0));
        assertThrows(IllegalArgumentException.class,()->AbyssLodSurfacePriority.distributed(desired,-1,-1,-1,0,0,1,8,17));
    }}
