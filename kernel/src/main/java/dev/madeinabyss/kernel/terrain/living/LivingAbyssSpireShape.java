package dev.madeinabyss.kernel.terrain.living;

import java.util.*;

/** Section18 prototype in local metres. Seeded rows are baked once, including connected-component
 * admission: the prototype's alternating terminal twin slices must not create floating rock. */
public final class LivingAbyssSpireShape {
    public static final List<String> KINDS=List.of("column","tapered","bottle","stacked","leaning","twin","needle");
    public static final int[] WEIGHTS={20,20,12,15,13,10,10};
    private record Joint(int y,boolean shelf,double angle,double halfWidth,double phase){}
    private final String kind;private final long seed;private final double radius;private final int height,pad;
    private final LivingAbyssVoxelPlan voxels;
    private final int[] columnMinimum,columnMaximum;
    private final List<int[]> summit;
    public LivingAbyssSpireShape(String kind,long seed,double radius,int height){
        if(!KINDS.contains(kind)||radius<3||radius>24||height<40||height>220)throw new IllegalArgumentException("Bounded section18 spire required");
        this.kind=kind;this.seed=seed;this.radius=radius;this.height=height;
        var rng=new SplittableRandom(seed);
        double taper=switch(kind){case "tapered"->.55;case "stacked","leaning"->.30;case "twin"->.20;case "needle"->.45;case "bottle"->.10;default->.15;};
        double flare=switch(kind){case "bottle"->.45;case "needle"->.20;case "leaning"->.15;case "tapered"->0;case "stacked","twin"->.05;default->.10;};
        double lean=switch(kind){case "leaning"->.16;case "needle"->.04;case "tapered"->.03;default->.02;};
        int facets=rng.nextInt(5,10),flutes=rng.nextInt(14,27);double rot=rng.nextDouble(2*Math.PI),amp=rng.nextDouble(.06,.12),morph=rng.nextDouble(.3,1),direction=rng.nextDouble(2*Math.PI);
        var joints=new ArrayList<Joint>();double joint=0;while(joint<height+15){joint+=rng.nextDouble(6,15);joints.add(new Joint((int)joint,rng.nextBoolean(),rng.nextDouble(2*Math.PI),rng.nextDouble(.6,1.8),rng.nextDouble(2*Math.PI)));}
        var offsets=new TreeMap<Integer,double[]>();if(kind.equals("stacked")){int count=rng.nextInt(2,5);while(offsets.size()<count)offsets.put(rng.nextInt((int)(height*.2),(int)(height*.85)),new double[]{gaussian(rng)*radius*.18,gaussian(rng)*radius*.18});}
        double margin=0;for(var off:offsets.values())margin+=Math.hypot(off[0],off[1]);
        pad=(int)Math.ceil(radius*1.6+height*lean+margin+7);int size=pad*2+1,area=size*size;
        columnMinimum=new int[area];columnMaximum=new int[area];Arrays.fill(columnMinimum,Integer.MAX_VALUE);Arrays.fill(columnMaximum,Integer.MIN_VALUE);
        byte[] data=new byte[area*(height+1)];
        for(int y=0;y<=height;y++){
            double t=y/(double)height,r=profile(radius,y,height,taper,flare),ox=Math.cos(direction)*lean*y,oz=Math.sin(direction)*lean*y;
            for(var off:offsets.entrySet())if(y>=off.getKey()){ox+=off.getValue()[0];oz+=off.getValue()[1];r*=.9;}
            boolean twin=kind.equals("twin")&&t>.55;double split=twin?(t-.55)/.45*r*.9:0;if(twin)r*=.62;
            Joint nearest=joints.get(0);for(var j:joints)if(Math.abs(y-j.y)<Math.abs(y-nearest.y))nearest=j;
            int jy=Math.abs(y-nearest.y);double jointDepth=jy==0?1.8:jy==1?1:0;
            int peaks=twin&&t<=.90?2:1;
            for(int peak=0;peak<peaks;peak++){
                double cx=ox+(twin?(peak==0?split:-split):0),cz=oz;
                int minX=Math.max(-pad,(int)Math.floor(cx-r-3)),maxX=Math.min(pad,(int)Math.ceil(cx+r+3));
                int minZ=Math.max(-pad,(int)Math.floor(cz-r-3)),maxZ=Math.min(pad,(int)Math.ceil(cz+r+3));
                for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
                    double dx=x-cx,dz=z-cz,rr=Math.hypot(dx,dz);if(rr>r+2)continue;
                    double th=Math.atan2(dz,dx)+rot+morph*t*1.5,period=2*Math.PI/facets;
                    double poly=Math.cos(Math.PI/facets)/Math.cos(mod(th,period)-Math.PI/facets);
                    double noise=.5+.5*LivingAbyssPlan.noise(seed,2.5,x,Math.floorDiv(y,12),z,1801);
                    double flute=1-amp*Math.abs(Math.sin(flutes*th*.5+6*noise));
                    double limit=r*poly*flute-jointDepth*(.4+.6*Math.abs(Math.sin(th*1.5+nearest.phase)));
                    for(var j:joints)if(j.shelf&&Math.abs(mod(th-j.angle+Math.PI,2*Math.PI)-Math.PI)<j.halfWidth){
                        if(j.y-y>0&&j.y-y<=4)limit-=3.0*(j.y-y)/4;
                        else if(y-j.y>=0&&y-j.y<=1)limit+=1.8;
                    }
                    if(rr<limit)data[(y*size+z+pad)*size+x+pad]=LivingAbyssFeatureBlock.STONE.code();
                }
            }
        }
        int anchor=pad*size+pad;var seen=new BitSet(data.length);int[] queue=new int[data.length];int head=0,tail=0;
        if(data[anchor]!=0){queue[tail++]=anchor;seen.set(anchor);}
        while(head<tail){int i=queue[head++],x=i%size,z=i/size%size,y=i/area;
            if(x>0)tail=admit(data,i-1,seen,queue,tail);if(x+1<size)tail=admit(data,i+1,seen,queue,tail);
            if(z>0)tail=admit(data,i-size,seen,queue,tail);if(z+1<size)tail=admit(data,i+size,seen,queue,tail);
            if(y>0)tail=admit(data,i-area,seen,queue,tail);if(y<height)tail=admit(data,i+area,seen,queue,tail);
        }
        var builder=new LivingAbyssVoxelPlan.Builder();var tops=new ArrayList<int[]>();
        for(int i=seen.nextSetBit(0);i>=0;i=seen.nextSetBit(i+1)){int x=i%size-pad,z=i/size%size-pad,y=i/area;builder.put(x,y,z,LivingAbyssFeatureBlock.STONE.ordinal());
            int column=(z+pad)*size+x+pad;columnMinimum[column]=Math.min(columnMinimum[column],y);columnMaximum[column]=Math.max(columnMaximum[column],y);
            if(y>=height-4&&(y==height||!seen.get(i+area)))tops.add(new int[]{x,y,z});}
        voxels=builder.freeze();builder.clear();summit=List.copyOf(tops);
    }
    private static int admit(byte[] data,int i,BitSet seen,int[] queue,int tail){if(data[i]!=0&&!seen.get(i)){seen.set(i);queue[tail++]=i;}return tail;}
    private static double gaussian(SplittableRandom r){return Math.sqrt(-2*Math.log(Math.max(1e-12,r.nextDouble())))*Math.cos(2*Math.PI*r.nextDouble());}
    private static double mod(double x,double p){return x-Math.floor(x/p)*p;}
    public static double profile(double radius,double y,double height,double taper,double flare){double t=Math.max(0,Math.min(1,y/height));return radius*(1-taper*t)*(1+flare*Math.pow(Math.max(0,(t-.82)/.18),1.5))*(1+.5*Math.pow(Math.max(0,1-y/10),2));}
    public LivingAbyssVoxelPlan voxels(){return voxels;}public String kind(){return kind;}public int pad(){return pad;}
    public List<int[]> summit(){return summit.stream().map(int[]::clone).toList();}
    public boolean solid(int x,int y,int z){return voxels.at(x,y,z)!=0;}
    public int minimumY(int x,int z){return x < -pad||x>pad||z < -pad||z>pad?Integer.MAX_VALUE:columnMinimum[(z+pad)*(pad*2+1)+x+pad];}
    public int maximumY(int x,int z){return x < -pad||x>pad||z < -pad||z>pad?Integer.MIN_VALUE:columnMaximum[(z+pad)*(pad*2+1)+x+pad];}
    public static String choose(long seed){double pick=LivingAbyssWall.unit(seed)*100;for(int i=0;i<WEIGHTS.length;i++){pick-=WEIGHTS[i];if(pick<0)return KINDS.get(i);}return "needle";}
}
