package dev.madeinabyss.forge.probe.client.compat.lightgpu;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL46C.*;

/** Context-affine exact light service. */
public final class GpuLightWorker implements AutoCloseable {
    private record Job(int[] owners,Runnable checkpoint,CompletableFuture<int[]> result){}
    private final ArrayBlockingQueue<Job> queue=new ArrayBlockingQueue<>(16);
    private final AtomicLong queuedOwners=new AtomicLong(),completedOwners=new AtomicLong(),submissions=new AtomicLong();
    private final Object frameLock=new Object();
    private final Thread owner;
    private final long creatorThread=Thread.currentThread().getId(),budgetNanos;
    private long window,frameNumber,frameGpuNanos,budgetSpentNanos,totalGpuNanos,maximumFrameGpuNanos,dispatches,maxDispatchNanos,lastFrameGpuNanos,budgetWaitNanos;
    private final Map<String,Long> stageMaximum=new HashMap<>();
    private final Map<String,Long> stageActualMaximum=new HashMap<>();
    private volatile String state="BOOTING",error="";
    private volatile boolean closed,terminated;
    private volatile Map<String,Object> kernelStatus=Map.of();
    private volatile double ownersPerSecond,setupMillis;
    private volatile int activeOwners;
    private volatile long activeStartNanos,activeNanos,lastCompletionNanos;
    private record Phase(String name,long start){}
    private volatile Phase phase=new Phase("boot",System.nanoTime());
    private final ConcurrentHashMap<String,Long> phaseNanos=new ConcurrentHashMap<>();
    private long completedBatches,checkpointCalls,checkpointNanos,maxCheckpointNanos;
    private void phase(String next){long now=System.nanoTime();var previous=phase;phaseNanos.merge(previous.name(),now-previous.start(),Long::sum);phase=new Phase(next,now);}
    private void checkpoint(Job job,String where){phase(where);long start=System.nanoTime();try{job.checkpoint().run();}finally{long elapsed=System.nanoTime()-start;checkpointCalls++;checkpointNanos+=elapsed;maxCheckpointNanos=Math.max(maxCheckpointNanos,elapsed);}}

