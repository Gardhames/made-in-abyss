package dev.madeinabyss.kernel.cube;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Owner-thread immutable snapshots. Retain authoritative edits, not a history of exploration.
 * Arrays belong to immutable captured windows; callers must not mutate them after publication.
 * A previous observation absent from retained was already verified against this generator.
 */
public final class GeneratedCubeRetention {
    private GeneratedCubeRetention() { }
    public record Result(Map<CubeKey,int[]> retained,boolean geometryChanged,long pristineComparisons) { }
    public static Result merge(Map<CubeKey,int[]> retained,Map<CubeKey,int[]> incoming,
                               Map<CubeKey,int[]> previous,Function<CubeKey,int[]> procedural,int limit) {
        if(limit<0||retained.size()>limit)throw new IllegalArgumentException("Retained saved geometry exceeds bound");
        var next=new HashMap<>(retained);boolean changed=false;long pristine=0;
        for(var entry:incoming.entrySet()) {
            var key=entry.getKey();var value=entry.getValue();var old=next.get(key);
            if(old!=null) {
                if(!Arrays.equals(old,value)){next.put(key,value);changed=true;}
            } else if(!Arrays.equals(previous.get(key),value)) {
                if(Arrays.equals(procedural.apply(key),value))pristine++;
                else {next.put(key,value);changed=true;}
            }
        }
        if(next.size()>limit)throw new IllegalArgumentException("Retained saved geometry exceeds bound");
        return new Result(Collections.unmodifiableMap(next),changed,pristine);
    }
}
