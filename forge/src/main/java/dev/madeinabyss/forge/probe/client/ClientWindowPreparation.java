package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import dev.madeinabyss.kernel.concurrent.OrderedOwnerPreparation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Shared ordering for physical/visual windows and their following deep-channel notifications. */
final class ClientWindowPreparation {
    private static final boolean ASYNC=Boolean.parseBoolean(System.getProperty("mia.render.asyncWindowDecode","true"));
    private static OrderedOwnerPreparation queue;
    private static long decodedWindows,maxDecodeNanos,lastDecodeNanos,lastNativeNanos,lastSnapshotNanos,lastEntityNanos;
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(task->{
        var thread=new Thread(task,"mia-client-window-decode");thread.setDaemon(true);thread.setPriority(Thread.NORM_PRIORITY);return thread;
    });
    private ClientWindowPreparation() { }
    private static OrderedOwnerPreparation queue(){
        if(queue==null)queue=new OrderedOwnerPreparation(WORKER,task->Minecraft.getInstance().execute(task));
        return queue;
    }
    static <T> void submit(Level current,GeneratedFrameCosts.Stage stage,Supplier<OrderedOwnerPreparation.Work<T>> starter){
        if(!ASYNC){inline(stage,starter);return;}
        var requested=new WeakReference<>(current);
        queue().submit(()->{
            if(requested.get()==null || requested.get()!=Minecraft.getInstance().level)return null;
            int timing=GeneratedFrameCosts.begin(stage);OrderedOwnerPreparation.Work<T> work;
            try {work=starter.get();}finally{GeneratedFrameCosts.end(timing);}
            if(work==null)return null;
            return new OrderedOwnerPreparation.Work<>(work.prepare(),(value,error)->{
                if(requested.get()==null || requested.get()!=Minecraft.getInstance().level)return;
                int publishTiming=GeneratedFrameCosts.begin(stage);
                try{work.complete().accept(value,error);}finally{GeneratedFrameCosts.end(publishTiming);}
            });
        });
    }
    static <T> void inline(GeneratedFrameCosts.Stage stage,Supplier<OrderedOwnerPreparation.Work<T>> starter){
        int timing=GeneratedFrameCosts.begin(stage);
        try {
            var work=starter.get();if(work==null)return;
            T value=null;RuntimeException failure=null;
            int decodeTiming=GeneratedFrameCosts.begin(GeneratedFrameCosts.Stage.WINDOW_DECODE);
            try {value=work.prepare().apply(()->{});}catch(RuntimeException e){failure=e;}
            finally{GeneratedFrameCosts.end(decodeTiming);}
            work.complete().accept(value,failure);
        } finally {GeneratedFrameCosts.end(timing);}
    }
    static void after(Runnable packet){
        if(!ASYNC){packet.run();return;}
        var current=Minecraft.getInstance().level;var requested=new WeakReference<Level>(current);boolean hadLevel=current!=null;
        queue().after(()->{
            var target=requested.get();
            if(hadLevel?target!=null&&target==Minecraft.getInstance().level:Minecraft.getInstance().level==null)packet.run();
        });
    }
    static void clear(){if(queue!=null)queue.clear();decodedWindows=maxDecodeNanos=lastDecodeNanos=lastNativeNanos=lastSnapshotNanos=lastEntityNanos=0;}
    static void decoded(ClientWindowFrames.Decoded result){
        decodedWindows++;lastDecodeNanos=result.totalNanos();maxDecodeNanos=Math.max(maxDecodeNanos,lastDecodeNanos);
        lastNativeNanos=result.nativeNanos();lastSnapshotNanos=result.snapshotNanos();lastEntityNanos=result.entityNanos();
    }
    static JsonObject status(){
        var out=new JsonObject();out.addProperty("async",ASYNC);out.addProperty("workerActive",queue!=null&&queue.active());
        out.addProperty("queuedPackets",queue==null?0:queue.pending());out.addProperty("decodedWindows",decodedWindows);
        out.addProperty("lastDecodeMillis",lastDecodeNanos/1e6);out.addProperty("maxDecodeMillis",maxDecodeNanos/1e6);
        out.addProperty("lastNativeDecodeMillis",lastNativeNanos/1e6);out.addProperty("lastSnapshotMillis",lastSnapshotNanos/1e6);out.addProperty("lastEntityValidationMillis",lastEntityNanos/1e6);return out;
    }
}
