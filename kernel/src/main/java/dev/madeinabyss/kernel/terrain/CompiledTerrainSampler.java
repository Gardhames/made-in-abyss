package dev.madeinabyss.kernel.terrain;

import java.util.Objects;

/** Exact optional evaluator of the unchanged geology oracle. Lazy bounded worker-local XZ/lattice caches; no changed contours or visited profiles. */
public final class CompiledTerrainSampler {
    private final TerrainParameters p;
    private static final TerrainSample.Unavailable BELOW = new TerrainSample.Unavailable(
            TerrainSample.Reason.BELOW_DECLARED_FIRST_LAYER);
    private static final TerrainSample.Known[] MATERIALS = java.util.Arrays.stream(TerrainMaterial.values())
            .map(TerrainSample.Known::new).toArray(TerrainSample.Known[]::new);

    private final ThreadLocal<Cache> columns;
    private final long sectors;
    private final int compiledBands;
    private final Ledge[] compiledLedges;
    public CompiledTerrainSampler(TerrainParameters parameters){this(parameters,4096);}
    public CompiledTerrainSampler(TerrainParameters parameters,int cacheCapacity) {
        p=Objects.requireNonNull(parameters);
        if(cacheCapacity<16||cacheCapacity>65536||(cacheCapacity&(cacheCapacity-1))!=0)throw new IllegalArgumentException("XZ cache capacity must be a power of two in16..65536");
        int capacity=cacheCapacity;columns=ThreadLocal.withInitial(()->new Cache(capacity));
        sectors=Math.max(1L,(long)Math.ceil(2*Math.PI*p.cavity().mouthRadius()/p.noise().wavelength()));
        long bands=(long)Math.floor((p.cavity().firstLayerTopY()-p.cavity().declaredLowerBoundY())/p.cavity().ledgePeriod())+3;
        if(bands>0&&bands<=32768&&sectors<=32768/bands){
            compiledBands=(int)bands;compiledLedges=new Ledge[(int)(sectors*bands)];
            for(int sector=0;sector<sectors;sector++)for(int band=0;band<compiledBands;band++)compiledLedges[sector*compiledBands+band]=compileLedge(sector,band);
        }else{compiledBands=0;compiledLedges=null;}
    }
    public double radialDistance(int x,int z){return column(x,z).radial;}
    public int cachedColumnCount(){return columns.get().count;}
    /** Hits retained in this worker's currently resident XZ columns; no other workers included. */
    public long cachedRadiusHits(){long hits=0;for(var col:columns.get().values)if(col!=null)hits+=col.radiusHits;return hits;}
    private Column column(int x,int z){
        Cache cache=columns.get();int slot=(int)mix(((long)x<<32)^(z&0xffffffffL))&(cache.values.length-1);
        Column col=cache.values[slot];
        if(col==null){cache.count++;col=new Column(x,z);cache.values[slot]=col;}
        else if(col.x!=x||col.z!=z)col.reset(x,z);
        return col;
    }
    private static final class Cache {
        final Column[] values;final Ledge[] ledges=new Ledge[128];int count;
        Cache(int capacity){values=new Column[capacity];}
    }
    private static final class Column {
        int x,z;double radial;
        boolean rimReady,surfaceReady,materialReady;int rimX,rimZ;double ribs,azimuth,ledgeRelief,surface;
        final long[] keys=new long[3];final double[] angularWeights=new double[3];
        NoiseColumn cliff,material;
        int radiusCount,radiusNext,y0,y1,y2,y3;double r0,r1,r2,r3;long radiusHits;
        Column(int x,int z){reset(x,z);}
        // The private column never escapes its worker-local evaluation. Lazy
        // flags/counts hide all old metadata while retaining bounded arrays.
        void reset(int x,int z){
            this.x=x;this.z=z;radial=Math.hypot((double)x,(double)z);
            rimReady=surfaceReady=materialReady=false;radiusCount=radiusNext=0;radiusHits=0;
        }
    }
    private void rim(Column col){
        if(col.rimReady)return;
        var c=p.cavity();col.rimX=(int)Math.round(col.radial==0?c.mouthRadius():col.x/col.radial*c.mouthRadius());
        col.rimZ=(int)Math.round(col.radial==0?0:col.z/col.radial*c.mouthRadius());
        col.ribs=noise(col.rimX,0,col.rimZ,11);
        col.azimuth=(Math.atan2(col.z,col.x)/(2*Math.PI)+1)%1*sectors;
        long sector=(long)Math.floor(col.azimuth);
        for(int offset=-1;offset<=1;offset++){
            long localSector=sector+offset;col.angularWeights[offset+1]=smooth(clamp(1-Math.abs(col.azimuth-localSector)));
            col.keys[offset+1]=Math.floorMod(localSector,sectors);
        }
        col.ledgeRelief=noise(col.x,0,col.z,41)*p.noise().surfaceAmplitude();
        if(col.cliff==null)col.cliff=new NoiseColumn(p.seed(),p.noise().wavelength(),col.rimX,col.rimZ,2);
        else col.cliff.reset(col.rimX,col.rimZ);
        col.rimReady=true;
    }
    private double materialNoise(Column col,int y){
        if(!col.materialReady){
            if(col.material==null)col.material=new NoiseColumn(p.seed(),p.noise().wavelength(),col.x,col.z,31);
            else col.material.reset(col.x,col.z);
            col.materialReady=true;
        }
        return col.material.value(y);
    }
    private record Ledge(long sector,long band,double top,double width,double projection){}
    private Ledge compileLedge(long key,long band){
        var c=p.cavity();
        double top=(band+0.2+0.6*randomUnit(key,band,21))*c.ledgePeriod()
            +(2*randomUnit(key,band,22)-1)*p.noise().ledgeVerticalAmplitude();
        return new Ledge(key,band,top,c.ledgeWidth()*(0.6+0.8*randomUnit(key,band,23)),c.ledgeProjection()*(0.4+0.6*randomUnit(key,band,24)));
    }
    private Ledge ledge(long key,long band){
        if(compiledLedges!=null&&band<compiledBands)return compiledLedges[(int)key*compiledBands+(int)band];
        Cache cache=columns.get();int slot=(int)mix(key^Long.rotateLeft(band,29))&127;var known=cache.ledges[slot];
        if(known==null||known.sector()!=key||known.band()!=band){known=compileLedge(key,band);cache.ledges[slot]=known;}
        return known;
    }
    private static final class NoiseColumn {
        final long seed,salt;long ix,iz;final double wavelength;double fx,fz;final double[] corners=new double[8];long band=Long.MIN_VALUE;
        NoiseColumn(long seed,double wavelength,int x,int z,long salt){
            this.seed=seed;this.wavelength=wavelength;this.salt=salt;
            reset(x,z);
        }
        void reset(int x,int z){
            double sx=x/wavelength,sz=z/wavelength;ix=(long)Math.floor(sx);iz=(long)Math.floor(sz);fx=smooth(sx-ix);fz=smooth(sz-iz);
            // Wavelength>=1 and intY guarantee the next value overwrites all8
            // corners before using a previously retained array.
            band=Long.MIN_VALUE;
        }
        double value(int y){
            double sy=y/wavelength;long iy=(long)Math.floor(sy);double fy=smooth(sy-iy);
            if(band!=iy){
                for(int dx=0;dx<=1;dx++)for(int dy=0;dy<=1;dy++)for(int dz=0;dz<=1;dz++){
                    long hash=mix(seed^mix(ix+dx)^Long.rotateLeft(mix(iy+dy),21)^Long.rotateLeft(mix(iz+dz),42)^mix(salt));
                    corners[(dx*2+dy)*2+dz]=(hash>>>11)*0x1.0p-53*2-1;
                }band=iy;
            }
            double result=0;
            for(int dx=0;dx<=1;dx++)for(int dy=0;dy<=1;dy++)for(int dz=0;dz<=1;dz++){
                double weight=(dx==0?1-fx:fx)*(dy==0?1-fy:fy)*(dz==0?1-fz:fz);
                result+=weight*corners[(dx*2+dy)*2+dz];
            }
            return result;
        }
    }

