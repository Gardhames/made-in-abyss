package dev.madeinabyss.kernel.terrain.living;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.*;
import static dev.madeinabyss.kernel.terrain.living.LivingAbyssFeatureBlock.*;

/** Sections16/19/20: baked canopy -> understory -> cover, bodies share exact surface columns.
 * Runtime performs one immutable cube-index lookup. Water/lake work is deferred to part3b. */
public final class LivingAbyssFloraPlan {
    public record Tree(String owner,String kind,int x,int y,int z,int rotation,String stampDigest,int voxels){}
    private record Site(int x,int y,int z,LivingAbyssGeometry.Surface surface){}
    private final LivingAbyssPlan plan;private final LivingAbyssGeometry geometry;
    private final LivingAbyssPlan.Solid solid,air,allowed;
    private final LivingAbyssVoxelPlan features;private final List<Tree> trees;
    private final int stampCount,surfaceColumns;private final long buildNanos;
    private final Map<String,List<LivingAbyssTreeStamp.Stamp>> library;
    private final LivingAbyssVoxelPlan.Builder index=new LivingAbyssVoxelPlan.Builder();
    // Primitive-keyed lookups (Long.hashCode of a packed x,z is x^z and chains badly); none of these maps is iterated.
    private final Long2IntOpenHashMap canopy=newCanopy();
    private static Long2IntOpenHashMap newCanopy(){var m=new Long2IntOpenHashMap();m.defaultReturnValue(Integer.MIN_VALUE);return m;}
    private final ArrayList<Tree> buildingTrees=new ArrayList<>();
    private final HashMap<String,ArrayList<Tree>> treesByOwner=new HashMap<>();
    private int clippedVoxels,prunedVoxels,ruinSteps,flowerClumps,boulders;
    public LivingAbyssFloraPlan(LivingAbyssPlan plan,LivingAbyssGeometry geometry,LivingAbyssSpirePlan spires,
                               LivingAbyssPlan.Solid solid,LivingAbyssPlan.Solid air,LivingAbyssPlan.Solid allowed){
        long start=System.nanoTime();this.plan=plan;this.geometry=geometry;this.solid=solid;this.air=air;this.allowed=allowed;
        var lib=new LinkedHashMap<String,List<LivingAbyssTreeStamp.Stamp>>();
        for(String kind:List.of("broad_oak","dark_oak","giant","guiding","inverted","birch","crooked","crack")){
            var stamps=new ArrayList<LivingAbyssTreeStamp.Stamp>();for(int variant=0;variant<4;variant++)stamps.add(LivingAbyssTreeStamp.build(kind,hash(kind.hashCode(),variant,1639)));lib.put(kind,List.copyOf(stamps));
        }library=Collections.unmodifiableMap(lib);stampCount=lib.size()*4;
        var sites=new LinkedHashMap<String,List<Site>>();for(var q:plan.ledges())sites.put(q.id(),new ArrayList<>());
        int count=0;
        // One census per XZ, all depths reuse its body/archetype column evaluation.
        // Columns are independent and every probe is thread-safe: evaluate x-slabs in parallel, then append in the
        // original x-major, z-minor order so the per-ledge site lists (and everything seeded from them) are unchanged.
        var slabs=java.util.stream.IntStream.rangeClosed(-760,760).parallel().mapToObj(x->{
            var found=new ArrayList<Site>();
            for(int z=-760;z<=760;z++){
                double rr=(double)x*x+(double)z*z;if(rr<380.0*380||rr>760.0*760)continue;
                for(var s:geometry.surfaces(x,z))if(s.topY()<0&&s.topY()>-1350&&(s.ledge().archetype()!=LivingAbyssPlan.Archetype.OVERHANG||Math.abs(s.zf())<=.22/.47)&&solid.at(x,s.topY(),z)&&air.at(x,s.topY()+1,z)&&allowed.at(x,s.topY()+1,z))
                    found.add(new Site(x,s.topY(),z,s));
            }
            return found;}).toList();
        for(var slab:slabs)for(var site:slab){sites.get(site.surface.ledge().id()).add(site);count++;}
        surfaceColumns=count;
        for(var q:plan.ledges())if(q.archetype()!=null){var cap=sites.get(q.id());if(q.archetype()==LivingAbyssPlan.Archetype.ROCK_GARDEN)rocks(q,cap);
            if(q.archetype()==LivingAbyssPlan.Archetype.RUIN)ruins(q,cap);}
        for(var q:plan.ledges())if(q.archetype()!=null)canopy(q,sites.get(q.id()));
        for(var q:spires.spires()){
            if(q.radius()*2>30)plant(q.id(),"giant",q.x(),q.topY()+1,q.z(),hash(q.x(),q.topY(),q.z()));
            for(var t:q.trees())plant(q.id(),t.birch()?"birch":"broad_oak",t.x(),t.y(),t.z(),hash(t.x(),t.y(),t.z()));
        }
        for(var q:plan.ledges())if(q.archetype()!=null){var cap=sites.get(q.id());underneath(q,cap);cover(q,cap);}
        features=index.freeze();trees=List.copyOf(buildingTrees);buildNanos=System.nanoTime()-start;
        index.clear();
        canopy.clear();buildingTrees.clear();treesByOwner.clear();
    }
    private long hash(long x,long y,long z){return LivingAbyssFields.mix(plan.recipe().seed()^LivingAbyssFields.mix(x)^Long.rotateLeft(LivingAbyssFields.mix(y),21)^Long.rotateLeft(LivingAbyssFields.mix(z),42));}
    private double noise(int x,int z,double cell,long salt){return LivingAbyssPlan.noise(plan.recipe().seed(),cell,x,0,z,salt)+.5;}
    private boolean domain(int x,int y,int z){return y<0&&y>=plan.recipe().domain().knownLowerY()&&(double)x*x+(double)z*z>=380.0*380&&(double)x*x+(double)z*z<960.0*960&&allowed.at(x,y,z);}
    private boolean free(int x,int y,int z){return domain(x,y,z)&&index.at(x,y,z)==0&&air.at(x,y,z);}
    private boolean supported(int x,int y,int z){int b=index.at(x,y,z);return b!=0&&b!=AIR_CUT.ordinal()&&!isPlant(b)||b==0&&solid.at(x,y,z);}
    private void put(int x,int y,int z,LivingAbyssFeatureBlock b){if(domain(x,y,z))index.put(x,y,z,b.ordinal());}
    private void fill(int x,int y,int z,LivingAbyssFeatureBlock b){if(free(x,y,z))put(x,y,z,b);}
    // Prototype cover uses the terrain heightfield. A canopy/boulder is not a new soil surface.
    private int ground(Site s){return s.y;}
    private boolean soil(Site s){int code=index.at(s.x,s.y,s.z);
        if(code!=0)return code==MOSS.ordinal()||code==GRASS.ordinal()||code==DIRT.ordinal()||code==COARSE_DIRT.ordinal()||code==ROOTED_DIRT.ordinal();
        var kind=geometry.sample(s.x,s.y,s.z);return kind==LivingAbyssGeometry.Kind.GRASS||kind==LivingAbyssGeometry.Kind.MOSS||kind==LivingAbyssGeometry.Kind.COARSE_DIRT||kind==LivingAbyssGeometry.Kind.ROOTED_DIRT||kind==LivingAbyssGeometry.Kind.SOIL;
    }
    private void canopy(LivingAbyssPlan.Ledge q,List<Site> cap){
        if(cap.isEmpty()||q.archetype()==LivingAbyssPlan.Archetype.LAKE)return;
        var byXZ=new Long2ObjectOpenHashMap<Site>();var mask=new ArrayList<int[]>();
        for(var s:cap){byXZ.put(key(s.x,s.z),s);if(s.surface.edgeDistance()>4&&s.surface.u()>.08
                &&(q.archetype()!=LivingAbyssPlan.Archetype.GROVE||noise(s.x,s.z,14,2016)<=.62))mask.add(new int[]{s.x,s.y,s.z});}
        long seed=hash(q.id().hashCode(),16,20);
        if(q.id().equals("guiding-tree-ledge")){
            cap.stream().filter(s->s.surface.edgeDistance()>5&&s.surface.edgeDistance()<14&&Math.abs(s.surface.zf())<.2).sorted(Comparator.comparingDouble(s->Math.abs(s.surface.u()-.75))).limit(128)
                .anyMatch(s->plant(q.id(),"guiding",s.x,ground(s)+1,s.z,seed));return;
        }
        if(q.archetype()==LivingAbyssPlan.Archetype.GROVE){
            cap.stream().filter(s->s.surface.edgeDistance()>15&&s.surface.u()>.15).sorted(Comparator.comparingDouble(s->Math.abs(s.surface.u()-.45)+Math.abs(s.surface.zf())))
                .limit(128).anyMatch(s->plant(q.id(),"giant",s.x,ground(s)+1,s.z,seed));
        }
        double radius=switch(q.archetype()){case MEADOW->34;case GROVE->7.5;case TERRACES->99;case ROCK_GARDEN->26;case RUIN->70;case OVERHANG->40;default->34;};
        int limit=q.archetype()==LivingAbyssPlan.Archetype.MEADOW?3:q.archetype()==LivingAbyssPlan.Archetype.TERRACES||q.archetype()==LivingAbyssPlan.Archetype.RUIN||q.archetype()==LivingAbyssPlan.Archetype.OVERHANG?1:10000;
        List<int[]> pts;
        if(q.archetype()==LivingAbyssPlan.Archetype.TERRACES){int highest=mask.stream().mapToInt(p->p[1]).max().orElse(Integer.MIN_VALUE);pts=mask.stream().filter(p->p[1]>=highest-1).limit(64).toList();}
        else pts=LivingAbyssPoisson.place(mask,radius,seed,limit);
        int planted=0;
        for(var p:pts){var s=byXZ.get(key(p[0],p[2]));if(s==null)continue;long h=hash(s.x,s.y,s.z);if(s.surface.edgeDistance()<10&&LivingAbyssWall.unit(h)>.4)continue;
            double pick=LivingAbyssWall.unit(h+1);String kind=switch(q.archetype()){
                case GROVE->pick<.3?"birch":pick<.45?"dark_oak":"broad_oak";
                case ROCK_GARDEN,OVERHANG->"dark_oak";default->"broad_oak";};
            if(plan.fields().zone(s.x,s.y,s.z)==4)kind="dark_oak";
            if(plant(q.id(),kind,s.x,ground(s)+1,s.z,h)&&++planted>=limit)break;
        }
    }
    private boolean plant(String owner,String kind,int x,int y,int z,long seed){
        boolean inverted=kind.equals("inverted"),sideways=kind.equals("crack");if(!domain(x,y,z)||!(inverted||sideways?supported(x,y+1,z):supported(x,y-1,z)))return false;
        // Keep substantial trunks apart, while Poisson governs the normal canopy.
        if(!inverted){var same=treesByOwner.get(owner);if(same!=null)for(int n=0,count=same.size();n<count;n++){var t=same.get(n);double dx=x-t.x,dz=z-t.z;if(Math.sqrt(dx*dx+dz*dz)<(kind.equals("giant")||t.kind.equals("giant")?12:4))return false;}}
        var stamp=library.get(kind).get(Math.floorMod(seed,4));int rotation=(int)Math.floorMod(seed>>>8,4);
        if(kind.equals("guiding")||sideways)rotation=cliffRotation(LivingAbyssFields.azimuth(x,z));
        byte[] data=stamp.voxels();int sx=stamp.sizeX(),sy=stamp.sizeY(),sz=stamp.sizeZ(),root=((-stamp.minY())*sz-stamp.minZ())*sx-stamp.minX();
        int before=0;for(int n=0;n<data.length;n++)if(data[n]!=0){before++;int dx=n%sx+stamp.minX(),dy=n/(sx*sz)+stamp.minY(),dz=n/sx%sz+stamp.minZ();int[] rotated=rotate(dx,dz,rotation);int X=x+rotated[0],Y=y+dy,Z=z+rotated[1],b=Byte.toUnsignedInt(data[n]);
            boolean embed=b==MANGROVE_ROOTS.ordinal()&&dy<=1||inverted&&dy>=0&&dy<=1;
            if(!domain(X,Y,Z)||!embed&&!air.at(X,Y,Z)||index.at(X,Y,Z)!=0&&!isLeaves(index.at(X,Y,Z))){data[n]=0;clippedVoxels++;}
        }
        if(data[root]==0)return false;
        var seen=new BitSet(data.length);int[] queue=new int[data.length];int head=0,tail=1;queue[0]=root;seen.set(root);
        while(head<tail){int n=queue[head++],xx=n%sx,zz=n/sx%sz,yy=n/(sx*sz);
            if(xx>0)tail=admit(data,n-1,seen,queue,tail);if(xx+1<sx)tail=admit(data,n+1,seen,queue,tail);if(zz>0)tail=admit(data,n-sx,seen,queue,tail);if(zz+1<sz)tail=admit(data,n+sx,seen,queue,tail);if(yy>0)tail=admit(data,n-sx*sz,seen,queue,tail);if(yy+1<sy)tail=admit(data,n+sx*sz,seen,queue,tail);}
        if(tail<(sideways?30:300))return false;
        for(int n=0;n<data.length;n++)if(data[n]!=0){if(!seen.get(n)){prunedVoxels++;continue;}int dx=n%sx+stamp.minX(),dy=n/(sx*sz)+stamp.minY(),dz=n/sx%sz+stamp.minZ();int[] rotated=rotate(dx,dz,rotation);int X=x+rotated[0],Y=y+dy,Z=z+rotated[1],b=Byte.toUnsignedInt(data[n]);
            // Face flags must rotate with the stamp.
            if(b>=128){int mask=b-128;for(int i=0;i<rotation;i++)mask=(mask&16)|((mask&1)!=0?8:0)|((mask&8)!=0?2:0)|((mask&2)!=0?4:0)|((mask&4)!=0?1:0);b=128+mask;}
            int existing=index.at(X,Y,Z);if(existing==0||!isLeaves(b)||isLeaves(existing))index.put(X,Y,Z,b);
            if(isLeaves(b)){long ck=key(X,Z);int previous=canopy.get(ck);canopy.put(ck,previous==Integer.MIN_VALUE?Y:Math.max(previous,Y));}
        }
        var tree=new Tree(owner,kind,x,y,z,rotation,stamp.digest(),tail);buildingTrees.add(tree);treesByOwner.computeIfAbsent(owner,k->new ArrayList<>()).add(tree);return true;
    }
    private static int admit(byte[] data,int n,BitSet seen,int[] queue,int tail){if(data[n]!=0&&!seen.get(n)){seen.set(n);queue[tail++]=n;}return tail;}
    public static int cliffRotation(double azimuth){return Math.floorMod((int)Math.floor((LivingAbyssFields.normalize(azimuth)+90)/90+.5),4);}
    private static int[] rotate(int x,int z,int r){return switch(r){case 1->new int[]{-z,x};case 2->new int[]{-x,-z};case 3->new int[]{z,-x};default->new int[]{x,z};};}
    private void rocks(LivingAbyssPlan.Ledge q,List<Site> cap){
        var mask=new ArrayList<int[]>();var byXZ=new Long2ObjectOpenHashMap<Site>();for(var s:cap){byXZ.put(key(s.x,s.z),s);if(s.surface.edgeDistance()>4)mask.add(new int[]{s.x,s.y,s.z});}
        for(var p:LivingAbyssPoisson.place(mask,9,hash(q.id().hashCode(),20,4),10000)){
            var s=byXZ.get(key(p[0],p[2]));long h=hash(s.x,s.y,s.z);double r=2+2.5*LivingAbyssWall.unit(h);blob(s.x,s.y+(int)(r*.65),s.z,r,.9,h);
            if(LivingAbyssWall.unit(h+1)<.5){double a=LivingAbyssWall.unit(h+2)*2*Math.PI;int X=s.x+(int)Math.round(Math.cos(a)*(r*2+1.5)),Z=s.z+(int)Math.round(Math.sin(a)*(r*2+1.5));var other=byXZ.get(key(X,Z));if(other!=null)blob(X,other.y+(int)(r*.3),Z,r*.8,.75,h+3);}
        }
    }
    private void blob(int x,int y,int z,double r,double flat,long seed){int R=(int)Math.ceil(r)+1;boulders++;
        for(int dx=-R;dx<=R;dx++)for(int dz=-R;dz<=R;dz++){double horizontal=(dx*dx+dz*dz)/(r*r);if(horizontal>=1)continue;int low=y-(int)Math.floor(r*flat*Math.sqrt(1-horizontal)),high=y+(int)Math.floor(r*flat*Math.sqrt(1-horizontal));
            for(int Y=low;Y<=high;Y++){if(!domain(x+dx,Y,z+dz))continue;long h=hash(x+dx,Y,z+dz)^seed;var b=Y==high&&LivingAbyssWall.unit(h)<.45?MOSS:switch(Math.floorMod(h,4)){case 0->STONE;case 1->ANDESITE;default->MOSSY_COBBLESTONE;};put(x+dx,Y,z+dz,b);}}
    }
    private void cover(LivingAbyssPlan.Ledge q,List<Site> cap){
        if(q.archetype()==LivingAbyssPlan.Archetype.LAKE)return;
        var flowerCenters=LivingAbyssPoisson.place(cap.stream().filter(s->s.surface.edgeDistance()>3).map(s->new int[]{s.x,s.y,s.z}).toList(),7,hash(q.id().hashCode(),16,1),10000);
        var flowers=new Long2ObjectOpenHashMap<LivingAbyssFeatureBlock>();double share=switch(q.archetype()){case MEADOW->.15;case TERRACES->.10;case GROVE->.03;default->.04;};
        double[] side=plan.fields().sideWeights(q.theta());
        for(var center:flowerCenters){long h=hash(center[0],center[1],center[2]);if(LivingAbyssWall.unit(h)>share*3)continue;flowerClumps++;double pick=LivingAbyssWall.unit(h+1)*100;
            var species=pick<30?OXEYE_DAISY:pick<55?AZURE_BLUET:pick<70?side[2]>.5?DANDELION:LILY_OF_THE_VALLEY:pick<90?CORNFLOWER:side[0]>.5?BLUE_ORCHID:OXEYE_DAISY;
            int n=3+(int)(LivingAbyssWall.unit(h+2)*7);double radius=2+2*LivingAbyssWall.unit(h+3);
            for(int i=0;i<n;i++){long salt=LivingAbyssFields.mix(h+i*8191);double a=LivingAbyssWall.unit(salt)*2*Math.PI,d=radius*Math.sqrt(LivingAbyssWall.unit(salt+1));flowers.put(key(center[0]+(int)Math.round(Math.cos(a)*d),center[2]+(int)Math.round(Math.sin(a)*d)),species);}
        }
        for(var s:cap){int y=ground(s)+1;if(!soil(s)||!free(s.x,y,s.z)||!supported(s.x,y-1,s.z))continue;long h=hash(s.x,y,s.z);double r=LivingAbyssWall.unit(h);boolean shade=false;
            for(int dx=-3;dx<=3;dx++)if(canopy.get(key(s.x+dx,s.z+dx))>y+2){shade=true;break;}
            if(s.surface.edgeDistance()<3){if(r<.35/3){fill(s.x,y,s.z,r<.07?MOSSY_COBBLESTONE:MOSS);continue;}if(r>.5&&r<.62){bush(s.x,y,s.z,h,side[2]>.5);continue;}}
            if(shade){if(r<.08)bush(s.x,y,s.z,h,side[2]>.5);else if(r<.20){if(free(s.x,y+1,s.z)&&r<.14){put(s.x,y,s.z,LARGE_FERN_LOWER);put(s.x,y+1,s.z,LARGE_FERN_UPPER);}else put(s.x,y,s.z,FERN);}
                else if(r<.35){put(s.x,y-1,s.z,MOSS);if(r<.28)fill(s.x,y,s.z,MOSS_CARPET);}continue;}
            var flower=flowers.get(key(s.x,s.z));if(flower!=null)fill(s.x,y,s.z,flower);
            else if(r<.55){if(r<.08&&free(s.x,y+1,s.z)){put(s.x,y,s.z,TALL_GRASS_LOWER);put(s.x,y+1,s.z,TALL_GRASS_UPPER);}else fill(s.x,y,s.z,r<.14?FERN:SHORT_GRASS);}
        }
        if(q.y()<-150&&q.y()>-450&&(side[0]>.5||side[3]>.5)){
            int n=cap.size()/2000;for(int i=0;i<n;i++){var s=cap.get(Math.floorMod(hash(q.id().hashCode(),i,1605),cap.size()));if(canopy.get(key(s.x,s.z))>s.y+5)mushroom(s);}
        }
    }
    private void bush(int x,int y,int z,long seed,boolean sunny){
        var b=sunny&&LivingAbyssWall.unit(seed)<.05?FLOWERING_AZALEA_LEAVES:LivingAbyssWall.unit(seed+1)<.5?AZALEA_LEAVES:OAK_LEAVES;
        for(int dy=0;dy<2;dy++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx*dx+dz*dz+dy*2<=2&&(dx==0&&dz==0||supported(x+dx,y-1,z+dz)))fill(x+dx,y+dy,z+dz,b);
    }
    private void mushroom(Site s){int y=ground(s)+1;long h=hash(s.x,y,s.z);int height=4+(int)(LivingAbyssWall.unit(h)*3);for(int d=0;d<height;d++)if(!free(s.x,y+d,s.z))return;
        for(int d=0;d<height;d++)fill(s.x,y+d,s.z,MUSHROOM_STEM);var block=LivingAbyssWall.unit(h+1)<.5?BROWN_MUSHROOM_BLOCK:RED_MUSHROOM_BLOCK;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)if(dx*dx+dz*dz<=6)fill(s.x+dx,y+height-1,s.z+dz,block);
    }
    private void underneath(LivingAbyssPlan.Ledge q,List<Site> cap){
        double[] side=plan.fields().sideWeights(q.theta());double rootChance=.02+.05*side[3]+.015*side[0]-.015*side[2],dripChance=.02+.08*side[1];
        var roots=new ArrayList<Site>();
        for(var s:cap){int y=s.surface.bottomY();if(!supported(s.x,y,s.z)||!free(s.x,y-1,s.z))continue;long h=hash(s.x,y,s.z);double r=LivingAbyssWall.unit(h);
            if(r<.25&&side[1]>.4)put(s.x,y,s.z,MOSS);
            double curtain=noise(s.x,s.z,5,1904)>.55?2.2:.25;
            if(LivingAbyssWall.unit(h+1)<rootChance*(.4+s.surface.u())*curtain){int length=(int)(2+4*LivingAbyssWall.unit(h+2)+s.surface.u()*(3+13*LivingAbyssWall.unit(h+3)));
                for(int d=1;d<=length&&free(s.x,y-d,s.z);d++)put(s.x,y-d,s.z,HANGING_ROOTS);}
            else if(s.surface.u()>.2&&s.surface.u()<.9&&LivingAbyssWall.unit(h+4)<dripChance){int length=1+(int)(LivingAbyssWall.unit(h+5)*4);for(int d=1;d<=length&&free(s.x,y-d,s.z);d++)put(s.x,y-d,s.z,d==length?DRIP_TIP:d==length-1?DRIP_FRUSTUM:d==1?DRIP_BASE:DRIP_MIDDLE);}
            if(q.y()<-1150&&s.surface.edgeDistance()>12&&s.surface.u()>.2&&s.surface.u()<.8)roots.add(s);
            if(s.surface.u()>.92&&r<.18)edgeVine(s,h,3,16);
        }
        if(q.y()<-1150&&!roots.isEmpty()){
            for(var p:LivingAbyssPoisson.place(roots.stream().map(s->new int[]{s.x,s.surface.bottomY()-1,s.z}).toList(),20,hash(q.id().hashCode(),16,7),10000))plant(q.id(),"inverted",p[0],p[1],p[2],hash(p[0],p[1],p[2]));
        }
        // Sideways crack trees use the prototype's down-then-up arc and a single cushion.
        int n=1+(int)(LivingAbyssWall.unit(hash(q.id().hashCode(),19,4))*3);
        for(int i=0;i<n&&!cap.isEmpty();i++){var s=cap.get(Math.floorMod(hash(q.id().hashCode(),i,1905),cap.size()));plant(q.id(),"crack",s.x,s.surface.bottomY()-1,s.z,hash(s.x,s.y+i,s.z));}
    }
    private void edgeVine(Site s,long seed,int lo,int hi){
        for(int[] d:new int[][]{{1,0,4},{-1,0,8},{0,1,1},{0,-1,2}}){int x=s.x+d[0],z=s.z+d[1];if(!free(x,s.y,z))continue;int len=lo+(int)(LivingAbyssWall.unit(seed+1)*(hi-lo));
            for(int y=s.y;y>s.y-len&&free(x,y,z);y--)index.put(x,y,z,128+d[2]);return;}
    }
    private void crackTree(Site s,long seed){
        int y=s.surface.bottomY()-1;if(!free(s.x,y,s.z)||!supported(s.x,y+1,s.z))return;double a=Math.toRadians(LivingAbyssFields.azimuth(s.x,s.z));
        double px=s.x,py=y,pz=s.z,vertical=-.35;int length=4+(int)(LivingAbyssWall.unit(seed)*5);int[] last={s.x,y,s.z};
        for(int i=0;i<length;i++){double n=Math.sqrt(.64+vertical*vertical);px-=Math.sin(a)*.8/n;pz+=Math.cos(a)*.8/n;py+=vertical/n;vertical+=.18;int[] next={(int)Math.floor(px+.5),(int)Math.floor(py+.5),(int)Math.floor(pz+.5)};
            for(int axis=0;axis<3;axis++)while(last[axis]!=next[axis]){last[axis]+=Integer.signum(next[axis]-last[axis]);if(!domain(last[0],last[1],last[2])||!air.at(last[0],last[1],last[2]))return;put(last[0],last[1],last[2],OAK_LOG);}
        }
        int cx=last[0],cy=last[1],cz=last[2];double radius=2.2+1.2*LivingAbyssWall.unit(seed+1);
        for(int dy=-2;dy<=3;dy++)for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)if(dx*dx+dz*dz+dy*dy*2.25<radius*radius)fill(cx+dx,cy+dy,cz+dz,OAK_LEAVES);
    }
    private void ruins(LivingAbyssPlan.Ledge q,List<Site> cap){
        if(cap.isEmpty())return;double a=Math.toRadians(q.theta()),outer=geometry.wallBoundaryAt(q.theta(),q.y());
        var byXZ=new Long2ObjectOpenHashMap<Site>();for(var s:cap)byXZ.put(key(s.x,s.z),s);
        int length=Math.max(8,(int)(q.projection()*.62));var path=new ArrayList<Site>();
        var seenPath=new LongOpenHashSet();
        for(int d=length;d>2;d--){double along=3*Math.sin((length-d)/(double)Math.max(1,length-3)*2.2+plan.recipe().seed());int x=(int)Math.round((outer-d)*Math.sin(a)+along*Math.cos(a)),z=(int)Math.round(-(outer-d)*Math.cos(a)+along*Math.sin(a));var s=byXZ.get(key(x,z));if(s!=null&&seenPath.add(key(x,z)))path.add(s);}
        double[] profile=new double[path.size()];for(int i=0;i<path.size();i++)profile[i]=path.get(i).y;var steps=LivingAbyssRuinStair.build(profile);
        var rubble=new ArrayList<Site>();
        for(int i=0;i<path.size();i++){var s=path.get(i);int step=steps[i];double decay=LivingAbyssRuinStair.decay(i/(double)Math.max(1,path.size()-1));ruinSteps++;
            for(int width=-4;width<=4;width++){int x=s.x+(int)Math.round(Math.cos(a)*width),z=s.z+(int)Math.round(Math.sin(a)*width);var ground=byXZ.get(key(x,z));if(ground==null)continue;long h=hash(x,step,z);double r=LivingAbyssWall.unit(h);
                if(Math.abs(width)<=3)for(int y=step+1;y<=ground.y;y++)put(x,y,z,AIR_CUT);
                if(Math.abs(width)==4){if(ground.y>step+1&&r>.2+.5*decay)for(int y=step+1;y<=Math.min(ground.y,step+3);y++)put(x,y,z,r<.35?MOSSY_STONE_BRICKS:STONE_BRICKS);continue;}
                boolean body=r>.55*decay;
                if(body)for(int y=Math.min(ground.y,step)-2;y<step;y++)put(x,y,z,r<.3*decay?COBBLESTONE:STONE_BRICKS);
                if(!body&&step>ground.y){put(x,step,z,AIR_CUT);rubble.add(ground);continue;}
                if(Math.abs(width)==3){put(x,step,z,STONE_BRICKS);if(r>.25+.6*decay)fill(x,step+1,z,STONE_BRICKS);continue;}
                if(r<.10+.5*decay||decay>.45&&LivingAbyssWall.unit(h+1)<.7*decay){put(x,step,z,r<.3?MOSS:GRASS);rubble.add(ground);}
                else put(x,step,z,r<.18+.2*decay?MOSSY_STONE_BRICKS:r<.30+.25*decay?CRACKED_STONE_BRICKS:STONE_BRICKS);
            }
        }
        for(var s:rubble){long h=hash(s.x,s.y,s.z);int distance=1+(int)(LivingAbyssWall.unit(h)*5),x=s.x-(int)Math.round(Math.sin(a)*distance),z=s.z+(int)Math.round(Math.cos(a)*distance);var g=byXZ.get(key(x,z));if(g!=null){put(x,g.y,z,GRAVEL);fill(x,g.y+1,z,LivingAbyssWall.unit(h+1)<.5?CRACKED_STONE_BRICKS:MOSSY_COBBLESTONE);}}
        for(var s:cap)if(s.surface.u()<.15&&Math.abs(s.surface.zf())<.08){long h=hash(s.x,s.y,s.z);if(LivingAbyssWall.unit(h)>.2)put(s.x,s.y,s.z,switch(Math.floorMod(h,5)){case 0->STONE_BRICKS;case 1->MOSSY_STONE_BRICKS;case 2->CRACKED_STONE_BRICKS;case 3->MOSS;default->GRASS;});}
        for(int i=0;i<40;i++){var s=cap.get(Math.floorMod(hash(q.id().hashCode(),i,2060),cap.size()));long h=hash(s.x,s.y,s.z);if(s.surface.u()<.8){var b=LivingAbyssWall.unit(h)<.5?CRACKED_STONE_BRICKS:MOSSY_STONE_BRICKS;put(s.x,s.y+(LivingAbyssWall.unit(h+1)<.5?0:1),s.z,b);}}
        for(int i=0;i<4;i++){int d=i<2?10:5,w=(i%2==0?-5:5),x=(int)Math.round((outer-d)*Math.sin(a)+w*Math.cos(a)),z=(int)Math.round(-(outer-d)*Math.cos(a)+w*Math.sin(a));var s=byXZ.get(key(x,z));if(s!=null){long h=hash(x,s.y,z);int height=1+(int)(LivingAbyssWall.unit(h)*5);for(int y=s.y+1;y<s.y+height;y++)fill(x,y,z,STONE_BRICKS);fill(x,s.y+height,z,CHISELED_STONE_BRICKS);}}
        for(int i=0;i<5;i++){var s=cap.get(Math.floorMod(hash(q.id().hashCode(),i,2065),cap.size()));long h=hash(s.x,s.y,s.z);int height=2+(int)(LivingAbyssWall.unit(h)*4),lengthWall=7+(int)(LivingAbyssWall.unit(h+1)*9);
            for(int k=0;k<lengthWall;k++){var at=byXZ.get(key(s.x+k,s.z));if(at==null)continue;for(int y=at.y+1;y<=at.y+height-(k%5==0?2:0);y++)fill(at.x,y,at.z,LivingAbyssWall.unit(h+k)<.4?MOSSY_STONE_BRICKS:CRACKED_STONE_BRICKS);}}
        // The exact framed doorway is cut into a supported wall, above the last stair.
        if(!path.isEmpty()){int top=steps[steps.length-1]+1,x=(int)Math.round(outer*Math.sin(a)),z=(int)Math.round(-outer*Math.cos(a));
            for(int w=-3;w<=3;w++)for(int y=top+1;y<=top+9;y++)for(int depth=0;depth<3;depth++){
                int X=x+(int)Math.round(Math.cos(a)*w+Math.sin(a)*depth),Z=z+(int)Math.round(Math.sin(a)*w-Math.cos(a)*depth);boolean frame=Math.abs(w)==3||y==top+9;
                if(supported(X,y,Z))put(X,y,Z,frame&&LivingAbyssWall.unit(hash(X,y,Z))>.2?CHISELED_STONE_BRICKS:AIR_CUT);
            }
        }
    }
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    public int at(int x,int y,int z){return features.at(x,y,z);}public LivingAbyssVoxelPlan features(){return features;}
    public List<Tree> trees(){return trees;}public int stampCount(){return stampCount;}public int surfaceColumns(){return surfaceColumns;}public long buildNanos(){return buildNanos;}
    public Map<String,Object> statistics(){var out=new LinkedHashMap<String,Object>();out.put("treeStampCount",stampCount);out.put("treeCount",trees.size());out.put("surfaceColumns",surfaceColumns);out.put("featureCubes",features.cubeCount());out.put("featureVoxels",features.voxelCount());out.put("featureBytes",features.estimatedBytes());out.put("floraBuildMillis",buildNanos/1e6);out.put("clippedStampVoxels",clippedVoxels);out.put("prunedStampVoxels",prunedVoxels);out.put("ruinSteps",ruinSteps);out.put("flowerClumps",flowerClumps);out.put("boulders",boulders);
        var kinds=new TreeMap<String,Long>();for(var t:trees)kinds.merge(t.kind,1L,Long::sum);out.put("treeKinds",kinds);out.put("featureDigest",features.digest());return Collections.unmodifiableMap(out);}
}
