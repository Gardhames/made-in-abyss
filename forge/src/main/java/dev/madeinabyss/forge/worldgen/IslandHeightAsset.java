package dev.madeinabyss.forge.worldgen;

import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.HexFormat;
import java.util.zip.*;

/** Fixed, lossless approved DEM. Strict size/hash/framing limits apply before publication. */
final class IslandHeightAsset {
    static final String ID="assets/madeinabyss/terrain/island-v13-proposal-1.f64le.zlib";
    static final int SIZE=641, RAW_BYTES=SIZE*SIZE*8, MAX_COMPRESSED=RAW_BYTES+65536;
    static double[] load(String expectedRaw,String expectedCompressed) {
        try(var in=IslandHeightAsset.class.getClassLoader().getResourceAsStream(ID)) {
            if(in==null)throw new IllegalArgumentException("Missing bundled approved island asset");
            byte[] packed=in.readNBytes(MAX_COMPRESSED+1);
            if(packed.length>MAX_COMPRESSED)throw new IllegalArgumentException("Oversized island compressed resource");
            return decode(packed,expectedRaw,expectedCompressed);
        } catch(IOException e){throw new IllegalArgumentException("Island asset IO",e);}
    }
    static double[] decode(byte[] packed,String expectedRaw,String expectedCompressed) {
        if(packed.length>MAX_COMPRESSED || !sha(packed).equals(expectedCompressed))
            throw new IllegalArgumentException("Island compressed size/hash mismatch");
        var inflater=new Inflater();byte[] raw=new byte[RAW_BYTES];
        try {
            inflater.setInput(packed);int n=0;
            while(n<raw.length && !inflater.finished()) {
                int count=inflater.inflate(raw,n,raw.length-n);
                if(count==0)throw new IllegalArgumentException("Truncated or stalled island compressed stream");
                n+=count;
            }
            // A one-byte probe rejects decompression bombs without allocating their output.
            byte[] extra=new byte[1];int overflow=inflater.inflate(extra);
            if(n!=RAW_BYTES || overflow!=0 || !inflater.finished() || inflater.getRemaining()!=0)
                throw new IllegalArgumentException("Island decompressed size/framing mismatch");
            if(!sha(raw).equals(expectedRaw))throw new IllegalArgumentException("Island data SHA256 mismatch");
            double[] values=new double[SIZE*SIZE];var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<values.length;i++){
                double v=b.getDouble();if(!Double.isFinite(v)||v < -5000||v>=310)throw new IllegalArgumentException("Invalid island height");
                values[i]=v;
            }
            return values;
        }catch(DataFormatException e){throw new IllegalArgumentException("Corrupt island zlib",e);}
        finally{inflater.end();}
    }
    static String sha(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
