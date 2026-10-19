package dev.madeinabyss.forge.probe.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;

/** Single render-owner native staging buffer. Retain capacity across cube/layer/scene builds;
 * never depend on finalizer throughput to retire thousands of explicit malloc allocations. */
public final class NativeMeshBuffers {
    private static BufferBuilder builder;
    private static BufferBuilder.RenderedBuffer outstanding;
    private static Thread owner;
    private static boolean leased,building;
    private static long buildersCreated,builds,cancellations,retiredBuilders;
    private static String lastCancelFailure="";
    private NativeMeshBuffers(){}
    public static BufferBuilder begin(VertexFormat.Mode mode,VertexFormat format){
        checkOwner();if(leased)throw new IllegalStateException("Native staging view is still owned");
        if(builder==null){builder=new BufferBuilder(16384);buildersCreated++;}
        builder.begin(mode,format);leased=building=true;return builder;
    }
    public static BufferBuilder.RenderedBuffer end(){
        checkOwner();if(!building)throw new IllegalStateException("Native staging is not building");
        outstanding=builder.end();building=false;builds++;return outstanding;
    }
    /** Called after manual release, or in finally once VertexBuffer.upload has taken ownership.
     * The mapped upload method releases its input on both success and failure. */
    public static void complete(){
        checkOwner();if(building)throw new IllegalStateException("Native staging build has not ended");
        outstanding=null;leased=false;
    }
    public static void cancel(){
        checkOwner();if(!leased)return;
        boolean clean=false;
        try{if(building){outstanding=builder.end();building=false;}if(outstanding!=null)outstanding.release();clean=true;}
        catch(RuntimeException failure){lastCancelFailure=failure.toString();}
        finally{if(!clean){builder=null;retiredBuilders++;}outstanding=null;building=leased=false;cancellations++;}
    }
    private static void checkOwner(){
        var current=Thread.currentThread();if(owner==null)owner=current;
        else if(owner!=current)throw new IllegalStateException("Native staging used outside its render owner");
    }
    /** Logical native capacity, not driver or allocator-resident memory. No GL query. */
    public static JsonObject status(){
        var out=new JsonObject();out.addProperty("buildersCreated",buildersCreated);out.addProperty("completedBuilds",builds);
        out.addProperty("cancellations",cancellations);out.addProperty("leased",leased);out.addProperty("building",building);
        out.addProperty("retiredBuilders",retiredBuilders);out.addProperty("lastCancelFailure",lastCancelFailure);
        long capacity=0;
        if(builder!=null)try{for(var field:BufferBuilder.class.getDeclaredFields())if(field.getType()==ByteBuffer.class){field.setAccessible(true);var value=(ByteBuffer)field.get(builder);if(value!=null)capacity+=value.capacity();}}
        catch(ReflectiveOperationException|RuntimeException unavailable){out.addProperty("capacityError",unavailable.toString());capacity=-1;}
        out.addProperty("nativeCapacityBytes",capacity);return out;
    }
}
