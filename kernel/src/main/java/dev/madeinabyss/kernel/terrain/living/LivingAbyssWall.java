package dev.madeinabyss.kernel.terrain.living;

import java.util.*;

/** Port of Claude's scalar protrusion/material field. The noise lattice wraps in angle;
 * its circumference is independent of depth, preventing a seam or depth-dependent phase jump. */
public final class LivingAbyssWall {
    private final long seed;
    private final double circumference;
    private final LivingAbyssRecipe.Wall p;
    private final String[][] names=new String[5][];
    private final double[][] base=new double[5][];
    private final int maxColours;
    private final int[] stoneIndex=new int[5],deepslateIndex=new int[5];
    private final double streakPeriod;
    private final int regionCount;
    private final ThreadLocal<double[]> weightScratch;
    private record Region(long theta,long row,double[] low,double[] high){}
    // Shared by all workers: entries are immutable records, so a racy slot write is safe and the slow
    // per-thread map lookup of a ThreadLocal disappears from the per-voxel path.
    private final Region[] regions=new Region[1<<13];
    /** The four protrusion noises and the drift axis live on tiny lattices (a few thousand cells over the whole depth range),
     * so their exact unit() hash values are tabulated once; the per-voxel path only interpolates. */
    private static final double TABLE_DEPTH=1500;
    private final Lattice flute,pipe,slab,bulge;
    private final double[] driftTab;
    private final double driftPeriod,driftAmp,fluteDepth,pipeDepth,bulgeDepth,slabDepth,slabSteps;
    private final class Lattice {
        final int count,rows;final double py;final long salt;final double[] tab;
        Lattice(double px,double py,long saltValue){
            count=Math.max(2,(int)Math.round(circumference/px));this.py=py;salt=seed^LivingAbyssFields.mix(saltValue);
            rows=(int)Math.floor(TABLE_DEPTH/py)+2;tab=new double[rows*count];
            for(int r=0;r<rows;r++){long y0=Long.rotateLeft(LivingAbyssFields.mix(r),31);for(int i=0;i<count;i++)tab[r*count+i]=unit(salt^LivingAbyssFields.mix(i)^y0);}
        }
        /** Bit-identical to noise(theta,depth,px,py,saltValue). */
        double at(double theta,double depth){
            double x=LivingAbyssFields.normalize(theta)/360*count,y=depth/py;
            long ix=(long)Math.floor(x),iy=(long)Math.floor(y);double fx=LivingAbyssFields.smooth(x-ix),fy=LivingAbyssFields.smooth(y-iy);
            double a,b,c,d;
            if(iy>=0&&iy+1<rows){
                int i0=ix>=0&&ix<count?(int)ix:Math.floorMod(ix,count),i1=i0+1==count?0:i0+1,r0=(int)iy*count,r1=r0+count;
                a=tab[r0+i0];b=tab[r0+i1];c=tab[r1+i0];d=tab[r1+i1];
            }else{
                long x0=LivingAbyssFields.mix(Math.floorMod(ix,count)),x1=LivingAbyssFields.mix(Math.floorMod(ix+1,count));
                long y0=Long.rotateLeft(LivingAbyssFields.mix(iy),31),y1=Long.rotateLeft(LivingAbyssFields.mix(iy+1),31);
                a=unit(salt^x0^y0);b=unit(salt^x1^y0);c=unit(salt^x0^y1);d=unit(salt^x1^y1);
            }
            return (a+(b-a)*fx)*(1-fy)+(c+(d-c)*fx)*fy;
        }
    }
    public LivingAbyssWall(long seed,double referenceRadius,LivingAbyssRecipe.Wall p) {
        this.seed=seed;this.p=Objects.requireNonNull(p);circumference=2*Math.PI*referenceRadius;
        for(int z=0;z<5;z++){var palette=p.palettes().get(z);names[z]=palette.keySet().toArray(String[]::new);base[z]=palette.values().stream().mapToDouble(Double::doubleValue).toArray();}
        maxColours=Arrays.stream(names).mapToInt(a->a.length).max().orElseThrow();
        Arrays.fill(stoneIndex,-1);Arrays.fill(deepslateIndex,-1);
        for(int z=0;z<5;z++)for(int i=0;i<names[z].length;i++){
            if(names[z][i].equals("stone"))stoneIndex[z]=i;
            if(names[z][i].equals("deepslate"))deepslateIndex[z]=i;
        }
        streakPeriod=p.streakWidth()*p.streakStretch()*3;regionCount=Math.max(2,(int)Math.round(circumference/(p.streakWidth()*6)));
        weightScratch=ThreadLocal.withInitial(()->new double[maxColours]);
        flute=new Lattice(p.flutePeriod(),p.flutePeriod()*p.fluteStretch(),6);pipe=new Lattice(p.pipePeriod(),p.pipePeriod()*p.pipeStretch(),18);
        slab=new Lattice(p.slabPeriod(),p.slabPeriod()*p.slabStretch(),34);bulge=new Lattice(p.bulgePeriod(),p.bulgePeriod()*1.5,90);
        driftPeriod=p.driftPeriod();driftAmp=p.driftAmp();fluteDepth=p.fluteDepth();pipeDepth=p.pipeDepth();bulgeDepth=p.bulgeDepth();slabDepth=p.slabDepth();slabSteps=p.slabSteps();
        driftTab=new double[(int)Math.floor(TABLE_DEPTH/driftPeriod)+3];for(int i=0;i<driftTab.length;i++)driftTab[i]=lattice(0,i,60);
    }
    public double protrusion(double theta,double depth) {
        double drift=(vertical(depth)-.5)*2*driftAmp;
        double shifted=theta+drift/circumference*360;
        double fl=ridged(flute.at(shifted,depth));
        double pipeValue=ridged(pipe.at(shifted,depth));
        double raw=slab.at(shifted,depth)*slabSteps;
        double f=raw-Math.floor(raw),q=Math.floor(raw)+Math.max(0,Math.min(1,(f-.85)/.15));
        return fl*fl*fluteDepth+pipeValue*pipeValue*pipeValue*pipeDepth+bulge.at(theta,depth)*bulgeDepth+q/slabSteps*slabDepth;
    }
    public double maximumProtrusion(){return p.fluteDepth()+p.pipeDepth()+p.bulgeDepth()+p.slabDepth();}
    public double[] weights(int zone,double theta,double depth) {
        var result=new double[base[zone].length];weights(zone,theta,depth,result);return result;
    }
    /** Caller-owned output; the owning-array overload above retains its original API. */
    public void weights(int zone,double theta,double depth,double[] result) {
        int count=base[zone].length;if(result.length<count)throw new IllegalArgumentException("Wall weights output too short");
        double total=0;
        double strata=.5+.5*Math.sin(2*Math.PI*depth/p.strataPeriod()+3*noise(theta,depth,80,40,300));
        double sy=depth/streakPeriod;long row=(long)Math.floor(sy);
        double fy=LivingAbyssFields.smooth(sy-row);var region=region(theta,row);
        double dark=zone<3?0:zone==3?40*Math.max(0,Math.min(1,(depth-800)/350)):40+30*Math.max(0,Math.min(1,(depth-1150)/200));
        // The prototype's common strata factor cancels on normalization. Preserve it
        // literally rather than inventing an unapproved material-specific contrast.
        for(int i=0;i<count;i++){
            double n=region.low[i]*(1-fy)+region.high[i]*fy;
            double share=base[zone][i];
            if(zone>=3){
                if(i==deepslateIndex[zone])share=dark;
                else if(i==stoneIndex[zone])share=100-base[zone][1]-dark;
            }
            double n2=n*n;result[i]=share*(.05+3*n2*n2)*(.85+.3*strata);total+=result[i];
        }
        for(int i=0;i<count;i++)result[i]/=total;
    }
    private Region region(double theta,long row){
        long bits=Double.doubleToLongBits(theta);int slot=(int)LivingAbyssFields.mix(bits^row)&(regions.length-1);var cache=regions;var known=cache[slot];
        if(known!=null&&known.theta==bits&&known.row==row)return known;
        int count=regionCount;double x=LivingAbyssFields.normalize(theta)/360*count;
        long ix=(long)Math.floor(x);double fx=LivingAbyssFields.smooth(x-ix);var low=new double[maxColours];var high=new double[maxColours];
        for(int i=0;i<maxColours;i++){double a=lattice(Math.floorMod(ix,count),row,1000+i),b=lattice(Math.floorMod(ix+1,count),row,1000+i);
            double c=lattice(Math.floorMod(ix,count),row+1,1000+i),d=lattice(Math.floorMod(ix+1,count),row+1,1000+i);
            low[i]=a+(b-a)*fx;high[i]=c+(d-c)*fx;}
        var result=new Region(bits,row,low,high);cache[slot]=result;return result;
    }
    public String material(int zone,int x,int y,int z) {return material(zone,LivingAbyssFields.azimuth(x,z),x,y,z);}
    public String material(int zone,double theta,int x,int y,int z) {
        double[] w=weightScratch.get();weights(zone,theta,-((double)y),w);int count=names[zone].length;
        double pick=.7*noise(theta,-((double)y),3,8,0x153L)
            +.3*unit(seed^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),21)^Long.rotateLeft(LivingAbyssFields.mix(z),42)^0x15b10cL);
        for(int i=0;i<count;i++){pick-=w[i];if(pick<0)return names[zone][i];}return names[zone][count-1];
    }
    /** Same connected-patch selection for the separately approved dry spire/body palette. */
    private static final String[] SPIRE_PALETTE={"stone","andesite","calcite","dripstone_block","white_terracotta","tuff","mossy_cobblestone"};
    private static final double[] SPIRE_SHARES={32,32,10,9,1,5,5};
    public String spireMaterial(int x,int y,int z) {return spireMaterial(LivingAbyssFields.azimuth(x,z),x,y,z);}
    public String spireMaterial(double theta,int x,int y,int z) {
        String[] palette=SPIRE_PALETTE;double[] shares=SPIRE_SHARES;double depth=-((double)y),total=0;
        double[] w=new double[shares.length];
        // The seven palette noises share one 36m x 240m lattice cell: hoist everything but the salt.
        int count=Math.max(2,(int)Math.round(circumference/36));double px=LivingAbyssFields.normalize(theta)/360*count,py=depth/240;
        long ix=(long)Math.floor(px),iy=(long)Math.floor(py);double fx=LivingAbyssFields.smooth(px-ix),fy=LivingAbyssFields.smooth(py-iy);
        long x0=LivingAbyssFields.mix(Math.floorMod(ix,count)),x1=LivingAbyssFields.mix(Math.floorMod(ix+1,count));
        long y0=Long.rotateLeft(LivingAbyssFields.mix(iy),31),y1=Long.rotateLeft(LivingAbyssFields.mix(iy+1),31);
        for(int i=0;i<w.length;i++){long s=seed^LivingAbyssFields.mix(1000+i);
            double a=unit(s^x0^y0),b=unit(s^x1^y0),c=unit(s^x0^y1),d=unit(s^x1^y1);
            double n=(a+(b-a)*fx)*(1-fy)+(c+(d-c)*fx)*fy;double nn=n*n;w[i]=shares[i]*(.05+3*nn*nn);total+=w[i];}
        double pick=(.7*noise(theta,depth,3,8,0x153L)+.3*unit(seed^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),21)^Long.rotateLeft(LivingAbyssFields.mix(z),42)^0x15b10cL))*total;
        for(int i=0;i<w.length;i++){pick-=w[i];if(pick<0)return palette[i];}return palette[palette.length-1];
    }
    public double noise(double theta,double depth,double px,double py,long salt) {
        int count=Math.max(2,(int)Math.round(circumference/px));double x=LivingAbyssFields.normalize(theta)/360*count,y=depth/py;
        long ix=(long)Math.floor(x),iy=(long)Math.floor(y);double fx=LivingAbyssFields.smooth(x-ix),fy=LivingAbyssFields.smooth(y-iy);
        // Same four lattice values as lattice(x,y,salt), with the shared mixes computed once.
        long s=seed^LivingAbyssFields.mix(salt),x0=LivingAbyssFields.mix(Math.floorMod(ix,count)),x1=LivingAbyssFields.mix(Math.floorMod(ix+1,count));
        long y0=Long.rotateLeft(LivingAbyssFields.mix(iy),31),y1=Long.rotateLeft(LivingAbyssFields.mix(iy+1),31);
        double a=unit(s^x0^y0),b=unit(s^x1^y0),c=unit(s^x0^y1),d=unit(s^x1^y1);
        return (a+(b-a)*fx)*(1-fy)+(c+(d-c)*fx)*fy;
    }
    /** Drift axis (period driftPeriod, salt 60); tabulated over the depth range. */
    private double vertical(double depth){
        double y=depth/driftPeriod;long i=(long)Math.floor(y);double f=LivingAbyssFields.smooth(y-i);
        if(i>=0&&i+1<driftTab.length)return driftTab[(int)i]*(1-f)+driftTab[(int)i+1]*f;
        return lattice(0,i,60)*(1-f)+lattice(0,i+1,60)*f;
    }
    private double lattice(long x,long y,long salt){return unit(seed^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),31)^LivingAbyssFields.mix(salt));}
    public static double unit(long value){return (LivingAbyssFields.mix(value)>>>11)*0x1.0p-53;}
    private static double ridged(double n){return 1-Math.abs(2*n-1);}
}
