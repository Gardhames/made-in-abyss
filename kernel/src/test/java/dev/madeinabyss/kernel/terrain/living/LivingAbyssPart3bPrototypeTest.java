package dev.madeinabyss.kernel.terrain.living;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Literal section17/18/22/23 boundaries; reflection keeps RED a missing-behaviour assertion. */
class LivingAbyssPart3bPrototypeTest {
    private static Class<?> type(String name){return assertDoesNotThrow(()->Class.forName("dev.madeinabyss.kernel.terrain.living."+name),"part3b prototype port is missing");}
    private static double call(String name,String method,double... args)throws Exception{
        var types=new Class<?>[args.length];Arrays.fill(types,double.class);var values=new Object[args.length];for(int i=0;i<args.length;i++)values[i]=args[i];
        return (double)type(name).getMethod(method,types).invoke(null,values);
    }
    @Test void pondHasSevenMetreBasinHalfMetreShelfAndNoWaterOutsideRim()throws Exception{
        assertEquals(7,call("LivingAbyssWaterShape","depth",0,15,7,3,.5),1e-12);
        assertEquals(.6,call("LivingAbyssWaterShape","depth",.9,15,7,3,.5),1e-12);
        assertEquals(0,call("LivingAbyssWaterShape","depth",1.1,15,7,3,.5),1e-12);
    }
    @Test void wallStreamNeverStepsIntoRockAndDetachesUnderOverhang()throws Exception{
        assertEquals(14,call("LivingAbyssWaterShape","outwardWater",12,13),0);
        assertEquals(14,call("LivingAbyssWaterShape","outwardWater",14,9),0);
        assertEquals(-8,call("LivingAbyssWaterShape","outwardWater",-12,-9),0);
    }
    @Test void section23LimitsBareTopAndReplacesDeepVisibleSoil()throws Exception{
        var c=type("LivingAbyssSurfaceMaterials");var top=c.getMethod("top",double.class,double.class);
        assertEquals("COARSE_DIRT",top.invoke(null,.049,.1).toString());
        assertEquals("ANDESITE",top.invoke(null,.06,.1).toString());
        assertEquals("BOULDER_STONE",top.invoke(null,.06,.6).toString());
        assertEquals("MOSSY_COBBLESTONE",top.invoke(null,.06,.9).toString());
        assertEquals("GRASS",top.invoke(null,.1,.1).toString());
        var side=c.getMethod("side",int.class,boolean.class,double.class);
        assertEquals("SOIL",side.invoke(null,1,true,.1).toString());
        assertEquals("BODY_ROCK",side.invoke(null,2,true,.1).toString());
        assertEquals("MOSSY_COBBLESTONE",side.invoke(null,2,true,.7).toString());
        assertEquals("MOSS",side.invoke(null,2,true,.9).toString());
        assertEquals("SOIL",side.invoke(null,2,false,.9).toString());
    }
    @Test void normalizedPatchMatchesIndependentSignedNumpyOracleAcrossCubeSeams()throws Exception{
        var method=type("LivingAbyssSurfaceMaterials").getMethod("patch",long.class,int.class,int.class);
        try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/terrain/living/surface-154-oracle.tsv")),java.nio.charset.StandardCharsets.UTF_8))){
            int count=0;String line;while((line=reader.readLine())!=null){if(line.startsWith("#"))continue;var s=line.split("\t");
                assertEquals(Double.parseDouble(s[3]),(double)method.invoke(null,Long.parseLong(s[0]),Integer.parseInt(s[1]),Integer.parseInt(s[2])),1e-14,line);count++;}
            assertTrue(count>10000,"signed lattice and seam fixture must be nonempty");
        }
    }
    @Test void bottleFlareAndBasalFlareRetainPrototypeDimensions()throws Exception{
        assertEquals(15,call("LivingAbyssSpireShape","profile",10,0,120,.1,.45),1e-12);
        assertEquals(13.05,call("LivingAbyssSpireShape","profile",10,120,120,.1,.45),1e-12);
        assertEquals(9.18,call("LivingAbyssSpireShape","profile",10,98.4,120,.1,.45),1e-12);
    }
    @Test void allSevenSpiresRemainFaceConnectedThroughJointsTwinPeaksAndLeaningSeams()throws Exception{
        var constructor=type("LivingAbyssSpireShape").getConstructor(String.class,long.class,double.class,int.class);
        record P(int x,int y,int z){}
        for(String kind:List.of("column","tapered","bottle","stacked","leaning","twin","needle")){
            Object shape=constructor.newInstance(kind,-20261003L,kind.equals("needle")?6.0:12.0,100);
            var voxels=(LivingAbyssVoxelPlan)shape.getClass().getMethod("voxels").invoke(shape);
            var cells=new HashSet<P>();voxels.visit((x,y,z,b)->cells.add(new P(x,y,z)));
            assertTrue(cells.size()>4000,kind+" retains a substantial stone body");
            assertTrue(cells.contains(new P(0,0,0)),kind+" grounded central anchor");
            var queue=new ArrayDeque<P>();queue.add(new P(0,0,0));var seen=new HashSet<P>();seen.add(queue.peek());
            while(!queue.isEmpty()){var p=queue.removeFirst();for(int[] d:new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}){var q=new P(p.x+d[0],p.y+d[1],p.z+d[2]);if(cells.contains(q)&&seen.add(q))queue.add(q);}}
            assertEquals(cells.size(),seen.size(),kind+" no floating stone islands across joints");
        }
    }
}
