package dev.madeinabyss.kernel.terrain.living;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LivingAbyssMossMixTest {
    @Test void coatingMixOnlyPutsCarpetOnSupportedUpperFaces()throws Exception{
        var method=assertDoesNotThrow(()->LivingAbyssGreenPlan.class.getMethod("mossCoating",long.class,int.class,int.class,int.class,boolean.class));
        int moss=0,rock=0,carpet=0;
        for(int x=-30000;x<30000;x++){
            int top=(int)method.invoke(null,-20261003L,x,-337,-641,true),wall=(int)method.invoke(null,-20261003L,x,-337,-641,false);
            if(top==LivingAbyssFeatureBlock.MOSS.ordinal())moss++;else if(top==LivingAbyssFeatureBlock.MOSSY_COBBLESTONE.ordinal())rock++;else if(top==LivingAbyssFeatureBlock.MOSS_CARPET.ordinal())carpet++;else fail("outside approved moss mix");
            assertNotEquals(LivingAbyssFeatureBlock.MOSS_CARPET.ordinal(),wall,"carpet cannot stand on a vertical face");
        }
        assertTrue(moss>33000&&moss<39000);assertTrue(rock>12000&&rock<18000);assertTrue(carpet>6000&&carpet<12000);
    }
}
