package dev.madeinabyss.kernel.terrain.living;

import java.util.*;
import java.io.*;
import java.security.*;

/** Independent implementation of deterministic D8 steepest descent on a diagnostic DEM copy.
 * Priority-flood epsilon filling resolves pits/flats; the approved elevation asset is never edited.
 * Shaft, ocean and external boundary sinks are accounted separately. No third-party code copied. */
public final class D8Hydrology {
    private D8Hydrology(){}
    public record Outlet(int x,int z,double area){}
    public record Wet(double degrees,String reason){}
    public record Plan(double wetDegrees,String reason,List<Outlet> outlets,double shaftArea,double oceanArea,
                       double boundaryArea,int filledCells,String digest,long buildNanos,long estimatedBytes) {
        public Plan {outlets=List.copyOf(outlets);}
    }
    private record Cell(int index,double height){}
    public static Wet selectWet(List<Outlet> outlets,double fallback) {
        if(outlets.isEmpty())return new Wet(fallback,"NO_SHAFT_INFLOW");
        double max=outlets.stream().mapToDouble(Outlet::area).max().orElseThrow();
        var best=outlets.stream().filter(o->o.area==max).toList();
        return best.size()!=1?new Wet(fallback,"AMBIGUOUS_MAX_SHAFT_INFLOW"):
            new Wet(LivingAbyssFields.azimuth(best.get(0).x,best.get(0).z),"UNIQUE_MAX_D8_SHAFT_INFLOW");
    }
    public static Plan plan(double[] dem,int nx,int nz,int minX,int minZ,int step,double shaftRadius,double fallback) {
        long start=System.nanoTime();
        if(nx<3||nz<3||nx>4096||nz>4096||dem.length!=nx*nz||step<1||!(shaftRadius>0)||!Double.isFinite(fallback)
            ||(long)minX+(long)(nx-1)*step>Integer.MAX_VALUE||(long)minZ+(long)(nz-1)*step>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid DEM grid");
        int n=dem.length;double[] filled=dem.clone(),acc=new double[n];byte[] sink=new byte[n];boolean[] seen=new boolean[n];
        for(double h:dem)if(!Double.isFinite(h))throw new IllegalArgumentException("Nonfinite DEM");
        var heap=new PriorityQueue<Cell>(Comparator.comparingDouble(Cell::height).thenComparingInt(Cell::index));
        for(int x=0;x<nx;x++)for(int z=0;z<nz;z++) {
            int i=x*nz+z;long bx=(long)minX+(long)x*step,bz=(long)minZ+(long)z*step;
            if(Math.hypot((double)bx,(double)bz)<shaftRadius){sink[i]=1;filled[i]=-5000;}
            else if(dem[i]<=0)sink[i]=2;
            else if(x==0||z==0||x==nx-1||z==nz-1)sink[i]=3;
            if(sink[i]!=0){seen[i]=true;heap.add(new Cell(i,filled[i]));}
            // Include all physical raster cells in the accounting, also water/sink area.
            acc[i]=(double)step*step;
        }
        int filledCells=0;int[] order=new int[n];int count=0;
        while(!heap.isEmpty()) {
            var c=heap.remove();int x=c.index/nz,z=c.index%nz;order[count++]=c.index;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                int xx=x+dx,zz=z+dz;if((dx==0&&dz==0)||xx<0||xx>=nx||zz<0||zz>=nz)continue;
                int j=xx*nz+zz;if(seen[j])continue;seen[j]=true;
                double v=Math.max(dem[j],Math.nextUp(c.height));if(v>dem[j])filledCells++;
                filled[j]=v;heap.add(new Cell(j,v));
            }
        }
        int[] next=new int[n];Arrays.fill(next,-1);
        for(int i=0;i<n;i++)if(sink[i]==0) {
            int x=i/nz,z=i%nz;double best=0;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                int xx=x+dx,zz=z+dz;if((dx==0&&dz==0)||xx<0||xx>=nx||zz<0||zz>=nz)continue;
                int j=xx*nz+zz;double slope=(filled[i]-filled[j])/(step*(dx!=0&&dz!=0?Math.sqrt(2):1));
                if(slope>best||(slope>0&&slope==best&&(next[i]<0||j<next[i]))){best=slope;next[i]=j;}
            }
            if(next[i]<0)throw new IllegalStateException("Unresolved D8 flat at "+i);
        }
        // Flood order is nondecreasing. Every strict descending edge points to an earlier cell.
        for(int k=count-1;k>=0;k--){int i=order[k];if(next[i]>=0)acc[next[i]]+=acc[i];}
        double shaft=0,ocean=0,boundary=0;var outlets=new ArrayList<Outlet>();
        for(int i=0;i<n;i++) {
            if(sink[i]==1)shaft+=acc[i];else if(sink[i]==2)ocean+=acc[i];else if(sink[i]==3)boundary+=acc[i];
            if(next[i]>=0&&sink[next[i]]==1)outlets.add(new Outlet(minX+i/nz*step,minZ+i%nz*step,acc[i]));
        }
        outlets.sort(Comparator.comparingDouble(Outlet::area).reversed().thenComparingInt(Outlet::x).thenComparingInt(Outlet::z));
        var wet=selectWet(outlets,fallback);String digest;
        try {
            var md=MessageDigest.getInstance("SHA-256");var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),md));
            out.writeUTF("mia-v15-d8-priority-flood-1");out.writeInt(nx);out.writeInt(nz);out.writeInt(minX);out.writeInt(minZ);out.writeInt(step);out.writeDouble(shaftRadius);
            for(int i=0;i<n;i++){out.writeDouble(dem[i]);out.writeInt(next[i]);out.writeDouble(acc[i]);}
            out.writeDouble(wet.degrees);out.writeUTF(wet.reason);out.flush();digest=HexFormat.of().formatHex(md.digest());
        }catch(IOException|NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        return new Plan(wet.degrees,wet.reason,outlets,shaft,ocean,boundary,filledCells,digest,System.nanoTime()-start,42L*n);
    }
}
