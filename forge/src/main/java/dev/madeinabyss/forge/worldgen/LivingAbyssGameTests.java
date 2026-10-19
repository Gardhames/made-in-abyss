package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.probe.deep.*;
import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraftforge.gametest.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import dev.madeinabyss.kernel.terrain.living.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LivingAbyssGameTests {
    private static GeneratedPreviewProfile cached;
    static synchronized GeneratedPreviewProfile profile(GameTestHelper h) throws Exception {
        if(cached==null){var m=manifest();cached=new GeneratedPreviewProfile(m,m.get("profileId").getAsString(),biomes(h));}return cached;
    }
    static JsonObject manifest() throws Exception {
        var m=parentManifest();m.addProperty("profileId","first-layer-v15-living-abyss");
        m.addProperty("algorithmRevision",m.get("algorithmRevision").getAsString()+"+living-abyss-v15.3-part3a");
        m.add("livingAbyss",recipe());m.getAsJsonObject("nativeWorldView").getAsJsonArray("bounds").set(1,new JsonPrimitive(-1424));return m;
    }
    @GameTest(template="empty",batch="miaV15Cost",timeoutTicks=1200)
    public static void optionalV15PairedCostAndProfileExport(GameTestHelper h) throws Exception {
        System.out.println("MIA_V15_COST_ENABLED="+Boolean.getBoolean("mia.bench.v15"));
        if(Boolean.getBoolean("mia.bench.v15"))LivingAbyssCostGameTests.exportV15AndMeasureZonesAndArchetypesAgainstV14(h);else h.succeed();
    }
    static JsonObject parentManifest() throws Exception {
        try(var in=LivingAbyssGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json")) {
            return JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    static FixedBiomeSource biomes(GameTestHelper h) {
        return new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
    }
    static JsonObject recipe() throws Exception {
        return LivingAbyssGeneration.recipe();
    }
    @GameTest(template="empty",batch="miaV15Probe")
    public static void deepNativeWaterStopsInSupportedV15BowlWithoutDeletion(GameTestHelper h) {
        var space=new SparseCubeSpace();
        for(int cx=-2;cx<=1;cx++)for(int cy=-61;cy<=-57;cy++)for(int cz=-2;cz<=1;cz++)space.loadEmpty(new CubeKey(cx,cy,cz));
        // Pocket width 6m, fully supported floor, lip higher than the inflow surface.
        for(int x=-4;x<=3;x++)for(int z=-4;z<=3;z++)for(int y=-965;y<=-962;y++)
            if(y<=-964||x==-4||x==3||z==-4||z==3)
                space.setBlock(new BlockCoordinate(x,y,z),Block.getId(Blocks.DEEPSLATE.defaultBlockState()));
        DeepCubeDomain.attach(h.getLevel(),space,false);
        var fluid=DeepFluidEngine.attach(h.getLevel(),space);
        long started=System.nanoTime(),maxStep=0;
        try {
            h.assertTrue(fluid.edit(h.getLevel(),new BlockPos(-1,-952,-1),Blocks.WATER.defaultBlockState()),"native source starts falling");
            for(long tick=5;tick<=300;tick+=5){long step=System.nanoTime();fluid.step(h.getLevel(),tick);maxStep=Math.max(maxStep,System.nanoTime()-step);}
            h.assertTrue(fluid.stats().unsupported()==0&&fluid.stats().deferred()==0,"v15 solid bowl is admitted by actual native fluid callbacks");
            h.assertTrue(h.getLevel().getBlockState(new BlockPos(-1,-963,-1)).is(Blocks.WATER),"fall reaches receiver");
            for(int x=-8;x<=7;x++)for(int z=-8;z<=7;z++)
                h.assertTrue(h.getLevel().getBlockState(new BlockPos(x,-966,z)).isAir(),"no water below supported bowl after ticks");
            System.out.println("MIA_V15_WATER_PROBE "+fluid.stats()+" totalMillis="+(System.nanoTime()-started)/1e6+" maxStepMillis="+maxStep/1e6);
        } finally { DeepFluidEngine.detach(h.getLevel());DeepCubeDomain.detach(h.getLevel()); }
        h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Probe",timeoutTicks=1200)
    public static void livingSectionExtendsOnlyNewProfileDomain(GameTestHelper h) throws Exception {
        var profile=profile(h);
        h.assertTrue(profile.generator().knownLowerY(-1,-1)==-1424,"v15 known domain ends at -1424 without changing v14 radius normalization");
        h.assertTrue(profile.generator().sampleBlock(-1,-1400,-1).isSolid(),"temporary floor is actual supported rock");
        h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Probe",timeoutTicks=1200)
    public static void livingCodecFingerprintUnknownAndSignedCubeHalo(GameTestHelper h) throws Exception {
        var p=profile(h);var m=manifest();var g=new ComposedTerrainChunkGenerator(m,biomes(h));
        var ops=net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE,h.getLevel().registryAccess());
        var encoded=ComposedTerrainChunkGenerator.CODEC.encodeStart(ops,g).result().orElseThrow();
        var restored=ComposedTerrainChunkGenerator.CODEC.parse(ops,encoded).result().orElseThrow();
        h.assertTrue(p.fingerprint().equals(restored.profile().fingerprint()),"reconstructed plan, D8 and native recipe retain identity");
        for(int x:new int[]{-1,0,1,-959,959})h.assertTrue(p.generator().knownLowerY(x,0)==-1424,"signed inland lower bound");
        h.assertTrue(p.generator().knownLowerY(-960,0)==-5000,"unchanged exterior ocean domain");
        h.assertTrue(p.generator().supportsCube(new CubeKey(-1,-89,-1))&&!p.generator().supportsCube(new CubeKey(-1,-90,-1)),"complete bottom cube plus halo, no unknown-to-air");
        boolean refused=false;try{p.generator().sampleBlock(0,-1425,0);}catch(TerrainUnavailableException expected){refused=true;}h.assertTrue(refused,"unknown below domain remains explicit");
        var changed=m.deepCopy();changed.getAsJsonObject("livingAbyss").addProperty("seed",-20261003);
        var different=new GeneratedPreviewProfile(changed,p.id(),biomes(h));h.assertTrue(!different.fingerprint().equals(p.fingerprint()),"negative seed changes independent namespace");
        for(var key:List.of(new CubeKey(-1,-89,-1),new CubeKey(28,-34,-4),new CubeKey(-33,-48,6)))
            h.assertTrue(Arrays.equals(p.generator().generateCube(key).states(),restored.profile().generator().generateCube(key).states()),"full signed cube reload equality");
        h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Probe",timeoutTicks=1200)
    public static void actualDemFieldsCatalogueArchetypesAndCascadeException(GameTestHelper h) throws Exception {
        var living=profile(h).generator().livingAbyss();var plan=living.plan();var hydro=living.hydrology();
        h.assertTrue(!hydro.outlets().isEmpty()&&hydro.shaftArea()>0,"real approved DEM has D8 shaft inflow");
        h.assertTrue(hydro.reason().equals("UNIQUE_MAX_D8_SHAFT_INFLOW")||hydro.reason().equals("AMBIGUOUS_MAX_SHAFT_INFLOW"),"wet direction carries actual reason");
        h.assertTrue(plan.ledges().size()>50,"census of surviving v14 caps is non-vacuous");
        for(var type:LivingAbyssPlan.Archetype.values())h.assertTrue(plan.ledges().stream().anyMatch(q->q.archetype()==type),"actual seed contains "+type);
        var byId=new HashMap<String,LivingAbyssPlan.Ledge>();plan.ledges().forEach(q->byId.put(q.id(),q));
        for(var n:plan.neighbours()){var a=byId.get(n.upper());var b=byId.get(n.lower());
            h.assertTrue(a.archetype()==null||b.archetype()==null||a.archetype()!=b.archetype()||(a.cascade()&&b.cascade()),"same type only within Grand Cascade");}
        long cascades=plan.ledges().stream().filter(LivingAbyssPlan.Ledge::cascade).count();h.assertTrue(cascades>=4&&cascades<=5,"four/five supported dry cascade terraces reserved");
        System.out.println("MIA_V15_PLAN "+living.status());h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Probe",timeoutTicks=1200)
    public static void finalCompositionEmptyCenterAllDepthsAndOldIslandExact(GameTestHelper h) throws Exception {
        var g=profile(h).generator();var m=parentManifest();var old=new GeneratedPreviewProfile(m,m.get("profileId").getAsString(),biomes(h)).generator();
        for(int y=-120;y>=-1350;y-=7)for(int a=0;a<180;a++)for(int r:new int[]{0,200,379}) {
            double t=a*Math.PI/90;int x=(int)(r*Math.sin(t)),z=(int)(-r*Math.cos(t));h.assertTrue(g.sampleBlock(x,y,z).isAir(),"final center, including protected/ecological parents, remains empty");
        }
        for(int x=-1200;x<=1200;x+=53)for(int z=-1200;z<=1200;z+=53)for(int y:new int[]{0,64,128,256})if(Math.hypot(x,z)>=500)
            h.assertTrue(g.sampleBlock(x,y,z).equals(old.sampleBlock(x,y,z)),"approved island exact outside deep composition");
        h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Probe",timeoutTicks=1200)
    public static void realPaletteLightTraitsAndSupportedArchetypeBodies(GameTestHelper h) throws Exception {
        var p=profile(h);var l=p.generator().livingAbyss();var sky=new GeneratedSkySource(p,Map.of());var traits=new JsonArray();
        for(var b:List.of(Blocks.STONE,Blocks.CALCITE,Blocks.DIORITE,Blocks.TUFF,Blocks.DEEPSLATE,Blocks.MOSS_BLOCK,Blocks.STONE_BRICKS,Blocks.STONE_BRICK_STAIRS,Blocks.MOSS_CARPET,Blocks.HANGING_ROOTS)){
            var s=b.defaultBlockState();var t=new JsonObject();t.addProperty("state",s.toString());t.addProperty("shapeOcclusion",s.useShapeForLightOcclusion());
            t.addProperty("opacity",s.getLightBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,BlockPos.ZERO));traits.add(t);
        }
        for(var q:l.plan().ledges())if(q.archetype()!=null) {
            int highest=Integer.MIN_VALUE;for(int y=q.y()-24;y<=q.y()+24;y++)if(l.geometry().sample(q.x(),y,q.z())!=LivingAbyssGeometry.Kind.AIR&&l.geometry().solid(q.x(),y,q.z()))highest=y;
            h.assertTrue(highest!=Integer.MIN_VALUE,"each planned cap has a solid body");
            // Traverse a dense 3D wedge down to the wall; every generated body connects,
            // including shortened edges, negative quadrants and the thin overhang lip.
            int wallY=highest-(int)Math.ceil(q.width());double a=Math.toRadians(LivingAbyssFields.azimuth(q.x(),q.z()));
            double wall=l.geometry().wallRadius(q.x(),wallY,q.z());int x=(int)Math.ceil((wall+2)*Math.sin(a)),z=(int)Math.floor(-(wall+2)*Math.cos(a));
            h.assertTrue(l.geometry().solid(x,wallY,z),"inherited lower root joins the wall for "+q.id());
            int id=Block.getId(p.generator().sampleBlock(q.x(),highest,q.z()));h.assertTrue(sky.opacity(id)>=0,"final states admitted by exact sky palette");
        }
        Path dir=Path.of(System.getProperty("mia.storageRoot", ".local") + "/codex/20261013-landscape-v15/part1");Files.createDirectories(dir);Files.writeString(dir.resolve("palette-probe.json"),traits.toString());h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Probe",timeoutTicks=1200)
    public static void v14OpeningAirProofCannotAdmitNewV15Caps(GameTestHelper h) throws Exception {
        var p=profile(h);
        h.assertTrue(!new GeneratedIslandAirCertificate(p).eligible(),"v14 upper-opening proof cannot certify post-opening v15 geometry");
        h.assertTrue(!new GeneratedDeepAirCertificate(p).eligible()&&!new GeneratedDeepAirCellCertificate(p).eligible(),"v14 deep table proofs stay disabled");
        var sky=new GeneratedSkySource(p,Map.of());
        for(var q:p.generator().livingAbyss().plan().ledges())for(int dx:new int[]{-16,0,16})for(int dz:new int[]{-16,0,16})for(int dy=-3;dy<=16;dy++) {
            int x=q.x()+dx,y=q.y()+dy,z=q.z()+dz;
            h.assertTrue(sky.stateId(x,y,z)==Block.getId(p.generator().sampleBlock(x,y,z)),"derived scalar source returns actual final cap at signed coordinates");
        }
        h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Probe",timeoutTicks=1200)
    public static void v15RecipeGuardsAndVisitedEditBytesSurviveReload(GameTestHelper h) throws Exception {
        var p=profile(h);var m=manifest();
        for(String field:List.of("algorithmSha256","demSha256")) {
            var bad=m.deepCopy();bad.getAsJsonObject("livingAbyss").addProperty(field,"0".repeat(64));boolean refused=false;
            try{new GeneratedPreviewProfile(bad,p.id(),biomes(h));}catch(IllegalArgumentException expected){refused=true;}h.assertTrue(refused,"stale v15 "+field+" must refuse");
        }
        var changed=m.deepCopy();changed.getAsJsonObject("livingAbyss").getAsJsonObject("frequenciesPer100m").addProperty("arch",.61);
        var different=new GeneratedPreviewProfile(changed,p.id(),biomes(h));h.assertTrue(!different.fingerprint().equals(p.fingerprint()),"the whole artistic recipe, including deferred frequencies, enters fingerprint");
        var key=new CubeKey(-1,-89,-1);var states=p.generator().generateCube(key).states();states[7]=Block.getId(Blocks.GOLD_BLOCK.defaultBlockState());
        var root=Path.of("diagnostics","living-v15-edit",UUID.randomUUID().toString());var store=new dev.madeinabyss.forge.cube.StableCubeStore(root,p.generatorId(),1);
        store.write(new CubeSnapshot(key,states));byte[] before=Files.readAllBytes(store.path(key));
        var reloaded=new ComposedTerrainChunkGenerator(m,biomes(h)).profile();var disk=new dev.madeinabyss.forge.cube.StableCubeStore(root,reloaded.generatorId(),1);
        var residency=new CubeResidencyService(disk,reloaded.generator()::generateCube,27,108,4);residency.move("v15",new BlockCoordinate(-1,-1410,-1),1,0);
        h.assertTrue(residency.space().block(new BlockCoordinate(-9,-1424,-16)).orElseThrow()==states[7],"store-first serves the saved edit rather than new factory terrain");
        h.assertTrue(Arrays.equals(states,disk.read(key).orElseThrow().states())&&Arrays.equals(before,Files.readAllBytes(store.path(key))),"visited snapshot retains exact disk bytes");residency.close();h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Probe",timeoutTicks=1200)
    public static void finalVoxelPatchesOfEveryArchetypeHaveNoDetachedInteriorSolid(GameTestHelper h) throws Exception {
        var p=profile(h);var pm=parentManifest();var old=new GeneratedPreviewProfile(pm,pm.get("profileId").getAsString(),biomes(h)).generator();
        final int size=49,plane=size*size,n=size*plane;var qFlags=new byte[n];var queue=new int[n];
        for(var type:LivingAbyssPlan.Archetype.values()) {
            var ledge=p.generator().livingAbyss().plan().ledges().stream().filter(q->q.archetype()==type).findFirst().orElseThrow();Arrays.fill(qFlags,(byte)0);
            int head=0,tail=0,minX=ledge.x()-24,minY=ledge.y()-24,minZ=ledge.z()-24;
            for(int y=0;y<size;y++)for(int z=0;z<size;z++)for(int x=0;x<size;x++) {
                int i=y*plane+z*size+x;var s=p.generator().sampleBlock(minX+x,minY+y,minZ+z);if(!s.isSolid()||!s.getFluidState().isEmpty())continue;qFlags[i]=1;
                boolean boundary=x==0||y==0||z==0||x==size-1||y==size-1||z==size-1;
                // Boundary-connected bodies continue into their planned wall supports;
                // interior components must join one of those or an actual parent solid.
                if(boundary||old.sampleBlock(minX+x,minY+y,minZ+z).isSolid()){qFlags[i]=2;queue[tail++]=i;}
            }
            while(head<tail){int i=queue[head++],x=i%size,z=(i/size)%size,y=i/plane;
                for(int d:new int[]{-1,1,-size,size,-plane,plane}){
                    if((d==-1&&x==0)||(d==1&&x==size-1)||(d==-size&&z==0)||(d==size&&z==size-1)||(d==-plane&&y==0)||(d==plane&&y==size-1))continue;
                    int next=i+d;if(qFlags[next]==1){qFlags[next]=2;queue[tail++]=next;}
                }
            }
            for(int i=0;i<n;i++)if(qFlags[i]==1){
                int xx=minX+i%size,yy=minY+i/plane,zz=minZ+i/size%size;var detail=new JsonObject();detail.addProperty("archetype",type.name());
                detail.addProperty("final",p.generator().sampleBlock(xx,yy,zz).toString());detail.addProperty("parent",old.sampleBlock(xx,yy,zz).toString());
                detail.addProperty("geometry",p.generator().livingAbyss().geometry().sample(xx,yy,zz).name());detail.addProperty("x",xx);detail.addProperty("y",yy);detail.addProperty("z",zz);
                var neighbours=new JsonArray();for(var d:net.minecraft.core.Direction.values()){int px=xx+d.getStepX(),py=yy+d.getStepY(),pz=zz+d.getStepZ();var item=new JsonObject();
                    item.addProperty("direction",d.name());item.addProperty("final",p.generator().sampleBlock(px,py,pz).toString());item.addProperty("parent",old.sampleBlock(px,py,pz).toString());item.addProperty("geometry",p.generator().livingAbyss().geometry().sample(px,py,pz).name());neighbours.add(item);}
                detail.add("neighbours",neighbours);int detached=0;for(byte flag:qFlags)if(flag==1)detached++;detail.addProperty("detachedCount",detached);
                var dir=Path.of(System.getProperty("mia.storageRoot", ".local") + "/codex/20261013-landscape-v15/part2");Files.createDirectories(dir);Files.writeString(dir.resolve("floating-"+type.name().toLowerCase(Locale.ROOT)+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(detail));
                h.assertTrue(false,"detached final solid in "+type+" at "+xx+","+yy+","+zz+" "+detail.get("final"));
            }
        }
        h.succeed();
    }
    @GameTest(template="empty",batch="miaV15Probe",timeoutTicks=1200)
    public static void frozenV14FullCubesMatchRecordedReference(GameTestHelper h) throws Exception {
        var m=parentManifest();var profile=new GeneratedPreviewProfile(m,m.get("profileId").getAsString(),biomes(h));
        var keys=List.of(new CubeKey(-1,-1,-1),new CubeKey(0,-80,0),new CubeKey(3,-35,-28),new CubeKey(31,-59,-8),
            new CubeKey(-37,-56,5),new CubeKey(-31,-14,-1),new CubeKey(39,-79,0),new CubeKey(43,-42,1),new CubeKey(44,5,-4));
        var out=new JsonObject();out.addProperty("fingerprint",profile.fingerprint());var cubes=new JsonObject();
        for(var key:keys)cubes.addProperty(key.toString(),HexFormat.of().formatHex(ProceduralCubeDigestCache.digest(profile.generator().generateCube(key).states())));
        out.add("cubes",cubes);
        var reference=Path.of(System.getProperty("mia.storageRoot", ".local") + "/codex/20261013-landscape-v15/part1/v14-reference.json");
        if("true".equals(System.getenv("MIA_V15_CAPTURE_V14_REFERENCE"))) {
            if(Files.exists(reference))throw new IllegalStateException("Reference already exists; never overwrite oracle");
            Files.createDirectories(reference.getParent());Files.writeString(reference,out.toString());
        } else try(var input=LivingAbyssGameTests.class.getResourceAsStream("/diagnostics/living-v15-v14-reference.json")) {
            h.assertTrue(out.equals(JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8))),"all v14 fingerprint and full cube hashes equal pre-change reference");
        }
        h.succeed();
    }
}
