package dev.madeinabyss.forge.probe.deep;

import com.google.gson.JsonParser;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.forge.worldgen.GeneratedPreviewProfile;
import dev.madeinabyss.forge.worldgen.GeneratedSkySource;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.NativeFirstViewPlan;
import dev.madeinabyss.kernel.light.DirectSkyColumnSeed;
import dev.madeinabyss.kernel.light.VoxelSkyLight;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Common payload used by the real client snapshot; these tests never load Minecraft/OpenGL. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedNativeWindowGameTests {
    private static final long EPOCH = 19;
    private static Fixture fixture;

    @GameTest(template="empty",timeoutTicks=2400)
    public static void completedNearWindowAdmitsUnchangedFullTargetAndCurrentCamera(GameTestHelper h)throws Exception {
        var f=fixture(h);
        h.assertTrue(f.near().stage()==NativeFirstViewPlan.Stage.NEAR&&f.near().radius()==24,
                "Real 80-cubed solve captures an independently complete 27-cube NEAR window");
        h.assertTrue(f.near().readyFor(64),"Actual snapshot admission must accept certified NEAR while target stays64");
        h.assertTrue(f.near().nativeReady(-269.153355,-337.365774,405.973425,64,EPOCH),
                "Current camera is covered even before FULL radius equality");
        h.assertTrue(!f.near().nativeReady(-269.153355,-337.365774,405.973425,64,EPOCH+1),
                "A complete old source epoch is never current authority");
        h.assertTrue(!f.near().nativeReady(1e20,-337,400,64,EPOCH)
                &&!f.full().nativeReady(1e20,-337,400,64,EPOCH),"Finite cameras outside signed XYZ refuse without throwing");
        var capped=NativeFirstViewPlan.near(10,-269.153355,-337.365774,405.973425,
                f.source().minY(),f.source().knownMaxY(),EPOCH,884736).orElseThrow();
        var cappedWindow=GeneratedNativeWindow.near(capped,EPOCH,f.near().sky(),f.near().block(),f.near().cubes()).orElseThrow();
        h.assertTrue(cappedWindow.requestedRadius()==160&&cappedWindow.readyFor(128)&&!cappedWindow.readyFor(160),
                "Uncapped requested metadata admits the actual capped FULL target without pretending to cover160");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=2400)
    public static void actualDrawPredicateExcludesSavedHaloModelsAndWater(GameTestHelper h)throws Exception {
        var f=fixture(h);
        for(int[] q:new int[][]{{-288,-350,400},{-241,-350,400},{-272,-368,400},
                {-272,-321,400},{-272,-350,384},{-272,-350,431}}) {
            h.assertTrue(f.near().neighborState(q[0],q[1],q[2])==f.source().stateIdUncached(q[0],q[1],q[2]),
                    "Submitted boundary state is the exact saved whole-cube value");
            h.assertTrue(f.near().allowsBlock(q[0],q[1],q[2],-272,-352,400,256,64),
                    "Explicit DRAW includes all six saved solid/fluid boundary planes");
        }
        h.assertTrue(f.source().stateIdUncached(-237,-352,400)==Block.getId(Blocks.WATER.defaultBlockState()),
                "Real halo WATER marker exercises a positive existing radius+8 admission");
        h.assertTrue(!f.near().allowsBlock(-237,-352,400,-272,-352,400,256,64),
                "NativeTask must not model/tessellate WATER outside the64-cubed DRAW");
        h.assertTrue(!f.near().allowsBlock(-272,-389,400,-272,-352,400,256,64),
                "Full vertical down/up must not leak into NEAR enumeration");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=2400)
    public static void absentCapturedNeighborCannotBecomeAnOccludedCompleteFace(GameTestHelper h)throws Exception {
        var f=fixture(h);
        h.assertTrue(f.full().neighborState(-321,-350,400)==f.fullSource().stateIdUncached(-321,-350,400),
                "Larger authoritative solve actually has the omitted neighbor state");
        boolean refused=false;
        try {f.near().neighborState(-321,-350,400);}catch(IllegalStateException unknown){refused=true;}
        h.assertTrue(refused,"Actual staged neighbor lookup must refuse missing geometry instead of returning the old occlusion sentinel");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=2400)
    public static void actualFullStateSavedAuthorityAndAllDrawPackedLightsMatchLargerSolve(GameTestHelper h)throws Exception {
        var f=fixture(h);var d=f.plan().drawBounds();int pairs=0,emission=0,water=0,leaves=0;
        for(int y=d.minY()-1;y<=d.minY()+d.sizeY();y++)for(int z=d.minZ()-1;z<=d.minZ()+d.sizeZ();z++)
            for(int x=d.minX()-1;x<=d.minX()+d.sizeX();x++) {
                int actual=f.near().packed(x,y,z).orElseThrow();
                h.assertTrue(actual==f.full().packed(x,y,z).orElseThrow(),
                        "Every actual DRAW/face scalar pair equals the independent larger full-state solve");
                int id=f.near().neighborState(x,y,z);
                h.assertTrue(id==f.source().stateIdUncached(x,y,z),"Window retains exact authority, including AIR within saved cubes");
                if(id==Block.getId(Blocks.GLOWSTONE.defaultBlockState()))emission++;
                if(id==Block.getId(Blocks.WATER.defaultBlockState()))water++;
                if(id==Block.getId(Blocks.OAK_LEAVES.defaultBlockState()))leaves++;
                pairs++;
            }
        h.assertTrue(pairs==125000&&emission>0&&water>0&&leaves>0,
                "The real source fixture contains emissive, water and partially opaque leaf states");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=2400)
    public static void incompleteStateSupportAndStaleAuthorityNeverIssueNearWindow(GameTestHelper h)throws Exception {
        var f=fixture(h);var incomplete=new HashMap<>(f.near().cubes());
        incomplete.remove(new CubeKey(-19,-22,25));
        h.assertTrue(GeneratedNativeWindow.near(f.plan(),EPOCH,f.near().sky(),f.near().block(),incomplete).isEmpty(),
                "Known face/water support requires every captured cube, not only exact intervals");
        h.assertTrue(GeneratedNativeWindow.near(f.plan(),EPOCH+1,f.near().sky(),f.near().block(),f.near().cubes()).isEmpty(),
                "A solved old source is not admitted by a newer owner epoch");
        var clippedBounds=new VoxelSkyLight.Bounds(-304,-383,368,80,80,80);int count=512000;
        var clippedSky=VoxelSkyLight.solve(clippedBounds,new byte[count],new byte[count],count);
        var clippedBlock=VoxelSkyLight.solveBlock(clippedBounds,new byte[count],new byte[count],count);
        var clippedCubes=new HashMap<>(f.near().cubes());clippedCubes.keySet().removeIf(key->key.y()==Math.floorDiv(clippedBounds.minY(),16));
        var clipped=GeneratedNativeWindow.full(EPOCH,-272,-352,400,64,clippedSky,clippedBlock,clippedCubes);
        h.assertTrue(clipped.minimumY()==-366,"Unaligned floor excludes both unknown partial-cube support and uncertain boundary light");
        var draw=clipped.drawBounds();
        for(int y=draw.minY()-1;y<=draw.minY()+draw.sizeY();y++)for(int z=draw.minZ()-1;z<=draw.minZ()+draw.sizeZ();z++)
            for(int x=draw.minX()-1;x<=draw.minX()+draw.sizeX();x++)
                h.assertTrue(clipped.packed(x,y,z).isPresent(),"Every actually claimed clipped DRAW face has exact SKY/BLOCK");
        h.assertTrue(clipped.neighborState(-272,draw.minY()-2,400)==f.source().stateIdUncached(-272,draw.minY()-2,400),
                "Clipped draw's two-cell water support exists in a complete captured cube");
        h.assertTrue(!clipped.allowsBlock(-272,-382,400,-272,-352,400,256,64),"Excluded floor strip is never silently claimed as rendered coverage");
        h.succeed();
    }

    private record Fixture(GeneratedSkySource source,GeneratedSkySource fullSource,NativeFirstViewPlan.Plan plan,
                           GeneratedNativeWindow near,GeneratedNativeWindow full) { }

    private static synchronized Fixture fixture(GameTestHelper h)throws Exception {
        if(fixture!=null)return fixture;
        var biomes=new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        var in=GeneratedNativeWindowGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json");
        var manifest=JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        in.close();var profile=new GeneratedPreviewProfile(manifest,manifest.get("profileId").getAsString(),biomes);
        var saved=new HashMap<CubeKey,int[]>();int air=Block.getId(Blocks.AIR.defaultBlockState());
        for(int y=-25;y<-19;y++)for(int z=22;z<28;z++)for(int x=-20;x<-14;x++) {
            int[] states=new int[4096];Arrays.fill(states,air);saved.put(new CubeKey(x,y,z),states);
        }
        for(int z=376;z<424;z++)for(int x=-296;x<-248;x++)if(x<-280||x>-269||z<394||z>406)
            set(saved,x,-343,z,Blocks.STONE);
        for(int[] q:new int[][]{{-288,-350,400},{-272,-321,400},{-272,-350,431}})set(saved,q[0],q[1],q[2],Blocks.STONE);
        for(int[] q:new int[][]{{-241,-350,400},{-272,-368,400},{-237,-352,400}})set(saved,q[0],q[1],q[2],Blocks.WATER);
        set(saved,-272,-350,384,Blocks.OAK_LEAVES);set(saved,-272,-389,400,Blocks.STONE);
        set(saved,-303,-370,400,Blocks.GLOWSTONE);set(saved,-250,-357,410,Blocks.GLOWSTONE);
        var source=new GeneratedSkySource(profile,saved);
        var independent=new GeneratedSkySource(profile.generator(),saved);
        var plan=NativeFirstViewPlan.near(4,-269.153355,-337.365774,405.973425,source.minY(),source.knownMaxY(),EPOCH,884736).orElseThrow();
        var b=plan.solveBounds();int lower=source.minKnownYForBox(b.minX(),b.minZ(),b.minX()+b.sizeX()-1,b.minZ()+b.sizeZ()-1);
        plan=NativeFirstViewPlan.near(4,-269.153355,-337.365774,405.973425,lower,source.knownMaxY(),EPOCH,884736).orElseThrow();
        var near=solve(source,plan.solveBounds(),plan,false);
        var larger=solve(independent,new VoxelSkyLight.Bounds(-336,-416,336,128,128,128),null,true);
        fixture=new Fixture(source,independent,plan,near,larger);return fixture;
    }

    private static GeneratedNativeWindow solve(GeneratedSkySource source,VoxelSkyLight.Bounds b,
                                                NativeFirstViewPlan.Plan plan,boolean oracle) {
        int cells=VoxelSkyLight.cellCount(b,2097152),sx=b.sizeX(),sz=b.sizeZ(),plane=sx*sz;
        byte[] opacity=new byte[cells],sky=new byte[cells],emission=new byte[cells];var cubes=new HashMap<CubeKey,int[]>();
        for(int y=b.minY();y<b.minY()+b.sizeY();y++)for(int z=b.minZ();z<b.minZ()+sz;z++)for(int x=b.minX();x<b.minX()+sx;x++) {
            int id=oracle?source.stateIdUncached(x,y,z):source.stateIdForDerivedGeometry(x,y,z);
            int at=x-b.minX()+sx*(z-b.minZ()+sz*(y-b.minY()));opacity[at]=(byte)source.opacity(id);emission[at]=(byte)source.emission(id);
            var key=new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16));
            cubes.computeIfAbsent(key,k->new int[4096])[(y&15)<<8|(z&15)<<4|(x&15)]=id;
        }
        for(int z=0;z<sz;z++)for(int x=0;x<sx;x++)DirectSkyColumnSeed.fill(opacity,sky,x+sx*z,plane,b.sizeY(),
                source.columnExposure(b.minX()+x,b.minZ()+z).directSky(b.minY()+b.sizeY()-1));
        dev.madeinabyss.kernel.light.SkyVisibility.VoxelOpacity lookup=(x,y,z)-> {
            long xx=(long)x-b.minX(),yy=(long)y-b.minY(),zz=(long)z-b.minZ();
            if(xx>=0&&xx<sx&&yy>=0&&yy<b.sizeY()&&zz>=0&&zz<sz)return opacity[(int)(xx+sx*(zz+sz*yy))];
            return oracle?source.opacity(source.stateIdUncached(x,y,z)):source.opacityFast(x,y,z);
        };
        for(int y=1;y<b.sizeY()-1;y++)for(int z=1;z<sz-1;z++)for(int x=1;x<sx-1;x++) {
            int i=x+sx*(z+sz*y);if(opacity[i]>=15||sky[i]==15)continue;
            if(opacity[i-1]>=15||opacity[i+1]>=15||opacity[i-sx]>=15||opacity[i+sx]>=15||opacity[i-plane]>=15||opacity[i+plane]>=15)
                sky[i]=(byte)Math.max(sky[i],source.ambientSky(b.minX()+x,b.minY()+y,b.minZ()+z,lookup,sky[i]));
        }
        var sr=VoxelSkyLight.solve(b,opacity,sky,2097152);var br=VoxelSkyLight.solveBlock(b,opacity,emission,2097152);
        return plan==null?GeneratedNativeWindow.full(EPOCH,-272,-352,400,64,sr,br,cubes):
                GeneratedNativeWindow.near(plan,EPOCH,sr,br,cubes).orElseThrow();
    }

    private static void set(Map<CubeKey,int[]> cubes,int x,int y,int z,Block block) {
        cubes.get(new CubeKey(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)))[(y&15)<<8|(z&15)<<4|(x&15)]=Block.getId(block.defaultBlockState());
    }
}
