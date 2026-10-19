package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.concurrent.BoundedOrderedWork;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/** Pre-T3 oracle, copied before production edits.
 * Source-context-owned exact BOX multiset and persistent dirty face-page incidence. */
final class T3ReferenceIncrementalSurfacePages {
    private final SurfaceQuadPages.Limits limits;private final int pageMetres;
    private final Map<TerrainLodColumns.Box,Entry> owners;
    private final boolean deduplicateIndex;
    private final Map<KeySignature,Membership> memberships=new HashMap<>();
    private final Map<SurfaceQuadPlan.PageKey,CanonicalKey> canonicalKeys=new HashMap<>();
    private final ArrayList<Entry> slots=new ArrayList<>();private final ArrayDeque<Integer> free=new ArrayDeque<>();
    private final Map<SurfaceQuadPlan.PageKey,PageState> pages=new HashMap<>();
    private Function<TerrainLodColumns.Box,SurfaceQuadPlan.Owner> lookup=box->null;
    private Set<SurfaceQuadPlan.PageKey> current=Set.of();
    private final Set<SurfaceQuadPlan.PageKey> knownLocalKeys=new HashSet<>();
    private long incidenceRefs,indexBytes,legacyIndexBytes,extracts,updates,reused,built;private int ownerCount,visibleOwners,visibleMissing,localVisibleMissing,localPages,rawFaces,liveSlots;private String capKind="";
    private static final class KeySignature {
        final SurfaceQuadPlan.PageKey[] keys;final int hash;
        KeySignature(SurfaceQuadPlan.PageKey[] keys){this.keys=keys;hash=Arrays.hashCode(keys);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object value){return value instanceof KeySignature other&&Arrays.equals(keys,other.keys);}
    }
    private static final class Membership {final KeySignature signature;int references;Membership(KeySignature signature){this.signature=signature;}}
    private static final class CanonicalKey {final SurfaceQuadPlan.PageKey key;int references;CanonicalKey(SurfaceQuadPlan.PageKey key){this.key=key;}}
    private static final class Entry {
        final TerrainLodColumns.Box box;final int id;final SurfaceQuadPlan.PageKey[] keys;
        int count,visibleRefs,localVisibleRefs;SurfaceQuadPlan.Owner light;
        Entry(TerrainLodColumns.Box box,int id,int count,SurfaceQuadPlan.PageKey[] keys){this.box=box;this.id=id;this.count=count;this.keys=keys;}
    }
    private static final class PageState {
        final SurfaceQuadPlan.PageKey key;int[] members=new int[0],visible=new int[0];
        SurfaceQuadPlan.Geometry surface;SurfaceQuadPlan.Prepared prepared;int missing;boolean local;
        PageState(SurfaceQuadPlan.PageKey key){this.key=key;}
    }
    private record Staged(SurfaceQuadPlan.PageKey key,List<TerrainLodColumns.Box> members,
            SurfaceQuadPlan.Geometry surface,Set<TerrainLodColumns.Box> visible,boolean extracted){}
    private record Extraction(SurfaceQuadPlan.PageKey key,List<TerrainLodColumns.Box> members,
            List<TerrainLodColumns.Box> boxes,SurfaceQuadPlan.Geometry previous){}
    private record Preparation(PageState state,SurfaceQuadPlan.Geometry surface,SurfaceQuadPlan.Owner[] light){}
    public T3ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits limits,int pageMetres){
        this(limits,pageMetres,Boolean.parseBoolean(System.getProperty("mia.dh.deduplicateIncidence","true")));
    }
    /** Reference switch is diagnostic only; both representations have identical owner/page semantics. */
    public T3ReferenceIncrementalSurfacePages(SurfaceQuadPages.Limits limits,int pageMetres,boolean deduplicateIndex){
        this.limits=Objects.requireNonNull(limits);if(pageMetres!=64&&pageMetres!=128)throw new IllegalArgumentException("Surface packing is64 or128 metres");this.pageMetres=pageMetres;
        this.deduplicateIndex=deduplicateIndex;owners=deduplicateIndex?new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>(16,.75f):new HashMap<>();
    }
    /** Stage only affected page memberships and extraction; no all-owner snapshot is constructed. */
    public void update(List<TerrainLodColumns.Box> removed,List<TerrainLodColumns.Box> added){update(removed,added,List.of(),false);}
    public void update(List<TerrainLodColumns.Box> removed,List<TerrainLodColumns.Box> added,List<TerrainLodClipper.BoundsLong> localFootprints,boolean localGeometry){
        update(removed,added,localFootprints,localGeometry,null,1);
    }
    /** Freeze exact page membership on the sole owner; only pure extraction runs on the supplied workers. */
    public void update(List<TerrainLodColumns.Box> removed,List<TerrainLodColumns.Box> added,List<TerrainLodClipper.BoundsLong> localFootprints,
            boolean localGeometry,ExecutorService executor,int parallelism){
        Objects.requireNonNull(removed);Objects.requireNonNull(added);Objects.requireNonNull(localFootprints);check();
        if(parallelism<1||parallelism>12||(executor==null&&parallelism!=1))throw new IllegalArgumentException("A bounded executor is required for parallel page extraction");
        var localKeys=new HashSet<SurfaceQuadPlan.PageKey>();for(var footprint:localFootprints)localKeys.addAll(footprintKeys(Objects.requireNonNull(footprint)));
        var removals=new HashMap<TerrainLodColumns.Box,Integer>();var deltas=new HashMap<TerrainLodColumns.Box,Integer>();
        for(var box:removed){Objects.requireNonNull(box);removals.merge(box,1,Math::addExact);deltas.merge(box,-1,Math::addExact);}
        for(var box:added){Objects.requireNonNull(box);deltas.merge(box,1,Math::addExact);}
        for(var entry:removals.entrySet()){var old=owners.get(entry.getKey());if(old==null||old.count<entry.getValue())throw new IllegalArgumentException("Removal exceeds whole BOX multiset");}
        deltas.entrySet().removeIf(e->e.getValue()==0);if(deltas.isEmpty()&&localKeys.isEmpty())return;
        var nextCounts=new HashMap<TerrainLodColumns.Box,Integer>();var memberships=new HashMap<TerrainLodColumns.Box,SurfaceQuadPlan.PageKey[]>();
        var changes=new HashMap<SurfaceQuadPlan.PageKey,Map<TerrainLodColumns.Box,Integer>>();
        long nextRefs=incidenceRefs;int nextOwners=ownerCount,births=0,deaths=0;
        for(var delta:deltas.entrySet()){check();var box=delta.getKey();var old=owners.get(box);int count=Math.addExact(old==null?0:old.count,delta.getValue());
            if(count<0)throw new IllegalArgumentException("Negative whole BOX multiset");var keys=old==null?membership(box):old.keys;
            nextCounts.put(box,count);memberships.put(box,keys);nextOwners=Math.addExact(nextOwners,delta.getValue());
            nextRefs=Math.addExact(nextRefs,Math.multiplyExact((long)delta.getValue(),keys.length));if(old==null&&count>0)births++;if(old!=null&&count==0)deaths++;
            for(var key:keys)changes.computeIfAbsent(key,k->new HashMap<>()).put(box,count);
        }
        if(localGeometry)localKeys.addAll(changes.keySet());
        localKeys.removeIf(knownLocalKeys::contains);if(deltas.isEmpty()&&localKeys.isEmpty())return;
        if(nextRefs>limits.maxIncidenceRefs())throw cap("Incremental owner-page references exceed index budget");
        int nextPages=pages.size();var extraction=new ArrayList<Extraction>(changes.size());long nextRaw=rawFaces;
        for(var change:changes.entrySet()){check();var old=pages.get(change.getKey());var counts=new TreeMap<TerrainLodColumns.Box,Integer>(T3ReferenceIncrementalSurfacePages::compare);
            if(old!=null)for(int id:old.members){var entry=slots.get(id);counts.put(entry.box,entry.count);}
            for(var e:change.getValue().entrySet())if(e.getValue()==0)counts.remove(e.getKey());else counts.put(e.getKey(),e.getValue());
            if(old==null&&!counts.isEmpty())nextPages++;if(old!=null&&counts.isEmpty())nextPages--;
            var boxes=new ArrayList<TerrainLodColumns.Box>();for(var e:counts.entrySet())for(int n=0;n<e.getValue();n++)boxes.add(e.getKey());
            if(old!=null)nextRaw-=old.surface.faces().size();
            extraction.add(new Extraction(change.getKey(),List.copyOf(counts.keySet()),List.copyOf(boxes),old==null?null:old.surface));
        }
        var staged=executor==null?extraction.stream().map(this::extract).toList():BoundedOrderedWork.map(extraction,parallelism,executor,this::extract);
        for(var value:staged)if(value.surface()!=null)nextRaw+=value.surface().faces().size();
        long nextBytes=Math.addExact(Math.addExact(128L*nextPages,64L*(liveSlots+births-deaths)),Math.multiplyExact(16L,nextRefs));
        nextBytes=Math.addExact(nextBytes,Math.multiplyExact(64L,(long)knownLocalKeys.size()+localKeys.size()));
        long nextSharedBytes=deduplicateIndex?projectedRetainedBytes(nextPages,liveSlots+births-deaths,nextRefs,(long)knownLocalKeys.size()+localKeys.size(),nextCounts,memberships):nextBytes;
        if(Math.max(nextBytes,nextSharedBytes)>limits.maxIndexBytes())throw cap("Incremental retained incidence bytes exceed index budget");
        if(nextRaw>limits.maxRawFaces()||Math.multiplyExact(nextRaw,SurfaceQuadPlan.CPU_FACE_BYTES)>limits.maxRetainedFaceBytes())throw cap("Incremental source faces exceed intermediate budget");
        check();
        // All bounded index and geometry checks succeeded. Install this delta atomically.
        var retired=new ArrayList<Entry>();
        for(var e:nextCounts.entrySet()){var old=owners.get(e.getKey());if(old!=null){boolean was=missing(old),wasLocal=localMissing(old),wasVisible=old.count>0&&old.visibleRefs>0;old.count=e.getValue();if(was&&!missing(old))visibleMissing--;if(wasLocal&&!localMissing(old))localVisibleMissing--;if(wasVisible&&old.count==0)visibleOwners--;if(e.getValue()==0){owners.remove(e.getKey());retired.add(old);}}
            else if(e.getValue()>0){int id=free.isEmpty()?slots.size():free.removeFirst();var keys=memberships.get(e.getKey());var entry=new Entry(e.getKey(),id,e.getValue(),deduplicateIndex?retainMembership(keys):keys);if(id==slots.size())slots.add(entry);else slots.set(id,entry);owners.put(entry.box,entry);liveSlots++;}}
        for(var value:staged){var old=pages.get(value.key());if(old!=null)for(int id:old.visible)changeVisibility(slots.get(id),-1,old.local);
            if(value.surface()==null){if(old!=null&&old.local)localPages--;pages.remove(value.key());continue;}
            var state=old==null?new PageState(value.key()):old;boolean same=state.surface==value.surface();state.surface=value.surface();if(!state.local&&(knownLocalKeys.contains(value.key())||localKeys.contains(value.key()))){state.local=true;localPages++;}
            state.members=value.members().stream().mapToInt(box->owners.get(box).id).toArray();state.visible=value.visible().stream().mapToInt(box->owners.get(box).id).sorted().toArray();
            state.missing=0;for(int id:state.visible){var entry=slots.get(id);changeVisibility(entry,1,state.local);if(entry.light==null)state.missing++;}
            if(!same)state.prepared=null;pages.put(state.key,state);if(value.extracted())extracts++;
        }
        for(var key:localKeys){var state=pages.get(key);if(state!=null&&!state.local){state.local=true;localPages++;for(int id:state.visible)changeLocalVisibility(slots.get(id),1);}}
        knownLocalKeys.addAll(localKeys);
        for(var entry:retired)release(entry);
        var nextCurrent=new HashSet<>(current);nextCurrent.removeAll(changes.keySet());current=Set.copyOf(nextCurrent);
        incidenceRefs=nextRefs;legacyIndexBytes=nextBytes;indexBytes=deduplicateIndex?retainedBytes():nextBytes;ownerCount=nextOwners;rawFaces=(int)nextRaw;updates++;
    }
    /** No mutable index, owner slots, cached light or publication state is read or written here. */
    private Staged extract(Extraction value){
        check();SurfaceQuadPlan.Geometry surface=null;boolean extracted=false;
        if(!value.boxes().isEmpty()){
            if(value.previous()!=null&&value.previous().owners().equals(value.boxes()))surface=value.previous();
            else{surface=clip(SurfaceQuadPlan.extractForMerge(value.boxes(),limits.maxRawFaces(),limits.maxRetainedFaceBytes()),value.key());extracted=true;}
        }
        var visible=new HashSet<TerrainLodColumns.Box>();if(surface!=null)for(int local:surface.visibleOwners())visible.add(surface.owners().get(local));
        return new Staged(value.key(),value.members(),surface,visible,extracted);
    }

    public void setLightLookup(Function<TerrainLodColumns.Box,SurfaceQuadPlan.Owner> lookup){this.lookup=Objects.requireNonNull(lookup);}
    public void invalidatePrepared(){
        visibleMissing=0;localVisibleMissing=0;for(var entry:owners.values()){entry.light=null;if(entry.visibleRefs>0)visibleMissing++;if(entry.localVisibleRefs>0)localVisibleMissing++;}
        for(var page:pages.values()){page.prepared=null;page.missing=page.visible.length;}current=Set.of();
    }
    public List<TerrainLodColumns.Box> missingOwners(Comparator<SurfaceQuadPlan.PageKey> order,int maximum){
        Objects.requireNonNull(order);if(maximum<1)throw new IllegalArgumentException("Positive owner quantum required");
        var selected=new ArrayList<TerrainLodColumns.Box>();var seen=new HashSet<Entry>();
        for(var key:ordered(pages.keySet(),order)){check();var page=pages.get(key);if(page.missing==0)continue;
            for(int id:page.visible){var entry=slots.get(id);if(entry.light!=null||!seen.add(entry))continue;restore(entry);
                if(entry.light!=null)continue;selected.add(entry.box);if(selected.size()==maximum)return List.copyOf(selected);}}
        return List.copyOf(selected);
    }
    /** One physical light quantum belongs to only one pending page, so another page cannot block publication. */
    public List<TerrainLodColumns.Box> missingPageOwners(Comparator<SurfaceQuadPlan.PageKey> order,int maximum){
        return missingPageOwners(order,maximum,Set.of());
    }
    /** Claimed physical owners stay pending but do not prevent another page starting independent work. */
    public List<TerrainLodColumns.Box> missingPageOwners(Comparator<SurfaceQuadPlan.PageKey> order,int maximum,Set<TerrainLodColumns.Box> claimed){
        return missingFilteredPageOwners(order,maximum,claimed,null);
    }
    private List<TerrainLodColumns.Box> missingFilteredPageOwners(Comparator<SurfaceQuadPlan.PageKey> order,int maximum,Set<TerrainLodColumns.Box> claimed,Boolean localRole){
        Objects.requireNonNull(order);Objects.requireNonNull(claimed);if(maximum<1)throw new IllegalArgumentException("Positive owner quantum required");
        for(var key:ordered(pages.keySet(),order)){check();var page=pages.get(key);if(page.missing==0||(localRole!=null&&page.local!=localRole))continue;
            var selected=new ArrayList<TerrainLodColumns.Box>();
            // Visible IDs are unique even when a physical BOX has multiple multiset occurrences.
            for(int id:page.visible){var entry=slots.get(id);if(entry.light!=null)continue;restore(entry);
                if(entry.light!=null||claimed.contains(entry.box)||(Boolean.FALSE.equals(localRole)&&localMissing(entry)))continue;selected.add(entry.box);if(selected.size()==maximum)return List.copyOf(selected);}
            if(!selected.isEmpty())return List.copyOf(selected);
        }
        return List.of();
    }
    public List<TerrainLodColumns.Box> missingLocalPageOwners(Comparator<SurfaceQuadPlan.PageKey> order,int maximum,Set<TerrainLodColumns.Box> claimed){return missingFilteredPageOwners(order,maximum,claimed,true);}
    public List<TerrainLodColumns.Box> missingBackgroundPageOwners(Comparator<SurfaceQuadPlan.PageKey> order,int maximum,Set<TerrainLodColumns.Box> claimed){return missingFilteredPageOwners(order,maximum,claimed,false);}
    /** Bounded source-owner work across pages; one physical BOX is emitted only once. */
    public List<TerrainLodColumns.Box> missingLocalOwners(Comparator<SurfaceQuadPlan.PageKey> order,int maximum,Set<TerrainLodColumns.Box> claimed){return missingRoleOwners(order,maximum,claimed,true);}
    public List<TerrainLodColumns.Box> missingBackgroundOwners(Comparator<SurfaceQuadPlan.PageKey> order,int maximum,Set<TerrainLodColumns.Box> claimed){return missingRoleOwners(order,maximum,claimed,false);}
    private List<TerrainLodColumns.Box> missingRoleOwners(Comparator<SurfaceQuadPlan.PageKey> order,int maximum,Set<TerrainLodColumns.Box> claimed,boolean localRole){
        Objects.requireNonNull(order);Objects.requireNonNull(claimed);if(maximum<1)throw new IllegalArgumentException("Positive owner quantum required");
        var selected=new ArrayList<TerrainLodColumns.Box>();var seen=new HashSet<Entry>();
        for(var key:ordered(pages.keySet(),order)){check();var page=pages.get(key);if(page.missing==0||page.local!=localRole)continue;
            for(int id:page.visible){var entry=slots.get(id);if(entry.light!=null||!seen.add(entry))continue;restore(entry);
                if(entry.light!=null||claimed.contains(entry.box)||(!localRole&&localMissing(entry)))continue;
                selected.add(entry.box);if(selected.size()==maximum)return List.copyOf(selected);}
        }
        return List.copyOf(selected);
    }
    public boolean isUnlitLocalOwner(TerrainLodColumns.Box box){var entry=owners.get(Objects.requireNonNull(box));return entry!=null&&localMissing(entry);}
    /** True while at least one exact physical BOX occurrence belongs to this source context. */
    public boolean containsOwner(TerrainLodColumns.Box box){return owners.containsKey(Objects.requireNonNull(box));}
    public void supplyLight(TerrainLodColumns.Box box,SurfaceQuadPlan.Owner light){
        Objects.requireNonNull(box);Objects.requireNonNull(light);if(!box.equals(light.box()))throw new IllegalArgumentException("Original physical source owner changed");
        var entry=owners.get(box);if(entry==null)throw new IllegalArgumentException("Unknown live physical source owner");if(light.equals(entry.light))return;
        if(entry.light!=null&&(entry.light.sky()!=light.sky()||entry.light.block()!=light.block()))throw new IllegalArgumentException("A different physical scalar requires a new source context");
        boolean was=missing(entry),wasLocal=localMissing(entry);entry.light=light;if(was)visibleMissing--;if(wasLocal)localVisibleMissing--;
        for(var key:entry.keys){var page=pages.get(key);if(page!=null&&Arrays.binarySearch(page.visible,entry.id)>=0){if(was)page.missing--;page.prepared=null;}}
    }
    public SurfaceQuadPages.Partial preparePartial(SurfaceQuadPlan.PageSet retained,int maxChanged,Comparator<SurfaceQuadPlan.PageKey> order){
        return preparePartial(retained,maxChanged,order,null,1);
    }
    /** The caller retains sole mutable ownership; workers see only frozen geometry and physical light. */
    public SurfaceQuadPages.Partial preparePartial(SurfaceQuadPlan.PageSet retained,int maxChanged,Comparator<SurfaceQuadPlan.PageKey> order,
            ExecutorService executor,int parallelism){
        return preparePartial(retained,maxChanged,order,executor,parallelism,true);
    }
    /**
     * A ready-feed caller may skip scene-wide cache restoration after supplying completed exact owners.
     * Missing owners remain pending; no scalar is inferred. Existing overloads retain restoration.
     * Geometry/resource changes require an explicit restoration pass; role selection still restores
     * cached owners before claiming them, independently of this publication-only option.
     */
    public SurfaceQuadPages.Partial preparePartial(SurfaceQuadPlan.PageSet retained,int maxChanged,Comparator<SurfaceQuadPlan.PageKey> order,
            ExecutorService executor,int parallelism,boolean restoreMissing){
        Objects.requireNonNull(order);if(maxChanged<1)throw new IllegalArgumentException("Positive page quantum required");
        if(parallelism<1||parallelism>12||(executor==null&&parallelism!=1))throw new IllegalArgumentException("A bounded executor is required for parallel page preparation");capKind="";
        var result=new HashMap<SurfaceQuadPlan.PageKey,SurfaceQuadPlan.Page>();if(retained!=null)for(var page:retained.pages())result.put(page.key(),page);
        var keys=new HashSet<>(pages.keySet());keys.addAll(result.keySet());var ready=new HashSet<SurfaceQuadPlan.PageKey>();var changed=new HashSet<SurfaceQuadPlan.PageKey>();int pending=0;
        var preparation=new ArrayList<Preparation>();
        for(var key:ordered(keys,order)){check();var state=pages.get(key);var old=result.get(key);
            if(state==null||state.surface.faces().isEmpty()){
                if(old==null){if(state!=null)ready.add(key);continue;}if(changed.size()>=maxChanged){pending++;continue;}
                result.remove(key);changed.add(key);if(state!=null)ready.add(key);continue;
            }
            if(restoreMissing&&state.missing>0)for(int id:state.visible){var entry=slots.get(id);if(entry.light==null)restore(entry);}
            if(state.missing>0){pending++;continue;}
            if(state.prepared!=null&&old!=null&&old.prepared()==state.prepared){ready.add(key);reused++;continue;}
            if(changed.size()>=maxChanged){pending++;continue;}
            if(state.prepared==null){
                var light=new SurfaceQuadPlan.Owner[state.surface.owners().size()];
                for(int id:state.surface.visibleOwners())light[id]=Objects.requireNonNull(owners.get(state.surface.owners().get(id)).light);
                preparation.add(new Preparation(state,state.surface,light));
            }else{reused++;result.put(key,new SurfaceQuadPlan.Page(key,state.prepared));}
            ready.add(key);changed.add(key);
        }
        Function<Preparation,SurfaceQuadPlan.Prepared> prepare=value->SurfaceQuadPlan.prepare(value.surface(),id->value.light()[id]);
        var prepared=executor==null?preparation.stream().map(prepare).toList():BoundedOrderedWork.map(preparation,parallelism,executor,prepare);
        // No prepared page enters the mutable index until the entire bounded worker batch succeeds.
        check();for(int i=0;i<preparation.size();i++){var state=preparation.get(i).state();state.prepared=prepared.get(i);built++;result.put(state.key,new SurfaceQuadPlan.Page(state.key,state.prepared));}
        var list=new ArrayList<>(result.values());list.sort(Comparator.comparing(SurfaceQuadPlan.Page::key,pageOrder()));int count=0,opaque=0,water=0;long vertices=0;
        for(var page:list){var plan=page.prepared();count=Math.addExact(count,plan.faces().size());vertices=Math.addExact(vertices,plan.vertexBytes());
            opaque=Math.addExact(opaque,page.opaqueFaces());water=Math.addExact(water,page.waterFaces());}
        long gpu=Math.multiplyExact((long)count,SurfaceQuadPlan.GPU_BYTES_PER_FACE);
        if(list.size()>limits.maxPages()||count>limits.maxFaces()||gpu>limits.maxGpuBytes()){capKind=list.size()>limits.maxPages()?"PAGECOUNT":count>limits.maxFaces()?"FACES":"GPU_BYTES";throw cap("Incremental aggregate retained/ready "+capKind+" cap exceeded");}
        var published=new SurfaceQuadPlan.PageSet(list,vertices,gpu,count,opaque,water);current=Set.copyOf(ready);return new SurfaceQuadPages.Partial(published,ready,pending,changed);
    }
    public Set<SurfaceQuadPlan.PageKey> targetKeys(){return Set.copyOf(pages.keySet());}
    public Set<SurfaceQuadPlan.PageKey> currentPages(){return current;}
    public Map<String,Object> status(){var result=new LinkedHashMap<String,Object>();result.put("owners",ownerCount);result.put("uniqueOwners",owners.size());result.put("visibleMissing",visibleMissing);result.put("visibleOwners",visibleOwners);result.put("localVisibleMissing",localVisibleMissing);result.put("localPages",localPages);result.put("knownLocalKeysCount",knownLocalKeys.size());result.put("rawFaces",rawFaces);
        result.put("geometryCalls",0L);result.put("dirtyKeyExtracts",extracts);result.put("updates",updates);result.put("incidenceRefs",incidenceRefs);result.put("retainedIndexBytes",indexBytes);result.put("legacyIndexBytes",legacyIndexBytes);result.put("deduplicateIndex",deduplicateIndex);result.put("membershipArrays",memberships.size());result.put("canonicalPageKeys",canonicalKeys.size());result.put("targetPages",pages.size());result.put("reusedPages",reused);result.put("builtPages",built);result.put("pageMetres",pageMetres);result.put("capKind",capKind);result.put("remaining",visibleMissing);return Collections.unmodifiableMap(result);}
    public boolean pageCountRefused(){return "PAGECOUNT".equals(capKind);}
    private void restore(Entry entry){var value=lookup.apply(entry.box);if(value!=null)supplyLight(entry.box,value);}
    private static boolean missing(Entry entry){return entry.count>0&&entry.visibleRefs>0&&entry.light==null;}
    private static boolean localMissing(Entry entry){return entry.count>0&&entry.localVisibleRefs>0&&entry.light==null;}
    private void changeLocalVisibility(Entry entry,int delta){boolean before=localMissing(entry);entry.localVisibleRefs+=delta;boolean after=localMissing(entry);if(before!=after)localVisibleMissing+=after?1:-1;}
    private void changeVisibility(Entry entry,int delta,boolean local){if(local)changeLocalVisibility(entry,delta);boolean before=missing(entry),visibleBefore=entry.count>0&&entry.visibleRefs>0;entry.visibleRefs+=delta;boolean after=missing(entry),visibleAfter=entry.count>0&&entry.visibleRefs>0;if(visibleBefore!=visibleAfter)visibleOwners+=visibleAfter?1:-1;if(before!=after)visibleMissing+=after?1:-1;
        release(entry);}
    private void release(Entry entry){if(entry.count==0&&entry.visibleRefs==0&&entry.localVisibleRefs==0&&slots.get(entry.id)==entry){if(deduplicateIndex)releaseMembership(entry.keys);slots.set(entry.id,null);free.addLast(entry.id);liveSlots--;}}
    private SurfaceQuadPlan.PageKey[] retainMembership(SurfaceQuadPlan.PageKey[] keys){
        var signature=new KeySignature(keys);var known=memberships.get(signature);
        if(known==null){var retained=keys.clone();for(int i=0;i<retained.length;i++){var key=canonicalKeys.get(retained[i]);if(key==null){key=new CanonicalKey(retained[i]);canonicalKeys.put(key.key,key);}key.references++;retained[i]=key.key;}
            signature=new KeySignature(retained);known=new Membership(signature);memberships.put(signature,known);}
        known.references++;return known.signature.keys;
    }
    private void releaseMembership(SurfaceQuadPlan.PageKey[] keys){
        var signature=new KeySignature(keys);var group=Objects.requireNonNull(memberships.get(signature));
        if(--group.references==0){memberships.remove(signature);for(var value:keys){var key=Objects.requireNonNull(canonicalKeys.get(value));if(--key.references==0)canonicalKeys.remove(value);}}
    }
    private long retainedBytes(){
        // Same conservative owner/page budget. Shared signatures and canonical keys
        // are counted once; page member/visible int arrays use <=8 bytes per incidence.
        long bytes=128L*pages.size()+64L*liveSlots+8L*incidenceRefs+64L*knownLocalKeys.size()+128L*canonicalKeys.size();
        for(var group:memberships.values())bytes+=128L+8L*group.signature.keys.length;return bytes;
    }
    /** Forecast pure metadata, without interning keys or touching a live owner before the cap succeeds. */
    private long projectedRetainedBytes(int pageCount,int liveCount,long refs,long localCount,
            Map<TerrainLodColumns.Box,Integer> nextCounts,Map<TerrainLodColumns.Box,SurfaceQuadPlan.PageKey[]> nextMemberships){
        var deltas=new HashMap<KeySignature,Integer>();
        for(var item:nextCounts.entrySet()){var old=owners.get(item.getKey());
            if(old==null&&item.getValue()>0)deltas.merge(new KeySignature(nextMemberships.get(item.getKey())),1,Math::addExact);
            else if(old!=null&&item.getValue()==0)deltas.merge(new KeySignature(old.keys),-1,Math::addExact);}
        var future=new HashSet<>(memberships.keySet());
        for(var item:deltas.entrySet()){var old=memberships.get(item.getKey());int count=Math.addExact(old==null?0:old.references,item.getValue());
            if(count<0)throw new IllegalStateException("Shared membership reference underflow");if(count==0)future.remove(item.getKey());else future.add(item.getKey());}
        var keys=new HashSet<SurfaceQuadPlan.PageKey>();long bytes=128L*pageCount+64L*liveCount+8L*refs+64L*localCount;
        for(var signature:future){bytes+=128L+8L*signature.keys.length;Collections.addAll(keys,signature.keys);}return bytes+128L*keys.size();
    }
    /** Footprints are metadata, including AIR; positive boundary planes belong to their canonical next page. */
    private Set<SurfaceQuadPlan.PageKey> footprintKeys(TerrainLodClipper.BoundsLong bounds){
        long x0=Math.floorDiv(bounds.minX(),pageMetres),x1=Math.floorDiv(bounds.maxXExclusive(),pageMetres),
             y0=Math.floorDiv(bounds.minY(),pageMetres),y1=Math.floorDiv(bounds.maxYExclusive(),pageMetres),
             z0=Math.floorDiv(bounds.minZ(),pageMetres),z1=Math.floorDiv(bounds.maxZExclusive(),pageMetres);
        long count;try{count=Math.multiplyExact(Math.multiplyExact(Math.addExact(Math.subtractExact(x1,x0),1),Math.addExact(Math.subtractExact(y1,y0),1)),Math.addExact(Math.subtractExact(z1,z0),1));}
        catch(ArithmeticException huge){throw cap("Local footprint page coordinate overflow");}
        if(count>limits.maxIncidenceRefs())throw cap("Local footprint exceeds bounded incidence");
        var keys=new HashSet<SurfaceQuadPlan.PageKey>();for(long x=x0;x<=x1;x++)for(long y=y0;y<=y1;y++)for(long z=z0;z<=z1;z++){check();keys.add(new SurfaceQuadPlan.PageKey(x,y,z));}return keys;
    }
    private SurfaceQuadPlan.PageKey[] membership(TerrainLodColumns.Box box){
        if(deduplicateIndex){long x=Math.floorDiv(box.minX(),pageMetres),y=Math.floorDiv(box.minY(),pageMetres),z=Math.floorDiv(box.minZ(),pageMetres);
            if(x==Math.floorDiv(box.maxXExclusive(),pageMetres)&&y==Math.floorDiv(box.maxYExclusive(),pageMetres)&&z==Math.floorDiv(box.maxZExclusive(),pageMetres))
                return new SurfaceQuadPlan.PageKey[]{new SurfaceQuadPlan.PageKey(x,y,z)};}
        var result=new HashSet<SurfaceQuadPlan.PageKey>();for(int axis=0;axis<3;axis++)for(int sign=0;sign<2;sign++){
            long plane=axis==0?(sign==0?box.minX():box.maxXExclusive()):axis==1?(sign==0?box.minY():box.maxYExclusive()):(sign==0?box.minZ():box.maxZExclusive());
            long u0=axis==0?box.minZ():box.minX(),u1=axis==0?box.maxZExclusive():box.maxXExclusive(),v0=axis==1?box.minZ():box.minY(),v1=axis==1?box.maxZExclusive():box.maxYExclusive();
            long firstU=Math.floorDiv(u0,pageMetres),lastU=Math.floorDiv(u1-1,pageMetres),firstV=Math.floorDiv(v0,pageMetres),lastV=Math.floorDiv(v1-1,pageMetres),normal=Math.floorDiv(plane,pageMetres);
            long area;try{area=Math.multiplyExact(lastU-firstU+1,lastV-firstV+1);}catch(ArithmeticException huge){throw cap("Incremental face coordinate overflow");}
            if(area>limits.maxIncidenceRefs())throw cap("One source face exceeds bounded incidence");
            for(long u=firstU;u<=lastU;u++)for(long v=firstV;v<=lastV;v++){check();result.add(axis==0?new SurfaceQuadPlan.PageKey(normal,v,u):axis==1?new SurfaceQuadPlan.PageKey(u,normal,v):new SurfaceQuadPlan.PageKey(u,v,normal));if(result.size()>limits.maxIncidenceRefs())throw cap("One owner exceeds incidence budget");}}
        var keys=result.toArray(SurfaceQuadPlan.PageKey[]::new);if(deduplicateIndex)Arrays.sort(keys,pageOrder());return keys;
    }
    private SurfaceQuadPlan.Geometry clip(SurfaceQuadPlan.Geometry geometry,SurfaceQuadPlan.PageKey page){
        var faces=new ArrayList<SurfaceQuadPlan.Face>();var visible=new BitSet(geometry.owners().size());for(var f:geometry.faces()){
            long normal=f.axis()==0?page.x():f.axis()==1?page.y():page.z();if(Math.floorDiv(f.plane(),pageMetres)!=normal)continue;
            long u=Math.multiplyExact(f.axis()==0?page.z():page.x(),pageMetres),v=Math.multiplyExact(f.axis()==1?page.z():page.y(),pageMetres);
            long u0=Math.max(f.u0(),u),u1=Math.min(f.u1(),Math.addExact(u,pageMetres)),v0=Math.max(f.v0(),v),v1=Math.min(f.v1(),Math.addExact(v,pageMetres));
            if(u0<u1&&v0<v1){faces.add(new SurfaceQuadPlan.Face(f.owner(),f.axis(),f.positive(),f.plane(),u0,v0,u1,v1));visible.set(f.owner());}}
        return new SurfaceQuadPlan.Geometry(geometry.owners(),faces,visible.stream().boxed().toList(),(long)faces.size()*SurfaceQuadPlan.VERTEX_BYTES_PER_FACE);
    }
    private static List<SurfaceQuadPlan.PageKey> ordered(Collection<SurfaceQuadPlan.PageKey> keys,Comparator<SurfaceQuadPlan.PageKey> order){var result=new ArrayList<>(keys);result.sort(order.thenComparing(pageOrder()));return result;}
    private static Comparator<SurfaceQuadPlan.PageKey> pageOrder(){return Comparator.comparingLong(SurfaceQuadPlan.PageKey::x).thenComparingLong(SurfaceQuadPlan.PageKey::y).thenComparingLong(SurfaceQuadPlan.PageKey::z);}
    private static int compare(TerrainLodColumns.Box a,TerrainLodColumns.Box b){int c=Long.compare(a.minX(),b.minX());if(c!=0)return c;c=Long.compare(a.minY(),b.minY());if(c!=0)return c;c=Long.compare(a.minZ(),b.minZ());if(c!=0)return c;c=Long.compare(a.maxXExclusive(),b.maxXExclusive());if(c!=0)return c;c=Long.compare(a.maxYExclusive(),b.maxYExclusive());if(c!=0)return c;c=Long.compare(a.maxZExclusive(),b.maxZExclusive());return c!=0?c:Integer.compare(a.material().ordinal(),b.material().ordinal());}
    private static TerrainLodColumns.BudgetExceededException cap(String message){return new TerrainLodColumns.BudgetExceededException(message);}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Incremental surface source lease cancelled");}
}
