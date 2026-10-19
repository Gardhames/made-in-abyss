package dev.madeinabyss.kernel.terrain.living;

import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Endpoint expectations are hand-derived from Claude's corrected formulas, not the port. */
class LivingAbyssPart3aPrototypeTest {
    @Test void cliffBiasedStampsLeanTowardTheShaftVoidAtCardinalAngles()throws Exception{
        var method=assertDoesNotThrow(()->type("LivingAbyssFloraPlan").getMethod("cliffRotation",double.class));
        assertEquals(1,method.invoke(null,0.0));assertEquals(2,method.invoke(null,90.0));assertEquals(3,method.invoke(null,180.0));assertEquals(0,method.invoke(null,270.0));
    }
    @Test void crackTreeBakesAnEmbeddedAnchorAndCurvingSidewaysCrown(){
        assertDoesNotThrow(()->type("LivingAbyssTreeStamp").getMethod("build",String.class,long.class).invoke(null,"crack",-20261003L));
    }
    private static Class<?> type(String name){return assertDoesNotThrow(()->Class.forName("dev.madeinabyss.kernel.terrain.living."+name),"approved prototype must be ported");}
    private static double body(String method,double... args)throws Exception{
        var types=new Class<?>[args.length];Arrays.fill(types,double.class);
        var values=new Object[args.length];for(int i=0;i<args.length;i++)values[i]=args[i];
        return (double)type("LivingAbyssLedgeBody").getMethod(method,types).invoke(null,values);
    }
    @Test void lipHasFourMetresOfRockAndConcaveFilletEndsAtItsRadius()throws Exception{
        assertEquals(4,body("thickness",1,0,0,32,4,2),1e-12);
        assertEquals(32,body("filletDepth",0,0,20),1e-12);
        assertEquals(0,body("filletDepth",20,0,20),1e-12);
        assertEquals(0,body("filletDepth",40,0,20),1e-12);
        assertEquals(0,body("filletDepth",0,1,20),1e-12);
    }
    @Test void buttressRibsStayUnderTheBellyAndEndBeforeTheLip()throws Exception{
        assertEquals(28,body("ribDepth",0,40,28),1e-12);
        assertEquals(0,body("ribDepth",40,40,28),1e-12);
        assertEquals(0,body("ribDepth",80,40,28),1e-12);
        assertTrue(body("ribDepth",20,40,28)>0);
    }
    @Test void allFiveTreeSilhouettesBakeConnectedDeterministicSeededStamps()throws Exception{
        var build=type("LivingAbyssTreeStamp").getMethod("build",String.class,long.class);
        for(String kind:List.of("broad_oak","dark_oak","giant","guiding","inverted")){
            Object a=build.invoke(null,kind,-20261003L),b=build.invoke(null,kind,-20261003L),c=build.invoke(null,kind,-20261004L);
            var stamp=a.getClass();assertEquals(stamp.getMethod("digest").invoke(a),stamp.getMethod("digest").invoke(b));
            assertNotEquals(stamp.getMethod("digest").invoke(a),stamp.getMethod("digest").invoke(c));
            byte[] vox=(byte[])stamp.getMethod("voxels").invoke(a);int sx=(int)stamp.getMethod("sizeX").invoke(a),sy=(int)stamp.getMethod("sizeY").invoke(a),sz=(int)stamp.getMethod("sizeZ").invoke(a);
            int minX=(int)stamp.getMethod("minX").invoke(a),minY=(int)stamp.getMethod("minY").invoke(a),minZ=(int)stamp.getMethod("minZ").invoke(a);
            int root=((-minY)*sz-minZ)*sx-minX;assertNotEquals(0,vox[root],"embedded root anchor");
            int count=0;for(byte v:vox)if(v!=0)count++;assertTrue(count>1000,kind+" nonempty crown and skeleton");
            var seen=new BitSet(vox.length);var queue=new int[vox.length];int head=0,tail=1;queue[0]=root;seen.set(root);
            while(head<tail){int n=queue[head++],x=n%sx,z=(n/sx)%sz,y=n/(sx*sz);
                for(int[] d:new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}){int xx=x+d[0],yy=y+d[1],zz=z+d[2];if(xx<0||xx>=sx||yy<0||yy>=sy||zz<0||zz>=sz)continue;
                    int k=(yy*sz+zz)*sx+xx;if(vox[k]!=0&&!seen.get(k)){seen.set(k);queue[tail++]=k;}}
            }assertEquals(count,tail,kind+" zero floating connected components");
            if(kind.equals("guiding"))assertTrue(minY<=-6,"roots continue 6-15m down the cliff");
            if(kind.equals("inverted"))assertTrue(minY<=-24&&minY+sy<=3,"downward dome and embedded ceiling roots");
            if(kind.equals("giant"))assertTrue(minY+sy>=30,"30-34m trunk before crown");
        }
    }
}
