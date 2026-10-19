package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.io.*;
import java.nio.charset.StandardCharsets;
import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;
import dev.madeinabyss.kernel.light.SkyColumnKey;

/** Approved v13 reconstruction. This independent surface never alters the legacy cavity sampler. */
public final class IslandSurfaceGeneration {
    public static final String DATA_SHA="f1dec5462139bfbcde073d9693653b0dc96dbb38c5b1bf8aefda3b114ec2d9af";
    public static final String COMPRESSED_SHA="8fead3a7f089b1053d6ed3627b553a260a3754186a3a69a8e77c4868251d1f00";
    private static final double[] DIST={0,30,120,320,1100,2600,4800,7500,10000};
    private static final double[] BED={0,-9,-32,-105,-510,-1510,-2910,-3860,-4100};
    private static final double[] DERIV=pchipDerivatives();
    private final double[] heights;
    private final int legacyLower;
    private final BoundedReuseCache<SkyColumnKey,Column> columns;
    private final BoundedReuseCache<SkyColumnKey,Tree> trees;
    private final int columnCapacity,treeCapacity;
    private final boolean cacheEnabled;
    private final java.util.concurrent.atomic.LongAdder columnBuilds=new java.util.concurrent.atomic.LongAdder(),treeBuilds=new java.util.concurrent.atomic.LongAdder();
    private final ThreadLocal<LastColumn> lastColumn=ThreadLocal.withInitial(LastColumn::new);
    private final int maximum;
    private record Column(int top,double slope,boolean soil){}
    private record Tree(int x,int z,int ground,int height,boolean valid){}
    private static final class LastColumn {int x,z;Column value;}

