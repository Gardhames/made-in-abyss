package dev.madeinabyss.forge.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

/** Read-only map atlas of the actual v15 generator for visual comparison with canon references
 * (-PmiaBenchV15A2Mode=atlas -PmiaBenchV15Output=dir). Raw pixel layers only: 1 px = STEP metres;
 * axes, labels and legends are added offline. Nothing here feeds generation. */
final class LivingAbyssAtlasGameTests {
    private static final int STEP = 2, HALF = 1100, TOP_Y = 330, LOW_Y = -1420;
    private static final int AIR = 0x00000000, UNKNOWN = 0xFF400040, WATER = 0xFF3F76E4;

    static void atlas(GameTestHelper h) throws Exception {
        var g = LivingAbyssGameTests.profile(h).generator();
        var dir = Path.of(System.getProperty("mia.bench.v15Output", System.getProperty("mia.storageRoot", ".local") + "/evidence/landscape-v15/atlas"));
        export(g,dir);h.succeed();
    }
    /** Shared with the Forge datagen bench: registered blocks, no Level or save creation. */
    static void export(ParameterizedTerrainChunkGenerator g,Path dir) throws Exception {
        var living = g.livingAbyss();
        if(living!=null&&living.revision155()!=null){export155(g,dir);return;}
        Files.createDirectories(dir);
        long t = System.nanoTime();
        // Vertical sections through the shaft axis. Right half = azimuth theta, left half = theta+180.
        for (int theta : new int[]{0, 45, 90, 135}) {
            double a = Math.toRadians(theta), sin = Math.sin(a), cos = Math.cos(a);
            int w = 2 * HALF / STEP + 1, hgt = (TOP_Y - LOW_Y) / STEP + 1;
            var img = new BufferedImage(w, hgt, BufferedImage.TYPE_INT_ARGB);
            IntStream.range(0, hgt).parallel().forEach(row -> {
                int y = TOP_Y - row * STEP;
                for (int col = 0; col < w; col++) {
                    int s = -HALF + col * STEP;
                    img.setRGB(col, row, color(g, (int) Math.round(s * sin), y, (int) Math.round(-s * cos)));
                }
            });
            ImageIO.write(img, "png", dir.resolve("section-" + theta + ".png").toFile());
        }
        // Horizontal slices (plan views) at fixed depths; north (-Z) up.
        for (int depth : new int[]{-60, 0, 30, 100, 200, 300, 450, 600, 800, 1000, 1200, 1340}) {
            int w = 2 * HALF / STEP + 1;
            var img = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
            IntStream.range(0, w).parallel().forEach(row -> {
                int z = -HALF + row * STEP;
                for (int col = 0; col < w; col++) img.setRGB(col, row, color(g, -HALF + col * STEP, -depth, z));
            });
            ImageIO.write(img, "png", dir.resolve("slice-d" + depth + ".png").toFile());
        }
        // Top-down first surface from the sky: height (raw 16-bit-ish in RG) and its block colour.
        {
            int step = 4, w = 2 * HALF / step + 1;
            var height = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
            var colour = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
            IntStream.range(0, w).parallel().forEach(row -> {
                int z = -HALF + row * step;
                for (int col = 0; col < w; col++) {
                    int x = -HALF + col * step, y = TOP_Y, hit = LOW_Y - 1, c = AIR;
                    while (y >= LOW_Y) {
                        int cy = Math.floorDiv(y, 16);
                        if (living != null && (x * (long) x + z * (long) z) < 960L * 960 && living.uniformAirCube(Math.floorDiv(x, 16), cy, Math.floorDiv(z, 16))) {
                            y = cy * 16 - 1;
                            continue;
                        }
                        int v = color(g, x, y, z);
                        if (v != AIR) { hit = y; c = v; break; }
                        y--;
                    }
                    int enc = hit + 2048; // 0..4095 in 12 bits
                    height.setRGB(col, row, (enc >> 8) << 16 | (enc & 255) << 8);
                    colour.setRGB(col, row, c);
                }
            });
            ImageIO.write(height, "png", dir.resolve("topdown-height.png").toFile());
            ImageIO.write(colour, "png", dir.resolve("topdown-colour.png").toFile());
        }
        // Unrolled shaft wall seen from the axis: theta 0..360 (x), depth 0..1400 (y).
        {
            int tw = 720, dh = 1400 / STEP + 1;
            var facade = new BufferedImage(tw, dh, BufferedImage.TYPE_INT_ARGB);
            var radius = new BufferedImage(tw, dh, BufferedImage.TYPE_INT_RGB);
            IntStream.range(0, dh).parallel().forEach(row -> {
                int y = -row * STEP;
                for (int col = 0; col < tw; col++) {
                    double a = Math.toRadians(col * 0.5), sin = Math.sin(a), cos = Math.cos(a);
                    int hit = 0, c = AIR;
                    for (int r = 300; r <= 1000; r++) {
                        int v = color(g, (int) Math.round(r * sin), y, (int) Math.round(-r * cos));
                        if (v != AIR) { hit = r; c = v; break; }
                    }
                    facade.setRGB(col, row, c);
                    radius.setRGB(col, row, (hit >> 8) << 16 | (hit & 255) << 8);
                }
            });
            ImageIO.write(facade, "png", dir.resolve("unrolled-facade.png").toFile());
            ImageIO.write(radius, "png", dir.resolve("unrolled-radius.png").toFile());
        }
        Files.writeString(dir.resolve("atlas.json"), "{\"step\":" + STEP + ",\"half\":" + HALF + ",\"topY\":" + TOP_Y + ",\"lowY\":" + LOW_Y
                + ",\"seconds\":" + (System.nanoTime() - t) / 1e9 + "}");
    }

