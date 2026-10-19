package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.terrain.living.*;
import java.util.*;

/** Section24 (v15.4): natural cave mouths replace the old rectangular entrance boxes. Each v14
 * portal gets a bent superellipse tunnel from its cave interior to the actual v15 face, a flared
 * noisy rim, a flat walkable floor and one of four decoration kinds. Pure immutable descriptors. */
final class LivingAbyssCaveMouths {
    enum Kind { MOSS_CURTAIN, DRIP_TEETH, CLEFT, OVERGROWN_ARCH }
    record Mouth(int id,Kind kind,double theta,int floorY,double rIn,double rOut,double width,double height,double bend,long seed,int minX,int maxX,int minZ,int maxZ){}
    /** Where a cell lies relative to the nearest mouth: value<1 inside, depth 0 inner .. 1 at face. */
    record Hit(Mouth mouth,double value,double depth,double t,double r){}
    private final List<Mouth> mouths;
    /** 16 m XZ cells -> mouths whose bounding box touches the cell, in original list order (tie-breaking is unchanged). */
    private final dev.madeinabyss.kernel.concurrent.FrozenCubeTable<Mouth[]> cells;
    private final long seed;
    record Surface(double radius,double nx,double ny,double nz){}
    interface PortalFace { Surface at(double theta,int y,double portalRadius); }
    private record Vec(double x,double y,double z){
        Vec add(Vec v){return new Vec(x+v.x,y+v.y,z+v.z);}Vec sub(Vec v){return new Vec(x-v.x,y-v.y,z-v.z);}
        Vec scale(double s){return new Vec(x*s,y*s,z*s);}double dot(Vec v){return x*v.x+y*v.y+z*v.z;}
        Vec unit(){double n=Math.sqrt(dot(this));return n<1e-12?new Vec(1,0,0):scale(1/n);}
        Vec cross(Vec v){return new Vec(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x);}
    }
    /** Hermite centreline: exact face normal at the lip, original cave direction inside. */
    private record Frame(Vec start,Vec end,Vec first,Vec last,double length){
        Vec at(double t){double t2=t*t,t3=t2*t;return start.scale(2*t3-3*t2+1).add(first.scale(t3-2*t2+t)).add(end.scale(-2*t3+3*t2)).add(last.scale(t3-t2));}
        Vec tangent(double t){return start.scale(6*t*t-6*t).add(first.scale(3*t*t-4*t+1)).add(end.scale(-6*t*t+6*t)).add(last.scale(3*t*t-2*t));}
    }
    private final Frame[] frames;
    interface Face { double radius(double theta,int y); }
    LivingAbyssCaveMouths(long seed,List<int[]> portals,Face face){
        this(seed,portals,face,null);
    }
    LivingAbyssCaveMouths(long seed,List<int[]> portals,PortalFace face){this(seed,portals,null,face);}
    private LivingAbyssCaveMouths(long seed,List<int[]> portals,Face face,PortalFace portalFace){
        this.seed=seed;var out=new ArrayList<Mouth>();var localFrames=new ArrayList<Frame>();int id=0;
        for(var p:portals){
            double theta=Math.atan2(p[0],-p[2]),pr=Math.hypot(p[0],p[2]);if(pr<400)continue;
            if(portalFace!=null&&(p[1]>=0||p[1]<-1350))continue;
            long h=LivingAbyssFields.mix(seed^0x5ca1ab1eL^(long)p[0]*73856093L^(long)p[1]*19349663L^(long)p[2]*83492791L);
            var kind=Kind.values()[(int)Math.floorMod(h,4L)];
            double jitter=1.0+.6*unit(h+1),jitter2=1.0+.5*unit(h+2);
            double w=switch(kind){case MOSS_CURTAIN->7;case DRIP_TEETH->12;case CLEFT->4;case OVERGROWN_ARCH->8;}*jitter;
            double ht=switch(kind){case MOSS_CURTAIN->9;case DRIP_TEETH->6;case CLEFT->16;case OVERGROWN_ARCH->8;}*jitter2;
            Surface surface=portalFace==null?null:portalFace.at(theta,p[1]+(int)Math.round(ht/2),pr);
            double sface=portalFace==null?face.radius(theta,p[1]+(int)Math.round(ht/2)):surface==null?Double.NaN:surface.radius;
            if(!Double.isFinite(sface))continue;
            double rOut=sface-2,rIn=Math.max(pr+4,sface+10);
            double bend=(unit(h+3)*2-1)*Math.min(4,w*.4);
            double reach=rIn+2,pad=w*1.4+Math.abs(bend)+(portalFace==null?4:12);
            double[] xs={(rOut-2)*Math.sin(theta),reach*Math.sin(theta)},zs={-(rOut-2)*Math.cos(theta),-reach*Math.cos(theta)};
            int minX=(int)Math.floor(Math.min(xs[0],xs[1])-pad),maxX=(int)Math.ceil(Math.max(xs[0],xs[1])+pad);
            int minZ=(int)Math.floor(Math.min(zs[0],zs[1])-pad),maxZ=(int)Math.ceil(Math.max(zs[0],zs[1])+pad);
            out.add(new Mouth(id++,kind,theta,p[1],rIn,rOut,w,ht,bend,h,minX,maxX,minZ,maxZ));
            if(surface!=null){double cy=p[1]+ht/2-.5;var radial=new Vec(Math.sin(theta),0,-Math.cos(theta));
                var start=radial.scale(sface).add(new Vec(0,cy,0));var end=radial.scale(rIn).add(new Vec(0,cy,0));
                var normal=new Vec(surface.nx,surface.ny,surface.nz).unit();if(normal.dot(radial)<0)normal=normal.scale(-1);
                double len=Math.max(1,rIn-sface),handle=Math.min(24,2*len);
                localFrames.add(new Frame(start,end,normal.scale(handle),radial.scale(handle),len));}
        }
        frames=portalFace==null?null:localFrames.toArray(Frame[]::new);
        mouths=List.copyOf(out);
        var staged=new HashMap<dev.madeinabyss.kernel.cube.CubeKey,ArrayList<Mouth>>();
        for(var m:mouths)for(int cx=m.minX>>4;cx<=m.maxX>>4;cx++)for(int cz=m.minZ>>4;cz<=m.maxZ>>4;cz++)
            staged.computeIfAbsent(new dev.madeinabyss.kernel.cube.CubeKey(cx,0,cz),k->new ArrayList<>()).add(m);
        var frozen=new HashMap<dev.madeinabyss.kernel.cube.CubeKey,Mouth[]>();staged.forEach((k,v)->frozen.put(k,v.toArray(Mouth[]::new)));
        cells=new dev.madeinabyss.kernel.concurrent.FrozenCubeTable<>(frozen);
    }
    List<Mouth> mouths(){return mouths;}
    private static double unit(long h){return LivingAbyssWall.unit(h);}
    /** Superellipse membership with a 1.6 rim shell; null when no mouth is near. */
    Hit hit(int x,int y,int z){
        if(frames!=null)return hit3d(x,y,z);
        Hit best=null;
        var near=cells.get(x>>4,0,z>>4);if(near==null)return null;
        for(var m:near){
            if(x<m.minX||x>m.maxX||z<m.minZ||z>m.maxZ||y<m.floorY-8||y>m.floorY+m.height*1.5+3)continue;
            double r=Math.sqrt((double)x*x+(double)z*z);if(r<m.rOut-8||r>m.rIn+1)continue;
            double s=Math.max(0,Math.min(1,(m.rIn-r)/(m.rIn-m.rOut)));
            double da=Math.atan2(x,-z)-m.theta;da=Math.atan2(Math.sin(da),Math.cos(da));
            double t=da*r-m.bend*Math.sin(Math.PI*s);
            double flare=1+.35*smooth((s-.6)/.4);
            double cy=m.floorY+m.height/2-.5,v=y-cy;
            double n=.26*LivingAbyssPlan.noise(m.seed,3.5,(int)Math.round(t*1.7),(int)Math.round(v*1.7),(int)Math.round(s*9),2401)+.14*LivingAbyssPlan.noise(m.seed,1.5,(int)Math.round(t*3.1),(int)Math.round(v*3.1),(int)Math.round(s*17),2403);
            double e=m.kind==Kind.CLEFT?1.7:m.kind==Kind.DRIP_TEETH?2.0:1.9;
            double value=Math.pow(Math.abs(t/(m.width*flare/2)),e)+Math.pow(Math.abs(v/(m.height*flare/2)),e);
            value/=Math.max(.4,1+n);
            if(best==null||value<best.value)best=new Hit(m,value,s,t,r);
        }
        return best;
    }
    private Hit hit3d(int x,int y,int z){var near=cells.get(x>>4,0,z>>4);if(near==null)return null;Hit best=null;var q=new Vec(x,y,z);double r=Math.hypot(x,z);
        for(var m:near){if(x<m.minX||x>m.maxX||z<m.minZ||z>m.maxZ||y<m.floorY-8||y>m.floorY+m.height*1.5+3||r<m.rOut-8||r>m.rIn+2)continue;
            var f=frames[m.id];double t=Math.max(0,Math.min(1,(r-m.rOut-2)/f.length));
            for(int iteration=0;iteration<2;iteration++){var at=f.at(t);var tangent=f.tangent(t);double length2=tangent.dot(tangent);
                if(length2>1e-9)t=Math.max(0,Math.min(1,t+q.sub(at).dot(tangent)/length2));}
            var at=f.at(t);var axis=f.tangent(t).unit();var delta=q.sub(at);
            if(t==0&&delta.dot(axis)<-3||t==1&&delta.dot(axis)>3)continue;
            var side=new Vec(-axis.z,0,axis.x).unit();var up=side.cross(axis).unit();
            double u=delta.dot(side)-m.bend*Math.sin(Math.PI*t),v=-delta.dot(up),angle=LivingAbyssMouthShape155B.axisAngle(Math.toDegrees(m.theta));
            double cs=Math.cos(angle),sn=Math.sin(angle),ru=u*cs+v*sn,rv=-u*sn+v*cs,depth=1-t,flare=1+.35*smooth((depth-.6)/.4);
            double value=LivingAbyssMouthShape155B.sectionValue(seed,m.seed,ru/flare,rv/flare,m.width/2,m.height/2,-y);
            if(best==null||value<best.value)best=new Hit(m,value,depth,u,r);
        }return best;
    }
    private static double smooth(double t){t=Math.max(0,Math.min(1,t));return t*t*(3-2*t);}
    /** Carved air: inside the section and on or above the flat floor. */
    boolean carve(Hit h,int y){return h!=null&&h.value<1&&y>=h.mouth.floorY;}
    long cellHash(int x,int y,int z){return LivingAbyssFields.mix(seed^(long)x*0x9E3779B97F4A7C15L^(long)y*0xC2B2AE3D27D4EB4FL^(long)z*0x165667B19E3779F9L);}
    /** Rim/floor rock dressing for an exposed solid cell near the face; NONE when untouched. */
    LivingAbyssFeatureBlock rimRock(Hit h,int x,int y,int z){
        if(h==null||h.depth<.55)return LivingAbyssFeatureBlock.NONE;
        double u=unit(cellHash(x,y,z));var m=h.mouth;
        boolean floor=y==m.floorY-1&&h.value<1.15;
        if(!floor&&(h.value<1||h.value>1.7))return LivingAbyssFeatureBlock.NONE;
        return switch(m.kind){
            case MOSS_CURTAIN->floor?LivingAbyssFeatureBlock.MOSS:u<.45?LivingAbyssFeatureBlock.MOSS:u<.75?LivingAbyssFeatureBlock.MOSSY_COBBLESTONE:LivingAbyssFeatureBlock.NONE;
            case OVERGROWN_ARCH->floor?(u<.6?LivingAbyssFeatureBlock.MOSS:LivingAbyssFeatureBlock.GRASS):u<.5?LivingAbyssFeatureBlock.MOSSY_COBBLESTONE:u<.8?LivingAbyssFeatureBlock.MOSS:LivingAbyssFeatureBlock.NONE;
            case DRIP_TEETH->floor?(u<.5?LivingAbyssFeatureBlock.GRAVEL:u<.7?LivingAbyssFeatureBlock.COBBLESTONE:LivingAbyssFeatureBlock.NONE):u<.35?LivingAbyssFeatureBlock.CALCITE:u<.7?LivingAbyssFeatureBlock.DRIPSTONE:LivingAbyssFeatureBlock.NONE;
            case CLEFT->floor?(u<.3?LivingAbyssFeatureBlock.MOSS:LivingAbyssFeatureBlock.NONE):u<.4?LivingAbyssFeatureBlock.TUFF:u<.7?LivingAbyssFeatureBlock.ANDESITE:LivingAbyssFeatureBlock.NONE;
        };
    }
    /** Curtain column density by kind; MOSS_CURTAIN dense, DRIP_TEETH none. */
    double curtainDensity(Mouth m){return switch(m.kind){case MOSS_CURTAIN->.75;case OVERGROWN_ARCH->.5;case CLEFT->.3;case DRIP_TEETH->0;};}
    double curtainLength(Mouth m,int x,int z){return 2+unit(cellHash(x,0,z)+11)*m.height*.7;}
    boolean curtainColumn(Mouth m,int x,int z){return unit(cellHash(x,0,z)+13)<curtainDensity(m);}
    /** Scree apron on the shelf below the lip: one supported layer of rubble, kind-tinted. */
    LivingAbyssFeatureBlock rubble(Hit h,int x,int y,int z){
        if(h==null)return LivingAbyssFeatureBlock.NONE;var m=h.mouth;double out=m.rOut-h.r;
        if(y>=m.floorY||y<m.floorY-7||out<-1||out>7||Math.abs(h.t)>m.width*.55*(1-Math.max(0,out)/9))return LivingAbyssFeatureBlock.NONE;
        double u=unit(cellHash(x,y,z)+21);if(u>.55*(1-Math.max(0,out)/8))return LivingAbyssFeatureBlock.NONE;
        double k=unit(cellHash(x,y,z)+22);
        return switch(m.kind){
            case DRIP_TEETH->k<.4?LivingAbyssFeatureBlock.COBBLESTONE:k<.7?LivingAbyssFeatureBlock.GRAVEL:LivingAbyssFeatureBlock.CALCITE;
            case CLEFT->k<.5?LivingAbyssFeatureBlock.TUFF:LivingAbyssFeatureBlock.ANDESITE;
            default->k<.45?LivingAbyssFeatureBlock.MOSSY_COBBLESTONE:k<.7?LivingAbyssFeatureBlock.ANDESITE:LivingAbyssFeatureBlock.MOSS;
        };
    }
    /** Air dressing: ceiling hangers (solid sturdy above) and floor plants (on the dressed floor). */
    LivingAbyssFeatureBlock airDressing(Hit h,int x,int y,int z,boolean ceiling){
        if(h==null||h.value>=1||h.depth<.45)return LivingAbyssFeatureBlock.NONE;
        double u=unit(cellHash(x,y,z)+7);var m=h.mouth;
        if(ceiling)return switch(m.kind){
            case MOSS_CURTAIN->u<.45?LivingAbyssFeatureBlock.HANGING_ROOTS:u<.62?LivingAbyssFeatureBlock.GLOW_UP:LivingAbyssFeatureBlock.NONE;
            case DRIP_TEETH->u<.55?LivingAbyssFeatureBlock.DRIP_TIP:LivingAbyssFeatureBlock.NONE;
            case CLEFT->u<.3?LivingAbyssFeatureBlock.GLOW_UP:u<.5?LivingAbyssFeatureBlock.HANGING_ROOTS:LivingAbyssFeatureBlock.NONE;
            case OVERGROWN_ARCH->u<.3?LivingAbyssFeatureBlock.HANGING_ROOTS:LivingAbyssFeatureBlock.NONE;
        };
        if(y!=m.floorY||h.depth<.55)return LivingAbyssFeatureBlock.NONE;
        return switch(m.kind){
            case MOSS_CURTAIN->u<.35?LivingAbyssFeatureBlock.MOSS_CARPET:u<.6?LivingAbyssFeatureBlock.FERN:LivingAbyssFeatureBlock.NONE;
            case OVERGROWN_ARCH->u<.3?LivingAbyssFeatureBlock.SHORT_GRASS:u<.55?LivingAbyssFeatureBlock.FERN:u<.7?LivingAbyssFeatureBlock.MOSS_CARPET:LivingAbyssFeatureBlock.NONE;
            case CLEFT->u<.25?LivingAbyssFeatureBlock.MOSS_CARPET:LivingAbyssFeatureBlock.NONE;
            case DRIP_TEETH->LivingAbyssFeatureBlock.NONE;
        };
    }
}
