package dev.madeinabyss.forge.probe.client;

import com.google.gson.*;
import java.lang.management.*;
import java.nio.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Explicit read-only capture; never called on every frame and never purges an allocator. */
public final class ClientMemoryDiagnostics {
    private ClientMemoryDiagnostics(){}
    public static JsonObject status(){
        var result=new JsonObject();result.addProperty("startedAtMillis",System.currentTimeMillis());result.addProperty("startedNanos",System.nanoTime());
        var memory=ManagementFactory.getMemoryMXBean();result.add("heap",usage(memory.getHeapMemoryUsage()));result.add("nonHeap",usage(memory.getNonHeapMemoryUsage()));
        result.addProperty("pendingFinalization",memory.getObjectPendingFinalizationCount());result.addProperty("pid",ProcessHandle.current().pid());
        var pools=new JsonArray();for(var bean:ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)){
            var row=new JsonObject();row.addProperty("name",bean.getName());row.addProperty("count",bean.getCount());row.addProperty("capacityBytes",bean.getTotalCapacity());row.addProperty("memoryUsedBytes",bean.getMemoryUsed());pools.add(row);}
        result.add("bufferPools",pools);var collectors=new JsonArray();for(var bean:ManagementFactory.getGarbageCollectorMXBeans()){
            var row=new JsonObject();row.addProperty("name",bean.getName());row.addProperty("collections",bean.getCollectionCount());row.addProperty("timeMillis",bean.getCollectionTime());collectors.add(row);}
        result.add("gc",collectors);String allocator=MemoryUtil.getAllocator(false).getClass().getName();result.addProperty("allocator",allocator);
        result.addProperty("trackedAllocator",MemoryUtil.getAllocator(true).getClass().getName());result.add("jemalloc",jemalloc(allocator));
        result.add("nativeMeshStaging",NativeMeshBuffers.status());result.addProperty("finishedAtMillis",System.currentTimeMillis());result.addProperty("finishedNanos",System.nanoTime());return result;
    }
    private static JsonObject usage(MemoryUsage usage){
        var value=new JsonObject();value.addProperty("usedBytes",usage.getUsed());value.addProperty("committedBytes",usage.getCommitted());value.addProperty("maxBytes",usage.getMax());return value;
    }
    private static JsonObject jemalloc(String allocator){
        var result=new JsonObject();if(!allocator.contains("JEmalloc")){result.addProperty("available",false);result.addProperty("reason","Actual allocator is not jemalloc");return result;}
        try(var stack=MemoryStack.stackPush()){
            var method=Class.forName("org.lwjgl.system.jemalloc.JEmalloc").getMethod("je_mallctl",CharSequence.class,ByteBuffer.class,PointerBuffer.class,ByteBuffer.class);
            var epoch=stack.malloc(8).order(ByteOrder.nativeOrder()).putLong(0,1L);
            int code=(Integer)method.invoke(null,"epoch",null,null,epoch);result.addProperty("epochResult",code);result.addProperty("available",code==0);if(code!=0)return result;
            boolean any=false;
            for(String name:new String[]{"allocated","active","resident","mapped","retained","metadata"}){
                int width=org.lwjgl.system.Pointer.POINTER_SIZE;var value=stack.malloc(width).order(ByteOrder.nativeOrder());var length=stack.mallocPointer(1).put(0,width);
                code=(Integer)method.invoke(null,"stats."+name,value,length,null);result.addProperty(name+"Result",code);
                if(code==0){result.addProperty(name+"Bytes",width==8?value.getLong(0):Integer.toUnsignedLong(value.getInt(0)));any=true;}
            }
            result.addProperty("statsAvailable",any);
            if(!any)result.addProperty("reason","Installed jemalloc does not expose stats.*; result codes are preserved");
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){result.addProperty("available",false);result.addProperty("reason",unavailable.toString());}
        return result;
    }
}