    /** GLFW creation/destruction stays on the main thread; the context is current only on its worker. */
    public static GpuLightWorker open(long share,Path snapshot,Path shaders,double budgetMillis){
        return open(share,snapshot,name->Files.readString(shaders.resolve(name)),budgetMillis);
    }
    public static GpuLightWorker open(long share,Path snapshot,GpuOwnerSkyKernel.ShaderSource shaders,double budgetMillis){
        return open(share,snapshot,shaders,budgetMillis,()->{});
    }
    public static GpuLightWorker open(long share,Path snapshot,GpuOwnerSkyKernel.ShaderSource shaders,double budgetMillis,Runnable validateSnapshot){
        if(GLFW.glfwGetCurrentContext()!=share||share==0)throw new IllegalStateException("Shared worker must be created on the current main context owner");
        if(!Double.isFinite(budgetMillis)||budgetMillis<=0||budgetMillis>1.5)throw new IllegalArgumentException("GPU frame budget must be in (0,1.5] ms");
        GLFW.glfwDefaultWindowHints();long window;
        try{GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,6);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
            window=GLFW.glfwCreateWindow(16,16,"MIA exact light worker",0,share);
        }finally{GLFW.glfwDefaultWindowHints();}
        if(window==0)throw new IllegalStateException("Shared GL4.6 context unavailable");
        return new GpuLightWorker(window,snapshot,shaders,(long)(budgetMillis*1e6),validateSnapshot);
    }
    private GpuLightWorker(long window,Path snapshot,GpuOwnerSkyKernel.ShaderSource shaders,long budgetNanos,Runnable validateSnapshot){
        this.window=window;this.budgetNanos=budgetNanos;
        owner=new Thread(()->run(snapshot,shaders,validateSnapshot),"mia-gpu-exact-light");owner.setDaemon(true);owner.setPriority(Thread.NORM_PRIORITY-1);owner.start();
    }
    public CompletableFuture<int[]> solve(int[] owners,Runnable checkpoint){
        Objects.requireNonNull(checkpoint);if(owners.length==0||owners.length%8!=0||owners.length>GpuOwnerSkyKernel.BATCH*8)throw new IllegalArgumentException("Invalid GPU owner request");
        var future=new CompletableFuture<int[]>();
        if(closed||state.equals("FAILED")||terminated){future.completeExceptionally(new IllegalStateException("GPU light unavailable: "+state+" "+error));return future;}
        var job=new Job(owners.clone(),checkpoint,future);queuedOwners.addAndGet(owners.length/8);
        if(!queue.offer(job)){queuedOwners.addAndGet(-owners.length/8);future.completeExceptionally(new RejectedExecutionException("GPU exact light queue full"));}
        else{submissions.incrementAndGet();if((closed||terminated||state.equals("FAILED"))&&queue.remove(job)){queuedOwners.addAndGet(-owners.length/8);future.completeExceptionally(new IllegalStateException("GPU light stopped: "+error));}}
        return future;
    }
    private void run(Path snapshot,GpuOwnerSkyKernel.ShaderSource shaders,Runnable validateSnapshot){
        int query=0;IntBuffer input=null;
        try{
            phase("context");GLFW.glfwMakeContextCurrent(window);var caps=GL.createCapabilities();
            if(!caps.OpenGL46||!caps.GL_ARB_gpu_shader_int64||!caps.GL_ARB_gpu_shader_fp64)throw new IllegalStateException("GL4.6/int64/fp64 unavailable");
            query=glGenQueries();int timer=query;input=MemoryUtil.memAllocInt(GpuOwnerSkyKernel.BATCH*8);long setup=System.nanoTime();phase("snapshot.validate");validateSnapshot.run();phase("kernel.init");
            try(var kernel=new GpuOwnerSkyKernel(snapshot,shaders)){
                kernel.scheduling(512,(stage,commands)->timedDispatch(timer,stage,commands));
                kernel.progress(this::phase);
                kernel.beginScene();kernelStatus=Map.copyOf(kernel.status());setupMillis=(System.nanoTime()-setup)/1e6;state="READY";
                while(!closed){phase("idle.queue");var first=queue.poll(250,TimeUnit.MILLISECONDS);if(first==null)continue;
                    var jobs=new ArrayList<Job>();jobs.add(first);int n=first.owners().length/8;
                    while(queue.peek()!=null&&n+queue.peek().owners().length/8<=GpuOwnerSkyKernel.BATCH){var next=queue.poll();if(next==null)break;jobs.add(next);n+=next.owners().length/8;}
                    queuedOwners.addAndGet(-n);activeOwners=n;activeStartNanos=System.nanoTime();
                    try{
                        input.clear();var valid=new ArrayList<Job>();
                        for(var job:jobs){try{if(job.result().isCancelled())throw new CancellationException("GPU request canceled");checkpoint(job,"checkpoint.before");phase("cpu.copy-owners");input.put(job.owners());valid.add(job);}catch(CancellationException canceled){job.result().completeExceptionally(canceled);}}
                        if(!valid.isEmpty()){
                            phase("kernel.solve");input.flip();int[] result=kernel.solve(input,input.remaining()/8);int offset=0;
                            // Retire only after a complete prepare/rays/resolve group. A
                            // half-canceled prepare would poison reserved fraction flags.
                            for(var job:valid){int count=job.owners().length/8;try{checkpoint(job,"checkpoint.after");phase("cpu.complete-result");job.result().complete(Arrays.copyOfRange(result,offset,offset+count));completedOwners.addAndGet(count);lastCompletionNanos=System.nanoTime();}catch(CancellationException canceled){job.result().completeExceptionally(canceled);}offset+=count;}
                        }
                        phase("kernel.status");kernelStatus=Map.copyOf(kernel.status());completedBatches++;
                    }catch(Throwable failed){for(var job:jobs)job.result().completeExceptionally(failed);throw failed;}
                    finally{activeNanos+=System.nanoTime()-activeStartNanos;ownersPerSecond=completedOwners.get()*1e9/Math.max(1,activeNanos);activeOwners=0;activeStartNanos=0;}
                }
            }
        }catch(InterruptedException stopped){if(!closed){error=stopped.toString();state="FAILED";}Thread.currentThread().interrupt();}
        catch(Throwable failed){error=failed.toString();state="FAILED";}
        finally{
            phase("shutdown");Job job;while((job=queue.poll())!=null){queuedOwners.addAndGet(-job.owners().length/8);job.result().completeExceptionally(new IllegalStateException("GPU light stopped: "+error));}
            if(input!=null)MemoryUtil.memFree(input);if(query!=0)glDeleteQueries(query);
            GL.setCapabilities(null);GLFW.glfwMakeContextCurrent(0);if(closed)state="CLOSED";terminated=true;
        }
    }
    private void timedDispatch(int query,String stage,Runnable commands){
        phase("budget."+stage);long waitStart=System.nanoTime();
        synchronized(frameLock){while(!closed&&(frameNumber==0||budgetSpentNanos+stageMaximum.getOrDefault(stage,250_000L)>budgetNanos)){
            try{frameLock.wait(25);}catch(InterruptedException canceled){Thread.currentThread().interrupt();throw new CancellationException("GPU budget interrupted");}
        }if(closed)throw new CancellationException("GPU light closed");budgetWaitNanos+=System.nanoTime()-waitStart;}
        phase("timer.begin."+stage);glBeginQuery(GL_TIME_ELAPSED,query);try{phase("submit."+stage);commands.run();}finally{glEndQuery(GL_TIME_ELAPSED);}
        // The only blocking GL readback is on this private worker, never on the render thread.
        phase("timer.readback."+stage);long elapsed=glGetQueryObjectui64(query,GL_QUERY_RESULT);
        phase("timer.account."+stage);
        synchronized(frameLock){dispatches++;totalGpuNanos+=elapsed;frameGpuNanos+=elapsed;budgetSpentNanos+=elapsed;maximumFrameGpuNanos=Math.max(maximumFrameGpuNanos,frameGpuNanos);maxDispatchNanos=Math.max(maxDispatchNanos,elapsed);
            // A rare over-budget dispatch must not starve its stage forever.
            long observed=Math.min(budgetNanos,Math.max(10_000L,elapsed));
            // A lifetime maximum forces every cheap later prefix to wait a whole frame
            // after one cold/mixed outlier. Charge actual time, carry debt, and use a
            // decaying estimate only as a small reservation before the next dispatch.
            stageMaximum.compute(stage,(key,previous)->previous==null?observed:Math.max(10_000L,(previous+observed)/2));}
        synchronized(frameLock){stageActualMaximum.merge(stage,elapsed,Math::max);}
    }
    /** Once per actual rendered frame. No GL call and no wait for worker progress. */
    public void frame(){synchronized(frameLock){lastFrameGpuNanos=frameGpuNanos;frameGpuNanos=0;budgetSpentNanos=Math.max(0,budgetSpentNanos-budgetNanos);frameNumber++;frameLock.notifyAll();}}
    public boolean ready(){return !closed&&!terminated&&state.equals("READY");}
    public Map<String,Object> status(){var out=new LinkedHashMap<String,Object>();out.put("state",state);out.put("error",error);out.put("queuedOwners",queuedOwners.get());out.put("queue",queue.size());out.put("activeOwners",activeOwners);out.put("completedOwners",completedOwners.get());out.put("submissions",submissions.get());out.put("ownersPerSecond",ownersPerSecond);out.put("setupMillis",setupMillis);
        long now=System.nanoTime();var current=phase;out.put("phase",current.name());out.put("phaseAgeMillis",(now-current.start())/1e6);out.put("batchAgeMillis",activeStartNanos==0?0:(now-activeStartNanos)/1e6);out.put("lastCompletionAgeMillis",lastCompletionNanos==0?-1:(now-lastCompletionNanos)/1e6);out.put("completedBatches",completedBatches);out.put("checkpointCalls",checkpointCalls);out.put("checkpointMillis",checkpointNanos/1e6);out.put("checkpointMaxMillis",maxCheckpointNanos/1e6);
        long activeDuration=activeNanos+(activeStartNanos==0?0:now-activeStartNanos);out.put("ownersPerSecondLive",completedOwners.get()*1e9/Math.max(1,activeDuration));var exclusive=new TreeMap<String,Double>();phaseNanos.forEach((name,nanos)->exclusive.put(name,nanos/1e6));exclusive.merge(current.name(),(now-current.start())/1e6,Double::sum);out.put("phaseWallMillis",exclusive);
        synchronized(frameLock){out.put("dispatches",dispatches);out.put("gpuMillisPerFrame",lastFrameGpuNanos/1e6);out.put("gpuMeanMillisPerFrame",frameNumber==0?0:totalGpuNanos/(double)frameNumber/1e6);out.put("gpuMaxMillisPerFrame",maximumFrameGpuNanos/1e6);out.put("maxDispatchMillis",maxDispatchNanos/1e6);out.put("gpuBudgetMillis",budgetNanos/1e6);out.put("budgetWaitMillis",budgetWaitNanos/1e6);out.put("frames",frameNumber);for(var stage:stageActualMaximum.entrySet())out.put("max"+stage.getKey()+"Millis",stage.getValue()/1e6);}
        out.put("vramBytes",kernelStatus.getOrDefault("totalBufferBytes",0));out.put("fractionJobs",kernelStatus.getOrDefault("fractionJobs",0));out.put("quantizedJobs",kernelStatus.getOrDefault("quantizedJobs",0));return Map.copyOf(out);
    }
    /** Poll on the creating thread; never joins or waits for GL. */
    public boolean pollClosed(){if(Thread.currentThread().getId()!=creatorThread)throw new IllegalStateException("GLFW cleanup must run on creating thread");if(!terminated)return false;if(window!=0){GLFW.glfwDestroyWindow(window);window=0;}return true;}
    @Override public void close(){closed=true;owner.interrupt();synchronized(frameLock){frameLock.notifyAll();}}
}
