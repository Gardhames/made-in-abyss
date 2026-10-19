package dev.madeinabyss.kernel.terrain.living;

import dev.madeinabyss.kernel.terrain.CompiledTerrainSampler;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.function.IntToDoubleFunction;

/** Standalone timing main, deliberately not a JUnit test. See the T1 report for the exact invocation. */
public final class LivingAbyssWallMicrobenchmark {
    private static volatile long sink;
    private static final double[] BOUNDARIES={120,450,800,1150};
    private record Points(int[] x,int[] y,int[] z,double[] theta,int[] zone) {int size(){return x.length;}}
    private static Points points(boolean cubeScan,CompiledTerrainSampler radius,LivingAbyssFields fields) {
        int n=65_536;var x=new int[n];var y=new int[n];var z=new int[n];var theta=new double[n];var zones=new int[n];
        var random=new Random(151515);int sx=0,sy=0,sz=0;
        for(int i=0;i<n;i++) {
            if(cubeScan) {
                if((i&4095)==0) {
                    double angle=random.nextDouble()*Math.PI*2;int yy=-20-random.nextInt(1320);
                    int xx=(int)Math.round(650*Math.sin(angle)),zz=(int)Math.round(-650*Math.cos(angle));
                    double r=radius.cavityRadius(xx,yy,zz)-8;
                    sx=Math.floorDiv((int)Math.round(r*Math.sin(angle)),16)*16;
                    sz=Math.floorDiv((int)Math.round(-r*Math.cos(angle)),16)*16;sy=Math.floorDiv(yy,16)*16;
                }
                x[i]=sx+(i&15);z[i]=sz+((i>>>4)&15);y[i]=sy+((i>>>8)&15);
            } else {
                double angle=random.nextDouble()*Math.PI*2;y[i]=-1-random.nextInt(1349);
                int xx=(int)Math.round(650*Math.sin(angle)),zz=(int)Math.round(-650*Math.cos(angle));
                double r=radius.cavityRadius(xx,y[i],zz)+random.nextInt(49)-24;
                x[i]=(int)Math.round(r*Math.sin(angle));z[i]=(int)Math.round(-r*Math.cos(angle));
            }
            theta[i]=LivingAbyssFields.azimuth(x[i],z[i]);zones[i]=fields.zoneAtDepth(-((double)y[i]),theta[i]);
        }
        return new Points(x,y,z,theta,zones);
    }
    private static long run(IntToDoubleFunction operation,int count,int repeats) {
        long sum=0,start=System.nanoTime();
        for(int rep=0;rep<repeats;rep++)for(int i=0;i<count;i++)sum+=Double.doubleToRawLongBits(operation.applyAsDouble(i));
        long elapsed=System.nanoTime()-start;sink=sum;return elapsed;
    }
    private static void compare(String pattern,String name,int n,IntToDoubleFunction old,IntToDoubleFunction fast) {
        for(int i=0;i<n;i++)if(Double.doubleToRawLongBits(old.applyAsDouble(i))!=Double.doubleToRawLongBits(fast.applyAsDouble(i)))
            throw new AssertionError("benchmark inputs disagree: "+pattern+" "+name+" "+i);
        for(int warm=0;warm<3;warm++){run(old,n,4);run(fast,n,4);}
        double[] before=new double[7],after=new double[7];int repeats=8;
        for(int round=0;round<7;round++) {
            long a,b;if((round&1)==0){a=run(old,n,repeats);b=run(fast,n,repeats);}else{b=run(fast,n,repeats);a=run(old,n,repeats);}
            before[round]=(double)a/(n*repeats);after[round]=(double)b/(n*repeats);
            System.out.printf(Locale.ROOT,"ROUND,%s,%s,%d,%.3f,%.3f%n",pattern,name,round,before[round],after[round]);
        }
        Arrays.sort(before);Arrays.sort(after);
        System.out.printf(Locale.ROOT,"MEDIAN,%s,%s,%d,%.3f,%.3f,%.4f%n",pattern,name,n,before[3],after[3],before[3]/after[3]);
    }
    private static int oldZone(long seed,double depth,double theta) {
        for(int i=0;i<4;i++) {
            double phase=(LivingAbyssFields.mix(seed+i*0x9e3779b97f4a7c15L)>>>11)*0x1.0p-53*2*Math.PI;
            double a=Math.toRadians(LivingAbyssFields.normalize(theta));
            if(depth<BOUNDARIES[i]+25*(.65*Math.sin(3*a+phase)+.35*Math.sin(7*a-phase)))return i;
        }return 4;
    }
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);long seed=20261003;
        System.out.println("java="+System.getProperty("java.runtime.version")+" vm="+System.getProperty("java.vm.name"));
        System.out.println("# 3 paired warmups, 7 alternating paired rounds, 524288 calls/round; medians ns/call");
        for(boolean cubes:new boolean[]{true,false}) {
            var p=LivingAbyssWallRadiusTest.parameters(seed);var recipe=LivingAbyssWallTest.recipe(seed);
            var oldRadius=new CompiledTerrainSampler(p,16384);var radius=new LivingAbyssWallRadius(p);
            var oldWall=new LivingAbyssWallScalarReference(seed,650,recipe.wall());var wall=new LivingAbyssWall(seed,650,recipe.wall());
            var fields=new LivingAbyssFields(seed,20,new double[]{120,450,800,1150,1350},25,22.5,45);
            var points=points(cubes,oldRadius,fields);String pattern=cubes?"cube-scan":"lod-shell";int n=points.size();
            compare(pattern,"protrusion",n,i->oldWall.protrusion(points.theta[i],-((double)points.y[i])),i->wall.protrusion(points.theta[i],-((double)points.y[i])));
            compare(pattern,"wallRadius",n,i->oldRadius.cavityRadius(points.x[i],points.y[i],points.z[i]),i->radius.cavityRadius(points.x[i],points.y[i],points.z[i]));
            compare(pattern,"material",n,i->oldWall.material(points.zone[i],points.theta[i],points.x[i],points.y[i],points.z[i]).hashCode(),
                    i->wall.material(points.zone[i],points.theta[i],points.x[i],points.y[i],points.z[i]).hashCode());
            compare(pattern,"zoneAtDepth",n,i->oldZone(seed,-((double)points.y[i]),points.theta[i]),i->fields.zoneAtDepth(-((double)points.y[i]),points.theta[i]));
            compare(pattern,"zone+material",n,
                    i->oldWall.material(oldZone(seed,-((double)points.y[i]),points.theta[i]),points.theta[i],points.x[i],points.y[i],points.z[i]).hashCode(),
                    i->wall.material(fields.zoneAtDepth(-((double)points.y[i]),points.theta[i]),points.theta[i],points.x[i],points.y[i],points.z[i]).hashCode());
        }
        System.out.println("sink="+sink);
    }
}
