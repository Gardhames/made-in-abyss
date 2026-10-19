package dev.madeinabyss.kernel.lod;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/** Presentation-only page preparation. Values never become exact owner light. */
public interface SurfaceProvisionalLight extends Function<TerrainLodColumns.Box,SurfaceQuadPlan.Owner> {
    record Page(SurfaceQuadPlan.PageKey key,SurfaceQuadPlan.Geometry geometry){}
    void prepare(List<Page> pages,ExecutorService executor,int parallelism);
    Object dependencies(SurfaceQuadPlan.Geometry geometry);
    long revision(Object dependencies);
    default long viewRevision(){return 0;}
    default int priority(SurfaceQuadPlan.PageKey page){return 0;}
    default void noteExact(TerrainLodColumns.Box box,int sky){}
    default int skyForAudit(TerrainLodColumns.Box box){return apply(box).sky();}
}
