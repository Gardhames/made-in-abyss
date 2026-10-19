package dev.madeinabyss.kernel.terrain.natural;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NaturalProtectionTest {
    private boolean brute(List<NaturalProtection.Box> boxes,int x,int y,int z) {
        for(var b:boxes)if(x>=b.minX()&&x<=b.maxX()&&y>=b.minY()&&y<=b.maxY()&&z>=b.minZ()&&z<=b.maxZ())return true;
        return false;
    }
    @Test void indexedRectanglesExactlyMatchBruteForceAcrossSignedCellsAndInclusiveEdges() {
        var boxes=new ArrayList<NaturalProtection.Box>();
        boxes.add(new NaturalProtection.Box(-17,-40,-33,-1,-20,1));
        boxes.add(new NaturalProtection.Box(0,-16,-16,16,16,0));
        boxes.add(new NaturalProtection.Box(-101,-140,81,-78,-120,99));
        var random=new Random(827);for(int i=0;i<452;i++){
            int x=random.nextInt(2000)-1000,y=random.nextInt(1500)-1300,z=random.nextInt(2000)-1000;
            boxes.add(new NaturalProtection.Box(x-2L,y-5L,z-2L,x+2L,y+12L,z+2L));
        }
        var index=new NaturalProtection(boxes);
        for(var b:boxes)for(long x:new long[]{b.minX()-1,b.minX(),b.maxX(),b.maxX()+1})
            for(long y:new long[]{b.minY()-1,b.minY(),b.maxY(),b.maxY()+1})for(long z:new long[]{b.minZ()-1,b.minZ(),b.maxZ(),b.maxZ()+1})
                assertEquals(brute(boxes,(int)x,(int)y,(int)z),index.contains((int)x,(int)y,(int)z));
        for(int i=0;i<10000;i++){
            int x=random.nextInt(2200)-1100,y=random.nextInt(1700)-1400,z=random.nextInt(2200)-1100;
            assertEquals(brute(boxes,x,y,z),index.contains(x,y,z));
        }
        assertTrue(index.indexedCellCount()<3000,"bounded index scales with small protected footprints");
    }
    @Test void longExpandedEdgesClipWithoutWrappingOrAliasing() {
        var boxes=List.of(new NaturalProtection.Box((long)Integer.MIN_VALUE-12,-8,(long)Integer.MIN_VALUE-12,(long)Integer.MIN_VALUE+12,8,(long)Integer.MIN_VALUE+12),
                new NaturalProtection.Box((long)Integer.MAX_VALUE-12,-8,(long)Integer.MAX_VALUE-12,(long)Integer.MAX_VALUE+12,8,(long)Integer.MAX_VALUE+12),
                new NaturalProtection.Box(Long.MIN_VALUE,Long.MIN_VALUE,100,Long.MIN_VALUE+1,Long.MAX_VALUE,101));
        var index=new NaturalProtection(boxes);
        assertTrue(index.contains(Integer.MIN_VALUE,0,Integer.MIN_VALUE));
        assertTrue(index.contains(Integer.MAX_VALUE,0,Integer.MAX_VALUE));
        assertFalse(index.contains(0,0,0));assertFalse(index.contains(Integer.MIN_VALUE,9,Integer.MIN_VALUE));
        assertFalse(index.contains(Integer.MIN_VALUE,0,Integer.MAX_VALUE));
    }
    @Test void emptyImmutableAndOversizedPlansHaveExplicitBehaviour() {
        assertFalse(new NaturalProtection(List.of()).contains(0,0,0));
        var mutable=new ArrayList<NaturalProtection.Box>();mutable.add(new NaturalProtection.Box(-1,-1,-1,1,1,1));
        var index=new NaturalProtection(mutable);mutable.clear();assertTrue(index.contains(0,0,0));
        assertThrows(IllegalArgumentException.class,()->new NaturalProtection.Box(1,0,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new NaturalProtection(List.of(new NaturalProtection.Box(Long.MIN_VALUE,-1,Long.MIN_VALUE,Long.MAX_VALUE,1,Long.MAX_VALUE))));
        var repeated=Collections.nCopies(NaturalProtection.MAX_BOXES+1,new NaturalProtection.Box(0,0,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new NaturalProtection(repeated));
    }
}
