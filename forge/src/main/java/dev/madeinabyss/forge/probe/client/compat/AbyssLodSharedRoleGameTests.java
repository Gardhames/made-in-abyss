package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.kernel.concurrent.BoundedReuseCache;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual CPU pipeline/worker/registry dependency probe; no native GPU, acquisition-retarget or WORLD proof. */
@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodSharedRoleGameTests {
    static Object field(Object o,String name)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static Object call(Object o,String name,Object... args)throws Exception {
        for(Method m:o.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){
            m.setAccessible(true);try{return m.invoke(o,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}
        throw new NoSuchMethodException(name);
    }
    static void require(boolean value,String text){if(!value)throw new AssertionError(text);}
    static <T>T on(ExecutorService e,Callable<T> task)throws Exception{return e.submit(task).get(5,TimeUnit.SECONDS);}
    static AbyssLodPipeline.Proposal proposal(AbyssLodPipeline p,long seconds)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<end){var next=p.poll();if(next.isPresent())return next.orElseThrow();
            require(String.valueOf(p.status().get("error")).isEmpty(),"Pipeline error: "+p.status());Thread.sleep(2);}
        throw new AssertionError("Bounded fixture setup: no staged proposal; "+p.status());
    }
    @GameTest(template="empty",timeoutTicks=1200)
    @SuppressWarnings("unchecked") public static void promotedSharedOwnerDoesNotWaitBehindBackgroundPrefix(GameTestHelper h)throws Exception{
        var release=new CountDownLatch(1);var enabled=new AtomicBoolean();
        var allowed=new AtomicReference<TerrainLodColumns.Box>();
        Set<TerrainLodColumns.Box> blocked=ConcurrentHashMap.newKeySet(),interrupted=ConcurrentHashMap.newKeySet();
        var calls=new ConcurrentHashMap<TerrainLodColumns.Box,AtomicInteger>();
        AbyssLodPipeline.BoxLighting lighting=box->{
            calls.computeIfAbsent(box,k->new AtomicInteger()).incrementAndGet();
            if(enabled.get()&&box.minX()<-128&&!box.equals(allowed.get())){
                blocked.add(box);try{release.await();}catch(InterruptedException e){interrupted.add(box);Thread.currentThread().interrupt();throw new CancellationException("Controlled BG prefix canceled");}}
            return new AbyssLodPipeline.Light(7,0);
        };
        try(var p=new AbyssLodPipeline(true)){
            var target=new AbyssLodPipeline.Target("shared-role-cpu",1,"roof",1,1,1,1);
            var camera=new AbyssLodPipeline.Camera(256,8,8,0,0,1);
            p.request(new AbyssLodPipeline.Input(target,camera,new TerrainLodColumns.Bounds(240,0,0,271,15,31),
                new TerrainLodColumns.Bounds(-1024,0,-1024,1023,15,1023),List.of(new TerrainLodRefinement.Band(128,1)),List.of(),
                (x,y,z)->new TerrainSample.Known(y==15?TerrainMaterial.ROCK:TerrainMaterial.AIR),lighting,AbyssLodPipeline.Limits.defaults()));
            long setupEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            var first=proposal(p,10);enabled.set(true);require(p.acknowledge(first,true),"Initial simulated WORLD ACK refused");
            long lastAcknowledged=first.sequence();AbyssLodPipeline.Proposal held=null;
            Object context=field(p,"context");long generation=((Number)field(p,"generation")).longValue();
            var coordinator=(ExecutorService)field(p,"coordinator");
            var cache=(BoundedReuseCache<TerrainLodColumns.Box,AbyssLodPipeline.Light>)field(context,"light");
            var registry=(IncrementalSurfacePages)field(context,"incrementalPages");
            TerrainLodColumns.Box[] pair=null;
            while(pair==null&&System.nanoTime()<setupEnd){
                var next=p.poll();
                require(String.valueOf(p.status().get("error")).isEmpty(),"Pipeline setup refusal: "+p.status());
                if(next.isEmpty()){Thread.sleep(2);continue;}
                held=next.orElseThrow();
                pair=on(coordinator,()->{
                var jobs=(List<Object>)field(context,"pageLightJobs");synchronized(jobs){for(Object job:jobs){
                    if((boolean)call(job,"local"))continue;
                    var owners=(List<TerrainLodColumns.Box>)call(job,"owners");
                    for(int i=0;i<owners.size()-1;i++)if(blocked.contains(owners.get(i))){
                        for(int j=i+1;j<owners.size();j++){var s=owners.get(j);
                            if(cache.get(s)==null&&!blocked.contains(s)&&registry.containsOwner(s))return new TerrainLodColumns.Box[]{owners.get(i),s};}
                    }
                }}return null;});
                if(pair==null){require(p.acknowledge(held,true),"Current positive setup proposal ACK refused");lastAcknowledged=held.sequence();held=null;}
            }
            require(pair!=null,"Fixture setup cannot find a live BG job with blocked prefix and unstarted retained owner");
            var r=pair[0];var s=pair[1];allowed.set(s);
            // Existing R remains inside release.await(); newly dispatched work is free.
            // Thus a promoted LOCAL task cannot recreate the artificial prefix gate.
            enabled.set(false);
            var footprint=new TerrainLodClipper.BoundsLong(s.minX(),16,s.minZ(),s.maxXExclusive(),32,s.maxZExclusive());
            Comparator<SurfaceQuadPlan.PageKey> order=(Comparator<SurfaceQuadPlan.PageKey>)call(p,"surfacePageOrder",camera,128);
            on(coordinator,()->{registry.update(List.of(),List.of(),List.of(footprint),false);
                require(registry.containsOwner(s)&&registry.isUnlitLocalOwner(s),"AIR footprint must promote exact unchanged S owner into LOCAL debt");return null;});
            System.out.println("SETUP actual BG prefix R="+r+"; unstarted exact S="+s+"; sourceGeneration="+generation+"; heldSequence="+held.sequence());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean adopted=false;
            while(System.nanoTime()<end){adopted=on(coordinator,()->{
                call(p,"dispatchPageLight",context,order,true,generation);call(p,"adoptPageLight",context,generation);
                return cache.get(s)!=null&&!registry.isUnlitLocalOwner(s);});if(adopted)break;Thread.sleep(5);}
            require(adopted,"LOCAL exact S stayed claimed behind unrelated blocked BG prefix R");
            require(interrupted.contains(r),"Dependent BG prefix R must be interrupted without source cancellation");
            require(calls.get(s)!=null&&calls.get(s).get()==1,"S original physical BOX scalar must compute exactly once");
            require(new AbyssLodPipeline.Light(7,0).equals(cache.get(s)),"S scalar cache must preserve exact original SKY/BLOCK 7/0");
            require(registry.containsOwner(s),"Promotion must preserve S physical BOX membership");
            require(((Number)field(p,"generation")).longValue()==generation&&field(p,"context")==context,"Dependent job retirement must preserve source token/context");
            require(p.poll().orElseThrow()==held&&held.target().equals(target),"Pending unacknowledged proposal identity/target must remain exact");
            require(((Number)p.status().get("acknowledgedSequence")).longValue()==lastAcknowledged,"Probe must not invent a WORLD ACK after holding the dependent-owner proposal");
            System.out.println("PASS exact dependent cancellation, scalar reuse, source token and held proposal");
        }finally{release.countDown();}
        h.succeed();
    }
}
