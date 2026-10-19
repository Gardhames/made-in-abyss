package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.*;
import dev.madeinabyss.kernel.terrain.trail.*;
import dev.madeinabyss.kernel.terrain.habitat.*;
import dev.madeinabyss.kernel.terrain.natural.*;
import net.minecraft.world.level.biome.FixedBiomeSource;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit development manifest. No default metrics, world replacement or shipping preset. */
public final class GeneratedPreviewProfile {
    private final String id, algorithmRevision, fingerprint;
    private final TerrainParameters parameters;
    private final BlockCoordinate ground;
    private final ParameterizedTerrainChunkGenerator generator;
    private final int trailCount;
    private final HabitatOverlay habitats;
    private final LandscapeOverlay landscape;
    private final String landscapeDataHash;
    private final NaturalGeneration natural;
    private final WildernessGeneration wilderness;
    private final CaveExpansionGeneration caveExpansion;
    // Immutable binding from the same validated recipe used by the actual Alex instance.
    private final long derivedAlexEcologySeed;

    public static GeneratedPreviewProfile load(String requestedId, FixedBiomeSource biomes) throws IOException {
        if (!Boolean.getBoolean("mia.cube.playerProbe")) throw new IllegalStateException("Development preview disabled");
        String configured = System.getProperty("mia.generator.previewProfile");
        if (configured == null || configured.isBlank()) throw new IllegalArgumentException("Explicit preview manifest not configured");
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (Files.size(path) > 4194304) throw new IOException("Preview manifest exceeds four MiB");
        var manifest = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        return new GeneratedPreviewProfile(manifest, requestedId, biomes);
    }

