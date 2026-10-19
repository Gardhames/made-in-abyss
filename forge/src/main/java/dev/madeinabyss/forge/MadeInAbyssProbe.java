package dev.madeinabyss.forge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;

@Mod(MadeInAbyssProbe.MOD_ID)
public final class MadeInAbyssProbe {
    public static final String MOD_ID = "madeinabyss";

    public MadeInAbyssProbe() {
        dev.madeinabyss.forge.probe.CubeProbeNetwork.register();
        dev.madeinabyss.forge.probe.deep.DeepLightNetwork.register();
        dev.madeinabyss.forge.probe.deep.GeneratedSceneNetwork.register();
        dev.madeinabyss.forge.probe.deep.DeepCubeRuntime.register();
        LogUtils.getLogger().info("Made in Abyss engine probe loaded; full-depth runtime is not implemented.");
    }
}
