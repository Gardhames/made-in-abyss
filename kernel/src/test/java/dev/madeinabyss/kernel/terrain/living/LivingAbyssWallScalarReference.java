package dev.madeinabyss.kernel.terrain.living;

import java.util.*;

/** Frozen scalar formulas from the pre-T1 source. Test/benchmark oracle only; no production dependency. */
final class LivingAbyssWallScalarReference {
    private final long seed;
    private final double circumference;
    private final LivingAbyssRecipe.Wall p;
    private final String[][] names=new String[5][];
    private final double[][] base=new double[5][];
    private final int maxColours;
    private record Height(long theta,long depth,double value){}
    private record Region(long theta,long row,double[] low,double[] high){}
    private final ThreadLocal<Height[]> heights=ThreadLocal.withInitial(()->new Height[8192]);
    private final Region[] regions=new Region[8192];
    LivingAbyssWallScalarReference(long seed,double radius,LivingAbyssRecipe.Wall p) {
        this.seed=seed;this.p=p;circumference=2*Math.PI*radius;
        for(int z=0;z<5;z++){var palette=p.palettes().get(z);names[z]=palette.keySet().toArray(String[]::new);base[z]=palette.values().stream().mapToDouble(Double::doubleValue).toArray();}
        maxColours=Arrays.stream(names).mapToInt(a->a.length).max().orElseThrow();
    }
    double protrusion(double theta,double depth) {
        long tb=Double.doubleToLongBits(theta),db=Double.doubleToLongBits(depth);int slot=(int)LivingAbyssFields.mix(tb^Long.rotateLeft(db,23))&8191;
        var cache=heights.get();var known=cache[slot];if(known!=null&&known.theta==tb&&known.depth==db)return known.value;
        double value=scalarProtrusion(theta,depth);cache[slot]=new Height(tb,db,value);return value;
    }
    double scalarProtrusion(double theta,double depth) {
        double sy=depth/p.driftPeriod();long row=(long)Math.floor(sy);double fy=LivingAbyssFields.smooth(sy-row);
        double vertical=lattice(0,row,60)*(1-fy)+lattice(0,row+1,60)*fy;
        double drift=(vertical-.5)*2*p.driftAmp(),shifted=theta+drift/circumference*360;
        double fl=ridged(noise(shifted,depth,p.flutePeriod(),p.flutePeriod()*p.fluteStretch(),6));
        double pipe=ridged(noise(shifted,depth,p.pipePeriod(),p.pipePeriod()*p.pipeStretch(),18));
        double slab=noise(shifted,depth,p.slabPeriod(),p.slabPeriod()*p.slabStretch(),34)*p.slabSteps();
        double f=slab-Math.floor(slab),q=Math.floor(slab)+Math.max(0,Math.min(1,(f-.85)/.15));
        return fl*fl*p.fluteDepth()+pipe*pipe*pipe*p.pipeDepth()+noise(theta,depth,p.bulgePeriod(),p.bulgePeriod()*1.5,90)*p.bulgeDepth()+q/p.slabSteps()*p.slabDepth();
    }
    double[] weights(int zone,double theta,double depth) {
        var result=new double[base[zone].length];double total=0;
        double strata=.5+.5*Math.sin(2*Math.PI*depth/p.strataPeriod()+3*noise(theta,depth,80,40,300));
        double py=p.streakWidth()*p.streakStretch()*3,sy=depth/py;long row=(long)Math.floor(sy);
        double fy=LivingAbyssFields.smooth(sy-row);var region=region(theta,row);
        for(int i=0;i<result.length;i++){
            double n=region.low[i]*(1-fy)+region.high[i]*fy,share=base[zone][i];
            if(zone>=3){double dark=zone==3?40*Math.max(0,Math.min(1,(depth-800)/350)):40+30*Math.max(0,Math.min(1,(depth-1150)/200));
                if(names[zone][i].equals("deepslate"))share=dark;else if(names[zone][i].equals("stone"))share=100-base[zone][1]-dark;}
            double n2=n*n;result[i]=share*(.05+3*n2*n2)*(.85+.3*strata);total+=result[i];
        }
        for(int i=0;i<result.length;i++)result[i]/=total;return result;
    }
    private Region region(double theta,long row) {
        long bits=Double.doubleToLongBits(theta);int slot=(int)LivingAbyssFields.mix(bits^row)&8191;var known=regions[slot];
        if(known!=null&&known.theta==bits&&known.row==row)return known;
        int count=Math.max(2,(int)Math.round(circumference/(p.streakWidth()*6)));double x=LivingAbyssFields.normalize(theta)/360*count;
        long ix=(long)Math.floor(x);double fx=LivingAbyssFields.smooth(x-ix);var low=new double[maxColours];var high=new double[maxColours];
        for(int i=0;i<maxColours;i++){double a=lattice(Math.floorMod(ix,count),row,1000+i),b=lattice(Math.floorMod(ix+1,count),row,1000+i);
            double c=lattice(Math.floorMod(ix,count),row+1,1000+i),d=lattice(Math.floorMod(ix+1,count),row+1,1000+i);
            low[i]=a+(b-a)*fx;high[i]=c+(d-c)*fx;}
        var result=new Region(bits,row,low,high);regions[slot]=result;return result;
    }
    String material(int zone,double theta,int x,int y,int z) {
        double[] w=weights(zone,theta,-((double)y));
        double pick=.7*noise(theta,-((double)y),3,8,0x153L)+.3*unit(seed^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),21)^Long.rotateLeft(LivingAbyssFields.mix(z),42)^0x15b10cL);
        for(int i=0;i<w.length;i++){pick-=w[i];if(pick<0)return names[zone][i];}return names[zone][w.length-1];
    }
    double noise(double theta,double depth,double px,double py,long salt) {
        int count=Math.max(2,(int)Math.round(circumference/px));double x=LivingAbyssFields.normalize(theta)/360*count,y=depth/py;
        long ix=(long)Math.floor(x),iy=(long)Math.floor(y);double fx=LivingAbyssFields.smooth(x-ix),fy=LivingAbyssFields.smooth(y-iy);
        long s=seed^LivingAbyssFields.mix(salt),x0=LivingAbyssFields.mix(Math.floorMod(ix,count)),x1=LivingAbyssFields.mix(Math.floorMod(ix+1,count));
        long y0=Long.rotateLeft(LivingAbyssFields.mix(iy),31),y1=Long.rotateLeft(LivingAbyssFields.mix(iy+1),31);
        double a=unit(s^x0^y0),b=unit(s^x1^y0),c=unit(s^x0^y1),d=unit(s^x1^y1);
        return (a+(b-a)*fx)*(1-fy)+(c+(d-c)*fx)*fy;
    }
    private double lattice(long x,long y,long salt){return unit(seed^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),31)^LivingAbyssFields.mix(salt));}
    private static double unit(long value){return (LivingAbyssFields.mix(value)>>>11)*0x1.0p-53;}
    private static double ridged(double n){return 1-Math.abs(2*n-1);}
}
