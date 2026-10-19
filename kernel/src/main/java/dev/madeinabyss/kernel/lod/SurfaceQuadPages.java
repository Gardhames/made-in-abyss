package dev.madeinabyss.kernel.lod;

import java.util.*;
import java.util.function.IntFunction;

/** Worker-owned canonical face pages. Physical light always belongs to whole input boxes. */
public final class SurfaceQuadPages {
    public record Partial(SurfaceQuadPlan.PageSet published,Set<SurfaceQuadPlan.PageKey> currentKeys,int pendingPages,Set<SurfaceQuadPlan.PageKey> changedKeys){
        public Partial{Objects.requireNonNull(published);currentKeys=Set.copyOf(currentKeys);changedKeys=Set.copyOf(changedKeys);if(pendingPages<0)throw new IllegalArgumentException("Negative pending page count");}}
    public record Limits(int maxPages,long maxIncidenceRefs,long maxIndexBytes,int maxRawFaces,long maxRetainedFaceBytes,int maxFaces,long maxGpuBytes){
        public Limits{if(maxPages<1||maxIncidenceRefs<1||maxIndexBytes<1||maxRawFaces<1||maxRetainedFaceBytes<1||maxFaces<1||maxGpuBytes<1)throw new IllegalArgumentException("Positive surface page budgets required");}
        public static Limits defaults(){return new Limits(8192,4_000_000,96L*1024*1024,2_000_000,192L*1024*1024,1_000_000,96L*1024*1024);}}
    public static final class Region {
        private final SurfaceQuadPlan.PageKey key;
        private final SurfaceQuadPlan.Geometry surface;
        private final int[] originalOwners;
        private Region(SurfaceQuadPlan.PageKey key,SurfaceQuadPlan.Geometry surface,int[] originalOwners){this.key=key;this.surface=surface;this.originalOwners=originalOwners;}
        public SurfaceQuadPlan.PageKey key(){return key;}
        public SurfaceQuadPlan.Geometry surface(){return surface;}
        public int originalOwner(int index){return originalOwners[index];}
    }
    public record Geometry(List<TerrainLodColumns.Box> owners,List<Region> pages,List<Integer> visibleOwners,long incidenceRefs){
        public Geometry{owners=List.copyOf(owners);pages=List.copyOf(pages);visibleOwners=visibleOwners instanceof Indices?visibleOwners:List.copyOf(visibleOwners);}
        public int rawFaces(){int n=0;for(var page:pages)n=Math.addExact(n,page.surface().faces().size());return n;}}
    private final Limits limits;
    private final int pageMetres;
    private final Map<SurfaceQuadPlan.PageKey,Cached> cache=new HashMap<>();
    private volatile long incidenceRefs,indexBytes,reusedPages,builtPages;
    private volatile int incidencePages,visiblePages;
    private volatile double indexMillis,ownerSortMillis,extractionMillis,geometryMillis,prepareMillis;
    private volatile long geometryCalls,geometryReused,geometryBuilt;
    private volatile int attemptedPages,attemptedFaces,attemptedRawFaces;
    private volatile long attemptedGpuBytes;
    private volatile String capKind="";
    private static final class Cached {
        final SurfaceQuadPlan.Geometry surface;
        SurfaceQuadPlan.Prepared prepared;
        List<SurfaceQuadPlan.Owner> lights;
        Cached(SurfaceQuadPlan.Geometry surface){this.surface=surface;}
    }
    private static final class Ints {
        int[] values=new int[4];int size;
        void add(int value){if(size==values.length)values=Arrays.copyOf(values,values.length*2);values[size++]=value;}
    }
    /** Read-only primitive-backed indices avoid retaining one boxed Integer per owner. */
    private static final class Indices extends AbstractList<Integer> implements RandomAccess {
        private final int[] values;
        Indices(BitSet indices){values=indices.stream().toArray();}
        @Override public Integer get(int index){return values[index];}
        @Override public int size(){return values.length;}
    }
    public SurfaceQuadPages(Limits limits){this(limits,64);}
    public SurfaceQuadPages(Limits limits,int pageMetres){this.limits=Objects.requireNonNull(limits);
        if(pageMetres!=64&&pageMetres!=128)throw new IllegalArgumentException("Surface packing is64 or128 metres");this.pageMetres=pageMetres;}
    public int pageMetres(){return pageMetres;}
    public boolean pageCountRefused(){return "PAGECOUNT".equals(capKind);}
    public Geometry geometry(List<TerrainLodColumns.Box> input){
        long started=System.nanoTime();geometryCalls++;long sorting=0,extracting=0;
        var owners=List.copyOf(input);var incidence=new HashMap<SurfaceQuadPlan.PageKey,Ints>();var membership=new HashSet<SurfaceQuadPlan.PageKey>();long refs=0,capacity=0;
        for(int owner=0;owner<owners.size();owner++){check();membership.clear();var b=owners.get(owner);
            for(int axis=0;axis<3;axis++)for(int sign=0;sign<2;sign++){
                long plane=axis==0?(sign==0?b.minX():b.maxXExclusive()):axis==1?(sign==0?b.minY():b.maxYExclusive()):(sign==0?b.minZ():b.maxZExclusive());
                long u0=axis==0?b.minZ():b.minX(),u1=axis==0?b.maxZExclusive():b.maxXExclusive(),v0=axis==1?b.minZ():b.minY(),v1=axis==1?b.maxZExclusive():b.maxYExclusive();
                long firstU=Math.floorDiv(u0,pageMetres),lastU=Math.floorDiv(u1-1,pageMetres),firstV=Math.floorDiv(v0,pageMetres),lastV=Math.floorDiv(v1-1,pageMetres),normal=Math.floorDiv(plane,pageMetres);
                long area;try{area=Math.multiplyExact(Math.addExact(Math.subtractExact(lastU,firstU),1),Math.addExact(Math.subtractExact(lastV,firstV),1));}
                catch(ArithmeticException huge){throw cap("Surface page incidence coordinate overflow");}
                if(area>limits.maxIncidenceRefs())throw cap("One source face exceeds bounded incidence index");
                for(long u=firstU;u<=lastU;u++)for(long v=firstV;v<=lastV;v++){
                    check();var key=axis==0?new SurfaceQuadPlan.PageKey(normal,v,u):axis==1?new SurfaceQuadPlan.PageKey(u,normal,v):new SurfaceQuadPlan.PageKey(u,v,normal);
                    if(!membership.add(key))continue;
                    if(++refs>limits.maxIncidenceRefs())throw cap("Surface owner-page references exceed index budget");
                    var ids=incidence.computeIfAbsent(key,k->new Ints());int previous=ids.values.length;ids.add(owner);capacity+=ids.values.length-previous;
                    if(ids.size==1)capacity+=previous;
                    long bytes=128L*incidence.size()+16L*capacity+12L*owners.size();
                    if(bytes>limits.maxIndexBytes())throw cap("Surface retained incidence bytes exceed index budget");
                }
            }
        }
        incidenceRefs=refs;incidencePages=incidence.size();indexBytes=128L*incidence.size()+16L*capacity+12L*owners.size();
        var keys=new ArrayList<>(incidence.keySet());keys.sort(Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z));
        indexMillis=(System.nanoTime()-started)/1_000_000.0;
        var regions=new ArrayList<Region>();var visible=new BitSet(owners.size());long rawFaces=0;var nextCache=new HashMap<SurfaceQuadPlan.PageKey,Cached>();
        for(var key:keys){check();var buffer=incidence.get(key);var order=new ArrayList<Integer>(buffer.size);for(int i=0;i<buffer.size;i++)order.add(buffer.values[i]);
            long sortStarted=System.nanoTime();
            order.sort((a,b)->{int c=compare(owners.get(a),owners.get(b));return c!=0?c:Integer.compare(a,b);});
            sorting+=System.nanoTime()-sortStarted;
            var local=new ArrayList<TerrainLodColumns.Box>(order.size());int[] originals=new int[order.size()];
            for(int i=0;i<order.size();i++){originals[i]=order.get(i);local.add(owners.get(originals[i]));}
            var old=cache.get(key);SurfaceQuadPlan.Geometry surface;
            if(old!=null&&old.surface.owners().equals(local)){surface=old.surface;nextCache.put(key,old);geometryReused++;}
            else{long extractStarted=System.nanoTime();var full=SurfaceQuadPlan.extractForMerge(local,limits.maxRawFaces(),limits.maxRetainedFaceBytes());surface=clip(full,key);nextCache.put(key,new Cached(surface));
                extracting+=System.nanoTime()-extractStarted;geometryBuilt++;}
            rawFaces+=surface.faces().size();if(rawFaces>limits.maxRawFaces()||rawFaces*SurfaceQuadPlan.CPU_FACE_BYTES>limits.maxRetainedFaceBytes())throw cap("Clipped source face metadata exceeds bounded intermediate");
            for(int i:surface.visibleOwners())visible.set(originals[i]);
            regions.add(new Region(key,surface,originals));
        }
        cache.clear();cache.putAll(nextCache);
        ownerSortMillis=sorting/1_000_000.0;extractionMillis=extracting/1_000_000.0;
        geometryMillis=(System.nanoTime()-started)/1_000_000.0;
        return new Geometry(owners,regions,new Indices(visible),refs);
    }
    public SurfaceQuadPlan.PageSet prepare(Geometry geometry,IntFunction<SurfaceQuadPlan.Owner> lighting){
        long started=System.nanoTime();
        attemptedPages=0;attemptedFaces=0;attemptedGpuBytes=0;attemptedRawFaces=geometry.rawFaces();capKind="";
        for(var region:geometry.pages())if(!region.surface().faces().isEmpty())attemptedPages++;
        // Greedy never removes the last face of a nonempty page. This is an exact
        // page-count refusal for this composition, independent of owner lighting.
        if(attemptedPages>limits.maxPages()){capKind="PAGECOUNT";prepareMillis=(System.nanoTime()-started)/1_000_000.0;
            throw cap("Surface PAGECOUNT "+attemptedPages+" exceeds "+limits.maxPages()+"; rawFaces="+attemptedRawFaces+"; packingMetres="+pageMetres);}
        Objects.requireNonNull(lighting);var lit=new SurfaceQuadPlan.Owner[geometry.owners().size()];
        for(int i:geometry.visibleOwners()){check();var value=Objects.requireNonNull(lighting.apply(i));if(!value.box().equals(geometry.owners().get(i)))throw new IllegalArgumentException("Original physical source owner changed");lit[i]=value;}
        var pages=new ArrayList<SurfaceQuadPlan.Page>();int faces=0,opaque=0,water=0;
        for(var region:geometry.pages()){check();if(region.surface().faces().isEmpty())continue;
            var stamp=new ArrayList<SurfaceQuadPlan.Owner>();for(int i:region.surface().visibleOwners())stamp.add(lit[region.originalOwner(i)]);
            var cached=cache.get(region.key());SurfaceQuadPlan.Prepared plan;
            if(cached!=null&&cached.surface==region.surface()&&cached.prepared!=null&&cached.lights.equals(stamp)){plan=cached.prepared;reusedPages++;}
            else{plan=SurfaceQuadPlan.prepare(region.surface(),i->lit[region.originalOwner(i)]);builtPages++;
                if(cached!=null&&cached.surface==region.surface()){cached.prepared=plan;cached.lights=List.copyOf(stamp);}}
            pages.add(new SurfaceQuadPlan.Page(region.key(),plan));faces=Math.addExact(faces,plan.faces().size());
            attemptedFaces=faces;attemptedGpuBytes=(long)faces*76;
            if(faces>limits.maxFaces()||attemptedGpuBytes>limits.maxGpuBytes()){capKind=faces>limits.maxFaces()?"FACES":"GPU_BYTES";prepareMillis=(System.nanoTime()-started)/1_000_000.0;
                throw cap("Surface "+capKind+" refusal: pages="+attemptedPages+", faces="+faces+"/"+limits.maxFaces()+", gpuBytes="+attemptedGpuBytes+"/"+limits.maxGpuBytes());}
            for(var face:plan.faces())if(plan.owners().get(face.owner()).box().material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER)water++;else opaque++;}
        visiblePages=pages.size();
        prepareMillis=(System.nanoTime()-started)/1_000_000.0;
        return new SurfaceQuadPlan.PageSet(pages,(long)faces*64,(long)faces*76,faces,opaque,water);
    }
    public Partial preparePartial(Geometry geometry,IntFunction<SurfaceQuadPlan.Owner> lighting,SurfaceQuadPlan.PageSet retained,
            int maxChangedPages,Comparator<SurfaceQuadPlan.PageKey> order){
        Objects.requireNonNull(geometry);Objects.requireNonNull(lighting);Objects.requireNonNull(order);
        if(maxChangedPages<1)throw new IllegalArgumentException("Positive changed-page quantum required");
        long started=System.nanoTime();capKind="";attemptedRawFaces=geometry.rawFaces();
        var published=new HashMap<SurfaceQuadPlan.PageKey,SurfaceQuadPlan.Page>();
        if(retained!=null)for(var page:retained.pages())published.put(page.key(),page);
        var target=new HashMap<SurfaceQuadPlan.PageKey,Region>();for(var region:geometry.pages())target.put(region.key(),region);
        var keys=new HashSet<>(target.keySet());keys.addAll(published.keySet());var ordered=new ArrayList<>(keys);ordered.sort(order.thenComparing(pageOrder()));
        var values=new HashMap<Integer,SurfaceQuadPlan.Owner>();var current=new HashSet<SurfaceQuadPlan.PageKey>();var changed=new HashSet<SurfaceQuadPlan.PageKey>();int pending=0;
        for(var key:ordered){check();var region=target.get(key);var old=published.get(key);
            // The immutable complete target geometry proves empty/absent pages. Pending
            // scalar data never makes this inference for a nonempty target page.
            if(region==null||region.surface().faces().isEmpty()){
                if(old==null){if(region!=null)current.add(key);continue;}
                if(changed.size()>=maxChangedPages){pending++;continue;}
                published.remove(key);changed.add(key);if(region!=null)current.add(key);continue;
            }
            var stamp=new ArrayList<SurfaceQuadPlan.Owner>(region.surface().visibleOwners().size());boolean ready=true;
            for(int local:region.surface().visibleOwners()){
                int original=region.originalOwner(local);var value=partialOwner(geometry,lighting,values,original);
                if(value==null){ready=false;break;}stamp.add(value);
            }
            if(!ready){pending++;continue;}
            var cached=cache.get(key);SurfaceQuadPlan.Prepared plan=null;
            if(cached!=null&&cached.surface==region.surface()&&cached.prepared!=null&&cached.lights.equals(stamp))plan=cached.prepared;
            if(old!=null&&old.prepared()==plan){current.add(key);reusedPages++;continue;}
            if(changed.size()>=maxChangedPages){pending++;continue;}
            if(plan==null){plan=SurfaceQuadPlan.prepare(region.surface(),i->values.get(region.originalOwner(i)));builtPages++;
                if(cached!=null&&cached.surface==region.surface()){cached.prepared=plan;cached.lights=List.copyOf(stamp);}}
            else reusedPages++;
            published.put(key,new SurfaceQuadPlan.Page(key,plan));changed.add(key);current.add(key);
        }
        var result=new ArrayList<>(published.values());result.sort(Comparator.comparing(SurfaceQuadPlan.Page::key,pageOrder()));
        int faces=0,opaque=0,water=0;long vertices=0;
        for(var page:result){check();var plan=page.prepared();faces=Math.addExact(faces,plan.faces().size());vertices=Math.addExact(vertices,plan.vertexBytes());
            for(var face:plan.faces())if(plan.owners().get(face.owner()).box().material()==dev.madeinabyss.kernel.terrain.TerrainMaterial.WATER)water++;else opaque++;}
        attemptedPages=result.size();attemptedFaces=faces;attemptedGpuBytes=Math.multiplyExact((long)faces,SurfaceQuadPlan.GPU_BYTES_PER_FACE);
        if(attemptedPages>limits.maxPages()||faces>limits.maxFaces()||attemptedGpuBytes>limits.maxGpuBytes()){
            capKind=attemptedPages>limits.maxPages()?"PAGECOUNT":faces>limits.maxFaces()?"FACES":"GPU_BYTES";prepareMillis=(System.nanoTime()-started)/1_000_000.0;
            throw cap("Partial surface "+capKind+" refusal: pages="+attemptedPages+", faces="+faces+", gpuBytes="+attemptedGpuBytes);}
        visiblePages=result.size();prepareMillis=(System.nanoTime()-started)/1_000_000.0;
        return new Partial(new SurfaceQuadPlan.PageSet(result,vertices,attemptedGpuBytes,faces,opaque,water),current,pending,changed);
    }
    /** Bounded selected original IDs; a shared BOX is requested once across page fragments. */
    public List<Integer> pendingPageOwners(Geometry geometry,IntFunction<?> readyValue,Comparator<SurfaceQuadPlan.PageKey> order,int maxOwners){
        Objects.requireNonNull(geometry);Objects.requireNonNull(readyValue);Objects.requireNonNull(order);
        if(maxOwners<1)throw new IllegalArgumentException("Positive page-owner quantum required");
        var regions=new ArrayList<>(geometry.pages());regions.sort(Comparator.comparing(Region::key,order.thenComparing(pageOrder())));
        var seen=new HashSet<Integer>();var result=new ArrayList<Integer>(Math.min(maxOwners,geometry.visibleOwners().size()));
        for(var region:regions){check();for(int local:region.surface().visibleOwners()){
            int original=region.originalOwner(local);if(!seen.add(original)||readyValue.apply(original)!=null)continue;
            result.add(original);if(result.size()==maxOwners)return List.copyOf(result);
        }}
        return List.copyOf(result);
    }
    private static SurfaceQuadPlan.Owner partialOwner(Geometry geometry,IntFunction<SurfaceQuadPlan.Owner> lighting,
            Map<Integer,SurfaceQuadPlan.Owner> values,int original){
        if(values.containsKey(original))return values.get(original);var value=lighting.apply(original);
        if(value!=null&&!value.box().equals(geometry.owners().get(original)))throw new IllegalArgumentException("Original physical source owner changed");
        values.put(original,value);return value;
    }
    private static Comparator<SurfaceQuadPlan.PageKey> pageOrder(){return Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);}
    public Map<String,Object> status(){var out=new LinkedHashMap<String,Object>();out.put("incidencePages",incidencePages);out.put("visiblePages",visiblePages);out.put("incidenceRefs",incidenceRefs);out.put("retainedIndexBytes",indexBytes);
        out.put("pageMetres",pageMetres);out.put("attemptedPages",attemptedPages);out.put("attemptedRawFaces",attemptedRawFaces);out.put("attemptedFaces",attemptedFaces);out.put("attemptedGpuBytes",attemptedGpuBytes);out.put("capKind",capKind);
        out.put("reusedPages",reusedPages);out.put("builtPages",builtPages);out.put("geometryCalls",geometryCalls);out.put("geometryReusedPages",geometryReused);out.put("geometryBuiltPages",geometryBuilt);
        out.put("indexMillis",indexMillis);out.put("ownerSortMillis",ownerSortMillis);out.put("extractionMillis",extractionMillis);out.put("geometryMillis",geometryMillis);out.put("prepareMillis",prepareMillis);return Collections.unmodifiableMap(out);}
    private SurfaceQuadPlan.Geometry clip(SurfaceQuadPlan.Geometry input,SurfaceQuadPlan.PageKey page){
        var faces=new ArrayList<SurfaceQuadPlan.Face>();var visible=new BitSet(input.owners().size());
        for(var f:input.faces()){long normal=f.axis()==0?page.x():f.axis()==1?page.y():page.z();if(Math.floorDiv(f.plane(),pageMetres)!=normal)continue;
            long baseU=Math.multiplyExact(f.axis()==0?page.z():page.x(),pageMetres),baseV=Math.multiplyExact(f.axis()==1?page.z():page.y(),pageMetres);
            long u0=Math.max(f.u0(),baseU),v0=Math.max(f.v0(),baseV),u1=Math.min(f.u1(),Math.addExact(baseU,pageMetres)),v1=Math.min(f.v1(),Math.addExact(baseV,pageMetres));
            if(u0>=u1||v0>=v1)continue;faces.add(new SurfaceQuadPlan.Face(f.owner(),f.axis(),f.positive(),f.plane(),u0,v0,u1,v1));visible.set(f.owner());}
        return new SurfaceQuadPlan.Geometry(input.owners(),faces,new Indices(visible),(long)faces.size()*64);
    }
    private static int compare(TerrainLodColumns.Box a,TerrainLodColumns.Box b){int c=Long.compare(a.minX(),b.minX());if(c!=0)return c;c=Long.compare(a.minY(),b.minY());if(c!=0)return c;c=Long.compare(a.minZ(),b.minZ());if(c!=0)return c;
        c=Long.compare(a.maxXExclusive(),b.maxXExclusive());if(c!=0)return c;c=Long.compare(a.maxYExclusive(),b.maxYExclusive());if(c!=0)return c;c=Long.compare(a.maxZExclusive(),b.maxZExclusive());return c!=0?c:Integer.compare(a.material().ordinal(),b.material().ordinal());}
    private static TerrainLodColumns.BudgetExceededException cap(String message){return new TerrainLodColumns.BudgetExceededException(message);}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Surface page source lease cancelled");}
}
