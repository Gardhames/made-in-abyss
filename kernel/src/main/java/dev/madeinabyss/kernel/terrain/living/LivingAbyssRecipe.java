package dev.madeinabyss.kernel.terrain.living;

/** Metre-scale inputs from Claude's one v15 recipe. Arrays are privately owned. */
public record LivingAbyssRecipe(int schema,String revision,String provenance,long seed,Domain domain,Fields fields,
                              Archetypes archetypes,NewLedges newLedges,Cascade cascade,
                              Wall wall,Curtains curtains,Spires spires,BiomeColors biomeColors) {
    public LivingAbyssRecipe {
        if(schema!=1||revision==null||!"RECONSTRUCTION".equals(provenance)||domain==null||fields==null||archetypes==null||newLedges==null||cascade==null)
            throw new IllegalArgumentException("Explicit v15 schema/revision/recipe required");
        if((revision.startsWith("15.2")||revision.startsWith("15.3")||revision.startsWith("15.4"))&&(wall==null||curtains==null||spires==null||biomeColors==null))throw new IllegalArgumentException("Complete wall/spire/curtain/color recipe required in v15.2+");
    }
    public boolean part3a(){return revision.startsWith("15.3")||revision.startsWith("15.4");}
    public boolean part3b(){return revision.startsWith("15.4");}
    public LivingAbyssRecipe(int schema,String revision,String provenance,long seed,Domain domain,Fields fields,
                            Archetypes archetypes,NewLedges newLedges,Cascade cascade) {
        this(schema,revision,provenance,seed,domain,fields,archetypes,newLedges,cascade,null,null,null,null);
    }
    public record Wall(double flutePeriod,double fluteStretch,double fluteDepth,double pipePeriod,double pipeStretch,double pipeDepth,
                       double driftPeriod,double driftAmp,double bulgePeriod,double bulgeDepth,double streakWidth,double streakStretch,
                       double strataPeriod,double slabPeriod,double slabStretch,double slabDepth,int slabSteps,
                       java.util.List<java.util.Map<String,Double>> palettes) {
        public Wall {
            for(double v:new double[]{flutePeriod,fluteStretch,fluteDepth,pipePeriod,pipeStretch,pipeDepth,driftPeriod,driftAmp,bulgePeriod,bulgeDepth,streakWidth,streakStretch,strataPeriod,slabPeriod,slabStretch,slabDepth})
                if(!Double.isFinite(v)||v<=0||v>4096)throw new IllegalArgumentException("Positive bounded wall metres required");
            if(slabSteps<1||slabSteps>32||palettes==null||palettes.size()!=5)throw new IllegalArgumentException("Five zone palettes required");
            var copy=new java.util.ArrayList<java.util.Map<String,Double>>();
            for(var palette:palettes){var ordered=new java.util.LinkedHashMap<String,Double>();double sum=0;
                for(var entry:palette.entrySet()){double v=entry.getValue();if(!Double.isFinite(v)||v<0)throw new IllegalArgumentException("Invalid formula weight");ordered.put(entry.getKey(),v);sum+=v;}
                if(!(sum>0))throw new IllegalArgumentException("Positive total formula weights required");copy.add(java.util.Collections.unmodifiableMap(ordered));}
            palettes=java.util.List.copyOf(copy);
        }
    }
    public record Curtains(double mossBelowLedge,double mossStreakLen,int vineMin,int vineMax,double drapeWidth,double drapeLen,
                           double drapeCover,int anchorsPerCluster,double scatterX,double scatterY,double clumpMin,double clumpMax,
                           double wetMultiplier,double sunnyMultiplier) {
        public Curtains {
            if(mossBelowLedge<=0||mossStreakLen<=0||vineMin<1||vineMax<vineMin||vineMax>64||drapeWidth<=0||drapeLen<=0||drapeLen>64
                ||drapeCover<=0||drapeCover>1||anchorsPerCluster!=5||scatterX<0||scatterY<0||clumpMin<1||clumpMax<clumpMin
                ||wetMultiplier!=1.5||sunnyMultiplier!=.5)throw new IllegalArgumentException("Bounded supported curtain contract required");
        }
    }
    public record Spires(int[] diameter,int[] height,int[] wallDistance,int[] groupSize,double[] frequenciesPer100m,
                         double karstMultiplier,double outsideKarstMultiplier,double topRadiusRatio,int saddleThickness) {
        public Spires {
            diameter=range(diameter);height=range(height);wallDistance=range(wallDistance);groupSize=range(groupSize);
            if(diameter[0]<12||diameter[1]>45||height[0]<40||height[1]>220||wallDistance[0]<10||wallDistance[1]>140
                    ||groupSize[0]<2||groupSize[1]>5||frequenciesPer100m.length!=3||topRadiusRatio<.7||topRadiusRatio>1||saddleThickness<4)
                throw new IllegalArgumentException("Canon spire dimensions required");
            frequenciesPer100m=frequenciesPer100m.clone();for(double v:frequenciesPer100m)if(!Double.isFinite(v)||v<=0||v>3)throw new IllegalArgumentException("Invalid spire frequency");
        }
        @Override public int[] diameter(){return diameter.clone();}@Override public int[] height(){return height.clone();}
        @Override public int[] wallDistance(){return wallDistance.clone();}@Override public int[] groupSize(){return groupSize.clone();}
        @Override public double[] frequenciesPer100m(){return frequenciesPer100m.clone();}
    }
    public record BiomeColors(String grass,String foliage,String water) {
        public BiomeColors {for(String v:new String[]{grass,foliage,water})if(v==null||!v.matches("#[0-9A-Fa-f]{6}"))throw new IllegalArgumentException("Explicit RGB biome colors required");}
        public int grassRgb(){return Integer.parseInt(grass.substring(1),16);}
        public int foliageRgb(){return Integer.parseInt(foliage.substring(1),16);}
        public int waterRgb(){return Integer.parseInt(water.substring(1),16);}
    }
    public record Domain(int knownLowerY,int wallBottomY,int temporaryFloorY,int emptyRadius,int emptyTopY) {
        public Domain {if(knownLowerY!=-1424||wallBottomY!=-1350||temporaryFloorY!=-1400||emptyRadius!=380||emptyTopY!=-120)
            throw new IllegalArgumentException("v15.1 fixed domain required");}
    }
    public record Fields(double[] zoneBoundaries,double zoneNoiseMetres,double sidePlateauDegrees,double sideTransitionDegrees,double fallbackWetDegrees) {
        public Fields {zoneBoundaries=zoneBoundaries.clone();new LivingAbyssFields(0,fallbackWetDegrees,zoneBoundaries,zoneNoiseMetres,sidePlateauDegrees,sideTransitionDegrees);}
        @Override public double[] zoneBoundaries(){return zoneBoundaries.clone();}
    }
    public record Archetypes(double[] base,double[][] sideMultipliers,double[] mist,double[] threshold,double neighbourOverlap,
                             double meadowAmplitude,double groveAmplitude,int[] lakeDepth,int[] terraceCount,int[] terraceRise,
                             int[] boulderHeight,int[] overhangThickness) {
        public Archetypes {
            base=weights(base);mist=weights(mist);threshold=weights(threshold);
            if(sideMultipliers.length!=4)throw new IllegalArgumentException("Four side weight rows required");
            sideMultipliers=java.util.Arrays.stream(sideMultipliers).map(Archetypes::weights).toArray(double[][]::new);
            if(!(neighbourOverlap>0&&neighbourOverlap<1)||!Double.isFinite(meadowAmplitude)||meadowAmplitude<0||!Double.isFinite(groveAmplitude)||groveAmplitude<0)
                throw new IllegalArgumentException("Invalid archetype dimensions");
            lakeDepth=range(lakeDepth);terraceCount=range(terraceCount);terraceRise=range(terraceRise);boulderHeight=range(boulderHeight);overhangThickness=range(overhangThickness);
        }
        private static double[] weights(double[] a){if(a.length!=7)throw new IllegalArgumentException("Seven archetype weights required");a=a.clone();double sum=0;for(double w:a){if(!Double.isFinite(w)||w<0)throw new IllegalArgumentException("Invalid weight");sum+=w;}if(sum<=0)throw new IllegalArgumentException("Zero total weights");return a;}
        @Override public double[] base(){return base.clone();}@Override public double[] mist(){return mist.clone();}@Override public double[] threshold(){return threshold.clone();}
        @Override public double[][] sideMultipliers(){return java.util.Arrays.stream(sideMultipliers).map(double[]::clone).toArray(double[][]::new);}
        @Override public int[] lakeDepth(){return lakeDepth.clone();}@Override public int[] terraceCount(){return terraceCount.clone();}@Override public int[] terraceRise(){return terraceRise.clone();}
        @Override public int[] boulderHeight(){return boulderHeight.clone();}@Override public int[] overhangThickness(){return overhangThickness.clone();}
    }
    public record NewLedges(double sunnyWidthMultiplier,double mistDensityMultiplier) {
        public NewLedges {if(!Double.isFinite(sunnyWidthMultiplier)||sunnyWidthMultiplier<1||mistDensityMultiplier<0||mistDensityMultiplier>1)throw new IllegalArgumentException("Invalid new ledge multipliers");}
    }
    public record Cascade(int[] count,int sourceDepth,int[] ledgeDepth,double halfWidthDegrees,int receiverDrop,double receiverAngleDegrees,
                          int[] terminationDepth,int[] bowlWidth,String termination) {
        public Cascade {
            count=range(count);ledgeDepth=range(ledgeDepth);terminationDepth=range(terminationDepth);bowlWidth=range(bowlWidth);
            if(count[0]<4||count[1]>5||sourceDepth!=140||halfWidthDegrees<=0||receiverDrop<=0||receiverAngleDegrees<=0||!"SUPPORTED_BOWL_OR_CAVE_DRAIN".equals(termination))throw new IllegalArgumentException("v15 supported cascade contract required");
        }
        @Override public int[] count(){return count.clone();}@Override public int[] ledgeDepth(){return ledgeDepth.clone();}@Override public int[] terminationDepth(){return terminationDepth.clone();}@Override public int[] bowlWidth(){return bowlWidth.clone();}
    }
    private static int[] range(int[] v){if(v.length!=2||v[0]<1||v[1]<v[0]||v[1]>4096)throw new IllegalArgumentException("Invalid metric range");return v.clone();}
}
