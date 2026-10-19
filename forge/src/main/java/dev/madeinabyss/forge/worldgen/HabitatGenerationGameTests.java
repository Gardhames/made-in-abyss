package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.cube.StableCubeStore;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.*;
import dev.madeinabyss.kernel.terrain.TerrainParameters;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import dev.madeinabyss.kernel.terrain.habitat.HabitatBlock;
import dev.madeinabyss.kernel.terrain.habitat.HabitatOverlay;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded ORIGINAL habitat fixtures; no artistic or full-depth acceptance implied. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HabitatGenerationGameTests {
    private static final int[][] PATCHES={{-609,60,-884},{545,-160,-1128},{40,-469,-556}};

    @GameTest(template="empty",timeoutTicks=400)
    public static void denseHabitatAdmissionFitsActualNetworkStateBudget(GameTestHelper h) {
        var p=profile(h,manifest(true));var status=p.status();status.addProperty("generatedAccepted",true);
        var bytes=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            bytes.writeUtf(status.toString(),8192);
            var decoded=JsonParser.parseString(bytes.readUtf(8192)).getAsJsonObject();
            h.assertTrue(decoded.get("generatedAccepted").getAsBoolean(),"admission survives the actual state packet codec");
            h.assertTrue(decoded.getAsJsonObject("habitats").get("trees").getAsInt()==p.habitats().summary().trees(),
                    "wire summary preserves actual content counts");
        } finally {bytes.release();}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void groundCoverUsesNativeNonOccludingGrassOnComposedSoil(GameTestHelper h) {
        h.assertTrue(HabitatBlockStates.palette().contains(Blocks.GRASS.defaultBlockState()),
                "dense ground cover requires native one-block grass in the generated palette");
        var p=profile(h,manifest(true));int count=0;
        for(var v:p.habitats().placements())if(v.block().name().equals("TALL_GRASS")) {
            var actual=p.generator().sampleBlock(v.x(),v.y(),v.z());
            var below=p.generator().sampleBlock(v.x(),v.y()-1,v.z());
            h.assertTrue(actual.is(Blocks.GRASS)&&!actual.canOcclude(),"ground cover must retain native cutout state");
            h.assertTrue(below.is(Blocks.GRASS_BLOCK)||below.is(Blocks.DIRT),"grass needs composed soil, including after excavation");
            count++;
        }
        h.assertTrue(count>600,"three real measured plots carry substantial ground cover");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void recessedPondStreamContainsOrdinaryNativeWaterFlow(GameTestHelper h) {
        var overlay=HabitatOverlay.plan((x,y,z)->new TerrainSample.Known(y>0?TerrainMaterial.AIR:
                y==0?TerrainMaterial.GRASS:y>=-2?TerrainMaterial.SOIL:TerrainMaterial.ROCK),
                List.of(new HabitatOverlay.Patch("native-fluid",0,0,80,80,0,91)));
        var feature=overlay.placements().stream().filter(v->v.featureId().equals("native-fluid/pond-stream")).toList();
        var water=feature.stream().filter(v->v.block()==HabitatBlock.WATER).toList();
        h.assertTrue(!water.isEmpty(),"fixture has the real recessed pond and stream");
        int sourceMinX=water.stream().mapToInt(v->v.x()).min().orElseThrow()-3;
        int sourceMinZ=water.stream().mapToInt(v->v.z()).min().orElseThrow()-3;
        final int width=20,depth=20,height=10,groundOffset=4;
        // Distinct from the missing-native-halo fixture at (1_000_000,1_000_000).
        var origin=new BlockPos(900_016,250,-900_016);var level=h.getLevel();
        // Only this test owns the distant diagnostic volume; every original native state is restored.
        var before=new BlockState[width*depth*height];var intendedColumns=new HashSet<Long>();
        for(var v:water)intendedColumns.add(((long)(v.x()-sourceMinX)<<32)|(v.z()-sourceMinZ&0xffffffffL));
        for(int z=0;z<depth;z+=16)for(int x=0;x<width;x+=16)
            level.getChunk(Math.floorDiv(origin.getX()+x,16),Math.floorDiv(origin.getZ()+z,16));
        for(int y=0;y<height;y++)for(int z=0;z<depth;z++)for(int x=0;x<width;x++)
            before[(y*depth+z)*width+x]=level.getBlockState(origin.offset(x,y,z));
        try {
            for(int y=0;y<height;y++)for(int z=0;z<depth;z++)for(int x=0;x<width;x++) {
                boolean boundary=x==0||z==0||x==width-1||z==depth-1||y==0;
                level.setBlock(origin.offset(x,y,z),boundary?Blocks.STONE.defaultBlockState():
                        y<=groundOffset?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
            }
            for(var v:feature) {
                int x=v.x()-sourceMinX,z=v.z()-sourceMinZ,y=v.y()+groundOffset;
                h.assertTrue(x>0&&x<width-1&&z>0&&z<depth-1&&y>0&&y<height-1,"real feature fits bounded diagnostic volume");
                level.setBlock(origin.offset(x,y,z),HabitatBlockStates.state(v.block()),2);
            }
            // Invoke the actual native fluid evaluator in deterministic sequential sweeps. This
            // checks ordinary flow decisions, not elapsed server tick scheduling or cube fluid hooks.
            for(int pass=0;pass<12;pass++) {
                var tickPositions=new ArrayList<BlockPos>();
                for(int y=1;y<height-1;y++)for(int z=1;z<depth-1;z++)for(int x=1;x<width-1;x++) {
                    var pos=origin.offset(x,y,z);if(!level.getFluidState(pos).isEmpty())tickPositions.add(pos);
                }
                for(var pos:tickPositions)level.getFluidState(pos).tick(level,pos);
                for(int y=1;y<height-1;y++)for(int z=1;z<depth-1;z++)for(int x=1;x<width-1;x++) {
                    var pos=origin.offset(x,y,z);var state=level.getBlockState(pos);
                    if(!state.getFluidState().isEmpty())h.assertTrue(intendedColumns.contains(((long)x<<32)|(z&0xffffffffL)),
                            "native water escaped intended pond/channel XZ at pass="+pass+" local="+x+","+y+","+z);
                    if(state.is(Blocks.GRAVEL))h.assertTrue(!level.getBlockState(pos.below()).isAir()
                                    && level.getFluidState(pos.below()).isEmpty(),"native gravel bed/bank must have solid support");
                }
            }
        } finally {
            for(int y=0;y<height;y++)for(int z=0;z<depth;z++)for(int x=0;x<width;x++)
                level.setBlock(origin.offset(x,y,z),before[(y*depth+z)*width+x],2);
        }
        h.succeed();
    }

    private static JsonObject manifest(boolean habitats) {
        var parameters=new TerrainParameters(20261003,
                new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
                new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),new TerrainParameters.Noise(140,20,60,70));
        var out=new JsonObject();out.addProperty("profileId","test-habitat");out.addProperty("algorithmRevision","v5-test");
        out.addProperty("algorithmSha256",GeneratedPreviewProfile.actualAlgorithmSha256());
        out.add("parameters",TerrainParameterCodecs.PARAMETERS.encodeStart(JsonOps.INSTANCE,parameters).result().orElseThrow());
        var anchor=new JsonObject();anchor.addProperty("x",-590);anchor.addProperty("y",-888);anchor.addProperty("z",80);
        out.add("groundAnchor",anchor);
        if(habitats) {
            var ecology=new JsonObject();ecology.addProperty("algorithmSha256",GeneratedPreviewProfile.actualHabitatAlgorithmSha256());
            ecology.addProperty("provenance","ORIGINAL");var patches=new JsonArray();
            for(int i=0;i<PATCHES.length;i++) {
                var patch=new JsonObject();patch.addProperty("id","patch-"+i);patch.addProperty("minX",PATCHES[i][0]);
                patch.addProperty("minZ",PATCHES[i][1]);patch.addProperty("referenceY",PATCHES[i][2]);
                patch.addProperty("width",80);patch.addProperty("depth",80);patch.addProperty("seed",20261004L+i);patches.add(patch);
            }
            ecology.add("patches",patches);out.add("habitats",ecology);
        }
        return out;
    }
    private static GeneratedPreviewProfile profile(GameTestHelper h,JsonObject manifest) {
        return new GeneratedPreviewProfile(manifest,"test-habitat",new FixedBiomeSource(h.getLevel().registryAccess()
                .registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)));
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void unsavedHabitatPaletteHasCompiledNativeScalarLighting(GameTestHelper h) {
        var generator=profile(h,manifest(true)).generator();
        var source=new GeneratedSkySource(generator,Map.of()); // No saved states may seed this palette.
        for(var state:HabitatBlockStates.palette()) {
            h.assertTrue(!state.hasBlockEntity() && !state.useShapeForLightOcclusion(),"native habitat state is scalar-light compatible: "+state);
            int id=Block.getId(state);
            h.assertTrue(source.opacity(id)==state.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),"unsaved generated opacity compiled: "+state);
            h.assertTrue(source.emission(id)==state.getLightEmission(EmptyBlockGetter.INSTANCE,BlockPos.ZERO),"unsaved generated emission compiled: "+state);
        }
        for(var block:HabitatBlock.values())
            h.assertTrue(HabitatBlockStates.palette().contains(HabitatBlockStates.state(block)),"complete generator output palette: "+block);
        int leaf=source.opacity(Block.getId(HabitatBlockStates.state(HabitatBlock.LEAVES)));
        h.assertTrue(leaf>0 && leaf<15,"native leaves attenuate light without opaque LOD substitution");
        h.assertTrue(source.opacity(Block.getId(HabitatBlockStates.state(HabitatBlock.FLOWER)))==0,"native flower is not an opaque light obstacle");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void actualPatchPlacementsAgreeWithNativeSamplesAndBoundedCubeFill(GameTestHelper h) throws Exception {
        var p=profile(h,manifest(true));var overlay=p.habitats();
        h.assertTrue(overlay!=null && !overlay.placements().isEmpty(),"actual supported shelves accept habitat features");
        for(var v:overlay.placements()) {
            h.assertTrue(v.y()>=-1286 && v.y()<320,"habitats cannot cross declared known domain");
            h.assertTrue(!(Math.abs((long)v.x()-p.feet().x())<=3 && Math.abs((long)v.z()-p.feet().z())<=3
                    && v.y()>=p.feet().y()-4L && v.y()<=p.feet().y()+11L),"spawn ground and headroom remain protected");
        }
        var keys=new LinkedHashSet<CubeKey>();
        for(int[] patch:PATCHES) {
            var accepted=overlay.placements().stream().filter(v->v.x()>=patch[0] && v.x()<patch[0]+80
                    && v.z()>=patch[1] && v.z()<patch[1]+80).toList();
            h.assertTrue(!accepted.isEmpty(),"at least one accepted feature on actual patch "+Arrays.toString(patch));
            // Unsupported slopes may reject individual feature types; every accepted write must be exact.
            for(var v:accepted)h.assertTrue(p.generator().sampleBlock(v.x(),v.y(),v.z())
                    .equals(HabitatBlockStates.state(v.block())),"accepted native habitat write survives generator sampling");
            var selected=new LinkedHashSet<CubeKey>();
            for(var v:accepted) {
                if(selected.size()>=3)break;
                if(selected.isEmpty() || Math.floorMod(v.x(),16)==0 || Math.floorMod(v.x(),16)==15
                        || Math.floorMod(v.z(),16)==0 || Math.floorMod(v.z(),16)==15)
                    selected.add(CubeKey.fromBlock(new BlockCoordinate(v.x(),v.y(),v.z())));
            }
            keys.addAll(selected);
        }
        h.assertTrue(keys.size()<=9,"bounded fixture generates at most nine cubes");
        for(var key:keys) {
            int[] cells=p.generator().generateCube(key).states();
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
                h.assertTrue(cells[(y<<8)|(z<<4)|x]==Block.getId(p.generator().sampleBlock(key.x()*16+x,key.y()*16+y,key.z()*16+z)),
                        "cube fill equals sample across signed cube boundaries: "+key);
        }
        writeReport(p);
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void actualTreeCandidateCannotOverwriteVerifiedTrailCorridor(GameTestHelper h) {
        var populated=profile(h,manifest(true));var plain=profile(h,manifest(false));
        var treeRoot=populated.habitats().placements().stream().filter(v->v.block()==HabitatBlock.LOG)
                .min(Comparator.comparingInt(dev.madeinabyss.kernel.terrain.habitat.HabitatOverlay.Placement::y)).orElseThrow();
        int x=treeRoot.x(),z=treeRoot.z(),y=treeRoot.y()-1;int[] neighbor=null;
        for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(int rise=-1;rise<=1;rise++) {
            int nx=x+direction[0],nz=z+direction[1],ny=y+rise;
            if(plain.generator().sampleBlock(nx,ny,nz).is(Blocks.GRASS_BLOCK)
                    && plain.generator().sampleBlock(nx,ny+1,nz).isAir() && plain.generator().sampleBlock(nx,ny+2,nz).isAir())
                neighbor=new int[]{nx,ny,nz};
        }
        h.assertTrue(neighbor!=null,"actual accepted tree has a walkable adjacent exposed floor");
        var m=manifest(true);var trails=new JsonObject();var metrics=new JsonObject();
        metrics.addProperty("widthMetres",1);metrics.addProperty("supportDepthMetres",1);metrics.addProperty("headroomMetres",2);
        metrics.addProperty("maxRisePerMetre",1);metrics.addProperty("maxCutInsetMetres",1);trails.add("parameters",metrics);
        var points=new JsonArray();
        for(int[] point:new int[][]{{x,y,z},neighbor}) {var a=new JsonArray();for(int coordinate:point)a.add(coordinate);points.add(a);}
        var route=new JsonObject();route.addProperty("id","protected-tree-corridor");route.add("points",points);
        var routes=new JsonArray();routes.add(route);trails.add("routes",routes);m.add("trails",trails);
        var protectedProfile=profile(h,m);
        for(var v:protectedProfile.habitats().placements())for(int[] point:new int[][]{{x,y,z},neighbor})
            h.assertTrue(!(Math.abs((long)v.x()-point[0])<=2 && Math.abs((long)v.z()-point[2])<=2
                    && v.y()>=point[1]-1L && v.y()<=point[1]+12L),"verified trail floor/support/headroom excludes habitat writes");
        h.assertTrue(protectedProfile.generator().sampleBlock(x,y+1,z).isAir(),"old tree root becomes walkable trail headroom");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void habitatIdentityRejectsStaleHashAndPreservesExactLegacyFingerprint(GameTestHelper h) throws Exception {
        var oldManifest=manifest(false);var legacy=profile(h,oldManifest);String before=legacy.fingerprint();
        String oldInput="mia-generated-preview-v1\ntest-habitat\nv5-test\n"+GeneratedPreviewProfile.actualAlgorithmSha256()
                +"\n"+canonical(oldManifest.get("parameters"))+"\nno-trails";
        String expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(oldInput.getBytes(StandardCharsets.UTF_8)));
        h.assertTrue(before.equals(expected) && legacy.habitats()==null,"absent habitat uses exact historical v1 identity");
        var variant=profile(h,manifest(true));
        h.assertTrue(!variant.fingerprint().equals(before),"habitat content receives an independent visited store");
        h.assertTrue(profile(h,oldManifest).fingerprint().equals(before),"constructing habitat variant cannot mutate legacy identity");
        var stale=manifest(true);stale.getAsJsonObject("habitats").addProperty("algorithmSha256","0".repeat(64));
        try {profile(h,stale);h.fail("stale habitat algorithm must refuse");}catch(IllegalArgumentException expectedFailure){}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=400)
    public static void savedHabitatBuildRemainsAuthoritativeAndIsNotRewritten(GameTestHelper h) throws Exception {
        var p=profile(h,manifest(true));var key=CubeKey.fromBlock(p.feet());
        Path root=Path.of("diagnostics","habitat-stores",UUID.randomUUID().toString());
        var store=new StableCubeStore(root,p.generatorId(),1);int[] states=p.generator().generateCube(key).states();
        int index=(Math.floorMod(p.feet().y(),16)<<8)|(Math.floorMod(p.feet().z(),16)<<4)|Math.floorMod(p.feet().x(),16);
        states[index]=Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());store.write(new CubeSnapshot(key,states));
        byte[] before=Files.readAllBytes(store.path(key));
        var residency=new CubeResidencyService(store,k->{throw new AssertionError("saved habitat build must not invoke generator");},1,1,1);
        residency.move("habitat",p.feet(),0,0);
        h.assertTrue(Block.stateById(residency.space().block(p.feet()).orElseThrow()).is(Blocks.GOLD_BLOCK),"saved block wins over habitat plan");
        var light=new GeneratedSkySource(p.generator(),Map.of(key,states));
        h.assertTrue(light.stateId(p.feet().x(),p.feet().y(),p.feet().z())==Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()),"derived lighting uses saved override");
        var legacyStore=new StableCubeStore(root,profile(h,manifest(false)).generatorId(),1);
        try {legacyStore.read(key);h.fail("legacy profile cannot reinterpret habitat bytes");}catch(java.io.IOException expected){}
        h.assertTrue(Arrays.equals(before,Files.readAllBytes(store.path(key))),"admission and provenance refusal preserve saved bytes");
        residency.release("habitat");h.succeed();
    }

    private static String canonical(JsonElement element) {
        if(element.isJsonObject()) {
            var sorted=new TreeMap<String,JsonElement>();element.getAsJsonObject().entrySet().forEach(e->sorted.put(e.getKey(),e.getValue()));
            var out=new JsonObject();sorted.forEach((k,v)->out.add(k,JsonParser.parseString(canonical(v))));return out.toString();
        }
        if(element.isJsonArray()) {var out=new JsonArray();for(var v:element.getAsJsonArray())out.add(JsonParser.parseString(canonical(v)));return out.toString();}
        return element.toString();
    }

    private static void writeReport(GeneratedPreviewProfile profile) throws Exception {
        var overlay=profile.habitats();var summary=overlay.summary();var report=new JsonObject();
        report.addProperty("scope","bounded Forge generation API; runtime/visual pending");report.addProperty("fingerprint",profile.fingerprint());
        report.addProperty("habitatAlgorithmSha256",GeneratedPreviewProfile.actualHabitatAlgorithmSha256());
        var totals=new JsonObject();totals.addProperty("trees",summary.trees());totals.addProperty("flowers",summary.flowers());
        totals.addProperty("waterCells",summary.waterCells());totals.addProperty("structureCells",summary.structureCells());
        totals.addProperty("grass",overlay.placements().stream().filter(v->v.block()==HabitatBlock.TALL_GRASS).count());
        for(String kind:List.of("shrub-","rock-group-","microrelief-"))totals.addProperty(kind.replace("-",""),
                overlay.placements().stream().filter(v->v.featureId().contains("/"+kind)).map(HabitatOverlay.Placement::featureId).distinct().count());
        totals.addProperty("placementCells",summary.placementCount());totals.addProperty("sampledVoxels",summary.sampledVoxels());report.add("summary",totals);
        var patches=new JsonArray();
        for(int i=0;i<PATCHES.length;i++) {
            int[] bounds=PATCHES[i];var patch=new JsonObject();patch.addProperty("id","patch-"+i);
            var values=overlay.placements().stream().filter(v->v.x()>=bounds[0] && v.x()<bounds[0]+80 && v.z()>=bounds[1] && v.z()<bounds[1]+80).toList();
            patch.addProperty("acceptedCells",values.size());patch.addProperty("acceptedFeatures",values.stream().map(v->v.featureId()).distinct().count());
            patch.addProperty("grass",values.stream().filter(v->v.block()==HabitatBlock.TALL_GRASS).count());
            patch.addProperty("trees",values.stream().filter(v->v.featureId().contains("/tree-")).map(HabitatOverlay.Placement::featureId).distinct().count());
            var rejected=new JsonArray();for(var r:summary.rejections())if(r.patchId().equals("patch-"+i)) {
                var value=new JsonObject();value.addProperty("featureId",r.featureId());value.addProperty("reason",r.reason());rejected.add(value);
            }
            patch.add("rejections",rejected);var features=new JsonArray();
            var grouped=new TreeMap<String,List<dev.madeinabyss.kernel.terrain.habitat.HabitatOverlay.Placement>>();
            for(var v:values)grouped.computeIfAbsent(v.featureId(),k->new ArrayList<>()).add(v);
            for(var entry:grouped.entrySet()) {
                var feature=new JsonObject();feature.addProperty("id",entry.getKey());var cells=entry.getValue();feature.addProperty("cells",cells.size());
                var box=new JsonArray();box.add(cells.stream().mapToInt(v->v.x()).min().orElseThrow());box.add(cells.stream().mapToInt(v->v.y()).min().orElseThrow());
                box.add(cells.stream().mapToInt(v->v.z()).min().orElseThrow());box.add(cells.stream().mapToInt(v->v.x()).max().orElseThrow()+1);
                box.add(cells.stream().mapToInt(v->v.y()).max().orElseThrow()+1);box.add(cells.stream().mapToInt(v->v.z()).max().orElseThrow()+1);feature.add("boundsExclusive",box);
                for(var type:new HabitatBlock[]{HabitatBlock.LOG,HabitatBlock.WATER,HabitatBlock.FLOWER,HabitatBlock.COBBLE,HabitatBlock.TALL_GRASS,HabitatBlock.GRASS}) {
                    var representative=cells.stream().filter(v->v.block()==type).min(Comparator.comparingInt(v->v.y()));
                    representative.ifPresent(v->{var xyz=new JsonArray();xyz.add(v.x());xyz.add(v.y());xyz.add(v.z());feature.add(type.name().toLowerCase(Locale.ROOT)+"XYZ",xyz);
                        if(type==HabitatBlock.LOG){var floor=new JsonArray();floor.add(v.x());floor.add(v.y()-1);floor.add(v.z());feature.add("rootFloorXYZ",floor);}});
                }
                features.add(feature);
            }
            patch.add("features",features);patches.add(patch);
        }
        report.add("patches",patches);Path output=Path.of(System.getProperty("mia.storageRoot", ".local") + "/evidence/generator/habitat-native-generation.json");
        Files.createDirectories(output.getParent());Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report),StandardCharsets.UTF_8);
    }
}
