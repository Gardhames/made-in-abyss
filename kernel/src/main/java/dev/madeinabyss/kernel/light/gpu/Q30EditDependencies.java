package dev.madeinabyss.kernel.light.gpu;

import java.util.List;

/** Edit-local inverse queries. They do not evaluate SKY or call a voxel sampler. */
public final class Q30EditDependencies {
    public record Point(int x,int y,int z){}
    public record Direction(long x,long y,long z){}
    public record Box(long minX,long minY,long minZ,long maxX,long maxY,long maxZ){}
    public record Grid(int minX,int minY,int minZ,int sizeX,int sizeY,int sizeZ){
        public Grid{if(sizeX<1||sizeY<1||sizeZ<1)throw new IllegalArgumentException("Positive lattice required");Math.addExact(minX,Math.multiplyExact(sizeX-1,8));Math.addExact(minY,Math.multiplyExact(sizeY-1,8));Math.addExact(minZ,Math.multiplyExact(sizeZ-1,8));Math.multiplyExact(Math.multiplyExact(sizeX,sizeY),sizeZ);}
        public int count(){return Math.multiplyExact(Math.multiplyExact(sizeX,sizeY),sizeZ);}
        public Point point(int index){return new Point(minX+index%sizeX*8,minY+index/(sizeX*sizeZ)*8,minZ+(index/sizeX)%sizeZ*8);}
    }
    private Q30EditDependencies(){}
    private static final List<Direction> CANONICAL=canonical();
    private static List<Direction> canonical(){var result=new java.util.ArrayList<Direction>();double angle=Math.PI*(3-Math.sqrt(5));long q=1L<<30;for(int i=0;i<32;i++){double y=Math.sqrt((i+.5)/32),r=Math.sqrt(1-y*y),x=r*Math.cos(i*angle),z=r*Math.sin(i*angle);result.add(new Direction((x<0?-1:1)*Math.round(Math.abs(x)*q),Math.round(y*q),(z<0?-1:1)*Math.round(Math.abs(z)*q)));}return List.copyOf(result);}
    public static List<Direction> canonicalDirections(){return CANONICAL;}
    private record Ratio(long numerator,long denominator) implements Comparable<Ratio>{
        public int compareTo(Ratio other){try{return Long.compare(Math.multiplyExact(numerator,other.denominator),Math.multiplyExact(other.numerator,denominator));}
            catch(ArithmeticException overflow){return java.math.BigInteger.valueOf(numerator).multiply(java.math.BigInteger.valueOf(other.denominator)).compareTo(java.math.BigInteger.valueOf(other.numerator).multiply(java.math.BigInteger.valueOf(denominator)));}}
    }
    private static void validate(Direction d){long q=1L<<30;if(d.y()<=0||d.y()>q||d.x()< -q||d.x()>q||d.z()< -q||d.z()>q)throw new IllegalArgumentException("Upward Q30 direction required");}
    public static boolean rayCrosses(Point start,Direction direction,Box changed,int skyY){
        validate(direction);if(changed.minX()>=changed.maxX()||changed.minY()>=changed.maxY()||changed.minZ()>=changed.maxZ())throw new IllegalArgumentException("Positive changed voxel region required");
        if(start.y()>=skyY||changed.minY()>=skyY)return false;
        long[] p={start.x(),start.y(),start.z()},d={direction.x(),direction.y(),direction.z()},min={changed.minX(),changed.minY(),changed.minZ()},max={changed.maxX(),Math.min(changed.maxY(),skyY),changed.maxZ()};
        Ratio lo=new Ratio(0,1),hi=null;
        for(int a=0;a<3;a++){
            if(d[a]==0){if(p[a]<min[a]||p[a]>=max[a])return false;continue;}
            long lower=d[a]>0?Math.subtractExact(Math.multiplyExact(Math.subtractExact(min[a],p[a]),2),1):Math.addExact(Math.multiplyExact(Math.subtractExact(p[a],max[a]),2),1);
            long upper=d[a]>0?Math.subtractExact(Math.multiplyExact(Math.subtractExact(max[a],p[a]),2),1):Math.addExact(Math.multiplyExact(Math.subtractExact(p[a],min[a]),2),1);
            var enter=new Ratio(lower,Math.abs(d[a]));var exit=new Ratio(upper,Math.abs(d[a]));if(enter.compareTo(lo)>0)lo=enter;if(hi==null||exit.compareTo(hi)<0)hi=exit;if(lo.compareTo(hi)>=0)return false;
        }
        return true;
    }
    public static int[] affectedNodes(Grid grid,List<Box> changes,List<Direction> directions,int skyY){
        // Enumerate narrow reverse cones one lattice Y plane at a time. Floating
        // bounds are deliberately padded; the final all-axis tie test is integer exact.
        var result=new java.util.TreeSet<Integer>();
        for(var direction:directions){validate(direction);for(var b:changes){
            int top=(int)Math.min(grid.sizeY()-1,Math.floorDiv(Math.min(b.maxY(),(long)skyY)-1-grid.minY(),8));
            for(int iy=0;iy<=top;iy++){
                int y=grid.minY()+iy*8;double near=Math.max(0,(b.minY()-(y+.5))/direction.y()),far=(Math.min(b.maxY(),(long)skyY)-(y+.5))/direction.y();if(far<=0)continue;
                double ax=direction.x()*near,bx=direction.x()*far,az=direction.z()*near,bz=direction.z()*far;
                double x0=b.minX()-Math.max(ax,bx)-2,x1=b.maxX()-Math.min(ax,bx)+2,z0=b.minZ()-Math.max(az,bz)-2,z1=b.maxZ()-Math.min(az,bz)+2;
                if(x1<grid.minX()||x0>grid.minX()+(grid.sizeX()-1)*8||z1<grid.minZ()||z0>grid.minZ()+(grid.sizeZ()-1)*8)continue;
                int lx=Math.max(0,(int)Math.floor((Math.max(x0,grid.minX())-grid.minX())/8)),hx=Math.min(grid.sizeX()-1,(int)Math.ceil((Math.min(x1,grid.minX()+(grid.sizeX()-1)*8)-grid.minX())/8));
                int lz=Math.max(0,(int)Math.floor((Math.max(z0,grid.minZ())-grid.minZ())/8)),hz=Math.min(grid.sizeZ()-1,(int)Math.ceil((Math.min(z1,grid.minZ()+(grid.sizeZ()-1)*8)-grid.minZ())/8));
                for(int iz=lz;iz<=hz;iz++)for(int ix=lx;ix<=hx;ix++){int index=(iy*grid.sizeZ()+iz)*grid.sizeX()+ix;if(!result.contains(index)&&rayCrosses(grid.point(index),direction,b,skyY))result.add(index);}
            }
        }}return result.stream().mapToInt(Integer::intValue).toArray();
    }
}
