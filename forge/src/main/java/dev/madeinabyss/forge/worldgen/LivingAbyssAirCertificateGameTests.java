package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.living.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Opt-in exhaustive AIR proof; the oracle never calls prepareCube or the certificate. */
public final class LivingAbyssAirCertificateGameTests {
    private record Counts(long certified,long shell,long certifiedShell,long core,long above,
                          long missedAir,long missedVoxels,long missedNanos,
                          long highCertified,long highMissedAir,long highMissedVoxels,long highMissedNanos,
                          long highInnerAir,long highCrossingAir,long highExteriorAir,long highAuditNanos) {}
    private static void upperCoreCubeIsPreparedAsLiteralAir(GameTestHelper h,LivingAbyssGeneration living) {
        for(int sy:new int[]{-16,0,48}){
            for(int y=sy;y<sy+16;y++)for(int z=-16;z<0;z++)for(int x=-16;x<0;x++)
                h.assertTrue(living.sample(x,y,z)==Blocks.AIR.defaultBlockState(),"scalar upper-core/opening fixture is literal AIR");
            try{h.assertTrue(living.prepareCube(-16,sy,-16),"v15 certificate must prepare all4096 upper-core/opening AIR voxels atY="+sy);}
            finally{living.finishCube();}
        }
    }
    private static void aboveGroundCubesArePreparedAsLiteralAir(GameTestHelper h,LivingAbyssGeneration living){
        for(var key:List.of(new CubeKey(70,19,0),new CubeKey(-71,19,-1),new CubeKey(75,4,0),new CubeKey(-76,4,-1))){
            int sx=key.x()*16,sy=key.y()*16,sz=key.z()*16;
            for(int y=sy;y<sy+16;y++)for(int z=sz;z<sz+16;z++)for(int x=sx;x<sx+16;x++)
                h.assertTrue(living.sample(x,y,z)==Blocks.AIR.defaultBlockState(),"independent sky fixture is literal AIR");
            try{h.assertTrue(living.prepareCube(sx,sy,sz),"above-ground AIR proof must prepare "+key);}
            finally{living.finishCube();}
        }
    }
    public static void exhaustiveAudit(GameTestHelper h)throws Exception {
        var dir=Path.of(System.getProperty("mia.bench.v15Output",System.getProperty("mia.storageRoot", ".local") + "/evidence/landscape-v15/perf-aircert"));Files.createDirectories(dir);
        var result=new JsonObject();result.addProperty("complete",false);
        var audited=new AtomicLong();var pool=Executors.newFixedThreadPool(Math.min(12,Runtime.getRuntime().availableProcessors()));
        var missedExamples=new ConcurrentLinkedQueue<CubeKey>();
        try {
            long t=System.nanoTime();var profile=LivingAbyssGameTests.profile(h);
            result.addProperty("profileBuildMillis",(System.nanoTime()-t)/1e6);
            var g=profile.generator();var living=g.livingAbyss();var cert=living.airCertificate();
            result.addProperty("certificateBuildMillis",living.airCertificateBuildNanos()/1e6);result.addProperty("certificateBytes",cert.residentBytes());result.addProperty("workers",cert.workers());
            h.assertTrue(!living.airCertificateBypassed(),"optimized profile must actually enable the certificate");
            upperCoreCubeIsPreparedAsLiteralAir(h,living);
            aboveGroundCubesArePreparedAsLiteralAir(h,living);
            GeneratedPreviewProfile oracleProfile;String previous=System.getProperty("mia.v15.airCertificate.bypass");
            t=System.nanoTime();
            try{System.setProperty("mia.v15.airCertificate.bypass","true");var m=LivingAbyssGameTests.manifest();oracleProfile=new GeneratedPreviewProfile(m,m.get("profileId").getAsString(),LivingAbyssGameTests.biomes(h));}
            finally{if(previous==null)System.clearProperty("mia.v15.airCertificate.bypass");else System.setProperty("mia.v15.airCertificate.bypass",previous);}
            var oracle=oracleProfile.generator();
            h.assertTrue(oracle.livingAbyss().airCertificateBypassed(),"constructor flag must bypass both scalar and prepared certificate paths");
            h.assertTrue(profile.fingerprint().equals(oracleProfile.fingerprint())&&living.planDigest().equals(oracle.livingAbyss().planDigest()),"oracle flags cannot change profile/plan identity");
            result.addProperty("oracleProfileBuildMillis",(System.nanoTime()-t)/1e6);result.addProperty("oracleCertificateBuildMillis",oracle.livingAbyss().airCertificateBuildNanos()/1e6);
            result.addProperty("fingerprint",profile.fingerprint());result.addProperty("algorithmSha256",LivingAbyssGeneration.algorithmSha256());result.addProperty("oracleBypassed",true);
            var jobs=new ArrayList<Callable<Counts>>();int firstY=Math.floorDiv(living.recipeValue().domain().wallBottomY(),16)+1;
            double exterior=g.parameters().cavity().lowerRadius()+g.parameters().noise().wallAmplitude();
            for(int cx=-80;cx<80;cx++)for(int cz=-80;cz<80;cz++){
                final int xx=cx,zz=cz;int sx=cx*16,sz=cz*16;
                jobs.add(()->{
                    var radii=new double[256];double min=Double.POSITIVE_INFINITY,max=0;
                    for(int z=0;z<16;z++)for(int x=0;x<16;x++){double r=Math.sqrt((double)(sx+x)*(sx+x)+(double)(sz+z)*(sz+z));radii[z*16+x]=r;min=Math.min(min,r);max=Math.max(max,r);}
                    long certified=0,shell=0,certifiedShell=0,core=0,above=0,missedAir=0,missedVoxels=0,missedNanos=0;
                    long highCertified=0,highMissedAir=0,highMissedVoxels=0,highMissedNanos=0;
                    long highInnerAir=0,highCrossingAir=0,highExteriorAir=0,highAuditNanos=0;
                    for(int cy=firstY;cy<20;cy++){
                        int sy=cy*16;boolean inShell=cy<0&&min>=380&&max<=exterior;
                        // Exact denominator: EVERY voxel satisfies the supplied sample-stats
                        // shell definition. No certificate or analytic wall bound is used.
                        outer:if(inShell)for(int y=sy;y<sy+16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
                            if(radii[z*16+x]>=living.geometry().wallRadius(sx+x,y,sz+z)-24){inShell=false;break outer;}
                        if(inShell)shell++;
                        boolean admitted=cert.uniformAir(xx,cy,zz);
                        if(admitted!=oracle.livingAbyss().airCertificate().uniformAir(xx,cy,zz))throw new AssertionError("Fresh profile certificate mismatch at "+xx+","+cy+","+zz);
                        if(!admitted){
                            if(inShell||cy>=4){
                                long start=System.nanoTime(),scanned=0;boolean literalAir=true;
                                scan:for(int y=sy;y<sy+16;y++)for(int z=sz;z<sz+16;z++)for(int x=sx;x<sx+16;x++){
                                    scanned++;
                                    if(oracle.sampleBlock(x,y,z)!=Blocks.AIR.defaultBlockState()){literalAir=false;break scan;}
                                }
                                long elapsed=System.nanoTime()-start;
                                if(inShell){missedVoxels+=scanned;missedNanos+=elapsed;if(literalAir){missedAir++;if(missedExamples.size()<32)missedExamples.add(new CubeKey(xx,cy,zz));}}
                                else{highMissedVoxels+=scanned;highMissedNanos+=elapsed;if(literalAir){highMissedAir++;if(max<500)highInnerAir++;else if(min<500)highCrossingAir++;else highExteriorAir++;}}
                            }
                            continue;
                        }certified++;if(inShell)certifiedShell++;if(max<380)core++;if(cy>=0)above++;if(cy>=4)highCertified++;
                        long cubeAuditStart=System.nanoTime();
                        for(int y=sy;y<sy+16;y++)for(int z=sz;z<sz+16;z++)for(int x=sx;x<sx+16;x++){
                            var expected=oracle.sampleBlock(x,y,z);
                            if(expected!=Blocks.AIR.defaultBlockState())throw new AssertionError("NON_AIR certificate voxel "+x+","+y+","+z+" = "+expected);
                            if(g.sampleBlock(x,y,z)!=expected)throw new AssertionError("Optimized scalar mismatch at "+x+","+y+","+z);
                        }
                        if(cy>=4)highAuditNanos+=System.nanoTime()-cubeAuditStart;
                        long done=audited.incrementAndGet();if((done&8191)==0)System.out.println("MIA_V15_AIRCERT_PROGRESS cubes="+done+" voxels="+(done*4096));
                    }return new Counts(certified,shell,certifiedShell,core,above,missedAir,missedVoxels,missedNanos,highCertified,highMissedAir,highMissedVoxels,highMissedNanos,highInnerAir,highCrossingAir,highExteriorAir,highAuditNanos);
                });
            }
            t=System.nanoTime();long certified=0,shell=0,certifiedShell=0,core=0,above=0,missedAir=0,missedVoxels=0,missedNanos=0;
            long highCertified=0,highMissedAir=0,highMissedVoxels=0,highMissedNanos=0;
            long highInnerAir=0,highCrossingAir=0,highExteriorAir=0,highAuditNanos=0;
            for(var f:pool.invokeAll(jobs)){var c=f.get();certified+=c.certified;shell+=c.shell;certifiedShell+=c.certifiedShell;core+=c.core;above+=c.above;missedAir+=c.missedAir;missedVoxels+=c.missedVoxels;missedNanos+=c.missedNanos;highCertified+=c.highCertified;highMissedAir+=c.highMissedAir;highMissedVoxels+=c.highMissedVoxels;highMissedNanos+=c.highMissedNanos;highInnerAir+=c.highInnerAir;highCrossingAir+=c.highCrossingAir;highExteriorAir+=c.highExteriorAir;highAuditNanos+=c.highAuditNanos;}
            result.addProperty("auditMillis",(System.nanoTime()-t)/1e6);result.addProperty("certifiedCubes",certified);result.addProperty("auditedVoxels",audited.get()*4096);
            result.addProperty("shellCubes",shell);result.addProperty("certifiedShellCubes",certifiedShell);result.addProperty("shellCoveragePercent",100.0*certifiedShell/Math.max(1,shell));
            result.addProperty("uncertifiedShellCubes",shell-certifiedShell);result.addProperty("uncertifiedLiteralAirShellCubes",missedAir);
            result.addProperty("uncertifiedShellScannedVoxels",missedVoxels);result.addProperty("uncertifiedShellScanWorkerMillis",missedNanos/1e6);
            result.addProperty("missedFractionOfLiteralAirShellPercent",100.0*missedAir/Math.max(1,certifiedShell+missedAir));
            result.add("uncertifiedLiteralAirExamples",new Gson().toJsonTree(missedExamples));
            result.addProperty("certifiedCoreCubes",core);result.addProperty("certifiedBoundaryOrBandCubes",certified-core-certifiedShell);
            result.addProperty("certifiedAboveGroundCubes",above);result.addProperty("certifiedBelowGroundCubes",certified-above);
            result.addProperty("above64DomainCubes",160L*160*16);result.addProperty("certifiedAbove64Cubes",highCertified);result.addProperty("uncertifiedLiteralAirAbove64Cubes",highMissedAir);
            result.addProperty("above64MissedFractionOfLiteralAirPercent",100.0*highMissedAir/Math.max(1,highCertified+highMissedAir));
            result.addProperty("uncertifiedAbove64ScannedVoxels",highMissedVoxels);result.addProperty("uncertifiedAbove64ScanWorkerMillis",highMissedNanos/1e6);
            result.addProperty("uncertifiedLiteralAirAbove64InnerCubes",highInnerAir);result.addProperty("uncertifiedLiteralAirAbove64CrossingCubes",highCrossingAir);result.addProperty("uncertifiedLiteralAirAbove64ExteriorCubes",highExteriorAir);
            result.addProperty("above64CertifiedAuditWorkerMillis",highAuditNanos/1e6);result.addProperty("above64OracleScannedVoxels",highCertified*4096+highMissedVoxels);
            h.assertTrue(highInnerAir+highCrossingAir+highExteriorAir==highMissedAir,"sky false-negative partition is exhaustive");
            result.addProperty("aboveGroundProof","Y0..63: approved R500 opening. Y64..319: whole XZ cube at r>=500, above every floor(public island surfaceHeight)+8, final frozen feature/support footprint absent. Inner opaque parent callback remains unproved.");
            result.addProperty("shellDefinition","whole aligned cubes: -1344<=Y<=-1, all voxels 380<=r<wallRadius(x,y,z)-24, r<=lowerRadius+wallAmplitude");
            h.assertTrue(certified==cert.certifiedCubeCount()&&audited.get()==certified,"every certified cube, without gaps or duplicate accounting");
            h.assertTrue(certifiedShell>1000&&shell>=certifiedShell,"non-vacuous exact shell coverage");
            t=System.nanoTime();verifyConsumers(h,profile,oracle,result);result.addProperty("consumerAuditMillis",(System.nanoTime()-t)/1e6);
            result.addProperty("nonAirVoxels",0);result.addProperty("complete",true);
        }catch(Exception|AssertionError e){Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();result.addProperty("error",cause.toString());throw e;}
        finally{pool.shutdownNow();result.addProperty("completedAuditCubes",audited.get());Files.writeString(dir.resolve("audit.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));System.out.println("MIA_V15_AIRCERT "+result);}
        h.succeed();
    }
    private static void verifyConsumers(GameTestHelper h,GeneratedPreviewProfile profile,ParameterizedTerrainChunkGenerator oracle,JsonObject result)throws Exception {
        var g=profile.generator();var cert=g.livingAbyss().airCertificate();var keys=new ArrayList<CubeKey>();keys.add(new CubeKey(-1,-1,-1));
        keys.addAll(List.of(new CubeKey(70,19,0),new CubeKey(-71,19,-1),new CubeKey(75,4,0)));
        cert.visitCertifiedCubes(k->{if(keys.size()<64&&(LivingAbyssFields.mix(((long)k.x()<<32)^((long)k.y()<<16)^k.z())&4095)==0)keys.add(k);});
        var source=new GeneratedSkySource(profile,Map.of());int air=Block.getId(Blocks.AIR.defaultBlockState());
        for(var key:keys){var generated=g.generateCube(key).states();var tile=source.captureDerivedCube(key,()->{});
            for(int i=0;i<4096;i++)h.assertTrue(generated[i]==air&&tile[i]==air,"generateCube and exact tile preserve every certified voxel");}
        var saved=new int[4096];Arrays.fill(saved,air);saved[0]=Block.getId(Blocks.STONE.defaultBlockState());saved[15]=Block.getId(Blocks.WATER.defaultBlockState());saved[4095]=Block.getId(Blocks.OAK_LEAVES.defaultBlockState());
        var key=keys.get(0);var edited=new GeneratedSkySource(profile,Map.of(key,saved));h.assertTrue(Arrays.equals(saved,edited.captureDerivedCube(key,()->{})),"whole saved override precedes uniform procedural AIR");
        for(int i:new int[]{0,15,4095})h.assertTrue(edited.stateIdUncached(key.x()*16+(i&15),key.y()*16+(i>>8),key.z()*16+((i>>4)&15))==saved[i],"saved override also precedes scalar fast path");
        var column=LevelHeightAccessor.create(-64,384);var biomes=h.getLevel().registryAccess().registryOrThrow(Registries.BIOME);long filled=0;int certifiedSections=0;
        int[][] positions={{-1,-1},{25,0},{-26,-1},{0,25},{-1,-26},{39,-2},{65,0},{75,0},{-76,-1}};
        for(var pos:positions){var chunk=new ProtoChunk(new ChunkPos(pos[0],pos[1]),UpgradeData.EMPTY,column,biomes,null);int sx=pos[0]*16,sz=pos[1]*16;
            for(int sy=-64;sy<320;sy+=16){chunk.setBlockState(new BlockPos(sx+3,sy+15,sz+5),Blocks.STONE.defaultBlockState(),false);if(cert.uniformAir(pos[0],sy>>4,pos[1]))certifiedSections++;}
            g.fillFromNoise(Runnable::run,null,null,null,chunk).join();
            var position=new BlockPos.MutableBlockPos();
            for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                int surface=-64,floor=-64;
                for(int y=-64;y<320;y++){var expected=oracle.sampleBlock(sx+x,y,sz+z);filled++;
                    h.assertTrue(chunk.getBlockState(position.set(sx+x,y,sz+z))==expected,"prepared native fill agrees with independent unprepared scalar at "+position);
                    if(Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(expected))surface=y+1;
                    if(Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(expected))floor=y+1;
                }
                h.assertTrue(chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG).getFirstAvailable(x,z)==surface,"native AIR skip preserves world-surface heightmap");
                h.assertTrue(chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG).getFirstAvailable(x,z)==floor,"native AIR skip preserves ocean-floor heightmap");
            }
        }
        h.assertTrue(certifiedSections>=4,"native fill fixture actually contains certified sections");
        result.addProperty("consumerCubes",keys.size());result.addProperty("savedOverrideChecks",4099);result.addProperty("nativeColumns",positions.length);result.addProperty("nativeCheckedVoxels",filled);result.addProperty("nativeCertifiedSections",certifiedSections);
    }
}
