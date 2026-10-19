package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.terrain.TerrainParameters;
import java.util.Objects;

/** Exact v15 specialization of CompiledTerrainSampler.cavityRadius for zero ledge projection.
 * Keeps the oracle's arithmetic and eight-corner accumulation order. A zero projection makes
 * localLedge exactly +0, so sector angles, ledge relief and ledge plans are unnecessary here.
 * The historical sampler and its bytecode remain untouched. */
public final class LivingAbyssWallRadius {
    private static final int COLUMN_SLOTS=1<<18;
    private static final int SQRT_LIMIT=1700;
    private final double mouth,delta,top,depthSpan,wavelength,wallAmplitude;
    private final long cliffSalt,ribSalt;
    private record Column(int x,int z,long ix,long iz,double fx,double fz,double ribs,int tableBase){}
    // Reference stores are atomic; all entry fields are final. Collisions only discard work.
    // No mutable column or partially initialized corner array is shared between workers.
    private final Column[] columns=new Column[COLUMN_SLOTS];
    private final int minimum,side,minimumY,rows;
    private final double[] cliffCorners,ribCorners;

    public LivingAbyssWallRadius(TerrainParameters parameters) {
        var p=Objects.requireNonNull(parameters);var c=p.cavity();var n=p.noise();
        if(c.ledgeProjection()!=0)throw new IllegalArgumentException("v15 wall radius requires zero ledge projection");
        mouth=c.mouthRadius();delta=c.lowerRadius()-c.mouthRadius();top=c.firstLayerTopY();
        depthSpan=c.firstLayerTopY()-c.declaredLowerBoundY();wavelength=n.wavelength();wallAmplitude=n.wallAmplitude();
        cliffSalt=p.seed()^LivingAbyssFields.mix(2);ribSalt=p.seed()^LivingAbyssFields.mix(11);
        long rimLimit=Math.round(mouth),lo=(long)Math.floor(-rimLimit/wavelength),hi=(long)Math.floor(rimLimit/wavelength)+1;
        long ylo=(long)Math.floor(-1500/wavelength),yhi=(long)Math.floor(400/wavelength)+1;
        long width=hi-lo+1,height=yhi-ylo+1;
        // Nonstandard parameter scales use the same exact hashing fallback, without unbounded tables.
        if(width<=128&&height<=4096&&width*width*height<=1_048_576) {
            minimum=(int)lo;side=(int)width;minimumY=(int)ylo;rows=(int)height;
            cliffCorners=new double[side*side*rows];ribCorners=new double[side*side*2];
            for(int ix=0;ix<side;ix++)for(int iz=0;iz<side;iz++) {
                int cell=ix*side+iz;
                for(int iy=0;iy<rows;iy++)cliffCorners[cell*rows+iy]=corner(cliffSalt,lo+ix,ylo+iy,lo+iz);
                for(int iy=0;iy<2;iy++)ribCorners[cell*2+iy]=corner(ribSalt,lo+ix,iy,lo+iz);
            }
        } else {minimum=side=minimumY=rows=0;cliffCorners=ribCorners=null;}
    }

    public double cavityRadius(int x,int y,int z) {
        if(y>=top)return mouth;
        double depth=top-(double)y,fraction=clamp(depth/depthSpan),envelope=smooth(clamp(depth/wavelength));
        var col=column(x,z);
        double cliff=cliff(col,y),wall=wallAmplitude*(0.65*col.ribs+0.35*cliff);
        return mouth+delta*smooth(fraction)+envelope*(wall-0.0);
    }

    /** Exact minimum of at most seventeen integer rows, including a cube's lower
     * support row. Reuses one immutable column without changing scalar arithmetic. */
    public double minimumRadius(int x,int minimumY,int maximumY,int z){
        if(minimumY>maximumY||(long)maximumY-minimumY>16)throw new IllegalArgumentException("One cube plus support row required");
        if(minimumY>=top)return mouth;
        var col=column(x,z);double minimum=Double.POSITIVE_INFINITY;
        for(int y=minimumY;;y++){
            double radius=mouth;
            if(y<top){
                double depth=top-(double)y,fraction=clamp(depth/depthSpan),envelope=smooth(clamp(depth/wavelength));
                double cliff=cliff(col,y),wall=wallAmplitude*(0.65*col.ribs+0.35*cliff);
                radius=mouth+delta*smooth(fraction)+envelope*(wall-0.0);
            }
            minimum=Math.min(minimum,radius);if(y==maximumY)return minimum;
        }
    }

