package dev.madeinabyss.forge.worldgen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.madeinabyss.forge.probe.deep.GeneratedNativeWindow;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.ExactCubeTileCache;
import dev.madeinabyss.kernel.cube.NativeFirstViewPlan;
import dev.madeinabyss.kernel.light.VoxelSkyLight;
import dev.madeinabyss.kernel.terrain.living.LivingAbyssFloraPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** CPU-only acceptance of the actual native solve; these times are never L_native/L_handoff. */
@GameTestHolder("near_stage1")
@PrefixGameTestTemplate(false)
public final class NearNativePrototypeGameTests {
    private static final int WORKERS=4, MAX_CELLS=512_000;
    private static final long AUTHORITY_EPOCH=73, BUDGET_NANOS=TimeUnit.SECONDS.toNanos(30);
    private static final Point WALL=new Point("capture-wall",296.765,-330,-326.207,null);
    private static final Path OUTPUT=Path.of(System.getProperty("mia.near.prototypeOutput",
            System.getProperty("mia.storageRoot", ".local") + "/codex/20261014-near-blocks-stage1/prototype"));

    // Catches returning on Future.cancel before the real density writers have exited.
    @GameTest(template="empty",batch="nearPrototypeCancel",timeoutTicks=2400)
    public static void cancelActualV15DensityJoinsEveryWriterAndPublishesNothing(GameTestHelper h)throws Exception {
        cancellation(h,"DENSITY_BEGIN");
    }

    // Catches ambient rays/scans ignoring a new target, or publishing a partial old light result.
    @GameTest(template="empty",batch="nearPrototypeCancel",timeoutTicks=2400)
    public static void cancelActualV15AmbientJoinsEveryWriterAndPublishesNothing(GameTestHelper h)throws Exception {
        cancellation(h,"AMBIENT_BEGIN");
    }

    // The sparse wall can have zero probes: separately enter a real global original32 ray.
    @GameTest(template="empty",batch="nearPrototypeDenseRay",timeoutTicks=2400)
    public static void cancelActualV15DenseOriginal32RayJoinsEveryWriterAndPublishesNothing(GameTestHelper h)throws Exception {
        var giant=LivingAbyssGameTests.profile(h).generator().livingAbyss().flora().trees().stream()
                .filter(tree->tree.kind().equals("giant")).findFirst().orElseThrow();
        cancellation(h,"AMBIENT_BEGIN",treePoint(giant),true);
    }

