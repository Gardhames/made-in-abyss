package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import dev.madeinabyss.kernel.lod.*;
import dev.madeinabyss.kernel.terrain.TerrainMaterial;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.minecraftforge.gametest.*;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AbyssLodScalarArchiveGameTests {
    private static final TerrainLodColumns.Bounds WORLD=new TerrainLodColumns.Bounds(-30000,-512,-512,30000,511,511);
    private static final long TINY=256L*1024;
    @GameTest(template="empty",timeoutTicks=300)
    public static void physicalReloadQuarantineDiscardsDirtyOwnersAndFencesPersistedFamily(GameTestHelper h)throws Exception {
        var directory=Files.createTempDirectory("mia-scalar-reload-");
        try {
            try(var unrelated=archive(directory,"unrelated-family",2048)){unrelated.put(owner(0),light(0));}
            var revoked=archive(directory,"revoked-family",2048);
            revoked.put(owner(0),light(0));revoked.flush();revoked.put(owner(1),light(1));
            revoked.quarantine();revoked.close();
            h.assertTrue(number(revoked,"dirtyPages")==0,"Reload discards pending writes rather than force-flushing mixed physical scalars");
            try(var fresh=archive(directory,"revoked-family",2048)){
                h.assertTrue(fresh.get(owner(0))==null&&fresh.get(owner(1))==null,"Returning to the previous semantics cannot restore values from the unsafe reload interval");
            }
            try(var unrelated=archive(directory,"unrelated-family",2048)){h.assertTrue(light(0).equals(unrelated.get(owner(0))),"Other authority families remain available");}
            try(var paths=Files.list(directory.resolve("owner-pages-v1"))){h.assertTrue(paths.anyMatch(p->p.getFileName().toString().contains(".quarantined-")),"Existing cache files are preserved in an ignored sibling");}
        }finally{remove(directory);}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=1200)
    public static void scalarArchivePersistsMoreThanGlobalFifoAcrossTinyResidentReopens(GameTestHelper h)throws Exception {
        var directory=Files.createTempDirectory("mia-scalar-all-owners-");int count=401_409;
        try{
            try(var archive=archive(directory,"world-A",TINY)){
                for(int i=0;i<count;i++)archive.put(owner(i),light(i));
                h.assertTrue(number(archive,"evictions")>300,"Tiny page cache must evict dirty pages while more than400k owners are recorded");
                h.assertTrue(number(archive,"peakResidentBytes")<=TINY,"Resident page accounting never exceeds256KiB");
                archive.flush();h.assertTrue(number(archive,"dirtyPages")==0,"Terminal flush commits every current dirty page");
            }
            try(var reopened=archive(directory,"world-A",TINY)){
                for(int i=0;i<count;i++)h.assertTrue(light(i).equals(reopened.get(owner(i))),"Every exact scalar survives dirty eviction and fresh reopen, owner="+i);
                h.assertTrue(number(reopened,"entriesRead")==count&&number(reopened,"diskHits")>300,"All401409 owners restored from existing scalar page files");
                h.assertTrue(number(reopened,"peakResidentBytes")<=TINY,"Warm resident accounting remains bounded");
            }
        }finally{remove(directory);}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void scalarArchiveKeepsSignedOwnerPagesAndStableFamilyAuthority(GameTestHelper h)throws Exception {
        var directory=Files.createTempDirectory("mia-scalar-authority-");var boxes=List.of(box(-129),box(-128),box(-1),box(0),box(127),box(128));
        var oldScope=new ExactBoxLightDiskCache.Scope("family","original32",WORLD,400_000);
        try{
            ExactBoxLightDiskCache.save(directory,oldScope,Map.of(boxes.get(0),new TerrainLodDiskCache.Light(15,15)));
            var legacy=ExactBoxLightDiskCache.file(directory,oldScope);byte[] legacyBytes=Files.readAllBytes(legacy);
            try(var archive=new AbyssLodScalarArchive(directory,"family","original32",WORLD,400_000,2048,(x,y,z)->"before:"+x+":"+y+":"+z)){
                h.assertTrue(archive.get(boxes.get(0))==null,"Legacy root slots are explicitly ignored");
                for(int i=0;i<boxes.size();i++)archive.put(boxes.get(i),light(i));
            }
            try(var changed=new AbyssLodScalarArchive(directory,"family","original32",WORLD,400_000,2048,
                    (x,y,z)->(x==0?"after:":"before:")+x+":"+y+":"+z)){
                for(int i=0;i<boxes.size();i++)h.assertTrue(Math.floorDiv(boxes.get(i).minX(),128)==0?changed.get(boxes.get(i))==null:light(i).equals(changed.get(boxes.get(i))),
                        "Only pages whose explicit edit authority changes become unknown");
            }
            h.assertTrue(Arrays.equals(legacyBytes,Files.readAllBytes(legacy)),"Legacy scalar file remains byte-identical");
            try(var otherResource=new AbyssLodScalarArchive(directory,"family","resource-B",WORLD,400_000)){
                h.assertTrue(otherResource.get(boxes.get(0))==null,"Changed resource/physical authority must not reuse stale numeric light");
            }
            // Signed floorDiv must distinguish -129/-128/-1/0/127/128; large BOX uses its minimum only.
            try(var exact=archive(directory,"whole-box",2048)){
                var crossed=new TerrainLodColumns.Box(-129,-129,-129,129,129,129,TerrainMaterial.ROCK);
                exact.put(crossed,new AbyssLodPipeline.Light(8,2));exact.flush();
                h.assertTrue(new AbyssLodPipeline.Light(8,2).equals(exact.get(crossed)),"World-bounded whole owner may cross many128m pages");
                boolean conflict=false;try{exact.put(crossed,new AbyssLodPipeline.Light(9,2));}catch(IllegalStateException expected){conflict=true;}
                h.assertTrue(conflict,"Conflicting scalar requires a different source authority");
            }
        }finally{remove(directory);}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void scalarArchiveCancellationAndFailedEvictionKeepDirtyOwners(GameTestHelper h)throws Exception {
        var directory=Files.createTempDirectory("mia-scalar-retry-");var blocked=directory.resolve("blocked");Files.writeString(blocked,"owned-test-blocker");
        try{
            var archive=archive(blocked,"world",256);archive.put(box(0),light(1));
            boolean bounded=false;try{archive.put(box(1),light(3));}catch(IllegalArgumentException expected){bounded=true;}
            h.assertTrue(bounded&&number(archive,"residentBytes")==256&&number(archive,"residentEntries")==1,"Single-page resident overflow refuses before modifying exact owners");
            boolean failed=false;try{archive.put(box(128),light(2));}catch(IOException expected){failed=true;}
            h.assertTrue(failed&&number(archive,"dirtyPages")==1&&number(archive,"residentEntries")==1,"Failed dirty eviction preserves the exact original resident page");
            h.assertTrue(light(1).equals(archive.get(box(0))),"IO failure cannot discard computed light");
            Files.delete(blocked);Files.createDirectory(blocked);
            Thread.currentThread().interrupt();boolean canceled=false;
            try{archive.close();}catch(java.util.concurrent.CancellationException expected){canceled=true;}finally{Thread.interrupted();}
            h.assertTrue(canceled&&number(archive,"dirtyPages")==1,"Canceled close retains dirty state and can be retried");
            archive.close();boolean closed=false;try{archive.put(box(0),light(1));}catch(IllegalStateException expected){closed=true;}
            h.assertTrue(closed,"Late writes cannot silently enter a closed archive");
            try(var reopened=archive(blocked,"world",256)){h.assertTrue(light(1).equals(reopened.get(box(0))),"Successful retry flushes dirty shutdown data");}
            Path file;try(var files=Files.walk(blocked)){file=files.filter(p->p.toString().endsWith(".bin")).findFirst().orElseThrow();}
            var bytes=Files.readAllBytes(file);bytes[bytes.length/2]^=1;Files.write(file,bytes);
            try(var corrupt=archive(blocked,"world",256)){h.assertTrue(corrupt.get(box(0))==null&&String.valueOf(corrupt.status().get("lastRead")).startsWith("INVALID"),"Checksum corruption is unknown, not numeric light");}
            Files.write(file,Arrays.copyOf(bytes,20));
            try(var truncated=archive(blocked,"world",256)){h.assertTrue(truncated.get(box(0))==null,"Truncated page is unknown");}
        }finally{remove(directory);}h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=300)
    public static void scalarArchiveConcurrentWorkersCommitEachOwnerOnce(GameTestHelper h)throws Exception {
        var directory=Files.createTempDirectory("mia-scalar-parallel-");var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        try{
            try(var archive=archive(directory,"parallel",2L*1024*1024)){
                var tasks=new ArrayList<java.util.concurrent.Future<?>>();
                for(int worker=0;worker<4;worker++)tasks.add(executor.submit(()->{
                    for(int i=0;i<8192;i++){archive.put(owner(i),light(i));if(!light(i).equals(archive.get(owner(i))))throw new AssertionError("Concurrent exact scalar changed");}return null;
                }));
                for(var task:tasks)task.get();
                h.assertTrue(number(archive,"residentEntries")==8192,"Duplicate concurrent exact put is idempotent");archive.flush();
                h.assertTrue(number(archive,"dirtyEntries")==0&&number(archive,"entriesWritten")==8192,"Concurrent owners flush exactly once per page");
            }
            try(var reopened=archive(directory,"parallel",2L*1024*1024)){
                for(int i=0;i<8192;i++)h.assertTrue(light(i).equals(reopened.get(owner(i))),"Concurrent output survives fresh reopen");
            }
        }finally{executor.shutdownNow();remove(directory);}h.succeed();
    }
    private static AbyssLodScalarArchive archive(Path directory,String source,long bytes){return new AbyssLodScalarArchive(directory,source,"original32",WORLD,400_000,bytes);}
    private static long number(AbyssLodScalarArchive archive,String key){return ((Number)archive.status().get(key)).longValue();}
    private static TerrainLodColumns.Box owner(int i){long x=((long)i/1024-196)*128+i%128,y=-128+(i%1024)/128;return new TerrainLodColumns.Box(x,y,-128,x+1,y+1,-127,TerrainMaterial.ROCK);}
    private static TerrainLodColumns.Box box(long x){return new TerrainLodColumns.Box(x,-129,-128,x+1,-128,-127,TerrainMaterial.ROCK);}
    private static AbyssLodPipeline.Light light(int i){return new AbyssLodPipeline.Light(i%16,(i/16)%16);}
    private static void remove(Path root)throws IOException {try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
}
