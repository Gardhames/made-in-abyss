package dev.madeinabyss.kernel.lod;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AbyssLodSourcePagesTest {
    @TempDir Path directory;
    @SuppressWarnings("unchecked") private static List<TerrainLodRefinement.Region> order(List<TerrainLodRefinement.Region> regions)throws Exception{
        try{return (List<TerrainLodRefinement.Region>)Class.forName("dev.madeinabyss.kernel.lod.AbyssLodSourcePages")
            .getMethod("order",List.class,double.class,double.class,double.class,double.class,double.class,double.class)
            .invoke(null,regions,0.,-100.,-64.,1.,0.,0.);}
        catch(ClassNotFoundException missing){return List.copyOf(regions);}
    }
    private static TerrainTileDiskCache.Key key(TerrainLodRefinement.Region r){var b=r.bounds();return new TerrainTileDiskCache.Key(b.minX(),b.minY(),b.minZ(),b.maxXInclusive(),b.maxYInclusive(),b.maxZInclusive(),r.horizontalStep());}
    @Test void allSignedLatticesSurviveAndSparsePageSweepReadsEveryPageOnlyOnce()throws Exception{
        var regions=new ArrayList<TerrainLodRefinement.Region>();
        for(int step:new int[]{4,1})for(int x=-40;x<40;x++)regions.add(new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds(x*64+31,-113,-49,x*64+46,-65,-34),step));
        var writer=new AbyssRawTileArchive(directory,"actual-page-order");for(var region:regions)writer.put(key(region),List.of());writer.checkpoint();
        var ordered=order(regions);assertEquals(new HashSet<>(regions),new HashSet<>(ordered));assertEquals(regions.size(),ordered.size());
        var reader=new AbyssRawTileArchive(directory,"actual-page-order");for(var region:ordered)assertEquals(List.of(),reader.get(key(region)));
        assertEquals(80L,reader.status().get("diskReads"),"The same physical page must not be loaded again merely for a second LOD step");
        var positions=new HashMap<Integer,Integer>();var departed=new HashSet<Integer>();Integer previous=null;for(int i=0;i<ordered.size();i++){
            var r=ordered.get(i);int page=Math.floorDiv(r.bounds().minX(),64);
            if(!Objects.equals(previous,page)){assertFalse(departed.contains(page),"Signed physical archive pages remain contiguous");if(previous!=null)departed.add(previous);previous=page;}
            if(r.horizontalStep()==1)positions.put(page,i);else assertTrue(positions.getOrDefault(page,Integer.MAX_VALUE)<i,"Finer exact source precedes redundant coarse acquisition in the same page");
        }
    }
}
