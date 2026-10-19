package dev.madeinabyss.kernel.terrain.natural;

/** ORIGINAL proposed v10 joint/cave dimensions; not approved canon geography. */
public record CaveNetworkParameters(long seed,double sectorSpacing,double verticalSpacing,
        double maximumPenetration,double tunnelRadius,double portalRadius,double shallowDepth,
        double mouthProtection) {
    public CaveNetworkParameters {
        for(double v:new double[]{sectorSpacing,verticalSpacing,maximumPenetration,tunnelRadius,portalRadius,shallowDepth,mouthProtection})
            if(!Double.isFinite(v))throw new IllegalArgumentException("Cave dimensions must be finite");
        if(sectorSpacing<48||sectorSpacing>192||verticalSpacing<48||verticalSpacing>192
                ||maximumPenetration<16||maximumPenetration>48||tunnelRadius<2||tunnelRadius>4
                ||portalRadius<tunnelRadius||portalRadius>6||shallowDepth<2||shallowDepth>6
                ||mouthProtection<32||mouthProtection>128||maximumPenetration<portalRadius*4)
            throw new IllegalArgumentException("Cave dimensions exceed coherent bounded wall recipe");
    }
    public static CaveNetworkParameters proposal(long seed){return new CaveNetworkParameters(seed,96,96,36,2.5,3.5,4,64);}
}
