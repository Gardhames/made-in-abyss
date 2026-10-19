package dev.madeinabyss.forge.probe.client.compat.lightgpu;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.opengl.GL46C.*;
import org.lwjgl.system.MemoryUtil;
import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.volume.VoxelBrick;
import dev.madeinabyss.kernel.light.gpu.IncrementalLightVolume;

/** G2 backend. Requires the caller's GL context; has no Minecraft/CPU-sampler dependency. */
public final class GpuOwnerSkyKernel implements AutoCloseable {
    public record Edit(long expectedEpoch,Map<CubeKey,VoxelBrick> replacements,int skyY){public Edit{replacements=Map.copyOf(replacements);}}
    public record EditResult(long epoch,int bricks,int columns,int fractions,int invalidatedOwners,long uploadBytes){}
    public EditResult applyEdit(Edit edit){throw new UnsupportedOperationException("GPU local optical patch not wired");}
    public long epoch(){return 0;}
    public IncrementalLightVolume.Snapshot opticalSnapshot(){throw new UnsupportedOperationException("GPU local optical patch not wired");}
    public static final int BATCH=32768,SHARD_WORDS=1<<25,PROBE_CHUNK=16384;
    private final int[] buffers=new int[16];
    private final int[] programs=new int[6];
    private final long[] sizes=new long[16];
    private final int[] dimensions,gridMin,gridSize;
    private final int haloMin,haloSide,sky,coreLower,parentLower,water;
    private final double radiusSquared;
    private final int fractionCapacity=BATCH*48,quantCapacity=BATCH*6;
    private final long gridCount;
    private final Map<String,Long> stages=new LinkedHashMap<>();
    private long fractionJobs,quantJobs,maxFractionJobs,maxQuantJobs;
    @FunctionalInterface public interface ShaderSource {String read(String name)throws IOException;}
    @FunctionalInterface public interface DispatchControl {void run(String stage,Runnable commands);}
    private DispatchControl control=(stage,commands)->commands.run();
    private int workChunk=PROBE_CHUNK;
    private int ownerChunk=BATCH;
    private java.util.function.Consumer<String> progress=stage->{};
    public void progress(java.util.function.Consumer<String> listener){progress=Objects.requireNonNull(listener);}
    public static final class Refused extends IllegalStateException {
        private final int[] counts;
        Refused(int[] counts){super("GPU refused UNKNOWN/budget/overflow: "+Arrays.toString(counts));this.counts=counts.clone();}
        public int[] counters(){return counts.clone();}
    }
    private final Properties properties=new Properties();
    public GpuOwnerSkyKernel(Path snapshot,Path shaderRoot)throws Exception {this(snapshot,name->Files.readString(shaderRoot.resolve(name)));}
    public GpuOwnerSkyKernel(Path snapshot,ShaderSource shaders)throws Exception {
        if(glGetInteger(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS)<16||glGetInteger64(GL_MAX_SHADER_STORAGE_BLOCK_SIZE)<(1L<<27))throw new IllegalStateException("Insufficient SSBO limits");
        try(var in=Files.newInputStream(snapshot.resolve("snapshot.properties"))){properties.load(in);}
        if(!"true".equals(properties.getProperty("closed")))throw new IllegalArgumentException("Unclosed optical snapshot");
        dimensions=Arrays.stream(properties.getProperty("dimensions").split(",")).mapToInt(Integer::parseInt).toArray();
        haloMin=Integer.parseInt(properties.getProperty("haloMin"));haloSide=Integer.parseInt(properties.getProperty("haloSide"));sky=Integer.parseInt(properties.getProperty("skyY"));coreLower=Integer.parseInt(properties.getProperty("coreLower"));parentLower=Integer.parseInt(properties.getProperty("parentLower"));water=Integer.parseInt(properties.getProperty("waterOpacity"));radiusSquared=Double.parseDouble(properties.getProperty("knownRadiusSquared"));
        gridMin=new int[]{Math.floorDiv(dimensions[0]*16-16,8)*8,Math.floorDiv(dimensions[1]*16-16,8)*8,Math.floorDiv(dimensions[2]*16-16,8)*8};
        gridSize=new int[]{((dimensions[3]+1)*16+16-gridMin[0])/8+1,((dimensions[4]+1)*16+16-gridMin[1])/8+1,((dimensions[5]+1)*16+16-gridMin[2])/8+1};gridCount=(long)gridSize[0]*gridSize[1]*gridSize[2];
        glGenBuffers(buffers);try{upload(0,snapshot.resolve("directory.words"),0,Files.size(snapshot.resolve("directory.words")));upload(1,snapshot.resolve("roots.words"),0,Files.size(snapshot.resolve("roots.words")));
        Path opacity=snapshot.resolve("opacity.words");long bytes=Files.size(opacity);for(int i=0;i<3;i++){long start=i*(1L<<27),n=Math.max(0,Math.min(1L<<27,bytes-start));if(n>0)upload(2+i,opacity,start,n);else allocate(2+i,4);}
        if(bytes>3L*(1L<<27))throw new IllegalArgumentException("Opacity pool exceeds3shards");
        upload(5,snapshot.resolve("halo-meta.words"),0,Files.size(snapshot.resolve("halo-meta.words")));upload(6,snapshot.resolve("halo.words"),0,Files.size(snapshot.resolve("halo.words")));
        long columns=(long)(gridSize[0]*8+1)*(gridSize[2]*8+1);allocate(7,columns*12);
        long fractionBytes=gridCount*8;allocate(8,Math.min(1L<<27,fractionBytes));allocate(9,Math.max(8,fractionBytes-(1L<<27)));
        allocate(10,((gridCount+31)/32)*4);allocate(11,((long)fractionCapacity+quantCapacity)*4);allocate(12,PROBE_CHUNK*32L*8);allocate(13,BATCH*32L);allocate(14,BATCH*32L);allocate(15,128);
        String common=shaders.read("common.glsl");String[] names={"direct","prepare","rays","reduce","prefix","resolve"};
        for(int i=0;i<names.length;i++){programs[i]=program(shaders.read(names[i]+".comp").replace("#include \"common.glsl\"",common));configure(programs[i]);}
        glFinish();checkError();
        }catch(Exception|Error failed){close();throw failed;}
    }
    private void allocate(int binding,long bytes){sizes[binding]=bytes;glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[binding]);glBufferData(GL_SHADER_STORAGE_BUFFER,bytes,GL_DYNAMIC_DRAW);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,binding,buffers[binding]);if(glGetBufferParameteri64(GL_SHADER_STORAGE_BUFFER,GL_BUFFER_SIZE)!=bytes)throw new IllegalStateException("Allocation mismatch");}
    private void upload(int binding,Path path,long offset,long bytes)throws IOException{allocate(binding,bytes);try(var channel=FileChannel.open(path)){long copied=0;while(copied<bytes){long count=Math.min(32L<<20,bytes-copied);var mapped=channel.map(FileChannel.MapMode.READ_ONLY,offset+copied,count);glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[binding]);glBufferSubData(GL_SHADER_STORAGE_BUFFER,copied,mapped);copied+=count;}}}
    private static int program(String source){int shader=glCreateShader(GL_COMPUTE_SHADER),p=0;try{glShaderSource(shader,source);glCompileShader(shader);if(glGetShaderi(shader,GL_COMPILE_STATUS)==0)throw new IllegalStateException(glGetShaderInfoLog(shader));p=glCreateProgram();glAttachShader(p,shader);glLinkProgram(p);if(glGetProgrami(p,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(p));return p;}catch(RuntimeException|Error failed){if(p!=0)glDeleteProgram(p);throw failed;}finally{glDeleteShader(shader);}}
    private static void iv3(int p,String name,int[] v){glUniform3i(glGetUniformLocation(p,name),v[0],v[1],v[2]);}
    private void configure(int p){glUseProgram(p);iv3(p,"uBrickMin",Arrays.copyOfRange(dimensions,0,3));iv3(p,"uBrickMax",Arrays.copyOfRange(dimensions,3,6));iv3(p,"uPageMin",Arrays.copyOfRange(dimensions,6,9));iv3(p,"uPageSize",Arrays.copyOfRange(dimensions,9,12));iv3(p,"uGridMin",gridMin);iv3(p,"uGridSize",gridSize);
        glUniform1i(glGetUniformLocation(p,"uHaloMin"),haloMin);glUniform1i(glGetUniformLocation(p,"uHaloSide"),haloSide);glUniform1i(glGetUniformLocation(p,"uSky"),sky);glUniform1i(glGetUniformLocation(p,"uCoreLower"),coreLower);glUniform1i(glGetUniformLocation(p,"uParentLower"),parentLower);glUniform1i(glGetUniformLocation(p,"uWater"),water);glUniform1d(glGetUniformLocation(p,"uRadiusSquared"),radiusSquared);
        glUniform1ui(glGetUniformLocation(p,"uFractionCap"),fractionCapacity);glUniform1ui(glGetUniformLocation(p,"uQuantCap"),quantCapacity);
        int[] dirs=new int[128];double angle=Math.PI*(3-Math.sqrt(5));for(int i=0;i<32;i++){double y=Math.sqrt((i+.5)/32),r=Math.sqrt(1-y*y),x=r*Math.cos(i*angle),z=r*Math.sin(i*angle);dirs[i*4]=(int)Math.round(Math.abs(x)*(1L<<30));dirs[i*4+1]=(int)Math.round(y*(1L<<30));dirs[i*4+2]=(int)Math.round(Math.abs(z)*(1L<<30));dirs[i*4+3]=(Integer.signum(Double.compare(x,0))+1)|((Integer.signum(Double.compare(z,0))+1)<<2);}
        glUniform4iv(glGetUniformLocation(p,"uDirections"),dirs);double[] factors=new double[16];for(int i=0;i<16;i++)factors[i]=(15.0-i)/15.0;glUniform1dv(glGetUniformLocation(p,"uFactors"),factors);
    }
    private void zero(int binding){progress.accept("buffer.clear."+binding);glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[binding]);glClearBufferData(GL_SHADER_STORAGE_BUFFER,GL_R32UI,GL_RED_INTEGER,GL_UNSIGNED_INT,new int[]{0});}
    private static void checkError(){int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException("GL error0x"+Integer.toHexString(error));}
    public void scheduling(int workChunk,DispatchControl control){if(workChunk<1||workChunk>PROBE_CHUNK)throw new IllegalArgumentException("Invalid compute quantum");this.workChunk=workChunk;this.ownerChunk=2048;this.control=Objects.requireNonNull(control);}
    private void dispatch(int stage,int groupsX,int groupsY){String name=new String[]{"direct","prepare","rays","reduce","prefix","resolve"}[stage];control.run(name,()->{long start=System.nanoTime();glUseProgram(programs[stage]);glDispatchCompute(groupsX,groupsY,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);stages.merge(name+"Submit",System.nanoTime()-start,Long::sum);});}
    public void beginScene(){stages.clear();fractionJobs=quantJobs=maxFractionJobs=maxQuantJobs=0;zero(10);zero(15);int columns=(gridSize[0]*8+1)*(gridSize[2]*8+1),chunk=Math.max(8192,workChunk*16);
        for(int offset=0;offset<columns;offset+=chunk){int n=Math.min(chunk,columns-offset);progress.accept("uniforms.direct");glUseProgram(programs[0]);glUniform1ui(glGetUniformLocation(programs[0],"uJobOffset"),offset);glUniform1ui(glGetUniformLocation(programs[0],"uCount"),n);int groups=(n+127)/128;dispatch(0,Math.min(groups,32768),(groups+32767)/32768);}progress.accept("direct.finish");glFinish();assertCounters();}
    public int[] solve(IntBuffer owners,int count){if(count<0||count>BATCH||owners.remaining()!=count*8)throw new IllegalArgumentException("Invalid owner batch");zero(15);progress.accept("upload.owners");glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[13]);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,owners);
        for(int offset=0;offset<count;offset+=ownerChunk){int n=Math.min(ownerChunk,count-offset);progress.accept("uniforms.prepare");glUseProgram(programs[1]);glUniform1ui(glGetUniformLocation(programs[1],"uJobOffset"),offset);glUniform1ui(glGetUniformLocation(programs[1],"uCount"),n);dispatch(1,(n+127)/128,1);}int[] c=counters();assertCounters(c);fractionJobs+=c[5];quantJobs+=c[6];maxFractionJobs=Math.max(maxFractionJobs,c[5]);maxQuantJobs=Math.max(maxQuantJobs,c[6]);
        for(int offset=0;offset<c[5];offset+=workChunk){int n=Math.min(workChunk,c[5]-offset);for(int stage:new int[]{2,3}){progress.accept(stage==2?"uniforms.rays":"uniforms.reduce");glUseProgram(programs[stage]);glUniform1ui(glGetUniformLocation(programs[stage],"uJobOffset"),offset);glUniform1ui(glGetUniformLocation(programs[stage],"uCount"),n);dispatch(stage,((stage==2?n*32:n)+127)/128,1);}}
        for(int offset=0;offset<c[6];offset+=workChunk){int n=Math.min(workChunk,c[6]-offset);progress.accept("uniforms.prefix");glUseProgram(programs[4]);glUniform1ui(glGetUniformLocation(programs[4],"uJobOffset"),offset);glUniform1ui(glGetUniformLocation(programs[4],"uCount"),n);dispatch(4,Math.min(n,32768),(n+32767)/32768);}
        for(int offset=0;offset<count;offset+=ownerChunk){int n=Math.min(ownerChunk,count-offset);progress.accept("uniforms.resolve");glUseProgram(programs[5]);glUniform1ui(glGetUniformLocation(programs[5],"uJobOffset"),offset);glUniform1ui(glGetUniformLocation(programs[5],"uCount"),n);dispatch(5,(n+127)/128,1);}int[] face=new int[count*8];progress.accept("readback.results");glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[14]);glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,face);assertCounters();checkError();progress.accept("cpu.extract-results");int[] result=new int[count];for(int i=0;i<count;i++)result[i]=face[i*8+1];return result;
    }
    private int[] counters(){progress.accept("readback.counters");int[] result=new int[32];glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[15]);glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,result);return result;}
    private void assertCounters(){assertCounters(counters());}
    private void assertCounters(int[] c){if(c[0]!=0||c[4]!=0||c[7]!=0)throw new Refused(c);}
    public Map<String,Object> status(){var result=new LinkedHashMap<String,Object>();long total=0;var sizesOut=new ArrayList<Long>();for(long n:sizes){total+=n;sizesOut.add(n);}result.put("bufferBytes",sizesOut);result.put("totalBufferBytes",total);result.put("gridNodes",gridCount);result.put("fractionJobs",fractionJobs);result.put("quantizedJobs",quantJobs);result.put("maxFractionJobsBatch",maxFractionJobs);result.put("maxQuantizedJobsBatch",maxQuantJobs);result.put("lastCounters",counters());result.put("submissionsNanos",Map.copyOf(stages));result.put("maxSsboBlockBytes",glGetInteger64(GL_MAX_SHADER_STORAGE_BLOCK_SIZE));result.put("maxComputeSsboBlocks",glGetInteger(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS));return result;}
    @Override public void close(){for(int p:programs)if(p!=0)glDeleteProgram(p);glDeleteBuffers(buffers);}
}
