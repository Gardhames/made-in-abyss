package dev.madeinabyss.forge.probe.client.compat;

import dev.madeinabyss.kernel.lod.RenderGroupHandoff;
import java.util.List;
import java.util.Objects;

/** Owner-thread scene transport, without Minecraft, DH, source sampling or disk access.
 * Hidden GPU readiness and actual world presentation are separate transitions.
 */
public final class AbyssLodTransport<K, S> {
    public enum DrawPass { HIDDEN_UPLOAD, SHADOW, WORLD }
    private final RenderGroupHandoff<S> scenes = new RenderGroupHandoff<>();
    private K target;
    private long sequence;
    private S acknowledged;

    public S request(K next, long nextSequence) {
        target=Objects.requireNonNull(next);sequence=nextSequence;acknowledged=null;
        return scenes.beginTarget(nextSequence);
    }
    public void stage(K candidateTarget,long candidateSequence,S scene) {
        if(!Objects.equals(target,candidateTarget))throw new IllegalStateException("Stale scene authority");
        scenes.stage(candidateSequence,scene);
    }
    public void uploaded(long candidateSequence,S scene) {scenes.markDrawn(candidateSequence,scene);}
    public S promote() {return scenes.promote();}
    public boolean drawn(K candidateTarget,long candidateSequence,S scene,DrawPass pass,int positiveBoxes) {
        if(!Objects.equals(target,candidateTarget)||pass!=DrawPass.WORLD||candidateSequence!=sequence || scenes.displayed()!=scene || positiveBoxes<=0 || acknowledged==scene)return false;
        acknowledged=scene;return true;
    }
    public S displayed(){return scenes.displayed();}
    public S staged(){return scenes.staged();}
    public boolean ready(){return scenes.ready();}
    public int size(){return scenes.size();}
    public K target(){return target;}
    public List<S> clear(){acknowledged=null;target=null;return scenes.clear();}
}
