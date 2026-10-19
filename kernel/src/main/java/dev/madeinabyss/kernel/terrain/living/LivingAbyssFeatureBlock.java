package dev.madeinabyss.kernel.terrain.living;

/** Pure stamp palette. Every value is mapped explicitly by LivingAbyssBlockStates. */
public enum LivingAbyssFeatureBlock {
    NONE,OAK_LOG,OAK_LEAVES,DARK_OAK_LOG,DARK_OAK_LEAVES,MOSS,MANGROVE_ROOTS,HANGING_ROOTS,
    INNER_LEAVES,BIRCH_LOG,BIRCH_LEAVES,GLOW_UP,
    GRASS,DIRT,ROOTED_DIRT,COARSE_DIRT,MOSS_CARPET,STONE,ANDESITE,CALCITE,MOSSY_COBBLESTONE,
    COBBLESTONE,GRAVEL,STONE_BRICKS,MOSSY_STONE_BRICKS,CRACKED_STONE_BRICKS,CHISELED_STONE_BRICKS,
    SHORT_GRASS,TALL_GRASS_LOWER,TALL_GRASS_UPPER,FERN,LARGE_FERN_LOWER,LARGE_FERN_UPPER,
    OXEYE_DAISY,AZURE_BLUET,LILY_OF_THE_VALLEY,CORNFLOWER,BLUE_ORCHID,DANDELION,
    AZALEA_LEAVES,FLOWERING_AZALEA_LEAVES,BROWN_MUSHROOM_BLOCK,RED_MUSHROOM_BLOCK,MUSHROOM_STEM,
    DRIP_BASE,DRIP_MIDDLE,DRIP_TIP,AIR_CUT,DRIP_FRUSTUM,
    WATER_SOURCE,WATER_FALLING,SAND,CLAY,MUD,SEAGRASS,TALL_SEAGRASS_LOWER,TALL_SEAGRASS_UPPER,
    KELP,KELP_PLANT,LILY_PAD,SUGAR_CANE,TUFF,DRIPSTONE,WHITE_TERRACOTTA,OAK_LOG_X,OAK_LOG_Z,CAVE_VINES,CAVE_VINES_PLANT;
    public byte code(){return (byte)ordinal();}
    public static boolean isLeaves(int code){return code==OAK_LEAVES.ordinal()||code==DARK_OAK_LEAVES.ordinal()||code==INNER_LEAVES.ordinal()||code==BIRCH_LEAVES.ordinal()||code==AZALEA_LEAVES.ordinal()||code==FLOWERING_AZALEA_LEAVES.ordinal();}
    public static boolean isPlant(int code){return code>=SHORT_GRASS.ordinal()&&code<=DANDELION.ordinal()||code>=SEAGRASS.ordinal()&&code<=SUGAR_CANE.ordinal()||code==MOSS_CARPET.ordinal()||code==HANGING_ROOTS.ordinal()||code==GLOW_UP.ordinal()||code>=128;}
    public static boolean isWater(int code){return code==WATER_SOURCE.ordinal()||code==WATER_FALLING.ordinal()||code>=SEAGRASS.ordinal()&&code<=KELP_PLANT.ordinal();}
    public static byte vine(int mask){if(mask<=0||mask>31)throw new IllegalArgumentException("Attached vine faces required");return (byte)(128+mask);}
}
