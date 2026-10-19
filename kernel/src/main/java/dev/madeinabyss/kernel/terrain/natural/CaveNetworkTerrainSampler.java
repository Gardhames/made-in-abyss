package dev.madeinabyss.kernel.terrain.natural;

import dev.madeinabyss.kernel.terrain.*;
import java.util.*;

public final class CaveNetworkTerrainSampler {
    @FunctionalInterface public interface Source {TerrainSample sample(int x,int y,int z);}
    @FunctionalInterface public interface Protection {boolean protectedAt(int x,int y,int z);}
    public enum Kind {CAVE,FRACTURE}
    public record Point(double x,double y,double z){}
    public record Segment(Point a,Point b,double radius,Kind kind,int network){}
    public record Portal(Point position,int network){}
    public record Feature(boolean cave,boolean fracture,double penetration,double distance,int network){}
    private static final TerrainSample AIR=new TerrainSample.Known(TerrainMaterial.AIR);
    private static final int CELL=32;
    private static final int[][] NEIGHBOURS={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private final Source source;
    private final Protection protection;
    private final TerrainParameters parameters;
    private final CaveNetworkParameters recipe;
    private final ParametricTerrainSampler geometry;
    private final List<Segment> segments;
    private final List<Portal> portals;
    private final int minX,minY,minZ,nx,ny,nz;
    private final int[][] index;
    private final double outerRadius;
    private record WallPoint(double angle,double y,double inset){}

    public CaveNetworkTerrainSampler(TerrainParameters p,CaveNetworkParameters recipe,Source source,Protection protection){
        this.parameters=Objects.requireNonNull(p);this.recipe=Objects.requireNonNull(recipe);
        this.source=Objects.requireNonNull(source);this.protection=Objects.requireNonNull(protection);geometry=new ParametricTerrainSampler(p);
        outerRadius=Math.max(p.cavity().mouthRadius(),p.cavity().lowerRadius())+p.noise().wallAmplitude()+recipe.maximumPenetration()+10;
        minX=minZ=Math.floorDiv((int)Math.floor(-outerRadius),CELL);minY=Math.floorDiv(p.cavity().declaredLowerBoundY(),CELL);
        nx=nz=Math.floorDiv((int)Math.ceil(outerRadius),CELL)-minX+1;
        ny=Math.floorDiv((int)Math.ceil(p.cavity().firstLayerTopY()),CELL)-minY+1;
        if(nx<1||ny<1||nz<1||(long)nx*ny>2_000_000L/nz)throw new IllegalArgumentException("Cave spatial index exceeds bounded first-layer budget");
        var edges=new ArrayList<Segment>();var openings=new ArrayList<Portal>();plan(edges,openings);
        if(edges.size()>100000)throw new IllegalArgumentException("Cave graph exceeds100000 segment budget");
        segments=List.copyOf(edges);portals=List.copyOf(openings);index=new int[nx*ny*nz][];
        var buckets=new HashMap<Integer,ArrayList<Integer>>();
        for(int i=0;i<segments.size();i++){
            var e=segments.get(i);double r=e.radius();
            int xa=cell(Math.min(e.a().x(),e.b().x())-r,minX,nx),xb=cell(Math.max(e.a().x(),e.b().x())+r,minX,nx);
            int ya=cell(Math.min(e.a().y(),e.b().y())-r,minY,ny),yb=cell(Math.max(e.a().y(),e.b().y())+r,minY,ny);
            int za=cell(Math.min(e.a().z(),e.b().z())-r,minZ,nz),zb=cell(Math.max(e.a().z(),e.b().z())+r,minZ,nz);
            for(int yy=ya;yy<=yb;yy++)for(int zz=za;zz<=zb;zz++)for(int xx=xa;xx<=xb;xx++)
                buckets.computeIfAbsent(xx+nx*(zz+nz*yy),unused->new ArrayList<>()).add(i);
        }
        for(var entry:buckets.entrySet())index[entry.getKey()]=entry.getValue().stream().mapToInt(Integer::intValue).toArray();
    }
    private static int cell(double coordinate,int minimum,int size){return Math.max(0,Math.min(size-1,(int)Math.floor(coordinate/CELL)-minimum));}

    private void plan(ArrayList<Segment> out,ArrayList<Portal> openings){
        var c=parameters.cavity();int sectors=(int)Math.ceil(2*Math.PI*c.mouthRadius()/recipe.sectorSpacing());
        int bands=(int)Math.floor((c.firstLayerTopY()-recipe.mouthProtection()-c.declaredLowerBoundY()-64)/recipe.verticalSpacing());
        if((long)sectors*bands>5000)throw new IllegalArgumentException("Cave network count exceeds5000");
        double step=2*Math.PI/sectors;
        var junctions=new HashMap<Integer,WallPoint>();
        for(int band=0;band<bands;band++)for(int sector=0;sector<sectors;sector++){
            int id=band*sectors+sector;double a=(sector+0.08*random(id,1))*step;
            double y=c.firstLayerTopY()-recipe.mouthProtection()-40-band*recipe.verticalSpacing()+random(id,2)*10;
            var p0=mouth(new WallPoint(a-step*.25,y+12,-2));
            var hub=new WallPoint(a,y,16+random(id,3)*2);
            var deep=new WallPoint(a+step*.18,y+10,recipe.maximumPenetration()*.67);
            var lower=new WallPoint(a-step*.08,y-22,recipe.maximumPenetration()*.48);
            var p1=mouth(new WallPoint(a+step*.32,y-20,-2));
            var local=new ArrayList<Segment>();double portalR=recipe.portalRadius()+Math.max(0,random(id,4))*Math.min(2.5,6-recipe.portalRadius());
            boolean valid=path(local,p0,hub,portalR,Kind.CAVE,id,true)&path(local,hub,deep,recipe.tunnelRadius(),Kind.CAVE,id,true)
                    &path(local,deep,lower,recipe.tunnelRadius(),Kind.CAVE,id,true)&path(local,lower,hub,recipe.tunnelRadius(),Kind.CAVE,id,true)
                    &path(local,lower,p1,portalR,Kind.CAVE,id,true);
            if(!valid||!portalOpens(p0,local)||!portalOpens(p1,local))continue;
            // Selected irregular junction halls: overlapping rounded lobes, not identical spheres.
            if(random(id,5)>.15){
                Point center=point(deep);double radius=6+Math.max(0,random(id,6))*3;
                local.add(new Segment(center,center,radius,Kind.CAVE,id));
                var lobe=point(new WallPoint(deep.angle()+step*.035,deep.y()-3,deep.inset()-2));
                local.add(new Segment(lobe,lobe,radius*.72,Kind.CAVE,id));
            }
            // Branching shallow sheet joints share irregular hubs, with horizontal/diagonal/vertical arms.
            var joint=new WallPoint(a-step*.2,y+30,recipe.shallowDepth()*.55);
            for(int branch=0;branch<5;branch++){
                double dy=branch==0?0:branch==1?27:branch==2?-31:random(id,20+branch)*28;
                var end=new WallPoint(a+(branch-2)*step*.17+random(id,30+branch)*step*.04,y+30+dy,recipe.shallowDepth()*.55);
                path(local,joint,end,1.0+Math.max(0,random(id,40+branch))*.25,Kind.FRACTURE,id,false);
                var fork=new WallPoint(end.angle()+step*.1*random(id,50+branch),end.y()-12,recipe.shallowDepth()*.55);
                path(local,end,fork,.85,Kind.FRACTURE,id,false);
            }
            out.addAll(local);openings.add(new Portal(point(p0),id));openings.add(new Portal(point(p1),id));junctions.put(id,deep);
        }
        // Interconnect admitted two-mouth networks; skipped bridges never strand a local component.
        for(int id:junctions.keySet().stream().sorted().mapToInt(Integer::intValue).toArray()){
            int band=id/sectors,sector=id%sectors;
            for(int next:new int[]{band*sectors+(sector+1)%sectors,id+sectors}){
                var target=junctions.get(next);if(target==null||random(id,90+next)<-.25)continue;
                var from=junctions.get(id);
                // Wrap the angular seam through the short arc, never drill across the shaft.
                double angle=target.angle();while(angle-from.angle()>Math.PI)angle-=2*Math.PI;while(angle-from.angle()< -Math.PI)angle+=2*Math.PI;
                var bridge=new ArrayList<Segment>();
                if(path(bridge,from,new WallPoint(angle,target.y(),target.inset()),recipe.tunnelRadius(),Kind.CAVE,id,true))out.addAll(bridge);
            }
        }
    }
    private boolean path(ArrayList<Segment> out,WallPoint a,WallPoint b,double radius,Kind kind,int id,boolean verify){
        double span=Math.abs(b.angle()-a.angle())*parameters.cavity().mouthRadius()+Math.abs(b.y()-a.y())+Math.abs(b.inset()-a.inset());
        int steps=Math.max(2,(int)Math.ceil(span/7));Point previous=pointFor(a,kind);double previousWall=Math.hypot(previous.x(),previous.z())-a.inset();
        for(int i=1;i<=steps;i++){
            double t=i/(double)steps,warp=Math.sin(Math.PI*t)*random(id,71+i)*1.1;
            var wp=new WallPoint(a.angle()+(b.angle()-a.angle())*t,a.y()+(b.y()-a.y())*t+warp,a.inset()+(b.inset()-a.inset())*t);
            Point next=pointFor(wp,kind);double wall=Math.hypot(next.x(),next.z())-wp.inset();
            if(Math.abs(wall-previousWall)>14)return false;
            if(verify&&!admitted(next,radius))return false;
            out.add(new Segment(previous,next,radius,kind,id));previous=next;previousWall=wall;
        }
        return true;
    }
    private boolean admitted(Point point,double radius){
        int x=(int)Math.round(point.x()),y=(int)Math.round(point.y()),z=(int)Math.round(point.z());
        // Conservatively probe the center and radius-extreme safeguards before admitting a local loop.
        for(int[] d:NEIGHBOURS){int xx=x+(int)Math.ceil(radius)*d[0],yy=y+(int)Math.ceil(radius)*d[1],zz=z+(int)Math.ceil(radius)*d[2];
            if(protection.protectedAt(xx,yy,zz))return false;
            var v=source.sample(xx,yy,zz);if(!(v instanceof TerrainSample.Known k)||k.material()==TerrainMaterial.WATER)return false;
        }
        return !protection.protectedAt(x,y,z);
    }
    private Point point(WallPoint p){
        double cos=Math.cos(p.angle()),sin=Math.sin(p.angle());double rim=parameters.cavity().mouthRadius();
        double wall=geometry.cavityRadius((int)Math.round(rim*cos),(int)Math.round(p.y()),(int)Math.round(rim*sin));
        // Existing ledge relief includes XYZ noise. Resolve against the actual local wall,
        // instead of using only its fixed-radius rim probe as an invented mouth surface.
        for(int i=0;i<5;i++)wall=geometry.cavityRadius((int)Math.round(wall*cos),(int)Math.round(p.y()),(int)Math.round(wall*sin));
        return new Point((wall+p.inset())*cos,p.y(),(wall+p.inset())*sin);
    }
    private WallPoint mouth(WallPoint nominal){
        Point wall=point(new WallPoint(nominal.angle(),nominal.y(),0));double r=Math.hypot(wall.x(),wall.z());
        // Old weathering may already recess this surface by12m. Attach portals to the
        // supplied immutable source's first solid rock, not an obsolete analytical skin.
        for(int d=0;d<=18;d++){
            int x=(int)Math.round(wall.x()*(r+d)/r),y=(int)Math.round(wall.y()),z=(int)Math.round(wall.z()*(r+d)/r);
            var v=source.sample(x,y,z);
            if(v instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.ROCK)return new WallPoint(nominal.angle(),nominal.y(),d-2);
        }
        return nominal;
    }
    private Point pointFor(WallPoint p,Kind kind){
        if(kind==Kind.CAVE)return point(p);
        var surface=mouth(new WallPoint(p.angle(),p.y(),-2));
        return point(new WallPoint(p.angle(),p.y(),surface.inset()+2+p.inset()));
    }
    private boolean portalOpens(WallPoint p,List<Segment> local){
        var mouth=point(p);double r=Math.hypot(mouth.x(),mouth.z());
        for(int d=0;d<=9;d++){
            int x=(int)Math.round(mouth.x()*(r+d)/r),y=(int)Math.round(mouth.y()),z=(int)Math.round(mouth.z()*(r+d)/r);
            var original=source.sample(x,y,z);
            if(!(original instanceof TerrainSample.Known k)||k.material()!=TerrainMaterial.ROCK||protection.protectedAt(x,y,z))continue;
            double depth=Math.hypot(x,z)-geometry.cavityRadius(x,y,z);if(depth< -2||depth>recipe.maximumPenetration())continue;
            boolean sealed=false;
            for(var delta:NEIGHBOURS){var v=source.sample(x+delta[0],y+delta[1],z+delta[2]);if(!(v instanceof TerrainSample.Known n)||n.material()==TerrainMaterial.WATER){sealed=true;break;}}
            if(sealed)continue;
            for(var e:local)if(distanceSquared(e,x,y,z)<e.radius()*e.radius())return true;
        }
        return false;
    }
    private double random(int network,int salt){
        long h=recipe.seed()^((long)network*0x9e3779b97f4a7c15L)^((long)salt*0xbf58476d1ce4e5b9L);
        h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;h^=h>>>31;
        return (h>>>11)*0x1.0p-53*2-1;
    }
    public TerrainSample sample(int x,int y,int z){
        TerrainSample original=source.sample(x,y,z);
        if(!(original instanceof TerrainSample.Known k)||k.material()!=TerrainMaterial.ROCK||!mayAffectShell(x,y,z)||protection.protectedAt(x,y,z))return original;
        var edge=hit(x,y,z);if(edge==null)return original;
        double penetration=Math.hypot(x,z)-geometry.cavityRadius(x,y,z);
        if(penetration>recipe.maximumPenetration()||penetration< -2)return original;
        // Keep known water and unknown neighbours sealed; protection can also exclude future hydrology.
        for(var d:NEIGHBOURS){var v=source.sample(x+d[0],y+d[1],z+d[2]);if(!(v instanceof TerrainSample.Known n)||n.material()==TerrainMaterial.WATER)return original;}
        return AIR;
    }
    public boolean mayAffectShell(int x,int y,int z){
        if(y<parameters.cavity().declaredLowerBoundY()+8||y>=parameters.cavity().firstLayerTopY()-recipe.mouthProtection())return false;
        return (double)x*x+(double)z*z<=outerRadius*outerRadius && bucket(x,y,z)!=null;
    }
    private int[] bucket(int x,int y,int z){
        int xx=Math.floorDiv(x,CELL)-minX,yy=Math.floorDiv(y,CELL)-minY,zz=Math.floorDiv(z,CELL)-minZ;
        if(xx<0||xx>=nx||yy<0||yy>=ny||zz<0||zz>=nz)return null;
        return index[xx+nx*(zz+nz*yy)];
    }
    private Segment hit(int x,int y,int z){
        int[] candidates=bucket(x,y,z);if(candidates==null)return null;
        Segment fracture=null;
        for(int i:candidates){var e=segments.get(i);if(distanceSquared(e,x,y,z)<e.radius()*e.radius()){
            if(e.kind()==Kind.CAVE)return e;fracture=e;
        }}
        return fracture;
    }
    private static double distanceSquared(Segment e,double x,double y,double z){
        double dx=e.b().x()-e.a().x(),dy=e.b().y()-e.a().y(),dz=e.b().z()-e.a().z();
        double px=x-e.a().x(),py=y-e.a().y(),pz=z-e.a().z(),length=dx*dx+dy*dy+dz*dz;
        double t=length==0?0:Math.max(0,Math.min(1,(px*dx+py*dy+pz*dz)/length));
        px-=dx*t;py-=dy*t;pz-=dz*t;return px*px+py*py+pz*pz;
    }
    public Feature featureAt(int x,int y,int z){
        if(!mayAffectShell(x,y,z))return new Feature(false,false,0,0,-1);
        var e=hit(x,y,z);if(e==null)return new Feature(false,false,0,0,-1);
        double penetration=Math.hypot(x,z)-geometry.cavityRadius(x,y,z);
        return new Feature(e.kind()==Kind.CAVE,e.kind()==Kind.FRACTURE,penetration,Math.sqrt(distanceSquared(e,x,y,z)),e.network());
    }
    public List<Segment> segments(){return segments;}
    public List<Portal> portals(){return portals;}
}
