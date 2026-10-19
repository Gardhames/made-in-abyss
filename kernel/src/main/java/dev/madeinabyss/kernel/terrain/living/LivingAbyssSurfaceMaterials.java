package dev.madeinabyss.kernel.terrain.living;

/** Section23: a unit-interval patch field and exposed-soil admission. */
public final class LivingAbyssSurfaceMaterials {
    private LivingAbyssSurfaceMaterials(){}
    public static double patch(long seed,int x,int z){return .5+.5*LivingAbyssPlan.noise(seed,4,x,0,z,2020);}
    public static LivingAbyssGeometry.Kind top(double patch,double pick){
        if(patch<.05)return LivingAbyssGeometry.Kind.COARSE_DIRT;
        if(patch<.10)return pick<.45?LivingAbyssGeometry.Kind.ANDESITE:pick<.80?LivingAbyssGeometry.Kind.BOULDER_STONE:LivingAbyssGeometry.Kind.MOSSY_COBBLESTONE;
        return patch>.78?LivingAbyssGeometry.Kind.MOSS:LivingAbyssGeometry.Kind.GRASS;
    }
    public static LivingAbyssGeometry.Kind side(int depth,boolean exposed,double pick){
        if(depth<2||!exposed)return LivingAbyssGeometry.Kind.SOIL;
        return pick<.60?LivingAbyssGeometry.Kind.BODY_ROCK:pick<.85?LivingAbyssGeometry.Kind.MOSSY_COBBLESTONE:LivingAbyssGeometry.Kind.MOSS;
    }
}
