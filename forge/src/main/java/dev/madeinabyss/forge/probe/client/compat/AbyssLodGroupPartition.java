package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.kernel.lod.TerrainLodColumns;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Physical box payloads consumed directly by adapter registration. No GPU or source work here. */
public final class AbyssLodGroupPartition {
    private AbyssLodGroupPartition(){}
    private record Cell(long x,long y,long z,int sky,int block){}
    private static final Comparator<Cell> CELLS=Comparator.comparingLong(Cell::x).thenComparingLong(Cell::y).thenComparingLong(Cell::z).thenComparingInt(Cell::sky).thenComparingInt(Cell::block);
    private static final Comparator<TerrainLodColumns.Box> BOXES=Comparator.comparingLong(TerrainLodColumns.Box::minX).thenComparingLong(TerrainLodColumns.Box::minY)
        .thenComparingLong(TerrainLodColumns.Box::minZ).thenComparingLong(TerrainLodColumns.Box::maxXExclusive).thenComparingLong(TerrainLodColumns.Box::maxYExclusive)
        .thenComparingLong(TerrainLodColumns.Box::maxZExclusive).thenComparingInt(b->b.material().ordinal());
    public static List<AbyssLodGroupLeases.Payload<TerrainLodColumns.Box>> parts(List<AbyssLodPipeline.LitBox> boxes){
        // These cells only package unchanged boxes; they never sample/split/coarsen geometry.
        long horizontal=512,vertical=256;TreeMap<Cell,List<TerrainLodColumns.Box>> grouped;
        while(true){grouped=new TreeMap<>(CELLS);
            for(var b:boxes){if(Thread.currentThread().isInterrupted())throw new CancellationException();var box=b.box();
                var cell=new Cell(Math.floorDiv(box.minX(),horizontal),Math.floorDiv(box.minY(),vertical),Math.floorDiv(box.minZ(),horizontal),b.light().sky(),b.light().block());
                grouped.computeIfAbsent(cell,k->new ArrayList<>()).add(box);}
            long count=0;for(var list:grouped.values())count+=(list.size()+4095L)/4096;
            if(count<=256)break;
            if(horizontal>=(1L<<33))throw new IllegalArgumentException("Bounded GPU packaging cannot fit scene");
            horizontal*=2;vertical*=2;
        }
        var result=new ArrayList<AbyssLodGroupLeases.Payload<TerrainLodColumns.Box>>();
        for(var e:grouped.entrySet()){if(Thread.currentThread().isInterrupted())throw new CancellationException();e.getValue().sort(BOXES);
            for(int i=0;i<e.getValue().size();i+=4096){if(Thread.currentThread().isInterrupted())throw new CancellationException();var c=e.getKey();
                result.add(new AbyssLodGroupLeases.Payload<>(horizontal+"/"+vertical+"/"+c.x()+"/"+c.y()+"/"+c.z()+"/"+(i/4096),
                    e.getValue().subList(i,Math.min(i+4096,e.getValue().size())),c.sky(),c.block()));}}
        return List.copyOf(result);
    }
}
