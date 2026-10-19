package dev.madeinabyss.kernel.terrain.living;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LivingAbyssRevision153Test {
    @Test void rimFormulaWeightsMayTotalNinetyFiveWithoutBeingRenormalizedAsInput() {
        var p=LivingAbyssWallTest.recipe(20261003).wall();
        var palettes=new java.util.ArrayList<>(p.palettes());
        var rim=new java.util.LinkedHashMap<String,Double>();
        rim.put("stone",36.0);rim.put("andesite",32.0);rim.put("calcite",6.0);rim.put("dripstone_block",7.0);rim.put("white_terracotta",2.0);rim.put("mossy_cobblestone",12.0);palettes.set(0,rim);
        assertDoesNotThrow(()->new LivingAbyssRecipe.Wall(6,20,1.2,18,8,3.5,60,8,90,10,5,16,30,34,10,9,5,palettes));
    }
    @Test void connectedWallPatchesReplaceTheNearFieldCheckerboard() {
        var r=LivingAbyssWallTest.recipe(20261003);
        var wall=new LivingAbyssWall(r.seed(),650,r.wall());
        int same=0,total=0;
        for(int x=-100;x<=100;x+=4)for(int y=-420;y<-150;y++){
            if(wall.material(1,x,y,-650).equals(wall.material(1,x,y-1,-650)))same++;
            total++;
        }
        assertTrue(same/(double)total>.65,"adjacent vertical blocks must form connected 3x8m patches: "+same+"/"+total);
    }
}
