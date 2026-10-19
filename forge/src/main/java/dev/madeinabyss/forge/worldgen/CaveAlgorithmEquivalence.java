package dev.madeinabyss.forge.worldgen;

import com.google.gson.JsonObject;

/** Fixed audited construction substitutions. Original manifest bytes still own save identity. */
final class CaveAlgorithmEquivalence {
    static final String ORIGINAL="5e8e03c72fa854c2b0037ca1c4477242df3116aeeb0b964b2210ea7ec598d262";
    static final String PRE_REUSE_INDEXED="06eb446c89e51c163700accb162135fbac608f170f4e4aac873c0c59ad5a495a";
    // Actual loaded JDK17 closure with bounded private Column reuse, verified against the unchanged oracle.
    static final String INDEXED="91f87867f1ab1ed693723175e41733f65e6f655d3e6024c32156e55ef3b42812";
    private CaveAlgorithmEquivalence() { }
    static JsonObject forConstruction(JsonObject original,String actual) {
        String stored=original.get("algorithmSha256").getAsString();
        if(!INDEXED.equals(actual)||(!ORIGINAL.equals(stored)&&!PRE_REUSE_INDEXED.equals(stored)))return original;
        var copy=original.deepCopy();copy.addProperty("algorithmSha256",actual);return copy;
    }
}
