package dev.madeinabyss.kernel.cube;

import java.util.*;

/** Bounded owner/worker event journal. Times are monotonic and relative to applied intent. */
public final class NativeLatencyTrace {
    public record Target(long targetGeneration,long teleportAppliedWallMillis,List<Double> targetPosition,
                         int requestedRadius,String cacheMode,String reason,double nativeMillis,double handoffMillis,
                         List<Integer> nativeCube,int nativeFaces,long coverageRevision,boolean cancelled,double cancellationMillis,
                         boolean sourceCachesFresh,boolean diskRestoreBypassed,boolean inSolveMemoization,
                         double cutWorldAckMillis,boolean handoffCoverageComplete,String handoffPendingReason,
                         boolean nativeWindowCacheHit) {}
    public record Event(long targetGeneration,String stage,List<Integer> cube,long revision,double millis) {}
    private static final class State {
        final long generation,origin,wall;final double x,y,z;final int radius;final String cacheMode,reason;
        Long nativeAt,handoffAt,cutAt,cancelAt,exitAt,fullDrawAt;
        long coverage=-1,fullDrawCoverage=-1;
        CubeKey nativeCube;int faces;boolean full,complete,cacheHit,fresh,diskBypassed,memo;
        int activeJobs;
        State(long g,long n,long w,double x,double y,double z,int r,String m,String why){generation=g;origin=n;wall=w;this.x=x;this.y=y;this.z=z;radius=r;cacheMode=m;reason=why;}
        boolean nearby(CubeKey key){return Math.abs((long)key.x()-cube(x))<=1&&Math.abs((long)key.y()-cube(y))<=1&&Math.abs((long)key.z()-cube(z))<=1;}
        Target snapshot(){return new Target(generation,wall,List.of(x,y,z),radius,cacheMode,reason,
                elapsed(nativeAt),elapsed(handoffAt),key(nativeCube),faces,coverage,cancelAt!=null,
                cancelAt!=null&&exitAt!=null?(exitAt-cancelAt)/1_000_000.0:-1,
                fresh,diskBypassed,memo,elapsed(cutAt),complete,
                handoffAt!=null?"":!full?"FULL_NATIVE_PENDING":fullDrawCoverage!=coverage?"FULL_NATIVE_WORLD_DRAW_PENDING":!complete?"FULL_WINDOW_CUT_COVERAGE_PENDING":"CUT_WORLD_ACK_PENDING",cacheHit);}
        double elapsed(Long n){return n==null?-1:(n-origin)/1_000_000.0;}
    }
    private final LinkedHashMap<Long,State> history=new LinkedHashMap<>();
    private final ArrayDeque<Event> journal=new ArrayDeque<>();private long current=-1;
    public synchronized void begin(long generation,long nano,long wall,double x,double y,double z,int radius,String cacheMode,String reason) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||radius<1||generation<=current)
            throw new IllegalArgumentException("Invalid or stale native trace target");
        int cx=cube(x),cy=cube(y),cz=cube(z);
        current=generation;history.put(generation,new State(generation,nano,wall,x,y,z,radius,cacheMode,reason));
        while(history.size()>128)history.remove(history.keySet().iterator().next());
        phase(generation,"DESIRED",null,-1,nano);
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++)
            phase(generation,"CUBE_DESIRED",new CubeKey(cx+dx,cy+dy,cz+dz),-1,nano);
    }
    public synchronized void commit(long generation,long coverage,boolean full,long nano) {
        var t=active(generation);if(t==null)return;t.coverage=coverage;t.full=full;t.fullDrawCoverage=-1;t.fullDrawAt=null;t.complete=false;t.cutAt=null;
        phase(generation,full?"FULL_COMMIT":"NEAR_COMMIT",null,coverage,nano);
    }
    public synchronized void draw(long generation,long coverage,CubeKey cube,int faces,boolean world,boolean full,long nano) {
        var t=active(generation);if(t==null||!world||faces<=0||cube==null||coverage!=t.coverage||full!=t.full)return;
        if(full){if(t.fullDrawAt==null)phase(generation,"FIRST_FULL_NATIVE_WORLD_DRAW",cube,coverage,nano);t.fullDrawCoverage=coverage;t.fullDrawAt=nano;}
        if(t.nativeAt==null&&t.nearby(cube)){t.nativeAt=nano;t.nativeCube=cube;t.faces=faces;phase(generation,"FIRST_NATIVE_WORLD_DRAW",cube,coverage,nano);}
        complete(t);
    }
    /** A coherent retained mesh may draw near a new moving target before its replacement commits. */
    public synchronized void retainedDraw(long generation,CubeKey cube,int faces,boolean coherentWorld,long nano) {
        var t=active(generation);if(t==null||!coherentWorld||faces<=0||cube==null||t.nativeAt!=null||!t.nearby(cube))return;
        t.nativeAt=nano;t.nativeCube=cube;t.faces=faces;
        phase(generation,"FIRST_RETAINED_NATIVE_WORLD_DRAW",cube,-1,nano);
    }
    public synchronized void cutAck(long generation,long coverage,boolean completeCoverage,long nano) {
        var t=active(generation);if(t==null||coverage!=t.coverage)return;
        t.cutAt=nano;t.complete=completeCoverage;phase(generation,"CUT_WORLD_ACK",null,coverage,nano);
        complete(t);
    }
    private static void complete(State t){if(t.full&&t.nativeAt!=null&&t.fullDrawAt!=null&&t.fullDrawCoverage==t.coverage&&t.complete&&t.cutAt!=null&&t.handoffAt==null)
        t.handoffAt=Math.max(t.nativeAt,Math.max(t.fullDrawAt,t.cutAt));}
    public synchronized void cacheScope(long generation,boolean sourceFresh,boolean diskBypassed,boolean inSolveMemoization){
        var t=active(generation);if(t!=null){t.fresh=sourceFresh;t.diskBypassed=diskBypassed;t.memo=inSolveMemoization;}
    }
    public synchronized void cacheHit(long generation){var t=active(generation);if(t!=null)t.cacheHit=true;}
    public synchronized void cancel(long generation,long nano) {
        var t=history.get(generation);if(t==null||t.cancelAt!=null)return;t.cancelAt=nano;
        if(t.activeJobs==0)t.exitAt=nano;else t.exitAt=null;
        event(t,"CANCEL_REQUESTED",null,-1,nano);
    }
    public synchronized void workQueued(long generation,long nano){
        var t=active(generation);if(t==null)return;t.activeJobs++;t.exitAt=null;event(t,"WORK_RESERVED",null,-1,nano);
    }
    public synchronized void workerExited(long generation,long nano) {
        var t=history.get(generation);if(t==null)return;if(t.activeJobs>0)t.activeJobs--;
        if(t.activeJobs==0)t.exitAt=nano;event(t,"WORKERS_EXITED",null,-1,nano);
    }
    public synchronized void phase(long generation,String stage,CubeKey cube,long revision,long nano) {
        var t=active(generation);if(t==null)return;event(t,stage,cube,revision,nano);
    }
    private void event(State t,String stage,CubeKey cube,long revision,long nano){
        journal.addLast(new Event(t.generation,stage,key(cube),revision,t.elapsed(nano)));while(journal.size()>8192)journal.removeFirst();
    }
    private State active(long generation){var t=history.get(generation);return generation==current&&t!=null&&t.cancelAt==null?t:null;}
    public synchronized List<Target> targets(){return history.values().stream().map(State::snapshot).toList();}
    public synchronized List<Event> events(){return List.copyOf(journal);}
    public synchronized void clear(){history.clear();journal.clear();current=-1;}
    private static List<Integer> key(CubeKey key){return key==null?List.of():List.of(key.x(),key.y(),key.z());}
    private static int cube(double coordinate){
        if(Math.floor(coordinate)<Integer.MIN_VALUE||Math.floor(coordinate)>Integer.MAX_VALUE)throw new IllegalArgumentException("Trace target outside signed XYZ");
        return Math.floorDiv((int)Math.floor(coordinate),16);
    }
}