    public TerrainSample sample(int x, int y, int z) {
        if (y < p.cavity().declaredLowerBoundY()) return BELOW;
        Column col=column(x,z);
        if (col.radial < cavityRadius(col,y)) return known(TerrainMaterial.AIR);
        double surface = Math.floor(surfaceHeight(col));
        if (y > surface) return known(y <= p.surface().oceanLevelY() ? TerrainMaterial.WATER : TerrainMaterial.AIR);
        if (surface > p.surface().oceanLevelY()) {
            if (y == surface) return known(TerrainMaterial.GRASS);
            if ((double)y > surface - p.surface().soilDepth()) return known(TerrainMaterial.SOIL);
        }
        // Soil horizon on real exposed deep ground, never an ecological feature or recursive sample call.
        // Patch noise leaves bare rock between covered terraces; depth is the explicit surface soilDepth.
        if (y < p.cavity().firstLayerTopY() && materialNoise(col,y) > 0) {
            if (cavityAirAbove(col,y, 1) && cavityAirAbove(col,y, 2)) return known(TerrainMaterial.GRASS);
            long horizon = (long)Math.ceil(p.surface().soilDepth());
            if (cavityAirAbove(col,y, horizon) && cavityAirAbove(col,y, horizon + 1))
                return known(TerrainMaterial.SOIL);
        }
        return known(TerrainMaterial.ROCK);
    }

