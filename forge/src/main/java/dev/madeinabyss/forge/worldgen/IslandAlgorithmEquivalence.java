package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;

/** One fixed cache-only implementation substitution; original recipe bytes own save identity. */
final class IslandAlgorithmEquivalence {
    static final String ORIGINAL="c13811131b7dc9ec01f4e641c7eb292731a85089d656e45c477e81547f1d81fb";
    // Actual JDK17 closure includes all declared island/height/cache/key implementation members.
    static final String CACHED="01c3c884b5bdba301d3dfb0b2c59d88e10a473cce82bf18706110a898bd99463";
    private IslandAlgorithmEquivalence() {}
    static JsonObject forConstruction(JsonObject original,String actual) {
        if(!original.has("algorithmSha256")||!ORIGINAL.equals(original.get("algorithmSha256").getAsString())||!CACHED.equals(actual))return original;
        var copy=original.deepCopy();copy.addProperty("algorithmSha256",actual);return copy;
    }
}
