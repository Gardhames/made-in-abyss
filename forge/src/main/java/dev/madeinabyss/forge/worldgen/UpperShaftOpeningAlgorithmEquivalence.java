package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;

/** One fixed cache-lifetime substitution; the original opening recipe owns save identity. */
final class UpperShaftOpeningAlgorithmEquivalence {
    private static final String ORIGINAL="fcb4149aecb3801354d3a4ad45e6c68b339e6295aca4415fea9e16e3d2e44a2d";
    private static final String REUSED="c217cd95b585e768a4fc65ecb9f56245c672bdbd53b3d19780f7ca957b41e422";
    private UpperShaftOpeningAlgorithmEquivalence(){}
    static JsonObject forConstruction(JsonObject original,String actual){
        if(!original.has("algorithmSha256")||!ORIGINAL.equals(original.get("algorithmSha256").getAsString())||!REUSED.equals(actual))return original;
        var copy=original.deepCopy();copy.addProperty("algorithmSha256",actual);return copy;
    }
}