    private static void export155(ParameterizedTerrainChunkGenerator g,Path dir)throws Exception{
        Files.createDirectories(dir);long begin=System.nanoTime();var shape=g.livingAbyss().revision155();
        int half=1000,top=60,bottom=-1420,step=2;
        var angles=new java.util.LinkedHashMap<String,Double>();
        for(int theta:new int[]{0,45,90,135})angles.put("axis-"+theta,(double)theta);
        for(var hall:shape.halls())angles.put(hall.id(),hall.theta());
        for(var entry:angles.entrySet()){
            double a=Math.toRadians(entry.getValue()),sin=Math.sin(a),cos=Math.cos(a);int w=2*half/step+1,h=(top-bottom)/step+1;
            var img=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
            IntStream.range(0,h).parallel().forEach(row->{int y=top-row*step;for(int col=0;col<w;col++){int r=-half+col*step;
                img.setRGB(col,row,color(g,(int)Math.round(r*sin),y,(int)Math.round(-r*cos)));}});
            ImageIO.write(img,"png",dir.resolve("section-"+entry.getKey()+".png").toFile());
            System.out.println("MIA_REV155_ATLAS section "+entry.getKey());
        }
        for(int depth:new int[]{300,600,950,1250}){
            int w=2*half/step+1;var img=new BufferedImage(w,w,BufferedImage.TYPE_INT_ARGB);
            IntStream.range(0,w).parallel().forEach(row->{int z=-half+row*step;for(int col=0;col<w;col++)img.setRGB(col,row,color(g,-half+col*step,-depth,z));});
            ImageIO.write(img,"png",dir.resolve("slice-d"+depth+".png").toFile());
        }
        int tw=900,dh=1350/step+1;var facade=new BufferedImage(tw,dh,BufferedImage.TYPE_INT_ARGB);var radius=new BufferedImage(tw,dh,BufferedImage.TYPE_INT_RGB);
        IntStream.range(0,dh).parallel().forEach(row->{int y=-row*step;for(int col=0;col<tw;col++){
            double a=col*(2*Math.PI/tw),sin=Math.sin(a),cos=Math.cos(a);int hit=0,c=AIR;
            for(int r=Math.max(0,(int)dev.madeinabyss.kernel.terrain.living.LivingAbyssMath155.centre(-y)-2);r<=Math.max(960,shape.maximumRadius());r++){
                int v=color(g,(int)Math.round(r*sin),y,(int)Math.round(-r*cos));if(v!=AIR){hit=r;c=v;break;}}
            facade.setRGB(col,row,c);radius.setRGB(col,row,(hit>>8)<<16|(hit&255)<<8);
        }});
        ImageIO.write(facade,"png",dir.resolve("unrolled-facade.png").toFile());ImageIO.write(radius,"png",dir.resolve("unrolled-radius.png").toFile());
        // Top-down first actual block; height is encoded in RG as y+2048.
        int ts=4,w=2*half/ts+1;var height=new BufferedImage(w,w,BufferedImage.TYPE_INT_RGB);var colour=new BufferedImage(w,w,BufferedImage.TYPE_INT_ARGB);
        IntStream.range(0,w).parallel().forEach(row->{int z=-half+row*ts;for(int col=0;col<w;col++){
            int x=-half+col*ts,hit=bottom-1,c=AIR;
            for(int y=top;y>=bottom;y--){if(g.livingAbyss().uniformAirCube(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16))){y=Math.floorDiv(y,16)*16;continue;}
                int v=color(g,x,y,z);if(v!=AIR){hit=y;c=v;break;}}
            int enc=hit+2048;height.setRGB(col,row,(enc>>8)<<16|(enc&255)<<8);colour.setRGB(col,row,c);
        }});
        ImageIO.write(height,"png",dir.resolve("topdown-height.png").toFile());ImageIO.write(colour,"png",dir.resolve("topdown-colour.png").toFile());
        var meta=new com.google.gson.JsonObject();meta.addProperty("revision",g.livingAbyss().recipeValue().revision());
        meta.addProperty("stepMetres",step);meta.addProperty("halfMetres",half);meta.addProperty("sections",angles.size());meta.addProperty("seconds",(System.nanoTime()-begin)/1e9);
        meta.add("sectionAzimuths",new com.google.gson.Gson().toJsonTree(angles));meta.add("sliceDepths",new com.google.gson.Gson().toJsonTree(new int[]{300,600,950,1250}));
        meta.addProperty("scope","Actual block sampler/map colours; AIR transparent. Diagnostic atlas, Claude visual acceptance pending.");
        Files.writeString(dir.resolve("atlas.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(meta));
    }

    private static int color(ParameterizedTerrainChunkGenerator g, int x, int y, int z) {
        BlockState state;
        try { state = g.sampleBlock(x, y, z); } catch (RuntimeException unavailable) { return UNKNOWN; }
        if (state.isAir()) return AIR;
        if (!state.getFluidState().isEmpty()) return WATER;
        int rgb = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        return 0xFF000000 | rgb;
    }

    private LivingAbyssAtlasGameTests() {}
}
