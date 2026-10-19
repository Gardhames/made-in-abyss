package dev.madeinabyss.forge.worldgen;

import java.io.IOException;

/** Background source/native persistence shares the retired source's quarantine IO fence. */
public final class GeneratedNativeCachePersistence {
    private GeneratedNativeCachePersistence() {}

    @FunctionalInterface
    public interface IOAction {void run()throws IOException;}

    /** An admitted write finishes before quarantine; a retired source cannot start native IO. */
    public static void write(GeneratedSkySource source,IOAction nativeAction)throws IOException {
        synchronized(source){
            source.requirePhysicalAuthority();
            source.persistLightCache();
            source.requirePhysicalAuthority();
            nativeAction.run();
        }
    }
}
