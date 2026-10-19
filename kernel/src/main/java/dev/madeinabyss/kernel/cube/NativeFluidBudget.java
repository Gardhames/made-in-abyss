package dev.madeinabyss.kernel.cube;

/** Bounded double-sided native ocean mesh, including the renderer's eight-metre fringe. */
public final class NativeFluidBudget {
    public static final int MAX_FACES=196608,UPLOAD_BATCH_FACES=8192;
    private NativeFluidBudget(){}
    public static int facesForRadius(int radius){
        if(radius<1||radius>128)throw new IllegalArgumentException("Unsupported native fluid radius");
        int width=2*(radius+8);
        return Math.min(MAX_FACES,Math.max(8192,3*width*width));
    }
    /** Check before mutating a pending candidate; failure leaves displayed geometry authoritative. */
    public static int add(int current,int incoming,int budget){
        if(current<0||incoming<0||budget<1||budget>MAX_FACES)throw new IllegalArgumentException("Invalid fluid face admission");
        long next=(long)current+incoming;
        if(next>budget)throw new IllegalStateException("Generated fluid face budget exceeded "+budget);
        return (int)next;
    }
    /** Conservative 512 bytes/quad including vertices, immutable lists, block metadata; displayed plus pending. */
    public static long maximumRetainedCpuBytes(){return 2L*MAX_FACES*512;}
    /** Conservative 128 bytes/vertex for supported shader vertex formats, four vertices/quad. */
    public static long maximumBatchBytes(){return 4L*UPLOAD_BATCH_FACES*128;}
}
