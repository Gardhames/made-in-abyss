package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.Objects;

/** Native full-state cache oracle; unrelated to visual/gameplay acceptance. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IslandSurfaceCacheGameTests {
    @GameTest(template="empty",timeoutTicks=600)
    public static void islandCapacityEvictsOneColumnWithoutFlushingRemainingWorkingSet(GameTestHelper h) {
        var a=new IslandSurfaceGeneration(IslandSurfaceGeneration.proposal(),IslandSurfaceGenerationGameTests.legacy(),4,2,true);
        for(int i=0;i<5;i++)a.sampleIfOwned(-1100+i,-2000,-1100+i);
        h.assertTrue(a.status().get("cachedColumns").getAsInt()==4,"one admission evicts one old entry, retaining four bounded columns");
        long before=a.status().get("columnBuilds").getAsLong();
        a.sampleIfOwned(-1098,-2000,-1098);
        h.assertTrue(a.status().get("columnBuilds").getAsLong()==before,"retained negative diagonal column must not regenerate after eviction");
        a.sampleIfOwned(-1100,-2000,-1100);
        h.assertTrue(a.status().get("columnBuilds").getAsLong()==before+1,"oldest column really leaves the bounded cache");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void islandCacheMatchesBypassAtSignedOwnershipCoastPlantsAndEviction(GameTestHelper h) {
        var recipe=IslandSurfaceGeneration.proposal();var p=IslandSurfaceGenerationGameTests.legacy();
        var cached=new IslandSurfaceGeneration(recipe,p,32,8,true);
        var oracle=new IslandSurfaceGeneration(recipe,p,32,8,false);
        int comparisons=0;
        for(int signX:new int[]{-1,1})for(int signZ:new int[]{-1,1})
            for(int radius:new int[]{499,500,540,959,960,961,1279,1280,1281,4000})
                for(int dz:new int[]{-1,0,1})comparisons+=compare(cached,oracle,signX*radius,signZ*dz,h);
        long seed=0x130091L;
        for(int i=0;i<1200;i++) {
            seed=seed*6364136223846793005L+1442695040888963407L;int x=(int)Math.floorMod(seed,3001L)-1500;
            seed=seed*6364136223846793005L+1442695040888963407L;int z=(int)Math.floorMod(seed,3001L)-1500;
            comparisons+=compare(cached,oracle,x,z,h);
        }
        h.assertTrue(cached.status().get("cachedColumns").getAsInt()<=32&&cached.status().get("cachedTrees").getAsInt()<=8,"eviction keeps both caches bounded");
        h.assertTrue(oracle.status().get("cachedColumns").getAsInt()==0&&oracle.status().get("cachedTrees").getAsInt()==0,"bypass really recalculates immutable values");
        System.out.println("MIA_ISLAND_CACHE_ORACLE fullStates="+comparisons+" columnBuilds="+cached.status().get("columnBuilds")+" bypassBuilds="+oracle.status().get("columnBuilds"));h.succeed();
    }
    private static int compare(IslandSurfaceGeneration cached,IslandSurfaceGeneration oracle,int x,int z,GameTestHelper h) {
        h.assertTrue(Double.doubleToLongBits(cached.surfaceHeight(x,z))==Double.doubleToLongBits(oracle.surfaceHeight(x,z)),"exact height bits survive cache mode");
        int top=(int)Math.floor(oracle.surfaceHeight(x,z));int count=0;
        for(int y:new int[]{oracle.knownLowerY(x,z),-65,-1,0,1,top-4,top,top+1,top+2,top+4,top+8,top+9}) {
            if(y<oracle.knownLowerY(x,z))continue;
            h.assertTrue(Objects.equals(cached.sampleIfOwned(x,y,z),oracle.sampleIfOwned(x,y,z)),"full block state including null delegation survives cache/eviction at "+x+","+y+","+z);count++;
        }
        return count;
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void islandSharedCacheParallelEvictionKeepsExactStates(GameTestHelper h) throws Exception {
        var recipe=IslandSurfaceGeneration.proposal();var p=IslandSurfaceGenerationGameTests.legacy();
        var shared=new IslandSurfaceGeneration(recipe,p,32,8,true);
        var oracle=new IslandSurfaceGeneration(recipe,p,32,8,false);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var jobs=new java.util.ArrayList<java.util.concurrent.Callable<String>>();
            for(int quadrant=0;quadrant<4;quadrant++) {
                final int q=quadrant;
                jobs.add(()->{
                    for(int i=0;i<160;i++) {
                        int x=((q&1)==0?-1:1)*(540+Math.floorMod(i*37,1000));
                        int z=((q&2)==0?-1:1)*(40+Math.floorMod(i*73,1200));
                        int top=(int)Math.floor(oracle.surfaceHeight(x,z));
                        for(int y:new int[]{0,1,top-1,top,top+1,top+4,top+8}) {
                            if(y<oracle.knownLowerY(x,z))continue;
                            if(!Objects.equals(shared.sampleIfOwned(x,y,z),oracle.sampleIfOwned(x,y,z)))return "parallel state differs at "+x+","+y+","+z;
                        }
                    }
                    return "";
                });
            }
            for(var result:executor.invokeAll(jobs))h.assertTrue(result.get().isEmpty(),"four workers retain exact island states during tiny-cache eviction");
            h.assertTrue(shared.status().get("cachedColumns").getAsInt()<=32&&shared.status().get("cachedTrees").getAsInt()<=8,"parallel admission keeps strict entry budgets");
        } finally {executor.shutdownNow();}
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=600)
    public static void islandConstructionBridgeIsFixedPairAndLeavesRecipeUntouched(GameTestHelper h) {
        var original=IslandSurfaceGeneration.proposal();original.addProperty("algorithmSha256",IslandAlgorithmEquivalence.ORIGINAL);
        String before=original.toString();String actual=IslandSurfaceGeneration.actualAlgorithmSha256();
        var accepted=IslandAlgorithmEquivalence.forConstruction(original,actual);
        h.assertTrue(accepted!=original&&accepted.get("algorithmSha256").getAsString().equals(actual),"only actual audited cache closure receives construction copy");
        h.assertTrue(original.toString().equals(before),"original recipe bytes remain authoritative");
        h.assertTrue(IslandAlgorithmEquivalence.forConstruction(original,"f".repeat(64))==original,"unverified live implementation cannot use bridge");
        var different=original.deepCopy();different.addProperty("algorithmSha256","e".repeat(64));
        h.assertTrue(IslandAlgorithmEquivalence.forConstruction(different,actual)==different,"unverified original recipe cannot use bridge");
        new IslandSurfaceGeneration(original,IslandSurfaceGenerationGameTests.legacy());h.succeed();
    }
}
