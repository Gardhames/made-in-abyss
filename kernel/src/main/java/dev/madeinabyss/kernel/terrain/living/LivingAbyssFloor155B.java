package dev.madeinabyss.kernel.terrain.living;

import java.util.*;
import dev.madeinabyss.kernel.concurrent.FrozenCubeTable;
import dev.madeinabyss.kernel.cube.CubeKey;
import static dev.madeinabyss.kernel.terrain.living.LivingAbyssMath155.*;

/** Immutable supported floor finish. No flora, falling-water route or physics shortcut. */
public final class LivingAbyssFloor155B {
    public enum Type {TALUS,BOULDER,GUR,CRACK}
    public record Feature(Type type,int x,int z,int baseY,int height,int radius,double angle,double gx,double gz,long seed,String owner){}
    @FunctionalInterface public interface Ground {int at(int x,int z,LivingAbyssRevision155.Hall hall);}
    @FunctionalInterface public interface Solid {boolean at(int x,int y,int z);}
    private record Edit(Feature feature,int bottom,int top,int water,boolean rim,int block){}
    private record Column(int x,int z,Edit[] edits){}
    private final List<Feature> features;
    private final FrozenCubeTable<Feature[]> cells;
    private final Column[] columns=new Column[32768];
    private final Solid solid,editable,empty;
    private final int low,high;
    public LivingAbyssFloor155B(long seed,double wet,List<LivingAbyssRevision155.Hall> halls,List<LivingAbyssRevision155.Speleo> speleo,Ground ground,Solid solid){
        this(build(seed,wet,halls,speleo,ground,solid),solid,(x,y,z)->true,(x,y,z)->!solid.at(x,y,z));
    }
    private static List<Feature> build(long seed,double wet,List<LivingAbyssRevision155.Hall> halls,List<LivingAbyssRevision155.Speleo> speleo,Ground ground,Solid solid){
        var result=new ArrayList<Feature>();var rng=new Rng(seed^0x155bf100L);
        for(var h:halls){
            add(result,h,Type.TALUS,2+rng.pick(3),rng,seed,speleo,ground,solid);
            add(result,h,Type.BOULDER,2+rng.pick(3),rng,seed,speleo,ground,solid);
            if(side(h.theta(),wet)>.5)add(result,h,Type.GUR,1+rng.pick(2),rng,seed,speleo,ground,solid);
            add(result,h,Type.CRACK,1+rng.pick(2),rng,seed,speleo,ground,solid);
        }
        return List.copyOf(result);
    }
    private LivingAbyssFloor155B(List<Feature> input,Solid solid,Solid editable,Solid empty){
        this.solid=solid;this.editable=editable;this.empty=empty;features=List.copyOf(input);int lo=Integer.MAX_VALUE,hi=Integer.MIN_VALUE;
        var mutable=new HashMap<CubeKey,ArrayList<Feature>>();
        for(var f:features){int pad=f.radius+2,shift=(int)Math.ceil((Math.abs(f.gx)+Math.abs(f.gz))*pad);
            lo=Math.min(lo,f.baseY-f.height-shift-13);hi=Math.max(hi,f.baseY+f.height+shift+3);
            for(int cx=Math.floorDiv(f.x-pad,16);cx<=Math.floorDiv(f.x+pad,16);cx++)for(int cz=Math.floorDiv(f.z-pad,16);cz<=Math.floorDiv(f.z+pad,16);cz++)
                mutable.computeIfAbsent(new CubeKey(cx,0,cz),k->new ArrayList<>()).add(f);}
        var frozen=new HashMap<CubeKey,Feature[]>();mutable.forEach((k,v)->frozen.put(k,v.toArray(Feature[]::new)));cells=new FrozenCubeTable<>(frozen);low=lo;high=hi;
    }
    private static void add(List<Feature> out,LivingAbyssRevision155.Hall h,Type type,int count,Rng rng,long seed,List<LivingAbyssRevision155.Speleo> speleo,Ground ground,Solid solid){
        for(int n=0;n<count;n++)for(int attempt=0;attempt<96;attempt++){
            double theta=h.theta()+Math.toDegrees(rng.range(-.78,.78)*h.A()/450),middle=h.top()+h.h()/2;
            double s=LivingAbyssRevision155.signedArc(theta,h.theta(),450)/h.A();
            double inner=LivingAbyssMath155.inner(middle)+Math.max(8,h.T()*(.4+1.2*noise(seed,theta,middle,30,55,h.seed()+1,450)));
            double gap=h.G()*(.7+.6*noise(seed,theta,middle,70,45,h.seed(),450))*LivingAbyssRevision155.envelope(s,0);
            double q=type==Type.TALUS?rng.range(.72,.94):rng.range(.25,.8),r=inner+q*gap,a=Math.toRadians(theta);
            int x=(int)Math.round(r*Math.sin(a)),z=(int)Math.round(-r*Math.cos(a)),y=ground.at(x,z,h);if(y==Integer.MIN_VALUE)continue;
            int height=type==Type.TALUS?4+rng.pick(9):type==Type.BOULDER?2+rng.pick(4):type==Type.GUR?1+rng.pick(2):1+rng.pick(3);
            int radius=type==Type.TALUS?(int)Math.ceil(height*rng.range(1.2,1.8)):type==Type.BOULDER?2+rng.pick(4):type==Type.GUR?3+rng.pick(3):6+rng.pick(9);
            int xa=ground.at(x+1,z,h),xb=ground.at(x-1,z,h),za=ground.at(x,z+1,h),zb=ground.at(x,z-1,h);
            if(xa==Integer.MIN_VALUE||xb==Integer.MIN_VALUE||za==Integer.MIN_VALUE||zb==Integer.MIN_VALUE)continue;
            double gx=Math.max(-1.5,Math.min(1.5,(xa-xb)/2.)),gz=Math.max(-1.5,Math.min(1.5,(za-zb)/2.));
            boolean conflict=false;
            for(var previous:out)if(Math.hypot(previous.x-x,previous.z-z)<previous.radius+radius+3&&Math.abs(previous.baseY-y)<height+previous.height+8){conflict=true;break;}
            if(conflict)continue;
            for(var p:speleo){double py=-p.depth(),end=py-p.direction()*p.L(),lower=Math.min(py,end),upper=Math.max(py,end);
                if(upper>=y-height-18&&lower<=y+height+4&&Math.hypot(p.x()-x,p.z()-z)<radius+1.4*p.rb()+3){conflict=true;break;}}
            if(conflict)continue;
            if(type==Type.GUR){
                for(int dx=-radius;dx<=radius&&!conflict;dx++)for(int dz=-radius;dz<=radius;dz++)if(dx*dx+dz*dz<=radius*radius){
                    if(!solid.at(x+dx,y-3,z+dz)||solid.at(x+dx,y+1,z+dz)){conflict=true;break;}}
                if(conflict)continue;gx=0;gz=0;
            }
            out.add(new Feature(type,x,z,y,height,radius,rng.range(0,2*Math.PI),gx,gz,rng.salt(),h.id()));break;
        }
    }
    public List<Feature> features(){return features;}
    public Map<String,Integer> counts(){var result=new LinkedHashMap<String,Integer>();for(var t:Type.values())result.put("floor-"+t,0);for(var f:features)result.merge("floor-"+f.type,1,Integer::sum);return Map.copyOf(result);}
    /** Assembly-only: retain immutable descriptors whose final bare composition
     * admits their centre. Every pool additionally admits its entire sealed bowl. */
    public LivingAbyssFloor155B withActualSupport(Solid support,Solid allowed,Solid air){
        var candidate=new LivingAbyssFloor155B(features,support,allowed,air);var accepted=new ArrayList<Feature>();
        for(var f:features){if(!support.at(f.x,f.baseY,f.z)||!candidate.contains(f,f.x,f.z))continue;
            if(f.type==Type.CRACK&&!support.at(f.x,f.baseY-f.height-1,f.z))continue;
            boolean valid=true;
            if(f.type==Type.GUR)for(int dx=-f.radius;dx<=f.radius&&valid;dx++)for(int dz=-f.radius;dz<=f.radius;dz++)
                if(dx*dx+dz*dz<=f.radius*f.radius&&!candidate.contains(f,f.x+dx,f.z+dz)){valid=false;break;}
            if(valid)accepted.add(f);
        }return new LivingAbyssFloor155B(accepted,support,allowed,air);
    }
    private boolean contains(Feature f,int x,int z){var c=column(x,z);if(c==null)return false;for(var e:c.edits)if(e.feature==f)return true;return false;}
    /** At most one small immutable edit list per hot XZ column. A failed support
     * test admits no new column, so there is never an unsupported suspended toe. */
    private Column column(int x,int z){int slot=(int)LivingAbyssFields.mix(((long)x<<32)^(z&0xffffffffL))&(columns.length-1);var old=columns[slot];
        if(old!=null&&old.x==x&&old.z==z)return old;var near=cells.get(x>>4,0,z>>4);if(near==null)return null;var edits=new ArrayList<Edit>();
        for(var f:near){double dx=x-f.x,dz=z-f.z,distance=Math.hypot(dx,dz);if(distance>f.radius+1)continue;
            int plane=(int)Math.round(f.baseY+f.gx*dx+f.gz*dz),bottom=plane;
            while(bottom>=plane-f.height-12&&!solid.at(x,bottom-1,z))bottom--;
            if(bottom<plane-f.height-12)continue;
            if(f.type==Type.GUR){if(distance>f.radius)continue;boolean rim=distance>f.radius-2;
                if(!solid.at(x,f.baseY-3,z)||!rim&&!empty.at(x,f.baseY+1,z))continue;
                addEdit(edits,new Edit(f,f.baseY-2,rim?f.baseY+f.height:f.baseY,f.baseY+1,rim,LivingAbyssFeatureBlock.CALCITE.ordinal()),x,z);
            }else if(f.type==Type.CRACK){double ca=Math.cos(f.angle),sa=Math.sin(f.angle),along=dx*ca+dz*sa,across=-dx*sa+dz*ca;
                double bend=1.25*(Math.sin(along/5+f.angle)-Math.sin(f.angle));if(Math.abs(along)>f.radius||Math.abs(across-bend)>.55)continue;
                if(!solid.at(x,plane-f.height,z))continue;
                addEdit(edits,new Edit(f,plane-f.height+1,plane+2,Integer.MIN_VALUE,false,LivingAbyssFeatureBlock.AIR_CUT.ordinal()),x,z);
            }else{double outline=f.radius*(.92+.16*noise2(f.seed,dx,dz,8,8));double u=distance/outline;if(u>=1)continue;
                double body=f.type==Type.BOULDER?Math.sqrt(Math.max(0,1-u*u)):Math.pow(1-u,1.3);
                int top=plane+Math.min(f.height,Math.max(1,(int)Math.ceil(f.height*body)));
                int block=f.type==Type.TALUS?(noise2(f.seed,dx,dz,3,3)<.65?LivingAbyssFeatureBlock.GRAVEL.ordinal():LivingAbyssFeatureBlock.COBBLESTONE.ordinal())
                    :(mixUnit(f.seed)<.25?LivingAbyssFeatureBlock.CALCITE.ordinal():LivingAbyssFeatureBlock.ANDESITE.ordinal());
                addEdit(edits,new Edit(f,bottom,top,Integer.MIN_VALUE,false,block),x,z);}
        }var next=new Column(x,z,edits.toArray(Edit[]::new));columns[slot]=next;return next;
    }
    private void addEdit(List<Edit> edits,Edit e,int x,int z){int top=e.feature.type==Type.GUR&&!e.rim?Math.max(e.top,e.water):e.top;
        for(int y=e.bottom;y<=top;y++)if(!editable.at(x,y,z))return;edits.add(e);
    }
    public int at(int x,int y,int z){if(y<low||y>high)return 0;var c=column(x,z);if(c==null)return 0;
        for(var e:c.edits){if(e.feature.type==Type.GUR&&!e.rim&&y==e.water)return LivingAbyssFeatureBlock.WATER_SOURCE.ordinal();
            if(y>=e.bottom&&y<=e.top)return e.block;}return 0;
    }
    /** Cartesian low-frequency phase breaks the lobe's concentric floor contours. */
    public static double floorWarp(long seed,double x,double z,double v){double fade=LivingAbyssFields.smooth(clamp((v-.35)/.5));
        if(fade==0)return 0;
        return fade*(3*(noise2(seed^0x155bf10L,x,z,24,18)-.5)+1.2*(noise2(seed^0x155bf11L,x,z,8,9)-.5));}
    private static double noise2(long seed,double x,double z,double px,double pz){x/=px;z/=pz;long ix=(long)Math.floor(x),iz=(long)Math.floor(z);
        double fx=LivingAbyssFields.smooth(x-ix),fz=LivingAbyssFields.smooth(z-iz);long a=LivingAbyssFields.mix(ix),b=LivingAbyssFields.mix(ix+1),c=Long.rotateLeft(LivingAbyssFields.mix(iz),31),d=Long.rotateLeft(LivingAbyssFields.mix(iz+1),31);
        return (mixUnit(seed^a^c)*(1-fx)+mixUnit(seed^b^c)*fx)*(1-fz)+(mixUnit(seed^a^d)*(1-fx)+mixUnit(seed^b^d)*fx)*fz;}
}
