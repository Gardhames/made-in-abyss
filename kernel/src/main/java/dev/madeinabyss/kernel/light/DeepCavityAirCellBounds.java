package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.terrain.TerrainParameters;
import java.util.Objects;

/** Candidate rendering proof only; exact composed-profile/save admission belongs to Forge wrapper.
 * Entire16-cube plus10m XZ/32m Y support halo is bounded analytically, never corner-sampled.
 * Final v14 carve is an independent union and needs no parent ecology/support halo. */
public final class DeepCavityAirCellBounds {
    private static final int MIN_Y=-1286,MAX_DEEP_Y=-251,MIN_CY=-81,MAX_CY=-16;
    private static final int MIN_CXZ=-40,XZ=80,YS=66,SECTORS=23,BANDS=10;
    private static final double EPS=1e-8;
    private static final TerrainParameters.Surface SURFACE=new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3);
    private static final TerrainParameters.Cavity CAVITY=new TerrainParameters.Cavity(500,64,-1286,650,180,170,280);
    private static final TerrainParameters.Noise NOISE=new TerrainParameters.Noise(140,20,60,70);
    private final Tables tables;
    private record Patch(double top,double width,double projection){}
    private record Tables(long[] cells,short[] carve,long constructionNanos,int admittedCells){}
    private static final class Fixed {private static final Tables TABLES=build();}

    public DeepCavityAirCellBounds(TerrainParameters p) {
        Objects.requireNonNull(p);
        tables=p.seed()==20261003&&p.surface().equals(SURFACE)&&p.cavity().equals(CAVITY)&&p.noise().equals(NOISE)?Fixed.TABLES:null;
    }
    public boolean guaranteedAir(int x,int y,int z){return finalCarveAir(x,y,z)||deepCellAir(x,y,z);}
    public boolean deepCellAir(int x,int y,int z) {
        if(tables==null||y<MIN_Y||y>MAX_DEEP_Y)return false;
        int cx=Math.floorDiv(x,16),cz=Math.floorDiv(z,16),cy=Math.floorDiv(y,16);
        // Guard before subtraction/multiplication, including all signed-int endpoints.
        if(cx<MIN_CXZ||cx>=MIN_CXZ+XZ||cz<MIN_CXZ||cz>=MIN_CXZ+XZ||cy<MIN_CY||cy>MAX_CY)return false;
        int i=(cx-MIN_CXZ)+XZ*((cz-MIN_CXZ)+XZ*(cy-MIN_CY));
        return (tables.cells()[i>>>6]&(1L<<(i&63)))!=0;
    }
    public int finalCarveRadiusAtY(int y){return tables==null||y<=-400||y>=64?0:tables.carve()[y+399];}
    public boolean finalCarveAir(int x,int y,int z) {
        int r=finalCarveRadiusAtY(y);return r>0&&(double)x*x+(double)z*z<(double)r*r;
    }
    public int residentArrayBytes(){return tables==null?0:tables.cells().length*8+tables.carve().length*2;}
    public long constructionNanos(){return tables==null?0:tables.constructionNanos();}
    public int admittedCellCount(){return tables==null?0:tables.admittedCells();}

    private static Tables build() {
        long start=System.nanoTime();
        var patches=new Patch[SECTORS][BANDS];
        for(int s=0;s<SECTORS;s++)for(int b=0;b<BANDS;b++)patches[s][b]=new Patch(
            (b+.2+.6*random(s,b,21))*180+(2*random(s,b,22)-1)*70,
            170*(.6+.8*random(s,b,23)),280*(.4+.6*random(s,b,24)));
        var extrusions=new double[1350][SECTORS];
        for(int y=MIN_Y;y<=63;y++)for(int s=0;s<SECTORS;s++) {
            double d=64-y;int band=(int)Math.floor(d/180);double e=0;
            for(int b=Math.max(0,band-2);b<=band+2;b++) {
                var p=patches[s][b];double relief=20+EPS;
                if(d<p.top()-relief||d>p.top()+relief+p.width()+EPS)continue;
                double remaining=Math.max(0,1-Math.max(0,d-p.top()-relief)/(p.width()+EPS));
                e=Math.max(e,(p.projection()+EPS)*remaining*remaining+EPS);
            }
            extrusions[y-MIN_Y][s]=e;
        }
        var verticalE=new double[YS][SECTORS];var verticalBase=new double[YS];
        for(int cy=MIN_CY;cy<=MAX_CY;cy++) {
            int low=Math.max(MIN_Y,cy*16-32),high=Math.min(63,cy*16+15+32),row=cy-MIN_CY;
            verticalBase[row]=base(high);
            for(int y=low;y<=high;y++)for(int s=0;s<SECTORS;s++)verticalE[row][s]=Math.max(verticalE[row][s],extrusions[y-MIN_Y][s]);
        }
        long[] bits=new long[(XZ*XZ*YS+63)/64];int admitted=0;
        for(int cx=MIN_CXZ;cx<MIN_CXZ+XZ;cx++)for(int cz=MIN_CXZ;cz<MIN_CXZ+XZ;cz++) {
            // Bounding circle encloses the ENTIRE expanded voxel rectangle, including edges.
            double centreX=cx*16+7.5,centreZ=cz*16+7.5,centreR=Math.hypot(centreX,centreZ);
            double circleR=Math.sqrt(2)*17.5+EPS;
            double a=(Math.atan2(centreZ,centreX)/(2*Math.PI)+1)%1*SECTORS;
            double half=centreR<=circleR?SECTORS/2.0:(Math.asin(Math.min(1,(circleR+EPS)/centreR))+EPS)*SECTORS/(2*Math.PI);
            double[] weights=new double[SECTORS];
            for(int s=0;s<SECTORS;s++) {
                double distance=Double.POSITIVE_INFINITY;
                for(int turn=-1;turn<=1;turn++)distance=Math.min(distance,Math.max(0,Math.abs(a-(s+turn*SECTORS))-half-EPS));
                weights[s]=Math.min(1+EPS,smooth(Math.max(0,Math.min(1,1-distance)))+EPS);
            }
            double farX=Math.max(Math.abs(cx*16-10.0),Math.abs(cx*16+25.0));
            double farZ=Math.max(Math.abs(cz*16-10.0),Math.abs(cz*16+25.0));
            double maximumRadius=Math.hypot(farX,farZ);
            for(int cy=MIN_CY;cy<=MAX_CY;cy++) {
                int row=cy-MIN_CY;double extrusion=0;
                for(int s=0;s<SECTORS;s++)extrusion=Math.max(extrusion,weights[s]*verticalE[row][s]);
                // Full wall/envelope worst case; numerical margin is a whole metre.
                double lower=verticalBase[row]-60-extrusion-1;
                if(maximumRadius<lower) {
                    int i=(cx-MIN_CXZ)+XZ*((cz-MIN_CXZ)+XZ*row);bits[i>>>6]|=1L<<(i&63);admitted++;
                }
            }
        }
        short[] carve=new short[463];
        for(int y=-399;y<=63;y++) {
            double d=64-y,envelope=smooth(Math.max(0,Math.min(1,d/140)));
            double retention=y>=-250?0:smooth((-250-y)/150.0),e=0;
            for(int s=0;s<SECTORS;s++)e=Math.max(e,extrusions[y-MIN_Y][s]);
            // Final-carve guarantee: radius<500 preserves independent island delegation.
            double lower=base(y)-envelope*(60+EPS+retention*e)-1;
            carve[y+399]=(short)Math.max(0,Math.floor(Math.min(500,lower)));
        }
        return new Tables(bits,carve,System.nanoTime()-start,admitted);
    }
    private static double base(int y){double f=Math.max(0,Math.min(1,(64-y)/1350.0));return 500+150*smooth(f);}
    private static double smooth(double f){return f*f*(3-2*f);}
    private static double random(long s,long b,long salt){return (mix(20261003^mix(s)^Long.rotateLeft(mix(b),29)^mix(salt))>>>11)*0x1.0p-53;}
    private static long mix(long v){v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;return v^(v>>>31);}
}
