package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.living.*;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Separate v15 branch. Opening precedes this composition; old recipes never enter it. */
public final class LivingAbyssGeneration {
    private final ParameterizedTerrainChunkGenerator parent;
    private final LivingAbyssRecipe recipe;
    private final D8Hydrology.Plan hydrology;
    private final LivingAbyssPlan plan;
    private final LivingAbyssGeometry geometry;
    private final LivingAbyssSpirePlan spires;
    private final LivingAbyssGreenPlan green;
    private final LivingAbyssFloraPlan flora;
    private final LivingAbyssWaterPlan water;
    private final LivingAbyssCaveMouths mouths;
    private final Map<String,BlockState> rocks;
    private final LivingAbyssAirCertificate airCertificate;
    private final LivingAbyssRevision155 revision155;
    private final CompiledTerrainSampler legacyBare;
    private final long airCertificateBuildNanos;
    private final boolean airCertificateBypassed=Boolean.getBoolean("mia.v15.airCertificate.bypass");
    private final boolean cubePredicatesBypassed=Boolean.getBoolean("mia.v15.cubePredicates.bypass");
    private record Support(int x,int y,int z,boolean value){}
    private final ThreadLocal<Support[]> wallSupports=ThreadLocal.withInitial(()->new Support[16384]);
    private static final class CubeScope {
        int x,y,z;boolean active;LivingAbyssCubeEnvelope.Kind kind;
        final short[] reserved=new short[256],protectedVoxels=new short[256];
        final LivingAbyssCubePredicates predicates=new LivingAbyssCubePredicates();
        LivingAbyssVoxelPlan.CubeView water,flora;
        boolean contains(int x,int y,int z){return active&&(long)x-this.x>=0&&(long)x-this.x<16&&(long)y-this.y>=0&&(long)y-this.y<16&&(long)z-this.z>=0&&(long)z-this.z<16;}
    }
    private final ThreadLocal<CubeScope> cubeScope=ThreadLocal.withInitial(CubeScope::new);
    private final LivingAbyssReservations reservations,protectedReservations;
    private final dev.madeinabyss.kernel.terrain.natural.NaturalProtection protection;
    public LivingAbyssGeneration(JsonObject json,ParameterizedTerrainChunkGenerator parent,
                                dev.madeinabyss.kernel.terrain.natural.NaturalProtection protection,
                                List<dev.madeinabyss.kernel.terrain.natural.NaturalProtection.Box> protectedBoxes) {
        if(!LivingAbyssAlgorithmCompatibility.admitted(json,algorithmSha256()))throw new IllegalArgumentException("Explicit current or audited legacy v15 algorithm SHA required");
        if(!json.has("demSha256")||!json.get("demSha256").getAsString().equals(IslandSurfaceGeneration.DATA_SHA))throw new IllegalArgumentException("v15 requires the exact approved DEM SHA");
        this.parent=Objects.requireNonNull(parent);this.protection=protection;recipe=new Gson().fromJson(json,LivingAbyssRecipe.class);
        if(parent.islandSurface()==null||parent.upperShaftOpening()==null)throw new IllegalArgumentException("v15 requires approved v14 island/opening parent");
        var p=parent.parameters();var f=recipe.fields();
        double[] dem=IslandHeightAsset.load(IslandSurfaceGeneration.DATA_SHA,IslandSurfaceGeneration.COMPRESSED_SHA);
        hydrology=D8Hydrology.plan(dem,641,641,-1280,-1280,4,p.cavity().mouthRadius(),f.fallbackWetDegrees());
        var fields=new LivingAbyssFields(recipe.seed(),hydrology.wetDegrees(),f.zoneBoundaries(),f.zoneNoiseMetres(),f.sidePlateauDegrees(),f.sideTransitionDegrees());
        var c=p.cavity();var without=new CompiledTerrainSampler(new TerrainParameters(p.seed(),p.surface(),new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),c.declaredLowerBoundY(),c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),p.noise()));
        legacyBare=without;
        protectedReservations=new LivingAbyssReservations(protectedBoxes);
        reservations=new LivingAbyssReservations(reservationBoxes(parent,protectedBoxes,without,recipe.wall()==null?0:new LivingAbyssWall(recipe.seed(),c.lowerRadius(),recipe.wall()).maximumProtrusion()));
        plan=new LivingAbyssPlan(p,recipe,fields,(x,y,z)->{
            var sample=parent.sampleGeometry(x,y,z);return sample instanceof TerrainSample.Known k&&k.material()!=TerrainMaterial.AIR&&k.material()!=TerrainMaterial.WATER;
        },parent.upperShaftOpening()::openingRadius,without::cavityRadius);
        revision155=plan.revision155();
        if(revision155!=null){
            geometry=new LivingAbyssGeometry(p,plan);spires=null;green=null;flora=null;water=null;
            if(revision155.stageB()){
                var portals=new ArrayList<int[]>();if(parent.caveExpansion()!=null)for(var portal:parent.caveExpansion().caves().portals()){
                    var q=portal.position();portals.add(new int[]{(int)Math.round(q.x()),(int)Math.round(q.y()),(int)Math.round(q.z())});}
                mouths=new LivingAbyssCaveMouths(recipe.seed(),portals,(LivingAbyssCaveMouths.PortalFace)this::mouthSurface155B);
            }else mouths=null;
            rocks=rocks155();
            if(revision155.stageB())revision155.finishFloorSupport(
                (x,y,z)->y>=knownLowerY(x,z)&&sample155(x,y,z,false).isFaceSturdy(EMPTY,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.UP),
                (x,y,z)->!protectedAt(x,y,z),(x,y,z)->y>=knownLowerY(x,z)&&sample155(x,y,z,false).isAir());
            airCertificate=null;airCertificateBuildNanos=0;return;
        }
        var base=new LivingAbyssGeometry(p,plan);
        spires=new LivingAbyssSpirePlan(p,plan,base,(x,y,z)->{
            if(protectedAt(x,y,z))return false;
            if(y<p.cavity().declaredLowerBoundY())return true;
            if(caveCut(base,x,y,z))return false;
            if(Math.sqrt((double)x*x+(double)z*z)<base.wallRadius(x,y,z))return true;
            return parent.sampleBlock(x,y,z).isFaceSturdy(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.UP);
        },(x,y,z)->protectedAt(x,y,z)?LivingAbyssSpirePlan.Rejection.PATH:caveCut(base,x,y,z)?LivingAbyssSpirePlan.Rejection.PORTAL:LivingAbyssSpirePlan.Rejection.SUPPORT);
        geometry=new LivingAbyssGeometry(p,plan,spires);
        // Section24: natural cave mouths from every v14 portal to the actual v15 face.
        var portalList=new ArrayList<int[]>();var expansion=parent.caveExpansion();
        if(recipe.part3b()&&expansion!=null)for(var portal:expansion.caves().portals()){var q=portal.position();
            portalList.add(new int[]{(int)Math.round(q.x()),(int)Math.round(q.y()),(int)Math.round(q.z())});}
        mouths=recipe.part3b()?new LivingAbyssCaveMouths(recipe.seed(),portalList,(theta,yy)->{
            double deg=Math.toDegrees(theta),start=geometry.wallBoundaryAt(deg,yy)+4;
            for(double r=start;r>390;r-=1){int fx=(int)Math.round(r*Math.sin(theta)),fz=(int)Math.round(-r*Math.cos(theta));if(!geometry.solid(fx,yy,fz))return r;}
            return Double.NaN;}):null;
        var map=new LinkedHashMap<String,BlockState>();
        for(var b:List.of(Blocks.STONE,Blocks.ANDESITE,Blocks.CALCITE,Blocks.DRIPSTONE_BLOCK,Blocks.WHITE_TERRACOTTA,Blocks.TUFF,Blocks.MOSSY_COBBLESTONE,Blocks.MOSS_BLOCK,Blocks.DEEPSLATE))
            map.put(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath(),b.defaultBlockState());
        rocks=Map.copyOf(map);
        if(recipe.wall()!=null)for(var palette:recipe.wall().palettes())for(var name:palette.keySet())if(!rocks.containsKey(name))throw new IllegalArgumentException("Unknown approved rock "+name);
        // Section17 water before flora: complete basins/channels are admitted first, plants avoid them.
        water=recipe.part3b()?new LivingAbyssWaterPlan(plan,geometry,(x,y,z)->!reservedAt(x,y,z),
            (x,y,z)->baseBlock(x,y,z,false).isFaceSturdy(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.UP)):null;
        flora=recipe.part3a()?new LivingAbyssFloraPlan(plan,geometry,spires,
            (x,y,z)->baseBlock(x,y,z,false).isFaceSturdy(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.UP),
            (x,y,z)->baseBlock(x,y,z,false).isAir(),(x,y,z)->!reservedAt(x,y,z)&&!waterNear(x,y,z)):null;
        green=new LivingAbyssGreenPlan(plan,geometry,spires,(x,y,z)->baseWithFeatures(x,y,z).isFaceSturdy(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.UP),
            (x,y,z)->!protectedAt(x,y,z)&&(flora==null||flora.at(x,y,z)==0));
        // Final outputs are frozen: waterNear and vine adjacency affected admission,
        // not read-time growth. Only same-XZ y-1 moss support crosses an output edge.
        long certificateStart=System.nanoTime();var dirty=new HashSet<dev.madeinabyss.kernel.cube.CubeKey>();
        addReservationFootprint(dirty,reservations);addReservationFootprint(dirty,protectedReservations);
        if(water!=null)dirty.addAll(LivingAbyssAirCertificate.supportingFeatureCubes(water.voxels()));
        if(flora!=null)dirty.addAll(LivingAbyssAirCertificate.supportingFeatureCubes(flora.features()));
        for(var vine:green.vines())for(int cy=(vine.topY()-vine.masks().size()+1)>>4;cy<=vine.topY()>>4;cy++)
            dirty.add(new dev.madeinabyss.kernel.cube.CubeKey(vine.x()>>4,cy,vine.z()>>4));
        boolean approvedParent=parent.livingAbyss()==null;
        airCertificate=LivingAbyssAirCertificate.exact(p,geometry,dirty,approvedParent,approvedParent?this::parentSkyFirstAirY:null);
        airCertificateBuildNanos=System.nanoTime()-certificateStart;
    }
    private static void addReservationFootprint(Set<dev.madeinabyss.kernel.cube.CubeKey> dirty,LivingAbyssReservations reservations){
        dirty.addAll(reservations.cubeKeys());var mask=new short[256];
        for(var key:reservations.cubeKeys())if(key.y()<(Integer.MAX_VALUE>>4)){
            reservations.fillCubeMask(key.x()*16,key.y()*16,key.z()*16,mask);
            for(short column:mask)if((column&0x8000)!=0){dirty.add(new dev.madeinabyss.kernel.cube.CubeKey(key.x(),key.y()+1,key.z()));break;}
        }
    }
    /** At Y>=64 and R>=500, public parent sampling gives the island final
     * ownership after all trails/caves/Alex ecology/opening. Its own plant guard
     * is y<=floor(surfaceHeight)+8, even for neighboring tree crowns. The inner
     * parent has an opaque composed callback and therefore remains unproved. */
    private int parentSkyFirstAirY(int cx,int cz){
        int sx=cx*16,sz=cz*16,nx=sx>0?sx:sx+15<0?sx+15:0,nz=sz>0?sz:sz+15<0?sz+15:0;
        if((double)nx*nx+(double)nz*nz<250000)return Integer.MAX_VALUE;
        int maximum=63;
        for(int z=sz;z<sz+16;z++)for(int x=sx;x<sx+16;x++)maximum=Math.max(maximum,(int)Math.floor(parent.islandSurface().surfaceHeight(x,z))+8);
        return maximum+1;
    }
    private static List<dev.madeinabyss.kernel.terrain.natural.NaturalProtection.Box> reservationBoxes(ParameterizedTerrainChunkGenerator parent,
            List<dev.madeinabyss.kernel.terrain.natural.NaturalProtection.Box> protectedBoxes,CompiledTerrainSampler wall,double hmax){
        var boxes=new ArrayList<>(protectedBoxes);var expansion=parent.caveExpansion();if(expansion==null)return boxes;
        var caves=expansion.caves();
        for(var e:caves.segments()){double pad=e.radius()+2;var a=e.a();var b=e.b();boxes.add(box(Math.min(a.x(),b.x())-pad,Math.min(a.y(),b.y())-pad,Math.min(a.z(),b.z())-pad,
            Math.max(a.x(),b.x())+pad,Math.max(a.y(),b.y())+pad,Math.max(a.z(),b.z())+pad));}
        for(var route:caves.routes())for(var f:route.feet())boxes.add(box(f.x()-1,f.y()-12,f.z()-1,f.x()+1,f.y()+3,f.z()+1));
        // Mouths may lie on a v14 shelf far inside the bare wall. Reserve the entire
        // radial corridor to the new surface, with a tangent and vertical halo.
        for(var portal:caves.portals()){var q=portal.position();double pr=Math.hypot(q.x(),q.z()),theta=LivingAbyssFields.azimuth(q.x(),q.z()),a=Math.toRadians(theta);
            double raw=wall.cavityRadius((int)Math.round(q.x()),(int)Math.round(q.y()),(int)Math.round(q.z()));
            double inner=Math.max(380,Math.min(pr,raw-hmax)-10),outer=Math.max(pr,raw+8)+10,delta=Math.asin(Math.min(1,12/inner));
            double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
            for(double angle:new double[]{a-delta,a,a+delta})for(double r:new double[]{inner,outer}){double x=r*Math.sin(angle),z=-r*Math.cos(angle);minX=Math.min(minX,x);maxX=Math.max(maxX,x);minZ=Math.min(minZ,z);maxZ=Math.max(maxZ,z);}
            boxes.add(box(minX,q.y()-12,minZ,maxX,q.y()+12,maxZ));
        }return boxes;
    }
    private static dev.madeinabyss.kernel.terrain.natural.NaturalProtection.Box box(double x,double y,double z,double xx,double yy,double zz){
        return new dev.madeinabyss.kernel.terrain.natural.NaturalProtection.Box((long)Math.floor(x),(long)Math.floor(y),(long)Math.floor(z),(long)Math.ceil(xx),(long)Math.ceil(yy),(long)Math.ceil(zz));
    }
    public static JsonObject recipe() {
        try(var in=LivingAbyssGeneration.class.getResourceAsStream("/assets/madeinabyss/terrain/living-abyss-v15.recipe.json")) {
            var j=JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            j.addProperty("algorithmSha256",algorithmSha256());j.addProperty("demSha256",IslandSurfaceGeneration.DATA_SHA);return j;
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    /** Explicit Stage A factory: legacy test/reference profiles keep their v15.4 recipe. */
    public static JsonObject recipe155(){var j=recipe();j.addProperty("revision",LivingAbyssRevision155.VERSION);
        j.addProperty("algorithmSha256",LivingAbyssAlgorithmCompatibility.STAGE_A);
        j.addProperty("algorithmVersion",LivingAbyssRevision155.VERSION);j.addProperty("stage","A");
        j.addProperty("strataContract","section24.1-whole-rock-column");
        var strata=new JsonObject();strata.add("columnBounds",new Gson().toJsonTree(new int[]{-120,1480}));strata.add("thickness",new Gson().toJsonTree(new int[]{5,26}));
        strata.add("thinThickness",new Gson().toJsonTree(new int[]{2,5}));strata.addProperty("thickProbability",.8);strata.addProperty("secondaryShare",.25);strata.addProperty("faciesReplacementProbability",.55);
        j.add("strata",strata);return j;}
    private static Map<String,BlockState> rocks155(){var result=new LinkedHashMap<String,BlockState>();
        for(var b:List.of(Blocks.STONE,Blocks.ANDESITE,Blocks.CALCITE,Blocks.WHITE_TERRACOTTA,Blocks.DIORITE,Blocks.TERRACOTTA,
            Blocks.YELLOW_TERRACOTTA,Blocks.TUFF,Blocks.DRIPSTONE_BLOCK,Blocks.GRANITE,Blocks.DEEPSLATE,Blocks.COBBLED_DEEPSLATE,Blocks.BLACKSTONE))
            result.put(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath(),b.defaultBlockState());return Map.copyOf(result);}
    public static String algorithmSha256(){
        // Include v15 routing/census adapters and javac's anonymous enum switch maps.
        // This closure is separate from every historical profile's fixed SHA formula.
        try {
            var types=new TreeMap<String,Class<?>>();
            for(var root:List.of(LivingAbyssRecipe.class,LivingAbyssFields.class,D8Hydrology.class,LivingAbyssPlan.class,
                    LivingAbyssRevision155.class,LivingAbyssStrata155.class,LivingAbyssStrata155B.class,LivingAbyssMouthShape155B.class,LivingAbyssCaveMouths.class,LivingAbyssMath155.class,
                    LivingAbyssFloor155B.class,
                    LivingAbyssGeometry.class,LivingAbyssLedgeBody.class,LivingAbyssFeatureBlock.class,LivingAbyssBlockStates.class,LivingAbyssTreeStamp.class,LivingAbyssVoxelPlan.class,LivingAbyssPoisson.class,LivingAbyssRuinStair.class,LivingAbyssFloraPlan.class,
                    LivingAbyssSurfaceMaterials.class,LivingAbyssWaterShape.class,LivingAbyssSpireShape.class,
                    LivingAbyssWall.class,LivingAbyssWallRadius.class,LivingAbyssAirCertificate.class,LivingAbyssCubeEnvelope.class,LivingAbyssReservations.class,LivingAbyssSpirePlan.class,LivingAbyssGreenPlan.class,LivingAbyssDigest.class,
                    LivingAbyssGeneration.class,LivingAbyssCubePredicates.class,LivingAbyssColors.class,ParameterizedTerrainChunkGenerator.class,GeneratedPreviewProfile.class,IslandHeightAsset.class))collect(root,types);
            var bytes=new TreeMap<String,byte[]>();
            for(var e:types.entrySet()) {
                try(var in=e.getValue().getResourceAsStream("/"+e.getKey())){bytes.put(e.getKey(),Objects.requireNonNull(in).readAllBytes());}
                for(int i=1;i<=32;i++){String path=e.getKey().replace(".class","$"+i+".class");try(var in=e.getValue().getResourceAsStream("/"+path)){if(in!=null)bytes.put(path,in.readAllBytes());}}
            }
            var digest=java.security.MessageDigest.getInstance("SHA-256");var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest));
            for(var e:bytes.entrySet()){out.writeUTF(e.getKey());out.writeInt(e.getValue().length);out.write(e.getValue());}out.flush();return HexFormat.of().formatHex(digest.digest());
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException("Cannot identify v15 code closure",e);}
    }
    private static void collect(Class<?> type,Map<String,Class<?>> out){out.put(type.getName().replace('.','/')+".class",type);for(var child:type.getDeclaredClasses())collect(child,out);}
    /** Certified AIR is exact; other prepared bounds remain hints for the old admission path. */
    public boolean prepareCube(int x,int y,int z){
        var scope=cubeScope.get();scope.active=false;scope.predicates.end();scope.water=null;scope.flora=null;
        if(revision155!=null)return revision155.uniformCentreAir(x,y,z);
        if(((x|y|z)&15)==0&&uniformAirCube(x>>4,y>>4,z>>4))return true;
        if(recipe.wall()==null)return false;
        scope.x=x;scope.y=y;scope.z=z;scope.kind=LivingAbyssCubeEnvelope.classify(parent.parameters(),recipe.domain(),geometry::wallRadius,x,y,z).kind();
        boolean intersects=reservations.fillCubeMask(x,y,z,scope.reserved);protectedReservations.fillCubeMask(x,y,z,scope.protectedVoxels);scope.active=true;
        scope.water=water==null?null:water.voxels().cubeView(x>>4,y>>4,z>>4);
        scope.flora=flora==null?null:flora.features().cubeView(x>>4,y>>4,z>>4);
        if(!cubePredicatesBypassed)scope.predicates.begin(x,y,z);
        if(intersects&&Math.abs((long)x)<960&&Math.abs((long)z)<960)parent.beginPreparedParentCube(x,y,z);
        return scope.kind==LivingAbyssCubeEnvelope.Kind.CENTRE_AIR;
    }
    public void finishCube(){var scope=cubeScope.get();scope.active=false;scope.predicates.end();scope.water=null;scope.flora=null;parent.finishPreparedParentCube();}
    public boolean uniformAirCube(int cx,int cy,int cz){
        if(airCertificateBypassed)return false;
        if(revision155==null)return airCertificate.uniformAir(cx,cy,cz);
        if(cx<(Integer.MIN_VALUE>>4)||cx>(Integer.MAX_VALUE>>4)||cy<(Integer.MIN_VALUE>>4)||cy>(Integer.MAX_VALUE>>4)||cz<(Integer.MIN_VALUE>>4)||cz>(Integer.MAX_VALUE>>4))return false;
        return revision155.uniformCentreAir(cx*16,cy*16,cz*16);
    }
    private boolean deepCube(int x,int y,int z){return deepCube(cubeScope.get(),x,y,z);}
    private static boolean deepCube(CubeScope scope,int x,int y,int z){return scope.contains(x,y,z)&&scope.kind==LivingAbyssCubeEnvelope.Kind.DEEP_ROCK;}
    private boolean reservedAt(int x,int y,int z){return reservedAt(cubeScope.get(),x,y,z);}
    private boolean reservedAt(CubeScope scope,int x,int y,int z){return scope.contains(x,y,z)?(scope.reserved[(z-scope.z)*16+x-scope.x]&(1<<(y-scope.y)))!=0:reservations.contains(x,y,z);}
    private boolean protectedAt(int x,int y,int z){return protectedAt(cubeScope.get(),x,y,z);}
    private boolean protectedAt(CubeScope scope,int x,int y,int z){return scope.contains(x,y,z)?(scope.protectedVoxels[(z-scope.z)*16+x-scope.x]&(1<<(y-scope.y)))!=0:protectedReservations.contains(x,y,z);}
    public BlockState sample(int x,int y,int z) {
        if(revision155!=null)return sample155(x,y,z);
        if(uniformAirCube(x>>4,y>>4,z>>4))return Blocks.AIR.defaultBlockState();
        var scope=cubeScope.get();
        if(y>=recipe.domain().wallBottomY()&&y<=recipe.domain().emptyTopY()&&(double)x*x+(double)z*z<380.0*380)return Blocks.AIR.defaultBlockState();
        if(water!=null){int code=waterAt(scope,x,y,z);if(code!=0)return rememberBase(scope,x,y,z,LivingAbyssBlockStates.feature(code));}
        if(flora!=null){int code=floraAt(scope,x,y,z);if(code!=0)return rememberBase(scope,x,y,z,LivingAbyssBlockStates.feature(code));}
        var state=baseBlock(scope,x,y,z,true);
        rememberBase(scope,x,y,z,state);
        if(mouths!=null&&y<0&&reservedAt(scope,x,y,z)&&!protectedAt(scope,x,y,z)){var dressed=mouthDressing(state,x,y,z);if(dressed!=null)return dressed;}
        if(recipe.wall()==null||y>=0||y<-1350||Math.sqrt((double)x*x+(double)z*z)>=960)return state;
        if(protectedAt(scope,x,y,z))return state;
        if(state.isAir()){
            var tree=flora==null?tree(x,y,z):null;if(tree!=null)return tree;
            int mask=green.vineMask(x,y,z);if(mask!=0)return vine(mask);
            if((basePredicates(scope,x,y-1,z)&2)!=0&&green.moss(x,y-1,z)
                &&LivingAbyssGreenPlan.mossCoating(recipe.seed(),x,y-1,z,true)==LivingAbyssFeatureBlock.MOSS_CARPET.ordinal())return Blocks.MOSS_CARPET.defaultBlockState();
        }else if(state.isSolid()&&green.moss(x,y,z)){
            int above=floraAt(scope,x,y+1,z);
            if(above>=LivingAbyssFeatureBlock.SHORT_GRASS.ordinal()&&above<=LivingAbyssFeatureBlock.DANDELION.ordinal())return Blocks.MOSS_BLOCK.defaultBlockState();
            int code=LivingAbyssGreenPlan.mossCoating(recipe.seed(),x,y,z,(basePredicates(scope,x,y+1,z)&1)!=0);
            return code==LivingAbyssFeatureBlock.MOSS_CARPET.ordinal()?state:LivingAbyssBlockStates.feature(code);
        }
        return state;
    }
    private BlockState sample155(int x,int y,int z){return sample155(x,y,z,true);}
    private int shape155(int x,int y,int z,boolean finished){return finished?revision155.sample(x,y,z):revision155.sampleWithoutFloor(x,y,z);}
    private BlockState sample155(int x,int y,int z,boolean finished){
        if(y<knownLowerY(x,z))throw new TerrainUnavailableException(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER,new dev.madeinabyss.kernel.BlockCoordinate(x,y,z));
        double radius=Math.hypot(x,z),limit=Math.max(960,revision155.maximumRadius());
        if(y>=0||radius>=limit)return parent.sampleBlock(x,y,z);
        if(y<=recipe.domain().temporaryFloorY()+8&&y<=geometry.floorTop(x,z))return rocks.get(revision155.material(x,y,z));
        int code=shape155(x,y,z,finished);
        if(code==LivingAbyssRevision155.AIR||code==LivingAbyssRevision155.HALL)return Blocks.AIR.defaultBlockState();
        var mouth=mouths==null||code>=7?null:mouths.hit(x,y,z);
        if(mouth!=null&&code!=LivingAbyssRevision155.SPELEO&&code<7&&mouths.carve(mouth,y)
            &&(!revision155.stageB()||!revision155.speleoProtectedAt(x,y,z)))return Blocks.AIR.defaultBlockState();
        // Retain only explicitly authored parent blocks. Natural fill never overrides strata.
        if(y>=parent.parameters().cavity().declaredLowerBoundY()&&protectedAt(x,y,z)){
            var old=parent.sampleBlock(x,y,z);var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(old.getBlock());
            if(!naturalTerrain(old)&&!id.getPath().contains("limestone")&&!id.getPath().contains("galena"))return old;
        }
        if(code>=LivingAbyssRevision155.FEATURE_BASE)return LivingAbyssBlockStates.feature(code-LivingAbyssRevision155.FEATURE_BASE);
        if(y>=parent.parameters().cavity().declaredLowerBoundY()&&reservedAt(x,y,z)&&radius>=legacyBare.cavityRadius(x,y,z)){
            var old=parent.sampleGeometry(x,y,z);if(old instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.AIR
                &&(mouth==null||mouth.depth()<.55)
                &&(!revision155.stageB()||!(code==LivingAbyssRevision155.SPELEO||code>=7||revision155.speleoProtectedAt(x,y,z))))return Blocks.AIR.defaultBlockState();
        }
        if(code>=7)return tip155(code);
        if(code==LivingAbyssRevision155.SPELEO)return rocks.get(revision155.speleoMaterial(x,y,z));
        if(mouth!=null){var rim=mouths.rimRock(mouth,x,y,z);if(rim!=LivingAbyssFeatureBlock.NONE)return LivingAbyssBlockStates.feature(rim.ordinal());}
        // Stage A retains the agreed soil/rim-top rule. Hall flora/light admission is Stage B.
        if(code==LivingAbyssRevision155.LEDGE&&radius<LivingAbyssMath155.inner(-((double)y))){
            boolean above=LivingAbyssRevision155.isSolid(shape155(x,y+1,z,finished));
            if(!above){double patch=LivingAbyssSurfaceMaterials.patch(recipe.seed(),x,z);
                var kind=LivingAbyssSurfaceMaterials.top(patch,LivingAbyssMath155.mixUnit(recipe.seed()^x^Long.rotateLeft(z,21)));
                if(kind==LivingAbyssGeometry.Kind.GRASS)return Blocks.GRASS_BLOCK.defaultBlockState();
                if(kind==LivingAbyssGeometry.Kind.MOSS)return Blocks.MOSS_BLOCK.defaultBlockState();
                if(kind==LivingAbyssGeometry.Kind.COARSE_DIRT)return Blocks.COARSE_DIRT.defaultBlockState();
            }else if(!LivingAbyssRevision155.isSolid(shape155(x,y+2,z,finished))||!LivingAbyssRevision155.isSolid(shape155(x,y+3,z,finished))){
                boolean side=!LivingAbyssRevision155.isSolid(shape155(x+1,y,z,finished))||!LivingAbyssRevision155.isSolid(shape155(x-1,y,z,finished))
                    ||!LivingAbyssRevision155.isSolid(shape155(x,y,z+1,finished))||!LivingAbyssRevision155.isSolid(shape155(x,y,z-1,finished));
                if(!side)return revision155.edgeSoilAt(x,y,z)?Blocks.ROOTED_DIRT.defaultBlockState():Blocks.DIRT.defaultBlockState();
            }
        }
        return rocks.get(code==LivingAbyssRevision155.LEDGE&&revision155.stageB()?revision155.bodyMaterial(x,y,z):revision155.material(x,y,z));
    }
    /** Match the cave's inward ray to the first actual solid-to-air B boundary.
     * The occupancy gradient is evaluated once per descriptor, never per voxel. */
    private LivingAbyssCaveMouths.Surface mouthSurface155B(double theta,int y,double portalRadius){
        double sx=Math.sin(theta),sz=-Math.cos(theta);int low=(int)Math.floor(LivingAbyssMath155.centre(-y))-2;
        boolean previous=false;int radius=(int)Math.ceil(Math.min(Math.max(960,revision155.maximumRadius()),portalRadius+4));
        for(int r=radius;r>=low;r--){int x=(int)Math.round(r*sx),z=(int)Math.round(r*sz);
            boolean solid=LivingAbyssRevision155.isSolid(revision155.sampleWithoutFloor(x,y,z));
            if(previous&&!solid){double face=r+.5;int fx=(int)Math.round(face*sx),fz=(int)Math.round(face*sz);double nx=0,ny=0,nz=0;
                for(int dx=-2;dx<=2;dx++)for(int dy=-2;dy<=2;dy++)for(int dz=-2;dz<=2;dz++){
                    double sign=LivingAbyssRevision155.isSolid(revision155.sampleWithoutFloor(fx+dx,y+dy,fz+dz))?1:-1;nx+=sign*dx;ny+=sign*dy;nz+=sign*dz;}
                double norm=Math.sqrt(nx*nx+ny*ny+nz*nz);if(norm<1e-9)return new LivingAbyssCaveMouths.Surface(face,sx,0,sz);
                return new LivingAbyssCaveMouths.Surface(face,nx/norm,ny/norm,nz/norm);
            }previous=solid;
        }return null;
    }
    private static BlockState tip155(int code){int index=(code-7)&3;
        var thickness=switch(index){case 0->net.minecraft.world.level.block.state.properties.DripstoneThickness.BASE;case 1->net.minecraft.world.level.block.state.properties.DripstoneThickness.MIDDLE;
            case 2->net.minecraft.world.level.block.state.properties.DripstoneThickness.FRUSTUM;default->net.minecraft.world.level.block.state.properties.DripstoneThickness.TIP;};
        return Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.VERTICAL_DIRECTION,code<11?net.minecraft.core.Direction.DOWN:net.minecraft.core.Direction.UP)
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DRIPSTONE_THICKNESS,thickness);}
    private static final net.minecraft.world.level.BlockGetter EMPTY=net.minecraft.world.level.EmptyBlockGetter.INSTANCE;
    /** Section24 mouth dressing: rim rock, dressed floor, ceiling hangers and floor plants. */
    private BlockState mouthDressing(BlockState state,int x,int y,int z){
        var h=mouths.hit(x,y,z);if(h==null)return null;
        if(state.isAir()){
            if(!mouths.carve(h,y)){
                var rubble=mouths.rubble(h,x,y,z);
                if(rubble!=LivingAbyssFeatureBlock.NONE&&baseBlock(x,y-1,z,false).isFaceSturdy(EMPTY,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.UP))return LivingAbyssBlockStates.feature(rubble.ordinal());
                return null;
            }
            // Cave-vine curtains hang in chains from the mouth ceiling near the face.
            var m=h.mouth();
            if(h.depth()>=.5&&y>m.floorY()+1&&mouths.curtainColumn(m,x,z)){
                int top=y;while(top<y+40&&mouths.carve(mouths.hit(x,top+1,z),top+1))top++;
                if(baseBlock(x,top+1,z,false).isFaceSturdy(EMPTY,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.DOWN)){
                    int length=(int)mouths.curtainLength(m,x,z);
                    if(top-y<length)return LivingAbyssBlockStates.feature((top-y==length-1||y==m.floorY()+2?LivingAbyssFeatureBlock.CAVE_VINES:LivingAbyssFeatureBlock.CAVE_VINES_PLANT).ordinal());
                }
            }
            boolean ceiling=baseBlock(x,y+1,z,false).isFaceSturdy(EMPTY,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.DOWN);
            var d=mouths.airDressing(h,x,y,z,ceiling);if(d==LivingAbyssFeatureBlock.NONE)return null;
            if(!ceiling){
                var below=baseBlock(x,y-1,z,false);if(!below.isFaceSturdy(EMPTY,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.UP))return null;
                if(d!=LivingAbyssFeatureBlock.MOSS_CARPET){var ground=mouths.rimRock(mouths.hit(x,y-1,z),x,y-1,z);
                    if(ground!=LivingAbyssFeatureBlock.MOSS&&ground!=LivingAbyssFeatureBlock.GRASS)return null;}
            }
            return LivingAbyssBlockStates.feature(d.ordinal());
        }
        if(!state.isSolid())return null;
        var d=mouths.rimRock(h,x,y,z);if(d==LivingAbyssFeatureBlock.NONE)return null;
        boolean exposed=baseBlock(x,y+1,z,false).isAir()||baseBlock(x,y-1,z,false).isAir()||baseBlock(x+1,y,z,false).isAir()
            ||baseBlock(x-1,y,z,false).isAir()||baseBlock(x,y,z+1,false).isAir()||baseBlock(x,y,z-1,false).isAir();
        return exposed?LivingAbyssBlockStates.feature(d.ordinal()):null;
    }
    /** Plants and tree stamps keep one metre from water features and their cut air. */
    private boolean waterNear(int x,int y,int z){
        if(water==null||!water.voxels().touches(x,y,z))return false;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-1;dy<=1;dy++)if(water.at(x+dx,y+dy,z+dz)!=0)return true;
        return false;
    }
    private int waterAt(CubeScope scope,int x,int y,int z){return water==null?0:scope.contains(x,y,z)?scope.water==null?0:scope.water.atLocal(((y&15)<<8)|((z&15)<<4)|(x&15)):water.at(x,y,z);}
    private int floraAt(CubeScope scope,int x,int y,int z){return flora==null?0:scope.contains(x,y,z)?scope.flora==null?0:scope.flora.atLocal(((y&15)<<8)|((z&15)<<4)|(x&15)):flora.at(x,y,z);}
    private static int predicates(BlockState state){return 4|(state.isAir()?1:0)|(state.isFaceSturdy(EMPTY,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.UP)?2:0);}
    /** All selected rock palettes have the same isSolid/isAir/sturdy-UP predicates as STONE.
     * Cache before mouth/green dressing: baseWithFeatures intentionally excludes those outputs. */
    private BlockState rememberBase(CubeScope scope,int x,int y,int z,BlockState state){
        if(!cubePredicatesBypassed&&scope.contains(x,y,z))scope.predicates.put(x,y,z,predicates(state));return state;
    }
    private int basePredicates(CubeScope scope,int x,int y,int z){
        int flags=scope.predicates.get(x,y,z);if(flags!=0)return flags;
        flags=predicates(baseWithFeatures(x,y,z));scope.predicates.put(x,y,z,flags);return flags;
    }
    private BlockState baseWithFeatures(int x,int y,int z){var scope=cubeScope.get();int code=waterAt(scope,x,y,z);if(code!=0)return LivingAbyssBlockStates.feature(code);code=floraAt(scope,x,y,z);return code==0?baseBlock(scope,x,y,z,false):LivingAbyssBlockStates.feature(code);}
    private BlockState baseBlock(int x,int y,int z) {
        return baseBlock(x,y,z,true);
    }
    private BlockState baseBlock(int x,int y,int z,boolean chooseRockMaterial) {return baseBlock(cubeScope.get(),x,y,z,chooseRockMaterial);}
    private BlockState baseBlock(CubeScope scope,int x,int y,int z,boolean chooseRockMaterial) {
        var state=unadmittedBlock(scope,x,y,z,chooseRockMaterial);
        if(recipe.wall()==null||y>=0||y<-1350||!state.isSolid()||state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.DIRT))return state;
        if(deepCube(scope,x,y,z)||!reservedAt(scope,x,y,z))return state;
        double r=Math.sqrt((double)x*x+(double)z*z),raw=geometry.wallRadius(x,y,z);
        if(r<raw-geometry.wallField().maximumProtrusion()-1||r>raw+1.5)return state;
        // Wall shells only. Shelf interiors, trees and freestanding pillar cores keep
        // their own analytic support descriptors and exact cave/path reservations.
        if(r<raw-geometry.wallField().protrusion(geometry.azimuthAt(x,z),-((double)y))-1||r>raw+1.5)return state;
        return supportedWall(x,y,z,0)?state:Blocks.AIR.defaultBlockState();
    }
    private boolean supportedWall(int x,int y,int z,int depth){
        if(depth>2048)throw new IllegalStateException("Wall support path escaped its finite shaft domain");
        int slot=(int)LivingAbyssFields.mix(((long)x<<32)^Long.rotateLeft(y,17)^(z&0xffffffffL))&16383;
        var cache=wallSupports.get();var old=cache[slot];if(old!=null&&old.x==x&&old.y==y&&old.z==z)return old.value;
        var state=unadmittedBlock(x,y,z,false);boolean supported=false;
        if(state.isFaceSturdy(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.UP)){
            boolean originalSolid=y<parent.parameters().cavity().declaredLowerBoundY()?Math.sqrt((double)x*x+(double)z*z)>=geometry.wallRadius(x,y,z):
                parent.sampleBlock(x,y,z).isFaceSturdy(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.UP);
            if(originalSolid)supported=true;
            else if(Math.abs(x)>=Math.abs(z))supported=supportedWall(x+Integer.signum(x),y,z,depth+1);
            else supported=supportedWall(x,y,z+Integer.signum(z),depth+1);
        }
        cache[slot]=new Support(x,y,z,supported);return supported;
    }
    private BlockState unadmittedBlock(int x,int y,int z,boolean chooseRockMaterial) {return unadmittedBlock(cubeScope.get(),x,y,z,chooseRockMaterial);}
    private BlockState unadmittedBlock(CubeScope scope,int x,int y,int z,boolean chooseRockMaterial) {
        if(y<knownLowerY(x,z))throw new TerrainUnavailableException(TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER,new dev.madeinabyss.kernel.BlockCoordinate(x,y,z));
        // Exterior island/ocean remains authoritative at exactly its old spatial boundary.
        if((double)x*x+(double)z*z>=960.0*960)return parent.sampleBlock(x,y,z);
        var kind=deepCube(scope,x,y,z)?LivingAbyssGeometry.Kind.ROCK:geometry.sample(x,y,z);
        // Final R380 has priority over reservations. Conflicting routes are reported.
        if(y>=recipe.domain().wallBottomY()&&y<=recipe.domain().emptyTopY()&&(double)x*x+(double)z*z<380.0*380)return Blocks.AIR.defaultBlockState();
        if(y>=parent.parameters().cavity().declaredLowerBoundY()&&protectedAt(scope,x,y,z)){
            // v15.4: a protected v14 box keeps only its built blocks. Copying the whole AABB of old
            // natural terrain left rectangular pits and wall niches where v15 moved walls and ledges.
            var old=parent.sampleBlock(x,y,z);
            if(recipe.wall()==null||!naturalTerrain(old))return old;
        }
        if(kind==LivingAbyssGeometry.Kind.AIR)return Blocks.AIR.defaultBlockState();
        if(kind==LivingAbyssGeometry.Kind.DELEGATE)return parent.sampleBlock(x,y,z);
        if(y<=recipe.domain().temporaryFloorY()+8&&y<=geometry.floorTop(x,z)) {
            long h=LivingAbyssFields.mix(recipe.seed()^Math.floorDiv(x,8)^Long.rotateLeft(Math.floorDiv(z,8),21));
            return (y==geometry.floorTop(x,z)&&Math.floorMod(h,7)==0?Blocks.MOSS_BLOCK:Math.floorMod(h,5)==0?Blocks.TUFF:Blocks.DEEPSLATE).defaultBlockState();
        }
        if(caveCut(scope,geometry,x,y,z))return Blocks.AIR.defaultBlockState();
        // Extend every cave mouth's radial opening through the new protrusion field.
        // Exact path reservations also retain their original air and authored blocks.
        if(recipe.wall()!=null&&y>=parent.parameters().cavity().declaredLowerBoundY()&&kind==LivingAbyssGeometry.Kind.ROCK&&reservedAt(scope,x,y,z)){
            double r=Math.sqrt((double)x*x+(double)z*z),wall=geometry.wallRadius(x,y,z);
            if(r>=wall){var previous=parent.sampleBlock(x,y,z);if(!previous.is(Blocks.STONE))return previous;}
        }
        return switch(kind) {
            case AIR -> Blocks.AIR.defaultBlockState();case GRASS -> Blocks.GRASS_BLOCK.defaultBlockState();
            case SOIL -> Blocks.DIRT.defaultBlockState();case STONE_BRICKS -> Blocks.STONE_BRICKS.defaultBlockState();
            case ROOTED_DIRT -> Blocks.ROOTED_DIRT.defaultBlockState();case COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();case CALCITE -> Blocks.CALCITE.defaultBlockState();
            case MOSS -> Blocks.MOSS_BLOCK.defaultBlockState();case ANDESITE -> Blocks.ANDESITE.defaultBlockState();case MOSSY_COBBLESTONE -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case BOULDER_STONE -> Blocks.STONE.defaultBlockState();
            case BODY_ROCK -> recipe.wall()==null||!chooseRockMaterial?Blocks.STONE.defaultBlockState():rocks.get(geometry.wallField().spireMaterial(geometry.azimuthAt(x,z),x,y,z));
            default -> {if(recipe.wall()==null||!chooseRockMaterial)yield Blocks.STONE.defaultBlockState();double th=geometry.azimuthAt(x,z);
                yield rocks.get(geometry.wallField().material(plan.fields().zoneAtDepth(-((double)y),th),th,x,y,z));}
        };
    }
    private static final java.util.Set<net.minecraft.world.level.block.Block> NATURAL=java.util.Set.of(Blocks.AIR,Blocks.CAVE_AIR,Blocks.STONE,Blocks.DIRT,
        Blocks.GRASS_BLOCK,Blocks.COARSE_DIRT,Blocks.ROOTED_DIRT,Blocks.GRAVEL,Blocks.SAND,Blocks.ANDESITE,Blocks.DIORITE,Blocks.GRANITE,Blocks.TUFF,Blocks.DEEPSLATE,
        Blocks.CALCITE,Blocks.COBBLESTONE,Blocks.MOSSY_COBBLESTONE,Blocks.MOSS_BLOCK,Blocks.MOSS_CARPET,Blocks.GRASS,Blocks.TALL_GRASS,Blocks.FERN,Blocks.LARGE_FERN,
        Blocks.DRIPSTONE_BLOCK,Blocks.CLAY,Blocks.WATER,Blocks.PODZOL,Blocks.MUD);
    private static boolean naturalTerrain(BlockState state){return NATURAL.contains(state.getBlock());}
    private boolean caveCut(LivingAbyssGeometry geo,int x,int y,int z){return caveCut(cubeScope.get(),geo,x,y,z);}
    private boolean caveCut(CubeScope scope,LivingAbyssGeometry geo,int x,int y,int z){
        if(!reservedAt(scope,x,y,z))return false;
        if(y<parent.parameters().cavity().declaredLowerBoundY())return false;
        if(mouths!=null&&geo==geometry){
            // v15.4: old shaft air inside an entrance box no longer cuts rectangles. Genuine v14 cave
            // interior behind the old cliff stays open, and a natural mouth joins it to the v15 face.
            if(mouths.carve(mouths.hit(x,y,z),y))return true;
            var interior=parent.sampleGeometry(x,y,z);
            return interior instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.AIR&&Math.sqrt((double)x*x+(double)z*z)>=geo.wallRadius(x,y,z)+1;
        }
        // An indexed entrance lip can be inside the old opening radius. The
        // reservation, rather than a radial gate, determines where old air wins.
        double r=Math.sqrt((double)x*x+(double)z*z);var old=parent.sampleGeometry(x,y,z);
        if(old instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.AIR)return true;
        if(recipe.wall()==null)return false;
        double raw=geo.wallRadius(x,y,z),theta=geo.azimuthAt(x,z);
        if(r<=0||r>=raw||r<raw-geo.wallField().maximumProtrusion()-1-1e-9||r<raw-geo.wallField().protrusion(theta,-((double)y))-1)return false;
        double a=Math.toRadians(theta),probe=geo.wallBoundaryAt(theta,y)+3;
        int px=(int)Math.round(probe*Math.sin(a)),pz=(int)Math.round(-probe*Math.cos(a));
        // Angular projection rounds X and Z independently. A tangent-only offset
        // rounds to zero on one axis near cardinal directions, missing entrance lips.
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            int qx=px+dx,qz=pz+dz;
            var portal=parent.sampleGeometry(qx,y,qz);if(portal instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.AIR)return true;
        }return false;
    }
    private BlockState tree(int x,int y,int z){
        for(var spire:spires.candidates(x,z))for(var t:spire.trees()){
            int dx=x-t.x(),dz=z-t.z(),dy=y-t.y();
            if(dx==0&&dz==0&&dy>=0&&dy<t.height())return (t.birch()?Blocks.BIRCH_LOG:Blocks.OAK_LOG).defaultBlockState();
            int cy=t.height()-1;if(Math.abs(dx)<=2&&Math.abs(dz)<=2&&dy>=cy-2&&dy<=cy+1
                    &&dx*dx+dz*dz+2*(dy-cy)*(dy-cy)<=7)return (t.birch()?Blocks.BIRCH_LEAVES:Blocks.OAK_LEAVES).defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT,true);
        }return null;
    }
    public static BlockState vine(int mask){return Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH,(mask&1)!=0).setValue(VineBlock.SOUTH,(mask&2)!=0).setValue(VineBlock.WEST,(mask&4)!=0).setValue(VineBlock.EAST,(mask&8)!=0);}
    public int knownLowerY(int x,int z){double limit=revision155==null?960:Math.max(960,revision155.maximumRadius());return (double)x*x+(double)z*z<limit*limit?recipe.domain().knownLowerY():parent.knownLowerY(x,z);}
    public LivingAbyssPlan plan(){return plan;}public LivingAbyssGeometry geometry(){return geometry;}public D8Hydrology.Plan hydrology(){return hydrology;}
    public LivingAbyssRecipe recipeValue(){return recipe;}public LivingAbyssSpirePlan spires(){return spires;}public LivingAbyssGreenPlan green(){return green;}
    public LivingAbyssAirCertificate airCertificate(){return airCertificate;}public boolean airCertificateBypassed(){return airCertificateBypassed;}
    public long airCertificateBuildNanos(){return airCertificateBuildNanos;}
    public LivingAbyssFloraPlan flora(){return flora;}public LivingAbyssWaterPlan water(){return water;}public List<?> caveMouths(){return mouths==null?List.of():mouths.mouths();}
    public LivingAbyssRevision155 revision155(){return revision155;}
    public String planDigest(){return revision155!=null?revision155.digest():LivingAbyssDigest.of("v15.3-part3a-plan",plan.digest()+spires.digest()+green.digest()+(flora==null?"":flora.features().digest()));}public String hydrologyDigest(){return hydrology.digest();}
    public List<BlockState> palette(){return List.copyOf(LivingAbyssBlockStates.palette());}
    public JsonObject status() {
        if(revision155!=null){var j=new JsonObject();j.addProperty("revision",recipe.revision());j.addProperty("algorithmVersion",revision155.stageB()?recipe.revision():LivingAbyssRevision155.VERSION);
            j.addProperty("planDigest",revision155.digest());j.addProperty("hydrologyDigest",hydrology.digest());j.addProperty("wetDegrees",hydrology.wetDegrees());
            j.add("counts",new Gson().toJsonTree(revision155.counts()));j.add("diagnostics",new Gson().toJsonTree(revision155.diagnostics()));
            j.addProperty("knownLowerY",recipe.domain().knownLowerY());j.addProperty("maximumShapeRadius",revision155.maximumRadius());j.addProperty("floraStage","B-DEFERRED");j.addProperty("waterStage","C-DEFERRED");return j;}
        var j=new JsonObject();j.addProperty("revision",recipe.revision());j.addProperty("planDigest",plan.digest());j.addProperty("hydrologyDigest",hydrology.digest());
        j.addProperty("wetDegrees",hydrology.wetDegrees());j.addProperty("wetReason",hydrology.reason());j.addProperty("ledgeCount",plan.ledges().size());
        j.addProperty("shortenedCount",plan.ledges().stream().filter(LivingAbyssPlan.Ledge::shortened).count());j.addProperty("conflictCount",plan.conflicts().size());
        j.addProperty("spireCount",spires.spires().size());j.addProperty("spireDigest",spires.digest());j.addProperty("greenDigest",green.digest());j.addProperty("vineColumns",green.vines().size());
        j.add("spireAdmission",new Gson().toJsonTree(spires.admissionStats()));
        j.addProperty("vineVoxels",green.vines().stream().mapToInt(v->v.masks().size()).sum());j.addProperty("unsupportedVineStops",green.unsupportedStops());
        j.addProperty("reservationCells",reservations.cells());j.addProperty("reservationReferences",reservations.references());
        if(flora!=null)for(var e:flora.statistics().entrySet())j.add(e.getKey(),new Gson().toJsonTree(e.getValue()));
        j.addProperty("knownLowerY",recipe.domain().knownLowerY());j.addProperty("temporaryFloorY",recipe.domain().temporaryFloorY());return j;
    }
}
