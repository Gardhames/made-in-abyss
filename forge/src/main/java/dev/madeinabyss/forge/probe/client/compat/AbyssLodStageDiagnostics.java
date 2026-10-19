package dev.madeinabyss.forge.probe.client.compat;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in stage measurements; never changes admission, source authority or publication. */
final class AbyssLodStageDiagnostics {
    private static final ThreadMXBean CPU=ManagementFactory.getThreadMXBean();
    final boolean enabled=Boolean.getBoolean("mia.lod.stageTimers");
    private final long origin=System.nanoTime();
    private static final class Sample {
        final LongAdder calls=new LongAdder(),wall=new LongAdder(),cpu=new LongAdder();
        final AtomicLong maximum=new AtomicLong();
    }
    private final ConcurrentHashMap<String,Sample> stages=new ConcurrentHashMap<>();
    private final Map<String,Long> reasons=new LinkedHashMap<>();
    private final List<Map<String,Object>> events=new ArrayList<>();
    private AbyssLodPipeline.Target compositionTarget;
    private long compositionFineVersion=-1;
    private boolean previousFallback;
    long now(){return enabled?System.nanoTime():0;}
    long cpu(){return enabled&&CPU.isCurrentThreadCpuTimeSupported()?CPU.getCurrentThreadCpuTime():0;}
    void finish(String name,long start,long cpuStart){if(enabled)record(name,System.nanoTime()-start,Math.max(0,cpu()-cpuStart));}
    void record(String name,long wall,long cpu){if(!enabled)return;var value=stages.computeIfAbsent(name,k->new Sample());
        value.calls.increment();value.wall.add(wall);value.cpu.add(cpu);value.maximum.accumulateAndGet(wall,Math::max);}
    void composition(AbyssLodPipeline.Target target,long fineVersion,boolean fineChanged,boolean protectedChanged,
            boolean cutsChanged,boolean farChanged,boolean retry,boolean fallback,long doubleBytes,long unionBytes,long cap){
        if(!enabled)return;
        boolean initial=compositionTarget==null,retarget=!initial&&!compositionTarget.equals(target),arrival=fineVersion!=compositionFineVersion;
        String primary=initial?"INITIAL":retarget?"RETARGET":fallback&&!previousFallback?"CAP_FALLBACK_ENTER":farChanged?"FAR_CHANGE":cutsChanged?"NATIVE_CUTS":retry?"RETRY":fineChanged||arrival?"FINE_ADOPTION":protectedChanged?"PROTECTED_GRIDS":"OTHER";
        var flags=new ArrayList<String>();if(retarget)flags.add("RETARGET");if(fineChanged||arrival)flags.add("FINE");
        if(protectedChanged)flags.add("PROTECTED");if(cutsChanged)flags.add("CUTS");if(farChanged)flags.add("FAR");if(retry)flags.add("RETRY");if(fallback)flags.add("CAP_FALLBACK");
        synchronized(this){reasons.merge(primary,1L,Long::sum);for(String flag:flags)reasons.merge("flag_"+flag,1L,Long::sum);
            if(events.size()<512){var row=new LinkedHashMap<String,Object>();row.put("atMillis",(System.nanoTime()-origin)/1e6);row.put("request",target.requestRevision());
                row.put("primary",primary);row.put("flags",String.join(",",flags));row.put("fineVersion",fineVersion);row.put("doubleBytes",doubleBytes);row.put("unionBytes",unionBytes);row.put("cap",cap);events.add(Map.copyOf(row));}}
        compositionTarget=target;compositionFineVersion=fineVersion;previousFallback=fallback;
    }
    synchronized Map<String,Object> status(){if(!enabled)return Map.of();var out=new TreeMap<String,Object>();
        stages.forEach((name,value)->{out.put(name+"Calls",value.calls.sum());out.put(name+"WallNanos",value.wall.sum());out.put(name+"CpuNanos",value.cpu.sum());out.put(name+"MaxWallNanos",value.maximum.get());});
        reasons.forEach((name,value)->out.put("build_"+name,value));out.put("eventCount",events.size());return Map.copyOf(out);}
    synchronized List<Map<String,Object>> events(){return List.copyOf(events);}
}
