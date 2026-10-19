package dev.madeinabyss.kernel.light.gpu;

import dev.madeinabyss.kernel.cube.CubeKey;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Read-only optical baseline with bounded word-page caches; no procedural sampler or mapping lifetime. */
public final class PackedSceneFiles implements AutoCloseable {
    private static final class Words implements AutoCloseable {
        private final FileChannel file;private final int length;
        private final LinkedHashMap<Integer,int[]> pages=new LinkedHashMap<>(16,.75f,true){protected boolean removeEldestEntry(Map.Entry<Integer,int[]> e){return size()>256;}};
        Words(Path path)throws IOException{file=FileChannel.open(path,StandardOpenOption.READ);long n=file.size();if((n&3)!=0||n/4>Integer.MAX_VALUE){file.close();throw new IOException("Invalid packed word file");}length=(int)(n/4);}
        synchronized int get(int index){Objects.checkIndex(index,length);if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Optical baseline read canceled");
            int page=index>>>10;var data=pages.get(page);if(data==null){int count=Math.min(1024,length-page*1024);var bytes=ByteBuffer.allocate(count*4).order(ByteOrder.nativeOrder());
                try{while(bytes.hasRemaining()){int n=file.read(bytes,(long)page*4096+bytes.position());if(n<0)throw new EOFException("Truncated optical baseline");}}
                catch(IOException failure){throw new UncheckedIOException(failure);}bytes.flip();data=new int[count];bytes.asIntBuffer().get(data);pages.put(page,data);}
            return data[index&1023];
        }
        public synchronized void close()throws IOException{file.close();pages.clear();}
    }
    private final Properties props=new Properties();private final int[] dims;
    private final int sky,lower,parentLower,water,haloMin,haloSide;private final double radius;
    private final Words directory,roots,opacity,haloMeta,haloData;
    public PackedSceneFiles(Path root)throws IOException{
        try(var in=Files.newInputStream(root.resolve("snapshot.properties"))){props.load(in);}
        if(!"true".equals(props.getProperty("closed")))throw new IOException("Unclosed optical baseline");
        dims=Arrays.stream(props.getProperty("dimensions").split(",")).mapToInt(Integer::parseInt).toArray();if(dims.length!=12)throw new IOException("Packed dimensions required");
        sky=Integer.parseInt(props.getProperty("skyY"));lower=Integer.parseInt(props.getProperty("coreLower"));parentLower=Integer.parseInt(props.getProperty("parentLower"));water=Integer.parseInt(props.getProperty("waterOpacity"));radius=Double.parseDouble(props.getProperty("knownRadiusSquared"));haloMin=Integer.parseInt(props.getProperty("haloMin"));haloSide=Integer.parseInt(props.getProperty("haloSide"));
        var open=new ArrayList<Words>();try{directory=new Words(root.resolve("directory.words"));open.add(directory);roots=new Words(root.resolve("roots.words"));open.add(roots);opacity=new Words(root.resolve("opacity.words"));open.add(opacity);haloMeta=new Words(root.resolve("halo-meta.words"));open.add(haloMeta);haloData=new Words(root.resolve("halo.words"));open.add(haloData);}
        catch(IOException|RuntimeException failed){for(var f:open)try{f.close();}catch(IOException close){failed.addSuppressed(close);}throw failed;}
    }
    private boolean inside(CubeKey k){return k.x()>=dims[0]&&k.y()>=dims[1]&&k.z()>=dims[2]&&k.x()<=dims[3]&&k.y()<=dims[4]&&k.z()<=dims[5];}
    public int root(CubeKey key){if(!inside(key))return PackedLightVolume.UNKNOWN;int p=directory.get((((key.y()>>3)-dims[7])*dims[11]+(key.z()>>3)-dims[8])*dims[9]+(key.x()>>3)-dims[6]);return p==0?PackedLightVolume.SEALED:roots.get(p-1+((key.y()&7)<<6)+((key.z()&7)<<3)+(key.x()&7));}
    private int value(int root,int x,int y,int z){int tag=root&3;if(tag==PackedLightVolume.UNIFORM)return(root>>>2)&15;if(tag==PackedLightVolume.DENSE){int i=((y&15)<<8)|((z&15)<<4)|(x&15);return(opacity.get((root>>>2)+(i>>>3))>>>((i&7)*4))&15;}return-1;}
    public int canonicalOpacity(int x,int y,int z){return value(root(new CubeKey(x>>4,y>>4,z>>4)),x,y,z);}
    private int top(int x,int z){long xx=(long)x-haloMin,zz=(long)z-haloMin;if(xx<0||zz<0||xx>=haloSide||zz>=haloSide)return Integer.MIN_VALUE;int p=(((int)zz>>4)*(haloSide>>4)+((int)xx>>4))*2,base=haloMeta.get(p),meta=haloMeta.get(p+1),bits=meta&15;if(bits==0)return base;
        int b=(((z&15)<<4)|(x&15))*bits,w=(meta>>>4)+(b>>>5),shift=b&31;long value=Integer.toUnsignedLong(haloData.get(w))>>>shift;if(shift+bits>32)value|=Integer.toUnsignedLong(haloData.get(w+1))<<(32-shift);return base+((int)value&((1<<bits)-1));}
    public int opacity(int x,int y,int z){
        if(y>=sky)return 0;double r2=(double)x*x+(double)z*z;if(y<(r2<radius?lower:parentLower))return-1;
        var key=new CubeKey(x>>4,y>>4,z>>4);if(inside(key)){int r=root(key);return(r&3)==PackedLightVolume.SEALED?15:value(r,x,y,z);}
        if(y>0&&r2>=4000000)return 0;int top=top(x,z);if(top==Integer.MIN_VALUE)return-1;if(y<=top)return 15;if(y<=0)return water;return top<=11||y>top+8?0:-1;
    }
    public int[] dimensions(){return dims.clone();}
    public int[] directory(){int[] result=new int[directory.length];for(int i=0;i<result.length;i++)result[i]=directory.get(i);return result;}
    public Properties properties(){var copy=new Properties();copy.putAll(props);return copy;}
    public void close()throws IOException{IOException error=null;for(var f:List.of(directory,roots,opacity,haloMeta,haloData))try{f.close();}catch(IOException failed){if(error==null)error=failed;else error.addSuppressed(failed);}if(error!=null)throw error;}
}
