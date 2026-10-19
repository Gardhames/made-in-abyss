package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.light.SkyVisibility;
import dev.madeinabyss.kernel.terrain.living.LivingAbyssSupportedFaces155B2.Face;

/** Geometry-phase-bound ecological SKY, queried at actual support-face neighbors. */
public final class LivingAbyssGeometrySky155B2 {
    public record LitFace(Face face,int sky,String lightIdentity) {}
    public record Stats(long raySolves,long pointHits,long columnBuilds,int cachedPoints,int cachedColumns) {}
    public LivingAbyssGeometrySky155B2(String geometryIdentity,int openSkyY,int budget,int capacity,SkyVisibility.VoxelOpacity opacity){}
    public LitFace illuminate(Face face){return new LitFace(face,-1,"");}
    public String identity(){return "";}
    public Stats stats(){return new Stats(0,0,0,0,0);}
}
