package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Paired pre-T3/current standalone benchmark; never a timing-sensitive JUnit test. */
public final class T3LodMicrobenchmark {
    private static volatile long sink;
    private static volatile Object sampleSink;
    private static final com.sun.management.ThreadMXBean ALLOCATION=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    @FunctionalInterface interface Task {long run()throws Exception;}
    private record Measurement(double ns,double cpu,double bytes,long checksum){}
    private static Measurement run(Task task,int count)throws Exception {
        long thread=Thread.currentThread().getId(),startBytes=ALLOCATION.getThreadAllocatedBytes(thread),sum=0,startCpu=ALLOCATION.getCurrentThreadCpuTime(),start=System.nanoTime();
        for(int i=0;i<count;i++)sum+=task.run();long elapsed=System.nanoTime()-start;
        long cpu=ALLOCATION.getCurrentThreadCpuTime()-startCpu,bytes=ALLOCATION.getThreadAllocatedBytes(thread)-startBytes;sink=sum;return new Measurement((double)elapsed/count,(double)cpu/count,(double)bytes/count,sum);
    }
    private static void compare(String name,Task old,Task actual,int repetitions)throws Exception {
        for(int warm=0;warm<3;warm++){run(old,repetitions);run(actual,repetitions);}
        double[] nsBefore=new double[7],nsAfter=new double[7],cpuBefore=new double[7],cpuAfter=new double[7],bytesBefore=new double[7],bytesAfter=new double[7];
        for(int round=0;round<7;round++) {
            Measurement a,b;if((round&1)==0){a=run(old,repetitions);b=run(actual,repetitions);}else{b=run(actual,repetitions);a=run(old,repetitions);}
            if(a.checksum()!=b.checksum())throw new AssertionError("Benchmark output differs: "+name);
            nsBefore[round]=a.ns();nsAfter[round]=b.ns();cpuBefore[round]=a.cpu();cpuAfter[round]=b.cpu();bytesBefore[round]=a.bytes();bytesAfter[round]=b.bytes();
            System.out.printf(Locale.ROOT,"ROUND,%s,%d,%.3f,%.3f,%.1f,%.1f,%.3f,%.3f%n",name,round,a.ns(),b.ns(),a.bytes(),b.bytes(),a.cpu(),b.cpu());
        }
        Arrays.sort(nsBefore);Arrays.sort(nsAfter);Arrays.sort(cpuBefore);Arrays.sort(cpuAfter);Arrays.sort(bytesBefore);Arrays.sort(bytesAfter);
        System.out.printf(Locale.ROOT,"MEDIAN,%s,%.3f,%.3f,%.4f,%.1f,%.1f,%.3f,%.3f%n",name,nsBefore[3],nsAfter[3],nsBefore[3]/nsAfter[3],bytesBefore[3],bytesAfter[3],cpuBefore[3],cpuAfter[3]);
    }
    private static void pageUpdates()throws Exception {
        var actual=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128,true);
        var old=new T3ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),128,true);
        var all=new ArrayList<TerrainLodColumns.Box>();var original=new ArrayList<TerrainLodColumns.Box>();var replacement=new ArrayList<TerrainLodColumns.Box>();
        for(int page=0;page<32;page++)for(int i=0;i<512;i++) {
            var box=T3LodOracleTest.box((page-16)*128L+1+(i%16)*4,1+(i/256)*16,(i/16%16)*4+1,2,8,2,TerrainMaterial.ROCK);all.add(box);
            if(page==16&&i%32==0){original.add(box);replacement.add(new TerrainLodColumns.Box(box.minX(),box.minY(),box.minZ(),box.maxXExclusive(),box.maxYExclusive()+1,box.maxZExclusive(),box.material()));}
        }
        actual.update(List.of(),all);old.update(List.of(),all);int[] a={0},b={0};
        compare("page-update-32x512-change16",()->{boolean forward=(a[0]++&1)==0;old.update(forward?original:replacement,forward?replacement:original);return 1;},
                ()->{boolean forward=(b[0]++&1)==0;actual.update(forward?original:replacement,forward?replacement:original);return 1;},16);
        var executor=Executors.newFixedThreadPool(3);
        try {
            compare("page-coordinator-32x512-change16",()->{boolean forward=(a[0]++&1)==0;old.update(forward?original:replacement,forward?replacement:original,List.of(),false,executor,3);return 1;},
                    ()->{boolean forward=(b[0]++&1)==0;actual.update(forward?original:replacement,forward?replacement:original,List.of(),false,executor,3);return 1;},128);
        } finally {executor.shutdownNow();}
    }
    private static void metadata()throws Exception {
        var actual=new IncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64,true);var old=new T3ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits.defaults(),64,true);
        var all=new ArrayList<TerrainLodColumns.Box>();for(int i=0;i<1024;i++)all.add(T3LodOracleTest.box(i*64L+1,-10,1,1,1,1,TerrainMaterial.ROCK));
        actual.update(List.of(),all);old.update(List.of(),all);actual.setLightLookup(T3LodOracleTest::light);old.setLightLookup(T3LodOracleTest::light);
        actual.preparePartial(null,10000,T3LodOracleTest.ORDER);old.preparePartial(null,10000,T3LodOracleTest.ORDER);int[] a={0},b={0};
        compare("page-local-metadata-1024-remote",()->{long x=-2_000_000L+a[0]++*128;old.update(List.of(),List.of(),List.of(new TerrainLodClipper.BoundsLong(x,-10,1,x+1,-9,2)),false);return 1;},
                ()->{long x=-2_000_000L+b[0]++*128;actual.update(List.of(),List.of(),List.of(new TerrainLodClipper.BoundsLong(x,-10,1,x+1,-9,2)),false);return 1;},32);
    }
    private static void priority()throws Exception {
        var raw=T3LodAllocationTest.priorityBoxes().subList(0,32);var plan=T3LodAllocationTest.priorityPlan();var bounds=plan.regions().get(0).bounds();
        var index=AbyssLodSurfacePriority.index(raw,100000,16);var oldIndex=T3ReferenceAbyssLodSurfacePriority.index(raw,100000,16);
        for(int cap:new int[]{128,262144})compare("surface-prefix-cap"+cap,
                ()->T3ReferenceAbyssLodSurfacePriority.prefix(bounds,plan,oldIndex,List.of(),.125,-512.5,.75,.25,0,1,cap).hashCode(),
                ()->AbyssLodSurfacePriority.prefix(bounds,plan,index,List.of(),.125,-512.5,.75,.25,0,1,cap).hashCode(),1);
    }
    private static void extraction()throws Exception {
        var boxes=T3LodAllocationTest.denseBoxes(1024);
        compare("surface-extract-1024-owners",()->T3ReferenceSurfaceExtraction.extract(boxes,100000).faces().size(),
                ()->SurfaceQuadPlan.extractForMerge(boxes,100000,9600000).faces().size(),16);
    }
    private static void codec(Path directory)throws Exception {
        var tiles=T3TileCodecOracleTest.denseTiles();var oldTiles=T3TileCodecOracleTest.oldKeys(tiles);var oldDirectory=directory.resolve("old");
        TerrainTileDiskCache.save(directory,T3TileCodecOracleTest.ID,tiles);T3ReferenceTerrainTileDiskCache.save(oldDirectory,T3TileCodecOracleTest.ID,oldTiles);
        compare("tile-decode-4096-boxes",()->T3ReferenceTerrainTileDiskCache.load(oldDirectory,T3TileCodecOracleTest.ID).tiles().values().iterator().next().size(),
                ()->TerrainTileDiskCache.load(directory,T3TileCodecOracleTest.ID).tiles().values().iterator().next().size(),24);
        compare("tile-encode-4096-boxes",()->{T3ReferenceTerrainTileDiskCache.save(oldDirectory,T3TileCodecOracleTest.ID,oldTiles);return 4096;},
                ()->{TerrainTileDiskCache.save(directory,T3TileCodecOracleTest.ID,tiles);return 4096;},8);
    }
    private static void samples()throws Exception {
        var materials=TerrainMaterial.values();int[] a={0},b={0};
        compare("known-material-sample",()->{sampleSink=new TerrainSample.Known(materials[Math.floorMod(a[0]++,materials.length)]);return 1;},
                ()->{sampleSink=LodTerrainSamples.known(materials[Math.floorMod(b[0]++,materials.length)]);return 1;},262144);
    }
    public static void main(String[] args)throws Exception {
        if(!ALLOCATION.isThreadAllocatedMemoryEnabled())ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        System.out.println("VM,"+System.getProperty("java.runtime.version")+","+ManagementFactory.getGarbageCollectorMXBeans().stream().map(x->x.getName()).toList());
        System.out.println("# 3 paired warmups, 7 alternating rounds; ns/call and owner-thread allocated bytes/call");
        pageUpdates();metadata();priority();extraction();codec(Path.of(args[0]));samples();
    }
}
