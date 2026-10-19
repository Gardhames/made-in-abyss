package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.concurrent.BoundedOrderedWork;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Source-context-owned exact BOX multiset and persistent dirty face-page incidence. */
public final class IncrementalSurfacePages {
    private final SurfaceQuadPages.Limits limits;private final int pageMetres;
    private final Map<TerrainLodColumns.Box,Entry> owners;
    private final boolean deduplicateIndex;
    private final Map<KeySignature,Membership> memberships=new HashMap<>();
    private final Map<SurfaceQuadPlan.PageKey,CanonicalKey> canonicalKeys=new HashMap<>();
    private final ArrayList<Entry> slots=new ArrayList<>();private final ArrayDeque<Integer> free=new ArrayDeque<>();
    private final Map<SurfaceQuadPlan.PageKey,PageState> pages=new HashMap<>();
    private Function<TerrainLodColumns.Box,SurfaceQuadPlan.Owner> lookup=box->null;
    private LongSupplier lookupRevision;
    private Restoration cachedRestoration;
    private Set<SurfaceQuadPlan.PageKey> current=new HashSet<>();
    private Set<SurfaceQuadPlan.PageKey> currentSnapshot=Set.of();
    private final Set<SurfaceQuadPlan.PageKey> knownLocalKeys=new HashSet<>();
    private long incidenceRefs,indexBytes,legacyIndexBytes,membershipBytes,extracts,updates,reused,built;private int ownerCount,visibleOwners,visibleMissing,localVisibleMissing,localPages,rawFaces,liveSlots;private String capKind="";
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
        int count,visibleRefs,localVisibleRefs;SurfaceQuadPlan.Owner light;Restoration restoreScan;
        int rgba;boolean colourKnown;
        Entry(TerrainLodColumns.Box box,int id,int count,SurfaceQuadPlan.PageKey[] keys){this.box=box;this.id=id;this.count=count;this.keys=keys;}
    }
    private static final class PageState {
        final SurfaceQuadPlan.PageKey key;int[] members=new int[0],visible=new int[0];
        SurfaceQuadPlan.Geometry surface;SurfaceQuadPlan.Prepared prepared;int missing;boolean local;
        Object provisionalDependencies;long provisionalRevision,provisionalProbeView=Long.MIN_VALUE;
        PageState(SurfaceQuadPlan.PageKey key){this.key=key;}
    }
    private record Staged(SurfaceQuadPlan.PageKey key,List<TerrainLodColumns.Box> members,int[] memberIds,
            SurfaceQuadPlan.Geometry surface,Set<TerrainLodColumns.Box> visible,boolean extracted){}
    private record Extraction(SurfaceQuadPlan.PageKey key,List<TerrainLodColumns.Box> members,int[] memberIds,
            List<TerrainLodColumns.Box> boxes,SurfaceQuadPlan.Geometry previous){}
    private record Preparation(PageState state,SurfaceQuadPlan.Geometry surface,SurfaceQuadPlan.Owner[] light){}
    private record Restoration(long revision){}
    private record Change(Entry old,int count,SurfaceQuadPlan.PageKey[] keys){}
    public IncrementalSurfacePages(SurfaceQuadPages.Limits limits,int pageMetres){
        this(limits,pageMetres,Boolean.parseBoolean(System.getProperty("mia.dh.deduplicateIncidence","true")));
    }
    /** Reference switch is diagnostic only; both representations have identical owner/page semantics. */
    public IncrementalSurfacePages(SurfaceQuadPages.Limits limits,int pageMetres,boolean deduplicateIndex){
        this.limits=Objects.requireNonNull(limits);if(pageMetres!=64&&pageMetres!=128)throw new IllegalArgumentException("Surface packing is64 or128 metres");this.pageMetres=pageMetres;
        this.deduplicateIndex=deduplicateIndex;owners=deduplicateIndex?new it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap<>(16,.75f,StrongBoxKey.STRATEGY):new HashMap<>();
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
        // This map retains the legacy BOX hash/capacity/insertion order: its iteration assigns slot IDs.
        var nextCounts=new HashMap<TerrainLodColumns.Box,Change>();
        var changes=new HashMap<SurfaceQuadPlan.PageKey,Map<TerrainLodColumns.Box,Change>>();
        long nextRefs=incidenceRefs;int nextOwners=ownerCount,births=0,deaths=0;
        for(var delta:deltas.entrySet()){check();var box=delta.getKey();var old=owners.get(box);int count=Math.addExact(old==null?0:old.count,delta.getValue());
            if(count<0)throw new IllegalArgumentException("Negative whole BOX multiset");var keys=old==null?membership(box):old.keys;
            var change=new Change(old,count,keys);nextCounts.put(box,change);nextOwners=Math.addExact(nextOwners,delta.getValue());
            nextRefs=Math.addExact(nextRefs,Math.multiplyExact((long)delta.getValue(),keys.length));if(old==null&&count>0)births++;if(old!=null&&count==0)deaths++;
            for(var key:keys)changes.computeIfAbsent(key,k->new HashMap<>()).put(box,change);
        }
        if(localGeometry)localKeys.addAll(changes.keySet());
        localKeys.removeIf(knownLocalKeys::contains);if(deltas.isEmpty()&&localKeys.isEmpty())return;
        if(nextRefs>limits.maxIncidenceRefs())throw cap("Incremental owner-page references exceed index budget");
        int nextPages=pages.size();var extraction=new ArrayList<Extraction>(changes.size());long nextRaw=rawFaces;
        for(var change:changes.entrySet()){check();var old=pages.get(change.getKey());
            var value=mergeMembers(change.getKey(),old,change.getValue());
            if(old==null&&!value.members().isEmpty())nextPages++;if(old!=null&&value.members().isEmpty())nextPages--;
            if(old!=null)nextRaw-=old.surface.faces().size();
            extraction.add(value);
        }
        var staged=executor==null?extraction.stream().map(this::extract).toList():BoundedOrderedWork.map(extraction,parallelism,executor,this::extract);
        for(var value:staged)if(value.surface()!=null)nextRaw+=value.surface().faces().size();
        long nextBytes=Math.addExact(Math.addExact(128L*nextPages,64L*(liveSlots+births-deaths)),Math.multiplyExact(16L,nextRefs));
        nextBytes=Math.addExact(nextBytes,Math.multiplyExact(64L,(long)knownLocalKeys.size()+localKeys.size()));
        long nextSharedBytes=deduplicateIndex?projectedRetainedBytes(nextPages,liveSlots+births-deaths,nextRefs,(long)knownLocalKeys.size()+localKeys.size(),nextCounts):nextBytes;
        if(Math.max(nextBytes,nextSharedBytes)>limits.maxIndexBytes())throw cap("Incremental retained incidence bytes exceed index budget");
        if(nextRaw>limits.maxRawFaces()||Math.multiplyExact(nextRaw,SurfaceQuadPlan.CPU_FACE_BYTES)>limits.maxRetainedFaceBytes())throw cap("Incremental source faces exceed intermediate budget");
        check();
        // All bounded index and geometry checks succeeded. Install this delta atomically.
        var retired=new ArrayList<Entry>();
        for(var e:nextCounts.entrySet()){var change=e.getValue();var old=change.old();if(old!=null){boolean was=missing(old),wasLocal=localMissing(old),wasVisible=old.count>0&&old.visibleRefs>0;old.count=change.count();if(was&&!missing(old))visibleMissing--;if(wasLocal&&!localMissing(old))localVisibleMissing--;if(wasVisible&&old.count==0)visibleOwners--;if(change.count()==0){owners.remove(e.getKey());retired.add(old);}}
            else if(change.count()>0){int id=free.isEmpty()?slots.size():free.removeFirst();var keys=change.keys();var entry=new Entry(e.getKey(),id,change.count(),deduplicateIndex?retainMembership(keys):keys);if(id==slots.size())slots.add(entry);else slots.set(id,entry);owners.put(entry.box,entry);liveSlots++;}}
        for(var value:staged){var old=pages.get(value.key());if(old!=null)for(int id:old.visible)changeVisibility(slots.get(id),-1,old.local);
            if(value.surface()==null){if(old!=null&&old.local)localPages--;pages.remove(value.key());continue;}
            var state=old==null?new PageState(value.key()):old;boolean same=state.surface==value.surface();state.surface=value.surface();if(!state.local&&(knownLocalKeys.contains(value.key())||localKeys.contains(value.key()))){state.local=true;localPages++;}
            // Existing slots were frozen with membership; resolve only births after their commit.
            state.members=value.memberIds();for(int i=0;i<state.members.length;i++)if(state.members[i]<0)state.members[i]=owners.get(value.members().get(i)).id;
            state.visible=new int[value.visible().size()];int at=0;for(var box:value.visible())state.visible[at++]=owners.get(box).id;Arrays.sort(state.visible);
            state.missing=0;for(int id:state.visible){var entry=slots.get(id);changeVisibility(entry,1,state.local);if(entry.light==null)state.missing++;}
            if(!same){state.prepared=null;state.provisionalDependencies=null;state.provisionalProbeView=Long.MIN_VALUE;}pages.put(state.key,state);if(value.extracted())extracts++;
        }
        for(var key:localKeys){var state=pages.get(key);if(state!=null&&!state.local){state.local=true;localPages++;for(int id:state.visible)changeLocalVisibility(slots.get(id),1);}}
        knownLocalKeys.addAll(localKeys);
        for(var entry:retired)release(entry);
        if(current.removeAll(changes.keySet()))currentSnapshot=null;
        incidenceRefs=nextRefs;legacyIndexBytes=nextBytes;indexBytes=deduplicateIndex?retainedBytes():nextBytes;ownerCount=nextOwners;rawFaces=(int)nextRaw;updates++;
    }
    /** Merge the already ordered persistent slots with only the changed keys, including multiplicity. */
    private Extraction mergeMembers(SurfaceQuadPlan.PageKey key,PageState old,Map<TerrainLodColumns.Box,Change> changes){
        var delta=new ArrayList<>(changes.entrySet());delta.sort((a,b)->compare(a.getKey(),b.getKey()));
        int oldSize=old==null?0:old.members.length,oi=0,di=0,used=0;
        var members=new ArrayList<TerrainLodColumns.Box>(oldSize+delta.size());var boxes=new ArrayList<TerrainLodColumns.Box>(old==null?delta.size():old.surface.owners().size());
        int[] ids=new int[oldSize+delta.size()];
        while(oi<oldSize||di<delta.size()){
            check();var previous=oi<oldSize?slots.get(old.members[oi]):null;var next=di<delta.size()?delta.get(di):null;
            int order=previous==null?1:next==null?-1:compare(previous.box,next.getKey());
            TerrainLodColumns.Box box;int count,id;
            if(order<0){box=previous.box;count=previous.count;id=previous.id;oi++;}
            else if(order==0){box=previous.box;count=next.getValue().count();id=previous.id;oi++;di++;}
            else{box=next.getKey();count=next.getValue().count();var known=next.getValue().old();id=known==null?-1:known.id;di++;}
            if(count==0)continue;members.add(box);ids[used++]=id;for(int n=0;n<count;n++)boxes.add(box);
        }
        return new Extraction(key,List.copyOf(members),used==ids.length?ids:Arrays.copyOf(ids,used),List.copyOf(boxes),old==null?null:old.surface);
    }
    /** No mutable index, owner slots, cached light or publication state is read or written here. */
    private Staged extract(Extraction value){
        check();SurfaceQuadPlan.Geometry surface=null;boolean extracted=false;
        if(!value.boxes().isEmpty()){
            if(value.previous()!=null&&value.previous().owners().equals(value.boxes()))surface=value.previous();
            else{surface=clip(SurfaceQuadPlan.extractForMerge(value.boxes(),limits.maxRawFaces(),limits.maxRetainedFaceBytes()),value.key());extracted=true;}
        }
        var visible=new HashSet<TerrainLodColumns.Box>();if(surface!=null)for(int local:surface.visibleOwners())visible.add(surface.owners().get(local));
        return new Staged(value.key(),value.members(),value.memberIds(),surface,visible,extracted);
    }

    public void setLightLookup(Function<TerrainLodColumns.Box,SurfaceQuadPlan.Owner> lookup){setLightLookup(lookup,null);}
    /** Revision must advance after every newly visible cache admission; misses are reusable only at that revision. */
    public void setLightLookup(Function<TerrainLodColumns.Box,SurfaceQuadPlan.Owner> lookup,LongSupplier revision){
        this.lookup=Objects.requireNonNull(lookup);this.lookupRevision=revision;cachedRestoration=null;
        for(var entry:owners.values())entry.restoreScan=null;
    }
    public void invalidatePrepared(){
        visibleMissing=0;localVisibleMissing=0;for(var entry:owners.values()){entry.light=null;entry.restoreScan=null;entry.colourKnown=false;if(entry.visibleRefs>0)visibleMissing++;if(entry.localVisibleRefs>0)localVisibleMissing++;}
        for(var page:pages.values()){page.prepared=null;page.missing=page.visible.length;}current.clear();currentSnapshot=Set.of();
    }
    public List<TerrainLodColumns.Box> missingOwners(Comparator<SurfaceQuadPlan.PageKey> order,int maximum){
        Objects.requireNonNull(order);if(maximum<1)throw new IllegalArgumentException("Positive owner quantum required");
        var selected=new ArrayList<TerrainLodColumns.Box>();var seen=new HashSet<Entry>();var scan=restoration();
        for(var key:ordered(pages.keySet(),order)){check();var page=pages.get(key);if(page.missing==0)continue;
            for(int id:page.visible){var entry=slots.get(id);if(entry.light!=null||!seen.add(entry))continue;restore(entry,scan);
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
        var scan=restoration();
        for(var key:ordered(pages.keySet(),order)){check();var page=pages.get(key);if(page.missing==0||(localRole!=null&&page.local!=localRole))continue;
            var selected=new ArrayList<TerrainLodColumns.Box>();
            // Visible IDs are unique even when a physical BOX has multiple multiset occurrences.
            for(int id:page.visible){var entry=slots.get(id);if(entry.light!=null)continue;restore(entry,scan);
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
        var selected=new ArrayList<TerrainLodColumns.Box>();var seen=new HashSet<Entry>();var scan=restoration();
        for(var key:ordered(pages.keySet(),order)){check();var page=pages.get(key);if(page.missing==0||page.local!=localRole)continue;
            for(int id:page.visible){var entry=slots.get(id);if(entry.light!=null||!seen.add(entry))continue;restore(entry,scan);
                if(entry.light!=null||claimed.contains(entry.box)||(!localRole&&localMissing(entry)))continue;
                selected.add(entry.box);if(selected.size()==maximum)return List.copyOf(selected);}
        }
        return List.copyOf(selected);
    }
    public boolean isUnlitLocalOwner(TerrainLodColumns.Box box){var entry=owners.get(Objects.requireNonNull(box));return entry!=null&&localMissing(entry);}
    public List<dev.madeinabyss.kernel.light.VisibleLightAudit.Sample> visibleAudit(dev.madeinabyss.kernel.light.ProvisionalSkyPages.View view,
            Comparator<SurfaceQuadPlan.PageKey> order,int maximum,java.util.function.Predicate<dev.madeinabyss.kernel.light.ProvisionalSkyPages.Point> visible){
        if(maximum<1||maximum>4096)throw new IllegalArgumentException("Bounded visible sample required");
        var result=new ArrayList<dev.madeinabyss.kernel.light.VisibleLightAudit.Sample>();var seen=new HashSet<TerrainLodColumns.Box>();
        Comparator<AuditCandidate> candidateOrder=Comparator.comparingDouble(AuditCandidate::area).reversed().thenComparingDouble(AuditCandidate::distance)
                .thenComparingInt(c->c.sample.point().x()).thenComparingInt(c->c.sample.point().y()).thenComparingInt(c->c.sample.point().z());
        for(var key:ordered(pages.keySet(),order)){check();var state=pages.get(key);
            if(!dev.madeinabyss.kernel.light.ProvisionalSkyPages.intersectsPage(key,view))continue;
            var slots=new ArrayList<TreeSet<AuditCandidate>>(4);for(int i=0;i<4;i++)slots.add(new TreeSet<>(candidateOrder));
            for(var f:state.surface.faces()){
                double side=f.axis()==0?view.x()-f.plane():f.axis()==1?view.y()-f.plane():view.z()-f.plane();if(f.positive()?side<0:side>0)continue;
                long u=f.u0()+(f.u1()-f.u0()-1)/2,v=f.v0()+(f.v1()-f.v0()-1)/2,n=f.plane()+(f.positive()?0:-1);
                var point=new dev.madeinabyss.kernel.light.ProvisionalSkyPages.Point(Math.toIntExact(f.axis()==0?n:u),Math.toIntExact(f.axis()==1?n:v),Math.toIntExact(f.axis()==0?u:f.axis()==1?v:n));
                if(!dev.madeinabyss.kernel.light.ProvisionalSkyPages.inView(point,view))continue;
                var box=state.surface.owners().get(f.owner());if(seen.contains(box))continue;
                double dx=point.x()+.5-view.x(),dy=point.y()+.5-view.y(),dz=point.z()+.5-view.z();
                var sample=new dev.madeinabyss.kernel.light.VisibleLightAudit.Sample(box,key,point);
                var c=new AuditCandidate(sample,(double)(f.u1()-f.u0())*(f.v1()-f.v0()),dx*dx+dy*dy+dz*dz);
                int slot=(int)(Math.floorMod(u,128)/64+2*(Math.floorMod(v,128)/64));var set=slots.get(slot);
                if(set.size()<16||candidateOrder.compare(c,set.last())<0){set.add(c);if(set.size()>16)set.pollLast();}
            }
            for(var slot:slots)for(var c:slot){if(seen.contains(c.sample.box())||!visible.test(c.sample.point()))continue;
                seen.add(c.sample.box());result.add(c.sample);if(result.size()>=maximum)return List.copyOf(result);break;}
        }return List.copyOf(result);
    }
    private record AuditCandidate(dev.madeinabyss.kernel.light.VisibleLightAudit.Sample sample,double area,double distance){}
    public Integer presentedAuditSky(dev.madeinabyss.kernel.light.VisibleLightAudit.Sample sample,Set<SurfaceQuadPlan.PageKey> current,
            java.util.function.ToIntFunction<TerrainLodColumns.Box> provisional){
        if(!current.contains(sample.page()))return null;var state=pages.get(sample.page());var entry=owners.get(sample.box());
        if(state==null||state.prepared==null||entry==null||Arrays.binarySearch(state.visible,entry.id)<0)return null;
        return entry.light!=null?entry.light.sky():provisional.applyAsInt(sample.box());
    }
    public Integer exactAuditSky(TerrainLodColumns.Box box){var entry=owners.get(box);return entry==null||entry.light==null?null:entry.light.sky();}
    /** Presentation debt only; exact owners and pages outside this visibility set never hold its quota. */
    public boolean pendingProvisional(SurfaceProvisionalLight provisional,java.util.function.Predicate<SurfaceQuadPlan.PageKey> visible){
        Objects.requireNonNull(provisional);Objects.requireNonNull(visible);
        for(var state:pages.values())if(state.missing>0&&!state.surface.faces().isEmpty()&&visible.test(state.key)){
            if(state.provisionalProbeView!=provisional.viewRevision())return true;
        }return false;
    }
    public List<TerrainLodColumns.Box> requestOwners(Collection<TerrainLodColumns.Box> requested,int maximum,Set<TerrainLodColumns.Box> claimed){
        if(maximum<1)throw new IllegalArgumentException("Positive owner quantum required");var selected=new LinkedHashSet<TerrainLodColumns.Box>();var scan=restoration();
        for(var box:requested){var entry=owners.get(box);if(entry==null||entry.light!=null||entry.visibleRefs==0||claimed.contains(box))continue;restore(entry,scan);
            if(entry.light!=null)continue;selected.add(box);if(selected.size()>=maximum)break;
        }return List.copyOf(selected);
    }
    public List<TerrainLodColumns.Box> missingPriorityOwners(Comparator<SurfaceQuadPlan.PageKey> order,int maximum,
            Set<TerrainLodColumns.Box> claimed,java.util.function.Predicate<SurfaceQuadPlan.PageKey> priority){
        if(maximum<1)throw new IllegalArgumentException("Positive priority quantum required");
        var selected=new ArrayList<TerrainLodColumns.Box>();var seen=new HashSet<Entry>();var scan=restoration();
        for(var key:ordered(pages.keySet(),order)){var page=pages.get(key);if(page.missing==0||!priority.test(key))continue;
            for(int id:page.visible){var entry=slots.get(id);if(entry.light!=null||!seen.add(entry))continue;restore(entry,scan);
                if(entry.light!=null||claimed.contains(entry.box))continue;selected.add(entry.box);if(selected.size()==maximum)return List.copyOf(selected);}}
        return List.copyOf(selected);
    }
    /** True while at least one exact physical BOX occurrence belongs to this source context. */
    public boolean containsOwner(TerrainLodColumns.Box box){return owners.containsKey(Objects.requireNonNull(box));}
    /** Resolve scalar light against the current resource colour. */
    public SurfaceQuadPlan.Owner scalarOwner(TerrainLodColumns.Box box,int sky,int block,java.util.function.ToIntFunction<TerrainLodColumns.Box> colours){
        var entry=owners.get(Objects.requireNonNull(box));
        return new SurfaceQuadPlan.Owner(box,sky,block,entry!=null&&entry.colourKnown?entry.rgba:colours.applyAsInt(box));
    }
    public void supplyLight(TerrainLodColumns.Box box,SurfaceQuadPlan.Owner light){
        Objects.requireNonNull(box);Objects.requireNonNull(light);if(!box.equals(light.box()))throw new IllegalArgumentException("Original physical source owner changed");
        var entry=owners.get(box);if(entry==null)throw new IllegalArgumentException("Unknown live physical source owner");if(light.equals(entry.light))return;
        if(entry.light!=null&&(entry.light.sky()!=light.sky()||entry.light.block()!=light.block()))throw new IllegalArgumentException("A different physical scalar requires a new source context");
        boolean was=missing(entry),wasLocal=localMissing(entry);entry.light=light;entry.rgba=light.rgba();entry.colourKnown=true;if(was)visibleMissing--;if(wasLocal)localVisibleMissing--;
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
        return preparePartial(retained,maxChanged,order,executor,parallelism,restoreMissing,null);
    }
    /** Presentation-only coarse light can make geometry current without paying exact scalar debt.
     * It never enters Entry.light or the exact lookup/archive. A completed exact value invalidates
     * its affected prepared pages normally. Null keeps the historical exact-only behaviour. */
    public SurfaceQuadPages.Partial preparePartial(SurfaceQuadPlan.PageSet retained,int maxChanged,Comparator<SurfaceQuadPlan.PageKey> order,
            ExecutorService executor,int parallelism,boolean restoreMissing,Function<TerrainLodColumns.Box,SurfaceQuadPlan.Owner> provisional){
        Objects.requireNonNull(order);if(maxChanged<1)throw new IllegalArgumentException("Positive page quantum required");
        if(parallelism<1||parallelism>12||(executor==null&&parallelism!=1))throw new IllegalArgumentException("A bounded executor is required for parallel page preparation");capKind="";
        var result=new HashMap<SurfaceQuadPlan.PageKey,SurfaceQuadPlan.Page>(retained==null?0:mapCapacity(retained.pages().size()));if(retained!=null)for(var page:retained.pages())result.put(page.key(),page);
        var keys=new ArrayList<>(pages.keySet());for(var key:result.keySet())if(!pages.containsKey(key))keys.add(key);
        var ready=new HashSet<SurfaceQuadPlan.PageKey>();var changed=new HashSet<SurfaceQuadPlan.PageKey>();int pending=0;var scan=restoration();
        var preparation=new ArrayList<Preparation>();
        var orderedKeys=ordered(keys,order);
        var pageProvisional=provisional instanceof SurfaceProvisionalLight enhanced?enhanced:null;
        if(pageProvisional!=null){
            var batch=new ArrayList<SurfaceProvisionalLight.Page>();
            for(var key:orderedKeys){var state=pages.get(key);
                if(state!=null&&state.missing>0){
                    if(state.provisionalDependencies==null)state.provisionalDependencies=pageProvisional.dependencies(state.surface);
                    if(state.prepared!=null&&state.provisionalRevision!=pageProvisional.revision(state.provisionalDependencies))state.prepared=null;
                }
                if(state!=null&&state.missing>0&&state.prepared==null&&!state.surface.faces().isEmpty()
                        &&state.provisionalProbeView!=pageProvisional.viewRevision()){
                    batch.add(new SurfaceProvisionalLight.Page(key,state.surface));if(batch.size()>=maxChanged)break;
                }}
            if(!batch.isEmpty()){
                pageProvisional.prepare(batch,executor,parallelism);
                for(var page:batch)pages.get(page.key()).provisionalProbeView=pageProvisional.viewRevision();
            }
        }
        for(var key:orderedKeys){check();var state=pages.get(key);var old=result.get(key);
            if(state==null||state.surface.faces().isEmpty()){
                if(old==null){if(state!=null)ready.add(key);continue;}if(changed.size()>=maxChanged){pending++;continue;}
                result.remove(key);changed.add(key);if(state!=null)ready.add(key);continue;
            }
            if(restoreMissing&&state.missing>0)for(int id:state.visible){var entry=slots.get(id);if(entry.light==null)restore(entry,scan);}
            if(state.missing>0&&provisional==null){pending++;continue;}
            if(pageProvisional!=null&&state.missing>0){
                if(state.provisionalDependencies==null)state.provisionalDependencies=pageProvisional.dependencies(state.surface);
                long revision=pageProvisional.revision(state.provisionalDependencies);
                if(state.prepared!=null&&state.provisionalRevision!=revision)state.prepared=null;
                state.provisionalRevision=revision;
            }
            if(state.prepared!=null&&old!=null&&old.prepared()==state.prepared){ready.add(key);reused++;continue;}
            if(changed.size()>=maxChanged){pending++;continue;}
            if(state.prepared==null){
                var light=new SurfaceQuadPlan.Owner[state.surface.owners().size()];
                for(int id:state.surface.visibleOwners())light[id]=owners.get(state.surface.owners().get(id)).light;
                preparation.add(new Preparation(state,state.surface,light));
            }else{reused++;result.put(key,new SurfaceQuadPlan.Page(key,state.prepared));}
            ready.add(key);changed.add(key);
        }
        Function<Preparation,SurfaceQuadPlan.Prepared> prepare=value->SurfaceQuadPlan.prepare(value.surface(),id->{
            var exact=value.light()[id];if(exact!=null)return exact;
            var box=value.surface().owners().get(id);var temporary=Objects.requireNonNull(provisional.apply(box),"Unknown provisional light cannot publish a page");
            if(!box.equals(temporary.box()))throw new IllegalArgumentException("Provisional physical source owner changed");return temporary;
        });
        var prepared=executor==null?preparation.stream().map(prepare).toList():BoundedOrderedWork.map(preparation,parallelism,executor,prepare);
        // No prepared page enters the mutable index until the entire bounded worker batch succeeds.
        check();for(int i=0;i<preparation.size();i++){var state=preparation.get(i).state();state.prepared=prepared.get(i);
            for(var owner:state.prepared.owners()){var entry=owners.get(owner.box());entry.rgba=owner.rgba();entry.colourKnown=true;}
            built++;result.put(state.key,new SurfaceQuadPlan.Page(state.key,state.prepared));}
        var list=new ArrayList<>(result.values());list.sort(Comparator.comparing(SurfaceQuadPlan.Page::key,pageOrder()));int count=0,opaque=0,water=0;long vertices=0;
        for(var page:list){var plan=page.prepared();count=Math.addExact(count,plan.faces().size());vertices=Math.addExact(vertices,plan.vertexBytes());
            opaque=Math.addExact(opaque,page.opaqueFaces());water=Math.addExact(water,page.waterFaces());}
        long gpu=Math.multiplyExact((long)count,SurfaceQuadPlan.GPU_BYTES_PER_FACE);
        if(list.size()>limits.maxPages()||count>limits.maxFaces()||gpu>limits.maxGpuBytes()){capKind=list.size()>limits.maxPages()?"PAGECOUNT":count>limits.maxFaces()?"FACES":"GPU_BYTES";throw cap("Incremental aggregate retained/ready "+capKind+" cap exceeded");}
        var published=new SurfaceQuadPlan.PageSet(list,vertices,gpu,count,opaque,water);var partial=new SurfaceQuadPages.Partial(published,ready,pending,changed);
        current=ready;currentSnapshot=partial.currentKeys();return partial;
    }
    public Set<SurfaceQuadPlan.PageKey> targetKeys(){return Set.copyOf(pages.keySet());}
    public Set<SurfaceQuadPlan.PageKey> currentPages(){if(currentSnapshot==null)currentSnapshot=Set.copyOf(current);return currentSnapshot;}
    public Map<String,Object> status(){var result=new LinkedHashMap<String,Object>();result.put("owners",ownerCount);result.put("uniqueOwners",owners.size());result.put("visibleMissing",visibleMissing);result.put("visibleOwners",visibleOwners);result.put("localVisibleMissing",localVisibleMissing);result.put("localPages",localPages);result.put("knownLocalKeysCount",knownLocalKeys.size());result.put("rawFaces",rawFaces);
        result.put("geometryCalls",0L);result.put("dirtyKeyExtracts",extracts);result.put("updates",updates);result.put("incidenceRefs",incidenceRefs);result.put("retainedIndexBytes",indexBytes);result.put("legacyIndexBytes",legacyIndexBytes);result.put("deduplicateIndex",deduplicateIndex);result.put("membershipArrays",memberships.size());result.put("canonicalPageKeys",canonicalKeys.size());result.put("targetPages",pages.size());result.put("reusedPages",reused);result.put("builtPages",built);result.put("pageMetres",pageMetres);result.put("capKind",capKind);result.put("remaining",visibleMissing);return Collections.unmodifiableMap(result);}
    public boolean pageCountRefused(){return "PAGECOUNT".equals(capKind);}
    private Restoration restoration(){
        if(lookupRevision==null)return new Restoration(0);
        long revision=lookupRevision.getAsLong();
        if(cachedRestoration==null||cachedRestoration.revision()!=revision)cachedRestoration=new Restoration(revision);
        return cachedRestoration;
    }
    private void restore(Entry entry,Restoration scan){
        if(entry.restoreScan==scan)return;
        var value=lookup.apply(entry.box);if(value!=null)supplyLight(entry.box,value);
        // A throwing/cancelled lookup must remain retryable, including within the same cache revision.
        entry.restoreScan=scan;
    }
    private static int mapCapacity(int size){return (int)Math.min(1L<<30,(4L*size+2)/3);}
    private static boolean missing(Entry entry){return entry.count>0&&entry.visibleRefs>0&&entry.light==null;}
    private static boolean localMissing(Entry entry){return entry.count>0&&entry.localVisibleRefs>0&&entry.light==null;}
    private void changeLocalVisibility(Entry entry,int delta){boolean before=localMissing(entry);entry.localVisibleRefs+=delta;boolean after=localMissing(entry);if(before!=after)localVisibleMissing+=after?1:-1;}
    private void changeVisibility(Entry entry,int delta,boolean local){if(local)changeLocalVisibility(entry,delta);boolean before=missing(entry),visibleBefore=entry.count>0&&entry.visibleRefs>0;entry.visibleRefs+=delta;boolean after=missing(entry),visibleAfter=entry.count>0&&entry.visibleRefs>0;if(visibleBefore!=visibleAfter)visibleOwners+=visibleAfter?1:-1;if(before!=after)visibleMissing+=after?1:-1;
        release(entry);}
    private void release(Entry entry){if(entry.count==0&&entry.visibleRefs==0&&entry.localVisibleRefs==0&&slots.get(entry.id)==entry){if(deduplicateIndex)releaseMembership(entry.keys);slots.set(entry.id,null);free.addLast(entry.id);liveSlots--;}}
    private SurfaceQuadPlan.PageKey[] retainMembership(SurfaceQuadPlan.PageKey[] keys){
        var signature=new KeySignature(keys);var known=memberships.get(signature);
        if(known==null){var retained=keys.clone();for(int i=0;i<retained.length;i++){var key=canonicalKeys.get(retained[i]);if(key==null){key=new CanonicalKey(retained[i]);canonicalKeys.put(key.key,key);}key.references++;retained[i]=key.key;}
            signature=new KeySignature(retained);known=new Membership(signature);memberships.put(signature,known);membershipBytes+=128L+8L*retained.length;}
        known.references++;return known.signature.keys;
    }
    private void releaseMembership(SurfaceQuadPlan.PageKey[] keys){
        var signature=new KeySignature(keys);var group=Objects.requireNonNull(memberships.get(signature));
        if(--group.references==0){memberships.remove(signature);membershipBytes-=128L+8L*keys.length;for(var value:keys){var key=Objects.requireNonNull(canonicalKeys.get(value));if(--key.references==0)canonicalKeys.remove(value);}}
    }
    private long retainedBytes(){
        // Same conservative owner/page budget. Shared signatures and canonical keys
        // are counted once; page member/visible int arrays use <=8 bytes per incidence.
        long bytes=128L*pages.size()+64L*liveSlots+8L*incidenceRefs+64L*knownLocalKeys.size()+128L*canonicalKeys.size();
        return bytes+membershipBytes;
    }
    /** Forecast pure metadata, without interning keys or touching a live owner before the cap succeeds. */
    private long projectedRetainedBytes(int pageCount,int liveCount,long refs,long localCount,
            Map<TerrainLodColumns.Box,Change> nextCounts){
        var deltas=new HashMap<KeySignature,Integer>();
        for(var change:nextCounts.values()){var old=change.old();
            if(old==null&&change.count()>0)deltas.merge(new KeySignature(change.keys()),1,Math::addExact);
            else if(old!=null&&change.count()==0)deltas.merge(new KeySignature(old.keys),-1,Math::addExact);}
        long signatureBytes=membershipBytes;var keyDeltas=new HashMap<SurfaceQuadPlan.PageKey,Integer>();
        for(var item:deltas.entrySet()){var old=memberships.get(item.getKey());int count=Math.addExact(old==null?0:old.references,item.getValue());
            if(count<0)throw new IllegalStateException("Shared membership reference underflow");
            int transition=old==null?(count>0?1:0):(count==0?-1:0);if(transition==0)continue;
            signatureBytes+=transition*(128L+8L*item.getKey().keys.length);
            for(var key:item.getKey().keys)keyDeltas.merge(key,transition,Math::addExact);}
        int keyCount=canonicalKeys.size();
        for(var item:keyDeltas.entrySet()){var old=canonicalKeys.get(item.getKey());int count=Math.addExact(old==null?0:old.references,item.getValue());
            if(count<0)throw new IllegalStateException("Canonical page reference underflow");if(old==null&&count>0)keyCount++;else if(old!=null&&count==0)keyCount--;}
        return 128L*pageCount+64L*liveCount+8L*refs+64L*localCount+signatureBytes+128L*keyCount;
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