    // Catches exact-source reuse changing SKY/BLOCK, UNKNOWN, geometry, or model/fluid stencil.
    @GameTest(template="empty",batch="nearPrototypeCost",timeoutTicks=12000)
    public static void optionalColdWarmActualV15NearCostsKeepExactArraysAndDrawStencil(GameTestHelper h)throws Exception {
        if(!Boolean.getBoolean("mia.near.prototypeCost")) {
            System.out.println("MIA_NEAR_PROTOTYPE_COST status=DISABLED measurements=NOT_RUN");h.succeed();return;
        }
        long profileStart=System.nanoTime();var profile=LivingAbyssGameTests.profile(h);
        double profileStartupMillis=millis(profileStart);var report=header(profile,profileStartupMillis);
        var trials=new JsonArray();report.add("trials",trials);
        var trees=profile.generator().livingAbyss().flora().trees();
        var giant=trees.stream().filter(t->t.kind().equals("giant")).findFirst().orElseThrow();
        var guiding=trees.stream().filter(t->t.kind().equals("guiding")).findFirst().orElseThrow();
        int incomplete=0;boolean coldCpuBudgetMet=true;
        for(var point:List.of(WALL,treePoint(giant),treePoint(guiding))) {
            long sourceStart=System.nanoTime();var source=normalColdSource(profile);
            var plan=plan(source,point);String authority=source.cacheGeometryIdentity();
            double sourceStartupMillis=millis(sourceStart);
            try(var phases=new Writers("cost-"+point.label)) {
                var cold=trial(source,plan,phases);var coldJson=trialJson("cold",point,plan,cold);
                coldJson.addProperty("sourceStartupMillisExcluded",sourceStartupMillis);
                coldJson.addProperty("geometryIdentity",authority);trials.add(coldJson);
                write("cold-warm-v15.json",report);
                assertSourceLimits(h,source,cold);
                if(cold.result==null) {incomplete++;coldCpuBudgetMet=false;continue;}
                coldCpuBudgetMet&=cold.elapsedMillis<=2800;
                // Same immutable source and bounds; an empty prior-density map still exercises tiles.
                var warm=trial(source,plan,phases);var warmJson=trialJson("warm",point,plan,warm);
                warmJson.addProperty("geometryIdentity",authority);trials.add(warmJson);
                write("cold-warm-v15.json",report);assertSourceLimits(h,source,warm);
                if(warm.result==null){incomplete++;continue;}
                boolean exact=equal(cold.result.sky(),warm.result.sky())&&equal(cold.result.block(),warm.result.block());
                coldJson.addProperty("coldWarmExactArrays",exact);warmJson.addProperty("coldWarmExactArrays",exact);
                boolean stencil=digest(cold.result,plan).get("drawStencilSha256").getAsString()
                        .equals(digest(warm.result,plan).get("drawStencilSha256").getAsString());
                warmJson.addProperty("coldWarmExactStencil",stencil);
                write("cold-warm-v15.json",report);
                h.assertTrue(exact,"Cold/warm actual v15 must match every SKY/BLOCK lower/upper cell at "+point.label);
                h.assertTrue(stencil,"Cold/warm exact model/fluid draw stencil differs at "+point.label);
                h.assertTrue(digest(cold.result,plan).get("stateSha256").getAsString()
                        .equals(digest(warm.result,plan).get("stateSha256").getAsString()),"Warm reuse changes native full state ids");
            }
        }
        report.addProperty("measurementStatus",incomplete==0?"COMPLETE":"TIMEOUT");
        report.addProperty("correctnessStatus",incomplete==0?"COLD_WARM_EQUAL":"INCOMPLETE");
        report.addProperty("cpuCold2800MillisTargetMet",coldCpuBudgetMet);
        report.addProperty("performanceAcceptance","OPEN_REQUIRES_CLAUDE_LIVE_NATIVE");
        write("cold-warm-v15.json",report);
        System.out.println("MIA_NEAR_PROTOTYPE_COST status="+report.get("measurementStatus")
                +" coldCpu2800ms="+coldCpuBudgetMet+" output="+OUTPUT.resolve("cold-warm-v15.json"));
        h.assertTrue(incomplete==0,"30s CPU prototype timeout; preserved JSON is not a performance PASS");h.succeed();
    }

