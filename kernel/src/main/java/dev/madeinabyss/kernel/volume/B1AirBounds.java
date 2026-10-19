package dev.madeinabyss.kernel.volume;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.living.*;
import java.util.*;

/** Conservative derived B1 cavity bounds, no generator identity mutation. */
public final class B1AirBounds {
    private record Box(double x0,double y0,double z0,double x1,double y1,double z1) {
        boolean intersects(long x,long y,long z){return x<=x1&&x+15>=x0&&y<=y1&&y+15>=y0&&z<=z1&&z+15>=z0;}
    }
    private final Map<VoxelVolumeArchive.PageKey,List<Box>> index;
    private final int objects;
    public B1AirBounds(List<LivingAbyssRevision155.Ledge> ledges,List<LivingAbyssRevision155.Hall> halls,List<LivingAbyssRevision155.Speleo> speleo,List<LivingAbyssFloor155B.Feature> floor){
        var boxes=new ArrayList<Box>();
        for(var l:ledges){
            // top noise+archetype offset+lip gives [top-28.2,top+10].
            // height<=1.15H; fillet<=1.6*22, ribs<=31.8. plan<=1.15P,
            // profile<=1, radial displacement<=quarter of reach: max1.4375P.
            double d0=l.top()-29,d1=l.top()+1.15*l.H()+46;
            boxes.add(sector(l.theta(),l.W()/LivingAbyssMath155.inner(l.top()),LivingAbyssMath155.inner(d1)-1.4375*l.P(),LivingAbyssMath155.inner(d0)+3,d0,d1));
        }
        for(var h:halls){
            // hallShape's own early guard encloses ALL arches, columns, bridges,
            // outer/through ledges and gates. Carves cannot invalidate an AIR proof.
            double d0=h.top()-2,d1=h.top()+h.h()+2;
            boxes.add(sector(h.theta(),(h.A()+2)/450,LivingAbyssMath155.inner(d1)-125,LivingAbyssMath155.inner(d0)+2*h.T()+1.625*h.G()+2,d0,d1));
        }
        for(var s:speleo){double pad=1.27*s.rb()+2,end=s.depth()+s.direction()*s.L();
            boxes.add(padded(s.x()-pad,-Math.max(s.depth(),end)-2,s.z()-pad,s.x()+pad,-Math.min(s.depth(),end)+2,s.z()+pad));}
        for(var f:floor){double pad=f.radius()+2,shift=Math.ceil((Math.abs(f.gx())+Math.abs(f.gz()))*pad);
            boxes.add(padded(f.x()-pad,f.baseY()-f.height()-shift-13,f.z()-pad,f.x()+pad,f.baseY()+f.height()+shift+3,f.z()+pad));}
        objects=boxes.size();var made=new HashMap<VoxelVolumeArchive.PageKey,ArrayList<Box>>();
        for(var b:boxes){for(double v:new double[]{b.x0,b.y0,b.z0,b.x1,b.y1,b.z1})if(!Double.isFinite(v)||Math.abs(v)>1000000)throw new IllegalArgumentException("Unbounded B1 descriptor");
            for(int y=(int)Math.floor(b.y0/128);y<=Math.floor(b.y1/128);y++)for(int z=(int)Math.floor(b.z0/128);z<=Math.floor(b.z1/128);z++)for(int x=(int)Math.floor(b.x0/128);x<=Math.floor(b.x1/128);x++)
                made.computeIfAbsent(new VoxelVolumeArchive.PageKey(x,y,z),k->new ArrayList<>()).add(b);
        }
        var frozen=new HashMap<VoxelVolumeArchive.PageKey,List<Box>>();made.forEach((key,value)->frozen.put(key,List.copyOf(value)));index=Map.copyOf(frozen);
    }
    private static Box padded(double x0,double y0,double z0,double x1,double y1,double z1){return new Box(Math.nextDown(x0-1),Math.nextDown(y0-1),Math.nextDown(z0-1),Math.nextUp(x1+1),Math.nextUp(y1+1),Math.nextUp(z1+1));}
    private static Box sector(double thetaDegrees,double half,double near,double far,double d0,double d1){
        if(!Double.isFinite(half)||half<0||!Double.isFinite(near)||!Double.isFinite(far)||far<Math.max(0,near))throw new IllegalArgumentException("Invalid B1 sector");
        near=Math.max(0,near);double centre=Math.toRadians(thetaDegrees);var angles=new ArrayList<Double>();angles.add(centre-half);angles.add(centre+half);
        for(int i=0;i<4;i++){double a=i*Math.PI/2,delta=Math.abs(Math.atan2(Math.sin(a-centre),Math.cos(a-centre)));if(half>=Math.PI||delta<=half+1e-12)angles.add(a);}
        double x0=Double.POSITIVE_INFINITY,x1=Double.NEGATIVE_INFINITY,z0=x0,z1=x1;
        for(double a:angles)for(double r:new double[]{near,far}){double x=r*Math.sin(a),z=-r*Math.cos(a);x0=Math.min(x0,x);x1=Math.max(x1,x);z0=Math.min(z0,z);z1=Math.max(z1,z);}
        return padded(x0,-d1,z0,x1,-d0,z1);
    }
    public boolean guaranteedAir(CubeKey key){
        long x=(long)key.x()*16,y=(long)key.y()*16,z=(long)key.z()*16;
        if(y< -1350||y+15>=0||x<Integer.MIN_VALUE||x+15>Integer.MAX_VALUE||z<Integer.MIN_VALUE||z+15>Integer.MAX_VALUE)return false;
        double maxX=Math.max(Math.abs((double)x),Math.abs((double)x+15)),maxZ=Math.max(Math.abs((double)z),Math.abs((double)z+15));
        // inner is monotonically decreasing on this interval. Global wall relief<=20;
        // upper tier noses<=14. One extra metre is a conservative rounding margin.
        if(!(Math.hypot(maxX,maxZ)<LivingAbyssMath155.inner(-((double)y))-21))return false;
        var candidates=index.get(VoxelVolumeArchive.PageKey.ofBrick(key.x(),key.y(),key.z()));
        if(candidates!=null)for(var box:candidates)if(box.intersects(x,y,z))return false;
        return true;
    }
    public Map<String,Object> status(){return Map.of("objects",objects,"indexed128mPages",index.size(),"wallReliefCap",20,"margin",1);}
}
