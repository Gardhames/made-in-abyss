package dev.madeinabyss.kernel.volume;

import dev.madeinabyss.kernel.cube.CubeKey;
import dev.madeinabyss.kernel.terrain.living.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class B1AirBoundsTest {
    private B1AirBounds empty(){return new B1AirBounds(List.of(),List.of(),List.of(),List.of());}
    private CubeKey key(int x,int y,int z){return new CubeKey(x>>4,y>>4,z>>4);}
    @Test void widerThanOldCentreAndSignedCoordinatesSymmetric(){var p=empty();assertTrue(p.guaranteedAir(key(400,-160,0)));assertTrue(p.guaranteedAir(key(-416,-160,-16)));}
    @Test void allEightCornersAndWholeDepthIntervalMustBeInside(){assertFalse(empty().guaranteedAir(key(448,-128,0)));assertFalse(empty().guaranteedAir(key(300,-1344,0)));}
    @Test void parentAndTemporaryFloorAreOutsideThisProof(){assertFalse(empty().guaranteedAir(key(0,0,0)));assertFalse(empty().guaranteedAir(key(0,-1360,0)));}
    @Test void ledgeEnvelopeVetoesBodyRibsAndTopRelief(){var l=new LivingAbyssRevision155.Ledge("x",1,0,200,100,120,100,0,1,LivingAbyssPlan.Archetype.MEADOW,1,false);var p=new B1AirBounds(List.of(l),List.of(),List.of(),List.of());assertFalse(p.guaranteedAir(key(0,-208,-336)));assertTrue(p.guaranteedAir(key(0,-208,336)));}
    @Test void wholeHallIncludesColumnsBridgesAndThroughLedges(){var h=new LivingAbyssRevision155.Hall("h",1,0,300,160,140,120,30,1,List.of(),null,List.of(new LivingAbyssRevision155.Pillar(0,.5,10)),List.of(new LivingAbyssRevision155.Bridge(0,350,10,5)),List.of(),List.of(),true,1);var p=new B1AirBounds(List.of(),List.of(h),List.of(),List.of());assertFalse(p.guaranteedAir(key(0,-336,-352)));assertTrue(p.guaranteedAir(key(0,-336,352)));}
    @Test void speleoAndFloorWaterAreExplicitVetoes(){var s=new LivingAbyssRevision155.Speleo(320,0,500,40,8,1,3,false,1,"x");var f=new LivingAbyssFloor155B.Feature(LivingAbyssFloor155B.Type.GUR,320,32,-500,3,8,0,0,0,1,"h");var p=new B1AirBounds(List.of(),List.of(),List.of(s),List.of(f));assertFalse(p.guaranteedAir(key(320,-512,0)));assertFalse(p.guaranteedAir(key(320,-496,32)));assertTrue(p.guaranteedAir(key(-336,-512,0)));}
    @Test void wrappedAngularPlanDoesNotDisappearAtZero(){var l=new LivingAbyssRevision155.Ledge("wrap",1,359,200,100,120,100,0,1,LivingAbyssPlan.Archetype.GROVE,1,true);var p=new B1AirBounds(List.of(l),List.of(),List.of(),List.of());assertFalse(p.guaranteedAir(key(0,-208,-336)));assertFalse(p.guaranteedAir(key(-32,-208,-336)));}
}
