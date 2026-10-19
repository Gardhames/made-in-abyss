package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Bounded opt-in composed-generator audit; no level writes or player-save reads. */
public final class AlexEcologyDiagnostics {
    private AlexEcologyDiagnostics() {}
    private record Region(String name,int x,int y,int z) {}
    private static final List<Region> REGIONS=List.of(new Region("ledge",80,-556,-429),
            new Region("west",-569,-884,100),new Region("portal226",305,-601,-462),new Region("pond",376,-608,-222));
    private static final Set<String> FLORA=Set.of("pewen_pines","underweed","curly_fern","cycad","archaic_vine","archaic_vine_plant");
    private record Camera(BlockPos feet) {}
    public static void export(GeneratedPreviewProfile profile,GeneratedPreviewProfile baseline,LevelReader delegate,Path directory) throws Exception {
        long started=System.nanoTime(); var generator=profile.generator();var old=baseline.generator();
        LevelReader view=generatedView(generator,delegate,Math.multiplyExact(profile.minimumKnownCubeY(),16));
        var doc=new JsonObject();doc.addProperty("schema",1);doc.addProperty("profileId",profile.id());doc.addProperty("fingerprint",profile.fingerprint());
        doc.addProperty("baselineFingerprint",baseline.fingerprint());doc.addProperty("provenance","ORIGINAL");
        doc.addProperty("baselineProfileId",baseline.id());doc.addProperty("baselineStoreRoot",baseline.relativeStoreRoot().toString());doc.addProperty("storeRoot",profile.relativeStoreRoot().toString());
        doc.addProperty("scope","Bounded composed native BlockState audit using signed generator LevelReader; no native-client/runtime or whole-world acceptance");
        var regions=new JsonArray();var counts=new TreeMap<String,Long>();var changes=new TreeMap<String,Long>();
        var specimens=new TreeMap<String,BlockPos>();var specimenStates=new TreeMap<String,String>();var specimenChanged=new TreeMap<String,Boolean>();var failures=new JsonArray();
        var cameras=new ArrayList<Camera>();long sampled=0,unknown=0,floraChecked=0,logsChecked=0,waterBefore=0,waterRetained=0;
        for(var region:REGIONS) {
            var grid=new JsonArray();var regionJson=new JsonObject();regionJson.addProperty("name",region.name());
            int minX=region.x()-24,minY=region.y()-20,minZ=region.z()-24;
            regionJson.add("min",xyz(new BlockPos(minX,minY,minZ)));regionJson.add("size",new Gson().toJsonTree(new int[]{48,40,48}));
            long regionChanged=0;var regionCounts=new TreeMap<String,Long>();
            for(int x=minX;x<minX+48;x++)for(int z=minZ;z<minZ+48;z++) {
                BlockPos floorCandidate=null;String floorId=null;var plants=new JsonArray();
                for(int y=minY;y<minY+40;y++) {
                    var pos=new BlockPos(x,y,z);sampled++;
                    BlockState state,before;
                    try { state=generator.sampleBlock(x,y,z);before=old.sampleBlock(x,y,z); }
                    catch(TerrainUnavailableException unavailable){unknown++;continue;}
                    String id=id(state);boolean changed=!state.equals(before);
                    if(!before.getFluidState().isEmpty()){waterBefore++;if(state.equals(before))waterRetained++;else failure(failures,pos,"baseline fluid changed",state);}
                    if(changed){regionChanged++;changes.merge(id,1L,Long::sum);}
                    if(id.startsWith("alexscaves:")) {
                        counts.merge(id,1L,Long::sum);regionCounts.merge(id,1L,Long::sum);
                        if(!specimens.containsKey(id)||(changed&&!specimenChanged.get(id))){specimens.put(id,pos);specimenStates.put(id,state.toString());specimenChanged.put(id,changed);}
                        String name=id.substring("alexscaves:".length());
                        if(FLORA.contains(name)) {
                            floraChecked++;if(!state.canSurvive(view,pos)) failure(failures,pos,"canSurvive=false",state);
                            var plant=new JsonObject();plant.addProperty("y",y);plant.addProperty("category",name);plant.addProperty("changed",changed);plants.add(plant);
                        }
                        if((name.equals("pewen_log")||name.equals("thornwood_log"))&&changed) {
                            logsChecked++;if(!generator.sampleBlock(x,y-1,z).isCollisionShapeFullBlock(view,pos.below()))failure(failures,pos,"new log lacks full support",state);
                        }
                    }
                    // True two-air clearance excludes even noncolliding plants and fluids.
                    if(state.isAir()&&generator.sampleBlock(x,y+1,z).isAir()) {
                        var floor=generator.sampleBlock(x,y-1,z);
                        if(floor.isCollisionShapeFullBlock(view,pos.below())) {
                            cameras.add(new Camera(pos));
                            if(floorCandidate==null||Math.abs(y-region.y())<Math.abs(floorCandidate.getY()+1-region.y())){floorCandidate=pos.below();floorId=id(floor);}
                        }
                    }
                }
                var cell=new JsonObject();cell.addProperty("x",x);cell.addProperty("z",z);
                if(floorCandidate!=null){cell.addProperty("floorY",floorCandidate.getY());cell.addProperty("floorBlock",floorId);}else cell.add("floorY",JsonNull.INSTANCE);
                cell.add("plants",plants);grid.add(cell);
            }
            regionJson.addProperty("changedVoxels",regionChanged);regionJson.add("authorCounts",new Gson().toJsonTree(regionCounts));regionJson.add("floorPlantGrid",grid);regions.add(regionJson);
        }
        var specimensJson=new JsonObject();
        for(var entry:specimens.entrySet()) {
            var value=new JsonObject();value.add("position",xyz(entry.getValue()));value.addProperty("state",specimenStates.get(entry.getKey()));value.addProperty("changedFromBaseline",specimenChanged.get(entry.getKey()));
            var nearest=cameras.stream().filter(c->distance(c.feet(),entry.getValue())<=32L*32)
                    .min(Comparator.comparingLong(c->distance(c.feet(),entry.getValue())));
            if(nearest.isPresent())value.add("supportedCamera",center(nearest.get().feet()));else value.add("supportedCamera",JsonNull.INSTANCE);
            specimensJson.add(entry.getKey(),value);
        }
        var regionCameras=new JsonObject();
        for(var r:REGIONS) {
            var target=new BlockPos(r.x(),r.y(),r.z());
            var nearest=cameras.stream().filter(c->distance(c.feet(),target)<=32L*32).min(Comparator.comparingLong(c->distance(c.feet(),target)));
            regionCameras.add(r.name(),nearest.isPresent()?center(nearest.get().feet()):JsonNull.INSTANCE);
        }
        doc.add("regions",regions);doc.add("authorCounts",new Gson().toJsonTree(counts));doc.add("changedToCounts",new Gson().toJsonTree(changes));
        doc.add("specimens",specimensJson);doc.add("cameras",regionCameras);doc.add("failures",failures);
        doc.addProperty("sampledUniqueVoxels",sampled);doc.addProperty("unknownVoxels",unknown);doc.addProperty("floraSurvivalChecked",floraChecked);doc.addProperty("newLogsFullFloorChecked",logsChecked);
        doc.addProperty("baselineFluidVoxels",waterBefore);doc.addProperty("exactFluidRetained",waterRetained);doc.addProperty("elapsedMs",(System.nanoTime()-started)/1_000_000.0);
        Files.createDirectories(directory);Files.writeString(directory.resolve("ecology.json"),new GsonBuilder().setPrettyPrinting().create().toJson(doc));
        CaveExpansionDiagnostics.export(profile,directory);
        if(unknown>0||!failures.isEmpty())throw new IllegalStateException("Ecology audit failed: unknown="+unknown+", failures="+failures.size()+"; see "+directory.resolve("ecology.json"));
    }
    private static LevelReader generatedView(ParameterizedTerrainChunkGenerator generator,LevelReader delegate,int minY) {
        int maxY=Math.max(delegate.getMaxBuildHeight(),Math.addExact(generator.getMinY(),generator.getGenDepth()));
        return (LevelReader)Proxy.newProxyInstance(LevelReader.class.getClassLoader(),new Class<?>[]{LevelReader.class},(proxy,method,args)-> {
            String name=method.getName();
            if((name.equals("getBlockState")||name.equals("getFluidState")||name.equals("isEmptyBlock"))&&args!=null&&args.length==1&&args[0] instanceof BlockPos p) {
                var state=generator.sampleBlock(p.getX(),p.getY(),p.getZ());
                if(name.equals("getFluidState"))return state.getFluidState();
                if(name.equals("isEmptyBlock"))return state.isAir();
                return state;
            }
            if(name.equals("getMinBuildHeight"))return minY;
            if(name.equals("getHeight")&&method.getParameterCount()==0)return Math.subtractExact(maxY,minY);
            if(name.equals("getMaxBuildHeight"))return maxY;
            if(name.equals("isOutsideBuildHeight")&&args!=null&&args.length==1) {
                int y=args[0] instanceof BlockPos p?p.getY():((Number)args[0]).intValue();return y<minY||y>=maxY;
            }
            try{return method.invoke(delegate,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }
    private static long distance(BlockPos a,BlockPos b){long x=(long)a.getX()-b.getX(),y=(long)a.getY()-b.getY(),z=(long)a.getZ()-b.getZ();return x*x+y*y+z*z;}
    private static String id(BlockState state){return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();}
    private static JsonArray xyz(BlockPos p){return new Gson().toJsonTree(new int[]{p.getX(),p.getY(),p.getZ()}).getAsJsonArray();}
    private static JsonArray center(BlockPos p){return new Gson().toJsonTree(new double[]{p.getX()+.5,p.getY(),p.getZ()+.5}).getAsJsonArray();}
    private static void failure(JsonArray array,BlockPos pos,String reason,BlockState state){var failure=new JsonObject();failure.add("position",xyz(pos));failure.addProperty("reason",reason);failure.addProperty("state",state.toString());array.add(failure);}
}
