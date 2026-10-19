package dev.madeinabyss.forge.probe;

import dev.madeinabyss.forge.MadeInAbyssProbe;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.logging.LogUtils;

/** Optional clean startup/shutdown smoke; inactive in ordinary game profiles. */
@Mod.EventBusSubscriber(modid = MadeInAbyssProbe.MOD_ID)
public final class ServerStartupProbe {
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (Boolean.getBoolean("mia.probe.stopAfterStartup")) {
            LogUtils.getLogger().info("MIA_SERVER_SMOKE_STARTED class={}", event.getServer().getClass().getName());
            event.getServer().execute(() -> event.getServer().halt(false));
        }
    }
}
