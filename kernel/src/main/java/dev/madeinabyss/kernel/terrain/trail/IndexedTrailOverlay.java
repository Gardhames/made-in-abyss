package dev.madeinabyss.kernel.terrain.trail;
import dev.madeinabyss.kernel.terrain.*;
import java.util.*;

/** Exact v10 lookup alternative to the old immutable MapN probing. Old algorithms stay intact.
 * Owns one immutable HashMap with the already-verified footprint; no per-sample route scan. */
public final class IndexedTrailOverlay {
    private final TrailNetwork.SampleSource base;
    private final Map<TrailNetwork.Column,Integer> floors;
    private final int headroom;
    private final long minHeadY,maxHeadY;
    private final int minX,maxX,minZ,maxZ;
    private static final TerrainSample AIR=new TerrainSample.Known(TerrainMaterial.AIR);
    public IndexedTrailOverlay(TrailNetwork.SampleSource base,TrailNetwork network) {
        this.base=Objects.requireNonNull(base);Objects.requireNonNull(network);
        if(base!=network.verifiedBase())throw new IllegalArgumentException("Indexed overlay needs exact verified base source");
        headroom=network.parameters().headroomMetres();
        var index=new HashMap<TrailNetwork.Column,Integer>();
        for(var route:network.routes())for(int i=0;i<route.points().size();i++) {
            var point=route.points().get(i);var neighbour=route.points().get(i==0?1:i-1);
            long dx=(long)point.x()-neighbour.x(),dz=(long)point.z()-neighbour.z();
            for(int lateral=-network.parameters().widthMetres()/2;lateral<=network.parameters().widthMetres()/2;lateral++) {
                // The same immutable network already checked these signed coordinates.
                int x=Math.toIntExact((long)point.x()+dz*lateral),z=Math.toIntExact((long)point.z()-dx*lateral);
                Integer floor=network.floor(x,z);
                if(floor==null)throw new IllegalStateException("Verified trail footprint lacks its floor");
                index.put(new TrailNetwork.Column(x,z),floor);
            }
        }
        // Collections.unmodifiableMap retains HashMap buckets; Map.copyOf would recreate MapN.
        floors=Collections.unmodifiableMap(index);
        long low=Long.MAX_VALUE,high=Long.MIN_VALUE;int x0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,z0=Integer.MAX_VALUE,z1=Integer.MIN_VALUE;
        for(var entry:index.entrySet()) {
            low=Math.min(low,(long)entry.getValue()+1);high=Math.max(high,(long)entry.getValue()+headroom);
            x0=Math.min(x0,entry.getKey().x());x1=Math.max(x1,entry.getKey().x());z0=Math.min(z0,entry.getKey().z());z1=Math.max(z1,entry.getKey().z());
        }
        minHeadY=low;maxHeadY=high;minX=x0;maxX=x1;minZ=z0;maxZ=z1;
    }
    public int floorCount(){return floors.size();}
    public TerrainSample sample(int x,int y,int z) {
        var sample=base.sample(x,y,z);
        if(sample instanceof TerrainSample.Unavailable||floors.isEmpty()||y<minHeadY||y>maxHeadY||x<minX||x>maxX||z<minZ||z>maxZ)return sample;
        var floor=floors.get(new TrailNetwork.Column(x,z));
        return floor!=null&&(long)y>floor&&(long)y<=(long)floor+headroom?AIR:sample;
    }
}