    public IslandSurfaceGeneration(JsonObject recipe,TerrainParameters legacy){
        this(recipe,legacy,Integer.getInteger("mia.generated.islandColumnCapacity",16384),
                Integer.getInteger("mia.generated.islandTreeCapacity",4096),
                Boolean.parseBoolean(System.getProperty("mia.generated.islandCache","true")));
    }
    IslandSurfaceGeneration(JsonObject recipe,TerrainParameters legacy,int columnCapacity,int treeCapacity,boolean cacheEnabled){
        if(columnCapacity<1||columnCapacity>131072||treeCapacity<1||treeCapacity>65536)
            throw new IllegalArgumentException("Island cache capacities outside bounded diagnostic range");
        this.columnCapacity=columnCapacity;this.treeCapacity=treeCapacity;this.cacheEnabled=cacheEnabled;
        columns=new BoundedReuseCache<>(128L*columnCapacity,columnCapacity,ignored->128L);
        trees=new BoundedReuseCache<>(128L*treeCapacity,treeCapacity,ignored->128L);
        JsonObject required=proposal();
        // Only a fixed oracle-audited implementation pair can admit the original saved
        // recipe. The caller's manifest and its canonical world identity remain unchanged.
        recipe=IslandAlgorithmEquivalence.forConstruction(recipe,required.get("algorithmSha256").getAsString());
        for(var e:required.entrySet()){
            if(!recipe.has(e.getKey())||!e.getValue().equals(recipe.get(e.getKey())))
                throw new IllegalArgumentException("Unsupported/stale island field "+e.getKey());
        }
        legacyLower=legacy.cavity().declaredLowerBoundY();
        if(legacy.cavity().mouthRadius()!=500 || legacy.cavity().firstLayerTopY()!=64)
            throw new IllegalArgumentException("Approved island requires preserved R500/Y64 mouth");
        heights=IslandHeightAsset.load(DATA_SHA,COMPRESSED_SHA);
        double max=0;for(double v:heights)max=Math.max(max,v);
        maximum=(int)Math.floor(max)+9;
        if(maximum>=320)throw new IllegalArgumentException("Island plants exceed native surface lighting ceiling");
    }
    public static JsonObject proposal(){
        try(var in=IslandSurfaceGeneration.class.getClassLoader().getResourceAsStream(
                "assets/madeinabyss/terrain/island-v13-proposal-1.f64le.recipe.json")){
            if(in==null)throw new IllegalArgumentException("Missing island recipe metadata");
            var j=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            if(!j.get("assetId").getAsString().equals(IslandHeightAsset.ID)
                    ||!j.get("dataSha256").getAsString().equals(DATA_SHA)
                    ||!j.get("compressedSha256").getAsString().equals(COMPRESSED_SHA))
                throw new IllegalArgumentException("Bundled island metadata does not identify the fixed approved asset");
            j.addProperty("algorithmSha256",actualAlgorithmSha256());return j;
        }catch(IOException e){throw new IllegalArgumentException(e);}
    }
    public static String actualAlgorithmSha256(){
        return TerrainLodDiskCache.codeIdentity(IslandSurfaceGeneration.class,Column.class,Tree.class,IslandHeightAsset.class,
                SkyColumnKey.class,BoundedReuseCache.class);
    }
    public int minimumKnownY(){return -5000;}
    public int maximumSurfaceY(){return maximum;}
    public java.util.List<BlockState> palette(){
        return java.util.List.of(Blocks.AIR.defaultBlockState(),Blocks.WATER.defaultBlockState(),Blocks.SAND.defaultBlockState(),
                Blocks.GRAVEL.defaultBlockState(),Blocks.COARSE_DIRT.defaultBlockState(),Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),Blocks.DEEPSLATE.defaultBlockState(),Blocks.ANDESITE.defaultBlockState(),
                Blocks.TUFF.defaultBlockState(),Blocks.STONE.defaultBlockState(),Blocks.GRASS.defaultBlockState(),
                Blocks.FERN.defaultBlockState(),Blocks.OAK_LOG.defaultBlockState(),
                Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true));
    }
    public int knownLowerY(int x,int z){return (double)x*x+(double)z*z<921600?legacyLower:-5000;}
    /** Closest actual voxel, including signed edge cells; no centre-only or overflow-prone test. */
    public boolean supportsCube(CubeKey key){
        long x=(long)key.x()*16,y=(long)key.y()*16,z=(long)key.z()*16;
        if(x<Integer.MIN_VALUE||x+15>Integer.MAX_VALUE||y<Integer.MIN_VALUE||y+15>Integer.MAX_VALUE
                ||z<Integer.MIN_VALUE||z+15>Integer.MAX_VALUE)return false;
        long cx=x>0?x:(x+15<0?x+15:0),cz=z>0?z:(z+15<0?z+15:0);
        int low=(double)cx*cx+(double)cz*cz<921600?legacyLower:-5000;
        return y>=low;
    }
    /** Null is delegation, AIR is intentional cutting, WATER is actual ocean volume. */
    public BlockState sampleIfOwned(int x,int y,int z){
        double r2=(double)x*x+(double)z*z;
        if(r2<250000 || (r2<921600 && y<0))return null;
        if(y<knownLowerY(x,z))throw new IllegalArgumentException("Island coordinate below known domain: "+x+","+y+","+z);
        Column c=column(x,z);
        if(y<=c.top){
            int depth=c.top-y;
            long patch=hash(Math.floorDiv(x,13),Math.floorDiv(z,13));
            if(c.top<=11 && c.slope<.85 && depth<3)
                return ((patch&7)<5?Blocks.SAND:Blocks.GRAVEL).defaultBlockState();
            if(c.soil && depth==0)return ((patch&15)==0?Blocks.COARSE_DIRT:Blocks.GRASS_BLOCK).defaultBlockState();
            if(c.soil && depth<4)return Blocks.DIRT.defaultBlockState();
            if(depth<2 && c.slope>.85 && (patch&7)==0)return Blocks.GRAVEL.defaultBlockState();
            long stratum=Math.floorDiv(y+Math.floorDiv(x,47)-Math.floorDiv(z,63),19);
            return (y< -256?Blocks.DEEPSLATE:Math.floorMod(stratum,7)==0?Blocks.ANDESITE:
                    Math.floorMod(stratum,7)==1?Blocks.TUFF:Blocks.STONE).defaultBlockState();
        }
        if(y<=0)return Blocks.WATER.defaultBlockState();
        // Terrain is already resolved below plants; crowns never replace a hillside.
        if(r2>291600 && c.top>12 && y<=c.top+8 && y<=maximum){
            BlockState plant=treeAt(x,y,z);if(plant!=null)return plant;
            if(y==c.top+1 && c.soil){
                long scatter=hash(x,z);
                if((scatter&31)<8)return ((scatter&1)==0?Blocks.GRASS:Blocks.FERN).defaultBlockState();
                if((scatter&255)==37)return Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
            }
        }
        return Blocks.AIR.defaultBlockState();
    }
    private Column column(int x,int z){
        LastColumn last=lastColumn.get();if(cacheEnabled&&last.value!=null&&last.x==x&&last.z==z)return last.value;
        var key=new SkyColumnKey(x,z);Column cached=cacheEnabled?columns.get(key):null;
        if(cached!=null){last.x=x;last.z=z;last.value=cached;return cached;}
        columnBuilds.increment();
        double h=surfaceHeight(x,z),dx=(surfaceHeight((double)x+2,z)-surfaceHeight((double)x-2,z))/4,
                dz=(surfaceHeight(x,(double)z+2)-surfaceHeight(x,(double)z-2))/4;
        double slope=Math.hypot(dx,dz);boolean soil=h>12&&slope<.726542528; // 36 degrees
        var c=new Column((int)Math.floor(h),slope,soil);
        if(cacheEnabled){columns.put(key,c);last.x=x;last.z=z;last.value=c;}
        return c;
    }
    private Tree tree(int cellX,int cellZ){
        var key=new SkyColumnKey(cellX,cellZ);Tree t=cacheEnabled?trees.get(key):null;if(t!=null)return t;
        treeBuilds.increment();
        long noise=hash(cellX,cellZ);long bx=(long)cellX*20+4+Math.floorMod(noise,12),
                bz=(long)cellZ*20+4+Math.floorMod(noise>>>13,12);
        boolean range=bx>=Integer.MIN_VALUE+4&&bx<=Integer.MAX_VALUE-4&&bz>=Integer.MIN_VALUE+4&&bz<=Integer.MAX_VALUE-4;
        int x=(int)bx,z=(int)bz;Column c=range?column(x,z):new Column(0,0,false);
        boolean valid=range&&(noise&7)<4&&Math.hypot((double)x,(double)z)>550&&c.soil&&c.slope<.47;
        if(valid)for(int ox=-2;ox<=2;ox++)for(int oz=-2;oz<=2;oz++)
            if(!column(x+ox,z+oz).soil||Math.abs(column(x+ox,z+oz).top-c.top)>2)valid=false;
        t=new Tree(x,z,c.top,4+(int)((noise>>>21)&1),valid);
        if(cacheEnabled)trees.put(key,t);return t;
    }
    private BlockState treeAt(int x,int y,int z){
        int cx=Math.floorDiv(x,20),cz=Math.floorDiv(z,20);
        for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++){
            Tree t=tree(cx+a,cz+b);if(!t.valid)continue;
            int dx=x-t.x,dz=z-t.z,dy=y-t.ground;
            if(dx==0&&dz==0&&dy>=1&&dy<=t.height)return Blocks.OAK_LOG.defaultBlockState();
            int crown=dy-t.height;
            if(crown>=-1&&crown<=2&&Math.abs(dx)<=2&&Math.abs(dz)<=2
                    &&dx*dx+dz*dz+(crown==2?3:0)<=6)
                return Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
        }return null;
    }
    public double surfaceHeight(int x,int z){return surfaceHeight((double)x,(double)z);}
    public double surfaceHeight(double x,double z){
        double r=Math.hypot(x,z);if(r<=500)return 64;
        double boundedX=Math.max(-1280,Math.min(1280,x)),boundedZ=Math.max(-1280,Math.min(1280,z));
        double exact=bilinear(boundedX,boundedZ);
        double distance=Math.max(Math.abs(x),Math.abs(z))-1280;
        if(distance<=0)return exact;
        double analytic=seabed(r,Math.atan2(z,x));
        // Carry the exact boundary correction into the ocean, fading over 128m; no square seam.
        double correction=exact-seabed(Math.hypot(boundedX,boundedZ),Math.atan2(boundedZ,boundedX));
        return analytic+correction*(1-smooth(distance/128));
    }
    private double bilinear(double x,double z){
        double fx=(x+1280)/4,fz=(z+1280)/4;int ix=Math.min(639,(int)Math.floor(fx)),iz=Math.min(639,(int)Math.floor(fz));
        double tx=fx-ix,tz=fz-iz;
        return heights[ix*641+iz]*(1-tx)*(1-tz)+heights[(ix+1)*641+iz]*tx*(1-tz)
                +heights[ix*641+iz+1]*(1-tx)*tz+heights[(ix+1)*641+iz+1]*tx*tz;
    }
    static double coast(double t){return 1080+44*Math.sin(3*t-.6)+24*Math.sin(5*t+1.8)+14*Math.sin(11*t)
            +12*Math.sin(29*t+.5)+7*Math.sin(43*t)+5*Math.sin(79*t+1.3);}
    static double seabed(double radius,double t){
        double d=Math.max(radius-coast(t),0),v=pchip(Math.min(d,10000));
        double channel=.5+.5*Math.sin(19*t+.6*Math.sin(3*t)+d/2100);
        return v-90*Math.pow(channel,8)*smooth(d/700)*(1-smooth((d-6000)/4000))
                +32*Math.sin(d/730+2*t)*Math.sin(5*t)*smooth(d/1200);
    }
    private static double smooth(double t){t=Math.max(0,Math.min(1,t));return t*t*(3-2*t);}
    private static double pchip(double d){
        int i=0;while(i<DIST.length-2 && d>DIST[i+1])i++;
        double h=DIST[i+1]-DIST[i],t=(d-DIST[i])/h;
        return (2*t*t*t-3*t*t+1)*BED[i]+(t*t*t-2*t*t+t)*h*DERIV[i]
                +(-2*t*t*t+3*t*t)*BED[i+1]+(t*t*t-t*t)*h*DERIV[i+1];
    }
    private static double[] pchipDerivatives(){
        int n=DIST.length;double[] h=new double[n-1],m=new double[n-1],out=new double[n];
        for(int i=0;i<n-1;i++){h[i]=DIST[i+1]-DIST[i];m[i]=(BED[i+1]-BED[i])/h[i];}
        for(int i=1;i<n-1;i++){
            if(m[i-1]*m[i]<=0)out[i]=0;
            else {double a=2*h[i]+h[i-1],b=h[i]+2*h[i-1];out[i]=(a+b)/(a/m[i-1]+b/m[i]);}
        }
        out[0]=endpoint(h[0],h[1],m[0],m[1]);out[n-1]=endpoint(h[n-2],h[n-3],m[n-2],m[n-3]);return out;
    }
    private static double endpoint(double h0,double h1,double m0,double m1){
        double v=((2*h0+h1)*m0-h0*m1)/(h0+h1);
        if(Math.signum(v)!=Math.signum(m0))return 0;
        if(Math.signum(m0)!=Math.signum(m1)&&Math.abs(v)>3*Math.abs(m0))return 3*m0;
        return v;
    }
    private static long hash(int x,int z){
        long v=130091L^(x*0x9e3779b97f4a7c15L)^(z*0xc2b2ae3d27d4eb4fL);
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;return v^(v>>>31);
    }
    public JsonObject status(){
        var j=proposal();j.addProperty("maximumSurfaceY",maximum);j.addProperty("minimumKnownY",minimumKnownY());
        j.addProperty("legacyLowerY",legacyLower);j.addProperty("rawHeightBytes",IslandHeightAsset.RAW_BYTES);
        j.addProperty("ownedJoinY",0);j.addProperty("gridQuantizationError",0);
        j.addProperty("cacheEnabled",cacheEnabled);j.addProperty("columnCapacity",columnCapacity);j.addProperty("treeCapacity",treeCapacity);
        j.addProperty("cachedColumns",columns.size());j.addProperty("cachedTrees",trees.size());
        j.addProperty("columnEstimatedBytes",columns.weight());j.addProperty("treeEstimatedBytes",trees.weight());
        j.addProperty("columnEvictions",columns.evictions());j.addProperty("treeEvictions",trees.evictions());
        j.addProperty("columnBuilds",columnBuilds.sum());j.addProperty("treeBuilds",treeBuilds.sum());return j;
    }
}
