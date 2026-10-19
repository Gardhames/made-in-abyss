package dev.madeinabyss.kernel.lod;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProgressiveRefinementBudgetTest {
 @Test void boundedPrefixesReachFullDetailWithoutStarvation(){int limit=0,stages=0;while(limit<7331){int next=ProgressiveRefinementBudget.next(7331,0,limit);assertTrue(next>limit);assertTrue(next<=7331);limit=next;stages++;assertTrue(stages<10);}assertEquals(7,stages);}
 @Test void completeCachedSceneSkipsIntermediateStages(){assertEquals(7331,ProgressiveRefinementBudget.next(7331,7331,0));assertEquals(0,ProgressiveRefinementBudget.next(0,0,0));}
 @Test void changedViewStartsSmallAndLargeCountsDoNotOverflow(){assertEquals(128,ProgressiveRefinementBudget.next(7331,0,0));assertEquals(Integer.MAX_VALUE,ProgressiveRefinementBudget.next(Integer.MAX_VALUE,0,1_500_000_000));assertThrows(IllegalArgumentException.class,()->ProgressiveRefinementBudget.next(10,11,0));}
 @Test void completeDisplayedSceneCannotRestartPartialAfterViewProgressReset(){
  for(int cached:new int[]{0,1,127,4096,7330})assertEquals(7331,ProgressiveRefinementBudget.next(7331,cached,0,true));
  assertEquals(128,ProgressiveRefinementBudget.next(7331,4096,0,false));
  assertEquals(256,ProgressiveRefinementBudget.next(7331,4096,128,false));
 }
 @Test void retainedCompletionStillValidatesInputsAndHandlesEmptyAndExtremeCounts(){
  assertEquals(0,ProgressiveRefinementBudget.next(0,0,0,true));
  assertEquals(1,ProgressiveRefinementBudget.next(1,0,0,true));
  assertEquals(Integer.MAX_VALUE,ProgressiveRefinementBudget.next(Integer.MAX_VALUE,1,0,true));
  assertThrows(IllegalArgumentException.class,()->ProgressiveRefinementBudget.next(-1,0,0,true));
  assertThrows(IllegalArgumentException.class,()->ProgressiveRefinementBudget.next(10,-1,0,true));
  assertThrows(IllegalArgumentException.class,()->ProgressiveRefinementBudget.next(10,11,0,true));
  assertThrows(IllegalArgumentException.class,()->ProgressiveRefinementBudget.next(10,0,-1,true));
 }
}
