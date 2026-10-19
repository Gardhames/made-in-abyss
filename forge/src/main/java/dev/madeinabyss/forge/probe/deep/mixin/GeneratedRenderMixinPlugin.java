package dev.madeinabyss.forge.probe.deep.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Choose the actual terrain renderer; required redirects stay strict for each supported path. */
public final class GeneratedRenderMixinPlugin implements IMixinConfigPlugin {
    private boolean embeddium;
    @Override public void onLoad(String mixinPackage){
        try {
            org.spongepowered.asm.service.MixinService.getService().getBytecodeProvider()
                    .getClassNode("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");
            embeddium=true;
        }catch(ClassNotFoundException absent){embeddium=false;}
        catch(java.io.IOException invalid){throw new IllegalStateException("Cannot inspect optional Embeddium renderer",invalid);}
    }
    @Override public boolean shouldApplyMixin(String target,String mixin){
        if(mixin.endsWith("CountersMixin"))return Boolean.getBoolean("mia.render.drawCounters");
        if(mixin.endsWith("GeneratedTerrainLayerMixin"))return !embeddium;
        if(mixin.endsWith("GeneratedEmbeddiumMixin"))return embeddium;
        return true;
    }
    @Override public String getRefMapperConfig(){return null;}
    @Override public void acceptTargets(Set<String> mine,Set<String> others){}
    @Override public List<String> getMixins(){return null;}
    @Override public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    @Override public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
