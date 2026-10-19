package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.natural.*;
import dev.madeinabyss.kernel.terrain.trail.TrailNetwork;
import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Versioned first-layer ORIGINAL recipe. Old manifests keep their exact old generation. */
public final class CaveExpansionGeneration {
    private final TerrainParameters parameters;
    private final ParametricTerrainSampler baseline;
    private final CompiledTerrainSampler compiled;
    private final SupportedCaveTerrainSampler caves;
    private final RetainedWaterSampler water;
    private final DenseEcologyOverlay ecology;
    private final WildernessBlockStates palette;
    private final CaveInteriorOverlay interiors;
    private final Map<Integer,List<CaveNetworkTerrainSampler.Portal>> mouths;
    private final double minimumRadius,maximumRadius;
    public CaveExpansionGeneration(TerrainParameters p,JsonObject recipe,TrailNetwork.SampleSource source,CliffTerrainSampler.Protection protection){
        this(p,recipe,source,protection,Boolean.parseBoolean(System.getProperty("mia.generated.optimizedSampler","true")));
    }
    CaveExpansionGeneration(TerrainParameters p,JsonObject recipe,TrailNetwork.SampleSource source,CliffTerrainSampler.Protection protection,boolean optimized){
        this(p,recipe,source,protection,optimized?new CompiledTerrainSampler(p):null);
    }
    CaveExpansionGeneration(TerrainParameters p,JsonObject recipe,TrailNetwork.SampleSource source,CliffTerrainSampler.Protection protection,CompiledTerrainSampler shared){
        if(recipe.get("schema").getAsInt()!=1||!recipe.get("provenance").getAsString().equals("ORIGINAL")
                ||!recipe.get("algorithmSha256").getAsString().equals(algorithmSha256()))
            throw new IllegalArgumentException("Wilderness needs explicit matching schema1 ORIGINAL algorithm");
        parameters=p;baseline=new ParametricTerrainSampler(p);compiled=shared;
        var cp=new Gson().fromJson(recipe.get("caves"),CaveNetworkParameters.class);
        caves=new SupportedCaveTerrainSampler(p,cp,source::sample,protection::protectedAt);
        var c=p.cavity();minimumRadius=Math.max(0,Math.min(c.mouthRadius(),c.lowerRadius())-p.noise().wallAmplitude()-c.ledgeProjection()-48);
        maximumRadius=Math.max(c.mouthRadius(),c.lowerRadius())+p.noise().wallAmplitude()+cp.maximumPenetration()+24;
        long seed=recipe.get("ecologySeed").getAsLong();
        water=new RetainedWaterSampler(seed,caves::sample,(x,y,z)->protection.protectedAt(x,y,z)||caves.routeProtectedAt(x,y,z),this::surfaceBand);
        var grouped=new HashMap<Integer,List<CaveNetworkTerrainSampler.Portal>>();
        for(var portal:caves.portals())grouped.computeIfAbsent(portal.network(),n->new ArrayList<>()).add(portal);
        mouths=Map.copyOf(grouped);
        interiors=new CaveInteriorOverlay(seed,water::sample,protection::protectedAt,this::interiorFeature);
        ecology=new DenseEcologyOverlay(seed,this::sample,(x,y,z)->protection.protectedAt(x,y,z)||caves.routeProtectedAt(x,y,z),this::surfaceBand);
        palette=new WildernessBlockStates(recipe.get("alexAssets").getAsBoolean());
    }
    public TerrainSample sample(int x,int y,int z){
        var base=water.sample(x,y,z);
        return interiors.waterAt(x,y,z,base)?new TerrainSample.Known(TerrainMaterial.WATER):base;
    }
    public CaveInteriorOverlay.Feature interiorFeature(int x,int y,int z){
        int network=caves.caveNetworkAt(x,y,z);
        if(network<0){
            // Include exposed faces just outside the carved volume without decorating the whole shaft.
            for(int[] d:SIDES){int n=caves.caveNetworkAt(x+d[0],y+d[1],z+d[2]);if(n>=0){network=n;break;}}
        }
        if(network<0)return new CaveInteriorOverlay.Feature(false,Double.POSITIVE_INFINITY,false);
        double distance=Double.POSITIVE_INFINITY;
        for(var portal:mouths.getOrDefault(network,List.of())){var p=portal.position();distance=Math.min(distance,Math.sqrt((p.x()-x)*(p.x()-x)+(p.y()-y)*(p.y()-y)+(p.z()-z)*(p.z()-z)));}
        return new CaveInteriorOverlay.Feature(true,distance,caves.routeProtectedAt(x,y,z));
    }
    private static final int[][] SIDES={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    public CaveInteriorOverlay interiors(){return interiors;}
    public Optional<BlockState> decoration(int x,int y,int z,TerrainSample base){
        var feature=interiorFeature(x,y,z);
        if(feature.cave()){
            var detail=interiors.sampleOverride(x,y,z);
            if(detail.isPresent())return Optional.of(CaveInteriorBlockStates.state(detail.get()));
            if(base instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.ROCK)return Optional.of(palette.rock(x,y,z));
            return Optional.empty();
        }
        var d=ecology.sampleOverride(x,y,z,base);if(d.isPresent())return Optional.of(palette.state(d.get()));
        if(base instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.ROCK)return Optional.of(palette.rock(x,y,z));
        return Optional.empty();
    }
    private boolean surfaceBand(int x,int y,int z){
        var c=parameters.cavity();if(y<c.declaredLowerBoundY()||y>=320)return false;
        double radial=compiled==null?Math.hypot((double)x,(double)z):compiled.radialDistance(x,z);
        if(y>=parameters.surface().oceanLevelY()-3&&Math.abs(y-(compiled==null?baseline.surfaceHeight(x,z):compiled.surfaceHeight(x,z)))<=16&&radial>=c.mouthRadius()-16)return true;
        if(y>=c.firstLayerTopY()||radial<minimumRadius||radial>maximumRadius)return false;
        double wall=radius(x,y,z);
        if(Math.abs(radial-wall)<=52)return true;
        // A shelf's soil and its canopy lie on opposite sides of the local radius.
        // Use a symmetric envelope: an air-only downward probe dropped supported pools.
        int low=Math.max(c.declaredLowerBoundY(),y-16),high=Math.min((int)Math.ceil(c.firstLayerTopY())-1,y+16);
        double below=radius(x,low,z),above=radius(x,high,z);
        return radial>=Math.min(wall,Math.min(below,above))-6&&radial<=Math.max(wall,Math.max(below,above))+6;
    }
    private double radius(int x,int y,int z){return compiled==null?baseline.cavityRadius(x,y,z):compiled.cavityRadius(x,y,z);}
    public SupportedCaveTerrainSampler caves(){return caves;}
    public RetainedWaterSampler water(){return water;}
    public DenseEcologyOverlay ecology(){return ecology;}
    public Collection<BlockState> palette(){var all=new HashSet<>(palette.palette());all.addAll(CaveInteriorBlockStates.palette());return Set.copyOf(all);}
    public static String algorithmSha256(){return TerrainLodDiskCache.codeIdentity(CaveNetworkParameters.class,CaveNetworkTerrainSampler.class,SupportedCaveTerrainSampler.class,RouteVoxelIndex.class,CaveInteriorOverlay.class,CaveInteriorBlockStates.class,RetainedWaterSampler.class,DenseEcologyOverlay.class,EcologyMaskFields.class,CaveExpansionGeneration.class,WildernessBlockStates.class,CompiledTerrainSampler.class,dev.madeinabyss.kernel.terrain.trail.IndexedTrailOverlay.class);}
    public static JsonObject proposal(long seed,boolean alexAssets){
        var j=new JsonObject();j.addProperty("schema",1);j.addProperty("provenance","ORIGINAL");j.addProperty("algorithmSha256",algorithmSha256());
        j.addProperty("ecologySeed",seed);j.addProperty("alexAssets",alexAssets);j.add("caves",new Gson().toJsonTree(CaveNetworkParameters.proposal(seed)));return j;
    }
    public JsonObject status(){var j=new JsonObject();j.addProperty("algorithmSha256",algorithmSha256());j.addProperty("optimizedSampler",compiled!=null);j.addProperty("portals",caves.portals().size());j.addProperty("supportedRoutes",caves.routes().size());j.addProperty("segments",caves.segments().size());j.addProperty("routeOccupancyCells",caves.routeOccupancyCells());j.addProperty("waterPlans",water.cachedPlanCount());j.addProperty("treePlans",ecology.cachedPlanCount());j.addProperty("provenance","ORIGINAL; visual review pending");return j;}
}
