package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;

/** One fixed cache-lifetime substitution for the exact admitted Wilderness baseline. */
final class WildernessAlgorithmEquivalence {
    private static final String ORIGINAL="5e8183b96dd7700185d00129d49490e0e2db90ba92a2ed17974cb679a338a4d6";
    private static final String REUSED="be8c037a97864bc381c4ea631c190073ccaeeefaba1ef2cb613953a14b8669d9";
    private WildernessAlgorithmEquivalence(){}
    static JsonObject forConstruction(JsonObject original,String actual){
        if(!original.has("algorithmSha256")||!ORIGINAL.equals(original.get("algorithmSha256").getAsString())||!REUSED.equals(actual))return original;
        var copy=original.deepCopy();copy.addProperty("algorithmSha256",actual);return copy;
    }
}
