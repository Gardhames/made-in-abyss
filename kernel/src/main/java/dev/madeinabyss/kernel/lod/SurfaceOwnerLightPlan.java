package dev.madeinabyss.kernel.lod;

import java.util.*;
import java.util.function.Function;

/** Coordinator-owned original-owner scalars and monotonic missing-work cursors. */
public final class SurfaceOwnerLightPlan<L> {
    public record Scope(String session,long lifetime,String sourceIdentity,long sourceRevision){
        public Scope{Objects.requireNonNull(session);Objects.requireNonNull(sourceIdentity);if(session.isEmpty()||sourceIdentity.isEmpty())throw new IllegalArgumentException("Source light lease required");}}
    public static final class Batch extends AbstractList<Integer> implements RandomAccess {
        private final Object plan;private final int[] owners;private final boolean fine,selected;private final long revision;
        private Batch(Object plan,int[] owners,boolean fine,boolean selected,long revision){this.plan=plan;this.owners=owners;this.fine=fine;this.selected=selected;this.revision=revision;}
        @Override public Integer get(int index){Objects.checkIndex(index,size());return owners[index];}
        @Override public int size(){return owners.length;}
    }
    private final Scope scope;
    private final List<TerrainLodColumns.Box> owners;
    private final Object[] values;
    private final Map<TerrainLodColumns.Box,Integer> index=new HashMap<>();
    private final int[] next,prev;
    private final BitSet visible;
    private final int fineStart;
    private int fineHead=-1,farHead=-1,fineCount,farCount,remaining;
    private long fineRevision,farRevision;
    public SurfaceOwnerLightPlan(Scope scope,List<TerrainLodColumns.Box> owners,List<Integer> visibleOwners,int fineStart,int maxOwners,
            SurfaceOwnerLightPlan<L> previous,Function<TerrainLodColumns.Box,L> sourceCache){
        this.scope=Objects.requireNonNull(scope);Objects.requireNonNull(sourceCache);
        if(maxOwners<1||owners.size()>maxOwners||visibleOwners.size()>owners.size()||fineStart<0||fineStart>owners.size())throw new IllegalArgumentException("Bounded original-owner light plan required");
        this.owners=List.copyOf(owners);this.fineStart=fineStart;values=new Object[owners.size()];visible=new BitSet(owners.size());
        next=new int[owners.size()];prev=new int[owners.size()];Arrays.fill(next,-1);Arrays.fill(prev,-1);int fineTail=-1,farTail=-1;
        for(int owner:visibleOwners){Objects.checkIndex(owner,owners.size());if(visible.get(owner))throw new IllegalArgumentException("Duplicate visible source owner");visible.set(owner);
            var box=owners.get(owner);L value=previous!=null&&scope.equals(previous.scope)?previous.find(box):null;
            if(value==null)value=sourceCache.apply(box);values[owner]=value;index.put(box,owner);
            if(value==null){remaining++;
                if(owner>=fineStart){fineCount++;prev[owner]=fineTail;if(fineTail<0)fineHead=owner;else next[fineTail]=owner;fineTail=owner;}
                else{farCount++;prev[owner]=farTail;if(farTail<0)farHead=owner;else next[farTail]=owner;farTail=owner;}}}
    }
    public TerrainLodColumns.Box box(int owner){return owners.get(owner);}
    @SuppressWarnings("unchecked") public L value(int owner){Objects.checkIndex(owner,values.length);return (L)values[owner];}
    private L find(TerrainLodColumns.Box box){var owner=index.get(box);return owner==null?null:value(owner);}
    public Batch pendingFine(){return pending(true,Integer.MAX_VALUE);}
    /** Selected page work never scans or copies the old all-owner dictionary. */
    public Batch pendingOwners(List<Integer> selected,int maximum){
        Objects.requireNonNull(selected);if(maximum<1)throw new IllegalArgumentException("Positive selected-owner quantum required");
        int[] ids=new int[Math.min(maximum,Math.min(selected.size(),values.length))];int count=0;var seen=new HashSet<Integer>();
        for(int owner:selected){Objects.checkIndex(owner,values.length);if(!visible.get(owner))throw new IllegalArgumentException("Invisible original source owner");
            if(values[owner]!=null||!seen.add(owner))continue;ids[count++]=owner;if(count==ids.length)break;}
        return new Batch(this,Arrays.copyOf(ids,count),false,true,0);
    }
    public Batch pendingFar(int maximum){if(maximum<1)throw new IllegalArgumentException("Positive FAR quantum required");return pending(false,maximum);}
    private Batch pending(boolean fine,int maximum){
        int[] ids=new int[Math.min(maximum,fine?fineCount:farCount)];int count=0;
        for(int owner=fine?fineHead:farHead;owner>=0&&count<ids.length;owner=next[owner])ids[count++]=owner;
        return new Batch(this,Arrays.copyOf(ids,count),fine,false,fine?fineRevision:farRevision);
    }
    public int remaining(){return remaining;}
    public void complete(Batch batch,List<? extends L> completed){
        Objects.requireNonNull(batch);Objects.requireNonNull(completed);
        if(batch.plan!=this||!batch.selected&&batch.revision!=(batch.fine?fineRevision:farRevision)||completed.size()!=batch.size())
            throw new IllegalArgumentException("Stale or foreign original-owner light batch");
        // Validate the whole worker result before mutating any scalar or advancing a cursor.
        for(var value:completed)Objects.requireNonNull(value,"Completed original-owner light");
        var seen=new HashSet<Integer>();for(int owner:batch.owners)if(!visible.get(owner)||values[owner]!=null||!seen.add(owner))throw new IllegalArgumentException("Already completed original-owner light batch");
        boolean fineChanged=false,farChanged=false;
        for(int i=0;i<batch.size();i++){int owner=batch.owners[i];values[owner]=completed.get(i);int before=prev[owner],after=next[owner];
            if(before>=0)next[before]=after;else if(owner>=fineStart)fineHead=after;else farHead=after;
            if(after>=0)prev[after]=before;prev[owner]=-1;next[owner]=-1;
            if(owner>=fineStart){fineCount--;fineChanged=true;}else{farCount--;farChanged=true;}}
        if(fineChanged)fineRevision++;if(farChanged)farRevision++;remaining-=batch.size();
    }
}
