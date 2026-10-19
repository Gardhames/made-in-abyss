package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.terrain.*;
import java.util.*;

/** Pure v15 solid composition. Every shelf is a continuous radial body joined to its wall;
 * its top and underside share one descriptor. R380 shortening happens before composition.
 * The bounded worker-local column cache only reuses immutable arithmetic results. */
public final class LivingAbyssGeometry {
    public enum Kind { DELEGATE,AIR,ROCK,SOIL,GRASS,STONE_BRICKS,MOSS,ANDESITE,MOSSY_COBBLESTONE,BOULDER_STONE,ROOTED_DIRT,COARSE_DIRT,CALCITE,BODY_ROCK }
    private record Shape(LivingAbyssPlan.Ledge ledge,double top,double projection,double width,double weight,double bottom,double outer,double u,double zf){}
    public record Surface(LivingAbyssPlan.Ledge ledge,int topY,int bottomY,double u,double edgeDistance,double zf){}
    private static final LivingAbyssSpirePlan.Spire[] NO_SPIRES=new LivingAbyssSpirePlan.Spire[0];
    private record Column(int x,int z,double radius,double theta,Shape[] shapes,LivingAbyssSpirePlan.Spire[] spires){}
    private final TerrainParameters p;
    private final LivingAbyssPlan plan;
    private final LivingAbyssWallRadius wall;
    private final LivingAbyssWall wallField;
    private final LivingAbyssSpirePlan spires;
    /** Wall protrusion lies in [0,maxProtrusion]; the noise is only evaluated inside that band just inside the wall.
     * The slack dwarfs floating-point error, so every comparison keeps its exact outcome. */
    private final double maxProtrusion;
    private static final double BAND_SLACK=1e-9;
    private record Boundary(long angle,int y,double radius){}
    // Shared racy memo of immutable records (see LivingAbyssWall): no ThreadLocal lookup per probe.
    private final Boundary[] boundaries=new Boundary[1<<14];
    // Shared across workers: LOD rays and light probes cross many columns; a 4096-slot
    // per-thread memo recomputed column shapes for nearly every voxel. Entries are immutable.
    private static final int COLUMN_SLOTS=1<<18;
    private final java.util.concurrent.atomic.AtomicReferenceArray<Column> columns=new java.util.concurrent.atomic.AtomicReferenceArray<>(COLUMN_SLOTS);
    public LivingAbyssGeometry(TerrainParameters p,LivingAbyssPlan plan) {
        this(p,plan,null);
    }
    public LivingAbyssGeometry(TerrainParameters p,LivingAbyssPlan plan,LivingAbyssSpirePlan spires) {
        this.p=p;this.plan=plan;var c=p.cavity();
        this.spires=spires;wallField=plan.recipe().wall()==null?null:new LivingAbyssWall(plan.recipe().seed(),c.lowerRadius(),plan.recipe().wall());
        maxProtrusion=wallField==null?0:wallField.maximumProtrusion();
        wall=new LivingAbyssWallRadius(new TerrainParameters(p.seed(),p.surface(),new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),c.declaredLowerBoundY(),c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),p.noise()));
    }
    public double wallRadius(int x,int y,int z){return plan.revision155()!=null?LivingAbyssMath155.inner(-((double)y)):wall.cavityRadius(x,y,z);}
    public double minimumWallRadius(int x,int minimumY,int maximumY,int z){return plan.revision155()!=null?LivingAbyssMath155.inner(-((double)minimumY)):wall.minimumRadius(x,minimumY,maximumY,z);}
    /** The v14 wall noise depends on XZ as well as angle. Its contour is implicit:
     * r = wallRadius(r*sin(theta), y, -r*cos(theta)), not a sample at arbitrary R650. */
    public double wallBoundaryAt(double theta,int y){
        if(plan.revision155()!=null)return LivingAbyssMath155.inner(-((double)y));
        long bits=Double.doubleToLongBits(LivingAbyssFields.normalize(theta));int slot=(int)LivingAbyssFields.mix(bits^y)&(boundaries.length-1);var cache=boundaries;var known=cache[slot];
        if(known!=null&&known.angle==bits&&known.y==y)return known.radius;
        double a=Math.toRadians(LivingAbyssFields.normalize(theta)),lo=300,hi=p.cavity().lowerRadius()+p.noise().wallAmplitude()+16;
        for(int i=0;i<14;i++){double mid=(lo+hi)/2;int x=(int)Math.round(mid*Math.sin(a)),z=(int)Math.round(-mid*Math.cos(a));
            if(mid>=wallRadius(x,y,z))hi=mid;else lo=mid;}
        double radius=(lo+hi)/2;cache[slot]=new Boundary(bits,y,radius);return radius;
    }
    /** Section18 shaped summits: grass on exposed cap tops, one soil block, rock on faces and below. */
    private Kind spireKind(LivingAbyssSpirePlan.Spire q,int x,int y,int z,Kind rock){
        if(!spires.shaped(q))return y==q.topY()?Kind.GRASS:y>=q.topY()-2?Kind.SOIL:rock;
        if(y<q.topY()-8)return rock;
        if(!spires.solid(q,x,y+1,z))return Kind.GRASS;
        return spires.solid(q,x,y+2,z)?rock:Kind.SOIL;
    }
    public LivingAbyssWall wallField(){return wallField;}
    @FunctionalInterface public interface VerticalRange {void accept(int minimumY,int maximumY);}
    /** Owner ranges at this actual XZ column, including conservative legacy trees. */
    public void visitCertificateObstacles(int x,int z,VerticalRange ranges){
        if(plan.revision155()!=null){ranges.accept(-1424,0);return;}
        var c=column(x,z);
        for(var s:c.shapes)ranges.accept((int)Math.floor(s.bottom),(int)Math.floor(s.top));
        for(var q:c.spires){
            spires.visitCertificateObstacles(q,x,z,ranges);
            for(var t:q.trees()){
                int dx=x-t.x(),dz=z-t.z();
                if(dx==0&&dz==0)ranges.accept(t.y(),t.y()+t.height()-1);
                if(Math.abs(dx)<=2&&Math.abs(dz)<=2)for(int offset=-2;offset<=1;offset++)
                    if(dx*dx+dz*dz+2*offset*offset<=7)ranges.accept(t.y()+t.height()-1+offset,t.y()+t.height()-1+offset);
            }
        }
    }
    /** Azimuth of a voxel column, memoised with the column: one atan2 per column instead of one per voxel. */
    public double azimuthAt(int x,int z){return plan.revision155()!=null?plan.revision155().thetaAt(x,z):column(x,z).theta;}
    private Column column(int x,int z) {
        int slot=(int)LivingAbyssFields.mix(((long)x<<32)^(z&0xffffffffL))&(COLUMN_SLOTS-1);var c=columns.get(slot);if(c!=null&&c.x==x&&c.z==z)return c;
        double r=LivingAbyssFields.hypot(x,z),theta=LivingAbyssFields.azimuth(x,z),relief=LivingAbyssPlan.relief(p,x,z);var shapes=new ArrayList<Shape>();
        for(var q:plan.candidates(x,z)) {
            double weight=LivingAbyssFields.smooth(1-Math.abs(LivingAbyssFields.delta(theta,q.theta()))/q.halfAngle());if(weight==0)continue;
            double oldTop=p.cavity().firstLayerTopY()-q.topDepth()-(q.added()?0:relief),outer=wall.cavityRadius(x,(int)Math.floor(oldTop),z);
            double projection=q.projection()*weight*(q.added()?1:LivingAbyssPlan.retention(oldTop));
            // Planned circular rounded edge; same wall anchor and inherited support law.
            projection=Math.min(projection,Math.max(0,outer-plan.recipe().domain().emptyRadius()));if(projection<=0)continue;
            double radial=Math.max(0,Math.min(1,(outer-r)/projection));
            double offset=q.archetype()==null?0:topOffset(q,x,z,radial)*weight;
            // Outside the original cap's projection, do not expand the horizontal contour.
            if(radial<=0||r<outer-projection)offset=0;
            // Keep the v14 horizontal footprint/retention at its original parent top.
            // Section20 changes the surface around the catalogue anchor, not the old +/-20m relief.
            double top=(wallField!=null&&q.archetype()!=null?q.y():oldTop)+offset,zf=LivingAbyssFields.delta(theta,q.theta())/q.halfAngle();
            double bottom=Double.NaN;
            if(wallField!=null){
                double distance=outer-r,u=(outer-r)/projection;
                if(distance < -32||u>1)continue;
                long salt=LivingAbyssFields.mix(plan.recipe().seed()^q.id().hashCode());
                double noise=LivingAbyssPlan.noise(plan.recipe().seed(),9,x,0,z,1903)+.5;
                top+=1.2*Math.max(0,(radial-.85)/.15);
                double tw=26+12*LivingAbyssWall.unit(salt),te=3+2*LivingAbyssWall.unit(salt+1),tier=2+2*LivingAbyssWall.unit(salt+2),rf=14+8*LivingAbyssWall.unit(salt+3);
                double tangent=Math.toRadians(LivingAbyssFields.delta(theta,q.theta()))*outer;
                double groove=1-.10*Math.abs(Math.sin(26*Math.atan2(tangent,distance+1)+4*noise));
                bottom=top-LivingAbyssLedgeBody.thickness(radial,zf,noise,tw*groove,te,tier)-LivingAbyssLedgeBody.filletDepth(distance,zf,rf);
                int ribs=2+(int)(LivingAbyssWall.unit(salt+4)*3);double extra=0,half=Math.toRadians(q.halfAngle())*outer;
                for(int i=0;i<ribs;i++){long rs=LivingAbyssFields.mix(salt+i*65537L+19);double reach=q.projection()*(.25+.20*LivingAbyssWall.unit(rs)),t=LivingAbyssLedgeBody.clamp(distance/reach);
                    double center=(2*LivingAbyssWall.unit(rs+1)-1)*.6*half,hw=2.5*(1-t)+1;
                    if(distance>=-2&&Math.abs(tangent-center)<=hw)extra=Math.max(extra,LivingAbyssLedgeBody.ribDepth(distance,reach,rf*.9+6+6*LivingAbyssWall.unit(rs+2)));}
                bottom-=extra;
            }
            shapes.add(new Shape(q,top,projection,q.width(),weight,bottom,outer,radial,zf));
        }
        c=new Column(x,z,r,theta,shapes.toArray(Shape[]::new),spires==null?NO_SPIRES:spires.candidates(x,z).toArray(LivingAbyssSpirePlan.Spire[]::new));columns.set(slot,c);return c;
    }
    private double[] warped(int x,int z,long salt){return new double[]{x+12*LivingAbyssPlan.noise(plan.recipe().seed(),37,x,0,z,salt),z+12*LivingAbyssPlan.noise(plan.recipe().seed(),37,x,0,z,salt+1)};}
    private double waves(int x,int z,long salt){double[] w=warped(x,z,salt);int xx=(int)Math.round(w[0]),zz=(int)Math.round(w[1]);return .65*LivingAbyssPlan.noise(plan.recipe().seed(),19,xx,0,zz,salt+2)+.35*LivingAbyssPlan.noise(plan.recipe().seed(),9,xx,0,zz,salt+3);}
    private double topOffset(LivingAbyssPlan.Ledge q,int x,int z,double t) {
        var a=plan.recipe().archetypes();
        if(wallField!=null){double wave=waves(x,z,100+q.archetype().ordinal()*11L);double warpedT=Math.max(0,Math.min(1,t+8*wave/Math.max(1,q.projection())));
            return switch(q.archetype()){
                case MEADOW -> {double[] w=warped(x,z,2001);yield 2*Math.sin(w[0]/11)*Math.cos(w[1]/13)+3*wave;}
                case GROVE -> 5*wave+2*LivingAbyssPlan.noise(plan.recipe().seed(),6,x,0,z,2002);
                case LAKE -> -q.lakeDepth()*4*warpedT*(1-warpedT)+a.meadowAmplitude()*wave;
                case TERRACES -> {double along=LivingAbyssFields.delta(LivingAbyssFields.azimuth(x,z),q.theta())/q.halfAngle();double rr=(warpedT-.42)/.38;
                    double hill=15*Math.exp(-(rr*rr+along*along/(.76*.76)));yield Math.floor((hill+1.5*wave)/3)*3;}
                case ROCK_GARDEN -> 3*wave;
                case RUIN -> 20*Math.pow(Math.max(0,1-t/.7),1.6)+2.5*wave+1.5*LivingAbyssPlan.noise(plan.recipe().seed(),6,x,0,z,2003);
                case OVERHANG -> -3*t+1.2*LivingAbyssPlan.noise(plan.recipe().seed(),6,x,0,z,2004);
            };
        }
        return switch(q.archetype()) {
            case MEADOW -> a.meadowAmplitude()*LivingAbyssPlan.noise(plan.recipe().seed(),p.noise().wavelength()/3,x,0,z,101);
            case GROVE -> a.groveAmplitude()*LivingAbyssPlan.noise(plan.recipe().seed(),p.noise().wavelength()/3,x,0,z,102);
            case LAKE -> -q.lakeDepth()*4*t*(1-t); // both supported shores, empty dry bowl in part 1
            case TERRACES -> Math.min(q.terraces()-1,Math.floor(t*q.terraces()))*q.rise();
            case ROCK_GARDEN -> q.boulderHeight()*Math.max(0,LivingAbyssPlan.noise(plan.recipe().seed(),q.boulderHeight()*2,x,0,z,104))
                    -Math.max(0,LivingAbyssPlan.noise(plan.recipe().seed(),q.boulderHeight()*2,x,0,z,105));
            case RUIN -> Math.min(q.terraces()-1,Math.floor(t*q.terraces()));
            case OVERHANG -> 0;
        };
    }
    /** Rounded 2-6m solid boulders, not isolated raised voxels. */
    public double boulderHeight(int x,int z){
        int ix=Math.floorDiv(x,9),iz=Math.floorDiv(z,9);double highest=0;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            long h=LivingAbyssFields.mix(plan.recipe().seed()^LivingAbyssFields.mix(ix+dx)^Long.rotateLeft(LivingAbyssFields.mix(iz+dz),21)^104);
            double cx=(ix+dx)*9+2+5*LivingAbyssWall.unit(h),cz=(iz+dz)*9+2+5*LivingAbyssWall.unit(h+1),radius=1+2*LivingAbyssWall.unit(h+2);
            double rr=((x-cx)*(x-cx)+(z-cz)*(z-cz))/(radius*radius);
            if(rr<1)highest=Math.max(highest,(2+4*LivingAbyssWall.unit(h+3))*Math.sqrt(1-rr));
        }return highest;
    }
    private double creviceDepth(int x,int z){double[] w=warped(x,z,109);double fx=Math.abs(Math.IEEEremainder(w[0],19)),fz=Math.abs(Math.IEEEremainder(w[1],23));return Math.min(fx,fz)<.75?1.5:0;}
    private static double support(Shape s,int y) {
        if(!Double.isNaN(s.bottom))return y<=Math.floor(s.top)&&y>=Math.floor(s.bottom)?s.projection:0;
        double dy=s.top-y;if(dy<0||dy>=s.width)return 0;
        if(s.ledge.archetype()==LivingAbyssPlan.Archetype.OVERHANG) {
            if(dy<=s.ledge.thickness())return s.projection;
            double rem=(s.width-dy)/(s.width-s.ledge.thickness());return s.projection*rem*rem;
        }
        double rem=1-dy/s.width;return s.projection*rem*rem;
    }
    public double radius(int x,int y,int z) {
        if(plan.revision155()!=null)return wallRadius(x,y,z);
        var c=column(x,z);double outer=wallRadius(x,y,z),extrusion=0;
        for(var s:c.shapes)extrusion=Math.max(extrusion,support(s,y));
        if(wallField!=null&&y<0&&y>=plan.recipe().domain().wallBottomY())extrusion=Math.max(extrusion,wallField.protrusion(c.theta,-((double)y)));
        double result=outer-extrusion;
        var d=plan.recipe().domain();return y<=d.emptyTopY()&&y>=d.wallBottomY()?Math.max(d.emptyRadius(),result):result;
    }
    public boolean solid(int x,int y,int z){
        if(plan.revision155()!=null)return LivingAbyssRevision155.isSolid(plan.revision155().sample(x,y,z));
        if(wallField!=null){var col=column(x,z);double r=col.radius,outer=wallRadius(x,y,z);
            if(y<0&&y>=plan.recipe().domain().wallBottomY()){
                if(r>=outer+BAND_SLACK)return true;
                if(r>=outer-maxProtrusion-BAND_SLACK&&r>=outer-wallField.protrusion(col.theta,-((double)y)))return true;
            }else if(r>=outer)return true;
            for(var s:col.shapes)if(y<=Math.floor(s.top)&&y>=Math.floor(s.bottom))return true;
        }else if(LivingAbyssFields.hypot(x,z)>=radius(x,y,z))return true;
        for(var q:column(x,z).spires)if(spires.solid(q,x,y,z))return true;return false;
    }
    public List<Surface> surfaces(int x,int z){
        if(plan.revision155()!=null)return List.of();
        var out=new ArrayList<Surface>();for(var s:column(x,z).shapes)if(s.ledge.archetype()!=null&&s.u>=0&&s.u<=1){
            int y=(int)Math.floor(s.top);if(!solid(x,y+1,z))out.add(new Surface(s.ledge,y,(int)Math.floor(s.bottom),s.u,Math.max(0,(1-s.u)*s.projection),s.zf));}
        return out;
    }
    public Kind sample(int x,int y,int z) {
        if(plan.revision155()!=null){
            if(y<plan.recipe().domain().knownLowerY())throw new IllegalArgumentException("Unknown v15.5 geometry");
            if(y>=0)return Kind.DELEGATE;
            if(y<=plan.recipe().domain().temporaryFloorY()+8&&y<=floorTop(x,z))return Kind.ROCK;
            int code=plan.revision155().sample(x,y,z);
            if(code>=LivingAbyssRevision155.FEATURE_BASE)return LivingAbyssFeatureBlock.isWater(code-LivingAbyssRevision155.FEATURE_BASE)?Kind.AIR:Kind.ROCK;
            return code==LivingAbyssRevision155.AIR||code==LivingAbyssRevision155.HALL?Kind.AIR:code==LivingAbyssRevision155.LEDGE?Kind.BODY_ROCK:Kind.ROCK;
        }
        var d=plan.recipe().domain();double r=LivingAbyssFields.hypot(x,z);
        if(y<d.knownLowerY())throw new IllegalArgumentException("Unknown v15 geometry");
        if(y<=d.temporaryFloorY()+8&&y<=floorTop(x,z))return Kind.ROCK;
        if(y>=(wallField==null?d.emptyTopY()+1:0))return Kind.DELEGATE;
        if(r>p.cavity().lowerRadius()+p.noise().wallAmplitude())return y<p.cavity().declaredLowerBoundY()?Kind.ROCK:Kind.DELEGATE;
        if(y>=d.wallBottomY()&&r<d.emptyRadius())return Kind.AIR;
        if(wallField!=null)return sampleBody(x,y,z);
        double outer=wallRadius(x,y,z);if(r>=outer)return y<p.cavity().declaredLowerBoundY()||wallField!=null?Kind.ROCK:Kind.DELEGATE;
        var col=column(x,z);Shape owner=null;double maximum=0;
        for(var q:col.spires)if(spires.solid(q,x,y,z))return spireKind(q,x,y,z,Kind.BODY_ROCK);
        for(var s:col.shapes){double e=support(s,y);if(e>maximum){maximum=e;owner=s;}}
        double wallH=wallField==null||y<d.wallBottomY()?0:wallField.protrusion(LivingAbyssFields.azimuth(x,z),-((double)y));
        if(wallH>maximum){maximum=wallH;owner=null;}
        double boundary=y>=d.wallBottomY()?Math.max(d.emptyRadius(),outer-maximum):outer-maximum;
        if(r<boundary)return Kind.AIR;
        if(owner==null)return Kind.ROCK;
        // Final top includes other overlapping shelves; no internal dirt/grass seam.
        if(wallField!=null&&owner.ledge.archetype()==LivingAbyssPlan.Archetype.ROCK_GARDEN){
            double height=boulderHeight(x,z);boolean top=!solid(x,y+1,z);
            if(height>1&&y>owner.top-height*owner.weight){long h=LivingAbyssFields.mix(plan.recipe().seed()^Math.floorDiv(x,3)^Long.rotateLeft(Math.floorDiv(z,3),21));
                return top&&LivingAbyssWall.unit(h)<.3?Kind.MOSS:Math.floorMod(h,3)==0?Kind.ANDESITE:Math.floorMod(h,3)==1?Kind.MOSSY_COBBLESTONE:Kind.BOULDER_STONE;}
            if(top)return LivingAbyssPlan.noise(plan.recipe().seed(),13,x,0,z,106)>.42?Kind.BOULDER_STONE:LivingAbyssWall.unit(plan.recipe().seed()^x^Long.rotateLeft(z,31))<.45?Kind.MOSS:Kind.GRASS;
        }
        if(!solid(x,y+1,z))return owner.ledge.archetype()==LivingAbyssPlan.Archetype.RUIN?Kind.STONE_BRICKS:
            owner.ledge.archetype()==LivingAbyssPlan.Archetype.ROCK_GARDEN?Kind.ROCK:Kind.GRASS;
        if(!solid(x,y+(int)Math.ceil(p.surface().soilDepth()),z)){
            if(owner.ledge.archetype()==LivingAbyssPlan.Archetype.RUIN)return Kind.STONE_BRICKS;
            if(wallField!=null)for(int[] off:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                if(!solid(x+off[0],y,z+off[1])&&solid(x+off[0],y-2,z+off[1]))return Kind.GRASS;
            return Kind.SOIL;
        }
        return Kind.ROCK;
    }
    private Kind sampleBody(int x,int y,int z){
        var col=column(x,z);double r=col.radius;var d=plan.recipe().domain();
        for(var q:col.spires)if(spires.solid(q,x,y,z))return spireKind(q,x,y,z,Kind.ROCK);
        Shape owner=null;for(var s:col.shapes)if(y<=Math.floor(s.top)&&y>=Math.floor(s.bottom)&&(owner==null||s.top>owner.top))owner=s;
        if(owner==null){
            double outer=wallRadius(x,y,z);
            if(y<d.wallBottomY())return r>=outer?Kind.ROCK:Kind.AIR;
            if(r>=outer+BAND_SLACK)return Kind.ROCK;
            if(r<outer-maxProtrusion-BAND_SLACK)return Kind.AIR;
            return r>=outer-wallField.protrusion(col.theta,-((double)y))?Kind.ROCK:Kind.AIR;
        }
        if(solid(x,y+1,z)){
            if(plan.recipe().part3b()&&y>owner.top-3){
                boolean exposed=!solid(x-1,y,z)||!solid(x+1,y,z)||!solid(x,y,z-1)||!solid(x,y,z+1);
                var side=LivingAbyssSurfaceMaterials.side((int)Math.floor(owner.top)-y,exposed,materialPick(x,y,z));
                if(side!=Kind.SOIL)return side;
                return owner.u>.85?Kind.ROOTED_DIRT:Kind.SOIL;
            }
            if(owner.ledge.archetype()==LivingAbyssPlan.Archetype.TERRACES&&y>owner.top-3){
                for(int[] off:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})if(!solid(x+off[0],y,z+off[1])&&LivingAbyssWall.unit(plan.recipe().seed()^x^Long.rotateLeft(y,21)^Long.rotateLeft(z,42))<.75)
                    return switch(Math.floorMod(LivingAbyssFields.mix(plan.recipe().seed()^x^Long.rotateLeft(z,21)),4)){case 0->Kind.MOSSY_COBBLESTONE;case 1->Kind.BOULDER_STONE;case 2->Kind.CALCITE;default->Kind.MOSS;};
            }
            if(!solid(x,y+3,z))return owner.u>.85?Kind.ROOTED_DIRT:Kind.SOIL;
            return Kind.BODY_ROCK;
        }
        if(plan.recipe().part3b()){
            if(owner.ledge.archetype()==LivingAbyssPlan.Archetype.OVERHANG&&Math.abs(owner.zf)>.22/.47)return Kind.BOULDER_STONE;
            return LivingAbyssSurfaceMaterials.top(LivingAbyssSurfaceMaterials.patch(plan.recipe().seed(),x,z),materialPick(x,y,z));
        }
        double patch=LivingAbyssPlan.noise(plan.recipe().seed(),4,x,0,z,2020)+.5;
        if(owner.u>.85&&owner.u<1){long hash=LivingAbyssFields.mix(plan.recipe().seed()^x^Long.rotateLeft(z,21));double pick=LivingAbyssWall.unit(hash);
            if((1-owner.u)*owner.projection<3){if(pick<.35)return switch(Math.floorMod(hash,4)){case 0,3->Kind.MOSSY_COBBLESTONE;case 1->Kind.BOULDER_STONE;default->Kind.ANDESITE;};if(pick<.50)return Kind.COARSE_DIRT;}}
        if(owner.ledge.archetype()==LivingAbyssPlan.Archetype.OVERHANG&&Math.abs(owner.zf)>.22/.47)return Kind.BOULDER_STONE;
        return patch>.78?Kind.MOSS:patch<.16?Kind.COARSE_DIRT:Kind.GRASS;
    }
    private double materialPick(int x,int y,int z){return .7*wallField.noise(column(x,z).theta,-((double)y),3,8,0x153L)+.3*LivingAbyssWall.unit(plan.recipe().seed()^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),21)^Long.rotateLeft(LivingAbyssFields.mix(z),42)^0x15b10cL);}
    public double mossBand(int x,int y,int z){
        var c=column(x,z);double value=0;
        for(var s:c.shapes){double below=s.top-y,outer=wallRadius(x,(int)s.top,z);
            if(below>0&&below<plan.recipe().curtains().mossBelowLedge()+25*s.weight&&c.radius>=outer-s.projection&&c.radius<outer+2)value=Math.max(value,s.weight);}
        return value;
    }
    /** Temporary relief bounded by half a cube, retaining a full 16m bottom lighting halo. */
    public int floorTop(int x,int z){return plan.recipe().domain().temporaryFloorY()+(int)Math.round(8*LivingAbyssPlan.noise(plan.recipe().seed(),p.noise().wavelength(),x,0,z,1400));}
    public LivingAbyssPlan plan(){return plan;}
}
