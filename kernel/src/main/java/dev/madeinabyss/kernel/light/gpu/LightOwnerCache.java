package dev.madeinabyss.kernel.light.gpu;

import java.util.*;

/** Bounded exact-SKY residency, separate from immutable profile/global optical authority. */
public final class LightOwnerCache {
    public static final int VALID=1<<11,DIRECT=1<<10;
    public record Key(int minX,int minY,int minZ,int maxX,int maxY,int maxZ,int bottom){
        public Key{if(minX>=maxX||minY>=maxY||minZ>=maxZ)throw new IllegalArgumentException("Positive physical owner required");}
    }
    public record Lease(int id,boolean created,Key key,long generation){}
    private final int capacity,mask;private final int[] words,table;private final long[] versions;
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntOpenHashSet> buckets=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private int size,victim;private long clock,minimumFaceY=Long.MAX_VALUE;
    public LightOwnerCache(int capacity){
        if(capacity<1||capacity>(1<<24))throw new IllegalArgumentException("Bounded owner capacity required");this.capacity=capacity;
        int n=2;while(n<capacity*2)n<<=1;table=new int[n];mask=n-1;words=new int[Math.multiplyExact(capacity,8)];versions=new long[capacity];
    }
    private static int hash(Key k){long h=(long)k.minX()*0x9e3779b97f4a7c15L^Long.rotateLeft((long)k.minY()*0xc2b2ae3d27d4eb4fL,11)^Long.rotateLeft((long)k.minZ()*0x165667b19e3779f9L,22)^Long.rotateLeft((long)k.maxX()*0x85ebca77c2b2ae63L,33)^Long.rotateLeft((long)k.maxY()*0x27d4eb2f165667c5L,44)^Long.rotateLeft((long)k.maxZ()*0x94d049bb133111ebL,55)^k.bottom();h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;return(int)(h^(h>>>31));}
    private boolean matches(int id,Key k){int p=id*8;return words[p]==k.minX()&&words[p+1]==k.minY()&&words[p+2]==k.minZ()&&words[p+3]==k.maxX()&&words[p+4]==k.maxY()&&words[p+5]==k.maxZ()&&words[p+6]==k.bottom();}
    private int slot(Key k){int p=hash(k)&mask;while(table[p]!=0&&!matches(table[p]-1,k))p=(p+1)&mask;return p;}
    private Key key(int id){int p=id*8;return new Key(words[p],words[p+1],words[p+2],words[p+3],words[p+4],words[p+5],words[p+6]);}
    private void removeIndex(Key key){int last=slot(key),p=last;for(;;){p=(p+1)&mask;while(table[p]!=0){int home=hash(key(table[p]-1))&mask;
            if(last<=p?last>=home||home>p:last>=home&&home>p)break;p=(p+1)&mask;}
            if(table[p]==0){table[last]=0;return;}table[last]=table[p];last=p;}}
    private static long bucket(long x,long y,long z){long h=x*0x9e3779b97f4a7c15L^Long.rotateLeft(y*0xc2b2ae3d27d4eb4fL,21)^Long.rotateLeft(z*0x165667b19e3779f9L,42);h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;return h^(h>>>31);}
    private static long[] memberships(Key k){long x=(long)k.minX()+((long)k.maxX()-k.minX()-1)/2,y=(long)k.minY()+((long)k.maxY()-k.minY()-1)/2,z=(long)k.minZ()+((long)k.maxZ()-k.minZ()-1)/2;
        long[][] p={{x,k.maxY(),z},{(long)k.minX()-1,y,z},{k.maxX(),y,z},{x,y,(long)k.minZ()-1},{x,y,k.maxZ()},{x,(long)k.minY()-1,z}};long[] result=new long[6];int n=0;
        for(int i=0;i<(k.minY()>k.bottom()?6:5);i++){long b=bucket(Math.floorDiv(p[i][0],128),Math.floorDiv(p[i][1],128),Math.floorDiv(p[i][2],128));boolean duplicate=false;for(int j=0;j<n;j++)duplicate|=result[j]==b;if(!duplicate)result[n++]=b;}return Arrays.copyOf(result,n);
    }
    private void index(int id,Key key,boolean add){for(long b:memberships(key)){var ids=buckets.get(b);if(add){if(ids==null){ids=new it.unimi.dsi.fastutil.ints.IntOpenHashSet();buckets.put(b,ids);}ids.add(id);}else if(ids!=null){ids.remove(id);if(ids.isEmpty())buckets.remove(b);}}}
    public synchronized List<Lease> reserve(List<Key> keys){
        if(keys.size()>capacity)throw new IllegalArgumentException("One owner batch exceeds cache capacity");var pinned=new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        for(var k:keys){int slot=slot(k);if(table[slot]!=0)pinned.add(table[slot]-1);}
        var result=new ArrayList<Lease>(keys.size());
        for(var k:keys){int at=slot(k),id;boolean created=table[at]==0;
            if(!created)id=table[at]-1;else{
                if(size<capacity)id=size++;else{while(pinned.contains(victim))victim=(victim+1)%capacity;id=victim;victim=(victim+1)%capacity;var old=key(id);index(id,old,false);removeIndex(old);at=slot(k);}
                int p=id*8;words[p]=k.minX();words[p+1]=k.minY();words[p+2]=k.minZ();words[p+3]=k.maxX();words[p+4]=k.maxY();words[p+5]=k.maxZ();words[p+6]=k.bottom();words[p+7]=0;versions[id]=++clock;table[at]=id+1;index(id,k,true);minimumFaceY=Math.min(minimumFaceY,(long)k.minY()-1);
            }pinned.add(id);result.add(new Lease(id,created,k,versions[id]));
        }return List.copyOf(result);
    }
    public synchronized void publish(Lease lease,int sky,int prefixMask,boolean direct){
        if(sky<0||sky>15||prefixMask<0||prefixMask>63)throw new IllegalArgumentException("Valid exact result required");
        int id=lease.id();if(id<0||id>=size||versions[id]!=lease.generation()||!matches(id,lease.key()))throw new IllegalStateException("Retired owner lease cannot publish");
        words[id*8+7]=sky|(prefixMask<<4)|(direct?DIRECT:0)|VALID;
    }
    public synchronized OptionalInt sky(Key key){int at=slot(key);if(table[at]==0)return OptionalInt.empty();int bits=words[(table[at]-1)*8+7];return(bits&VALID)==0?OptionalInt.empty():OptionalInt.of(bits&15);}
    public synchronized int[] record(int id){Objects.checkIndex(id,size);return Arrays.copyOfRange(words,id*8,id*8+8);}
    public synchronized void invalidate(int[] ids){for(int id:ids){Objects.checkIndex(id,size);words[id*8+7]&=~VALID;versions[id]=++clock;}}
    private void collect(long x0,long y0,long z0,long x1,long y1,long z1,boolean prefix,it.unimi.dsi.fastutil.ints.IntOpenHashSet out){
        long a=Math.floorDiv(x0,128),b=Math.floorDiv(y0,128),c=Math.floorDiv(z0,128),d=Math.floorDiv(x1,128),e=Math.floorDiv(y1,128),f=Math.floorDiv(z1,128);
        if(a>d||b>e||c>f)return;
        if((double)(d-a+1)*(e-b+1)*(f-c+1)>1_000_000){for(int id=0;id<size;id++)if((words[id*8+7]&VALID)!=0&&(!prefix||(words[id*8+7]&0x3f0)!=0))out.add(id);return;}
        for(long y=b;y<=e;y++)for(long z=c;z<=f;z++)for(long x=a;x<=d;x++){var set=buckets.get(bucket(x,y,z));if(set==null)continue;for(int id:set)if((words[id*8+7]&VALID)!=0&&(!prefix||(words[id*8+7]&0x3f0)!=0))out.add(id);}
    }
    public synchronized int[] candidates(List<Q30EditDependencies.Box> changes,List<Q30EditDependencies.Point> nodes,List<Q30EditDependencies.Direction> rays,int skyY){
        var result=new it.unimi.dsi.fastutil.ints.IntOpenHashSet();if(size==0)return new int[0];
        for(var q:nodes)collect((long)q.x()-8,(long)q.y()-8,(long)q.z()-8,(long)q.x()+8,(long)q.y()+8,(long)q.z()+8,false,result);
        for(var b:changes){
            collect(b.minX()-8,b.minY()-8,b.minZ()-8,b.maxX()+8,b.maxY()+8,b.maxZ()+8,false,result);
            collect(b.minX(),minimumFaceY,b.minZ(),b.maxX()-1,Math.min((long)skyY,b.maxY())-1,b.maxZ()-1,false,result);
            long low=Math.floorDiv(minimumFaceY,128),high=Math.floorDiv(Math.min((long)skyY,b.maxY())-1,128);
            for(var ray:rays)for(long by=low;by<=high;by++){
                double first=by*128.0,last=first+127,near=Math.max(0,(b.minY()-(last+.5))/ray.y()),far=(Math.min((long)skyY,b.maxY())-(first+.5))/ray.y();if(far<=0)continue;
                double ax=ray.x()*near,bx=ray.x()*far,az=ray.z()*near,bz=ray.z()*far;
                collect((long)Math.floor(b.minX()-Math.max(ax,bx)-2),by*128,(long)Math.floor(b.minZ()-Math.max(az,bz)-2),(long)Math.ceil(b.maxX()-Math.min(ax,bx)+2),by*128+127,(long)Math.ceil(b.maxZ()-Math.min(az,bz)+2),true,result);
            }
        }int[] ids=result.toIntArray();Arrays.sort(ids);return ids;
    }
    public synchronized void clear(){Arrays.fill(table,0);buckets.clear();size=victim=0;minimumFaceY=Long.MAX_VALUE;clock++;}
    public synchronized int size(){return size;}
}
