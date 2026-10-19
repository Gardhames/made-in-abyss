package dev.madeinabyss.forge.worldgen;

import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** GameTest utility: execute the compiled adapter's pure material path without loading its client host.
 * No instructions in material(), its tables or enum-switch initializers are replaced or simulated. */
final class HeadlessLodAdapterMaterials implements AutoCloseable {
    private final URLClassLoader loader;
    final MethodHandle material;

    HeadlessLodAdapterMaterials(String adapter) throws ReflectiveOperationException,IOException {
        String original="dev/madeinabyss/forge/probe/client/compat/"+adapter;
        String isolated=original+"$HeadlessPaletteProbe";
        var classes=new HashMap<String,byte[]>();
        classes.put(isolated.replace('/','.'),isolate(bytes(original),original,isolated,true));
        // javac's shared enum switch initializer also mentions DH API enums. Load the
        // already cached compile-only API, never the DH mod or its server lifecycle.
        for(int i=1;i<=32;i++){
            String child=original+"$"+i;byte[] data=bytes(child);
            if(data==null)continue;
            boolean[] enumSwitch={false};
            new ClassReader(data).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value){
                    if(name.startsWith("$SwitchMap$"))enumSwitch[0]=true;return null;
                }
            },ClassReader.SKIP_CODE);
            if(enumSwitch[0])classes.put((isolated+"$"+i).replace('/','.'),isolate(data,original,isolated,false));
        }
        var apiRoot=Path.of(System.getProperty("mia.storageRoot", ".local"),"gradle-user-home/caches/modules-2/files-2.1/maven.modrinth/DistantHorizonsApi/7.2.0");
        URL api;
        try(var paths=Files.walk(apiRoot)){
            api=paths.filter(p->p.toString().endsWith(".jar")).findFirst().orElseThrow().toUri().toURL();
        }
        loader=new URLClassLoader(new URL[]{api},HeadlessLodAdapterMaterials.class.getClassLoader()){
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                var data=classes.get(name);return data==null?super.findClass(name):defineClass(name,data,0,data.length);
            }
        };
        var type=Class.forName(isolated.replace('/','.'),true,loader);
        material=MethodHandles.privateLookupIn(type,MethodHandles.lookup())
                .findStatic(type,"material",MethodType.methodType(TerrainMaterial.class,BlockState.class));
    }
    private static byte[] bytes(String name) throws IOException {
        try(var stream=HeadlessLodAdapterMaterials.class.getResourceAsStream("/"+name+".class")){
            return stream==null?null:stream.readAllBytes();
        }
    }
    private static byte[] isolate(byte[] data,String original,String isolated,boolean root){
        var writer=new ClassWriter(0);
        var remapper=new ClassRemapper(writer,new Remapper(){
            @Override public String map(String name){return name.equals(original)||name.startsWith(original+"$")?isolated+name.substring(original.length()):name;}
        });
        new ClassReader(Objects.requireNonNull(data,"Missing adapter bytecode")).accept(new ClassVisitor(Opcodes.ASM9,remapper){
            @Override public void visit(int version,int access,String name,String signature,String superclass,String[] interfaces){
                super.visit(version,Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL|Opcodes.ACC_SUPER,name,null,"java/lang/Object",null);
            }
            @Override public FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value){
                return (access&Opcodes.ACC_STATIC)==0?null:super.visitField(access,name,descriptor,signature,value);
            }
            @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                return name.equals("<clinit>")||(root&&(name.equals("material")||name.startsWith("lambda$static$")))
                        ?super.visitMethod(access,name,descriptor,signature,exceptions):null;
            }
            @Override public void visitNestHost(String name){}
            @Override public void visitNestMember(String name){}
            @Override public void visitInnerClass(String name,String outer,String inner,int access){}
            @Override public AnnotationVisitor visitAnnotation(String descriptor,boolean visible){return null;}
        },0);
        return writer.toByteArray();
    }
    @Override public void close() throws IOException {loader.close();}
}
