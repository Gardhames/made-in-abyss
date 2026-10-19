package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodResourceIdentityGameTests {
    @GameTest(template="empty",timeoutTicks=200)
    public static void durableResourceHashReadsBytesOffCaptureAndNotFileTimestamps(GameTestHelper h)throws Exception {
        Path parent=Path.of(System.getProperty("mia.storageRoot", ".local"),"codex","20261013-lod-speed","stage-d","authority-tests");
        Files.createDirectories(parent);var root=Files.createTempDirectory(parent,"resource-");
        var resource=root.resolve("assets/mia/textures/test.bin");Files.createDirectories(resource.getParent());Files.write(resource,new byte[]{1,2,3,4});
        Files.writeString(root.resolve("pack.mcmeta"),"{\"pack\":{\"description\":\"one\",\"pack_format\":15}}");
        var opens=new AtomicInteger();var descriptor=new AbyssLodResourceIdentity.Captured("test-pack",()->{opens.incrementAndGet();return new PathPackResources("test-pack",root,false);});
        var captured=new AbyssLodResourceIdentity.Snapshot(List.of(descriptor));
        h.assertTrue(opens.get()==0&&captured.cachedIdentity()==null,"Owner capture performs neither pack opening nor resource scanning");
        String first=captured.get();h.assertTrue(opens.get()==1&&first.equals(captured.get())&&opens.get()==1,"Worker content digest is memoized only within one resource generation");
        var timestamp=Files.getLastModifiedTime(resource);Files.write(resource,new byte[]{4,3,2,1});Files.setLastModifiedTime(resource,timestamp);
        String edited=new AbyssLodResourceIdentity.Snapshot(List.of(descriptor)).get();
        h.assertTrue(!first.equals(edited),"Same size and same timestamp with changed bytes must invalidate persistent resources");
        h.assertTrue(first.equals(captured.get()),"Previously captured generation retains its immutable published identity");
        String restarted=new AbyssLodResourceIdentity.Snapshot(List.of(descriptor)).get();h.assertTrue(edited.equals(restarted),"Independent capture on restart has identical content authority");
        Files.writeString(root.resolve("pack.mcmeta"),"{\"pack\":{\"description\":\"two\",\"pack_format\":15}}");
        h.assertTrue(!edited.equals(new AbyssLodResourceIdentity.Snapshot(List.of(descriptor)).get()),"Pack root metadata participates in strict invalidation");
        var other=new AbyssLodResourceIdentity.Captured("other-pack",()->new PathPackResources("other-pack",root,false));
        var ordered=new AbyssLodResourceIdentity.Snapshot(List.of(descriptor,other)).get();
        var reversed=new AbyssLodResourceIdentity.Snapshot(List.of(other,descriptor)).get();
        h.assertTrue(!ordered.equals(reversed),"Selected pack order and IDs are durable authority, since precedence can change visible resources");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void resourceReadFailureClosesIndependentHandleAndPublishesNoIdentity(GameTestHelper h) {
        var closed=new AtomicInteger();var opens=new AtomicInteger();
        var descriptor=new AbyssLodResourceIdentity.Captured("failing",()->{
            opens.incrementAndGet();return new PackResources(){
                @Override public IoSupplier<InputStream> getRootResource(String... paths){return ()->{throw new IOException("injected resource read failure");};}
                @Override public IoSupplier<InputStream> getResource(PackType type,ResourceLocation id){return null;}
                @Override public void listResources(PackType type,String namespace,String path,ResourceOutput output){ }
                @Override public Set<String> getNamespaces(PackType type){return Set.of();}
                @Override public <T>T getMetadataSection(MetadataSectionSerializer<T> serializer){return null;}
                @Override public String packId(){return "failing";}
                @Override public void close(){closed.incrementAndGet();}
            };
        });
        var captured=new AbyssLodResourceIdentity.Snapshot(List.of(descriptor));
        for(int attempt=0;attempt<2;attempt++){
            boolean failed=false;try{captured.get();}catch(AbyssLodResourceIdentity.UnavailableException expected){failed=true;
                h.assertTrue(expected.getMessage().contains("Pack 'failing'")&&expected.getMessage().contains("injected resource read failure"),"Diagnostics preserve descriptor and nested failure instead of a generic disabled label");}
            h.assertTrue(failed&&captured.cachedIdentity()==null,"Failed bytes never turn into a reusable resource identity");
        }
        h.assertTrue(opens.get()==2&&closed.get()==2,"A retry uses a new private handle and both failure paths close it");
        Thread.currentThread().interrupt();boolean canceled=false;
        try{captured.get();}catch(java.util.concurrent.CancellationException expected){canceled=true;}finally{Thread.interrupted();}
        h.assertTrue(canceled&&opens.get()==2&&captured.cachedIdentity()==null,"Canceled worker never opens a pack or publishes a partial digest");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=200)
    public static void verifiedForgeModUnionAndVanillaRootsHashActualBytes(GameTestHelper h)throws Exception {
        Path parent=Path.of(System.getProperty("mia.storageRoot", ".local"),"codex","20261013-lod-speed","stage-d","authority-tests");
        Files.createDirectories(parent);var root=Files.createTempDirectory(parent,"wrapped-resource-");
        var asset=root.resolve("assets/mia/custom_folder/value.bin");Files.createDirectories(asset.getParent());Files.write(asset,new byte[]{1});
        var modFile=(net.minecraftforge.forgespi.locating.IModFile)java.lang.reflect.Proxy.newProxyInstance(getLoader(),new Class<?>[]{net.minecraftforge.forgespi.locating.IModFile.class},(proxy,method,args)->switch(method.getName()){
            case "getFileName"->"authority-fixture";
            case "getFilePath"->root.resolve("not-the-union-root.jar");
            case "findResource"->{var segments=(String[])args[0];if(segments.length==0)throw new IllegalArgumentException("Missing path");Path path=root;for(var segment:segments)path=path.resolve(segment);yield path;}
            default->throw new UnsupportedOperationException(method.getName());
        });
        var modInfo=(net.minecraftforge.forgespi.language.IModFileInfo)java.lang.reflect.Proxy.newProxyInstance(getLoader(),new Class<?>[]{net.minecraftforge.forgespi.language.IModFileInfo.class},(proxy,method,args)->{
            if(method.getName().equals("getFile"))return modFile;throw new UnsupportedOperationException(method.getName());
        });
        java.util.function.Supplier<PackResources> mod=()->net.minecraftforge.resource.ResourcePackLoader.createPackForMod(modInfo);
        java.util.function.Supplier<PackResources> delegated=()->new net.minecraftforge.resource.DelegatingPackResources("mods",true,
                new net.minecraft.server.packs.metadata.pack.PackMetadataSection(net.minecraft.network.chat.Component.literal("test"),15),List.of(mod.get()));
        java.util.function.Supplier<PackResources> vanilla=()->new net.minecraft.server.packs.VanillaPackResourcesBuilder().pushUniversalPath(root).exposeNamespace("mia").build();
        for(var factory:List.of(mod,delegated,vanilla)){
            var descriptor=new AbyssLodResourceIdentity.Captured("wrapped",factory);
            Files.write(asset,new byte[]{1});String first=new AbyssLodResourceIdentity.Snapshot(List.of(descriptor)).get();
            Files.write(asset,new byte[]{2});String next=new AbyssLodResourceIdentity.Snapshot(List.of(descriptor)).get();
            h.assertTrue(!first.equals(next),"Verified Forge mod union/delegation and vanilla roots include arbitrary resource folders");
            h.assertTrue(next.equals(new AbyssLodResourceIdentity.Snapshot(List.of(descriptor)).get()),"Independent wrapper construction preserves exact resource identity");
        }
        var metadata=new net.minecraft.server.packs.metadata.pack.PackMetadataSection(net.minecraft.network.chat.Component.literal("order"),15);
        var childA=new net.minecraftforge.resource.PathPackResources("a",true,root);
        var childB=new net.minecraftforge.resource.PathPackResources("b",true,root);
        var forward=new AbyssLodResourceIdentity.Captured("order",()->new net.minecraftforge.resource.DelegatingPackResources("mods",true,metadata,List.of(childA,childB)));
        var reverse=new AbyssLodResourceIdentity.Captured("order",()->new net.minecraftforge.resource.DelegatingPackResources("mods",true,metadata,List.of(childB,childA)));
        h.assertTrue(!new AbyssLodResourceIdentity.Snapshot(List.of(forward)).get().equals(new AbyssLodResourceIdentity.Snapshot(List.of(reverse)).get()),"Reversing nested pack precedence invalidates even when the set of root files is identical");
        var rootB=Files.createTempDirectory(parent,"vanilla-second-");var assetB=rootB.resolve("assets/mia/custom_folder/value.bin");
        Files.createDirectories(assetB.getParent());Files.write(assetB,new byte[]{3});
        var vanillaForward=new AbyssLodResourceIdentity.Captured("vanilla-order",()->new net.minecraft.server.packs.VanillaPackResourcesBuilder().pushUniversalPath(root).pushUniversalPath(rootB).exposeNamespace("mia").build());
        var vanillaReverse=new AbyssLodResourceIdentity.Captured("vanilla-order",()->new net.minecraft.server.packs.VanillaPackResourcesBuilder().pushUniversalPath(rootB).pushUniversalPath(root).exposeNamespace("mia").build());
        h.assertTrue(!new AbyssLodResourceIdentity.Snapshot(List.of(vanillaForward)).get().equals(new AbyssLodResourceIdentity.Snapshot(List.of(vanillaReverse)).get()),"Vanilla per-type and root list precedence is part of the identity with unchanged root contents");
        h.succeed();
    }
    private static ClassLoader getLoader(){return AbyssLodResourceIdentityGameTests.class.getClassLoader();}
}
