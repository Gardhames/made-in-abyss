package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.*;
import dev.madeinabyss.kernel.terrain.living.*;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;
import java.nio.file.*;
import java.util.*;

/** Paired same-coordinate cost audit, isolated batch, real generateCube in both versions. */
@PrefixGameTestTemplate(false)
public final class LivingAbyssCostGameTests {
    private static long checksum;
    public static void exportV15AndMeasureZonesAndArchetypesAgainstV14(GameTestHelper h) throws Exception {
        var m=LivingAbyssGameTests.manifest();long t=System.nanoTime();var v15=new GeneratedPreviewProfile(m,m.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        double buildMillis=(System.nanoTime()-t)/1e6;
        var parent=LivingAbyssGameTests.parentManifest();var v14=new GeneratedPreviewProfile(parent,parent.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));
        var living=v15.generator().livingAbyss();var sets=new LinkedHashMap<String,List<CubeKey>>();
        int[] depths={60,285,625,975,1250};
        for(int zone=0;zone<5;zone++){var keys=new TreeSet<CubeKey>(Comparator.comparingInt(CubeKey::x).thenComparingInt(CubeKey::y).thenComparingInt(CubeKey::z));
            for(int angle=0;angle<360;angle+=15){double a=Math.toRadians(angle);int x=(int)(650*Math.sin(a)),z=(int)(-650*Math.cos(a));double r=living.geometry().wallRadius(x,-depths[zone],z);
                x=(int)Math.round((r+2)*Math.sin(a));z=(int)Math.round(-(r+2)*Math.cos(a));add(keys,CubeKey.fromBlock(new BlockCoordinate(x,-depths[zone],z)),1);}
            sets.put("zone-"+zone,List.copyOf(keys));}
        var cameras=new JsonArray();
        for(var type:LivingAbyssPlan.Archetype.values()) {
            var keys=new LinkedHashSet<CubeKey>();int count=0;
            for(var q:living.plan().ledges())if(q.archetype()==type) {add(keys,CubeKey.fromBlock(new BlockCoordinate(q.x(),q.y(),q.z())),1);if(count++==0)cameras.add(camera(type.name().toLowerCase(Locale.ROOT).replace('_','-'),q.x(),q.y(),q.z()));if(count>=8)break;}
            sets.put("archetype-"+type.name().toLowerCase(Locale.ROOT),List.copyOf(keys));
        }
        double wet=living.hydrology().wetDegrees();
        var spireKeys=new LinkedHashSet<CubeKey>();int spireSamples=0;
        for(var q:living.spires().spires()){
            int y=(q.baseY()+q.topY())/2;int x=q.x()+(int)Math.round(q.radius()*.9);
            add(spireKeys,CubeKey.fromBlock(new BlockCoordinate(x,y,q.z())),1);if(++spireSamples>=24)break;
        }
        sets.put("spire",List.copyOf(spireKeys));
        var treeKeys=new LinkedHashSet<CubeKey>();int bigTrees=0;
        for(var tree:living.flora().trees())if(tree.kind().equals("giant")||tree.kind().equals("guiding")||tree.kind().equals("inverted")){
            add(treeKeys,CubeKey.fromBlock(new BlockCoordinate(tree.x(),tree.y(),tree.z())),1);
            add(treeKeys,CubeKey.fromBlock(new BlockCoordinate(tree.x(),tree.y()+(tree.kind().equals("inverted")?-24:30),tree.z())),1);
            if(++bigTrees>=24)break;
        }sets.put("large-tree",List.copyOf(treeKeys));
        int number=1;
        for(var type:List.of(LivingAbyssPlan.Archetype.MEADOW,LivingAbyssPlan.Archetype.GROVE,LivingAbyssPlan.Archetype.TERRACES,LivingAbyssPlan.Archetype.ROCK_GARDEN,LivingAbyssPlan.Archetype.RUIN,LivingAbyssPlan.Archetype.OVERHANG)){
            var q=living.plan().ledges().stream().filter(v->v.archetype()==type&&!v.id().equals("guiding-tree-ledge")).findFirst().orElseThrow();
            double a=Math.toRadians(q.theta());int x=q.x(),z=q.z(),y=surfaceY(living,q,x,z);
            cameras.add(look(String.format(Locale.ROOT,"s%02d-%s",number++,type.name().toLowerCase(Locale.ROOT).replace('_','-')),x-48*Math.sin(a)+24*Math.cos(a),y+32,z+48*Math.cos(a)+24*Math.sin(a),x,y,z,70));
        }
        var body=living.plan().ledges().stream().filter(q->q.archetype()==LivingAbyssPlan.Archetype.GROVE&&!q.added()).findFirst().orElseThrow();
        double ba=Math.toRadians(body.theta());var underside=living.geometry().surfaces(body.x(),body.z()).stream().filter(s->s.ledge().id().equals(body.id())).findFirst().orElseThrow();
        cameras.add(look("u01-underside",body.x()-35*Math.sin(ba),underside.bottomY()-22,body.z()+35*Math.cos(ba),body.x(),underside.bottomY(),body.z(),70));
        double br=living.geometry().wallBoundaryAt(body.theta(),body.y());int bx=(int)Math.round((br-3)*Math.sin(ba)),bz=(int)Math.round(-(br-3)*Math.cos(ba));
        int by=surfaceY(living,body,bx,bz)-35;
        cameras.add(look("u02-fillet",bx-36*Math.sin(ba)+36*Math.cos(ba),by-8,bz+36*Math.cos(ba)+36*Math.sin(ba),bx,by,bz,70));
        cameras.add(art("01-overview",0,100,wet,55));cameras.add(art("02-shelf-gardens",330,-300,wet,10));
        cameras.add(art("03-karst-belt",300,-610,wet+95,0));cameras.add(art("04-mist-falls",300,-850,wet+230,20));cameras.add(art("05-threshold-gate",300,-1230,wet+180,0));
        LivingAbyssSpirePlan.Spire hero=null;LivingAbyssPlan.Ledge terrace=null;double pairScore=Double.POSITIVE_INFINITY;
        for(var spire:living.spires().spires())for(var ledge:living.plan().ledges())if(ledge.archetype()==LivingAbyssPlan.Archetype.TERRACES){
            double lr=Math.hypot(ledge.x(),ledge.z()),sr=Math.hypot(spire.x(),spire.z());
            if(ledge.y()+4<=spire.topY()||lr-35>=sr-5)continue;
            double score=Math.hypot(ledge.x()-spire.x(),ledge.z()-spire.z())+(spire.zone()==1?0:1000);
            if(score<pairScore){pairScore=score;hero=spire;terrace=ledge;}}
        if(hero==null||terrace==null)throw new IllegalStateException("No terrace above a visible outward-facing spire camera target");
        double tr=Math.hypot(terrace.x(),terrace.z());
        cameras.add(look("r01-edge-down",terrace.x()/tr*(tr-12),terrace.y()+4,terrace.z()/tr*(tr-12),hero.x(),hero.baseY()-40,hero.z(),75));
        cameras.add(look("r02-terrace-spire",terrace.x()/tr*(tr-35),terrace.y()+25,terrace.z()/tr*(tr-35),hero.x(),(hero.topY()+hero.baseY())/2.0,hero.z(),70));
        var groups=new TreeMap<Integer,List<LivingAbyssSpirePlan.Spire>>();for(var q:living.spires().spires())groups.computeIfAbsent(q.group(),id->new ArrayList<>()).add(q);
        var group=groups.values().stream().filter(q->q.size()>=2).max(Comparator.comparingInt(List::size)).orElseThrow();
        double gx=group.stream().mapToInt(LivingAbyssSpirePlan.Spire::x).average().orElseThrow(),gz=group.stream().mapToInt(LivingAbyssSpirePlan.Spire::z).average().orElseThrow();
        cameras.add(look("r03-spire-group-down",gx*.8,group.stream().mapToInt(LivingAbyssSpirePlan.Spire::topY).max().orElseThrow()+110,gz*.8,gx,group.stream().mapToInt(LivingAbyssSpirePlan.Spire::baseY).average().orElseThrow()+20,gz,70));
        double wa=Math.toRadians(wet+35);int wx=(int)(650*Math.sin(wa)),wz=(int)(-650*Math.cos(wa));double wr=living.geometry().radius(wx,-330,wz)-70;
        cameras.add(art("wall-medium-close",(int)wr,-330,wet+35,0));
        var deepOnly=new LinkedHashSet<CubeKey>();
        for(var e:sets.entrySet()){
            var paired=new ArrayList<CubeKey>();for(var key:e.getValue()){
                if(!v15.generator().supportsCube(key))continue;
                if(v14.generator().supportsCube(key))paired.add(key);else deepOnly.add(key);
            }e.setValue(List.copyOf(paired));
        }
        var all=new LinkedHashSet<CubeKey>();sets.values().forEach(all::addAll);
        // Independent warm-up cubes, then one common warm-up pass to avoid JIT-order bias.
        for(int i=0;i<80;i++){var key=new CubeKey(28+i%10,-38-i%3,-5+i%7);consume(v14.generator().generateCube(key));consume(v15.generator().generateCube(key));}
        for(var key:all){consume(v14.generator().generateCube(key));consume(v15.generator().generateCube(key));}
        for(var key:deepOnly)consume(v15.generator().generateCube(key));
        var results=new JsonObject();var all14=new ArrayList<Double>();var all15=new ArrayList<Double>();var wall14=new ArrayList<Double>();var wall15=new ArrayList<Double>();
        int order=0;
        for(var e:sets.entrySet()) {
            var old=new ArrayList<Double>();var current=new ArrayList<Double>();
            for(int pass=0;pass<2;pass++)for(var key:e.getValue()) {
                if((order++&1)==0){old.add(time(v14.generator(),key));current.add(time(v15.generator(),key));}
                else{current.add(time(v15.generator(),key));old.add(time(v14.generator(),key));}
            }
            var item=new JsonObject();item.add("v14",summary(old));item.add("v15",summary(current));item.addProperty("ratio",mean(current)/mean(old));results.add(e.getKey(),item);
            System.out.println("MIA_V15_COST_SET "+e.getKey()+" "+item);
            if(e.getKey().startsWith("archetype")){all14.addAll(old);all15.addAll(current);}
            if(e.getKey().startsWith("zone")){wall14.addAll(old);wall15.addAll(current);}
        }
        var deepTimes=new ArrayList<Double>();for(int pass=0;pass<2;pass++)for(var key:deepOnly)deepTimes.add(time(v15.generator(),key));
        results.add("deep-tree-v15-only",summary(deepTimes));
        var out=new JsonObject();out.addProperty("profileBuildMillis",buildMillis);out.addProperty("hydrologyMillis",living.hydrology().buildNanos()/1e6);out.addProperty("hydrologyEstimatedBytes",living.hydrology().estimatedBytes());
        out.addProperty("catalogueMillis",living.plan().buildNanos()/1e6);out.addProperty("fingerprint",v15.fingerprint());out.addProperty("parentFingerprint",v14.fingerprint());out.add("cubeMillis",results);
        out.add("ledgeV14",summary(all14));out.add("ledgeV15",summary(all15));out.addProperty("ledgeMeanRatio",mean(all15)/mean(all14));out.addProperty("checksum",checksum);
        out.add("wallV14",summary(wall14));out.add("wallV15",summary(wall15));out.addProperty("wallMeanRatio",mean(wall15)/mean(wall14));
        out.addProperty("airCertificateBuildMillis",living.airCertificateBuildNanos()/1e6);out.addProperty("airCertificateCubes",living.airCertificate().certifiedCubeCount());out.addProperty("airCertificateBytes",living.airCertificate().residentBytes());
        var detail=new JsonObject();detail.add("summary",living.status());detail.add("ledges",new Gson().toJsonTree(living.plan().ledges()));detail.add("neighbours",new Gson().toJsonTree(living.plan().neighbours()));detail.add("conflicts",new Gson().toJsonTree(living.plan().conflicts()));
        detail.add("hydrology",new Gson().toJsonTree(living.hydrology()));
        detail.add("spires",new Gson().toJsonTree(living.spires().spires()));detail.add("spireAdmission",new Gson().toJsonTree(living.spires().admissionStats()));detail.add("mossClumps",new Gson().toJsonTree(living.green().clumps()));
        detail.add("trees",new Gson().toJsonTree(living.flora().trees()));
        detail.add("caveMouths",new Gson().toJsonTree(living.caveMouths()));
        if(living.water()!=null)detail.add("water",new Gson().toJsonTree(living.water().statistics()));
        var dir=Path.of(System.getProperty("mia.bench.v15Output",System.getProperty("mia.storageRoot", ".local") + "/codex/20261013-landscape-v15/part1"));Files.createDirectories(dir);
        Files.writeString(dir.resolve("profile.json"),m.toString());Files.writeString(dir.resolve("plan.json"),new GsonBuilder().setPrettyPrinting().create().toJson(detail));
        Files.writeString(dir.resolve("benchmark.json"),new GsonBuilder().setPrettyPrinting().create().toJson(out));Files.writeString(dir.resolve("cameras.json"),new GsonBuilder().setPrettyPrinting().create().toJson(cameras));
        System.out.println("MIA_V15_COST "+out);
        if(Boolean.getBoolean("mia.bench.v15GreenCoverage"))System.out.println("MIA_V15_GREEN "+LivingAbyssGreenCoverageAudit.measure(v15.generator(),dir));
        if(Boolean.getBoolean("mia.bench.v15ReferenceOnly")){h.succeed();return;}
        h.assertTrue(mean(all15)/mean(all14)<=1.5,"paired ledge mean <=1.5x v14");h.assertTrue(percentile(all15,.95)<25,"v15 ledge p95 <25ms");
        h.assertTrue(results.getAsJsonObject("large-tree").getAsJsonObject("v15").get("p95").getAsDouble()<25,"large-tree cube p95 <25ms");
        h.assertTrue(percentile(deepTimes,.95)<25,"deep-tree v15-only cube p95 <25ms; v14 is explicitly unavailable here");
        h.assertTrue(mean(wall15)/mean(wall14)<=2,"all-wall mean <=2x v14; <=1.5 target, 1.5..2 accepted if every zone p95<25ms");
        for(var e:results.entrySet())if(e.getKey().startsWith("zone")||e.getKey().equals("spire")){
            var value=e.getValue().getAsJsonObject();
            if(e.getKey().equals("spire"))h.assertTrue(value.getAsJsonObject("v15").get("mean").getAsDouble()<=8,"spire mean <=8ms (v14 was almost empty)");
            h.assertTrue(value.getAsJsonObject("v15").get("p95").getAsDouble()<25,e.getKey()+" p95 <25ms");}
        h.succeed();
    }
    private static int surfaceY(LivingAbyssGeneration living,LivingAbyssPlan.Ledge ledge,int x,int z){return living.geometry().surfaces(x,z).stream().filter(s->s.ledge().id().equals(ledge.id())).mapToInt(LivingAbyssGeometry.Surface::topY).findFirst().orElse(ledge.y());}
    private static JsonObject look(String label,double x,double y,double z,double tx,double ty,double tz,int fov){var o=new JsonObject();o.addProperty("label",label);var pos=new JsonArray();pos.add(x);pos.add(y);pos.add(z);o.add("position",pos);
        var target=new JsonArray();target.add(tx);target.add(ty);target.add(tz);o.add("target",target);
        o.addProperty("yaw",Math.toDegrees(Math.atan2(-(tx-x),tz-z)));o.addProperty("pitch",Math.toDegrees(Math.atan2(-(ty-y),Math.hypot(tx-x,tz-z))));o.addProperty("fov",fov);return o;}
    private static JsonObject camera(String label,int x,int y,int z){double r=Math.hypot(x,z),theta=LivingAbyssFields.azimuth(x,z);var o=new JsonObject();o.addProperty("label",label);var p=new JsonArray();p.add(x/r*Math.max(0,r-35));p.add(y+25);p.add(z/r*Math.max(0,r-35));o.add("position",p);o.addProperty("yaw",theta-180);o.addProperty("pitch",35);o.addProperty("fov",70);return o;}
    private static JsonObject art(String label,int radius,int y,double theta,double pitch){var o=new JsonObject();o.addProperty("label",label);double a=Math.toRadians(theta);var p=new JsonArray();p.add(radius*Math.sin(a));p.add(y);p.add(-radius*Math.cos(a));o.add("position",p);o.addProperty("yaw",LivingAbyssFields.normalize(theta)-180);o.addProperty("pitch",pitch);o.addProperty("fov",70);return o;}
    private static void add(Collection<CubeKey> out,CubeKey k,int radius){for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++)for(int y=-1;y<=1;y++)out.add(new CubeKey(k.x()+x,k.y()+y,k.z()+z));}
    private static void consume(CubeSnapshot s){var v=s.states();checksum^=v[0]+(long)v[2048]*31+v[4095];}
    private static double time(ParameterizedTerrainChunkGenerator g,CubeKey k){long t=System.nanoTime();var cube=g.generateCube(k);double ms=(System.nanoTime()-t)/1e6;consume(cube);return ms;}
    private static double mean(List<Double> a){return a.stream().mapToDouble(Double::doubleValue).average().orElse(0);}
    private static double percentile(List<Double> a,double q){var b=new ArrayList<>(a);Collections.sort(b);return b.isEmpty()?0:b.get(Math.min(b.size()-1,(int)Math.ceil(q*b.size())-1));}
    private static JsonObject summary(List<Double> a){var o=new JsonObject();o.addProperty("count",a.size());o.addProperty("mean",mean(a));o.addProperty("p95",percentile(a,.95));o.addProperty("max",a.stream().mapToDouble(Double::doubleValue).max().orElse(0));return o;}
}
