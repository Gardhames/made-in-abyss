package dev.madeinabyss.kernel.terrain.living;

import java.util.*;

/** Clustered moss clumps and fixed-XZ vertical vine columns. Every admitted vine voxel
 * has its own full-face support; growth stops at the FIRST unsupported row. */
public final class LivingAbyssGreenPlan {
    public record Clump(double theta,double depth,double width,double height) {}
    public record Vine(int x,int z,int topY,List<Integer> masks) {public Vine{masks=List.copyOf(masks);}}
    private final List<Clump> clumps;
    private final List<Vine> vines;
    private final Map<Long,List<Vine>> columns;
    private final List<List<Clump>> angular;
    private final LivingAbyssPlan plan;
    private final LivingAbyssGeometry geometry;
    private final LivingAbyssPlan.Solid placementAllowed;
    private final String digest;
    private final double circumference;
    private int requestedVoxels,unsupportedStops;
    public LivingAbyssGreenPlan(LivingAbyssPlan plan,LivingAbyssGeometry geometry,LivingAbyssSpirePlan spires,LivingAbyssPlan.Solid solid,LivingAbyssPlan.Solid placementAllowed) {
        this.plan=plan;this.geometry=geometry;this.placementAllowed=placementAllowed;circumference=2*Math.PI*650;var p=plan.recipe().curtains();
        var cs=new ArrayList<Clump>();var vs=new ArrayList<Vine>();
        if(p!=null){
            int clusters=(int)Math.round(1.5*p.drapeCover()*circumference*1350/(p.drapeWidth()*p.drapeLen()*.55*p.anchorsPerCluster()));
            for(int i=0;i<clusters;i++){
                long h=LivingAbyssFields.mix(plan.recipe().seed()+i*65537L);double theta=360*LivingAbyssWall.unit(h),depth=1350*LivingAbyssWall.unit(h+1);
                var side=plan.fields().sideWeights(theta);double coverage=1+(p.wetMultiplier()-1)*side[0]+(p.sunnyMultiplier()-1)*side[2];
                if(LivingAbyssWall.unit(h+2)>coverage/1.5)continue;
                for(int j=0;j<p.anchorsPerCluster();j++){
                    long q=LivingAbyssFields.mix(h+j*313L);double a=LivingAbyssFields.normalize(theta+(2*LivingAbyssWall.unit(q)-1)*p.scatterX()/circumference*360);
                    double d=depth+(2*LivingAbyssWall.unit(q+1)-1)*p.scatterY();
                    double width=p.drapeWidth()*(.8+1.4*LivingAbyssWall.unit(q+2)),height=p.clumpMin()+(p.clumpMax()-p.clumpMin())*LivingAbyssWall.unit(q+3);
                    cs.add(new Clump(a,d,width,height));
                    double length=p.drapeLen()*(.45+.85*LivingAbyssWall.unit(q+4))*coverage;
                    for(int dx=-(int)Math.ceil(width/2);dx<=width/2;dx++){
                        double angle=LivingAbyssFields.normalize(a+dx/circumference*360);
                        int len=Math.max(1,(int)Math.round(length*(.3+.7*LivingAbyssWall.unit(q+dx*7919L+5))));
                        growWall(vs,angle,-(int)Math.round(d+height/2),len,solid);
                    }
                }
            }
            // Ledge projection only: do not paint a full circular belt at this elevation.
            for(var ledge:plan.ledges())if(ledge.archetype()!=null){
                int count=(int)Math.ceil(2*Math.toRadians(ledge.halfAngle())*650/3);
                for(int i=0;i<count;i++){
                    double a=LivingAbyssFields.normalize(ledge.theta()-ledge.halfAngle()+2*ledge.halfAngle()*(i+.5)/count);
                    long h=LivingAbyssFields.mix(plan.recipe().seed()+ledge.id().hashCode()+i*8191L);
                    var side=plan.fields().sideWeights(a);double mult=1+.5*side[0]-.5*side[2];
                    int len=(int)Math.round((p.vineMin()+LivingAbyssWall.unit(h)*(p.vineMax()-p.vineMin()))*mult);
                    growWall(vs,a,ledge.y()-(int)Math.round(ledge.width()),len,solid);
                }
            }
            for(var spire:spires.spires()){
                int count=Math.max(8,(int)Math.round(2*Math.PI*spire.topRadius()/3));
                for(int i=0;i<count;i++){
                    double a=i*2*Math.PI/count,theta=LivingAbyssFields.azimuth(spire.x(),spire.z());
                    var side=plan.fields().sideWeights(theta);long h=LivingAbyssFields.mix(plan.recipe().seed()+spire.id().hashCode()+i);
                    int len=(int)Math.round((p.vineMin()+LivingAbyssWall.unit(h)*(p.vineMax()-p.vineMin()))*(1+.5*side[0]-.5*side[2]));
                    int x=(int)Math.round(spire.x()+spire.topRadius()*.94*Math.cos(a)),z=(int)Math.round(spire.z()+spire.topRadius()*.94*Math.sin(a));
                    grow(vs,x,spire.topY()-1,z,len,solid);
                }
            }
        }
        clumps=List.copyOf(cs);vines=List.copyOf(vs);var map=new HashMap<Long,List<Vine>>();
        for(var v:vines)map.computeIfAbsent(key(v.x,v.z),k->new ArrayList<>()).add(v);
        var frozen=new HashMap<Long,List<Vine>>();map.forEach((k,v)->frozen.put(k,List.copyOf(v)));columns=Map.copyOf(frozen);
        var angles=new ArrayList<List<Clump>>();for(int i=0;i<128;i++)angles.add(new ArrayList<>());
        for(var c:clumps){int sector=(int)(c.theta/360*128);for(int off=-1;off<=1;off++)angles.get(Math.floorMod(sector+off,128)).add(c);}
        angular=angles.stream().map(List::copyOf).toList();
        digest=LivingAbyssDigest.of("green-v15.2",clumps.toString()+vines.toString());
    }
    private void growWall(List<Vine> out,double theta,int y,int len,LivingAbyssPlan.Solid solid){
        if(y<-1350||y>=0)return;
        double a=Math.toRadians(theta);int xx=(int)(650*Math.sin(a)),zz=(int)(-650*Math.cos(a));double boundary=geometry.radius(xx,y,zz);
        for(int dr=-2;dr<=1;dr++){
            int x=(int)Math.round((boundary+dr)*Math.sin(a)),z=(int)Math.round(-(boundary+dr)*Math.cos(a));
            if(!solid.at(x,y,z)&&supportMask(solid,x,y,z)!=0){grow(out,x,y,z,len,solid);return;}
        }
    }
    private void grow(List<Vine> out,int x,int y,int z,int len,LivingAbyssPlan.Solid solid){
        if((double)x*x+(double)z*z<380.0*380)return;requestedVoxels+=len;var masks=new ArrayList<Integer>();
        for(int i=0;i<len&&y-i>=-1350&&y-i<0;i++){
            int yy=y-i;int mask=!placementAllowed.at(x,yy,z)||solid.at(x,yy,z)?0:supportMask(solid,x,yy,z);
            if(mask==0){unsupportedStops++;break;}masks.add(mask);
        }
        if(!masks.isEmpty())out.add(new Vine(x,z,y,masks));
    }
    /** Bits north, south, west, east correspond directly to vanilla VineBlock properties. */
    public static int supportMask(LivingAbyssPlan.Solid solid,int x,int y,int z){return (solid.at(x,y,z-1)?1:0)|(solid.at(x,y,z+1)?2:0)|(solid.at(x-1,y,z)?4:0)|(solid.at(x+1,y,z)?8:0);}
    public int vineMask(int x,int y,int z){var list=columns.get(key(x,z));if(list==null)return 0;int mask=0;for(int n=0,count=list.size();n<count;n++){var v=list.get(n);int i=v.topY-y;if(i>=0&&i<v.masks.size())mask|=v.masks.get(i);}return mask;}
    public boolean moss(int x,int y,int z){
        if(plan.recipe().curtains()==null)return false;
        double theta=geometry.azimuthAt(x,z),depth=-((double)y),radius=LivingAbyssFields.hypot(x,z);
        if(radius>geometry.wallRadius(x,y,z)+1.5)return false; // radius() can only reduce this contour
        double surface=geometry.radius(x,y,z);
        if(radius<surface||radius>surface+1.5)return false;
        for(var c:angular.get((int)(theta/360*128))){double dx=LivingAbyssFields.delta(theta,c.theta)*circumference/360/(c.width*.75),dy=(depth-c.depth)/(c.height/2);
            if(Math.abs(dx)>1.3||Math.abs(dy)>1.3)continue;double edge=1+.22*(geometry.wallField().noise(theta,depth,2,2,415)-.5)*2;
            if(dx*dx+dy*dy<edge*edge)return true;}
        var wall=geometry.wallField();double band=geometry.mossBand(x,y,z);
        if(band>0&&wall.noise(theta,depth,4,6,400)>.45)return true;
        boolean up=geometry.radius(x,y+1,z)-surface>.35;
        if(up&&wall.noise(theta,depth,10,14,401)>.45)return true;
        return wall.noise(theta,depth,6,plan.recipe().curtains().mossStreakLen(),402)>.8&&wall.noise(theta,depth,6,120,6)<.2;
    }
    public List<Clump> clumps(){return clumps;}public List<Vine> vines(){return vines;}public String digest(){return digest;}
    public static int mossCoating(long seed,int x,int y,int z,boolean topFace){
        double pick=LivingAbyssWall.unit(seed^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),21)^Long.rotateLeft(LivingAbyssFields.mix(z),42)^0x153a055L);
        return pick<.60?LivingAbyssFeatureBlock.MOSS.ordinal():pick<.85?LivingAbyssFeatureBlock.MOSSY_COBBLESTONE.ordinal():topFace?LivingAbyssFeatureBlock.MOSS_CARPET.ordinal():LivingAbyssFeatureBlock.MOSS.ordinal();
    }
    public int requestedVoxels(){return requestedVoxels;}public int unsupportedStops(){return unsupportedStops;}
    // Map.copyOf uses open addressing. Raw packed-XZ Long.hashCode is x XOR z,
    // clustering tens of thousands of wall columns into a tiny probe range.
    // mix is bijective, so this changes only lookup distribution, never ownership.
    private static long key(int x,int z){return LivingAbyssFields.mix(((long)x<<32)^(z&0xffffffffL));}
}
