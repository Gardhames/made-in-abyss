package dev.madeinabyss.kernel.terrain.natural;

import dev.madeinabyss.kernel.terrain.*;
import java.util.*;

/** ORIGINAL v11: selective supported passages inside v10's irregular voids.
 * Source is the immutable weathered geology BEFORE v10 caves. Mouths on cliffs
 * remain climbing approaches; this does not declare the whole graph walkable. */
public final class SupportedCaveTerrainSampler {
    public record Foot(int x,int y,int z) {
        @Override public int hashCode(){
            return RouteVoxelIndex.hash(x,y,z);
        }
    }
    public record Route(int network,List<Foot> feet,boolean vertical) {
        public Route {feet=List.copyOf(feet);}
    }
    private final CaveNetworkTerrainSampler legacy;
    private final CaveNetworkTerrainSampler.Source source;
    private final CaveNetworkTerrainSampler.Protection protection;
    private final Set<Foot> floors=new HashSet<>(),clearance=new HashSet<>();
    private final Map<Foot,Integer> clearanceNetworks=new HashMap<>();
    private final RouteVoxelIndex routeVoxels;
    private final List<Route> routes;
    private final List<CaveNetworkTerrainSampler.Segment> segments;
    private final TerrainParameters parameters;
    private final CaveNetworkParameters recipe;
    private final ParametricTerrainSampler geometry;
    private final int minCellX,minCellY,minCellZ,cellsX,cellsY,cellsZ;
    private final int[][] caveIndex;
    private static final TerrainSample AIR=new TerrainSample.Known(TerrainMaterial.AIR);
    public static final int MAX_ROUTE_VOXELS=750000,MAX_ROUTE_STEPS=4096,MAX_SUPPORT_DEPTH=12;
    public SupportedCaveTerrainSampler(TerrainParameters p,CaveNetworkParameters recipe,
            CaveNetworkTerrainSampler.Source source,CaveNetworkTerrainSampler.Protection protection) {
        this.source=Objects.requireNonNull(source);this.protection=Objects.requireNonNull(protection);
        parameters=Objects.requireNonNull(p);this.recipe=Objects.requireNonNull(recipe);geometry=new ParametricTerrainSampler(p);
        legacy=new CaveNetworkTerrainSampler(p,recipe,source,protection);
        double outer=Math.max(p.cavity().mouthRadius(),p.cavity().lowerRadius())+p.noise().wallAmplitude()+recipe.maximumPenetration()+10;
        minCellX=minCellZ=Math.floorDiv((int)Math.floor(-outer),32);minCellY=Math.floorDiv(p.cavity().declaredLowerBoundY(),32);
        cellsX=cellsZ=Math.floorDiv((int)Math.ceil(outer),32)-minCellX+1;
        cellsY=Math.floorDiv((int)Math.ceil(p.cavity().firstLayerTopY()),32)-minCellY+1;
        caveIndex=new int[cellsX*cellsY*cellsZ][];buildCaveIndex();
        var admitted=new ArrayList<Route>();var extra=new ArrayList<CaveNetworkTerrainSampler.Segment>();
        // Sparse climbing alternatives: only one sector in eight receives a long returning
        // wall arc. Raw rock and mask admission can reject it; shafts are still retained.
        int sectors=(int)Math.ceil(2*Math.PI*p.cavity().mouthRadius()/recipe.sectorSpacing());
        var hubs=new TreeMap<Integer,CaveNetworkTerrainSampler.Point>();
        for(var e:legacy.segments())if(e.kind()==CaveNetworkTerrainSampler.Kind.CAVE)
            for(var q:List.of(e.a(),e.b()))hubs.compute(e.network(),(id,old)->old==null||Math.hypot(q.x(),q.z())>Math.hypot(old.x(),old.z())?q:old);
        for(var entry:hubs.entrySet())if(entry.getKey()%8==0){
            var target=hubs.get(entry.getKey()+sectors);if(target==null)continue;
            var a=entry.getValue();double angle=Math.atan2(a.z(),a.x());
            double bAngle=Math.atan2(target.z(),target.x());while(bAngle-angle>Math.PI)bAngle-=2*Math.PI;while(bAngle-angle< -Math.PI)bAngle+=2*Math.PI;
            var points=new ArrayList<CaveNetworkTerrainSampler.Point>();points.add(a);
            for(int i=1;i<100;i++){
                double t=i/100.0,theta=angle+(bAngle-angle)*t+Math.sin(Math.PI*t)*.48;
                double y=a.y()+(target.y()-a.y())*t;
                double rim=p.cavity().mouthRadius(),wall=geometry.cavityRadius((int)Math.round(rim*Math.cos(theta)),(int)Math.round(y),(int)Math.round(rim*Math.sin(theta)));
                for(int j=0;j<5;j++)wall=geometry.cavityRadius((int)Math.round(wall*Math.cos(theta)),(int)Math.round(y),(int)Math.round(wall*Math.sin(theta)));
                double r=wall+recipe.maximumPenetration()*.67+Math.sin(2*Math.PI*t)*5;points.add(new CaveNetworkTerrainSampler.Point(r*Math.cos(theta),y,r*Math.sin(theta)));
            }
            points.add(target);var feet=new ArrayList<Foot>();boolean valid=true;
            for(int i=1;i<points.size();i++){
                var q=points.get(i-1);var r=points.get(i);
                var part=line(new CaveNetworkTerrainSampler.Point(q.x(),a.y(),q.z()),new CaveNetworkTerrainSampler.Point(r.x(),a.y(),r.z()));
                if(part==null)continue;append(feet,part);
            }
            if(feet.size()<Math.abs(target.y()-a.y())+1)valid=false;
            for(int i=0;i<feet.size();i++){var f=feet.get(i);feet.set(i,new Foot(f.x(),(int)Math.round(a.y()-2+(target.y()-a.y())*i/(feet.size()-1.0)),f.z()));}
            if(valid&&admit(entry.getKey(),feet,true,admitted))for(int i=1;i<feet.size();i++){
                var f=feet.get(i-1);var g=feet.get(i);
                extra.add(new CaveNetworkTerrainSampler.Segment(new CaveNetworkTerrainSampler.Point(f.x(),f.y()+1,f.z()),new CaveNetworkTerrainSampler.Point(g.x(),g.y()+1,g.z()),.5,CaveNetworkTerrainSampler.Kind.CAVE,entry.getKey()));
            }
        }
        for(var e:legacy.segments())if(e.kind()==CaveNetworkTerrainSampler.Kind.CAVE){
            var feet=line(e.a(),e.b());if(feet!=null)admit(e.network(),feet,false,admitted);
        }
        routes=List.copyOf(admitted);var all=new ArrayList<>(legacy.segments());all.addAll(extra);segments=List.copyOf(all);
        // Admission guarantees floors and clearance do not overlap. Floor=0, clearance=network+1.
        var voxelCodes=new HashMap<Foot,Integer>();for(var q:floors)voxelCodes.put(q,0);
        for(var entry:clearanceNetworks.entrySet())voxelCodes.put(entry.getKey(),Math.addExact(entry.getValue(),1));
        routeVoxels=new RouteVoxelIndex(voxelCodes);
        floors.clear();clearance.clear();clearanceNetworks.clear();
    }
    private int indexCell(double v,int minimum,int size){return Math.max(0,Math.min(size-1,(int)Math.floor(v/32)-minimum));}
    private void buildCaveIndex(){
        int[] counts=new int[caveIndex.length];
        // Two passes preserve the legacy first-CAVE overlap order without temporary lists.
        for(int pass=0;pass<2;pass++){
            for(int id=0;id<legacy.segments().size();id++){
                var e=legacy.segments().get(id);if(e.kind()!=CaveNetworkTerrainSampler.Kind.CAVE)continue;
                double r=e.radius();
                int xa=indexCell(Math.min(e.a().x(),e.b().x())-r,minCellX,cellsX),xb=indexCell(Math.max(e.a().x(),e.b().x())+r,minCellX,cellsX);
                int ya=indexCell(Math.min(e.a().y(),e.b().y())-r,minCellY,cellsY),yb=indexCell(Math.max(e.a().y(),e.b().y())+r,minCellY,cellsY);
                int za=indexCell(Math.min(e.a().z(),e.b().z())-r,minCellZ,cellsZ),zb=indexCell(Math.max(e.a().z(),e.b().z())+r,minCellZ,cellsZ);
                for(int y=ya;y<=yb;y++)for(int z=za;z<=zb;z++)for(int x=xa;x<=xb;x++){
                    int bucket=x+cellsX*(z+cellsZ*y);
                    if(pass==0)counts[bucket]++;else caveIndex[bucket][counts[bucket]++]=id;
                }
            }
            if(pass==0)for(int i=0;i<counts.length;i++){if(counts[i]>0)caveIndex[i]=new int[counts[i]];counts[i]=0;}
        }
    }
    /** Exact cave identity without the unused penetration/noise/trigonometric diagnostics.
     * Classification remains geometric, including protected rock, as featureAt always was. */
    public int caveNetworkAt(int x,int y,int z){
        int override=routeVoxels.get(x,y,z);if(override>0)return override-1;
        int xx=Math.floorDiv(x,32)-minCellX,yy=Math.floorDiv(y,32)-minCellY,zz=Math.floorDiv(z,32)-minCellZ;
        if(xx<0||xx>=cellsX||yy<0||yy>=cellsY||zz<0||zz>=cellsZ)return -1;
        int[] bucket=caveIndex[xx+cellsX*(zz+cellsZ*yy)];if(bucket==null)return -1;
        if(!legacy.mayAffectShell(x,y,z))return -1;
        for(int id:bucket){var e=legacy.segments().get(id);
            double dx=e.b().x()-e.a().x(),dy=e.b().y()-e.a().y(),dz=e.b().z()-e.a().z();
            double px=x-e.a().x(),py=y-e.a().y(),pz=z-e.a().z(),length=dx*dx+dy*dy+dz*dz;
            double t=length==0?0:Math.max(0,Math.min(1,(px*dx+py*dy+pz*dz)/length));
            px-=dx*t;py-=dy*t;pz-=dz*t;
            if(px*px+py*py+pz*pz<e.radius()*e.radius())return e.network();
        }
        return -1;
    }
    private static void append(List<Foot> out,List<Foot> part){for(var q:part)if(out.isEmpty()||!out.get(out.size()-1).equals(q))out.add(q);}
    private static List<Foot> line(CaveNetworkTerrainSampler.Point a,CaveNetworkTerrainSampler.Point b){
        int ax=(int)Math.round(a.x()),az=(int)Math.round(a.z()),bx=(int)Math.round(b.x()),bz=(int)Math.round(b.z());
        int ay=(int)Math.round(a.y())-2,by=(int)Math.round(b.y())-2;
        int length=Math.abs(bx-ax)+Math.abs(bz-az);if(length<1||length>MAX_ROUTE_STEPS||Math.abs(by-ay)>length)return null;
        var out=new ArrayList<Foot>();int x=ax,z=az;out.add(new Foot(x,ay,z));
        for(int i=1;i<=length;i++){
            // Manhattan raster avoids diagonal corner cuts through solid blocks.
            double tx=ax+(bx-ax)*(i/(double)length),tz=az+(bz-az)*(i/(double)length);
            if(x!=bx&&(z==bz||Math.abs(tx-x)>=Math.abs(tz-z)))x+=Integer.signum(bx-ax);else z+=Integer.signum(bz-az);
            int y=ay+(int)Math.round((by-ay)*(i/(double)length));out.add(new Foot(x,y,z));
        }
        return out;
    }
    private boolean rock(Foot q){return source.sample(q.x(),q.y(),q.z()) instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.ROCK;}
    private boolean admit(int network,List<Foot> feet,boolean vertical,List<Route> admitted){
        if(feet.size()<2||feet.size()>MAX_ROUTE_STEPS||floors.size()+clearance.size()+feet.size()*4L>MAX_ROUTE_VOXELS)return false;
        var newFloors=new HashSet<Foot>();var newAir=new HashSet<Foot>();Foot previous=null;
        for(var f:feet){
            if(previous!=null&&(Math.abs(f.x()-previous.x())+Math.abs(f.z()-previous.z())!=1||Math.abs(f.y()-previous.y())>1))return false;
            previous=f;double depth=Math.hypot(f.x(),f.z())-geometry.cavityRadius(f.x(),f.y(),f.z());
            if(depth<3||depth>recipe.maximumPenetration()-4||f.y()<parameters.cavity().declaredLowerBoundY()+10||f.y()+3>=parameters.cavity().firstLayerTopY()-recipe.mouthProtection())return false;
            // Retain an original-rock buttress down to unchanged solid geology, rather
            // than leaving a one-voxel staircase suspended over the broader cave void.
            // Never fill source air or bridge another admitted route's headroom.
            boolean anchored=false;
            for(int depthBelow=1;depthBelow<=MAX_SUPPORT_DEPTH;depthBelow++){
                var support=new Foot(f.x(),f.y()-depthBelow,f.z());
                if(!rock(support)||clearance.contains(support)||protection.protectedAt(support.x(),support.y(),support.z()))return false;
                newFloors.add(support);
                if(depthBelow>=2&&legacy.sample(support.x(),support.y(),support.z()) instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.ROCK){anchored=true;break;}
            }
            if(!anchored)return false;
            for(int dy=0;dy<3;dy++){
                var q=new Foot(f.x(),f.y()+dy,f.z());if(floors.contains(q)||protection.protectedAt(q.x(),q.y(),q.z()))return false;
                var v=source.sample(q.x(),q.y(),q.z());if(!(v instanceof TerrainSample.Known k)||(k.material()!=TerrainMaterial.ROCK&&k.material()!=TerrainMaterial.AIR))return false;
                for(int[] d:NEIGHBOURS){var n=source.sample(q.x()+d[0],q.y()+d[1],q.z()+d[2]);if(!(n instanceof TerrainSample.Known nk)||nk.material()==TerrainMaterial.WATER)return false;}
                newAir.add(q);
            }
        }
        if(newFloors.stream().anyMatch(newAir::contains))return false;
        if(floors.size()+clearance.size()+newFloors.size()+newAir.size()>MAX_ROUTE_VOXELS)return false;
        floors.addAll(newFloors);clearance.addAll(newAir);for(var q:newAir)clearanceNetworks.put(q,network);admitted.add(new Route(network,feet,vertical));return true;
    }
    private static final int[][] NEIGHBOURS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    public TerrainSample sample(int x,int y,int z){int code=routeVoxels.get(x,y,z);if(code==0)return source.sample(x,y,z);if(code>0)return AIR;return legacy.sample(x,y,z);}
    public boolean mayAffectShell(int x,int y,int z){return routeVoxels.get(x,y,z)>0||legacy.mayAffectShell(x,y,z);}
    /** Resident immutable coarse route mask; zero means empty or exact-hash fallback. */
    public int routeOccupancyCells(){return routeVoxels.occupancyCellCount();}
    public CaveNetworkTerrainSampler.Feature featureAt(int x,int y,int z){
        int code=routeVoxels.get(x,y,z);
        return code<=0?legacy.featureAt(x,y,z):new CaveNetworkTerrainSampler.Feature(true,false,Math.hypot(x,z)-geometry.cavityRadius(x,y,z),0,code-1);
    }
    public List<CaveNetworkTerrainSampler.Segment> segments(){return segments;}
    public List<CaveNetworkTerrainSampler.Portal> portals(){return legacy.portals();}
    public List<Route> routes(){return routes;}
    public boolean routeProtectedAt(int x,int y,int z){return routeVoxels.get(x,y,z)>=0;}
}