    private boolean cavityAirAbove(Column col,int y,long offset) {
        long above = (long)y + offset;
        return above <= Integer.MAX_VALUE && above >= p.cavity().declaredLowerBoundY()
                && col.radial < cavityRadius(col,(int)above);
    }

    /** Continuous height of the upper solid block. Its voxel Y is floor(height), air starts one above. */
    public double surfaceHeight(int x,int z){return surfaceHeight(column(x,z));}
    private double surfaceHeight(Column col) {
        if(col.surfaceReady)return col.surface;
        col.surface=computeSurface(col);col.surfaceReady=true;return col.surface;
    }
    private double computeSurface(Column col) {
        int x=col.x,z=col.z;
        var s = p.surface();
        double ratio=s.islandRadiusX()/s.islandRadiusZ();
        double radius=ratio==1.0?col.radial:Math.hypot((double)x,(double)z*ratio);
        if (radius >= s.islandRadiusX()) return s.oceanBedY();
        double coast = smooth(clamp((s.islandRadiusX() - radius) / s.coastWidth()));
        double ring = Math.max(0, 1 - Math.abs(radius - s.mountainPeakRadius()) / s.mountainHalfWidth());
        double relief = smooth(clamp((radius - s.terraceOuterRadius()) / p.noise().wavelength()));
        return s.oceanBedY() + (s.terraceY() - s.oceanBedY()) * coast
                + s.mountainHeight() * smooth(ring)
                + noise(x, 0, z, 1) * p.noise().surfaceAmplitude() * relief * coast;
    }

