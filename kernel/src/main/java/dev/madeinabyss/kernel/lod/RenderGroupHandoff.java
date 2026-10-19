package dev.madeinabyss.kernel.lod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns at most a displayed group and one uploading replacement; rendering callbacks only mark readiness. */
public final class RenderGroupHandoff<T> {
    private T displayed, staged;
    private long target;
    private boolean drawn;

    public synchronized T beginTarget(long version) {
        target=version;drawn=false;
        T retired=staged;staged=null;
        return retired;
    }
    public synchronized void stage(long version,T candidate) {
        if(version!=target || staged!=null)throw new IllegalStateException("Stale or duplicate render replacement");
        staged=Objects.requireNonNull(candidate);drawn=false;
    }
    public synchronized void markDrawn(long version,T candidate) {
        if(version==target && staged==candidate)drawn=true;
    }
    public synchronized boolean ready() {return staged!=null && drawn;}
    public synchronized T promote() {
        if(!ready())throw new IllegalStateException("Replacement has not drawn");
        T retired=displayed;displayed=staged;staged=null;drawn=false;return retired;
    }
    public synchronized T displayed() {return displayed;}
    public synchronized T staged() {return staged;}
    public synchronized int size() {return (displayed==null?0:1)+(staged==null?0:1);}
    public synchronized List<T> clear() {
        var retired=new ArrayList<T>();if(displayed!=null)retired.add(displayed);if(staged!=null)retired.add(staged);
        displayed=null;staged=null;drawn=false;++target;return List.copyOf(retired);
    }
}
