package dev.madeinabyss.kernel.concurrent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Single-owner bounded frame trace. Hot paths allocate nothing; snapshots own their arrays. */
public final class FrameCostTrace {
    public record Frame(long sequence,long endWallMillis,long durationNanos,long target,int state,
                        long[] inclusiveNanos,long[] exclusiveNanos,int[] calls){}
    private final LongSupplier clock;
    private final int stages,capacity;
    private final long[] inclusive,exclusive,storedInclusive,storedExclusive,endWall,durations,targets;
    private final int[] calls,storedCalls,states;
    private final int[] stackStage=new int[64];
    private final long[] stackStart=new long[64],stackChildren=new long[64];
    private int depth,size;
    private long sequence,lastEnd;
    private boolean initialized;

    public FrameCostTrace(LongSupplier clock,int stages,int capacity) {
        if(stages<1||capacity<1)throw new IllegalArgumentException("Empty frame trace");
        this.clock=Objects.requireNonNull(clock);this.stages=stages;this.capacity=capacity;
        inclusive=new long[stages];exclusive=new long[stages];calls=new int[stages];
        storedInclusive=new long[Math.multiplyExact(stages,capacity)];storedExclusive=new long[storedInclusive.length];storedCalls=new int[storedInclusive.length];
        endWall=new long[capacity];durations=new long[capacity];targets=new long[capacity];states=new int[capacity];
    }
    public int begin(int stage) {
        if(stage<0||stage>=stages||depth==stackStage.length)throw new IllegalStateException("Invalid frame timing scope");
        int token=depth++;stackStage[token]=stage;stackStart[token]=clock.getAsLong();stackChildren[token]=0;return token;
    }
    public void end(int token) {
        if(token<0||depth!=token+1)throw new IllegalStateException("Unbalanced frame timing scopes");
        long elapsed=clock.getAsLong()-stackStart[token];int stage=stackStage[token];depth--;
        inclusive[stage]+=elapsed;exclusive[stage]+=elapsed-stackChildren[token];calls[stage]++;
        if(depth>0)stackChildren[depth-1]+=elapsed;
    }
    /** END-to-END intervals also include owner packet/task/tick work before rendering begins. */
    public void finishFrame(long wallMillis,long target,int state) {
        if(depth!=0)throw new IllegalStateException("Frame ended inside a measured scope");
        long now=clock.getAsLong();
        if(initialized){
            int slot=(int)(sequence%capacity),base=slot*stages;
            endWall[slot]=wallMillis;durations[slot]=now-lastEnd;targets[slot]=target;states[slot]=state;
            System.arraycopy(inclusive,0,storedInclusive,base,stages);System.arraycopy(exclusive,0,storedExclusive,base,stages);System.arraycopy(calls,0,storedCalls,base,stages);
            sequence++;size=Math.min(size+1,capacity);
        }
        initialized=true;lastEnd=now;clearCurrent();
    }
    public List<Frame> snapshot(long afterSequence) {
        var result=new ArrayList<Frame>(size);
        for(long n=Math.max(sequence-size,afterSequence);n<sequence;n++){
            int slot=(int)(n%capacity),base=slot*stages;
            result.add(new Frame(n+1,endWall[slot],durations[slot],targets[slot],states[slot],
                    Arrays.copyOfRange(storedInclusive,base,base+stages),Arrays.copyOfRange(storedExclusive,base,base+stages),Arrays.copyOfRange(storedCalls,base,base+stages)));
        }
        return List.copyOf(result);
    }
    public long sequence(){return sequence;}
    public long oldestSequence(){return size==0?sequence+1:sequence-size+1;}
    public void reset(){
        if(depth!=0)throw new IllegalStateException("Reset inside a measured scope");
        initialized=false;size=0;sequence=0;clearCurrent();
    }
    private void clearCurrent(){Arrays.fill(inclusive,0);Arrays.fill(exclusive,0);Arrays.fill(calls,0);}
}
