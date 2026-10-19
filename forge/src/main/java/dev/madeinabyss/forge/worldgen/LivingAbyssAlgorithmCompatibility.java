package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;

/** Fixed audited legacy-to-live pair. Canonical persisted JSON is never rewritten.
 * The seal class is outside the generation closure, like the older equivalence adapters. */
final class LivingAbyssAlgorithmCompatibility {
    static final String STAGE_A="9e903c73b7e2a22ff7b1f2fb8495a348913d00edae5cb018fb2021d7b10fbc82";
    private static final String STAGE_B_LIVE="d992fb787da808feb9aa530b9a89acecdf485c72f55edae7beedd41953cf7013";
    private LivingAbyssAlgorithmCompatibility(){}
    static boolean admitted(JsonObject recipe,String live){
        if(!recipe.has("algorithmSha256"))return false;
        String claimed=recipe.get("algorithmSha256").getAsString();
        if(claimed.equals(live))return true;
        String revision=recipe.has("revision")?recipe.get("revision").getAsString():"";
        return STAGE_A.equals(claimed)&&STAGE_B_LIVE.equals(live)
            &&("15.5-stage-a-1".equals(revision)||"15.4-part3b-r13".equals(revision));
    }
    static boolean provenStageA(String live){return STAGE_A.equals(live)||STAGE_B_LIVE.equals(live);}
}
