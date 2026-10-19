package dev.madeinabyss.kernel.terrain.living;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.*;

/** Bridson placement (20 attempts) over an exact exposed-column mask. Signed grid cells. */
public final class LivingAbyssPoisson {
    private LivingAbyssPoisson(){}
    public static List<int[]> place(List<int[]> allowed,double radius,long seed,int limit){
        if(!(radius>0)||limit<1)throw new IllegalArgumentException("positive spacing and limit");if(allowed.isEmpty())return List.of();
        // Primitive-keyed maps: Long.hashCode of a packed (x,z) is x^z, which chains tens of thousands of cells together.
        var mask=new Long2ObjectOpenHashMap<int[]>(allowed.size()*2);for(var p:allowed)mask.put(key(p[0],p[2]),p);
        double cell=radius/Math.sqrt(2);var grid=new Long2ObjectOpenHashMap<List<int[]>>();var points=new ArrayList<int[]>();var active=new ArrayList<int[]>();var rng=new SplittableRandom(seed);
        int restarts=0;
        while(points.size()<limit){
            if(active.isEmpty()){
                boolean found=false;for(int n=0;n<Math.min(allowed.size(),512);n++){var q=allowed.get(rng.nextInt(allowed.size()));if(accept(q,radius,cell,grid)){admit(q,cell,points,active,grid);found=true;break;}}
                if(!found||restarts++>allowed.size()/4+8)break;
            }
            int index=rng.nextInt(active.size());var p=active.get(index);boolean found=false;
            for(int i=0;i<20;i++){double a=rng.nextDouble()*2*Math.PI,d=radius*(1+rng.nextDouble());int x=(int)Math.floor(p[0]+Math.cos(a)*d+.5),z=(int)Math.floor(p[2]+Math.sin(a)*d+.5);var q=mask.get(key(x,z));
                if(q!=null&&accept(q,radius,cell,grid)){admit(q,cell,points,active,grid);found=true;break;}}
            if(!found)active.remove(index);
        }
        return points.stream().map(int[]::clone).toList();
    }
    private static boolean accept(int[] q,double radius,double cell,Long2ObjectOpenHashMap<List<int[]>> grid){int cx=(int)Math.floor(q[0]/cell),cz=(int)Math.floor(q[2]/cell);
        for(int x=cx-2;x<=cx+2;x++)for(int z=cz-2;z<=cz+2;z++){var cellPoints=grid.get(key(x,z));if(cellPoints==null)continue;
            for(int i=0,n=cellPoints.size();i<n;i++){var p=cellPoints.get(i);double dx=q[0]-p[0],dz=q[2]-p[2];if(Math.sqrt(dx*dx+dz*dz)<radius)return false;}}return true;}
    private static void admit(int[] q,double cell,List<int[]> points,List<int[]> active,Long2ObjectOpenHashMap<List<int[]>> grid){var copy=q.clone();points.add(copy);active.add(copy);grid.computeIfAbsent(key((int)Math.floor(q[0]/cell),(int)Math.floor(q[2]/cell)),k->new ArrayList<>()).add(copy);}
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
}
