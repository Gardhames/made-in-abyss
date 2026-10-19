package dev.madeinabyss.kernel.terrain.living;
import dev.madeinabyss.kernel.terrain.natural.NaturalProtection.Box;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LivingAbyssReservationsTest {
    @Test void columnMasksMatchIndependentSignedAabbUnionAcrossCubeSeams(){
        var random=new Random(1505);var boxes=new ArrayList<Box>();
        for(int i=0;i<120;i++){int x=random.nextInt(130)-65,y=random.nextInt(100)-80,z=random.nextInt(130)-65;boxes.add(new Box(x,y,z,x+random.nextInt(20),y+random.nextInt(18),z+random.nextInt(20)));}
        boxes.add(new Box(-17,-33,-1,-16,-16,0));var index=new LivingAbyssReservations(boxes);int hits=0,misses=0;
        for(int cx=-5;cx<=4;cx++)for(int cy=-6;cy<=1;cy++)for(int cz=-5;cz<=4;cz++){
            int x=cx*16,y=cy*16,z=cz*16;var mask=new short[256];boolean intersects=index.fillCubeMask(x,y,z,mask),any=false;
            for(int i=0;i<128;i++){int xx=x+random.nextInt(16),yy=y+random.nextInt(16),zz=z+random.nextInt(16);
                boolean expected=boxes.stream().anyMatch(b->xx>=b.minX()&&xx<=b.maxX()&&yy>=b.minY()&&yy<=b.maxY()&&zz>=b.minZ()&&zz<=b.maxZ());
                assertEquals(expected,index.contains(xx,yy,zz));assertEquals(expected,(mask[(zz-z)*16+xx-x]&(1<<(yy-y)))!=0);any|=expected;if(expected)hits++;else misses++;}
            if(any)assertTrue(intersects);
        }assertTrue(hits>1000&&misses>1000);
    }
    @Test void fullSignedEndpointsClipWithoutWrappingAndOversizedPlansRefuse(){
        var index=new LivingAbyssReservations(List.of(new Box((long)Integer.MIN_VALUE-4,Integer.MIN_VALUE,Integer.MIN_VALUE,(long)Integer.MIN_VALUE+15,(long)Integer.MIN_VALUE+15,(long)Integer.MIN_VALUE+15),
            new Box((long)Integer.MAX_VALUE-15,(long)Integer.MAX_VALUE-15,(long)Integer.MAX_VALUE-15,(long)Integer.MAX_VALUE+5,(long)Integer.MAX_VALUE+5,(long)Integer.MAX_VALUE+5)));
        assertTrue(index.contains(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE));assertTrue(index.contains(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));assertFalse(index.contains(0,0,0));
        var mask=new short[256];index.fillCubeMask(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,mask);for(short bits:mask)assertEquals((short)-1,bits);
        index.fillCubeMask(Integer.MAX_VALUE-15,Integer.MAX_VALUE-15,Integer.MAX_VALUE-15,mask);for(short bits:mask)assertEquals((short)-1,bits);
        assertThrows(IllegalArgumentException.class,()->new LivingAbyssReservations(List.of(new Box(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE))));
    }
}
