package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonParser;
import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@GameTestHolder(MadeInAbyssProbe.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneratedSkyIdentityConcurrencyGameTests {
    @GameTest(template="empty", timeoutTicks=1200)
    public static void resolvedPhysicalTagsInvalidateDerivedAuthorityButUnrelatedTagsDoNot(GameTestHelper h) throws Exception {
        var holder=net.minecraft.world.level.block.Blocks.STONE.builtInRegistryHolder();
        var original=holder.tags().toList();
        var parent=java.nio.file.Files.createTempDirectory("mia-reload-sky-");var sky=parent.resolve("sky-probes");
        try {
            var before=source(h);String identity=before.cacheGeometryIdentity();
            before.restoreLightCache(sky,"physical-reload-fixture");before.persistLightCache();
            var changed=new java.util.HashSet<>(original);
            changed.add(net.minecraft.tags.TagKey.create(Registries.BLOCK,new net.minecraft.resources.ResourceLocation("madeinabyss","irrelevant_test_tag")));
            holder.bindTags(changed);
            h.assertTrue(identity.equals(source(h).cacheGeometryIdentity()),"Unused resource tags cannot invalidate exact physical scalars");
            before.verifyPhysicalSemantics();
            if(!changed.remove(net.minecraft.tags.BlockTags.LOGS))changed.add(net.minecraft.tags.BlockTags.LOGS);
            holder.bindTags(changed);
            h.assertTrue(!identity.equals(source(h).cacheGeometryIdentity()),"Resolved LOGS classification used by upper opening must invalidate derived source authority");
            boolean refused=false;try{before.verifyPhysicalSemantics();}catch(IllegalStateException expected){refused=true;}
            h.assertTrue(refused,"Live physical semantics reload refuses old source instead of keeping stale generator classifications");
            holder.bindTags(original);
            boolean retired=false;try{before.requirePhysicalAuthority();}catch(CancellationException expected){retired=true;}
            h.assertTrue(retired,"Restoring tags cannot revive an immutable source retired during a physical reload");
            boolean writeRefused=false;try{before.persistLightCache();}catch(CancellationException expected){writeRefused=true;}
            h.assertTrue(writeRefused,"A revoked source cannot persist native probe values");
            before.quarantineLightCaches();
            h.assertTrue(!java.nio.file.Files.exists(sky),"Native probe/procedural/window cache namespace is removed from reuse after the writer fence");
        } finally {holder.bindTags(original);try(var paths=java.nio.file.Files.walk(parent)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}}
        h.succeed();
    }
    @GameTest(template="empty", timeoutTicks=1200)
    public static void cachedIdentityReturnsWhilePersistenceMonitorIsHeld(GameTestHelper h) throws Exception {
        var source = source(h);
        String expected = source.cacheGeometryIdentity();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var lookupStarted = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var result = new AtomicReference<String>();
        var failure = new AtomicReference<Throwable>();
        Thread owner = new Thread(() -> {
            synchronized (source) {
                held.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Monitor release deadline expired");
                } catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
            }
        }, "identity-test-persistence-owner");
        Thread reader = new Thread(() -> {
            lookupStarted.countDown();
            try { result.set(source.cacheGeometryIdentity()); }
            catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
            finally { returned.countDown(); }
        }, "identity-test-render-reader");
        boolean completedWhileHeld = false;
        try {
            owner.start();
            h.assertTrue(held.await(2, TimeUnit.SECONDS), "Persistence worker actually holds the source monitor");
            reader.start();
            h.assertTrue(lookupStarted.await(2, TimeUnit.SECONDS), "Identity lookup reader started");
            completedWhileHeld = returned.await(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            owner.join(2000);
            reader.join(2000);
        }
        h.assertTrue(!owner.isAlive() && !reader.isAlive(), "Controlled workers terminate even on RED");
        h.assertTrue(failure.get() == null, "Identity lookup workers have no errors: " + failure.get());
        h.assertTrue(completedWhileHeld, "Cached immutable identity must return before unrelated persistence releases the source monitor");
        h.assertTrue(expected.equals(result.get()), "Nonblocking lookup preserves exact derived identity bytes");
        h.succeed();
    }

    @GameTest(template="empty", timeoutTicks=1200)
    public static void concurrentFirstIdentityPublishesOneCompleteValue(GameTestHelper h) throws Exception {
        var source = source(h);
        String expected = source(h).cacheGeometryIdentity();
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        var failure = new AtomicReference<Throwable>();
        var first = new AtomicReference<String>();
        var second = new AtomicReference<String>();
        Thread a = identityReader(source, start, ready, first, failure);
        Thread b = identityReader(source, start, ready, second, failure);
        try {
            a.start(); b.start();
            h.assertTrue(ready.await(2, TimeUnit.SECONDS), "Both first readers reach the controlled start gate");
        } finally {
            start.countDown();
            a.join(2000); b.join(2000);
        }
        h.assertTrue(!a.isAlive() && !b.isAlive() && failure.get() == null, "Concurrent first readers terminate without errors");
        h.assertTrue(expected.equals(first.get()) && expected.equals(second.get()), "Concurrent initialization exposes only complete exact identity bytes");
        h.assertTrue(expected.equals(source.cacheGeometryIdentity()), "Published identity remains stable");
        h.succeed();
    }

    @GameTest(template="empty", timeoutTicks=1200)
    public static void interruptedFirstIdentityDoesNotPublishPartialValue(GameTestHelper h) throws Exception {
        var source = source(h);
        String expected = source(h).cacheGeometryIdentity();
        var outcome = new AtomicReference<Throwable>();
        Thread cancelled = new Thread(() -> {
            Thread.currentThread().interrupt();
            try { source.cacheGeometryIdentity(); }
            catch (Throwable thrown) { outcome.set(thrown); }
        }, "identity-test-cancelled-initializer");
        cancelled.start(); cancelled.join(2000);
        h.assertTrue(!cancelled.isAlive(), "Cancelled initializer terminates");
        h.assertTrue(outcome.get() instanceof CancellationException, "Interrupted uncached initialization refuses publication");
        h.assertTrue(expected.equals(source.cacheGeometryIdentity()), "Fresh retry after cancellation publishes the full exact identity");
        h.succeed();
    }

    private static Thread identityReader(GeneratedSkySource source, CountDownLatch start, CountDownLatch ready,
                                         AtomicReference<String> result, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Initialization start deadline expired");
                result.set(source.cacheGeometryIdentity());
            } catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
        }, "identity-test-first-reader");
    }

    private static GeneratedSkySource source(GameTestHelper h) throws Exception {
        try (var in = GeneratedSkyIdentityConcurrencyGameTests.class.getResourceAsStream("/diagnostics/cave-equivalence-v14.json")) {
            var manifest = JsonParser.parseString(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            var biomes = new FixedBiomeSource(h.getLevel().registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
            return new GeneratedSkySource(new GeneratedPreviewProfile(manifest, manifest.get("profileId").getAsString(), biomes), Map.of());
        }
    }
}