    private static void cancellation(GameTestHelper h,String targetPhase)throws Exception {
        cancellation(h,targetPhase,WALL,false);
    }
    private static void cancellation(GameTestHelper h,String targetPhase,Point point,boolean requireOriginal32Ray)throws Exception {
        long profileStart=System.nanoTime();var profile=LivingAbyssGameTests.profile(h);
        var report=header(profile,millis(profileStart));var source=normalColdSource(profile);var plan=plan(source,point);
        var control=new CancelControl(targetPhase,source,requireOriginal32Ray);var trace=new Trace();
        var result=new AtomicReference<GeneratedNativeLightSolver.Result>();
        var failure=new AtomicReference<Throwable>();var solverExited=new CountDownLatch(1);
        var solverExitNanos=new AtomicLong();
        var coordinator=Executors.newSingleThreadExecutor(factory("cancel-coordinator"));
        try(var phases=new Writers("cancel-"+targetPhase)) {
            long started=System.nanoTime();control.deadline=started+BUDGET_NANOS;trace.start=started;
            coordinator.execute(()->{
                try {result.set(GeneratedNativeLightScheduler.solve(source,plan.solveBounds(),Map.of(),phases,WORKERS,
                        control::checkpoint,(stage,key)->{
                            if(key==null) {
                                if(stage.equals(targetPhase))control.taskStartBaseline=phases.started.get();
                                control.phase=stage;
                            }
                            trace.accept(stage,key);
                        }));}
                catch(Throwable stopped){failure.set(stopped);}
                finally {solverExitNanos.set(System.nanoTime());trace.stop=solverExitNanos.get();solverExited.countDown();}
            });
            boolean inside=control.inside.await(30,TimeUnit.SECONDS);
            // Dynamic cursor scheduling may give a Runnable no slab. Count actual Runnable starts,
            // without requiring each worker to enter a scan or to find a nonzero ambient seed.
            boolean allStarted=inside&&phases.awaitStarted(control.taskStartBaseline+WORKERS,control.deadline);
            boolean requested=inside&&solverExited.getCount()!=0;
            long cancelAt=requested?System.nanoTime():0;control.cancelled.set(true);control.release.countDown();
            boolean exitHeld=requested&&control.exitHoldEntered.await(5,TimeUnit.SECONDS);
            boolean returnedBeforeWriter=exitHeld&&solverExited.await(100,TimeUnit.MILLISECONDS);
            long exitPermitAt=System.nanoTime();control.exitPermit.countDown();
            boolean exited=solverExited.await(5,TimeUnit.SECONDS);
            boolean writersExited=exited&&phases.awaitIdle(5);
            report.addProperty("targetPhase",targetPhase);report.add("point",pointJson(point));
            report.add("plan",boundsJson(plan));report.addProperty("allFourWritersStartedTargetPhase",allStarted);
            report.addProperty("actualScanOrRayCheckpointEntered",inside);
            report.addProperty("cancellationRequestedWhileSolverLive",requested);
            report.addProperty("targetPhaseRunnableStartBaseline",control.taskStartBaseline);
            if(requireOriginal32Ray) {
                report.addProperty("actualOriginal32SourceRayCheckpointEntered",inside&&!control.actualRayStack.isEmpty());
                var stack=new JsonArray();control.actualRayStack.forEach(stack::add);report.add("actualRayCheckpointStack",stack);
                report.addProperty("rayRequirement","Fresh source probe evaluation + source.checkRequest inside SkyVisibility.trace or QuantizedDiffuseSky.Ray.step; interpolation segment/scan alone cannot pass");
            }
            report.addProperty("solverRetainedUntilActualWriterExit",exitHeld&&!returnedBeforeWriter);
            if(requested)report.addProperty("controlledWriterExitHoldMillis",(exitPermitAt-cancelAt)/1e6);
            else report.add("controlledWriterExitHoldMillis",JsonNull.INSTANCE);
            addElapsed(report,"cancelToSolverExitMillis",cancelAt,exited?solverExitNanos.get():0);
            addElapsed(report,"cancelToAllWriterExitMillis",cancelAt,writersExited?phases.lastExitNanos.get():0);
            addElapsed(report,"exitPermitToAllWriterExitMillis",requested&&exitHeld?exitPermitAt:0,writersExited?phases.lastExitNanos.get():0);
            report.addProperty("cancellationTimingNote","cancelTo* includes explicit test-only exit hold; exitPermitTo* excludes it");
            report.addProperty("solverExited",exited);report.addProperty("publishedPartialResult",result.get()!=null);
            report.addProperty("lightReadyObserved",trace.lightReady.get());report.add("writers",phases.snapshot());
            report.add("phaseElapsedMillis",trace.phaseTimes());report.add("counters",counters(source));
            report.addProperty("measurementStatus",requested&&allStarted&&writersExited&&exitHeld&&!returnedBeforeWriter?"CANCELLED_JOINED":"FAILED");
            if(failure.get()!=null)report.addProperty("failure",failure.get().toString());
            write(requireOriginal32Ray?"cancel-dense-giant-ambient-ray.json":"cancel-"+targetPhase.toLowerCase(java.util.Locale.ROOT)+".json",report);
            h.assertTrue(allStarted,"All real phase tasks must start, and an actual scan/ray must reach its checkpoint, before cancelling "+targetPhase);
            h.assertTrue(requested,"Cancellation must be requested during the held real solve, not after its normal completion");
            h.assertTrue(exitHeld&&!returnedBeforeWriter,"Cancelled solver returned before the real scan/ray writer left its controlled exit hold");
            h.assertTrue(exited&&writersExited,"Cancellation must join actual phase writers, not only Futures");
            h.assertTrue(phases.started.get()==phases.finished.get()&&phases.active.get()==0,"Phase writer remains live after solver exit");
            h.assertTrue(failure.get() instanceof CancellationException,"Expected cooperative cancellation, got "+failure.get());
            h.assertTrue(result.get()==null&&!trace.lightReady.get(),"Cancelled old target published a partial light result");
            if(requireOriginal32Ray) {
                h.assertTrue(source.probeFlightEvaluations()>0&&!control.actualRayStack.isEmpty(),"Dense cancellation must enter a source-request checkpoint inside an actual original32 global ray");
            }
            System.out.println("MIA_NEAR_PROTOTYPE_CANCEL phase="+targetPhase+" joinedMillis="+report.get("cancelToAllWriterExitMillis")
                    +" entered="+phases.started.get()+" exited="+phases.finished.get());h.succeed();
        } finally {
            control.cancelled.set(true);control.release.countDown();control.exitPermit.countDown();coordinator.shutdownNow();
            if(!coordinator.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("Prototype coordinator did not exit");
        }
    }

    private record Point(String label,double x,double y,double z,LivingAbyssFloraPlan.Tree tree){}
    private record Trial(GeneratedNativeLightSolver.Result result,Throwable failure,double elapsedMillis,
                         JsonObject before,JsonObject after,JsonObject phases,JsonObject writers,JsonArray cubeReady){}

    private static Trial trial(GeneratedSkySource source,NativeFirstViewPlan.Plan plan,Writers phases)throws Exception {
        var before=counters(source);var trace=new Trace();trace.start=System.nanoTime();long deadline=trace.start+BUDGET_NANOS;
        GeneratedNativeLightSolver.Result result=null;Throwable failure=null;
        try {
            result=GeneratedNativeLightScheduler.solve(source,plan.solveBounds(),Map.of(),phases,WORKERS,()->{
                if(Thread.currentThread().isInterrupted())throw new CancellationException("Prototype interrupted");
                if(System.nanoTime()>=deadline)throw new TrialTimeout();
            },trace);
        } catch(TrialTimeout expired){failure=expired;}
        catch(Throwable failed){failure=failed;}
        trace.stop=System.nanoTime();double elapsed=(trace.stop-trace.start)/1e6;boolean exited=phases.awaitIdle(5);
        if(!exited)throw new AssertionError("Prototype solve returned while real phase tasks are live");
        return new Trial(result,failure,elapsed,before,counters(source),trace.phaseTimes(),phases.snapshot(),trace.cubes());
    }

    private static JsonObject trialJson(String mode,Point point,NativeFirstViewPlan.Plan plan,Trial trial)throws Exception {
        var out=new JsonObject();out.addProperty("mode",mode);out.add("point",pointJson(point));out.add("plan",boundsJson(plan));
        out.addProperty("measurementStatus",trial.result!=null?"COMPLETE":trial.failure instanceof TrialTimeout?"TIMEOUT":"FAILED");
        out.addProperty("cpuSolveElapsedMillis",trial.elapsedMillis);out.add("phaseElapsedMillis",trial.phases);
        out.add("countersBefore",trial.before);out.add("countersAfter",trial.after);out.add("writers",trial.writers);
        out.add("cubeReadyElapsedMillis",trial.cubeReady);
        if(trial.failure!=null)out.addProperty("failure",trial.failure.toString());
        if(trial.result!=null) {
            var t=trial.result.timings();var times=new JsonObject();times.addProperty("density",t.densityMillis());
            times.addProperty("directSky",t.directSkyMillis());times.addProperty("ambient",t.ambientSeedMillis());
            times.addProperty("skyPropagation",t.skyPropagationMillis());times.addProperty("blockPropagation",t.blockPropagationMillis());
            out.add("solverTimingsMillis",times);out.addProperty("ambientSeedQueries",t.ambientSeedQueries());
            out.addProperty("directSkyColumns",t.directSkyColumns());out.addProperty("reusedDensityCells",t.reusedDensityCells());
            out.addProperty("sampledDensityCells",t.sampledDensityCells());out.addProperty("denseOpacityQueries",t.denseOpacityQueries());
            out.addProperty("fallbackOpacityQueries",t.fallbackOpacityQueries());out.addProperty("completeDensityCubes",trial.result.cubes().size());
            out.add("digests",digest(trial.result,plan));
        }
        return out;
    }

    private static void assertSourceLimits(GameTestHelper h,GeneratedSkySource source,Trial trial) {
        h.assertTrue(source.derivedDiskRestoreDisabled()&&!source.derivedCachesBypassed(),"Cold source must disable disk restore, retain normal in-solve reuse");
        h.assertTrue(source.exactTileResidentBytes()<=ExactCubeTileCache.DEFAULT_MAX_BYTES
                &&source.exactTileResidentTiles()<=ExactCubeTileCache.DEFAULT_MAX_TILES,"Exact tile cache exceeded bounded source budget");
        h.assertTrue(trial.failure==null||trial.failure instanceof TrialTimeout,"Actual CPU solve failed: "+trial.failure);
    }

    private static GeneratedSkySource normalColdSource(GeneratedPreviewProfile profile) {
        var source=new GeneratedSkySource(profile,Map.of());source.disableDerivedDiskRestore();return source;
    }
    private static NativeFirstViewPlan.Plan plan(GeneratedSkySource source,Point p) {
        int x=Math.floorDiv((int)Math.floor(p.x),16)*16,z=Math.floorDiv((int)Math.floor(p.z),16)*16;
        int lower=source.minKnownYForBox(x-32,z-32,x+47,z+47);
        return NativeFirstViewPlan.near(4,p.x,p.y,p.z,lower,source.knownMaxY(),AUTHORITY_EPOCH,MAX_CELLS).orElseThrow();
    }
    private static Point treePoint(LivingAbyssFloraPlan.Tree tree) {
        return new Point("plan-"+tree.kind(),tree.x()+6.5,tree.y()+24,tree.z()+6.5,tree);
    }
    private static JsonObject header(GeneratedPreviewProfile profile,double profileMillis) {
        var o=new JsonObject();o.addProperty("scope","COMMON_NATIVE_SOLVER_CPU_ONLY_NO_CLIENT_NO_WORLD_DRAW");
        o.addProperty("profileFingerprint",profile.fingerprint());o.addProperty("profileStartupMillisExcluded",profileMillis);
        o.addProperty("profileSource","LivingAbyssGameTests.manifest/current-v15.3-part3a");
        o.addProperty("timingStarts","Existing profile/loaded world, source initialized; common solve entry");
        o.addProperty("budgetMillisPerTrial",30_000);o.addProperty("cpuColdTargetMillis",2800);
        o.addProperty("workers",WORKERS);o.addProperty("diskRestore",false);o.addProperty("normalInSolveMemo",true);
        o.addProperty("L_native","NOT_MEASURED_REQUIRES_NONSHADOW_WORLD_DRAW");
        o.addProperty("L_handoff","NOT_MEASURED_REQUIRES_NATIVE_AND_WORLD_CUT_ACK");return o;
    }
    private static JsonObject pointJson(Point point) {
        var o=new JsonObject();o.addProperty("label",point.label);o.addProperty("x",point.x);o.addProperty("y",point.y);o.addProperty("z",point.z);
        if(point.tree!=null)o.add("actualTreeProvenance",new GsonBuilder().create().toJsonTree(point.tree));return o;
    }
    private static JsonObject boundsJson(NativeFirstViewPlan.Plan plan) {
        var g=new GsonBuilder().create();var o=new JsonObject();o.add("solve",g.toJsonTree(plan.solveBounds()));o.add("draw",g.toJsonTree(plan.drawBounds()));
        o.addProperty("solveCells",plan.cells());o.addProperty("drawCubes",27);o.addProperty("halo",16);o.addProperty("requestedRadius",plan.requestedRadius());return o;
    }

    private static JsonObject counters(GeneratedSkySource s) {
        var o=new JsonObject();var tile=new JsonObject();tile.addProperty("hits",s.exactTileHits());tile.addProperty("misses",s.exactTileMisses());
        tile.addProperty("builds",s.exactTileBuilds());tile.addProperty("joins",s.exactTileJoins());tile.addProperty("evictions",s.exactTileEvictions());
        tile.addProperty("residentBytes",s.exactTileResidentBytes());tile.addProperty("residentTiles",s.exactTileResidentTiles());
        tile.addProperty("boundaryFallbacks",s.exactTileBoundaryFallbacks());o.add("exactTiles",tile);
        var probes=new JsonObject();probes.addProperty("evaluations",s.probeFlightEvaluations());probes.addProperty("joins",s.probeFlightJoins());
        probes.addProperty("cachedVisibilityEntries",s.cachedVisibilityProbes());
        for(var unavailable:List.of("hits","misses","evictions","residentBytes"))probes.add(unavailable,JsonNull.INSTANCE);
        probes.addProperty("unavailableReason","Current production source exposes evaluations/joins/entries only; no fabricated cache counters");o.add("probes",probes);
        o.addProperty("skyColumnBuilds",s.skyColumnBuilds());o.addProperty("skyColumnEvictions",s.skyColumnEvictions());o.addProperty("cachedSkyColumns",s.cachedSkyColumns());
        o.addProperty("opacityHits",s.opacityCacheHits());o.addProperty("opacityMisses",s.opacityCacheMisses());o.addProperty("opacityEvictions",s.opacityCacheEvictions());
        o.addProperty("opacityResidentBytes",s.opacityCacheResidentBytes());o.addProperty("sharedOpacityHits",s.sharedOpacityHits());
        o.addProperty("sharedOpacityMisses",s.sharedOpacityMisses());o.addProperty("sharedOpacityEvictions",s.sharedOpacityEvictions());
        o.addProperty("sharedOpacityResidentBytes",s.sharedOpacityResidentBytes());o.addProperty("denseOpacityHits",s.denseOpacityHits());
        o.addProperty("denseOpacityResidentBytes",s.denseOpacityResidentBytes());return o;
    }

    private static JsonObject digest(GeneratedNativeLightSolver.Result result,NativeFirstViewPlan.Plan plan)throws Exception {
        var o=new JsonObject();o.addProperty("skySha256",lightDigest(result.sky()));o.addProperty("blockSha256",lightDigest(result.block()));
        var window=GeneratedNativeWindow.near(plan,AUTHORITY_EPOCH,result.sky(),result.block(),result.cubes()).orElseThrow();
        var stencil=MessageDigest.getInstance("SHA-256");var d=plan.drawBounds();int checked=0;
        for(int y=d.minY()-1;y<=d.minY()+48;y++)for(int z=d.minZ()-1;z<=d.minZ()+48;z++)for(int x=d.minX()-1;x<=d.minX()+48;x++) {
            update(stencil,window.packed(x,y,z).orElseThrow());checked++;
        }
        o.addProperty("drawStencilSha256",HexFormat.of().formatHex(stencil.digest()));o.addProperty("exactStencilCells",checked);
        var states=MessageDigest.getInstance("SHA-256");var keys=result.cubes().keySet().stream()
                .sorted(Comparator.comparingInt(CubeKey::x).thenComparingInt(CubeKey::y).thenComparingInt(CubeKey::z)).toList();
        for(var key:keys){update(states,key.x());update(states,key.y());update(states,key.z());for(int id:result.cubes().get(key))update(states,id);}
        o.addProperty("stateSha256",HexFormat.of().formatHex(states.digest()));return o;
    }
    private static String lightDigest(VoxelSkyLight.Result r)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");digest.update(r.lowerSnapshot());digest.update(r.upperSnapshot());return HexFormat.of().formatHex(digest.digest());
    }
    private static boolean equal(VoxelSkyLight.Result a,VoxelSkyLight.Result b) {
        return a.bounds().equals(b.bounds())&&Arrays.equals(a.lowerSnapshot(),b.lowerSnapshot())&&Arrays.equals(a.upperSnapshot(),b.upperSnapshot());
    }
    private static void update(MessageDigest digest,int value) {
        digest.update((byte)(value>>>24));digest.update((byte)(value>>>16));digest.update((byte)(value>>>8));digest.update((byte)value);
    }
    private static void write(String name,JsonObject value)throws Exception {
        Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve(name),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(value));
    }
    private static double millis(long start){return (System.nanoTime()-start)/1e6;}
    private static void addElapsed(JsonObject out,String name,long start,long end) {
        if(start>0&&end>=start)out.addProperty(name,(end-start)/1e6);else out.add(name,JsonNull.INSTANCE);
    }
    private static ThreadFactory factory(String label) {
        var number=new AtomicInteger();return task->{var t=new Thread(task,"mia-near-prototype-"+label+"-"+number.incrementAndGet());t.setDaemon(true);return t;};
    }
    private static final class TrialTimeout extends CancellationException {
        TrialTimeout(){super("Prototype cooperative 30s CPU trial budget expired");}
    }

    /** Independent outer task lifetime counters observe actual executor tasks, never Future state. */
    private static final class Writers extends ThreadPoolExecutor implements AutoCloseable {
        final AtomicInteger started=new AtomicInteger(),finished=new AtomicInteger(),active=new AtomicInteger(),maximumActive=new AtomicInteger();
        final AtomicLong lastExitNanos=new AtomicLong();
        Writers(String label){super(WORKERS,WORKERS,0,TimeUnit.MILLISECONDS,new LinkedBlockingQueue<>(),factory("phase-"+label));prestartAllCoreThreads();}
        @Override public void execute(Runnable task) {
            super.execute(()->{started.incrementAndGet();int live=active.incrementAndGet();maximumActive.accumulateAndGet(live,Math::max);
                try{task.run();}finally{lastExitNanos.accumulateAndGet(System.nanoTime(),Math::max);active.decrementAndGet();finished.incrementAndGet();}});
        }
        boolean awaitStarted(int required,long deadline) {
            while(started.get()<required) {
                if(System.nanoTime()>=deadline)return false;LockSupport.parkNanos(100_000);
            }return true;
        }
        boolean awaitIdle(int seconds) {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while(active.get()!=0||started.get()!=finished.get()||!getQueue().isEmpty()) {
                if(System.nanoTime()>=deadline)return false;LockSupport.parkNanos(100_000);
            }return true;
        }
        JsonObject snapshot(){var o=new JsonObject();o.addProperty("started",started.get());o.addProperty("exited",finished.get());o.addProperty("active",active.get());o.addProperty("maximumActive",maximumActive.get());o.addProperty("queue",getQueue().size());return o;}
        @Override public void close()throws InterruptedException {
            shutdownNow();if(!awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("Actual prototype phase executor did not exit");
        }
    }
    private static final class CancelControl {
        final String targetPhase;
        final GeneratedSkySource source;final boolean requireOriginal32Ray;
        final AtomicBoolean cancelled=new AtomicBoolean(),gateClaimed=new AtomicBoolean();
        final CountDownLatch inside=new CountDownLatch(1),release=new CountDownLatch(1);
        final CountDownLatch exitHoldEntered=new CountDownLatch(1),exitPermit=new CountDownLatch(1);
        final ThreadLocal<int[]> checks=ThreadLocal.withInitial(()->new int[1]);
        volatile String phase="NOT_STARTED";volatile long deadline;volatile int taskStartBaseline;
        volatile List<String> actualRayStack=List.of();
        CancelControl(String targetPhase,GeneratedSkySource source,boolean requireOriginal32Ray) {
            this.targetPhase=targetPhase;this.source=source;this.requireOriginal32Ray=requireOriginal32Ray;
        }
        void checkpoint() {
            if(cancelled.get()||Thread.currentThread().isInterrupted())throw new CancellationException("Prototype newer target");
            if(System.nanoTime()>=deadline)throw new TrialTimeout();
            if(phase.equals(targetPhase)&&Thread.currentThread().getName().startsWith("mia-near-prototype-phase-")
                    &&++checks.get()[0]>=64
                    &&(!requireOriginal32Ray||actualSourceRayCheckpoint())
                    &&gateClaimed.compareAndSet(false,true)) {
                inside.countDown();
                boolean interrupted=false;
                try {
                    // Test-only slow writer: cancellation must join its real exit even after Future.cancel.
                    interrupted=awaitUninterruptibly(release,30);
                    if(cancelled.get()) {
                        exitHoldEntered.countDown();interrupted|=awaitUninterruptibly(exitPermit,5);
                        throw new CancellationException("Prototype newer target after actual writer exit was permitted");
                    }
                } finally {if(interrupted)Thread.currentThread().interrupt();}
            }
        }
        private boolean actualSourceRayCheckpoint() {
            if(source.probeFlightEvaluations()==0)return false;
            // Test-only observation of the real callback stack; no substitute ray/source is invoked.
            var stack=StackWalker.getInstance().walk(frames->frames.limit(48)
                    .map(frame->frame.getClassName()+"."+frame.getMethodName()).toList());
            boolean sourceRequest=stack.contains("dev.madeinabyss.forge.worldgen.GeneratedSkySource.checkRequest");
            boolean ray=stack.contains("dev.madeinabyss.kernel.light.SkyVisibility.trace")
                    ||stack.contains("dev.madeinabyss.kernel.light.QuantizedDiffuseSky$Ray.step");
            if(!sourceRequest||!ray)return false;
            actualRayStack=stack;return true;
        }
        private static boolean awaitUninterruptibly(CountDownLatch latch,int seconds) {
            boolean interrupted=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
            while(true) {
                long left=deadline-System.nanoTime();if(left<=0)throw new TrialTimeout();
                try {if(latch.await(left,TimeUnit.NANOSECONDS))return interrupted;throw new TrialTimeout();}
                catch(InterruptedException stop){interrupted=true;}
            }
        }
    }
    private static final class Trace implements BiConsumer<String,CubeKey> {
        volatile long start,stop;final Map<String,Long> begin=new LinkedHashMap<>();final Map<CubeKey,Long> ready=new ConcurrentHashMap<>();
        final AtomicBoolean lightReady=new AtomicBoolean();
        @Override public synchronized void accept(String stage,CubeKey key) {
            long now=System.nanoTime();if(key!=null)ready.put(key,now-start);else begin.put(stage,now);
            if(stage.equals("LIGHT_READY"))lightReady.set(true);
        }
        synchronized JsonObject phaseTimes() {
            var o=new JsonObject();var entries=List.copyOf(begin.entrySet());
            for(int i=0;i<entries.size();i++) {
                var entry=entries.get(i);long end=i+1<entries.size()?entries.get(i+1).getValue():stop==0?System.nanoTime():stop;
                if(!entry.getKey().equals("LIGHT_READY"))o.addProperty(entry.getKey(),(end-entry.getValue())/1e6);
            }return o;
        }
        JsonArray cubes() {
            var a=new JsonArray();ready.entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e->{var o=new JsonObject();
                o.addProperty("x",e.getKey().x());o.addProperty("y",e.getKey().y());o.addProperty("z",e.getKey().z());o.addProperty("elapsedMillis",e.getValue()/1e6);a.add(o);});return a;
        }
    }
}
