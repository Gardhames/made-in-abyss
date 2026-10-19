package dev.madeinabyss.forge.worldgen;

import com.google.gson.*;
import dev.madeinabyss.kernel.terrain.habitat.*;
import java.util.*;

/** Strict bounded import of offline terrain-shape/terrain-ecology output. */
public final class LandscapeRecipeCodec {
    private LandscapeRecipeCodec() {}
    public static List<LandscapeOverlay.Patch> parse(JsonObject data) {
        if(integer(data,"schema")!=1 || !"ORIGINAL".equals(data.get("provenance").getAsString()))
            throw new IllegalArgumentException("Landscape schema 1 and ORIGINAL provenance required");
        if(!data.get("toolkitCommit").getAsString().matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Pinned terrain toolkit commit required");
        var patches=data.getAsJsonArray("patches");
        if(patches.isEmpty() || patches.size()>LandscapeOverlay.MAX_PATCHES)throw new IllegalArgumentException("Landscape patch budget");
        var result=new ArrayList<LandscapeOverlay.Patch>();int totalColumns=0,totalDecorations=0;
        for(var value:patches) {
            var p=value.getAsJsonObject();var cs=p.getAsJsonArray("columns");var ds=p.getAsJsonArray("placements");
            if(cs.size()>6400 || (totalColumns+=cs.size())>204800 || (totalDecorations+=ds.size())>LandscapeOverlay.MAX_WRITES)
                throw new IllegalArgumentException("Landscape input budget");
            var columns=new ArrayList<LandscapeOverlay.Column>();var decorations=new ArrayList<HabitatOverlay.Placement>();
            for(var cValue:cs) {
                var c=cValue.getAsJsonObject();var water=c.get("waterY");
                columns.add(new LandscapeOverlay.Column(integer(c,"x"),integer(c,"z"),integer(c,"baseY"),integer(c,"surfaceY"),
                        water==null||water.isJsonNull()?null:integer(water)));
            }
            String id=p.get("id").getAsString();
            for(var dValue:ds) {
                var d=dValue.getAsJsonArray();if(d.size()!=4)throw new IllegalArgumentException("Decoration must be XYZ plus material");
                decorations.add(new HabitatOverlay.Placement(integer(d.get(0)),integer(d.get(1)),integer(d.get(2)),
                        HabitatBlock.valueOf(d.get(3).getAsString()),id+"/ecology"));
            }
            result.add(new LandscapeOverlay.Patch(id,integer(p,"minX"),integer(p,"minZ"),integer(p,"width"),integer(p,"depth"),
                    integer(p,"referenceY"),columns,decorations));
        }
        return List.copyOf(result);
    }
    private static int integer(JsonObject object,String key){return integer(object.get(key));}
    private static int integer(JsonElement e) {
        if(e==null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Exact integer required");
        try{return e.getAsBigDecimal().intValueExact();}catch(ArithmeticException ex){throw new IllegalArgumentException("Signed exact integer required",ex);}
    }
}
