package dev.madeinabyss.kernel.terrain.living;

import java.util.*;
import java.security.*;
import static dev.madeinabyss.kernel.terrain.living.LivingAbyssFeatureBlock.*;

/** Parameter/algorithm port of v15_tree_proto.py. No external assets or runtime growth.
 * Face-connected voxelisation and pruning close prototype gaps; roots have no soil pixels. */
public final class LivingAbyssTreeStamp {
    private LivingAbyssTreeStamp(){}
    private record Params(int h0,int h1,double r0,double rt,double flare,double split,int limbs0,int limbs1,
                          double tilt0,double tilt1,double len0,double len1,int depth,double cushion0,double cushion1,
                          double flat,LivingAbyssFeatureBlock log,LivingAbyssFeatureBlock leaf,int roots,double moss,double vines,
                          int vine0,int vine1,int stubs,int flutes,boolean doubled,double lean,double twist,double sideBias){}
    private static final Map<String,Params> KINDS=Map.of(
        "broad_oak",new Params(12,15,1.7,1.2,1.6,.55,4,6,32,55,.6,.85,2,3.8,5,.42,OAK_LOG,OAK_LEAVES,6,.18,.07,3,8,0,0,false,0,0,0),
        "dark_oak",new Params(10,13,1.5,1,1.4,.45,6,8,25,45,.4,.55,2,4.2,5.4,.8,DARK_OAK_LOG,DARK_OAK_LEAVES,6,.3,.05,2,6,0,0,true,0,0,0),
        "giant",new Params(30,34,5.6,4.4,1,.6,7,9,5,25,.32,.5,3,5,7,.36,OAK_LOG,OAK_LEAVES,10,.45,.12,4,12,3,10,false,0,0,0),
        "guiding",new Params(40,44,4.2,2.4,1,.55,8,10,4,26,.36,.6,3,5,7.5,.34,OAK_LOG,OAK_LEAVES,11,.4,.12,4,12,2,8,false,.22,1.4,.7),
        "birch",new Params(8,12,.6,.4,.5,.6,3,5,32,55,.4,.6,2,2.5,3.5,.5,BIRCH_LOG,BIRCH_LEAVES,4,.05,.02,2,4,0,0,false,0,0,0),
        "crooked",new Params(6,9,1,.7,1,.5,3,5,32,55,.5,.7,2,3,4,.42,OAK_LOG,OAK_LEAVES,5,.12,.04,3,5,0,0,false,.12,.8,0));
    public static final class Stamp {
        private final int minX,minY,minZ,sx,sy,sz;private final byte[] voxels;private final String digest;
        private Stamp(int x,int y,int z,int sx,int sy,int sz,byte[] data){this.minX=x;this.minY=y;this.minZ=z;this.sx=sx;this.sy=sy;this.sz=sz;voxels=data;
            try{var md=MessageDigest.getInstance("SHA-256");for(int n:new int[]{x,y,z,sx,sy,sz})md.update(java.nio.ByteBuffer.allocate(4).putInt(n).array());digest=HexFormat.of().formatHex(md.digest(data));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
        public int minX(){return minX;}public int minY(){return minY;}public int minZ(){return minZ;}public int sizeX(){return sx;}public int sizeY(){return sy;}public int sizeZ(){return sz;}
        public byte[] voxels(){return voxels.clone();}public String digest(){return digest;}
        public int at(int x,int y,int z){x-=minX;y-=minY;z-=minZ;return x<0||y<0||z<0||x>=sx||y>=sy||z>=sz?0:Byte.toUnsignedInt(voxels[(y*sz+z)*sx+x]);}
        public void visit(Voxel consumer){for(int y=0;y<sy;y++)for(int z=0;z<sz;z++)for(int x=0;x<sx;x++){int b=Byte.toUnsignedInt(voxels[(y*sz+z)*sx+x]);if(b!=0)consumer.accept(x+minX,y+minY,z+minZ,b);}}
    }
    @FunctionalInterface public interface Voxel{void accept(int x,int y,int z,int block);}
    private static final class Grid {
        final int x0,y0,z0,sx,sy,sz;final byte[] g;
        Grid(int radius,int bottom,int top){x0=z0=-radius;y0=bottom;sx=sz=radius*2+1;sy=top-bottom+1;g=new byte[sx*sy*sz];}
        int index(int x,int y,int z){x-=x0;y-=y0;z-=z0;return x<0||y<0||z<0||x>=sx||y>=sy||z>=sz?-1:(y*sz+z)*sx+x;}
        int at(int x,int y,int z){int n=index(x,y,z);return n<0?0:Byte.toUnsignedInt(g[n]);}
        void set(double x,double y,double z,int b,boolean onlyAir){int n=index(round(x),round(y),round(z));if(n>=0&&(!onlyAir||g[n]==0))g[n]=(byte)b;}
        void disc(double[] c,double r,int b,double[] dir,int flute,double phase){
            var d=unit(dir);var u=unit(cross(d,Math.abs(d[2])<.9?new double[]{0,0,1}:new double[]{1,0,0}));var v=cross(d,u);int R=(int)Math.ceil(r+1.5);
            for(int i=-R;i<=R;i++)for(int j=-R;j<=R;j++){double lim=r*(flute==0?1:1+.16*Math.cos(flute*Math.atan2(j,i)+phase));
                if(Math.hypot(i,j)<=lim+.35)set(c[0]+u[0]*i+v[0]*j,c[1]+u[1]*i+v[1]*j,c[2]+u[2]*i+v[2]*j,b,false);}
        }
        void line(double[] a,double[] b,int block,boolean onlyAir){int n=Math.max(1,(int)Math.ceil(Math.max(Math.abs(a[0]-b[0]),Math.max(Math.abs(a[1]-b[1]),Math.abs(a[2]-b[2])))*2));
            int[] prev={round(a[0]),round(a[1]),round(a[2])};set(prev[0],prev[1],prev[2],block,onlyAir);
            for(int i=1;i<=n;i++){int[] next={round(a[0]+(b[0]-a[0])*i/n),round(a[1]+(b[1]-a[1])*i/n),round(a[2]+(b[2]-a[2])*i/n)};
                for(int axis=0;axis<3;axis++)while(prev[axis]!=next[axis]){prev[axis]+=Integer.signum(next[axis]-prev[axis]);set(prev[0],prev[1],prev[2],block,onlyAir);}}
        }
        Stamp bake(){
            int root=index(0,0,0);if(g[root]==0)throw new IllegalStateException("No embedded root");
            var seen=new BitSet(g.length);int[] queue=new int[g.length];int head=0,tail=1;queue[0]=root;seen.set(root);
            while(head<tail){int n=queue[head++],x=n%sx,z=n/sx%sz,y=n/(sx*sz);
                if(x>0)tail=admit(n-1,seen,queue,tail);if(x+1<sx)tail=admit(n+1,seen,queue,tail);if(z>0)tail=admit(n-sx,seen,queue,tail);if(z+1<sz)tail=admit(n+sx,seen,queue,tail);if(y>0)tail=admit(n-sx*sz,seen,queue,tail);if(y+1<sy)tail=admit(n+sx*sz,seen,queue,tail);}
            int ax=sx,ay=sy,az=sz,bx=0,by=0,bz=0;
            for(int n=0;n<g.length;n++){if(!seen.get(n)){g[n]=0;continue;}int x=n%sx,z=n/sx%sz,y=n/(sx*sz);ax=Math.min(ax,x);ay=Math.min(ay,y);az=Math.min(az,z);bx=Math.max(bx,x);by=Math.max(by,y);bz=Math.max(bz,z);}
            int X=bx-ax+1,Y=by-ay+1,Z=bz-az+1;byte[] trimmed=new byte[X*Y*Z];
            for(int y=ay;y<=by;y++)for(int z=az;z<=bz;z++)System.arraycopy(g,(y*sz+z)*sx+ax,trimmed,((y-ay)*Z+z-az)*X,X);
            return new Stamp(x0+ax,y0+ay,z0+az,X,Y,Z,trimmed);
        }
        int admit(int n,BitSet seen,int[] queue,int tail){if(g[n]!=0&&!seen.get(n)){seen.set(n);queue[tail++]=n;}return tail;}
    }
    private static final class Rng {
        final SplittableRandom r;Rng(long seed){r=new SplittableRandom(seed);}
        double u(){return r.nextDouble();}double between(double a,double b){return a+(b-a)*u();}int integer(int a,int b){return r.nextInt(a,b);}
        double normal(double deviation){return Math.sqrt(-2*Math.log(Math.max(1e-12,u())))*Math.cos(2*Math.PI*u())*deviation;}
    }
    public static Stamp build(String kind,long seed){
        if(kind.equals("inverted"))return inverted(seed);
        if(kind.equals("crack"))return crack(seed);
        var k=KINDS.get(kind);if(k==null)throw new IllegalArgumentException("Unapproved tree kind "+kind);var rng=new Rng(seed);
        boolean cliff=kind.equals("guiding");var G=new Grid(cliff?80:kind.equals("giant")?64:40,-16,cliff?96:kind.equals("giant")?80:48);
        int h=rng.integer(k.h0,k.h1),log=k.log.ordinal();double lx=k.lean+rng.normal(.015),lz=rng.normal(.015);
        var centers=new ArrayList<double[]>();var fractions=new ArrayList<Double>();var radii=new ArrayList<Double>();
        double[][] trunks=k.doubled?new double[][]{{-.9,-.3},{.9,.35}}:new double[][]{{0,0}};
        for(var offset:trunks)for(int y=0;y<(int)(h*(k.doubled?.85:1));y++){
            double t=y/(double)h,r=(k.r0+(k.rt-k.r0)*Math.pow(t,.7))*(1+k.flare*Math.pow(Math.max(0,1-t/.3),2));
            double ox=k.doubled?offset[0]*(.4+1.6*t):0,oz=k.doubled?offset[1]*(.4+1.6*t):0;if(k.doubled)r*=.75;
            var c=new double[]{lx*y*(.4+t)+ox+Math.sin(t*2*Math.PI*.8)*k.twist*1.4,y,lz*y+oz+(Math.cos(t*2*Math.PI*.8)-1)*k.twist*1.4};
            G.disc(c,r,log,new double[]{0,1,0},k.flutes,k.twist*t*5);centers.add(c);fractions.add(t);radii.add(r);
        }
        G.set(0,0,0,log,false);
        for(int i=0;i<k.roots;i++){
            double az=2*Math.PI*i/k.roots+rng.between(-.2,.2),length=k.r0*rng.between(1.8,3.2);double[] previous={0,0,0};
            for(int n=0,N=(int)(length*3)+3;n<=N;n++){double s=n/(double)N,rr=k.r0*(1+k.flare*.6)+s*length,x=Math.cos(az)*rr,z=Math.sin(az)*rr;
                var at=new double[]{x,0,z};G.line(previous,at,log,false);previous=at;
                for(int y=0;y<=(int)(Math.pow(1-s,1.5)*k.r0*2.4);y++)G.set(x,y,z,y>0||s<.6?log:MANGROVE_ROOTS.ordinal(),false);
                if(cliff&&x>=k.r0*1.5-1){int depth=rng.integer(6,16);G.line(at,new double[]{x,-depth,z},MANGROVE_ROOTS.ordinal(),false);break;}
            }
        }
        var tips=new ArrayList<double[]>();int limbs=rng.integer(k.limbs0,k.limbs1);
        for(int i=0;i<limbs;i++){
            double az=2*Math.PI*(i+rng.between(-.3,.3))/limbs;if(k.sideBias>0&&rng.u()<k.sideBias)az=rng.normal(.7);
            double st=rng.between(k.split,.92);int best=0;for(int j=1;j<centers.size();j++)if(Math.abs(fractions.get(j)-st)<Math.abs(fractions.get(best)-st))best=j;
            double tilt=Math.toRadians(rng.between(k.tilt0,k.tilt1)),r=radii.get(best);
            limb(G,rng,centers.get(best),new double[]{Math.cos(az)*Math.cos(tilt),Math.sin(tilt),Math.sin(az)*Math.cos(tilt)},h*rng.between(k.len0,k.len1),Math.max(r*.5,.9),Math.max(r*.18,.5),k.depth,k,tips);
        }
        for(int i=0;i<k.stubs;i++){var a=centers.get(rng.integer(centers.size()/3,centers.size()));double az=rng.between(0,2*Math.PI),len=rng.between(4,8);G.line(a,new double[]{a[0]+Math.cos(az)*len,a[1]+.9*len,a[2]+Math.sin(az)*len},log,false);}
        var top=centers.get(centers.size()-1).clone();top[1]+=2;tips.add(top);
        for(var p:tips){p=p.clone();p[1]+=1;cushion(G,p,rng.between(k.cushion0,k.cushion1),k.flat,k.leaf.ordinal(),seed,false);}
        finish(G,rng,k,h);return G.bake();
    }
    private static Stamp crack(long seed){
        var rng=new Rng(seed);var G=new Grid(16,-12,12);int log=OAK_LOG.ordinal();G.set(0,0,0,log,false);
        double[] p={0,0,0},d={.8,-.35,rng.normal(.3)};int length=rng.integer(4,9);
        for(int i=0;i<length;i++){var dd=unit(d);var next=new double[]{p[0]+dd[0],p[1]+dd[1],p[2]+dd[2]};G.line(p,next,log,false);p=next;d[1]+=.18;}
        cushion(G,p,rng.between(2.2,3.4),.67,OAK_LEAVES.ordinal(),seed,false);return G.bake();
    }
    private static void limb(Grid G,Rng rng,double[] a,double[] direction,double length,double rs,double re,int depth,Params k,List<double[]> tips){
        var d=unit(direction);var p=a.clone();int n=Math.max(2,(int)(length*1.6));
        for(int i=0;i<=n;i++){double t=i/(double)n;var dd=unit(new double[]{d[0]+rng.normal(.04),d[1]+.25*t+rng.normal(.04),d[2]+rng.normal(.04)});double r=rs+(re-rs)*t;
            if(r>=.6)G.disc(p,r,k.log.ordinal(),dd,0,0);var next=new double[]{p[0]+dd[0]*length/n,p[1]+dd[1]*length/n,p[2]+dd[2]*length/n};G.line(p,next,k.log.ordinal(),false);p=next;}
        if(depth<=1||length<3){tips.add(p);return;}
        int children=rng.integer(2,4);for(int i=0;i<children;i++){double az=rng.between(0,2*Math.PI);limb(G,rng,p,new double[]{d[0]*.6+Math.cos(az)*.7,d[1]*.6+rng.between(.1,.6)*.7,d[2]*.6+Math.sin(az)*.7},length*rng.between(.45,.7),Math.max(re,.4),Math.max(re*.5,.3),depth-1,k,tips);}tips.add(p);
    }
    private static void cushion(Grid G,double[] p,double r,double flat,int leaf,long seed,boolean inverted){
        int cx=round(p[0]),cy=round(p[1]),cz=round(p[2]),R=(int)r+2;
        int ry=(int)Math.ceil(inverted?r*.8:r*flat)+2;
        for(int y=cy-ry;y<=cy+ry;y++)for(int z=cz-R;z<=cz+R;z++)for(int x=cx-R;x<=cx+R;x++){
            double vr=inverted?r*(y<cy?.8:.35):r*flat*(y<cy?.6:1),d=((x-cx)*(x-cx)+(z-cz)*(z-cz))/(r*r)+(y-cy)*(y-cy)/(vr*vr);
            double noise=LivingAbyssPlan.noise(seed,inverted?2.2:2.4,x,y,z,1639)+.5;
            if(d<(inverted?.55:.7)||d<1&&noise>(inverted?.38+.3*d:.3+.25*d))G.set(x,y,z,leaf,true);
        }
    }
    private static void finish(Grid G,Rng rng,Params k,int h){
        var leaves=new ArrayList<int[]>();
        for(int y=G.y0;y<G.y0+G.sy;y++)for(int z=G.z0;z<G.z0+G.sz;z++)for(int x=G.x0;x<G.x0+G.sx;x++){
            int b=G.at(x,y,z);if(b==k.log.ordinal()&&y<h*.35&&rng.u()<k.moss)G.set(x,y,z,MOSS.ordinal(),false);
            if(b==k.leaf.ordinal())leaves.add(new int[]{x,y,z});
        }
        for(var p:leaves){int x=p[0],y=p[1],z=p[2];boolean inner=G.at(x+1,y,z)!=0&&G.at(x-1,y,z)!=0&&G.at(x,y+1,z)!=0&&G.at(x,y-1,z)!=0&&G.at(x,y,z+1)!=0&&G.at(x,y,z-1)!=0;
            if(inner&&k.leaf==OAK_LEAVES&&rng.u()<.5)G.set(x,y,z,INNER_LEAVES.ordinal(),false);
            if(G.at(x,y-1,z)==0&&rng.u()<k.vines)hangVine(G,x,y,z,rng.integer(k.vine0,k.vine1));
        }
    }
    private static void hangVine(Grid G,int x,int y,int z,int length){
        // Mount the first row to a leaf SIDE; subsequent rows inherit that face from above.
        for(int[] o:new int[][]{{1,0,4},{-1,0,8},{0,1,1},{0,-1,2}}){int X=x+o[0],Z=z+o[1];if(G.at(X,y,Z)!=0)continue;
            for(int n=0;n<length;n++){if(G.at(X,y-n,Z)!=0)break;G.set(X,y-n,Z,128+o[2],true);}return;}
    }
    private static Stamp inverted(long seed){
        var rng=new Rng(seed);var G=new Grid(48,-80,1);int log=DARK_OAK_LOG.ordinal();G.set(0,0,0,log,false);
        for(int i=0;i<9;i++){double a=2*Math.PI*i/9+rng.between(-.2,.2),L=rng.between(7,14);var tip=new double[]{Math.cos(a)*L,0,Math.sin(a)*L};G.line(new double[]{0,0,0},tip,MANGROVE_ROOTS.ordinal(),false);
            G.line(new double[]{0,1,0},new double[]{tip[0]*.4,1,tip[2]*.4},MANGROVE_ROOTS.ordinal(),false);
            for(int n=2;n<L;n++)if(rng.u()<.15){double x=Math.cos(a)*n,z=Math.sin(a)*n;G.line(new double[]{x,0,z},new double[]{x,-rng.integer(2,6),z},HANGING_ROOTS.ordinal(),true);}
        }
        int tl=rng.integer(16,24);double ox=rng.normal(.12),oz=rng.normal(.12);var centers=new ArrayList<double[]>();
        double[] previous={0,0,0};
        for(int d=0;d<tl;d++){double t=d/(double)tl,r=2.3-t+1.2*Math.pow(Math.max(0,1-d/4.0),2)+.8*Math.max(0,t-.8)*5;
            var c=new double[]{ox*d+Math.sin(t*7)*1.8,-1-d,oz*d+Math.cos(t*7)*1.8};G.disc(c,r,log,new double[]{0,1,0},5,t*4);G.line(previous,c,log,false);previous=c;centers.add(c);}
        var tips=new ArrayList<double[]>();var bottom=previous.clone();bottom[1]-=1;tips.add(bottom);
        for(int i=0;i<9;i++){double az=2*Math.PI*i/9+rng.between(-.3,.3);var p=centers.get((int)(rng.between(.55,.9)*(tl-1))).clone();var dvec=new double[]{Math.cos(az)*.8,-.6,Math.sin(az)*.8};
            int L=rng.integer(5,9);for(int s=0;s<L;s++){dvec[1]-=.12;var dd=unit(dvec);var next=new double[]{p[0]+dd[0],p[1]+dd[1],p[2]+dd[2]};G.line(p,next,log,false);p=next;}tips.add(p);}
        for(var p:tips)cushion(G,p,rng.between(6,8.5),.8,DARK_OAK_LEAVES.ordinal(),seed,true);
        var leaves=new ArrayList<int[]>();for(int y=-75;y<0;y++)for(int z=-40;z<=40;z++)for(int x=-40;x<=40;x++)if(G.at(x,y,z)==DARK_OAK_LEAVES.ordinal())leaves.add(new int[]{x,y,z});
        int glow=0;
        for(var p:leaves)if(G.at(p[0],p[1]-1,p[2])==0){if(rng.u()<.12){int L=rng.integer(3,9);if(rng.u()<.45)G.line(new double[]{p[0],p[1]-1,p[2]},new double[]{p[0],p[1]-L,p[2]},HANGING_ROOTS.ordinal(),true);else hangVine(G,p[0],p[1],p[2],L);}
            else if(glow<3&&rng.u()<.001){G.set(p[0],p[1]-1,p[2],GLOW_UP.ordinal(),true);glow++;}}
        return G.bake();
    }
    private static int round(double x){return (int)Math.floor(x+.5);}
    private static double[] unit(double[] v){double n=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]);return new double[]{v[0]/n,v[1]/n,v[2]/n};}
    private static double[] cross(double[] a,double[] b){return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
}
