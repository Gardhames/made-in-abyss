package dev.madeinabyss.kernel.tick;

import dev.madeinabyss.kernel.BlockCoordinate;
import dev.madeinabyss.kernel.cube.CubeKey;
import java.util.*;

/** Bounded single-owner queue. Selection is nonconsuming; dispatch edits a revision-checked fork. */
public final class CubeScheduledTicks {
    public static final int MAX_PER_CUBE = 512;
    public static final int MAX_TOTAL = 4096;
    public record Tick(SavedCubeTick.Kind kind, String type, BlockCoordinate position, long triggerTick, int priority, long order) {
        public Tick { SavedCubeTick.validate(kind,type,position,priority); }
    }
    private record Identity(SavedCubeTick.Kind kind, String type, BlockCoordinate position) {
        static Identity of(Tick t) { return new Identity(t.kind(), t.type(), t.position()); }
    }
    private static final Comparator<Tick> ORDER = Comparator.comparingLong(Tick::triggerTick)
            .thenComparingInt(Tick::priority).thenComparingLong(Tick::order)
            .thenComparingInt(t -> t.position().x()).thenComparingInt(t -> t.position().y())
            .thenComparingInt(t -> t.position().z()).thenComparing(Tick::type).thenComparing(Tick::kind);
    private final Thread owner;
    private final CubeScheduledTicks parent;
    private final long parentRevision;
    private Map<Identity,Tick> entries;
    private long clock;
    private long revision;
    public CubeScheduledTicks() {
        owner=Thread.currentThread(); parent=null; parentRevision=-1; entries=new HashMap<>();
    }
    private CubeScheduledTicks(CubeScheduledTicks source) {
        owner=source.owner; parent=source; parentRevision=source.revision;
        entries=new HashMap<>(source.entries); clock=source.clock; revision=source.revision;
    }
    public void assertOwner() {
        if(Thread.currentThread()!=owner) throw new IllegalStateException("scheduled ticks require their owner thread");
    }
    /** Native first-wins identity: due/priority/order never replace an already pending tick. */
    public boolean schedule(Tick tick) {
        assertOwner(); Objects.requireNonNull(tick); var id=Identity.of(tick);
        if(entries.containsKey(id)) return false;
        CubeKey key=CubeKey.fromBlock(tick.position());
        if(entries.size()>=MAX_TOTAL || entries.values().stream().filter(t -> CubeKey.fromBlock(t.position()).equals(key)).count()>=MAX_PER_CUBE)
            throw new IllegalArgumentException("scheduled tick budget exceeded");
        long next=Math.incrementExact(revision); entries.put(id,tick); revision=next; return true;
    }
    public boolean consume(Tick tick) {
        assertOwner(); Objects.requireNonNull(tick); var id=Identity.of(tick);
        if(!tick.equals(entries.get(id))) return false;
        long next=Math.incrementExact(revision); entries.remove(id); revision=next; return true;
    }
    public CubeScheduledTicks copy() { assertOwner(); return new CubeScheduledTicks(this); }
    public void validateReplacement(CubeScheduledTicks fork) {
        assertOwner(); Objects.requireNonNull(fork); fork.assertOwner();
        if(fork.parent!=this || fork.parentRevision!=revision) throw new IllegalStateException("foreign or stale scheduled tick fork");
        Math.incrementExact(revision);
    }
    public void replaceWith(CubeScheduledTicks fork) {
        validateReplacement(fork); var replacement=new HashMap<>(fork.entries);
        long next=Math.incrementExact(revision); entries=replacement; clock=fork.clock; revision=next;
    }
    /** Take one immutable list per pass, so self-scheduled delay-zero ticks wait for another pass. */
    public List<Tick> due(long now, int limit) {
        assertOwner(); if(limit<0) throw new IllegalArgumentException("negative dispatch budget");
        return entries.values().stream().filter(t -> t.triggerTick()<=now).sorted(ORDER).limit(limit).toList();
    }
    public int pendingCount() { assertOwner(); return entries.size(); }
    public List<SavedCubeTick> snapshot(CubeKey key) { return snapshot(key,clock); }
    public List<SavedCubeTick> snapshot(CubeKey key,long now) {
        assertOwner(); Objects.requireNonNull(key);
        return entries.values().stream().filter(t -> CubeKey.fromBlock(t.position()).equals(key)).sorted(ORDER)
                .map(t -> new SavedCubeTick(t.kind(),t.type(),t.position(),Math.subtractExact(t.triggerTick(),now),t.priority(),t.order())).toList();
    }
    /** Loading does not run ticks; signed remaining delay preserves overdue work. */
    public void load(CubeKey key,List<SavedCubeTick> saved) {
        assertOwner(); var fork=copy();
        for(var t:saved) {
            if(!CubeKey.fromBlock(t.position()).equals(key)) throw new IllegalArgumentException("tick outside saved cube");
            if(!fork.schedule(new Tick(t.kind(),t.type(),t.position(),Math.addExact(clock,t.delay()),t.priority(),t.order())))
                throw new IllegalArgumentException("duplicate saved tick");
        }
        replaceWith(fork);
    }
    public void removeCube(CubeKey key) {
        assertOwner(); Objects.requireNonNull(key);
        if(entries.values().stream().noneMatch(t -> CubeKey.fromBlock(t.position()).equals(key))) return;
        long next=Math.incrementExact(revision); entries.values().removeIf(t -> CubeKey.fromBlock(t.position()).equals(key)); revision=next;
    }
    public void advanceTime(long now) {
        assertOwner(); if(now<clock) throw new IllegalArgumentException("scheduled clock cannot move backwards");
        if(now==clock) return; long next=Math.incrementExact(revision); clock=now; revision=next;
    }
    public long clock() { assertOwner(); return clock; }
}
