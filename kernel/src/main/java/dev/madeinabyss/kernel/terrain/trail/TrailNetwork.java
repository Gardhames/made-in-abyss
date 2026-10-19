package dev.madeinabyss.kernel.terrain.trail;
import dev.madeinabyss.kernel.terrain.*;
import java.util.*;
/** Explicit selective RECONSTRUCTION proposal. Validates existing geology; never builds bridges. */
public final class TrailNetwork {
    @FunctionalInterface public interface SampleSource { TerrainSample sample(int x,int y,int z); }
    public record Point(int x,int floorY,int z) {}
    public record Parameters(int widthMetres,int supportDepthMetres,int headroomMetres,double maxRisePerMetre,int maxCutInsetMetres) {
        public Parameters {
            if(widthMetres<1 || widthMetres>15 || widthMetres%2==0 || supportDepthMetres<1
                    || supportDepthMetres>64 || headroomMetres<2 || headroomMetres>8
                    || !Double.isFinite(maxRisePerMetre) || maxRisePerMetre<=0 || maxRisePerMetre>1
                    || maxCutInsetMetres<1 || maxCutInsetMetres>16)
                throw new IllegalArgumentException("Explicit bounded walking corridor metres required");
        }
    }
    public record Route(String id,List<Point> points) {
        public Route { Objects.requireNonNull(id);points=List.copyOf(points);
            if(id.isBlank() || points.size()<2)throw new IllegalArgumentException("Route needs named endpoints"); }
    }
    record Column(int x,int z) {}
    private final SampleSource verifiedBase;
    private final Parameters parameters;
    private final List<Route> routes;
    private final Map<Column,Integer> floors;
    private TrailNetwork(SampleSource base,Parameters parameters,List<Route> routes,Map<Column,Integer> floors) {
        this.verifiedBase=base;this.parameters=parameters;this.routes=List.copyOf(routes);this.floors=Map.copyOf(floors);
    }
    public Parameters parameters(){return parameters;}
    public List<Route> routes(){return routes;}
    SampleSource verifiedBase(){return verifiedBase;}
    Integer floor(int x,int z){return floors.get(new Column(x,z));}
    public static TrailNetwork verify(SampleSource base,Parameters parameters,List<Route> routes) {
        Objects.requireNonNull(base);Objects.requireNonNull(parameters);routes=List.copyOf(routes);
        var floors=new HashMap<Column,Integer>();var ids=new HashSet<String>();
        for(var route:routes) {
            if(!ids.add(route.id()))throw new IllegalArgumentException("Duplicate route ID");
            for(int i=0;i<route.points().size();i++) {
                var point=route.points().get(i);
                var neighbour=route.points().get(i==0?1:i-1);
                long dx=(long)point.x()-neighbour.x(),dz=(long)point.z()-neighbour.z();
                if(Math.abs(dx)+Math.abs(dz)!=1 || Math.abs((long)point.floorY()-neighbour.floorY())>parameters.maxRisePerMetre())
                    throw new IllegalArgumentException("Route must walk cardinal one-metre steps within slope");
                for(int lateral=-parameters.widthMetres()/2;lateral<=parameters.widthMetres()/2;lateral++) {
                    int x=checked((long)point.x()+dz*lateral),z=checked((long)point.z()-dx*lateral);
                    for(int d=0;d<parameters.supportDepthMetres();d++)
                        if(!solid(base.sample(x,checked((long)point.floorY()-d),z)))
                            throw new IllegalArgumentException("Route requires existing known solid support");
                    boolean cutsRock=false;
                    for(int d=1;d<=parameters.headroomMetres();d++) {
                        var sample=base.sample(x,checked((long)point.floorY()+d),z);
                        if(!(sample instanceof TerrainSample.Known known) || known.material()==TerrainMaterial.WATER)
                            throw new IllegalArgumentException("Corridor cannot cut UNKNOWN or water");
                        if((i==0 || i==route.points().size()-1) && known.material()!=TerrainMaterial.AIR)
                            throw new IllegalArgumentException("Endpoint must already join exposed ground");
                        cutsRock|=known.material()!=TerrainMaterial.AIR;
                    }
                    if(cutsRock && !nearExterior(base,x,checked((long)point.floorY()+1),z,parameters.maxCutInsetMetres()))
                        throw new IllegalArgumentException("Corridor cut must hug an existing exposed cliff");
                    floors.merge(new Column(x,z),point.floorY(),Math::max);
                }
            }
        }
        // Width overlaps at bends must still yield a connected walkable floor, including between routes.
        for(var entry:floors.entrySet()) for(int[] delta:new int[][]{{1,0},{0,1}}) {
            long x=(long)entry.getKey().x()+delta[0],z=(long)entry.getKey().z()+delta[1];
            if(x>Integer.MAX_VALUE || z>Integer.MAX_VALUE)continue;
            var adjacent=floors.get(new Column((int)x,(int)z));
            if(adjacent!=null && Math.abs((long)entry.getValue()-adjacent)>parameters.maxRisePerMetre())
                throw new IllegalArgumentException("Overlapping route widths would form an unwalkable step");
        }
        return new TrailNetwork(base,parameters,routes,floors);
    }
    private static int checked(long coordinate){
        if(coordinate<Integer.MIN_VALUE || coordinate>Integer.MAX_VALUE)throw new IllegalArgumentException("Signed XYZ overflow");
        return (int)coordinate;
    }
    private static boolean nearExterior(SampleSource base,int x,int y,int z,int reach) {
        for(int d=1;d<=reach;d++)for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            long candidateX=(long)x+direction[0]*d,candidateZ=(long)z+direction[1]*d;
            if(candidateX<Integer.MIN_VALUE || candidateX>Integer.MAX_VALUE
                    || candidateZ<Integer.MIN_VALUE || candidateZ>Integer.MAX_VALUE)continue;
            if(base.sample((int)candidateX,y,(int)candidateZ) instanceof TerrainSample.Known known
                    && known.material()==TerrainMaterial.AIR)return true;
        }
        return false;
    }
    private static boolean solid(TerrainSample sample){return sample instanceof TerrainSample.Known k
        && (k.material()==TerrainMaterial.ROCK || k.material()==TerrainMaterial.SOIL || k.material()==TerrainMaterial.GRASS);}
}
