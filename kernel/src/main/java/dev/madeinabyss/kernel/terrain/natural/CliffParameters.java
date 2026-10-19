package dev.madeinabyss.kernel.terrain.natural;

/** ORIGINAL proposed weathering recipe; these metre scales require visual agreement. */
public record CliffParameters(long seed,double maximumRecessMetres,double ribSpacingMetres,
        double verticalRibScaleMetres,double strataSpacingMetres,double fractureWidthMetres,
        double pocketSpacingMetres,double pocketHeightMetres,double pocketWidthMetres,double mouthBlendMetres) {
    public CliffParameters {
        for(double v:new double[]{maximumRecessMetres,ribSpacingMetres,verticalRibScaleMetres,
                strataSpacingMetres,fractureWidthMetres,pocketSpacingMetres,pocketHeightMetres,pocketWidthMetres,mouthBlendMetres})
            if(!Double.isFinite(v)||v<1||v>4096)throw new IllegalArgumentException("cliff scales must be finite metres in [1,4096]");
        if(maximumRecessMetres>24)throw new IllegalArgumentException("shallow wall weathering must be bounded to 24 metres");
        if(fractureWidthMetres>=ribSpacingMetres/2 || pocketHeightMetres>=pocketSpacingMetres || pocketWidthMetres>=pocketSpacingMetres)
            throw new IllegalArgumentException("joint/pocket dimensions must leave coherent rock between features");
    }
    public static CliffParameters proposal(long seed){return new CliffParameters(seed,12,64,144,28,4,56,10,18,48);}
}
