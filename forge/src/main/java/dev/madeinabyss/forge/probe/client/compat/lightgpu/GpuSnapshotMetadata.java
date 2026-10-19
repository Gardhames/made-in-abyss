package dev.madeinabyss.forge.probe.client.compat.lightgpu;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Immutable, checked cache authority. Camera changes are irrelevant; edits and physical reloads are not. */
public final class GpuSnapshotMetadata {
    public static final String SCHEMA="mia-gpu-light-client-v1";
    private static final String[] FILES={"snapshot.properties","directory.words","roots.words","opacity.words","halo-meta.words","halo.words"};
    private final Properties authority;
    private GpuSnapshotMetadata(Properties authority){this.authority=authority;}
    public static GpuSnapshotMetadata load(Path directory)throws IOException{
        var p=new Properties();try(var in=Files.newInputStream(directory.resolve("client.properties"))){p.load(in);}
        if(!SCHEMA.equals(p.getProperty("schema")))throw new IOException("GPU snapshot schema mismatch");
        for(String key:new String[]{"fingerprint","generationSha","physicalIdentity","sourceIdentity","producerCode","packedCode"}){
            if(!p.getProperty(key,"").matches("[0-9a-f]{64}"))throw new IOException("Missing GPU authority "+key);
        }
        try{
            var digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[4<<20];
            for(String name:FILES){digest.reset();try(var in=Files.newInputStream(directory.resolve(name))){int n;while((n=in.read(bytes))>=0)digest.update(bytes,0,n);}
                if(!HexFormat.of().formatHex(digest.digest()).equals(p.getProperty("sha256."+name)))throw new IOException("GPU snapshot checksum mismatch: "+name);
            }
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        var scene=new Properties();try(var in=Files.newInputStream(directory.resolve("snapshot.properties"))){scene.load(in);}
        if(!"true".equals(scene.getProperty("closed"))||!p.getProperty("fingerprint").equals(scene.getProperty("fingerprint"))||!p.getProperty("generationSha").equals(scene.getProperty("generationSha")))throw new IOException("GPU scene authority mismatch");
        return new GpuSnapshotMetadata(p);
    }
    public void require(String fingerprint,String generationSha,String physicalIdentity,String sourceIdentity,String producerCode,String packedCode){
        String[] keys={"fingerprint","generationSha","physicalIdentity","sourceIdentity","producerCode","packedCode"};String[] values={fingerprint,generationSha,physicalIdentity,sourceIdentity,producerCode,packedCode};
        for(int i=0;i<keys.length;i++)if(!authority.getProperty(keys[i]).equals(values[i]))throw new IllegalStateException("GPU snapshot authority mismatch: "+keys[i]);
    }
}
