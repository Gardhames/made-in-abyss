package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.kernel.terrain.living.*;
import java.lang.reflect.Array;
import java.util.*;

/** Binds the frozen canonical plan to a derived-only exact evaluator. Descriptors are
 * copied; the canonical class/constructor, blocks and persisted fingerprint are untouched.
 * Every unsupported profile/implementation refuses this optional acceleration. */
public final class Generated155ExactView {
    private static final String FINGERPRINT="fc361b11eb1d175bee064e4bf44eaefe0f0e83e9607e7ed9a5983e6c03136a6d";
    private static final String ALGORITHM="9e903c73b7e2a22ff7b1f2fb8495a348913d00edae5cb018fb2021d7b10fbc82";
    private final Exact155ShapeView shape;
    private Generated155ExactView(Exact155ShapeView shape){this.shape=shape;}
    public static Generated155ExactView create(GeneratedPreviewProfile profile){
        if(profile==null||!FINGERPRINT.equals(profile.fingerprint())||!ALGORITHM.equals(LivingAbyssGeneration.algorithmSha256()))return null;
        var living=profile.generator().livingAbyss();if(living==null||living.revision155()==null)return null;
        var original=living.revision155();
        try{
            var gson=new GsonBuilder().addSerializationExclusionStrategy(new ExclusionStrategy(){
                @Override public boolean shouldSkipField(FieldAttributes f){return f.getDeclaringClass()==LivingAbyssRevision155.class&&Set.of("columns","strata","recipe","hallIndex","ledgeIndex","speleoIndex").contains(f.getName());}
                @Override public boolean shouldSkipClass(Class<?> type){return false;}
            }).create();
            var copy=gson.fromJson(gson.toJson(original),Exact155ShapeView.class);
            for(String name:List.of("strata","recipe")){var field=Exact155ShapeView.class.getDeclaredField(name);field.setAccessible(true);field.set(copy,read(original,name));}
            var columns=Exact155ShapeView.class.getDeclaredField("columns");columns.setAccessible(true);
            columns.set(copy,Array.newInstance(columns.getType().componentType(),Array.getLength(read(original,"columns"))));
            // JSON is only a value bridge between the two descriptor types. Rebuild indexes
            // with the canonical sharing topology; duplicating every Hall per angular bucket
            // would expand the working set and defeat the exact evaluator's locality.
            var halls=new IdentityHashMap<LivingAbyssRevision155.Hall,Exact155ShapeView.Hall>();
            for(int i=0;i<original.halls().size();i++)halls.put(original.halls().get(i),copy.halls().get(i));
            var hi=(LivingAbyssRevision155.Hall[][])read(original,"hallIndex");var nh=new Exact155ShapeView.Hall[hi.length][];
            for(int i=0;i<hi.length;i++){nh[i]=new Exact155ShapeView.Hall[hi[i].length];for(int j=0;j<hi[i].length;j++)nh[i][j]=Objects.requireNonNull(halls.get(hi[i][j]));}write(copy,"hallIndex",nh);
            var ledges=new IdentityHashMap<LivingAbyssRevision155.Ledge,Exact155ShapeView.Ledge>();
            for(int i=0;i<original.ledges().size();i++)ledges.put(original.ledges().get(i),copy.ledges().get(i));
            var li=(LivingAbyssRevision155.Ledge[][])read(original,"ledgeIndex");var nl=new Exact155ShapeView.Ledge[li.length][];
            for(int i=0;i<li.length;i++){nl[i]=new Exact155ShapeView.Ledge[li[i].length];for(int j=0;j<li[i].length;j++)nl[i][j]=Objects.requireNonNull(ledges.get(li[i][j]));}write(copy,"ledgeIndex",nl);
            var speleo=new IdentityHashMap<LivingAbyssRevision155.Speleo,Exact155ShapeView.Speleo>();
            for(int i=0;i<original.speleothems().size();i++)speleo.put(original.speleothems().get(i),copy.speleothems().get(i));
            @SuppressWarnings("unchecked") var si=(Map<Long,LivingAbyssRevision155.Speleo[]>)read(original,"speleoIndex");var ns=new HashMap<Long,Exact155ShapeView.Speleo[]>();
            for(var entry:si.entrySet()){var old=entry.getValue();var next=new Exact155ShapeView.Speleo[old.length];for(int i=0;i<old.length;i++)next[i]=Objects.requireNonNull(speleo.get(old[i]));ns.put(entry.getKey(),next);}write(copy,"speleoIndex",Map.copyOf(ns));
            // Refuse before workers can observe a malformed descriptor graph.
            for(int[] p:new int[][]{{0,-100,0},{511,-580,127},{-511,-950,-127},{400,-1350,-400}})
                if(copy.sample(p[0],p[1],p[2])!=original.sample(p[0],p[1],p[2]))return null;
            return new Generated155ExactView(copy);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError refused){return null;}
    }
    private static Object read(Object source,String name)throws ReflectiveOperationException{var field=source.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(source);}
    private static void write(Object target,String name,Object value)throws ReflectiveOperationException{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    public int sample(int x,int y,int z){return shape.sample(x,y,z);}
    public static Map<String,Long> liveThreadStats(){return Exact155Math.liveThreadStats();}
    public static String codeIdentity(){return Code.VALUE;}
    private static final class Code {
        static final String VALUE=identity();
        private static String identity(){var types=new LinkedHashSet<Class<?>>();types.addAll(Arrays.asList(Generated155ExactView.class.getNestMembers()));
            types.addAll(Arrays.asList(Exact155ShapeView.class.getNestMembers()));types.addAll(Arrays.asList(Exact155Math.class.getNestMembers()));
            return dev.madeinabyss.kernel.lod.TerrainLodDiskCache.codeIdentity(types.toArray(Class<?>[]::new));}
    }
}
