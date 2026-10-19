package dev.madeinabyss.kernel.lod;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfacePageLeasesTest {
    private static SurfacePageLeases.Scope scope(Object model){return new SurfacePageLeases.Scope("session",2,"saved-source",3,4,5,6,model);}
    @Test void displayedAndCandidateShareOnePageUntilExactlyTheLastRetirement(){
        var leases=new SurfacePageLeases<Object,Object>(4);var prepared=new Object();var model=new Object();var factories=new AtomicInteger();var disposals=new AtomicInteger();
        var displayed=leases.acquire(scope(model),prepared,()->{factories.incrementAndGet();return new Object();},g->disposals.incrementAndGet());
        var candidate=leases.acquire(scope(model),prepared,()->{factories.incrementAndGet();return new Object();},g->disposals.incrementAndGet());
        assertEquals(1,factories.get(),"Unchanged immutable page must not upload/create a second GPU page");assertSame(displayed.value(),candidate.value());
        candidate.close();candidate.close();assertEquals(0,disposals.get(),"Cancellation must retain the displayed shared VAO");assertEquals(1,leases.size());
        var replacement=leases.acquire(scope(model),prepared,Object::new,g->disposals.incrementAndGet());displayed.close();
        assertEquals(0,disposals.get(),"Retiring the old scene cannot close the new scene's shared page");
        replacement.close();replacement.close();assertEquals(1,disposals.get());assertEquals(0,leases.size());
    }
    @Test void readyUploadProofCarriesAcrossAnEquivalentTargetButNotANewPreparedIdentity(){
        var leases=new SurfacePageLeases<Object,Object>(4);var model=new Object();var prepared=new Object();
        var first=leases.acquire(scope(model),prepared,Object::new,g->{});assertFalse(first.ready());first.markReady();
        var equivalent=leases.acquire(scope(model),prepared,Object::new,g->{});assertTrue(equivalent.ready(),"GPU-ready immutable page must be reused without another hidden upload");
        var changed=leases.acquire(scope(model),new Object(),Object::new,g->{});assertFalse(changed.ready());
        first.close();equivalent.close();changed.close();assertEquals(0,leases.size());
    }
    @Test void everyAuthorityAndResourceDimensionInvalidatesEvenTheSamePreparedReference(){
        var leases=new SurfacePageLeases<Object,Object>(16);var prepared=new Object();var model=new Object();var baseline=scope(model);
        var a=leases.acquire(baseline,prepared,Object::new,g->{});
        var variants=java.util.List.of(new SurfacePageLeases.Scope("other",2,"saved-source",3,4,5,6,model),
            new SurfacePageLeases.Scope("session",9,"saved-source",3,4,5,6,model),new SurfacePageLeases.Scope("session",2,"changed-source",3,4,5,6,model),
            new SurfacePageLeases.Scope("session",2,"saved-source",9,4,5,6,model),new SurfacePageLeases.Scope("session",2,"saved-source",3,9,5,6,model),
            new SurfacePageLeases.Scope("session",2,"saved-source",3,4,9,6,model),new SurfacePageLeases.Scope("session",2,"saved-source",3,4,5,9,model),
            new SurfacePageLeases.Scope("session",2,"saved-source",3,4,5,6,new Object()));
        for(var variant:variants){var b=leases.acquire(variant,prepared,Object::new,g->{});assertNotSame(a.value(),b.value());b.close();}a.close();assertEquals(0,leases.size());
    }
    @Test void capRefusesBeforeFactoryAndPreparedPayloadHashOrEqualityAreNeverEvaluated(){
        Object prepared=new Object(){@Override public int hashCode(){throw new AssertionError("Full RAW hash on owner");}@Override public boolean equals(Object other){throw new AssertionError("Full RAW equality on owner");}};
        var leases=new SurfacePageLeases<Object,Object>(1);var s=scope(new Object());var disposals=new AtomicInteger();
        var a=leases.acquire(s,prepared,Object::new,g->disposals.incrementAndGet());
        assertThrows(IllegalStateException.class,()->leases.acquire(s,new Object(),()->{throw new AssertionError("Factory beyond cap");},g->{}));
        a.close();a.close();assertEquals(1,disposals.get());assertEquals(0,leases.size());assertThrows(IllegalStateException.class,a::value);
    }
}
