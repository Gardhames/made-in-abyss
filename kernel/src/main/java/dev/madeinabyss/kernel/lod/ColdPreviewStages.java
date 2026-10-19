package dev.madeinabyss.kernel.lod;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

/** Owner-thread startup acknowledgements and bounded requested-origin near preview planning. */
public final class ColdPreviewStages {
    public static final int MAX_EARLY_DETAIL_TILES=128;
    public enum Stage { NEAR_PREVIEW,NEAR_DETAIL,FULL_PREVIEW,REQUESTED,CANCELLED }
    public record NearPlan(TerrainLodColumns.Bounds bounds,long samples) {}
    private final boolean earlyDetail;
    private Stage stage;
    public ColdPreviewStages(boolean near){this(near,false);}
    public ColdPreviewStages(boolean near,boolean earlyDetail){this.earlyDetail=near&&earlyDetail;stage=near?Stage.NEAR_PREVIEW:Stage.FULL_PREVIEW;}
    public Stage stage(){return stage;}
    public boolean guarded(){return stage==Stage.NEAR_PREVIEW||stage==Stage.NEAR_DETAIL||stage==Stage.FULL_PREVIEW;}
    public void nearDisplayed(){require(Stage.NEAR_PREVIEW);stage=earlyDetail?Stage.NEAR_DETAIL:Stage.FULL_PREVIEW;}
    public void nearDetailDisplayed(){require(Stage.NEAR_DETAIL);stage=Stage.FULL_PREVIEW;}
    public void skipNearDetail(){require(Stage.NEAR_DETAIL);stage=Stage.FULL_PREVIEW;}
    public void fullDisplayed(){require(Stage.FULL_PREVIEW);stage=Stage.REQUESTED;}
    public void bypass(){if(!guarded())throw new IllegalStateException("Startup already ended");stage=Stage.REQUESTED;}
    public void cancel(){stage=Stage.CANCELLED;}
    private void require(Stage expected){if(stage!=expected)throw new IllegalStateException("Unexpected preview acknowledgement: "+stage);}
    /** Bounded startup jobs on the same16XZ/64Y cache partition as the complete plan. */
    public static List<TerrainLodRefinement.Region> detailPrefix(TerrainLodRefinement.Plan plan,double x,double y,double z,int maximum) {
        Objects.requireNonNull(plan);
        if(maximum<1||maximum>MAX_EARLY_DETAIL_TILES||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))
            throw new IllegalArgumentException("Finite camera and bounded early tile count required");
        // Both supported planners produce disjoint slabs. Overlapping arbitrary plans
        // cannot certify prefix admission independently of unprepared later geometry.
        var regions=plan.regions();
        for(int i=0;i<regions.size();i++)for(int j=i+1;j<regions.size();j++) {
            var a=regions.get(i).bounds();var b=regions.get(j).bounds();
            if(a.minX()<=b.maxXInclusive()&&a.maxXInclusive()>=b.minX()&&a.minY()<=b.maxYInclusive()&&a.maxYInclusive()>=b.minY()
                    &&a.minZ()<=b.maxZInclusive()&&a.maxZInclusive()>=b.minZ())return List.of();
        }
        var tiles=new ArrayList<TerrainLodRefinement.Region>();
        for(var region:regions) {
            var b=region.bounds();
            for(long xx=b.minX();xx<=(long)b.maxXInclusive();xx=tileEnd(xx,b.maxXInclusive(),16))
                for(long zz=b.minZ();zz<=(long)b.maxZInclusive();zz=tileEnd(zz,b.maxZInclusive(),16))
                    for(long yy=b.minY();yy<=(long)b.maxYInclusive();yy=tileEnd(yy,b.maxYInclusive(),64))
                        tiles.add(new TerrainLodRefinement.Region(new TerrainLodColumns.Bounds((int)xx,(int)yy,(int)zz,
                                (int)(tileEnd(xx,b.maxXInclusive(),16)-1),(int)(tileEnd(yy,b.maxYInclusive(),64)-1),(int)(tileEnd(zz,b.maxZInclusive(),16)-1)),region.horizontalStep()));
        }
        tiles.sort(Comparator.<TerrainLodRefinement.Region>comparingDouble(tile->{var b=tile.bounds();
            double dx=Math.max(0,Math.max(b.minX()-x,x-((long)b.maxXInclusive()+1)));
            double dy=Math.max(0,Math.max(b.minY()-y,y-((long)b.maxYInclusive()+1)));
            double dz=Math.max(0,Math.max(b.minZ()-z,z-((long)b.maxZInclusive()+1)));return dx*dx+dy*dy+dz*dz;})
                .thenComparingInt(tile->Math.floorDiv(tile.bounds().minX(),16)).thenComparingInt(tile->Math.floorDiv(tile.bounds().minZ(),16))
                .thenComparingInt(tile->tile.bounds().minY()).thenComparingInt(tile->tile.bounds().maxYInclusive()).thenComparingInt(TerrainLodRefinement.Region::horizontalStep));
        return List.copyOf(tiles.subList(0,Math.min(maximum,tiles.size())));
    }
    private static long tileEnd(long start,int maximum,int size){return Math.min((long)maximum+1,(Math.floorDiv(start,size)+1)*size);}
    /** Reserve a changed complete plan plus every freshly sampled startup tile. */
    public static boolean earlyFitsBudget(long fixedSamples,long maximumDetailSamples,int maximumTiles,long cap) {
        if(fixedSamples<0||maximumDetailSamples<0||maximumTiles<1||maximumTiles>MAX_EARLY_DETAIL_TILES||cap<1)
            throw new IllegalArgumentException("Nonnegative samples and bounded early tile budget required");
        if(fixedSamples>cap)return false;
        long remaining=cap-fixedSamples;
        return maximumDetailSamples<=remaining&&16L*64*16*maximumTiles<=remaining-maximumDetailSamples;
    }
    public static Optional<NearPlan> near(TerrainLodColumns.Bounds world,double x,double y,double z,long budget) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||budget<1)return Optional.empty();
        long xx=(long)Math.floor(x),yy=(long)Math.floor(y),zz=(long)Math.floor(z);
        if(xx<Integer.MIN_VALUE||xx>Integer.MAX_VALUE||yy<Integer.MIN_VALUE||yy>Integer.MAX_VALUE||zz<Integer.MIN_VALUE||zz>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Preview camera outside signed world");
        long x0=Math.max(world.minX(),alignedDown(xx-160,world.minX()));
        long z0=Math.max(world.minZ(),alignedDown(zz-160,world.minZ()));
        long x1=Math.min((long)world.maxXInclusive()+1,alignedUp(xx+161,world.minX()));
        long z1=Math.min((long)world.maxZInclusive()+1,alignedUp(zz+161,world.minZ()));
        long y0=Math.max(world.minY(),yy-192),y1=Math.min((long)world.maxYInclusive()+1,yy+192);
        if(x0>=x1||y0>=y1||z0>=z1)return Optional.empty();
        var bounds=new TerrainLodColumns.Bounds((int)x0,(int)y0,(int)z0,(int)(x1-1),(int)(y1-1),(int)(z1-1));
        if(bounds.equals(world))return Optional.empty();
        long samples=new TerrainLodRefinement.Region(bounds,16).sampledVoxels();
        return samples<=budget?Optional.of(new NearPlan(bounds,samples)):Optional.empty();
    }
    private static long alignedDown(long value,int origin){return origin+Math.floorDiv(value-origin,16)*16;}
    private static long alignedUp(long value,int origin){return origin+Math.floorDiv(value-origin+15,16)*16;}
}
