package dev.madeinabyss.kernel.lod;

import java.lang.reflect.*;
import java.util.*;

/** HotSpot compressed-oops layout estimate for independent retained-graph assertions.
 * Benchmark instrumentation separately checks these estimates with getObjectSize. */
public final class T13RetainedGraph {
    private T13RetainedGraph() {}
    public static long bytes(Object root) {
        var seen = new IdentityHashMap<Object,Boolean>();
        var pending = new ArrayDeque<Object>(); pending.add(root); long bytes=0;
        while(!pending.isEmpty()) {
            var value=pending.removeLast(); if(seen.put(value,true)!=null) continue;
            var type=value.getClass(); if(type.isEnum()||value instanceof Class<?>||value instanceof Thread||value instanceof java.lang.ref.Reference<?>) continue;
            if(type.getName().startsWith("java.util.")&&value instanceof Map<?,?> map){
                bytes+=64+align(16+8L*map.size())+40L*map.size();
                for(var entry:map.entrySet()){if(entry.getKey()!=null)pending.add(entry.getKey());if(entry.getValue()!=null)pending.add(entry.getValue());}continue;
            }
            if(type.getName().startsWith("java.util.")&&value instanceof Collection<?> collection){
                boolean inline=type.getName().equals("java.util.ImmutableCollections$List12");
                bytes+=24+(inline?0:align(16+4L*collection.size()));for(var child:collection)if(child!=null)pending.add(child);continue;
            }
            if(type.isArray()) {
                int size=Array.getLength(value); var component=type.getComponentType();
                bytes+=align(16L+(long)size*(component.isPrimitive()?width(component):4));
                if(!component.isPrimitive()) for(int i=0;i<size;i++) {var child=Array.get(value,i);if(child!=null)pending.add(child);}
            } else {
                long size=12;
                for(var c=type;c!=null;c=c.getSuperclass())for(var field:c.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())) {
                    size+=field.getType().isPrimitive()?width(field.getType()):4;
                    if(!field.getType().isPrimitive())try {
                        field.setAccessible(true);var child=field.get(value);if(child!=null)pending.add(child);
                    }catch(ReflectiveOperationException e){throw new AssertionError(e);}
                }
                bytes+=align(size);
            }
        }
        return bytes;
    }
    private static long align(long n){return (n+7)&~7L;}
    private static int width(Class<?> c){return c==boolean.class||c==byte.class?1:c==short.class||c==char.class?2:c==int.class||c==float.class?4:8;}
}
