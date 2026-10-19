package dev.madeinabyss.forge.probe.client;

import dev.madeinabyss.forge.cube.CubeSectionPacketCodec;
import dev.madeinabyss.forge.probe.deep.DeepChestClientMirror;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.cube.CubeSnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import java.util.*;

/** Private native containers never leave this operation. Only defensive, immutable snapshots escape.
 * The owner supplies the admitted connection's registry; this code never reads a Level or a live space.
 */
final class ClientWindowFrames {
    record Decoded(List<CubeSnapshot> snapshots,int bytes,long nativeNanos,long snapshotNanos,long entityNanos,long totalNanos,DeepChestClientMirror.ExpectedBlocks entityBlocks) {
        Decoded {snapshots=List.copyOf(snapshots);}
    }
    private ClientWindowFrames() { }
    static Decoded decode(List<byte[]> frames,Registry<Biome> biomes,Set<CubeKey> expected,Runnable checkpoint) {
        long started=System.nanoTime(),nativeNanos=0,snapshotNanos=0;int bytes=0;
        var snapshots=new ArrayList<CubeSnapshot>(frames.size());var seen=new HashSet<CubeKey>();
        for(byte[] frame:frames) {
            checkpoint.run();bytes+=frame.length;
            var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(frame));
            try {
                long mark=System.nanoTime();var cube=CubeSectionPacketCodec.decode(buffer,biomes);
                nativeNanos+=System.nanoTime()-mark;
                if(expected!=null && (!expected.contains(cube.key()) || seen.contains(cube.key())))
                    throw new IllegalArgumentException("Unexpected or duplicate visual cube");
                mark=System.nanoTime();int[] states=new int[4096];
                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
                    states[y<<8|z<<4|x]=Block.getId(cube.section().getBlockState(x,y,z));
                var snapshot=new CubeSnapshot(cube.key(),states);
                if(!seen.add(cube.key()))throw new IllegalStateException("cube already resident: "+cube.key());
                snapshots.add(snapshot);snapshotNanos+=System.nanoTime()-mark;
            } finally {buffer.release();}
        }
        if(expected==null && snapshots.size()!=27)throw new IllegalArgumentException("Duplicate or incomplete player window");
        long entityStart=System.nanoTime();var entityBlocks=expected==null?DeepChestClientMirror.expectedBlocks(snapshots,checkpoint):null;
        long entityNanos=System.nanoTime()-entityStart;
        checkpoint.run();return new Decoded(snapshots,bytes,nativeNanos,snapshotNanos,entityNanos,System.nanoTime()-started,entityBlocks);
    }
}
