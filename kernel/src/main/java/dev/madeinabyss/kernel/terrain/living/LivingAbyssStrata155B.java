package dev.madeinabyss.kernel.terrain.living;

import static dev.madeinabyss.kernel.terrain.living.LivingAbyssMath155.*;

/** Stage B bedding coordinates and coherent patches. The Stage A column is frozen. */
public final class LivingAbyssStrata155B {
    private static final double PHI=Math.toRadians(130),SLOPE=Math.tan(Math.toRadians(5.5));
    private static final double KX=SLOPE*Math.cos(PHI),KZ=SLOPE*Math.sin(PHI),WAVE=.35*Math.tan(Math.toRadians(2.5));
    private static final double[][] QUARTILES=quartiles();
    private final long seed;
    private final double wet;
    private final LivingAbyssStrata155 base;
    private record Column(int x,int z,double strike,double offset,double a,double s,double k,double f,double sum){}
    private final Column[] columns=new Column[32768];
    public LivingAbyssStrata155B(long seed,double wet,LivingAbyssStrata155 base){this.seed=seed;this.wet=wet;this.base=base;}
    /** Literal24.14.1: XYZ dip plus radius-scaled strike wave; no azimuth sectors. */
    public static double beddingDepth(int x,int y,int z){return -((double)y)+offset(x,z);}
    private static double offset(int x,int z){double r=Math.hypot(x,z),theta=Math.atan2(x,-((double)z));
        return KX*x+KZ*z+WAVE*r*Math.sin(2*theta+Math.toRadians(40));}
    public static double strike(int x,int z){return -Math.sin(PHI)*x+Math.cos(PHI)*z;}
    private Column column(int x,int z){int slot=(int)LivingAbyssFields.mix(((long)x<<32)^(z&0xffffffffL))&(columns.length-1);var c=columns[slot];
        if(c!=null&&c.x==x&&c.z==z)return c;
        double theta=LivingAbyssFields.azimuth(x,z),a=side(theta,wet),s=side(theta,wet+180),k=side(theta,wet+90),f=side(theta,wet+270);
        c=new Column(x,z,strike(x,z),offset(x,z),a,s,k,f,Math.max(1e-15,a+s+k+f));columns[slot]=c;return c;
    }
    private int index(Column c,double q){return base.bandIndex(q+(noise(seed,c.strike,q,36,4.5,0x155b01L)-.5)*4);}
    private String primary(Column c,double q,int i){var b=base.bands().get(i);String material=b.primary();
        if(!b.replace())return material;
        double edge=(noise(seed,c.strike,q,40,6,0x155b02L+i)-.5)*.25;
        if(c.a/c.sum+edge>b.thresholds().get(0))material=b.substitutes().get(0);
        if(c.s/c.sum+edge>b.thresholds().get(1))material=b.substitutes().get(1);
        if(c.k/c.sum+edge>b.thresholds().get(2))material=b.substitutes().get(2);
        if(c.f/c.sum+edge>b.thresholds().get(3))material=b.substitutes().get(3);
        return material;
    }
    public String material(int x,int y,int z){var c=column(x,z);double q=-((double)y)+c.offset;int i=index(c,q);
        return noise(seed,c.strike,q,36,4.5,0x155b03L)<threshold(c.strike,q)?base.bands().get(i).secondary():primary(c,q,i);
    }
    /**24.14.2: all ledge/spire bodies share the same bedding, shifted one tone.
     * Stone's half-share uses a connected field, never a per-block coin toss. */
    public String bodyMaterial(int x,int y,int z){String natural=material(x,y,z);
        return switch(natural){case "tuff"->"andesite";case "deepslate"->"tuff";case "cobbled_deepslate"->"deepslate";
            case "blackstone"->"cobbled_deepslate";case "dripstone_block"->"white_terracotta";
            case "stone"->{var c=column(x,z);yield noise(seed,c.strike,-((double)y)+c.offset,36,4.5,0x155b19L)<.5?"andesite":"stone";}
            default->natural;};
    }
    public double hardness(int x,int y,int z){var c=column(x,z);double q=-((double)y)+c.offset;return LivingAbyssStrata155.hardness(primary(c,q,index(c,q)));}
    /** No independent per-voxel salt: sub-block camouflage has zero admission.
     * Patch axes are36m along strike /4.5m in bedding depth (ratio8:1). */
    private static double noise(long seed,double u,double v,double px,double py,long salt){
        double x=u/px,y=v/py;long ix=(long)Math.floor(x),iy=(long)Math.floor(y);
        double fx=LivingAbyssFields.smooth(x-ix),fy=LivingAbyssFields.smooth(y-iy);long s=seed^LivingAbyssFields.mix(salt);
        double a=mixUnit(s^LivingAbyssFields.mix(ix)^Long.rotateLeft(LivingAbyssFields.mix(iy),31));
        double b=mixUnit(s^LivingAbyssFields.mix(ix+1)^Long.rotateLeft(LivingAbyssFields.mix(iy),31));
        double c=mixUnit(s^LivingAbyssFields.mix(ix)^Long.rotateLeft(LivingAbyssFields.mix(iy+1),31));
        double d=mixUnit(s^LivingAbyssFields.mix(ix+1)^Long.rotateLeft(LivingAbyssFields.mix(iy+1),31));
        return (a+(b-a)*fx)*(1-fy)+(c+(d-c)*fx)*fy;
    }
    private static double threshold(double u,double q){double x=u/36,y=q/4.5;
        x=LivingAbyssFields.smooth(x-Math.floor(x))*32;y=LivingAbyssFields.smooth(y-Math.floor(y))*32;
        int ix=Math.min(31,(int)x),iy=Math.min(31,(int)y);double fx=x-ix,fy=y-iy;
        return (QUARTILES[ix][iy]*(1-fx)+QUARTILES[ix+1][iy]*fx)*(1-fy)+(QUARTILES[ix][iy+1]*(1-fx)+QUARTILES[ix+1][iy+1]*fx)*fy;
    }
    private static double[][] quartiles(){var out=new double[33][33];
        for(int ix=0;ix<=32;ix++)for(int iy=0;iy<=32;iy++){double x=ix/32.,y=iy/32.;
            double[] all={(1-x)*(1-y),x*(1-y),(1-x)*y,x*y};var weights=new double[4];int n=0;
            for(double w:all)if(w>1e-12)weights[n++]=w;double denominator=1;for(int i=1;i<=n;i++)denominator*=i;for(int i=0;i<n;i++)denominator*=weights[i];
            double low=0,high=1;
            for(int step=0;step<40;step++){double q=(low+high)/2,sum=0;
                for(int mask=0;mask<(1<<n);mask++){double v=q;for(int i=0;i<n;i++)if((mask&(1<<i))!=0)v-=weights[i];
                    if(v>0){double term=1;for(int i=0;i<n;i++)term*=v;sum+=(Integer.bitCount(mask)&1)==0?term:-term;}}
                if(sum/denominator<.25)low=q;else high=q;
            }out[ix][iy]=(low+high)/2;
        }return out;
    }
}
