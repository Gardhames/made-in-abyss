package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.lod.*;
import java.util.*;
import java.util.function.Function;

/** Bounded camera audit. UNKNOWN references never certify quality; only a current WORLD ACK does. */
public final class VisibleLightAudit {
    public record Sample(TerrainLodColumns.Box box,SurfaceQuadPlan.PageKey page,ProvisionalSkyPages.Point point){}
    public record Frame(String scope,long sequence,int total,int referenceKnown,int displayed,int within,int falseDark,int falseBright,Map<TerrainLodColumns.Box,Integer> errors){public Frame{errors=Map.copyOf(errors);}}
    public record Status(int samples,int referenceKnown,int displayed,int within,int falseDark,int falseBright,double coverage,double correctFraction,double falseDarkFraction,double falseBrightFraction,long sequence,double firstCorrectWorldMillis){}
    private final String scope;private final List<Sample> samples;private final Set<TerrainLodColumns.Box> membership;
    private final int tolerance;private final double requiredFraction;
    private final Map<TerrainLodColumns.Box,Integer> reference=new HashMap<>();
    private final Map<SurfaceQuadPlan.PageKey,Set<TerrainLodColumns.Box>> required=new HashMap<>();
    private final Set<SurfaceQuadPlan.PageKey> visiblePages;
    private record Presented(SurfaceQuadPlan.Prepared prepared,Map<TerrainLodColumns.Box,Integer> values){}
    private Map<SurfaceQuadPlan.PageKey,Presented> presented=Map.of();
    private Map<TerrainLodColumns.Box,Integer> errors=Map.of();
    private volatile Status status;
    public VisibleLightAudit(String scope,List<Sample> input,int tolerance,double requiredFraction){
        if(scope==null||scope.isBlank()||tolerance<0||tolerance>15||!Double.isFinite(requiredFraction)||requiredFraction<=0||requiredFraction>1)throw new IllegalArgumentException("Valid audit scope/threshold required");
        var unique=new LinkedHashMap<TerrainLodColumns.Box,Sample>();for(var s:input)unique.putIfAbsent(s.box,s);
        if(unique.isEmpty()||unique.size()>4096)throw new IllegalArgumentException("Audit requires1..4096 owners");
        this.scope=scope;this.samples=List.copyOf(unique.values());membership=Set.copyOf(unique.keySet());this.tolerance=tolerance;this.requiredFraction=requiredFraction;
        var unknown=new LinkedHashMap<TerrainLodColumns.Box,Integer>();for(var s:samples)unknown.put(s.box,16);errors=Map.copyOf(unknown);
        for(var s:samples)required.computeIfAbsent(s.page,k->new HashSet<>()).add(s.box);
        visiblePages=Set.copyOf(required.keySet());
        status=new Status(samples.size(),0,0,0,0,0,0,0,0,0,-1,-1);
    }
    public List<Sample> samples(){return samples;}
    /** Pages with an actual known-AIR, clear-LoS witness, rather than an AABB in the frustum. */
    public Set<SurfaceQuadPlan.PageKey> visiblePages(){return visiblePages;}
    public int pagePriority(SurfaceQuadPlan.PageKey page,int discrepancy){
        valid(discrepancy);return visiblePages.contains(page)?Math.max(3,discrepancy):0;
    }
    public void reference(TerrainLodColumns.Box box,int sky){
        if(!membership.contains(box))return;valid(sky);var old=reference.putIfAbsent(box,sky);
        if(old!=null&&old!=sky)throw new IllegalArgumentException("Audit reference changed within immutable source scope");
    }
    public Frame frame(long sequence,Function<Sample,Integer> displayed){
        int known=0,shown=0,within=0,dark=0,bright=0;var errors=new LinkedHashMap<TerrainLodColumns.Box,Integer>();
        for(var s:samples){Integer exact=reference.get(s.box),value=displayed.apply(s);if(exact!=null)known++;if(value!=null){valid(value);shown++;}
            if(exact==null||value==null){errors.put(s.box,16);continue;}
            int delta=value-exact;if(delta>tolerance)bright++;else if(delta< -tolerance)dark++;else within++;
            if(Math.abs(delta)>tolerance)errors.put(s.box,Math.abs(delta));
        }
        return new Frame(scope,sequence,samples.size(),known,shown,within,dark,bright,errors);
    }
    /** Read immutable publication bytes, including retained pages; a newer cache is not a WORLD draw. */
    public Frame presentedFrame(long sequence,SurfaceQuadPlan.PageSet publication){
        var next=new HashMap<SurfaceQuadPlan.PageKey,Presented>();
        for(var page:publication.pages()){
            var wanted=required.get(page.key());if(wanted==null)continue;
            var old=presented.get(page.key());
            if(old!=null&&old.prepared==page.prepared()){next.put(page.key(),old);continue;}
            var found=new HashMap<TerrainLodColumns.Box,Integer>();
            for(var owner:page.prepared().owners())if(wanted.contains(owner.box())){
                found.put(owner.box(),owner.sky());if(found.size()==wanted.size())break;
            }
            next.put(page.key(),new Presented(page.prepared(),Map.copyOf(found)));
        }
        presented=next;return frame(sequence,s->{var page=next.get(s.page);return page==null?null:page.values.get(s.box);});
    }
    public boolean worldDraw(Frame frame,boolean currentWorld,double millis){
        if(!currentWorld||!scope.equals(frame.scope)||frame.sequence<=status.sequence)return false;
        if(!Double.isFinite(millis)||millis<0||frame.total!=samples.size())throw new IllegalArgumentException("Invalid WORLD audit proof");
        double first=status.firstCorrectWorldMillis;
        if(first<0&&(double)frame.within/frame.total>=requiredFraction)first=millis;
        errors=frame.errors;status=new Status(frame.total,frame.referenceKnown,frame.displayed,frame.within,frame.falseDark,frame.falseBright,
                (double)frame.referenceKnown/frame.total,(double)frame.within/frame.total,(double)frame.falseDark/frame.total,(double)frame.falseBright/frame.total,frame.sequence,first);return true;
    }
    public Status status(){return status;}
    public List<TerrainLodColumns.Box> priorityOwners(){
        var result=new ArrayList<TerrainLodColumns.Box>();for(var s:samples)if(errors.containsKey(s.box))result.add(s.box);
        result.sort(Comparator.comparingInt((TerrainLodColumns.Box b)->errors.get(b)).reversed());return List.copyOf(result);
    }
    private static void valid(int sky){if(sky<0||sky>15)throw new IllegalArgumentException("Audit SKY must be0..15");}
}
