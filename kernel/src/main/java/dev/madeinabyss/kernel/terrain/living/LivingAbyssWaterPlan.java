package dev.madeinabyss.kernel.terrain.living;

import java.util.*;
import static dev.madeinabyss.kernel.terrain.living.LivingAbyssFeatureBlock.*;

/** Section17. Complete supported features are admitted before publication; no per-block clipping
 * of a basin, dam or channel can silently open it into a protected cave. */
public final class LivingAbyssWaterPlan {
    public record Feature(String id,String kind,int x,int y,int z,double width,int height,String owner,String conflict){}
    public record Outlet(String owner,int x,int y,int z,double theta,int width){}
    private record P(int x,int y,int z){}
    private record Ground(int top,int bottom){}
    private final LivingAbyssPlan plan;private final LivingAbyssGeometry geometry;private final LivingAbyssPlan.Solid allowed,solid;
    private final LivingAbyssVoxelPlan voxels;private final List<Feature> features;private final List<Outlet> outlets;
    private final LivingAbyssVoxelPlan.Builder building=new LivingAbyssVoxelPlan.Builder();
    private final ArrayList<Feature> metadata=new ArrayList<>();private final ArrayList<Outlet> falling=new ArrayList<>();
    private final Map<String,Map<Long,Ground>> grounds=new HashMap<>();
    private long buildNanos;private int terraceEligible,terraceSelected;
    public LivingAbyssWaterPlan(LivingAbyssPlan plan,LivingAbyssGeometry geometry,LivingAbyssPlan.Solid allowed,LivingAbyssPlan.Solid solid){
        long start=System.nanoTime();this.plan=plan;this.geometry=geometry;this.allowed=allowed;this.solid=solid;
        var terraces=new ArrayList<LivingAbyssPlan.Ledge>();
        for(var q:plan.ledges())if(q.archetype()==LivingAbyssPlan.Archetype.TERRACES||q.archetype()==LivingAbyssPlan.Archetype.ROCK_GARDEN)terraces.add(q);
        terraceEligible=terraces.size();terraces.sort(Comparator.comparingLong(q->hash(q.id().hashCode(),17,6)));terraceSelected=(int)Math.round(terraces.size()*.30);
        var chosen=new HashSet<String>();for(int i=0;i<terraceSelected;i++)chosen.add(terraces.get(i).id());
        for(var q:plan.ledges()){
            if(q.archetype()==LivingAbyssPlan.Archetype.LAKE)pond(q);
            else if(chosen.contains(q.id()))terraces(q);
            else if(q.archetype()==LivingAbyssPlan.Archetype.MEADOW)stream(q);
            grounds.remove(q.id());
        }
        falls();
        voxels=building.freeze();building.clear();features=List.copyOf(metadata);outlets=List.copyOf(falling);buildNanos=System.nanoTime()-start;
    }
    private long hash(long x,long y,long z){return LivingAbyssFields.mix(plan.recipe().seed()^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),21)^Long.rotateLeft(LivingAbyssFields.mix(z),42));}
    private double unit(long h){return LivingAbyssWall.unit(h);}
    private double noise(int x,int z,double scale,long salt){return .5+.5*LivingAbyssPlan.noise(plan.recipe().seed(),scale,x,0,z,salt);}
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    private Ground ground(LivingAbyssPlan.Ledge q,int x,int z){
        var map=grounds.computeIfAbsent(q.id(),k->new HashMap<>());long key=key(x,z);if(map.containsKey(key))return map.get(key);
        Ground result=null;for(var s:geometry.surfaces(x,z))if(s.ledge().id().equals(q.id())){result=new Ground(s.topY(),s.bottomY());break;}
        map.put(key,result);return result;
    }
    private final class Patch {
        final LinkedHashMap<P,Integer> cells=new LinkedHashMap<>();String conflict="";
        int at(int x,int y,int z){return cells.getOrDefault(new P(x,y,z),building.at(x,y,z));}
        void put(int x,int y,int z,LivingAbyssFeatureBlock b){
            if(!conflict.isEmpty())return;
            if(y>=0||y<plan.recipe().domain().knownLowerY()||(double)x*x+(double)z*z<380.0*380||(double)x*x+(double)z*z>=960.0*960||!allowed.at(x,y,z)){
                conflict="R380_OR_RESERVATION at "+x+","+y+","+z;return;}
            cells.put(new P(x,y,z),b.ordinal());
        }
        boolean sturdy(int x,int y,int z){int b=at(x,y,z);return b==0?solid.at(x,y,z):b!=AIR_CUT.ordinal()&&!isPlant(b)&&!isWater(b);}
        void supported(int x,int y,int z,LivingAbyssFeatureBlock b){
            int base=y-1;while(base>=y-32&&!sturdy(x,base,z))base--;
            if(base<y-32){conflict="FLOOR_SUPPORT at "+x+","+y+","+z;return;}
            for(int yy=base+1;yy<=y;yy++)put(x,yy,z,yy==y?b:STONE);
        }
        /** Places b on a column support when one exists within 32m; otherwise skips the cell. */
        boolean trySupported(int x,int y,int z,LivingAbyssFeatureBlock b){
            if(!conflict.isEmpty())return false;int base=y-1;while(base>=y-32&&!sturdy(x,base,z))base--;
            if(base<y-32)return false;for(int yy=base+1;yy<=y;yy++)put(x,yy,z,yy==y?b:STONE);return conflict.isEmpty();
        }
        void clear(int x,int z,int lo,int hi){for(int y=lo;y<=hi;y++)put(x,y,z,AIR_CUT);}
        boolean commit(Feature f){metadata.add(new Feature(f.id,f.kind,f.x,f.y,f.z,f.width,f.height,f.owner,conflict));
            if(!conflict.isEmpty())return false;cells.forEach((p,b)->building.put(p.x,p.y,p.z,b));return true;}
    }
    private int[] position(LivingAbyssPlan.Ledge q,double u,double lateral){
        double a=Math.toRadians(q.theta()),outer=geometry.wallBoundaryAt(q.theta(),q.y());
        double projection=Math.min(q.projection(),outer-380),r=outer-projection*u;
        return new int[]{(int)Math.round(r*Math.sin(a)+lateral*Math.cos(a)),(int)Math.round(-r*Math.cos(a)+lateral*Math.sin(a))};
    }
    private void pond(LivingAbyssPlan.Ledge q){
        long seed=hash(q.id().hashCode(),17,1);double R=13+4*unit(seed),D=6+2*unit(seed+1),shelf=2+2*unit(seed+2),phase=unit(seed+3)*2*Math.PI;
        double outer=geometry.wallBoundaryAt(q.theta(),q.y()),projection=Math.min(q.projection(),outer-380);
        double u=Math.max(.15,Math.min(.8,1-(R*1.34+6)/Math.max(1,projection)));int[] c=position(q,u,0);
        int cx=c[0],cz=c[1],pad=(int)Math.ceil(R*2.3);var patch=new Patch();int level=Integer.MAX_VALUE;
        for(int dx=-pad;dx<=pad;dx++)for(int dz=-pad;dz<=pad;dz++){
            double rr=pondRadius(dx,dz,R,phase);if(rr>=1&&rr<1.25){var g=ground(q,cx+dx,cz+dz);if(g!=null)level=Math.min(level,g.top);}
        }
        if(level==Integer.MAX_VALUE){patch.conflict="NO_SURROUNDING_RING";patch.commit(new Feature("pond-"+q.id(),"POND",cx,q.y(),cz,R*2,(int)Math.ceil(D),q.id(),""));return;}
        level--;final int waterLevel=level;
        // Depth is clamped per cell below: at least 4m of shelf rock stays under every bed.
        var depths=new HashMap<Long,Integer>();
        for(int dx=-pad;dx<=pad;dx++)for(int dz=-pad;dz<=pad;dz++){
            int x=cx+dx,z=cz+dz;double rr=pondRadius(dx,dz,R,phase);if(rr>=1.7)continue;var g=ground(q,x,z);
            if(rr<1.12&&g==null){patch.conflict="POND_FOOTPRINT at "+x+","+z;continue;}
            if(g==null)continue;
            if(rr<1){double d=LivingAbyssWaterShape.depth(rr,R,D,shelf,noise(x,z,6,1711));
                if(d<.3){patch.clear(x,z,level+2,g.top+2);patch.trySupported(x,level+1,z,SAND);continue;}
                int bottom=Math.max((int)Math.round(level-d),g.bottom+2);
                if(bottom>=level)bottom=level-1; // thin shelf: 1m shallows over its rock, never a dry sand cap
                if(bottom<g.bottom+2){patch.clear(x,z,level+2,g.top+2);patch.trySupported(x,level+1,z,SAND);continue;}
                patch.clear(x,z,bottom+1,g.top+2);
                double n=noise(x,z,6,1711);var mat=d<=1.6?n>.45?SAND:GRAVEL:d<3.5?n>.55?GRAVEL:CLAY:n>.35?MUD:CLAY;
                patch.supported(x,bottom-1,z,mat==MUD?CLAY:mat);patch.put(x,bottom,z,mat);
                for(int y=bottom+1;y<=level;y++)patch.put(x,y,z,WATER_SOURCE);depths.put(key(x,z),level-bottom);
                aquatic(patch,x,bottom,z,level,false,seed);
            }else{
                double t=Math.min(1,(rr-1)/.7);int top=(int)Math.round(level+1+Math.pow(t,1.3)*(g.top-level-1));
                if(top<g.top){patch.clear(x,z,top+1,g.top+2);patch.trySupported(x,top,z,GRASS);}
                if(rr<1.12){double n=noise(x,z,3,1712);int height=1+(n>.62?1:0)+(n>.8?1:0);patch.clear(x,z,level+height+1,g.top+2);
                    patch.trySupported(x,level+height,z,n<.45?MOSSY_COBBLESTONE:n<.7?STONE:ANDESITE);if(unit(hash(x,level,z))<.45)patch.put(x,level+height+1,z,MOSS);}
            }
        }
        // Connected bottom/shore boulders, and two sunken trunks which follow their actual bed.
        for(int i=0;i<7;i++){long h=hash(cx+i,level,cz);double a=unit(h)*2*Math.PI,rad=R*(.3+.8*unit(h+1));int x=cx+(int)Math.round(Math.cos(a)*rad),z=cz+(int)Math.round(Math.sin(a)*rad);
            Integer depth=depths.get(key(x,z));var g=ground(q,x,z);if(depth==null&&g==null)continue;int y=depth==null?g.top:level-depth;double r=1.2+1.4*unit(h+2);
            for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){double v=1-(dx*dx+dz*dz)/(r*r);if(v<=0)continue;int top=y+(int)Math.floor(r*.8*Math.sqrt(v));
                patch.trySupported(x+dx,top,z+dz,unit(h+dx*17+dz)<.6?MOSSY_COBBLESTONE:ANDESITE);}
        }
        for(int i=0;i<2;i++){int z=cz+(i==0?-3:3),lastY=Integer.MIN_VALUE;for(int x=cx-3;x<=cx+3;x++){
            Integer depth=depths.get(key(x,z));if(depth==null)continue;int y=level-depth+1;patch.put(x,y,z,OAK_LOG_X);
            if(lastY!=Integer.MIN_VALUE)for(int yy=Math.min(lastY,y);yy<=Math.max(lastY,y);yy++)patch.put(x,yy,z,OAK_LOG_X);lastY=y;}}
        Outlet outlet=outlet(patch,q,cx,cz,waterLevel,R*.75,3);
        var feature=new Feature("pond-"+q.id(),"POND",cx,waterLevel,cz,R*2,(int)Math.ceil(D),q.id(),"");
        if(patch.commit(feature)&&outlet!=null)falling.add(outlet);
    }
    private static double pondRadius(double x,double z,double r,double phase){double a=Math.atan2(z,x),warp=1+.22*Math.sin(3*a+phase)+.12*Math.sin(5*a+phase*1.71);return Math.hypot(x,z)/(r*warp);}
    private void aquatic(Patch p,int x,int bottom,int z,int level,boolean shallow,long seed){
        int depth=level-bottom;if(depth<1)return;long h=hash(x,bottom,z)^seed;double pick=unit(h);
        if(depth<=3.5&&pick<(shallow?.50:.75)*(.6+.8*noise(x,z,6,1711))){
            if(unit(h+1)<.45&&depth>=2){p.put(x,bottom+1,z,TALL_SEAGRASS_LOWER);p.put(x,bottom+2,z,TALL_SEAGRASS_UPPER);}else p.put(x,bottom+1,z,SEAGRASS);
        }else if(!shallow&&depth>=2.5&&pick<.38){
            // Mud has a lowered native collision/support face; kelp roots use the clay stratum.
            p.put(x,bottom,z,CLAY);int height=Math.max(1,Math.min(depth-1,2+(int)(unit(h+2)*Math.max(1,depth-2))));
            for(int y=bottom+1;y<=bottom+height;y++)p.put(x,y,z,y==bottom+height?KELP:KELP_PLANT);
        }
        if(depth>=1&&depth<=4&&noise(x,z,5,1713)>.76&&unit(h+3)<.5)p.put(x,level+1,z,LILY_PAD);
    }
    private Outlet outlet(Patch p,LivingAbyssPlan.Ledge q,int cx,int cz,int level,double start,int width){
        double a=Math.toRadians(q.theta());
        for(double turn:new double[]{0,Math.PI/2,-Math.PI/2}){
            double dx=-Math.sin(a+turn),dz=Math.cos(a+turn);int end=-1;
            for(int d=(int)start;d<q.projection()+q.width()+30;d++){int x=cx+(int)Math.round(dx*d),z=cz+(int)Math.round(dz*d);if(ground(q,x,z)==null){if(Math.hypot(x,z)>403)end=d;break;}}
            if(end<0)continue;int bed=level;var route=new ArrayList<int[]>();boolean valid=true;
            for(int d=(int)start;d<=end;d++){int x=cx+(int)Math.round(dx*d),z=cz+(int)Math.round(dz*d);var g=ground(q,x,z);
                if(g!=null){bed=Math.min(bed,Math.max(g.top-2,bed-1));if(g.top-bed>2){valid=false;break;}}route.add(new int[]{x,bed,z});}
            if(!valid)continue;
            for(var cell:route)channel(p,q,cell[0],cell[1],cell[2],width,dx,dz,MOSSY_COBBLESTONE);
            var last=route.get(route.size()-1);return new Outlet(q.id(),last[0],last[1],last[2],LivingAbyssFields.azimuth(last[0],last[2]),width);
        }
        return null; // closed, rimmed basin: no outlet is required
    }
    private void channel(Patch p,LivingAbyssPlan.Ledge q,int x,int bed,int z,int width,double dx,double dz,LivingAbyssFeatureBlock bottom){
        int half=width/2;for(int w=-half;w<=half;w++){int X=x+(int)Math.round(-dz*w),Z=z+(int)Math.round(dx*w);var g=ground(q,X,Z);
            p.clear(X,Z,bed+1,g==null?bed+2:Math.max(bed+2,g.top+2));p.supported(X,bed-1,Z,bottom);p.put(X,bed,Z,WATER_SOURCE);}
        for(int w:new int[]{-half-1,half+1}){int X=x+(int)Math.round(-dz*w),Z=z+(int)Math.round(dx*w);p.supported(X,bed+1,Z,MOSSY_COBBLESTONE);
            if(unit(hash(X,bed,Z))<.25&&p.at(X,bed+2,Z)==0)p.put(X,bed+2,Z,FERN);}
    }
    private void stream(LivingAbyssPlan.Ledge q){
        var patch=new Patch();int[] start=position(q,.12,-q.width()*.10);var first=ground(q,start[0],start[1]);if(first==null)return;
        int bed=first.top-1;double a=Math.toRadians(q.theta());int lastX=start[0],lastZ=start[1],length=Math.max(8,(int)(q.projection()*.7));boolean ended=false;
        for(int d=0;d<length;d++){
            double turn=2.5*Math.sin(d/7.0+q.id().hashCode());int x=start[0]-(int)Math.round(Math.sin(a)*d)+(int)Math.round(Math.cos(a)*turn),z=start[1]+(int)Math.round(Math.cos(a)*d)+(int)Math.round(Math.sin(a)*turn);
            var g=ground(q,x,z);if(g==null){if(Math.hypot(x,z)<403)patch.conflict="STREAM_R380_OUTLET";else ended=true;break;}
            if(g.top-1>bed+1){if(d<10)patch.conflict="STREAM_NON_MONOTONE_RELIEF";break;}bed=Math.min(bed,g.top-1);
            if(g.top-bed>2){if(d<10)patch.conflict="STREAM_TOO_DEEP";break;}
            // Supercover between meander samples keeps the water face-connected at negative seams.
            while(lastX!=x||lastZ!=z){if(lastX!=x)lastX+=Integer.signum(x-lastX);else lastZ+=Integer.signum(z-lastZ);channel(patch,q,lastX,bed,lastZ,1+(int)(unit(hash(x/9,bed,z/9))*3),-Math.sin(a),Math.cos(a),unit(hash(x,bed,z))<.5?GRAVEL:MOSSY_COBBLESTONE);}
            channel(patch,q,x,bed,z,1,-Math.sin(a),Math.cos(a),STONE);
        }
        // A stream which does not reach an edge ends in a supported, rimmed small spring bowl.
        if(!ended){for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){int x=lastX+dx,z=lastZ+dz;
            if(Math.abs(dx)==2||Math.abs(dz)==2)patch.supported(x,bed+1,z,MOSSY_COBBLESTONE);else{patch.supported(x,bed-1,z,GRAVEL);patch.put(x,bed,z,WATER_SOURCE);}}}
        if(patch.commit(new Feature("stream-"+q.id(),"STREAM",start[0],first.top,start[1],3,length,q.id(),""))&&ended)falling.add(new Outlet(q.id(),lastX,bed,lastZ,q.theta(),2));
    }
    private void terraces(LivingAbyssPlan.Ledge q){
        var patch=new Patch();long seed=hash(q.id().hashCode(),17,6);double R=8+3*unit(seed),a=Math.toRadians(q.theta());int[] c=position(q,.22,0);var g0=ground(q,c[0],c[1]);
        if(g0==null){patch.conflict="TERRACE_FOOTPRINT";patch.commit(new Feature("travertine-"+q.id(),"TRAVERTINE",c[0],q.y(),c[1],R*2,12,q.id(),""));return;}
        int level=g0.top-1,px=c[0],pz=c[1],lastX=px,lastZ=pz;
        for(int i=0;i<3;i++){
            int cx=px-(int)Math.round(Math.sin(a)*i*R*1.25),cz=pz+(int)Math.round(Math.cos(a)*i*R*1.25),lvl=level-i*4;int pad=(int)Math.ceil(R*1.6);
            for(int dx=-pad;dx<=pad;dx++)for(int dz=-pad;dz<=pad;dz++){
                double th=Math.atan2(dz,dx),rr=Math.hypot(dx,dz)/(R*(1+.15*Math.sin(3*th+i)+.08*Math.sin(5*th+2*i)));if(rr>=1.25)continue;
                int x=cx+dx,z=cz+dz;var g=ground(q,x,z);if(g==null){patch.conflict="TERRACE_FOOTPRINT at "+x+","+z;continue;}
                patch.clear(x,z,lvl+2,Math.max(lvl+2,g.top+2));
                if(rr<1){int bottom=lvl-(int)Math.ceil(1+2.2*Math.pow(1-rr,.7));patch.supported(x,bottom,z,unit(hash(x,bottom,z))<.4?SAND:GRAVEL);
                    for(int y=bottom+1;y<=lvl;y++)patch.put(x,y,z,WATER_SOURCE);aquatic(patch,x,bottom,z,lvl,true,seed);
                }else{patch.supported(x,lvl+1,z,unit(hash(x,lvl,z))<.7?CALCITE:MOSSY_COBBLESTONE);if(unit(hash(x,lvl+2,z))<.35)patch.put(x,lvl+2,z,MOSS);}
            }
            lastX=cx;lastZ=cz;
            if(i>0){int ax=px-(int)Math.round(Math.sin(a)*(i-1)*R*1.25),az=pz+(int)Math.round(Math.cos(a)*(i-1)*R*1.25);
                for(int d=0;d<=Math.ceil(R*1.25);d++){int x=ax-(int)Math.round(Math.sin(a)*d),z=az+(int)Math.round(Math.cos(a)*d);int y=d<R*.5?lvl+4:lvl;channel(patch,q,x,y,z,2,-Math.sin(a),Math.cos(a),CALCITE);
                    if(d>R*.5-1&&d<R*.5+1)for(int yy=lvl;yy<lvl+4;yy++)patch.put(x,yy,z,WATER_FALLING);}}
        }
        Outlet out=outlet(patch,q,lastX,lastZ,level-8,R*.8,2);
        if(patch.commit(new Feature("travertine-"+q.id(),"TRAVERTINE",px,level,pz,R*2,12,q.id(),""))&&out!=null)falling.add(out);
    }
    // Section22. Outlet lips continue as falling water; wall ribbons (wet side x3, top above d800);
    // the Seat of the Waterfall on the wet axis. Every fall ends in a supported basin or is rejected.
    private int fallsAccepted,fallsRejected;
    private void falls(){
        for(var o:List.copyOf(falling)){double a=Math.toRadians(o.theta());
            int x=(int)Math.round(o.x()-Math.sin(a)),z=(int)Math.round(o.z()+Math.cos(a));
            fall("lip-"+o.owner(),a,o.y(),Math.max(1,o.width()),Math.max(1,o.width()),new int[]{x,z});}
        double wet=plan.fields().wetDegrees();
        for(int k=0;k<36;k++){long h=hash(k,22,1);double theta=LivingAbyssFields.normalize(k*10+8*unit(h));
            double w=plan.fields().sideWeights(theta)[0];if(unit(h+1)>.12*(1+2*w))continue;
            int top=-60-(int)(unit(h+2)*560),width=1+(int)(unit(h+3)*3);
            for(double shift:new double[]{0,3,-3})if(fall("ribbon-"+k,Math.toRadians(theta+shift),top,width,width,null))break;}
        // Landmark: nearest admissible source to the wet axis inside d 230-260.
        seat:for(int step=0;step<=12;step++)for(int sign:new int[]{1,-1})for(int top:new int[]{-245,-235,-255}){
            if(step==0&&sign<0)continue;if(fall("seat-of-the-waterfall",Math.toRadians(wet+sign*step),top,9,20,null))break seat;}
    }
    private boolean air(Patch p,int x,int y,int z){int b=p.at(x,y,z);return b==0?!solid.at(x,y,z):b==AIR_CUT.ordinal();}
    /** First air cell in front of the wall face at this angle and height, or null inside the void band. */
    private int[] face(Patch p,double a,int y){
        double outer=geometry.wallBoundaryAt(Math.toDegrees(a),y);
        for(double r=outer+6;r>outer-40&&r>392;r-=1){int x=(int)Math.round(r*Math.sin(a)),z=(int)Math.round(-r*Math.cos(a));
            if(air(p,x,y,z))return new int[]{x,z};}
        return null;
    }
    private boolean fall(String id,double a,int top,int w0,int w1,int[] fixed){
        var patch=new Patch();int lower=Math.max(plan.recipe().domain().knownLowerY()+2,-1390);int landX=0,landY=Integer.MIN_VALUE,landZ=0,width=w0;
        if(fixed==null){int[] f=face(patch,a,top);if(f==null){patch.conflict="NO_WALL_FACE";}
            else for(int l=-w0/2;l<=w0/2;l++){double r=Math.hypot(f[0],f[1]),b=a+l/r;int x=(int)Math.round(r*Math.sin(b)),z=(int)Math.round(-r*Math.cos(b));
                // Source cleft: two metres cut into the wall, sources resting on rock.
                for(int d=1;d<=2;d++){int cx=(int)Math.round((r+d)*Math.sin(b)),cz=(int)Math.round(-(r+d)*Math.cos(b));patch.put(cx,top+1,cz,AIR_CUT);patch.supported(cx,top,cz,WATER_SOURCE);}
                if(air(patch,x,top,z))patch.put(x,top,z,WATER_FALLING);}}
        for(int y=top-1;y>lower&&patch.conflict.isEmpty();y--){
            width=w0+(int)Math.round((w1-w0)*Math.min(1,(top-y)/150.0));
            int[] c=fixed!=null?fixed:face(patch,a,y);if(c==null){patch.conflict="FACE_LOST at "+y;break;}
            if(!air(patch,c[0],y,c[1])){landX=c[0];landY=y+1;landZ=c[1];break;}
            if(!air(patch,c[0],y-1,c[1])&&!isWater(patch.at(c[0],y-1,c[1]))){
                // Land only on a real shelf (support 2m further from the wall); a flute bump is slid over.
                double rc=Math.hypot(c[0],c[1]),inward=Math.max(392,rc-2);int sx=(int)Math.round(c[0]*inward/rc),sz=(int)Math.round(c[1]*inward/rc);
                if(fixed!=null||!air(patch,sx,y-1,sz)){landX=c[0];landY=y;landZ=c[1];break;}
            }
            double r=Math.hypot(c[0],c[1]);
            for(int l=-width/2;l<=width/2;l++){double b=Math.atan2(c[0],-c[1])+l/r;int x=(int)Math.round(r*Math.sin(b)),z=(int)Math.round(-r*Math.cos(b));
                if(air(patch,x,y,z))patch.put(x,y,z,WATER_FALLING);}
        }
        if(patch.conflict.isEmpty()&&landY==Integer.MIN_VALUE)patch.conflict="NO_LANDING_ABOVE_FLOOR";
        if(patch.conflict.isEmpty()){int R=Math.max(2,Math.min(6,width/2+1));
            for(int dx=-R-1;dx<=R+1;dx++)for(int dz=-R-1;dz<=R+1;dz++){double rr=Math.hypot(dx,dz);int x=landX+dx,z=landZ+dz;
                if(rr<=R){if(patch.trySupported(x,landY-1,z,unit(hash(x,landY,z))<.5?GRAVEL:MOSSY_COBBLESTONE)){patch.clear(x,z,landY,landY+2);patch.put(x,landY,z,WATER_SOURCE);}}
                else if(rr<=R+1.2){patch.trySupported(x,landY,z,unit(hash(x,landY,z))<.6?MOSSY_COBBLESTONE:STONE);if(air(patch,x,landY+1,z)&&unit(hash(x,landY+1,z))<.3)patch.put(x,landY+1,z,MOSS);}}}
        if(patch.commit(new Feature(id,"FALL",landX,top,landZ,width,top-landY,"wall",patch.conflict))){fallsAccepted++;return true;}
        fallsRejected++;return false;
    }
    public int at(int x,int y,int z){return voxels.at(x,y,z);}public LivingAbyssVoxelPlan voxels(){return voxels;}
    public List<Feature> features(){return features;}public List<Outlet> outlets(){return outlets;}
    public Map<String,Object> statistics(){return Map.of("waterBuildMillis",buildNanos/1e6,"waterBytes",voxels.estimatedBytes(),"waterVoxels",voxels.voxelCount(),"terraceEligible",terraceEligible,"terraceSelected",terraceSelected,"fallsAccepted",fallsAccepted,"fallsRejected",fallsRejected,"waterFeatures",features);}
}
