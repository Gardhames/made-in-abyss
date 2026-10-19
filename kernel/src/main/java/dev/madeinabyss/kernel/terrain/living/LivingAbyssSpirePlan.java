package dev.madeinabyss.kernel.terrain.living;

import java.util.*;
import dev.madeinabyss.kernel.terrain.TerrainParameters;

/** Immutable analytic pillars plus basal saddles. A supported descriptor is admitted
 * before any cube is sampled, and all tree footprints lie inside its flat grassy cap. */
public final class LivingAbyssSpirePlan {
    public enum Rejection { SIDE_MULTIPLIER,WALL_DISTANCE,R380,LEDGE,OVERLAP,SUPPORT,PATH,PORTAL,TREE_SPACE }
    @FunctionalInterface public interface RejectedAt { Rejection reason(int x,int y,int z); }
    public record Tree(int x,int y,int z,int height,boolean birch) {}
    public record Spire(String id,int group,int zone,int x,int z,int baseY,int topY,double radius,double topRadius,
                        double theta,double wallDistance,int saddleThickness,String support,List<Tree> trees) {
        public Spire {trees=List.copyOf(trees);}
    }
    private final List<Spire> spires;
    private final Map<String,LivingAbyssSpireShape> shapes=new HashMap<>();
    private final Map<Long,List<Spire>> index;
    private final LivingAbyssGeometry base;
    private final LivingAbyssWall wall;
    private final String digest;
    private final long seed;
    private final int[] beforeSide=new int[3],attempted=new int[3],accepted=new int[3];
    private final int[][] rejections=new int[3][Rejection.values().length];
    public LivingAbyssSpirePlan(TerrainParameters p,LivingAbyssPlan plan,LivingAbyssGeometry base,LivingAbyssPlan.Solid allowed) {
        this(p,plan,base,allowed,(x,y,z)->Rejection.SUPPORT);
    }
    public LivingAbyssSpirePlan(TerrainParameters p,LivingAbyssPlan plan,LivingAbyssGeometry base,LivingAbyssPlan.Solid allowed,RejectedAt rejectedAt) {
        this.base=base;wall=base.wallField();seed=plan.recipe().seed();var r=plan.recipe();var s=r.spires();var out=new ArrayList<Spire>();
        if(s!=null){double circle=2*Math.PI*p.cavity().lowerRadius();int serial=0,group=0;
            int[] mins={0,120,450},maxs={120,450,800};
            for(int zone=0;zone<3;zone++){
                double maxMultiplier=zone==2?s.karstMultiplier():s.outsideKarstMultiplier();
                int clusters=Math.max(1,(int)Math.round(circle/100*s.frequenciesPer100m()[zone]/3.5*maxMultiplier));
                for(int k=0;k<clusters;k++,group++){
                    long hash=LivingAbyssFields.mix(r.seed()+zone*7919L+k*104729L);double theta=360*(k+LivingAbyssWall.unit(hash))/clusters;
                    double karst=plan.fields().sideWeights(theta)[1],multiplier=1+karst*((zone==2?s.karstMultiplier():s.outsideKarstMultiplier())-1);
                    int members=Math.min(5,s.groupSize()[0]+(int)(LivingAbyssWall.unit(hash+1)*(s.groupSize()[1]-s.groupSize()[0]+1)));
                    beforeSide[zone]+=members;
                    if(LivingAbyssWall.unit(hash+17)>multiplier/maxMultiplier){reject(zone,Rejection.SIDE_MULTIPLIER,members);continue;}
                    for(int n=0;n<members;n++){
                        attempted[zone]++;
                        double angle=LivingAbyssFields.normalize(theta+(n-(members-1)/2.0)*4.5);
                        long h=LivingAbyssFields.mix(hash+n*8191L);double rad=pick(h,s.diameter())/2.0;
                        int top=-mins[zone]-Math.max(8,(int)(LivingAbyssWall.unit(h+2)*(maxs[zone]-mins[zone]-12)));
                        int height=pick(h+3,s.height());if(zone==0)height=Math.min(height,110);
                        int bottom=top-height;double a=Math.toRadians(angle);
                        double outer=base.wallBoundaryAt(angle,bottom);
                        double distance=pick(h+4,s.wallDistance());
                        // A pillar must clear the actual fluted wall at EVERY height,
                        // not just the wider wall at its base. Otherwise upper cave cuts
                        // can slice an apparently supported descriptor into loose pieces.
                        double inner=outer;
                        for(int yy=bottom;yy<=top;yy++)inner=Math.min(inner,base.wallBoundaryAt(angle,yy)-(wall==null?0:wall.protrusion(angle,-((double)yy))));
                        double center=inner-distance-rad;
                        if(outer-center-rad>s.wallDistance()[1]){reject(zone,Rejection.WALL_DISTANCE,1);continue;}
                        if(center-rad<r.domain().emptyRadius()+3){reject(zone,Rejection.R380,1);continue;}
                        int x=(int)Math.round(center*Math.sin(a)),z=(int)Math.round(-center*Math.cos(a));
                        if(Math.hypot(x,z)-rad<r.domain().emptyRadius()+3){reject(zone,Rejection.R380,1);continue;}
                        boolean covered=false;for(int yy=top+1;yy<=top+8;yy++)if(base.solid(x,yy,z)){covered=true;break;}if(covered){reject(zone,Rejection.LEDGE,1);continue;}
                        // No intersections between pillars, including neighbouring angular groups.
                        boolean collision=false;for(var q:out)if(Math.hypot(x-q.x,z-q.z)<rad+q.radius+5&&bottom<=q.topY&&top>=q.baseY){collision=true;break;}
                        if(collision){reject(zone,Rejection.OVERLAP,1);continue;}
                        int anchorX=(int)Math.round((outer+4)*Math.sin(a)),anchorZ=(int)Math.round(-(outer+4)*Math.cos(a));
                        if(!base.solid(anchorX,bottom-s.saddleThickness()/2,anchorZ)){reject(zone,Rejection.SUPPORT,1);continue;}
                        if(!allowed.at(anchorX,bottom,anchorZ)){reject(zone,rejectedAt.reason(anchorX,bottom,anchorZ),1);continue;}
                        boolean blocked=false;Rejection reason=Rejection.SUPPORT;
                        for(double rr=center-rad;rr<=outer+4&&!blocked;rr+=3)for(int yy=bottom-s.saddleThickness();yy<=bottom;yy+=3)
                            for(int lateral:new int[]{-(int)rad/2,0,(int)rad/2}){
                                int px=(int)Math.round(rr*Math.sin(a)+lateral*Math.cos(a)),pz=(int)Math.round(-rr*Math.cos(a)+lateral*Math.sin(a));
                                if(!allowed.at(px,yy,pz)){blocked=true;reason=rejectedAt.reason(px,yy,pz);break;}
                            }
                        if(blocked){reject(zone,reason,1);continue;}
                        for(int yy=bottom;yy<=top;yy++)if(!allowed.at(x,yy,z)){blocked=true;reason=rejectedAt.reason(x,yy,z);break;}
                        if(blocked){reject(zone,reason,1);continue;}
                        // The final sampler's cave/path predicate is authoritative. Probe
                        // the entire basal spine at voxel resolution, including cube seams.
                        for(double rr=Math.hypot(x,z);rr<=outer+3;rr+=.5){
                            int px=(int)Math.round(rr*Math.sin(a)),pz=(int)Math.round(-rr*Math.cos(a));
                            if(!allowed.at(px,bottom-1,pz)){blocked=true;reason=rejectedAt.reason(px,bottom-1,pz);break;}
                        }
                        if(blocked){reject(zone,reason,1);continue;}
                        double topRadius=rad*s.topRadiusRatio();int count=Math.min(5,Math.max(1,(int)((topRadius-2)/3)));
                        var trees=new ArrayList<Tree>();
                        for(int j=0;j<count;j++){
                            double ta=2*Math.PI*j/count,rr=count==1?0:Math.max(0,topRadius-4);
                            int tx=(int)Math.round(x+rr*Math.cos(ta)),tz=(int)Math.round(z+rr*Math.sin(ta));
                            int treeHeight=4+(int)(LivingAbyssWall.unit(h+j+5)*3);boolean free=true;
                            for(int dy=1;dy<=treeHeight+2;dy++)if(base.solid(tx,top+dy,tz)||!allowed.at(tx,top+dy,tz)){free=false;break;}
                            if(free)trees.add(new Tree(tx,top+1,tz,treeHeight,(h+j)%2==0));
                        }
                        if(trees.isEmpty()){reject(zone,Rejection.TREE_SPACE,1);continue;}
                        String id="spire-"+serial++;
                        if(r.part3b()){
                            // Section18: baked prototype silhouette; the forest cap follows the real summit.
                            var shape=new LivingAbyssSpireShape(LivingAbyssSpireShape.choose(h+11),h+13,Math.max(3,Math.min(24,rad)),Math.max(40,Math.min(220,top-bottom)));
                            int peak=0;for(var c:shape.summit())peak=Math.max(peak,c[1]);
                            var capped=new ArrayList<Tree>();
                            var summit=new ArrayList<>(shape.summit());summit.sort(Comparator.comparingInt((int[] c)->-c[1]).thenComparingLong(c->LivingAbyssFields.mix(h^c[0]*31L^c[2])));
                            for(var c:summit){if(capped.size()>=count)break;int tx=x+c[0],ty=bottom+c[1]+1,tz=z+c[2];
                                boolean spaced=true;for(var t:capped)if(Math.abs(t.x()-tx)+Math.abs(t.z()-tz)<5){spaced=false;break;}if(!spaced)continue;
                                int treeHeight=4+(int)(LivingAbyssWall.unit(h+capped.size()+5)*3);boolean free=true;
                                for(int dy=0;dy<=treeHeight+2;dy++)if(base.solid(tx,ty+dy,tz)||!allowed.at(tx,ty+dy,tz)||shape.solid(c[0],c[1]+1+dy,c[2])){free=false;break;}
                                if(free)capped.add(new Tree(tx,ty,tz,treeHeight,(h+capped.size())%2==0));}
                            if(capped.isEmpty()){reject(zone,Rejection.TREE_SPACE,1);continue;}
                            shapes.put(id,shape);trees=capped;top=bottom+peak;
                        }
                        out.add(new Spire(id,group,zone,x,z,bottom,top,rad,topRadius,angle,distance,s.saddleThickness(),"SADDLE",trees));
                        accepted[zone]++;
                    }
                }
            }
        }
        spires=List.copyOf(out);var map=new HashMap<Long,List<Spire>>();
        for(var q:spires){double a=Math.toRadians(q.theta),outer=Math.hypot(q.x,q.z)+q.radius+q.wallDistance+12;
            int ax=(int)Math.round(outer*Math.sin(a)),az=(int)Math.round(-outer*Math.cos(a));var shape=shapes.get(q.id);int pad=shape==null?(int)Math.ceil(q.radius)+3:shape.pad()+1;
            for(int ix=Math.floorDiv(Math.min(q.x,ax)-pad,32);ix<=Math.floorDiv(Math.max(q.x,ax)+pad,32);ix++)
                for(int iz=Math.floorDiv(Math.min(q.z,az)-pad,32);iz<=Math.floorDiv(Math.max(q.z,az)+pad,32);iz++)map.computeIfAbsent(key(ix,iz),v->new ArrayList<>()).add(q);
        }
        var frozen=new HashMap<Long,List<Spire>>();map.forEach((k,v)->frozen.put(k,List.copyOf(v)));index=Map.copyOf(frozen);
        digest=LivingAbyssDigest.of(shapes.isEmpty()?"spires-v15.2-local-flutes":"spires-v15.4-section18",spires.toString());
    }
    public List<Spire> candidates(int x,int z){return index.getOrDefault(key(Math.floorDiv(x,32),Math.floorDiv(z,32)),List.of());}
    /** Certificate-only extents of the existing solid geometry. Baked columns may
     * have holes: their min/max deliberately retains those holes as obstacles. */
    public void visitCertificateObstacles(Spire q,int x,int z,LivingAbyssGeometry.VerticalRange ranges){
        var shape=shapes.get(q.id);int lo=Integer.MAX_VALUE,hi=Integer.MIN_VALUE;
        if(shape!=null){
            int bottom=shape.minimumY(x-q.x,z-q.z),top=shape.maximumY(x-q.x,z-q.z);
            if(bottom!=Integer.MAX_VALUE&&q.baseY+bottom<=q.topY)ranges.accept(q.baseY+bottom,Math.min(q.topY,q.baseY+top));
        }else{
            double dx=x-q.x,dz=z-q.z,r=Math.max(q.radius,q.topRadius);
            if(dx*dx+dz*dz<=r*r)for(int y=q.baseY;y<=q.topY;y++)if(solid(q,x,y,z)){lo=Math.min(lo,y);hi=y;}
            if(lo<=hi)ranges.accept(lo,hi);
        }
        lo=Integer.MAX_VALUE;hi=Integer.MIN_VALUE;
        for(int y=q.baseY-q.saddleThickness;y<q.baseY;y++)if(solid(q,x,y,z)){lo=Math.min(lo,y);hi=y;}
        if(lo<=hi)ranges.accept(lo,hi);
    }
    public boolean solid(Spire q,int x,int y,int z){
        if(y<q.baseY-q.saddleThickness||y>q.topY)return false;
        var shape=shapes.get(q.id);
        if(shape!=null&&y>=q.baseY)return shape.solid(x-q.x,y-q.baseY,z-q.z);
        if(y>=q.baseY){double t=(y-q.baseY)/(double)(q.topY-q.baseY),r=q.radius+(q.topRadius-q.radius)*t;
            double dx=x-q.x,dz=z-q.z,theta=LivingAbyssFields.azimuth(dx,dz);
            if(dx*dx+dz*dz>r*r)return false; // ribs only reduce this radius
            double boundary=r-fluting(q,theta,y);return dx*dx+dz*dz<=boundary*boundary;
        }
        double a=Math.toRadians(q.theta),radial=x*Math.sin(a)-z*Math.cos(a),tangent=x*Math.cos(a)+z*Math.sin(a);
        double center=Math.hypot(q.x,q.z),outer=base.wallRadius(x,y,z)+4;
        double rem=(y-(q.baseY-q.saddleThickness))/(double)q.saddleThickness;
        return radial>=center-q.radius&&radial<=outer&&Math.abs(tangent)<=q.radius*.7*Math.sqrt(Math.max(.01,rem));
    }
    public boolean shaped(Spire q){return shapes.containsKey(q.id);}
    public List<Spire> spires(){return spires;}public String digest(){return digest;}
    private void reject(int zone,Rejection reason,int count){rejections[zone][Objects.requireNonNull(reason).ordinal()]+=count;}
    public Map<String,Object> admissionStats(){var result=new LinkedHashMap<String,Object>();
        for(int zone=0;zone<3;zone++){var row=new LinkedHashMap<String,Object>();row.put("beforeSide",beforeSide[zone]);row.put("attempted",attempted[zone]);row.put("accepted",accepted[zone]);var rejected=new LinkedHashMap<String,Integer>();
            for(var reason:Rejection.values())rejected.put(reason.name(),rejections[zone][reason.ordinal()]);row.put("rejected",Collections.unmodifiableMap(rejected));result.put("zone-"+zone,Collections.unmodifiableMap(row));}return Collections.unmodifiableMap(result);}
    private int fluteCount(Spire q){int min=(int)Math.ceil(2*Math.PI*q.radius/6),max=Math.max(min,(int)Math.floor(2*Math.PI*q.topRadius/3));
        double period=3+3*LivingAbyssWall.unit(seed^q.id.hashCode());return Math.max(min,Math.min(max,(int)Math.round(2*Math.PI*q.radius/period)));}
    public double flutePeriod(Spire q){return 2*Math.PI*q.radius/fluteCount(q);}
    public double fluteMaximumDepth(Spire q){return .6+.9*LivingAbyssWall.unit(seed^q.id.hashCode()^0x15f10eL);}
    public double fluting(Spire q,double theta,int y){
        int count=fluteCount(q);double xx=LivingAbyssFields.normalize(theta)/360*count,yy=(y-(double)q.baseY)/(15*flutePeriod(q));
        long ix=(long)Math.floor(xx),iy=(long)Math.floor(yy),salt=LivingAbyssFields.mix(q.id.hashCode());double fx=LivingAbyssFields.smooth(xx-ix),fy=LivingAbyssFields.smooth(yy-iy);
        double a=lattice(Math.floorMod(ix,count),iy,salt),b=lattice(Math.floorMod(ix+1,count),iy,salt),c=lattice(Math.floorMod(ix,count),iy+1,salt),d=lattice(Math.floorMod(ix+1,count),iy+1,salt);
        double noise=(a+(b-a)*fx)*(1-fy)+(c+(d-c)*fx)*fy,ridge=1-Math.abs(2*noise-1);return fluteMaximumDepth(q)*ridge*ridge;
    }
    private double lattice(long x,long y,long salt){return LivingAbyssWall.unit(seed^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),31)^salt);}
    private static int pick(long h,int[] r){return r[0]+(int)(LivingAbyssWall.unit(h)*(r[1]-r[0]+1));}
    private static long key(int x,int z){return LivingAbyssFields.mix(((long)x<<32)^(z&0xffffffffL));}
}
