package dev.madeinabyss.kernel.cube;

/** Camera intent is independent of immutable geometry authority. Owner writes, workers read. */
public final class NativeTargetGeneration {
    public record Target(long generation,int anchorX,int anchorY,int anchorZ,int radius,int requestedRadius) { }
    private volatile long generation;
    private Target target;
    public Target request(double x,double y,double z,int radius,int requestedRadius,boolean teleport) {
        int ax=anchor(x),ay=anchor(y),az=anchor(z);
        if(radius<1||requestedRadius<radius)throw new IllegalArgumentException("Invalid native target radius");
        if(target==null||teleport||ax!=target.anchorX()||ay!=target.anchorY()||az!=target.anchorZ()
                ||radius!=target.radius()||requestedRadius!=target.requestedRadius())
            target=new Target(Math.incrementExact(generation),ax,ay,az,radius,requestedRadius);
        generation=target.generation();return target;
    }
    public long generation(){return generation;}
    public Target requestAppliedTeleport(double x,double feetY,double z,double eyeHeight,int radius,int requestedRadius){
        if(!Double.isFinite(eyeHeight))throw new IllegalArgumentException("Invalid camera eye height");
        return request(x,feetY+eyeHeight,z,radius,requestedRadius,true);
    }
    public boolean current(long candidate){return candidate==generation;}
    public void retire(){generation=Math.incrementExact(generation);target=null;}
    private static int anchor(double x){
        if(!Double.isFinite(x)||Math.floor(x)<Integer.MIN_VALUE||Math.floor(x)>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Camera outside signed XYZ");
        return Math.multiplyExact(Math.floorDiv((int)Math.floor(x),16),16);
    }
}