    /** Local cliff profile at this azimuth/altitude; the upper mouth preserves its declared radius. */
    public double cavityRadius(int x,int y,int z){
        if(y>=p.cavity().firstLayerTopY())return p.cavity().mouthRadius();
        return cavityRadius(column(x,z),y);
    }
    private double cavityRadius(Column col,int y){
        if(y>=p.cavity().firstLayerTopY())return p.cavity().mouthRadius();
        if(col.radiusCount>0&&y==col.y0){col.radiusHits++;return col.r0;}
        if(col.radiusCount>1&&y==col.y1){col.radiusHits++;return col.r1;}
        if(col.radiusCount>2&&y==col.y2){col.radiusHits++;return col.r2;}
        if(col.radiusCount>3&&y==col.y3){col.radiusHits++;return col.r3;}
        double value=calculateCavityRadius(col,y);
        switch(col.radiusNext){
            case 0->{col.y0=y;col.r0=value;}case 1->{col.y1=y;col.r1=value;}
            case 2->{col.y2=y;col.r2=value;}case 3->{col.y3=y;col.r3=value;}
            default->throw new AssertionError("Radius memo slot");
        }
        col.radiusNext=(col.radiusNext+1)&3;if(col.radiusCount<4)col.radiusCount++;
        return value;
    }
    private double calculateCavityRadius(Column col,int y){
        var c=p.cavity();var n=p.noise();if(y>=c.firstLayerTopY())return c.mouthRadius();
        double depth=c.firstLayerTopY()-(double)y;
        double fraction=clamp(depth/(c.firstLayerTopY()-c.declaredLowerBoundY()));
        double envelope=smooth(clamp(depth/n.wavelength()));rim(col);
        double cliff=col.cliff.value(y);
        double wall=n.wallAmplitude()*(0.65*col.ribs+0.35*cliff);
        return c.mouthRadius()+(c.lowerRadius()-c.mouthRadius())*smooth(fraction)
            +envelope*(wall-localLedge(depth,col));
    }

    // Reconstruction of local shelves, not rings: independent top, width and projection per patch.
    // Dimensionless shape coefficients are algorithm choices; all metre scales come from parameters.
    private double localLedge(double depth,Column col){
        var c=p.cavity();long band=(long)Math.floor(depth/c.ledgePeriod());double extrusion=0;
        for(int offset=-1;offset<=1;offset++){
            double angularWeight=col.angularWeights[offset+1];if(angularWeight==0)continue;
            long key=col.keys[offset+1];
            for(int vertical=-2;vertical<=2;vertical++){
                long localBand=band+vertical;if(localBand<0)continue;var constants=ledge(key,localBand);
                double top=constants.top()+col.ledgeRelief,width=constants.width();double belowTop=depth-top;
                if(top<=0||belowTop<0||belowTop>=width)continue;
                double projection=constants.projection();double position=belowTop/width;
                double remaining=1-position,support=remaining*remaining;
                extrusion=Math.max(extrusion,projection*angularWeight*support);
            }
        }
        return extrusion;
    }

    private double randomUnit(long sector, long band, long salt) {
        return (mix(p.seed() ^ mix(sector) ^ Long.rotateLeft(mix(band), 29) ^ mix(salt)) >>> 11) * 0x1.0p-53;
    }

    private TerrainSample.Known known(TerrainMaterial material) { return MATERIALS[material.ordinal()]; }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static double smooth(double value) { return value * value * (3 - 2 * value); }

    // Stateless interpolated lattice noise: signed coordinates, no packed keys or mutable RNG order.
    private double noise(int x, int y, int z, long salt) {
        double sx = x / p.noise().wavelength(), sy = y / p.noise().wavelength(), sz = z / p.noise().wavelength();
        long ix = (long)Math.floor(sx), iy = (long)Math.floor(sy), iz = (long)Math.floor(sz);
        double fx = smooth(sx - ix), fy = smooth(sy - iy), fz = smooth(sz - iz), result = 0;
        for (int dx = 0; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = 0; dz <= 1; dz++) {
            double weight = (dx == 0 ? 1-fx : fx) * (dy == 0 ? 1-fy : fy) * (dz == 0 ? 1-fz : fz);
            long hash = mix(p.seed() ^ mix(ix + dx) ^ Long.rotateLeft(mix(iy + dy), 21)
                    ^ Long.rotateLeft(mix(iz + dz), 42) ^ mix(salt));
            result += weight * ((hash >>> 11) * 0x1.0p-53 * 2 - 1);
        }
        return result;
    }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
