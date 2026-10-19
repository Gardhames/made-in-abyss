package dev.madeinabyss.kernel.lod;
/** Finite near-first prefixes; the last prefix is exactly the complete original plan. */
public final class ProgressiveRefinementBudget {
 private ProgressiveRefinementBudget(){}
 public static int next(int total,int cached,int previous){
  return next(total,cached,previous,false);
 }
 /** Movement must replace a complete displayed scene with complete current-plan detail. */
 public static int next(int total,int cached,int previous,boolean completedSceneDisplayed){
  if(total<0||cached<0||cached>total||previous<0)throw new IllegalArgumentException("Invalid refinement progress");
  if(completedSceneDisplayed||cached==total)return total;
  return (int)Math.min(total,Math.max(128L,2L*previous));
 }
}
