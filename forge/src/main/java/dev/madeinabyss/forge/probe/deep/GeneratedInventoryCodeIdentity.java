package dev.madeinabyss.forge.probe.deep;

import dev.madeinabyss.forge.worldgen.ParameterizedTerrainChunkGenerator;
import dev.madeinabyss.kernel.cube.CubeKey;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.JarFile;

/** Conservative actual project code fingerprint, including javac anonymous/switch classes.
 * Enumerating both Forge and kernel code sources avoids omissions as generator dependencies grow. */
final class GeneratedInventoryCodeIdentity {
    private GeneratedInventoryCodeIdentity() { }
    private static volatile String identity;
    static synchronized String get(){if(identity==null)identity=compute();return identity;}
    private static String compute() {
        try {
            var names=new TreeSet<String>();
            for(var root:List.of(ParameterizedTerrainChunkGenerator.class,CubeKey.class)) {
                var location=Path.of(root.getProtectionDomain().getCodeSource().getLocation().toURI());
                if(Files.isDirectory(location)) {
                    var packageRoot=location.resolve("dev/madeinabyss");
                    try(var paths=Files.walk(packageRoot)) {
                        var iterator=paths.iterator();while(iterator.hasNext()){
                            var file=iterator.next();if(Files.isRegularFile(file)&&file.toString().endsWith(".class"))
                                add(names,location.relativize(file).toString().replace('\\','/'));
                        }
                    }
                }else try(var jar=new JarFile(location.toFile())) {
                    var entries=jar.entries();while(entries.hasMoreElements()){
                        String name=entries.nextElement().getName();
                        if(name.startsWith("dev/madeinabyss/")&&name.endsWith(".class"))add(names,name);
                    }
                }
            }
            if(names.isEmpty())throw new IOException("No actual project class resources");
            var digest=MessageDigest.getInstance("SHA-256");long total=0;
            try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){
                for(String name:names)try(var input=ParameterizedTerrainChunkGenerator.class.getResourceAsStream("/"+name)){
                    if(input==null)throw new IOException("Missing actual class resource: "+name);
                    byte[] body=input.readNBytes(8*1024*1024+1);total+=body.length;
                    if(body.length>8*1024*1024||total>64L*1024*1024)throw new IOException("Code identity resource bound");
                    out.writeUTF(name);out.writeInt(body.length);out.write(body);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(Exception failure){throw new IllegalStateException("Cannot fingerprint actual generator dependency resources",failure);}
    }
    private static void add(Set<String> names,String name)throws IOException{
        names.add(name);if(names.size()>16384)throw new IOException("Code identity class count bound");
    }
}
