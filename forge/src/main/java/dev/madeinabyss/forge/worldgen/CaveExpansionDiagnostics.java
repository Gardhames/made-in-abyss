package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.kernel.terrain.natural.SupportedCaveTerrainSampler.Foot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Explicit offline/test exporter. Never called by terrain generation or normal gameplay. */
public final class CaveExpansionDiagnostics {
    private CaveExpansionDiagnostics() {}
    private static final int[][] SIDES={{1,0},{-1,0},{0,1},{0,-1}};
    private static final Comparator<Foot> ORDER=Comparator.comparingInt(Foot::x).thenComparingInt(Foot::y).thenComparingInt(Foot::z);
    private record Checked(boolean valid,String reason,List<String> states) {}
    public static void export(GeneratedPreviewProfile profile,Path directory) throws IOException {
        var expansion=Objects.requireNonNull(profile.caveExpansion(),"cave expansion required");
        var generator=profile.generator();var checked=new HashMap<Foot,Checked>();
        var graph=new HashMap<Foot,Set<Foot>>();var networks=new HashMap<Foot,Integer>();
        var document=new JsonObject();document.addProperty("schema",1);document.addProperty("profileId",profile.id());
        document.addProperty("fingerprint",profile.fingerprint());document.addProperty("provenance","ORIGINAL");
        document.addProperty("coordinateConvention","centered X/Z; Y is feet air voxel; support is Y-1");
        document.addProperty("verificationScope","Actual composed native BlockStates offline; no client collision or whole-graph connectivity claim");
        var routes=new JsonArray();int index=0,invalid=0;
        for(var route:expansion.caves().routes()) {
            var json=new JsonObject();json.addProperty("id",route.network()+"-"+index++);json.addProperty("network",route.network());
            json.addProperty("vertical",route.vertical());var points=new JsonArray();var failures=new JsonArray();Foot previous=null;
            for(var f:route.feet()) {
                points.add(point(f));var c=checked.computeIfAbsent(f,q->check(generator,q));
                if(!c.valid()) {var failure=new JsonObject();failure.add("point",point(f));failure.addProperty("reason",c.reason());failure.add("states",new Gson().toJsonTree(c.states()));failures.add(failure);invalid++;}
                else {graph.computeIfAbsent(f,q->new TreeSet<>(ORDER));networks.putIfAbsent(f,route.network());}
                if(previous!=null&&c.valid()&&checked.get(previous).valid()&&adjacent(previous,f)) connect(graph,previous,f);
                previous=f;
            }
            json.add("points",points);json.add("failures",failures);json.addProperty("collisionVerified",failures.isEmpty());routes.add(json);
        }
        // Join only actual verified adjacent foot cells. No invented diagonal or gap bridges.
        for(var f:graph.keySet())for(var d:SIDES)for(int dy=-1;dy<=1;dy++){
            var q=new Foot(f.x()+d[0],f.y()+dy,f.z()+d[1]);if(graph.containsKey(q))connect(graph,f,q);
        }
        var seen=new HashSet<Foot>();List<Foot> longest=List.of(),showcase=List.of();int components=0;
        var ordered=new ArrayList<>(graph.keySet());ordered.sort(ORDER);
        for(var start:ordered)if(!seen.contains(start)) {
            components++;var first=bfs(graph,start);seen.addAll(first.keySet());Foot far=farthest(first);
            var second=bfs(graph,far);var path=path(second,farthest(second));
            if(path.size()>longest.size())longest=path;
            if(path.stream().anyMatch(f->networks.getOrDefault(f,-1)==226)&&path.size()>showcase.size())showcase=path;
        }
        var preferred=showcase.size()>=31?showcase:longest;
        if(preferred.size()>=2){var json=new JsonObject();json.addProperty("id","verified-component-walk");json.addProperty("collisionVerified",true);
            json.addProperty("selection","two BFS sweeps within a verified component; approximate diameter, not global longest-path claim");
            var points=new JsonArray();for(var f:preferred)points.add(point(f));json.add("points",points);routes.add(json);document.addProperty("preferredRoute","verified-component-walk");}
        document.add("routes",routes);document.addProperty("sourceRouteCount",expansion.caves().routes().size());
        document.addProperty("uniqueVerifiedFeet",graph.size());document.addProperty("invalidRoutePointOccurrences",invalid);
        document.addProperty("verifiedComponents",components);document.addProperty("preferredRouteSteps",Math.max(0,preferred.size()-1));
        var specimens=new JsonObject();var cameras=new JsonObject();var scanned=new HashSet<Foot>();int sampleCount=0;
        var wanted=List.of(225,226,227,228);
        // Bounded targeted hall/portal catalog. Absence remains explicit, not a fabricated coordinate.
        var centers=new ArrayList<Foot>();
        for(var p:expansion.caves().portals())if(wanted.contains(p.network()))centers.add(round(p.position().x(),p.position().y(),p.position().z()));
        for(var e:expansion.caves().segments())if(wanted.contains(e.network())&&e.a().equals(e.b())&&e.radius()>=4)centers.add(round(e.a().x(),e.a().y(),e.a().z()));
        for(var center:centers)for(int dy=-10;dy<=8;dy++)for(int dx=-10;dx<=10;dx++)for(int dz=-10;dz<=10;dz++){
            var f=new Foot(center.x()+dx,center.y()+dy,center.z()+dz);if(!scanned.add(f))continue;
            var state=generator.sampleBlock(f.x(),f.y(),f.z());sampleCount++;String id=id(state),kind=kind(id);
            if(kind!=null&&!specimens.has(kind)) {
                var specimen=new JsonObject();specimen.add("position",raw(f));specimen.addProperty("block",id);specimen.addProperty("state",state.toString());specimens.add(kind,specimen);
                var camera=nearest(graph.keySet(),f,32);if(camera!=null)cameras.add(kind,point(camera));
            }
            if(!cameras.has("interior")&&check(generator,f).valid()&&expansion.interiorFeature(f.x(),f.y(),f.z()).cave())cameras.add("interior",point(f));
        }
        var portals=new JsonArray();for(var p:expansion.caves().portals())if(wanted.contains(p.network())){
            var entry=new JsonObject();entry.addProperty("network",p.network());entry.add("position",new Gson().toJsonTree(new double[]{p.position().x(),p.position().y(),p.position().z()}));
            var nearest=nearest(graph.keySet(),round(p.position().x(),p.position().y(),p.position().z()),32);
            if(nearest!=null){entry.add("supportedCamera",point(nearest));if(!cameras.has("entrance"))cameras.add("entrance",point(nearest));}portals.add(entry);
        }
        document.add("portals",portals);document.add("specimens",specimens);document.add("cameras",cameras);
        document.add("specimenNetworks",new Gson().toJsonTree(wanted));document.addProperty("specimenSampledVoxels",sampleCount);
        document.addProperty("waterFound",specimens.has("water"));
        var missing=new JsonArray();for(var name:List.of("water","calcite","dripstone","moss","lichen"))if(!specimens.has(name))missing.add(name);document.add("missingSpecimens",missing);
        Files.createDirectories(directory);Files.writeString(directory.resolve("routes.json"),new GsonBuilder().setPrettyPrinting().create().toJson(document));
        if(invalid>0)throw new IllegalStateException("Composed cave route audit failed; "+invalid+" obstructed/unsupported points, see "+directory.resolve("routes.json"));
    }
    private static Checked check(ParameterizedTerrainChunkGenerator g,Foot f) {
        var states=new ArrayList<String>();boolean valid=true;String reason="";
        for(int dy=-1;dy<=2;dy++){var s=g.sampleBlock(f.x(),f.y()+dy,f.z());states.add(s.toString());
            boolean ok=dy==-1?s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO):s.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).isEmpty()&&s.getFluidState().isEmpty();
            if(!ok){valid=false;reason+=(dy==-1?"non-full support":"blocked/fluid clearance Y+"+dy)+";";}}
        return new Checked(valid,reason,List.copyOf(states));
    }
    private static void connect(Map<Foot,Set<Foot>> graph,Foot a,Foot b){graph.get(a).add(b);graph.get(b).add(a);}
    private static boolean adjacent(Foot a,Foot b){return Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z())==1&&Math.abs(a.y()-b.y())<=1;}
    private record Visit(Foot previous,int distance){}
    private static Map<Foot,Visit> bfs(Map<Foot,Set<Foot>> graph,Foot start){var visited=new LinkedHashMap<Foot,Visit>();var queue=new ArrayDeque<Foot>();visited.put(start,new Visit(null,0));queue.add(start);
        while(!queue.isEmpty()){var f=queue.remove();for(var next:graph.get(f))if(!visited.containsKey(next)){visited.put(next,new Visit(f,visited.get(f).distance()+1));queue.add(next);}}return visited;}
    private static Foot farthest(Map<Foot,Visit> visits){return visits.entrySet().stream().max(Comparator.comparingInt(e->e.getValue().distance())).orElseThrow().getKey();}
    private static List<Foot> path(Map<Foot,Visit> visits,Foot end){var result=new ArrayList<Foot>();for(Foot f=end;f!=null;f=visits.get(f).previous())result.add(f);Collections.reverse(result);return List.copyOf(result);}
    private static Foot nearest(Collection<Foot> feet,Foot target,int limit){return feet.stream().filter(f->distance(f,target)<=limit*limit).min(Comparator.<Foot>comparingLong(f->distance(f,target)).thenComparing(ORDER)).orElse(null);}
    private static long distance(Foot a,Foot b){long x=(long)a.x()-b.x(),y=(long)a.y()-b.y(),z=(long)a.z()-b.z();return x*x+y*y+z*z;}
    private static Foot round(double x,double y,double z){return new Foot((int)Math.round(x),(int)Math.round(y),(int)Math.round(z));}
    private static JsonArray point(Foot f){return new Gson().toJsonTree(new double[]{f.x()+.5,f.y(),f.z()+.5}).getAsJsonArray();}
    private static JsonArray raw(Foot f){return new Gson().toJsonTree(new int[]{f.x(),f.y(),f.z()}).getAsJsonArray();}
    private static String id(BlockState s){return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();}
    private static String kind(String id){if(id.equals("minecraft:water"))return "water";if(id.contains("calcite"))return "calcite";if(id.contains("dripstone"))return "dripstone";if(id.equals("minecraft:moss_block"))return "moss";if(id.equals("minecraft:glow_lichen"))return "lichen";return null;}
}