    private Column column(int x,int z) {
        int slot=(int)LivingAbyssFields.mix(((long)x<<32)^(z&0xffffffffL))&(COLUMN_SLOTS-1);
        var known=columns[slot];if(known!=null&&known.x==x&&known.z==z)return known;
        // Exhaustively raw-bit checked for all 11,566,801 integer columns in this range on JDK17.
        // sqrt and hypot can differ by one ulp for large ints: retain the oracle outside the gate.
        double radial=x>=-SQRT_LIMIT&&x<=SQRT_LIMIT&&z>=-SQRT_LIMIT&&z<=SQRT_LIMIT
                ?Math.sqrt((double)x*x+(double)z*z):Math.hypot((double)x,(double)z);
        int rx=(int)Math.round(radial==0?mouth:x/radial*mouth),rz=(int)Math.round(radial==0?0:z/radial*mouth);
        double sx=rx/wavelength,sz=rz/wavelength;long ix=(long)Math.floor(sx),iz=(long)Math.floor(sz);
        double fx=smooth(sx-ix),fz=smooth(sz-iz);
        int cell=cliffCorners!=null&&ix>=minimum&&ix<minimum+side-1&&iz>=minimum&&iz<minimum+side-1
                ?((int)(ix-minimum)*side+(int)(iz-minimum)):-1;
        double ribs;
        if(cell>=0) {
            int at=cell*2,dx=side*2;
            ribs=interpolate(fx,0,fz,ribCorners[at],ribCorners[at+2],ribCorners[at+1],ribCorners[at+3],
                    ribCorners[at+dx],ribCorners[at+dx+2],ribCorners[at+dx+1],ribCorners[at+dx+3]);
        } else ribs=hashed(ribSalt,ix,0,iz,fx,0,fz);
        var col=new Column(x,z,ix,iz,fx,fz,ribs,cell<0?-1:cell*rows);columns[slot]=col;return col;
    }

    private double cliff(Column c,int y) {
        double sy=y/wavelength;long iy=(long)Math.floor(sy);double fy=smooth(sy-iy);
        if(c.tableBase>=0&&iy>=minimumY&&iy<minimumY+rows-1) {
            int at=c.tableBase+(int)(iy-minimumY),dx=side*rows;
            return interpolate(c.fx,fy,c.fz,cliffCorners[at],cliffCorners[at+rows],cliffCorners[at+1],cliffCorners[at+rows+1],
                    cliffCorners[at+dx],cliffCorners[at+dx+rows],cliffCorners[at+dx+1],cliffCorners[at+dx+rows+1]);
        }
        return hashed(cliffSalt,c.ix,iy,c.iz,c.fx,fy,c.fz);
    }

    private static double hashed(long salt,long ix,long iy,long iz,double fx,double fy,double fz) {
        return interpolate(fx,fy,fz,corner(salt,ix,iy,iz),corner(salt,ix,iy,iz+1),corner(salt,ix,iy+1,iz),corner(salt,ix,iy+1,iz+1),
                corner(salt,ix+1,iy,iz),corner(salt,ix+1,iy,iz+1),corner(salt,ix+1,iy+1,iz),corner(salt,ix+1,iy+1,iz+1));
    }
    private static double corner(long salt,long x,long y,long z) {
        long hash=LivingAbyssFields.mix(salt^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),21)^Long.rotateLeft(LivingAbyssFields.mix(z),42));
        return (hash>>>11)*0x1.0p-53*2-1;
    }
    private static double interpolate(double fx,double fy,double fz,double a,double b,double c,double d,double e,double f,double g,double h) {
        double x0=1-fx,y0=1-fy,z0=1-fz,result=0;
        result+=x0*y0*z0*a;result+=x0*y0*fz*b;result+=x0*fy*z0*c;result+=x0*fy*fz*d;
        result+=fx*y0*z0*e;result+=fx*y0*fz*f;result+=fx*fy*z0*g;result+=fx*fy*fz*h;
        return result;
    }
    private static double clamp(double value){return Math.max(0,Math.min(1,value));}
    private static double smooth(double value){return value*value*(3-2*value);}
}
