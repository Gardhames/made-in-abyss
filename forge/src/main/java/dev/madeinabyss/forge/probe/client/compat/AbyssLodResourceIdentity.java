package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.kernel.lod.TerrainLodDiskCache;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;

/** Diagnostic full-pack byte identity. Not an authority for physical original32 lighting. */
public final class AbyssLodResourceIdentity {
    private AbyssLodResourceIdentity() { }
    public static final class UnavailableException extends IllegalStateException {
        UnavailableException(IOException cause){super("Persistent resource authority unavailable: "+cause.getMessage(),cause);}
    }
    record Captured(String id,Supplier<PackResources> open) {
        Captured {Objects.requireNonNull(id);Objects.requireNonNull(open);}
    }
    /** Owner-thread operation: captures immutable descriptors only, with no resource enumeration or IO. */
    public static Snapshot capture(Collection<Pack> selectedPacks) {
        return new Snapshot(selectedPacks.stream().map(pack->new Captured(pack.getId(),pack::open)).toList());
    }
    /** Capture again after each successful resource reload; repeated worker requests share one digest. */
    public static final class Snapshot implements Supplier<String> {
        private final List<Captured> packs;
        private volatile String identity;
        Snapshot(List<Captured> packs){this.packs=List.copyOf(packs);}
        public String cachedIdentity(){return identity;}
        /** Worker-only. A failed or canceled read never publishes a usable cache identity. */
        @Override public String get() {
            String current=identity;if(current!=null)return current;
            synchronized(this){
                if(identity!=null)return identity;
                try {String completed=compute();checkCanceled();identity=completed;return completed;}
                catch(IOException unavailable){checkCanceled();throw new UnavailableException(unavailable);}
                catch(java.util.concurrent.CancellationException canceled){throw canceled;}
                catch(RuntimeException unavailable){throw new UnavailableException(new IOException("Active resource pack cannot provide a complete content snapshot",unavailable));}
            }
        }
        private String compute()throws IOException {
            var digest=sha();var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest));
            out.writeUTF("abyss-active-resource-content-v1");out.writeUTF(Code.VALUE);out.writeInt(packs.size());
            byte[] buffer=new byte[64*1024];
            for(var descriptor:packs){
                checkCanceled();out.writeUTF(descriptor.id());
                String packClass="unopened";
                try(var pack=Objects.requireNonNull(descriptor.open().get(),"Pack supplier returned null")){
                    packClass=pack.getClass().getName();
                    out.writeUTF(pack.packId());
                    root(out,pack,"pack.mcmeta",buffer);root(out,pack,"pack.png",buffer);
                    var roots=new LinkedHashSet<Path>();collectRoots(pack,roots,new java.util.IdentityHashMap<>());
                    if(roots.isEmpty())throw new IOException("Unsupported active resource pack: "+pack.getClass().getName());
                    var orderedRoots=roots.stream().sorted(java.util.Comparator.comparing(p->p.toUri().toString())).toList();
                    var indexes=new java.util.HashMap<Path,Integer>();for(int i=0;i<orderedRoots.size();i++)indexes.put(orderedRoots.get(i),i);
                    writeTopology(out,pack,indexes,new java.util.IdentityHashMap<>());
                    out.writeInt(orderedRoots.size());for(var path:orderedRoots)writePath(out,path,buffer);
                }catch(java.util.concurrent.CancellationException canceled){throw canceled;}
                catch(IOException|RuntimeException unavailable){checkCanceled();throw new IOException("Pack '"+descriptor.id()+"' ("+packClass+"): "+unavailable,unavailable);}
            }
            out.flush();return HexFormat.of().formatHex(digest.digest());
        }
    }
    /** Keep pack precedence, namespace filters and vanilla root roles/list order distinct from file-set equality. */
    private static void writeTopology(DataOutputStream out,PackResources pack,java.util.Map<Path,Integer> indexes,
            java.util.IdentityHashMap<PackResources,Boolean> active)throws IOException {
        checkCanceled();if(active.put(pack,true)!=null)throw new IOException("Cyclic resource pack hierarchy");
        out.writeUTF(pack.getClass().getName());out.writeUTF(pack.packId());
        for(var type:net.minecraft.server.packs.PackType.values()){
            out.writeUTF(type.name());var namespaces=new java.util.TreeSet<>(pack.getNamespaces(type));out.writeInt(namespaces.size());
            for(var namespace:namespaces)out.writeUTF(namespace);
        }
        if(pack.getClass()==net.minecraft.server.packs.VanillaPackResources.class){
            for(var field:pack.getClass().getDeclaredFields()){
                if(Modifier.isStatic(field.getModifiers()))continue;
                if(!Collection.class.isAssignableFrom(field.getType())&&!java.util.Map.class.isAssignableFrom(field.getType()))continue;
                try{
                    if(!field.trySetAccessible())throw new IOException("Cannot inspect vanilla resource precedence");
                    var value=field.get(pack);if(!hasPath(value))continue;
                    out.writeUTF(field.getName());writeRootShape(out,value,indexes);
                }catch(IllegalAccessException unavailable){throw new IOException("Cannot inspect vanilla resource precedence",unavailable);}
            }
        }
        out.writeUTF("end-root-roles");var children=pack.getChildren();out.writeInt(children==null?0:children.size());
        if(children!=null)for(var child:children)writeTopology(out,child,indexes,active);
        active.remove(pack);
    }
    private static boolean hasPath(Object value){
        if(value instanceof Path||value instanceof java.io.File)return true;
        if(value instanceof Collection<?> children)return children.stream().anyMatch(AbyssLodResourceIdentity::hasPath);
        if(value instanceof java.util.Map<?,?> children)return children.values().stream().anyMatch(AbyssLodResourceIdentity::hasPath);
        return false;
    }
    private static void writeRootShape(DataOutputStream out,Object value,java.util.Map<Path,Integer> indexes)throws IOException {
        if(value instanceof java.io.File file)value=file.toPath();
        if(value instanceof Path path){out.writeByte(1);var index=indexes.get(path);if(index==null)throw new IOException("Unknown resource precedence root");out.writeInt(index);}
        else if(value instanceof java.util.Map<?,?> values){
            out.writeByte(2);var sorted=new java.util.TreeMap<String,Object>();for(var entry:values.entrySet())sorted.put(entry.getKey().toString(),entry.getValue());
            out.writeInt(sorted.size());for(var entry:sorted.entrySet()){out.writeUTF(entry.getKey());writeRootShape(out,entry.getValue(),indexes);}
        }else if(value instanceof Collection<?> values){
            out.writeByte(3);out.writeInt(values.size());
            for(var child:values)writeRootShape(out,child,indexes); // Vanilla root collections are ordered Lists.
        }else throw new IOException("Unknown resource precedence shape");
    }
    /** 1.20.1 rejects listResources(..., ""): inspect verified roots rather than hash a false empty manifest. */
    private static void collectRoots(PackResources pack,LinkedHashSet<Path> roots,java.util.IdentityHashMap<PackResources,Boolean> seen)throws IOException {
        if(seen.put(pack,true)!=null)return;
        var children=pack.getChildren();if(children!=null)for(var child:children)collectRoots(child,roots,seen);
        if(pack.getClass()==net.minecraftforge.resource.PathPackResources.class){roots.add(((net.minecraftforge.resource.PathPackResources)pack).getSource());return;}
        // Forge47.4.20 uses this anonymous subclass for every mod and resolves through IModFile's
        // union root (dev classes/resources may live in separate directories). Its primary file
        // path alone does not prove the complete resource contents.
        if(pack.getClass().getName().equals("net.minecraftforge.resource.ResourcePackLoader$1")){
            for(var field:pack.getClass().getDeclaredFields())if(net.minecraftforge.forgespi.locating.IModFile.class.isAssignableFrom(field.getType())){
                try{
                    if(!field.trySetAccessible())throw new IOException("Cannot inspect Forge mod resource root");
                    // Real ModFile rejects zero varargs with "Missing path". The empty
                    // logical path is the explicit root and retains the complete union.
                    roots.add(((net.minecraftforge.forgespi.locating.IModFile)field.get(pack)).findResource(""));return;
                }catch(IllegalAccessException inaccessible){throw new IOException("Cannot inspect Forge mod resource root",inaccessible);}
            }
            throw new IOException("Verified Forge mod pack has no authoritative IModFile root");
        }
        if(pack.getClass()!=net.minecraft.server.packs.PathPackResources.class
                &&pack.getClass()!=net.minecraft.server.packs.FilePackResources.class
                &&pack.getClass()!=net.minecraft.server.packs.VanillaPackResources.class){
            if(pack.getClass()!=net.minecraftforge.resource.DelegatingPackResources.class||children==null||children.isEmpty())
                throw new IOException("Unsupported active resource pack: "+pack.getClass().getName());
            return;
        }
        for(Class<?> type=pack.getClass();type!=Object.class;type=type.getSuperclass())for(var field:type.getDeclaredFields()){
            if(Modifier.isStatic(field.getModifiers()))continue;
            var kind=field.getType();
            if(!Path.class.isAssignableFrom(kind)&&!java.io.File.class.isAssignableFrom(kind)
                    &&!Collection.class.isAssignableFrom(kind)&&!java.util.Map.class.isAssignableFrom(kind))continue;
            try{
                if(!field.trySetAccessible())throw new IOException("Cannot inspect supported resource root type "+type.getName());
                collectPaths(field.get(pack),roots);
            }catch(IllegalAccessException inaccessible){throw new IOException("Cannot inspect supported resource pack root",inaccessible);}
        }
    }
    private static void collectPaths(Object value,LinkedHashSet<Path> paths){
        if(value instanceof Path path)paths.add(path);
        else if(value instanceof java.io.File file)paths.add(file.toPath());
        else if(value instanceof Collection<?> values)for(var child:values)collectPaths(child,paths);
        else if(value instanceof java.util.Map<?,?> values)for(var child:values.values())collectPaths(child,paths);
    }
    private static void writePath(DataOutputStream out,Path root,byte[] buffer)throws IOException {
        checkCanceled();if(Files.isSymbolicLink(root))throw new IOException("Symbolic resource root requires explicit authority: "+root);
        if(Files.isRegularFile(root)){out.writeByte(1);out.write(content(()->Files.newInputStream(root),buffer));return;}
        if(!Files.isDirectory(root))throw new IOException("Active resource root unavailable: "+root);
        out.writeByte(2);var files=new java.util.TreeMap<String,Path>();
        try(var paths=Files.walk(root)){
            var iterator=paths.iterator();while(iterator.hasNext()){
                checkCanceled();var path=iterator.next();
                if(Files.isSymbolicLink(path))throw new IOException("Symbolic resource entry requires explicit authority: "+path);
                if(Files.isRegularFile(path)){
                    if(files.size()>=1_000_000)throw new IOException("Resource authority exceeds bounded entry metadata budget");
                    files.put(root.relativize(path).toString().replace('\\','/'),path);
                }
            }
        }
        out.writeInt(files.size());for(var entry:files.entrySet()){checkCanceled();out.writeUTF(entry.getKey());out.write(content(()->Files.newInputStream(entry.getValue()),buffer));}
    }
    private static void root(DataOutputStream out,PackResources pack,String name,byte[] buffer)throws IOException {
        out.writeUTF(name);var input=pack.getRootResource(name);out.writeBoolean(input!=null);if(input!=null)out.write(content(input,buffer));
    }
    private static byte[] content(IoSupplier<InputStream> source,byte[] buffer)throws IOException {
        var digest=sha();try(var input=source.get()){
            for(int count;(count=input.read(buffer))!=-1;){checkCanceled();if(count>0)digest.update(buffer,0,count);}
        }return digest.digest();
    }
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void checkCanceled(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Resource authority canceled");}
    private static final class Code {static final String VALUE=TerrainLodDiskCache.codeIdentity(AbyssLodResourceIdentity.class,Snapshot.class);}
}
