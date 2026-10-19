package dev.madeinabyss.kernel.volume;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;

/** Conservative brick reachability; unavailable opacity stays UNKNOWN. */
public final class ReachableOpacityVolume {
    private final CubeKey min,max;
    private final int nx,ny,nz;
    private final VoxelBrick[] bricks;
    private final AtomicIntegerArray visited;
    private final LongAdder completed=new LongAdder(),proofs=new LongAdder(),uniform=new LongAdder(),mixed=new LongAdder();
    private final LongAdder opaque=new LongAdder(),transparent=new LongAdder(),partial=new LongAdder();
    private final AtomicLong pending=new AtomicLong(),peakPending=new AtomicLong();
    private boolean sealed;
    private double seconds;
    private ReachableOpacityVolume(CubeKey min,CubeKey max) {
        this.min=Objects.requireNonNull(min);this.max=Objects.requireNonNull(max);
        nx=Math.addExact(Math.subtractExact(max.x(),min.x()),1);ny=Math.addExact(Math.subtractExact(max.y(),min.y()),1);nz=Math.addExact(Math.subtractExact(max.z(),min.z()),1);
        long count=(long)nx*ny*nz;if(nx<1||ny<1||nz<1||count>32000000)throw new IllegalArgumentException("Bounded signed brick domain required");
        Math.multiplyExact(min.x(),16);Math.multiplyExact(min.y(),16);Math.multiplyExact(min.z(),16);Math.addExact(Math.multiplyExact(max.x(),16),15);Math.addExact(Math.multiplyExact(max.y(),16),15);Math.addExact(Math.multiplyExact(max.z(),16),15);
        bricks=new VoxelBrick[(int)count];visited=new AtomicIntegerArray((int)count);
    }
    public static final class TimedOut extends CancellationException {
        private final Map<String,Object> progress;
        TimedOut(Map<String,Object> progress){super("Reachable opacity build timed out; no seal published");this.progress=progress;}
        public Map<String,Object> progress(){return progress;}
    }
    public static ReachableOpacityVolume build(CubeKey min,CubeKey max,Predicate<CubeKey> literalAirProof,SparseVoxelVolume.Producer producer,int workers,Duration timeout,Runnable checkpoint) {
        Objects.requireNonNull(literalAirProof);Objects.requireNonNull(producer);Objects.requireNonNull(checkpoint);
        if(workers<1||workers>64||timeout.isNegative()||timeout.isZero())throw new IllegalArgumentException("Positive workers/timeout required");
        var volume=new ReachableOpacityVolume(min,max);var queue=new LinkedBlockingQueue<Integer>();long started=System.nanoTime(),deadline=Math.addExact(started,timeout.toNanos());
        var failure=new AtomicReference<Throwable>();Runnable check=()->{if(Thread.currentThread().isInterrupted()||failure.get()!=null)throw new CancellationException("Reachable opacity build cancelled");checkpoint.run();if(System.nanoTime()>deadline)throw new TimeoutSignal();};
        var seeds=new ArrayList<Integer>();var air=VoxelBrick.uniform(-1,0);
        try {
            for(int i=0;i<volume.bricks.length;i++){if((i&255)==0)check.run();var key=volume.key(i);if(literalAirProof.test(key)){volume.visited.set(i,2);volume.bricks[i]=air;volume.completed.increment();volume.proofs.increment();volume.uniform.increment();volume.transparent.increment();seeds.add(i);}}
            // Any light/segment may enter the finite volume from outside. Seeding every
            // boundary is conservative even where that boundary later proves opaque.
            for(int i=0;i<volume.bricks.length;i++){var key=volume.key(i);if(key.x()==min.x()||key.x()==max.x()||key.y()==min.y()||key.y()==max.y()||key.z()==min.z()||key.z()==max.z())volume.enqueue(i,queue);}
            for(int i:seeds)volume.neighbors(volume.key(i),ALL_NEIGHBORS,queue);
            var pool=Executors.newFixedThreadPool(workers);var futures=new ArrayList<Future<?>>();
            try {
                for(int w=0;w<workers;w++)futures.add(pool.submit(()->{try{
                    while(true){check.run();Integer i=queue.poll(2,TimeUnit.MILLISECONDS);if(i==null){if(volume.pending.get()==0)return;continue;}
                        var key=volume.key(i);var brick=Objects.requireNonNull(producer.capture(key,check));check.run();
                        if(!brick.allKnown())throw new IllegalArgumentException("UNKNOWN opacity cannot certify flood closure at "+key);
                        int mask=openings(brick);volume.bricks[i]=brick;volume.visited.set(i,2);volume.completed.increment();
                        if(brick.uniformOpacity()){volume.uniform.increment();int op=brick.opacityAt(0);if(op==15)volume.opaque.increment();else if(op==0)volume.transparent.increment();else volume.partial.increment();}else volume.mixed.increment();
                        volume.neighbors(key,mask,queue);volume.pending.decrementAndGet();
                    }
                }catch(Throwable failed){failure.compareAndSet(null,failed);if(failed instanceof InterruptedException)Thread.currentThread().interrupt();throw new CompletionException(failed);}}));
                for(var future:futures)future.get();
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();failure.compareAndSet(null,new CancellationException("Interrupted flood coordinator"));}
              catch(ExecutionException failed){failure.compareAndSet(null,failed.getCause());}
            finally{pool.shutdownNow();try{if(!pool.awaitTermination(30,TimeUnit.SECONDS))throw new IllegalStateException("Flood workers did not retire");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
            volume.seconds=(System.nanoTime()-started)/1e9;
            var failed=failure.get();if(failed!=null){if(failed instanceof TimeoutSignal)throw new TimedOut(volume.status());if(failed instanceof RuntimeException runtime)throw runtime;if(failed instanceof Error error)throw error;throw new IllegalStateException(failed);}
            if(volume.pending.get()!=0)throw new IllegalStateException("Incomplete reachability queue");check.run();volume.sealed=true;return volume;
        }catch(TimeoutSignal timedOut){volume.seconds=(System.nanoTime()-started)/1e9;throw new TimedOut(volume.status());}
    }
    private static final class TimeoutSignal extends RuntimeException {}
    private static final int ALL_NEIGHBORS=((1<<27)-1)&~(1<<13);
    private static int openings(VoxelBrick brick) {
        if(brick.uniformOpacity())return brick.opacityAt(0)<15?ALL_NEIGHBORS:0;
        int mask=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            if(x!=0&&x!=15&&y!=0&&y!=15&&z!=0&&z!=15)continue;
            if(brick.opacityAt((y<<8)|(z<<4)|x)>=15)continue;
            // Simultaneous DDA crossings can skip the axis-adjacent cells at an
            // edge/corner. Include those exact contacts, not just six face neighbors.
            for(int dy=y==0?-1:0;dy<=(y==15?1:0);dy++)for(int dz=z==0?-1:0;dz<=(z==15?1:0);dz++)for(int dx=x==0?-1:0;dx<=(x==15?1:0);dx++)
                if(dx!=0||dy!=0||dz!=0)mask|=1<<((dz+1)*9+(dy+1)*3+dx+1);
        }return mask;
    }
    private void neighbors(CubeKey key,int mask,LinkedBlockingQueue<Integer> queue) {
        for(int bit=0;bit<27;bit++)if((mask&(1<<bit))!=0){var next=new CubeKey(key.x()+bit%3-1,key.y()+(bit/3)%3-1,key.z()+bit/9-1);if(contains(next))enqueue(index(next),queue);}
    }
    private void enqueue(int index,LinkedBlockingQueue<Integer> queue){if(visited.compareAndSet(index,0,1)){long value=pending.incrementAndGet();peakPending.accumulateAndGet(value,Math::max);queue.add(index);}}
    private int index(CubeKey key){return ((key.y()-min.y())*nz+key.z()-min.z())*nx+key.x()-min.x();}
    private CubeKey key(int index){return new CubeKey(min.x()+index%nx,min.y()+index/(nx*nz),min.z()+(index/nx)%nz);}
    public boolean reachable(CubeKey key){return sealed&&contains(key)&&visited.get(index(key))==2;}
    public int opacityOrUnknown(int x,int y,int z){var key=new CubeKey(x>>4,y>>4,z>>4);var brick=brick(key);return brick==null?-1:brick.opacityAt(SparseVoxelVolume.index(x,y,z));}
    public boolean contains(CubeKey key){return key.x()>=min.x()&&key.x()<=max.x()&&key.y()>=min.y()&&key.y()<=max.y()&&key.z()>=min.z()&&key.z()<=max.z();}
    public VoxelBrick brick(CubeKey key){return reachable(key)?bricks[index(key)]:null;}
    public Map<String,Object> status(){var out=new LinkedHashMap<String,Object>();out.put("sealed",sealed);out.put("wallSeconds",seconds);out.put("totalBricks",bricks.length);out.put("completedBricks",completed.sum());out.put("provenAirSeeds",proofs.sum());out.put("uniformOpacityBricks",uniform.sum());out.put("uniformOpaqueBricks",opaque.sum());out.put("uniformZeroOpacityBricks",transparent.sum());out.put("uniformPartialOpacityBricks",partial.sum());out.put("mixedBricks",mixed.sum());out.put("pending",pending.get());out.put("peakPending",peakPending.get());return Collections.unmodifiableMap(out);}
}