    public GeneratedPreviewProfile(JsonObject manifest, String requestedId, FixedBiomeSource biomes) {
        this(manifest,requestedId,biomes,Boolean.parseBoolean(System.getProperty("mia.generated.optimizedSampler","true")));
    }
    GeneratedPreviewProfile(JsonObject manifest, String requestedId, FixedBiomeSource biomes,boolean optimized) {
        id = text(manifest, "profileId");
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}") || !id.equals(requestedId)) throw new IllegalArgumentException("Unknown server preview profile");
        algorithmRevision = text(manifest, "algorithmRevision");
        String algorithmHash = text(manifest, "algorithmSha256");
        if (!algorithmHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Explicit algorithm SHA256 required");
        parameters = TerrainParameterCodecs.PARAMETERS.parse(JsonOps.INSTANCE, manifest.get("parameters"))
                .result().orElseThrow(() -> new IllegalArgumentException("Invalid explicit terrain metrics"));
        var candidate = manifest.getAsJsonObject("groundAnchor");
        ground = new BlockCoordinate(candidate.get("x").getAsInt(), candidate.get("y").getAsInt(), candidate.get("z").getAsInt());
        var terrain = new ParametricTerrainSampler(parameters);
        boolean fastWilderness=optimized&&(manifest.has("wilderness")||manifest.has("caveExpansion"));
        var compiled=fastWilderness?new CompiledTerrainSampler(parameters):null;
        TrailNetwork.SampleSource base = compiled==null?terrain::sample:compiled::sample;
        TrailNetwork.SampleSource source = base;
        var trailJson = manifest.getAsJsonObject("trails");
        List<TrailNetwork.Point> protectedTrailPoints = new ArrayList<>();
        int trailProtectionRadius = 0, trailProtectionDepth = 0;
        if (trailJson != null) {
            var metrics = trailJson.getAsJsonObject("parameters");
            var trailParameters = new TrailNetwork.Parameters(metrics.get("widthMetres").getAsInt(), metrics.get("supportDepthMetres").getAsInt(),
                    metrics.get("headroomMetres").getAsInt(), metrics.get("maxRisePerMetre").getAsDouble(), metrics.get("maxCutInsetMetres").getAsInt());
            List<TrailNetwork.Route> routes = new ArrayList<>();
            for (var value : trailJson.getAsJsonArray("routes")) {
                var route = value.getAsJsonObject(); List<TrailNetwork.Point> points = new ArrayList<>();
                for (var pointValue : route.getAsJsonArray("points")) {
                    var point = pointValue.getAsJsonArray();
                    if (point.size() != 3) throw new IllegalArgumentException("Route needs exact XYZ floor");
                    points.add(new TrailNetwork.Point(point.get(0).getAsInt(), point.get(1).getAsInt(), point.get(2).getAsInt()));
                }
                routes.add(new TrailNetwork.Route(text(route, "id"), points));
            }
            var network = TrailNetwork.verify(base, trailParameters, routes);
            routes.forEach(route -> protectedTrailPoints.addAll(route.points()));
            trailProtectionRadius = trailParameters.widthMetres() / 2 + 2;
            trailProtectionDepth = trailParameters.supportDepthMetres();
            if(fastWilderness){var overlay=new IndexedTrailOverlay(base,network);source=overlay::sample;}
            else {var overlay = new TrailOverlay(base, network); source = overlay::sample;}
            trailCount = routes.size();
        } else trailCount = 0;
        var habitatJson = manifest.getAsJsonObject("habitats");
        if (habitatJson != null) {
            if (!text(habitatJson,"provenance").equals("ORIGINAL")) throw new IllegalArgumentException("Habitat geography needs ORIGINAL visual review");
            if (!text(habitatJson,"algorithmSha256").equals(actualHabitatAlgorithmSha256()))
                throw new IllegalArgumentException("Manifest habitat SHA differs from actual loaded habitat classes");
            var patches = new ArrayList<HabitatOverlay.Patch>();
            for (var value : habitatJson.getAsJsonArray("patches")) {
                var p = value.getAsJsonObject();
                patches.add(new HabitatOverlay.Patch(text(p,"id"),p.get("minX").getAsInt(),p.get("minZ").getAsInt(),
                        p.get("width").getAsInt(),p.get("depth").getAsInt(),p.get("referenceY").getAsInt(),p.get("seed").getAsLong()));
            }
            final int radius = trailProtectionRadius, depth = trailProtectionDepth;
            habitats = HabitatOverlay.plan(source, patches, (x,y,z) -> {
                if (Math.abs((long)x-ground.x())<=3 && Math.abs((long)z-ground.z())<=3 && y>=ground.y()-3L && y<=ground.y()+12L) return true;
                for (var p : protectedTrailPoints) if (Math.abs((long)x-p.x())<=radius && Math.abs((long)z-p.z())<=radius
                        && y>=p.floorY()-(long)depth && y<=p.floorY()+12L) return true;
                return false;
            });
            for (var p : habitats.placements()) if (p.y()<parameters.cavity().declaredLowerBoundY() || p.y()>=320)
                throw new IllegalArgumentException("Habitat placement outside known generated domain");
        } else habitats = null;
        var landscapeJson = manifest.getAsJsonObject("landscape");
        List<LandscapeOverlay.Patch> protectedLandscape=List.of();
        if (landscapeJson != null) {
            if (habitats != null) throw new IllegalArgumentException("Choose one ecology version per profile");
            if (!text(landscapeJson,"algorithmSha256").equals(actualLandscapeAlgorithmSha256()))
                throw new IllegalArgumentException("Landscape algorithm SHA differs from loaded classes");
            var data=landscapeJson.getAsJsonObject("data");
            var patches=LandscapeRecipeCodec.parse(data);
            protectedLandscape=patches;
            final int radius=trailProtectionRadius;
            landscape=LandscapeOverlay.plan(source,patches,(x,y,z)-> {
                if(Math.abs((long)x-ground.x())<=3 && Math.abs((long)z-ground.z())<=3)return true;
                for(var p:protectedTrailPoints)if(Math.abs((long)x-p.x())<=radius && Math.abs((long)z-p.z())<=radius)return true;
                return false;
            });
            for(var p:landscape.placements())if(p.y()<parameters.cavity().declaredLowerBoundY() || p.y()>=320)
                throw new IllegalArgumentException("Landscape outside generated domain");
            landscapeDataHash=hash(canonical(data));
        } else {landscape=null;landscapeDataHash=null;}
        var naturalJson=manifest.getAsJsonObject("naturalization");
        if(naturalJson!=null) {
            if(!text(naturalJson,"provenance").equals("ORIGINAL")||naturalJson.get("schema").getAsBigDecimal().intValueExact()!=1)
                throw new IllegalArgumentException("Naturalization requires schema1 and ORIGINAL provenance");
            if(!text(naturalJson,"algorithmSha256").equals(actualNaturalAlgorithmSha256()))
                throw new IllegalArgumentException("Naturalization algorithm differs from explicit manifest");
            naturalJson.getAsJsonObject("cliffs").get("seed").getAsBigDecimal().longValueExact();
            var recipe=new com.google.gson.Gson().fromJson(naturalJson.get("cliffs"),CliffParameters.class);
            if(recipe==null)throw new IllegalArgumentException("Explicit cliff recipe required");
            final int radius=trailProtectionRadius,depth=trailProtectionDepth;
            var protectedBoxes=new ArrayList<NaturalProtection.Box>();
            protectedBoxes.add(new NaturalProtection.Box(ground.x()-5L,ground.y()-8L,ground.z()-5L,ground.x()+5L,ground.y()+16L,ground.z()+5L));
            for(var p:protectedTrailPoints)protectedBoxes.add(new NaturalProtection.Box(p.x()-(long)radius,p.floorY()-(long)depth-2,p.z()-(long)radius,p.x()+(long)radius,p.floorY()+12L,p.z()+(long)radius));
            for(var p:protectedLandscape)protectedBoxes.add(new NaturalProtection.Box(p.minX()-4L,p.referenceY()-64L,p.minZ()-4L,p.minX()+(long)p.width()+3,p.referenceY()+32L,p.minZ()+(long)p.depth()+3));
            var protection=new NaturalProtection(protectedBoxes);
            natural=new NaturalGeneration(parameters,recipe,source,protection::contains);
            source=natural::sample;
        } else natural=null;
        var caveJson=manifest.getAsJsonObject("caveExpansion");
        var alexJson=manifest.getAsJsonObject("alexEcology");
        derivedAlexEcologySeed=alexJson==null?0:alexJson.get("seed").getAsBigDecimal().longValueExact();
        if(alexJson!=null&&caveJson==null)throw new IllegalArgumentException("Alex ecology requires explicit caveExpansion parent");
        NaturalProtection caveProtection=null;List<NaturalProtection.Box> livingProtectedBoxes=List.of();
        if(caveJson!=null&&(manifest.has("wilderness")||landscape!=null||habitats!=null))throw new IllegalArgumentException("Choose a single composed cave/ecology recipe");
        if(caveJson!=null){
            final int radius=trailProtectionRadius,depth=trailProtectionDepth;
            var boxes=new ArrayList<NaturalProtection.Box>();
            boxes.add(new NaturalProtection.Box(ground.x()-5L,ground.y()-8L,ground.z()-5L,ground.x()+5L,ground.y()+16L,ground.z()+5L));
            for(var point:protectedTrailPoints)boxes.add(new NaturalProtection.Box(point.x()-(long)radius,point.floorY()-(long)depth-2,point.z()-(long)radius,point.x()+(long)radius,point.floorY()+12L,point.z()+(long)radius));
            var protectedArea=new NaturalProtection(boxes);
            caveProtection=protectedArea;
            livingProtectedBoxes=List.copyOf(boxes);
            // Only a fixed, audited implementation pair can alter construction admission. The
            // original caveJson below remains authoritative for canonical persisted fingerprint.
            var constructionRecipe=CaveAlgorithmEquivalence.forConstruction(caveJson,CaveExpansionGeneration.algorithmSha256());
            caveExpansion=new CaveExpansionGeneration(parameters,constructionRecipe,source,protectedArea::contains,compiled);source=caveExpansion::sample;
        }else caveExpansion=null;
        var wildernessJson=manifest.getAsJsonObject("wilderness");
        if(wildernessJson!=null){
            if(landscape!=null||habitats!=null)throw new IllegalArgumentException("Wilderness requires its own composed ecology, not legacy patch overrides");
            final int radius=trailProtectionRadius,depth=trailProtectionDepth;
            var boxes=new ArrayList<NaturalProtection.Box>();
            boxes.add(new NaturalProtection.Box(ground.x()-5L,ground.y()-8L,ground.z()-5L,ground.x()+5L,ground.y()+16L,ground.z()+5L));
            for(var point:protectedTrailPoints)boxes.add(new NaturalProtection.Box(point.x()-(long)radius,point.floorY()-(long)depth-2,point.z()-(long)radius,point.x()+(long)radius,point.floorY()+12L,point.z()+(long)radius));
            var protectedArea=new NaturalProtection(boxes);
            var constructionRecipe=WildernessAlgorithmEquivalence.forConstruction(wildernessJson,WildernessGeneration.algorithmSha256());
            wilderness=new WildernessGeneration(parameters,constructionRecipe,source,protectedArea::contains,compiled);source=wilderness::sample;
        }else wilderness=null;
        var islandJson=manifest.getAsJsonObject("islandSurface");
        var island=islandJson==null?null:new IslandSurfaceGeneration(islandJson,parameters);
        var upperJson=manifest.getAsJsonObject("upperShaftOpening");
        var upperRecipe=upperJson==null?null:UpperShaftOpeningAlgorithmEquivalence.forConstruction(upperJson,UpperShaftOpeningGeneration.actualAlgorithmSha256());
        var upper=upperRecipe==null?null:new UpperShaftOpeningGeneration(upperRecipe,parameters);
        var parent = new ParameterizedTerrainChunkGenerator(parameters, biomes, -64, 384, source, habitats, landscape,natural,wilderness,caveExpansion,alexJson,caveProtection,island,islandJson==null?"":canonical(islandJson),upper);
        var livingJson=manifest.getAsJsonObject("livingAbyss");
        var living=livingJson==null?null:new LivingAbyssGeneration(livingJson,parent,caveProtection,livingProtectedBoxes);
        if(living!=null&&!id.equals("first-layer-v15-living-abyss"))throw new IllegalArgumentException("livingAbyss requires its separate v15 profile ID");
        generator = living==null?parent:new ParameterizedTerrainChunkGenerator(parameters,biomes,-64,384,source,habitats,landscape,natural,wilderness,caveExpansion,alexJson,caveProtection,island,islandJson==null?"":canonical(islandJson),upper,living);
        if (!(source.sample(ground.x(), ground.y(), ground.z()) instanceof TerrainSample.Known k) || k.material() != TerrainMaterial.GRASS
                || !air(source, ground, 1) || !air(source, ground, 2)) throw new IllegalArgumentException("Preview anchor must be actual grass with two air voxels");
        if (feet().y() >= -80) throw new IllegalArgumentException("Generated shelf anchor must be safely below native seam");
        requireVisualDomain(feet());
        // Codec order is fixed; canonicalize optional trail object recursively before identity hashing.
        var normalizedParameters = TerrainParameterCodecs.PARAMETERS.encodeStart(JsonOps.INSTANCE, parameters).result().orElseThrow();
        String liveAlgorithmHash = actualAlgorithmSha256();
        if (!algorithmHash.equals(liveAlgorithmHash)) throw new IllegalArgumentException("Manifest algorithm SHA differs from actual loaded terrain/trail classes");
        fingerprint = hash("mia-generated-preview-v1\n" + id + "\n" + algorithmRevision + "\n" + liveAlgorithmHash + "\n"
                + canonical(normalizedParameters) + "\n" + (trailJson == null ? "no-trails" : canonical(trailJson))
                + (habitatJson == null ? "" : "\nhabitat-v1\n" + canonical(habitatJson) + "\nprotected-anchor\n" + canonical(manifest.get("groundAnchor")))
                + (landscapeJson == null ? "" : "\nlandscape-v1\n" + canonical(landscapeJson) + "\nprotected-anchor\n" + canonical(manifest.get("groundAnchor")))
                + (naturalJson == null ? "" : "\nglobal-naturalization-v1\n"+canonical(naturalJson))
                + (wildernessJson == null ? "" : "\nwilderness-v1\n"+canonical(wildernessJson))
                + (caveJson == null ? "" : "\ncave-expansion-v1\n"+canonical(caveJson))
                + (alexJson == null ? "" : "\nalex-ecology-v1\n"+canonical(alexJson))
                + (islandJson == null ? "" : "\nisland-surface-v1\n"+canonical(islandJson))
                + (upperJson == null ? "" : "\nupper-shaft-opening-v1\n"+canonical(upperJson))
                + (livingJson == null ? "" : "\nliving-abyss-v15\n"+canonical(livingJson)+"\n"+IslandSurfaceGeneration.DATA_SHA+"\n"+living.hydrologyDigest()+"\n"+living.planDigest()));
    }
    private static boolean air(TrailNetwork.SampleSource source, BlockCoordinate ground, int offset) {
        return source.sample(ground.x(), Math.addExact(ground.y(), offset), ground.z()) instanceof TerrainSample.Known k && k.material() == TerrainMaterial.AIR;
    }
    public void requireVisualDomain(BlockCoordinate center) {
        var key = CubeKey.fromBlock(center);
        // Preflight every complete physics cube before residency publishes any state.
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)
            if(!generator.supportsCube(new CubeKey(Math.addExact(key.x(),dx),Math.addExact(key.y(),dy),Math.addExact(key.z(),dz))))
                throw new IllegalArgumentException("Preview physics window crosses unknown spatial domain");
    }
    public int minimumKnownCubeY() {return completeCubeMinimum(generator.minimumKnownY());}
    /** Visual XZ footprint equals the physics halo; a conservative rectangle preserves all admitted deep ocean positions. */
    public int visualMinimumCubeY(CubeKey center){
        int minX=Math.multiplyExact(Math.addExact(center.x(),-1),16);
        int minZ=Math.multiplyExact(Math.addExact(center.z(),-1),16);
        int maxX=Math.addExact(Math.multiplyExact(Math.addExact(center.x(),1),16),15);
        int maxZ=Math.addExact(Math.multiplyExact(Math.addExact(center.z(),1),16),15);
        return completeCubeMinimum(generator.minKnownYForBox(minX,minZ,maxX,maxZ));
    }
    private static int completeCubeMinimum(int y){return (int)-Math.floorDiv(-(long)y,16);}
    private static String text(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new IllegalArgumentException("Required explicit field " + field);
        return value.getAsString();
    }
    private static String canonical(com.google.gson.JsonElement element) {
        if (element.isJsonObject()) {
            var sorted = new TreeMap<String, com.google.gson.JsonElement>(); element.getAsJsonObject().entrySet().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            var parts = new ArrayList<String>(); sorted.forEach((key, value) -> parts.add(new com.google.gson.JsonPrimitive(key) + ":" + canonical(value)));
            return "{" + String.join(",", parts) + "}";
        }
        if (element.isJsonArray()) { var parts = new ArrayList<String>(); element.getAsJsonArray().forEach(e -> parts.add(canonical(e))); return "[" + String.join(",", parts) + "]"; }
        return element.toString();
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    /** SHA256 of sorted resource paths: BE int pathByteLength/pathUTF8/int classByteLength/classBytes. */
    public static String actualAlgorithmSha256() {
        try {
            var classes = new TreeMap<String, Class<?>>();
            for (var type : List.of(ParametricTerrainSampler.class, TerrainParameters.class, TerrainSample.class,
                    TerrainMaterial.class, TrailNetwork.class, TrailOverlay.class)) collect(type, classes);
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            for (var entry : classes.entrySet()) {
                byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] body;
                try (InputStream input = entry.getValue().getResourceAsStream("/" + entry.getKey())) {
                    if (input == null) throw new IOException("Missing actual algorithm class " + entry.getKey());
                    body = input.readAllBytes();
                }
                out.writeInt(name.length); out.write(name); out.writeInt(body.length); out.write(body);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("Cannot fingerprint actual algorithm classes", failure); }
    }
    private static void collect(Class<?> type, Map<String, Class<?>> classes) {
        classes.put(type.getName().replace('.', '/') + ".class", type);
        for (Class<?> nested : type.getDeclaredClasses()) collect(nested, classes);
    }
    /** Independent version boundary: adding habitat content never changes the geological identity. */
    public static String actualHabitatAlgorithmSha256() {
        try {
            var classes = new TreeMap<String, Class<?>>();
            for (var type : List.of(HabitatOverlay.class,HabitatBlock.class,HabitatBlockStates.class)) collect(type,classes);
            // javac emits anonymous enum switch maps outside getDeclaredClasses(). Include those too.
            var roots = List.of(HabitatOverlay.class,HabitatBlock.class,HabitatBlockStates.class);
            var resources = new TreeMap<String,byte[]>();
            for (var entry : classes.entrySet()) try (var in=entry.getValue().getResourceAsStream("/"+entry.getKey())) {
                if(in==null) throw new IOException("Missing habitat class "+entry.getKey()); resources.put(entry.getKey(),in.readAllBytes());
            }
            for(var type:roots) for(int i=1;i<=32;i++) {
                String name=type.getName().replace('.','/')+"$"+i+".class";
                try(var in=type.getResourceAsStream("/"+name)){if(in!=null)resources.put(name,in.readAllBytes());}
            }
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            for(var entry:resources.entrySet()) {byte[] name=entry.getKey().getBytes(StandardCharsets.UTF_8);out.writeInt(name.length);out.write(name);out.writeInt(entry.getValue().length);out.write(entry.getValue());}
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(IOException | java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("Cannot fingerprint habitat classes",failure);}
    }
    public static String actualLandscapeAlgorithmSha256() {
        try {
            var classes=new TreeMap<String,Class<?>>();
            var roots=List.of(LandscapeOverlay.class,LandscapeRecipeCodec.class,HabitatBlock.class,HabitatBlockStates.class);
            for(var type:roots)collect(type,classes);
            var resources=new TreeMap<String,byte[]>();
            for(var entry:classes.entrySet())try(var in=entry.getValue().getResourceAsStream("/"+entry.getKey())) {
                if(in==null)throw new IOException("Missing landscape class");resources.put(entry.getKey(),in.readAllBytes());
            }
            for(var type:roots)for(int i=1;i<=32;i++) {
                String name=type.getName().replace('.','/')+"$"+i+".class";
                try(var in=type.getResourceAsStream("/"+name)){if(in!=null)resources.put(name,in.readAllBytes());}
            }
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            for(var entry:resources.entrySet()){byte[] name=entry.getKey().getBytes(StandardCharsets.UTF_8);out.writeInt(name.length);out.write(name);out.writeInt(entry.getValue().length);out.write(entry.getValue());}
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }catch(IOException|java.security.NoSuchAlgorithmException ex){throw new IllegalStateException("Cannot fingerprint landscape",ex);}
    }
    public LandscapeOverlay landscape(){return landscape;}
    public NaturalGeneration natural(){return natural;}
    public WildernessGeneration wilderness(){return wilderness;}
    public CaveExpansionGeneration caveExpansion(){return caveExpansion;}
    public static String actualNaturalAlgorithmSha256(){
        return dev.madeinabyss.kernel.lod.TerrainLodDiskCache.codeIdentity(CliffParameters.class,CliffTerrainSampler.class,
                GlobalEcologyOverlay.class,NaturalBlock.class,NaturalProtection.class,NaturalGeneration.class,NaturalBlockStates.class);
    }
    public HabitatOverlay habitats() { return habitats; }
    public String id() { return id; }
    long derivedAlexEcologySeed(){return derivedAlexEcologySeed;}
    public String fingerprint() { return fingerprint; }
    public String generatorId() { return "madeinabyss:generated_shelf_" + fingerprint; }
    public Path relativeStoreRoot() { return Path.of("mia-generated-preview", fingerprint, "overworld"); }
    public ParameterizedTerrainChunkGenerator generator() { return generator; }
    public BlockCoordinate feet() { return new BlockCoordinate(ground.x(), Math.addExact(ground.y(), 1), ground.z()); }
    public JsonObject status() {
        var out = new JsonObject(); out.addProperty("profileId", id); out.addProperty("fingerprint", fingerprint);
        if(wilderness!=null)out.add("wilderness",wilderness.status());
        if(caveExpansion!=null)out.add("caveExpansion",caveExpansion.status());
        if(generator.islandSurface()!=null)out.add("islandSurface",generator.islandSurface().status());
        if(generator.upperShaftOpening()!=null)out.add("upperShaftOpening",generator.upperShaftOpening().status());
        if(generator.livingAbyss()!=null)out.add("livingAbyss",generator.livingAbyss().status());
        out.addProperty("algorithmRevision", algorithmRevision); out.addProperty("declaredLowerY", parameters.cavity().declaredLowerBoundY());
        out.addProperty("actualAlgorithmSha256", actualAlgorithmSha256());
        out.addProperty("minimumKnownCubeY", minimumKnownCubeY());
        if(habitats!=null) {
            var ecology=new com.google.gson.Gson().toJsonTree(habitats.summary()).getAsJsonObject();
            // State packets have an 8192-character limit. Full per-candidate rejection data
            // stays in the local generation report; it must not prevent scene admission.
            ecology.remove("rejections");
            ecology.addProperty("rejectionCount",habitats.summary().rejections().size());
            ecology.addProperty("provenance","ORIGINAL; visual review pending");
            ecology.addProperty("algorithmSha256",actualHabitatAlgorithmSha256());out.add("habitats",ecology);
        }
        if(landscape!=null) {
            var detail=new JsonObject();detail.addProperty("writes",landscape.placements().size());
            detail.addProperty("dataSha256",landscapeDataHash);detail.addProperty("algorithmSha256",actualLandscapeAlgorithmSha256());
            detail.addProperty("provenance","ORIGINAL; visual review pending");out.add("landscape",detail);
        }
        if(natural!=null){var detail=new JsonObject();detail.addProperty("algorithmSha256",actualNaturalAlgorithmSha256());
            detail.addProperty("scope","Global known first-layer walls and supported ledges plus island surfaces; protected routes/legacy landscape patches; unknown lower layers untouched");
            detail.addProperty("cachedTreePlans",natural.ecology().cachedPlanCount());detail.addProperty("provenance","ORIGINAL; visual review pending");out.add("naturalization",detail);}
        out.addProperty("provenance", "RECONSTRUCTION; creative local preview, not continuous canonical world"); out.addProperty("trailRoutes", trailCount);
        var anchor = new com.google.gson.JsonArray(); anchor.add(feet().x()); anchor.add(feet().y()); anchor.add(feet().z()); out.add("anchor", anchor); return out;
    }
}
