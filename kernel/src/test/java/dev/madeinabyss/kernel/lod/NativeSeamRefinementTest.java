package dev.madeinabyss.kernel.lod;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeSeamRefinementTest {
    private static final TerrainLodColumns.Bounds WORLD=new TerrainLodColumns.Bounds(-720,-1286,-720,719,320,719);
    @Test void negativeNative128KeepsFirst32MetresFineOnSidesTopAndBottomWithinAggregateBudget() {
        var nativeVolume=new NativeSeamRefinement.NativeVolume(-16.5,-16.5,128,-921,-601);
        var plan=NativeSeamRefinement.plan(WORLD,nativeVolume,60_000_000,32);
        assertEquals(50_321_408,plan.sampledVoxels());
        assertTrue(plan.sampledVoxels()+52_066_800+13_016_700<=120_000_000);
        for(int d=0;d<32;d++) {
            assertEquals(1,step(plan,-145-d,-800,-17));assertEquals(1,step(plan,112+d,-800,-17));
            assertEquals(1,step(plan,-17,-921-d,-17));assertEquals(1,step(plan,-17,-601+d,-17));
        }
        assertEquals(2,step(plan,-17,-560,-17));assertEquals(2,step(plan,-17,-961,-17));
        assertEquals(4,step(plan,-17,-512,-17));assertEquals(0,step(plan,-17,-800,-17));
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->NativeSeamRefinement.plan(WORLD,nativeVolume,50_321_407,32));
    }
    @Test void asymmetricExpandedVolumeHasNoOverlapsOrHolesOutsideOnlyGuaranteedCore() {
        var plan=NativeSeamRefinement.plan(WORLD,new NativeSeamRefinement.NativeVolume(-16.5,-16.5,128,-921,-601),60_000_000,32);
        var outer=plan.replacement().orElseThrow();assertEquals(new TerrainLodClipper.BoundsLong(-288,-1056,-288,240,-464,240),outer);
        for(int x=-296;x<248;x+=8)for(int y=-1064;y<-456;y+=8)for(int z=-296;z<248;z+=8) {
            final int px=x,py=y,pz=z;
            long hits=plan.regions().stream().filter(r->contains(r.bounds(),px,py,pz)).count();
            boolean inside=x>=-288&&x<240&&y>=-1056&&y<-464&&z>=-288&&z<240;
            boolean core=x>=-96&&x<64&&y>=-912&&y<-608&&z>=-96&&z<64;
            assertEquals(inside&&!core?1:0,hits,"XYZ "+x+","+y+","+z);
        }
        assertEquals(java.util.List.of(1,2,4),plan.regions().stream().map(TerrainLodRefinement.Region::horizontalStep).distinct().toList());
        assertEquals(plan,NativeSeamRefinement.plan(WORLD,new NativeSeamRefinement.NativeVolume(-16.5,-16.5,128,-921,-601),60_000_000,32));
    }
    @Test void worldClippingExtremeCoordinatesAndInvalidVolumesAreBounded() {
        var point=new TerrainLodColumns.Bounds(Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE);
        var p=NativeSeamRefinement.plan(point,new NativeSeamRefinement.NativeVolume(Integer.MAX_VALUE,Integer.MIN_VALUE,32,Integer.MIN_VALUE,Integer.MIN_VALUE+320),60_000_000,32);
        assertTrue(p.sampledVoxels()<=1);for(var r:p.regions())assertEquals(point,r.bounds());
        assertThrows(IllegalArgumentException.class,()->new NativeSeamRefinement.NativeVolume(Double.NaN,0,32,-100,100));
        assertThrows(IllegalArgumentException.class,()->new NativeSeamRefinement.NativeVolume(0,0,129,-100,100));
        assertThrows(IllegalArgumentException.class,()->new NativeSeamRefinement.NativeVolume(0,0,32,-100,-100));
        assertThrows(TerrainLodColumns.BudgetExceededException.class,()->NativeSeamRefinement.plan(WORLD,new NativeSeamRefinement.NativeVolume(0,0,32,-921,-601),60_000_000,1));
    }
    @Test void inclusiveNativeTopRowUsesItsActualTopFaceForAll32FineMetres() {
        // Native minY=-1056/maxY=-736 includes321 rows: the last top face is-735.
        var p=NativeSeamRefinement.plan(WORLD,new NativeSeamRefinement.NativeVolume(0,0,128,-1056,-735),60_000_000,32);
        for(int y=-735;y<-703;y++)assertEquals(1,step(p,0,y,0),"Top seam Y="+y);
        assertEquals(2,step(p,0,-688,0));
        assertTrue(p.sampledVoxels()+52_066_800+13_016_700<=120_000_000);
        assertThrows(IllegalArgumentException.class,()->new NativeSeamRefinement.NativeVolume(0,0,128,-1056,-734));
    }
    private static int step(TerrainLodRefinement.Plan p,int x,int y,int z) {return p.regions().stream().filter(r->contains(r.bounds(),x,y,z)).findFirst().map(TerrainLodRefinement.Region::horizontalStep).orElse(0);}
    private static boolean contains(TerrainLodColumns.Bounds b,int x,int y,int z){return x>=b.minX()&&x<=b.maxXInclusive()&&y>=b.minY()&&y<=b.maxYInclusive()&&z>=b.minZ()&&z<=b.maxZInclusive();}
}
