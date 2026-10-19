package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.terrain.*;
import java.util.*;
import java.io.*;
import java.security.*;

/** Immutable catalogue of real v14 shelf caps AFTER opening. Deterministic 4m contour census,
 * exact per-voxel geometry predicates; no voxel writes or RNG depending on traversal order. */
public final class LivingAbyssPlan {
    public enum Archetype { MEADOW,GROVE,LAKE,TERRACES,ROCK_GARDEN,RUIN,OVERHANG }
    @FunctionalInterface public interface Solid {boolean at(int x,int y,int z);}
    @FunctionalInterface public interface Radius {double at(int x,int y,int z);}
    public record Ledge(String id,int sector,int band,double theta,double halfAngle,double topDepth,double width,
                        double projection,boolean added,boolean shortened,boolean cascade,Archetype archetype,
                        int x,int y,int z,int footprintCells,int lakeDepth,int terraces,int rise,int boulderHeight,int thickness) {}
    public record Neighbour(String upper,String lower,double overlap){}
    private final TerrainParameters parameters;
    private final LivingAbyssRecipe recipe;
    private final LivingAbyssFields fields;
    private final List<Ledge> ledges;
    private final List<Neighbour> neighbours;
    private final List<String> conflicts;
    private final String digest;
    private final long buildNanos;
    private final int sectors;
    private final Map<Integer,List<Ledge>> bySector;
    private final List<Ledge> additions;
    private final LivingAbyssRevision155 revision155;
    private static final class Candidate {
        final String id;final int sector,band;final double theta,half,top,width,projection;
        final boolean added;boolean shortened,cascade;Archetype type;int x,y,z;
        final TreeSet<Long> footprint=new TreeSet<>();
        Candidate(String id,int sector,int band,double theta,double half,double top,double width,double projection,boolean added){this.id=id;this.sector=sector;this.band=band;this.theta=theta;this.half=half;this.top=top;this.width=width;this.projection=projection;this.added=added;}
    }
    public LivingAbyssPlan(TerrainParameters p,LivingAbyssRecipe recipe,LivingAbyssFields fields,Solid solid,Radius opening,Radius wall) {
        long start=System.nanoTime();this.parameters=p;this.recipe=recipe;this.fields=fields;
        if(recipe.revision().startsWith("15.5")){
            revision155=new LivingAbyssRevision155(recipe,fields);sectors=1;
            ledges=revision155.catalogue();neighbours=List.of();conflicts=revision155.diagnostics();
            bySector=Map.of();additions=ledges;digest=revision155.digest();buildNanos=System.nanoTime()-start;return;
        }
        revision155=null;
        var c=p.cavity();sectors=(int)Math.max(1,Math.ceil(2*Math.PI*c.mouthRadius()/p.noise().wavelength()));
        int bands=(int)Math.floor((c.firstLayerTopY()-c.declaredLowerBoundY())/c.ledgePeriod())+3;
        var candidates=new ArrayList<Candidate>();var hidden=new ArrayList<Candidate>();var diagnostics=new ArrayList<String>();
        for(int sector=0;sector<sectors;sector++)for(int band=0;band<bands;band++) {
            double top=(band+.2+.6*unit(p.seed(),sector,band,21))*c.ledgePeriod()+(2*unit(p.seed(),sector,band,22)-1)*p.noise().ledgeVerticalAmplitude();
            if(c.firstLayerTopY()-top+p.noise().surfaceAmplitude()<=c.declaredLowerBoundY()||c.firstLayerTopY()-top-p.noise().surfaceAmplitude()>=-250)continue;
            double theta=LivingAbyssFields.normalize(90+sector*360.0/sectors);
            var q=new Candidate("v14-s"+sector+"-b"+band,sector,band,theta,360.0/sectors,top,
                c.ledgeWidth()*(.6+.8*unit(p.seed(),sector,band,23)),c.ledgeProjection()*(.4+.6*unit(p.seed(),sector,band,24)),false);
            census(q,solid,opening,wall);
            if(!q.footprint.isEmpty())candidates.add(q);
            else {q.y=(int)Math.floor(c.firstLayerTopY()-q.top);hidden.add(q);diagnostics.add(q.id+": NO_EXPOSED_CAP_AFTER_OPENING (parent retained)");}
        }
        reserveCascade(candidates,solid,wall,diagnostics);
        if(recipe.part3a())reserveGuiding(candidates,solid,wall,diagnostics);
        candidates.sort(Comparator.comparingInt((Candidate a)->-a.y).thenComparing(a->a.id));
        var edges=new ArrayList<Neighbour>();
        // Projection overlap relative to the smaller cap. Only vertical neighbours: an
        // intermediate overlapping cap removes a transitive edge.
        double[][] overlap=new double[candidates.size()][candidates.size()];
        for(int i=0;i<candidates.size();i++)for(int j=i+1;j<candidates.size();j++) {
            var a=candidates.get(i);var b=candidates.get(j);int common=0;for(long cell:a.footprint)if(b.footprint.contains(cell))common++;
            overlap[i][j]=common/(double)Math.min(a.footprint.size(),b.footprint.size());
        }
        for(int i=0;i<candidates.size();i++)for(int j=i+1;j<candidates.size();j++)if(overlap[i][j]>recipe.archetypes().neighbourOverlap()) {
            boolean middle=false;for(int k=i+1;k<j;k++)if(overlap[i][k]>recipe.archetypes().neighbourOverlap()&&overlap[k][j]>recipe.archetypes().neighbourOverlap()){middle=true;break;}
            if(!middle)edges.add(new Neighbour(candidates.get(i).id,candidates.get(j).id,overlap[i][j]));
        }
        var assigned=new HashMap<String,Candidate>();
        // Reserve mandatory cascade first: ordinary neighbours must avoid LAKE even if
        // they appear above the cascade in the stable order.
        for(var q:candidates)if(q.cascade||q.id.equals("guiding-tree-ledge")){q.type=q.cascade?Archetype.LAKE:Archetype.GROVE;assigned.put(q.id,q);}
        for(var q:candidates)if(!q.cascade&&!q.id.equals("guiding-tree-ledge")) {
            double[] weights=weights(fields.zone(q.x,q.y,q.z),fields.sideWeights(q.theta),recipe.archetypes());
            for(var edge:edges)if(edge.upper.equals(q.id)||edge.lower.equals(q.id)) {
                var other=assigned.get(edge.upper.equals(q.id)?edge.lower:edge.upper);if(other!=null&&other.type!=null)weights[other.type.ordinal()]=0;
            }
            double total=Arrays.stream(weights).sum();
            if(total==0){diagnostics.add(q.id+": ARCHETYPE_ADJACENCY_UNSATISFIABLE (parent retained)");continue;}
            double draw=(LivingAbyssFields.mix(recipe.seed()^stableId(q.id))>>>11)*0x1.0p-53*total;
            for(int i=0;i<7;i++){draw-=weights[i];if(draw<0){q.type=Archetype.values()[i];break;}}
            assigned.put(q.id,q);
        }
        var result=new ArrayList<Ledge>();
        for(var q:candidates) {
            long h=LivingAbyssFields.mix(recipe.seed()^stableId(q.id));
            result.add(new Ledge(q.id,q.sector,q.band,q.theta,q.half,q.top,q.width,q.projection,q.added,q.shortened,q.cascade,q.type,q.x,q.y,q.z,q.footprint.size(),
                pick(h,recipe.archetypes().lakeDepth()),pick(h>>>7,recipe.archetypes().terraceCount()),pick(h>>>14,recipe.archetypes().terraceRise()),pick(h>>>21,recipe.archetypes().boulderHeight()),pick(h>>>28,recipe.archetypes().overhangThickness())));
        }
        ledges=List.copyOf(result);neighbours=List.copyOf(edges);conflicts=List.copyOf(diagnostics);
        var index=new HashMap<Integer,List<Ledge>>();var added=new ArrayList<Ledge>();
        for(var q:ledges)if(q.added)added.add(q);else index.computeIfAbsent(q.sector,k->new ArrayList<>()).add(q);
        for(var q:hidden)index.computeIfAbsent(q.sector,k->new ArrayList<>()).add(new Ledge(q.id,q.sector,q.band,q.theta,q.half,q.top,q.width,q.projection,false,q.shortened,false,null,0,q.y,0,0,2,3,2,2,6));
        var immutable=new HashMap<Integer,List<Ledge>>();index.forEach((k,v)->immutable.put(k,List.copyOf(v)));bySector=Map.copyOf(immutable);additions=List.copyOf(added);
        digest=digest(candidates);buildNanos=System.nanoTime()-start;
    }
    private void reserveGuiding(List<Candidate> all,Solid solid,Radius wall,List<String> diagnostics){
        double theta=LivingAbyssFields.normalize(fields.wetDegrees()+40),a=Math.toRadians(theta);int x=(int)Math.round(650*Math.sin(a)),z=(int)Math.round(-650*Math.cos(a));double outer=wall.at(x,-120,z);
        int ax=(int)Math.round((outer+8)*Math.sin(a)),az=(int)Math.round(-(outer+8)*Math.cos(a));
        if(!solid.at(ax,-120,az)){diagnostics.add("GUIDING_WALL_SUPPORT_CONFLICT");return;}
        // Section13.6 explicitly requires a cap above the v14 -250m removal band.
        // Its reach/width come from section19's prototype, not a changed v14 contour.
        var q=new Candidate("guiding-tree-ledge",-1,-1,theta,Math.toDegrees(85/(2*outer)),parameters.cavity().firstLayerTopY()+120,85,48,true);
        for(int xx=-760;xx<=760;xx+=4)for(int zz=-760;zz<=760;zz+=4){double r=LivingAbyssFields.hypot(xx,zz),w=LivingAbyssFields.smooth(1-Math.abs(LivingAbyssFields.delta(LivingAbyssFields.azimuth(xx,zz),theta))/q.half);
            if(w>0&&r>=Math.max(380,outer-q.projection*w)&&r<outer)q.footprint.add(cell(xx,zz));}
        q.x=(int)Math.round((outer-48*.7)*Math.sin(a));q.y=-120;q.z=(int)Math.round(-(outer-48*.7)*Math.cos(a));q.shortened=outer-q.projection<380;all.add(q);
    }
    private void census(Candidate q,Solid solid,Radius opening,Radius wall) {
        double limit=Math.max(parameters.cavity().mouthRadius(),parameters.cavity().lowerRadius())+parameters.noise().wallAmplitude();
        double best=-1;int roughY=(int)Math.floor(parameters.cavity().firstLayerTopY()-q.top);
        // Global signed 4m raster: pair intersections use the SAME sample points.
        int bound=(int)Math.ceil(limit/4)*4;
        for(int x=-bound;x<=bound;x+=4)for(int z=-bound;z<=bound;z+=4) {
            double r=LivingAbyssFields.hypot(x,z);if(r<recipe.domain().emptyRadius()||r>limit)continue;
            double a=LivingAbyssFields.azimuth(x,z),w=LivingAbyssFields.smooth(1-Math.abs(LivingAbyssFields.delta(a,q.theta))/q.half);if(w==0)continue;
            int top=(int)Math.floor(parameters.cavity().firstLayerTopY()-q.top-relief(parameters,x,z));
            if(top<-1286||top>=-250)continue;
            double outer=wall.at(x,top,z),tip=outer-q.projection*w*retention(top);
            if(tip<recipe.domain().emptyRadius())q.shortened=true;
            if(r<opening.at(x,top,z)||r>=outer-1)continue;
            // Confirm the ACTUAL composed cap, including cliffs/caves and route cuts.
            int y=top;while(y>=top-16&&y>=parameters.cavity().declaredLowerBoundY()&&(!solid.at(x,y,z)||solid.at(x,y+1,z)))y--;
            if(y<top-16||y<parameters.cavity().declaredLowerBoundY())continue;
            q.footprint.add(cell(x,z));
            double score=w*Math.min(r-Math.max(recipe.domain().emptyRadius(),tip),outer-r);
            if(score>best){best=score;q.x=x;q.y=y;q.z=z;}
        }
    }
    private void reserveCascade(List<Candidate> all,Solid solid,Radius wall,List<String> diagnostics) {
        int count=pick(LivingAbyssFields.mix(recipe.seed()+140),recipe.cascade().count());int[] depths=recipe.cascade().ledgeDepth();
        var selected=new ArrayList<Candidate>();
        for(int i=0;i<count;i++) {
            int depth=(int)Math.round(depths[0]+i*(depths[1]-depths[0])/(double)(count-1));Candidate best=null;double error=Double.POSITIVE_INFINITY;
            for(var q:all)if(!selected.contains(q)&&Math.abs(q.y+depth)<(depths[1]-depths[0])/(double)(count*2)
                    &&Math.abs(LivingAbyssFields.delta(LivingAbyssFields.azimuth(q.x,q.z),fields.wetDegrees()))<=recipe.cascade().halfWidthDegrees()) {
                double e=Math.abs(q.y+depth);if(e<error){error=e;best=q;}
            }
            if(best==null) {
                long hash=LivingAbyssFields.mix(recipe.seed()+i);double a=Math.toRadians(fields.wetDegrees());
                int wx=(int)Math.round(parameters.cavity().lowerRadius()*Math.sin(a)),wz=(int)Math.round(-parameters.cavity().lowerRadius()*Math.cos(a));
                double outer=wall.at(wx,-depth,wz);int anchorX=(int)Math.round((outer+8)*Math.sin(a)),anchorZ=(int)Math.round(-(outer+8)*Math.cos(a));
                if(!solid.at(anchorX,-depth,anchorZ)){diagnostics.add("cascade-"+i+": WALL_SUPPORT_CONFLICT at "+anchorX+","+(-depth)+","+anchorZ);continue;}
                best=new Candidate("cascade-new-"+i,-1,-1,fields.wetDegrees(),recipe.cascade().halfWidthDegrees(),parameters.cavity().firstLayerTopY()+depth,
                    parameters.cavity().ledgeWidth(),parameters.cavity().ledgeProjection(),true);
                // Added lake caps use the same global census raster, but bypass opening;
                // the new supported volume is composed AFTER the old opening stage.
                final var added=best;double rad=Math.max(recipe.domain().emptyRadius(),outer-added.projection);
                for(int x=-720;x<=720;x+=4)for(int z=-720;z<=720;z+=4) {
                    double r=LivingAbyssFields.hypot(x,z),w=LivingAbyssFields.smooth(1-Math.abs(LivingAbyssFields.delta(LivingAbyssFields.azimuth(x,z),added.theta))/added.half);
                    if(w>0&&r>=Math.max(380,outer-added.projection*w)&&r<outer)added.footprint.add(cell(x,z));
                }
                best.x=(int)Math.round((outer+rad)/2*Math.sin(a));best.y=-depth;best.z=(int)Math.round(-(outer+rad)/2*Math.cos(a));best.shortened=outer-best.projection<380;all.add(best);
            }
            best.cascade=true;selected.add(best);
        }
        selected.sort(Comparator.comparingInt(q->-q.y));
        if(selected.size()<count)diagnostics.add("GRAND_CASCADE_INCOMPLETE: "+selected.size()+"/"+count);
        for(int i=1;i<selected.size();i++)if(selected.get(i-1).y-selected.get(i).y>recipe.cascade().receiverDrop())diagnostics.add("GRAND_CASCADE_RECEIVER_DROP_CONFLICT");
    }
    public static double[] weights(int zone,double[] side,LivingAbyssRecipe.Archetypes a) {
        double[] base=zone==3?a.mist():zone==4?a.threshold():a.base();double[][] m=a.sideMultipliers();
        for(int i=0;i<7;i++){double multiplier=0;for(int s=0;s<4;s++)multiplier+=side[s]*m[s][i];base[i]*=multiplier;}return base;
    }
    /** Subset for a column: its three v14 angular neighbours plus compact new descriptors. */
    public List<Ledge> candidates(int x,int z) {
        double phase=(Math.atan2(z,x)/(2*Math.PI)+1)%1*sectors;int sector=(int)Math.floor(phase);var out=new ArrayList<Ledge>();
        for(int i=-1;i<=1;i++)out.addAll(bySector.getOrDefault(Math.floorMod(sector+i,sectors),List.of()));out.addAll(additions);return out;
    }
    public List<Ledge> ledges(){return ledges;}public List<Neighbour> neighbours(){return neighbours;}public List<String> conflicts(){return conflicts;}
    public LivingAbyssRevision155 revision155(){return revision155;}
    public String digest(){return digest;}public long buildNanos(){return buildNanos;}public LivingAbyssRecipe recipe(){return recipe;}public LivingAbyssFields fields(){return fields;}
    public static double retention(double y){return y>=-250?0:y<=-400?1:LivingAbyssFields.smooth((-250-y)/150);}
    public static double relief(TerrainParameters p,int x,int z){return noise(p.seed(),p.noise().wavelength(),x,0,z,41)*p.noise().surfaceAmplitude();}
    // Same stateless signed lattice equations as the v14 oracle, kept outside its bytecode closure.
    public static double noise(long seed,double wavelength,int x,int y,int z,long salt) {
        double sx=x/wavelength,sy=y/wavelength,sz=z/wavelength;long ix=(long)Math.floor(sx),iy=(long)Math.floor(sy),iz=(long)Math.floor(sz);
        double fx=LivingAbyssFields.smooth(sx-ix),fy=LivingAbyssFields.smooth(sy-iy),fz=LivingAbyssFields.smooth(sz-iz),sum=0;
        for(int dx=0;dx<=1;dx++)for(int dy=0;dy<=1;dy++)for(int dz=0;dz<=1;dz++) {
            long h=LivingAbyssFields.mix(seed^LivingAbyssFields.mix(ix+dx)^Long.rotateLeft(LivingAbyssFields.mix(iy+dy),21)^Long.rotateLeft(LivingAbyssFields.mix(iz+dz),42)^LivingAbyssFields.mix(salt));
            sum+=(dx==0?1-fx:fx)*(dy==0?1-fy:fy)*(dz==0?1-fz:fz)*((h>>>11)*0x1.0p-53*2-1);
        }return sum;
    }
    private static double unit(long seed,long sector,long band,long salt){return (LivingAbyssFields.mix(seed^LivingAbyssFields.mix(sector)^Long.rotateLeft(LivingAbyssFields.mix(band),29)^LivingAbyssFields.mix(salt))>>>11)*0x1.0p-53;}
    private static int pick(long hash,int[] range){return range[0]+(int)Math.floorMod(hash,range[1]-range[0]+1);}
    private static long stableId(String id){long h=0xcbf29ce484222325L;for(char ch:id.toCharArray())h=(h^ch)*0x100000001b3L;return h;}
    private static long cell(int x,int z){return ((long)Math.floorDiv(x,4)<<32)^(Math.floorDiv(z,4)&0xffffffffL);}
    private String digest(List<Candidate> candidates) {
        try {
            var md=MessageDigest.getInstance("SHA-256");var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),md));out.writeUTF("mia-living-abyss-plan-1");
            for(var q:ledges){out.writeUTF(q.toString());}for(var q:candidates)for(long cell:q.footprint)out.writeLong(cell);
            for(var e:neighbours)out.writeUTF(e.toString());for(String c:conflicts)out.writeUTF(c);out.flush();return HexFormat.of().formatHex(md.digest());
        }catch(IOException|NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
