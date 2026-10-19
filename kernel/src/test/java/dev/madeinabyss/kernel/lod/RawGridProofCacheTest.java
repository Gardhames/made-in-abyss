package dev.madeinabyss.kernel.lod;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import dev.madeinabyss.kernel.terrain.TerrainSample;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RawGridProofCacheTest {
    private static final TerrainLodColumns.Bounds BOUNDS=new TerrainLodColumns.Bounds(-16,-64,0,-16,-64,0);
    private static AbyssLodFineLedger.Grid grid(String source){
        var raw=TerrainLodColumns.build((x,y,z)->new TerrainSample.Known(TerrainMaterial.ROCK),BOUNDS,new TerrainLodColumns.Parameters(1,1,1));
        return new AbyssLodFineLedger.Grid(source,BOUNDS,1,raw);
    }
    @Test void exactLegacyShaRemainsCompatibleWithRecordedRawProof(){
        var cache=new RawGridProofCache(2);
        assertEquals("ec91feeff90e69f2739baba15b48b85eeed6c56b096e9b56af686d4c783be787",cache.proof(grid("source-a")),
                "Original ordered source/bounds/step and Box.toString SHA must remain byte-exact");
    }
    @Test void newGridWrapperReusesSameImmutableRawWithoutDigestWork(){
        var cache=new RawGridProofCache(2);var first=grid("source-a");String sha=cache.proof(first);
        var wrapper=new AbyssLodFineLedger.Grid(first.sourceIdentity(),first.bounds(),first.step(),first.raw());
        try{assertEquals(sha,cache.proof(wrapper));assertEquals(1,cache.digestBuilds(),"Unchanged raw identity must not traverse/re-hash its ordered boxes again");
            assertEquals(1,cache.residentEntries());assertEquals(1,cache.hits());}
        finally{java.lang.ref.Reference.reachabilityFence(wrapper);}
    }
    @Test void authoritySourceBoundsAndStepNeverAliasEvenWithSameRawIdentity(){
        var cache=new RawGridProofCache(8);var first=grid("source-a");String original=cache.proof(first);
        assertNotEquals(original,cache.proof(new AbyssLodFineLedger.Grid("source-b",BOUNDS,1,first.raw())));
        assertNotEquals(original,cache.proof(new AbyssLodFineLedger.Grid("source-a",BOUNDS,2,first.raw())));
        var air=new TerrainLodColumns.Result(List.of(),1);
        String left=cache.proof(new AbyssLodFineLedger.Grid("source-a",BOUNDS,1,air));
        assertNotEquals(left,cache.proof(new AbyssLodFineLedger.Grid("source-a",new TerrainLodColumns.Bounds(-15,-64,0,-15,-64,0),1,air)));
        assertEquals(5,cache.digestBuilds());
    }
    @Test void equalContentDistinctRawObjectDoesNotInvokeStructuralLookupReuse(){
        var cache=new RawGridProofCache(2);var first=grid("source-a");String original=cache.proof(first);
        var distinct=new TerrainLodColumns.Result(first.raw().boxes(),first.raw().sampledVoxels());
        assertNotSame(first.raw(),distinct);assertEquals(first.raw(),distinct);
        assertEquals(original,cache.proof(new AbyssLodFineLedger.Grid(first.sourceIdentity(),BOUNDS,1,distinct)));
        assertEquals(2,cache.digestBuilds(),"Structural Result/list equality must not masquerade as raw identity");
    }
    @Test void capEvictionRetainsBoundedEntriesAndRebuildsOnlyEvictedProof(){
        var cache=new RawGridProofCache(2);var a=grid("a");var b=grid("b");var c=grid("c");
        var retained=List.of(a,b,c);
        try{cache.proof(a);cache.proof(b);cache.proof(c);assertEquals(2,cache.residentEntries());
            assertEquals(1,cache.evictions());long built=cache.digestBuilds();cache.proof(c);assertEquals(built,cache.digestBuilds());
            cache.proof(a);assertEquals(built+1,cache.digestBuilds());assertEquals(2,cache.residentEntries());
            cache.clear();assertEquals(0,cache.residentEntries());assertThrows(IllegalArgumentException.class,()->new RawGridProofCache(0));
            assertThrows(IllegalArgumentException.class,()->new RawGridProofCache(32769));}
        finally{java.lang.ref.Reference.reachabilityFence(retained);}
    }
    @Test void cancellationCannotPublishAnEmptyRawProofOrConsumeItsDigestCounter(){
        var cache=new RawGridProofCache(2);
        var empty=new AbyssLodFineLedger.Grid("source-a",BOUNDS,1,new TerrainLodColumns.Result(List.of(),1));
        try{Thread.currentThread().interrupt();assertThrows(java.util.concurrent.CancellationException.class,()->cache.proof(empty));}
        finally{Thread.interrupted();}
        assertEquals(0,cache.residentEntries());assertEquals(0,cache.digestBuilds());
        assertEquals(64,cache.proof(empty).length());assertEquals(1,cache.digestBuilds());
    }
}
