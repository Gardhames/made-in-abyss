package dev.madeinabyss.kernel.probe;

import dev.madeinabyss.kernel.terrain.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Scientific export only. No world/store access; the explicit reconstruction is not a shipping default. */
public final class TerrainSectionProbe {
    private static final int RADIAL_EXTENT = 1000;
    private static final int MAX_DEPTH = 1350;
    private static final int RASTER_STEP = 2;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: TerrainSectionProbe <new-output-directory>");
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        for (String name : new String[]{"proposal.json", "section-x.png", "section-z.png", "wall-boundaries.csv", "habitat-proof.json", "support-depth-metrics.json", "habitat-ground-grid.csv"})
            if (Files.exists(output.resolve(name))) throw new IllegalArgumentException("Refusing to replace " + name);
        // Only mouth radius/depth have source attribution supplied for this review. All other metres are proposals.
        var parameters = new TerrainParameters(20261003,
                new TerrainParameters.Surface(0, -64, 1000, 1000, 80, 64, 650, 800, 100, 160, 3),
                new TerrainParameters.Cavity(500, 64, -1286, 650, 180, 170, 280),
                new TerrainParameters.Noise(140, 20, 60, 70));
        var sampler = new ParametricTerrainSampler(parameters);
        Files.writeString(output.resolve("proposal.json"), """
                {
                  "status": "REVIEW_PROPOSAL_NOT_ACCEPTED_NOT_SHIPPING_DEFAULT",
                  "contourStatus": "RECONSTRUCTION",
                  "algorithm": "local-azimuth-habitat-shelves-v5-rounded-buttress",
                  "sourceImage": "manga-first-layer-map.jpg",
                  "userShapeReference": "1-\u0424\u043e\u0442\u043e-1.jpg (annotated downward rounded buttresses)",
                  "supportShape": "quadratic remaining-depth squared; declared characteristic thickness170m; seeded actual thickness102..238m; overlapping bodies may merge",
                  "sourceAttributedMetres": ["mouthRadius=500", "firstLayerDepth=1350"],
                  "otherMetresStatus": "UNACCEPTED_REVIEW_PROPOSAL",
                  "seed": 20261003,
                  "surface": {"oceanLevelY":0,"oceanBedY":-64,"islandRadiusX":1000,"islandRadiusZ":1000,"coastWidth":80,"terraceY":64,"terraceOuterRadius":650,"mountainPeakRadius":800,"mountainHalfWidth":100,"mountainHeight":160,"soilDepth":3},
                  "cavity": {"mouthRadius":500,"firstLayerTopY":64,"declaredLowerBoundY":-1286,"lowerRadius":650,"ledgePeriod":180,"ledgeWidth":170,"ledgeProjection":280},
                  "naturalGroundRelief": "existing surfaceAmplitude20m also shifts local shelf cap and its support body together; surface and deep ground share this explicit scale",
                  "noise": {"wavelength":140,"surfaceAmplitude":20,"wallAmplitude":60,"ledgeVerticalAmplitude":70},
                  "raster": {"horizontalMin":-1000,"horizontalMax":1000,"depthMin":0,"depthMax":1350,"horizontalStep":2,"verticalStep":2,"sampling":"one actual voxel per pixel; no interpolation"},
                  "boundaries": {"horizontalStep":1,"verticalStep":1,"definition":"first actual ROCK/SOIL/GRASS voxel from centre outwards on each axis ray"},
                  "paletteRGB": {"AIR":"e8f1fa","ROCK":"5b6470","SOIL":"84664d","GRASS":"5c8a46","WATER":"377cac","UNAVAILABLE":"d92e8a"},
                  "habitatProof": "habitat-proof.json: connected natural80x80 ground with per-column height variation, maximum neighbour step1m, and24 AIR voxels above each local floor; 24m is a diagnostic cap, not a canonical tree height; material counts and example positions",
                  "scope": "geological samples only; no plants, fossil trees, ecology, second layer or world edits"
                }
                """, StandardOpenOption.CREATE_NEW);
        for (boolean alongX : new boolean[]{true, false}) {
            var image = new BufferedImage(2 * RADIAL_EXTENT / RASTER_STEP + 1,
                    MAX_DEPTH / RASTER_STEP + 1, BufferedImage.TYPE_INT_RGB);
            for (int depth = 0; depth <= MAX_DEPTH; depth += RASTER_STEP)
                for (int horizontal = -RADIAL_EXTENT; horizontal <= RADIAL_EXTENT; horizontal += RASTER_STEP) {
                    TerrainSample sample = sampler.sample(alongX ? horizontal : 0,
                            64 - depth, alongX ? 0 : horizontal);
                    image.setRGB((horizontal + RADIAL_EXTENT) / RASTER_STEP, depth / RASTER_STEP, rgb(sample));
                }
            if (!ImageIO.write(image, "PNG", output.resolve(alongX ? "section-x.png" : "section-z.png").toFile()))
                throw new IllegalStateException("PNG writer unavailable");
        }
        try (BufferedWriter csv = Files.newBufferedWriter(output.resolve("wall-boundaries.csv"), StandardOpenOption.CREATE_NEW)) {
            csv.write("depth_m,y,x_negative_solid,x_positive_solid,z_negative_solid,z_positive_solid\n");
            for (int depth = 0; depth <= MAX_DEPTH; depth++) {
                int y = 64 - depth;
                csv.write(depth + "," + y + "," + wall(sampler, y, true, -1) + "," + wall(sampler, y, true, 1)
                        + "," + wall(sampler, y, false, -1) + "," + wall(sampler, y, false, 1) + "\n");
            }
        }
        writeHabitats(output, sampler);
        writeSupportMetrics(output, sampler, parameters);
        System.out.println("Exported actual sampler sections/boundaries to " + output);
    }

    private static void writeSupportMetrics(Path output, ParametricTerrainSampler actual, TerrainParameters p) throws Exception {
        var c = p.cavity();
        var bare = new ParametricTerrainSampler(new TerrainParameters(p.seed(), p.surface(),
                new TerrainParameters.Cavity(c.mouthRadius(),c.firstLayerTopY(),c.declaredLowerBoundY(),
                        c.lowerRadius(),c.ledgePeriod(),c.ledgeWidth(),0),p.noise()));
        List<String> profiles = new ArrayList<>();
        for (int axis = 0; axis < 4; axis++) for (int y = 0; y > -1100; y--) {
            int[] ray = point(axis,500,0);
            if (actual.cavityRadius(ray[0],y+1,ray[1])-actual.cavityRadius(ray[0],y,ray[1]) < 30) continue;
            int cap = Math.min(300,y-c.declaredLowerBoundY());
            int massArea = 0, contiguous = 0;
            boolean connected = true;
            List<String> samples = new ArrayList<>();
            for (int below = 0; below <= cap; below++) {
                int baselineBoundary = firstSolid(bare,axis,y-below);
                int actualBoundary = firstSolid(actual,axis,y-below);
                int extension = Math.max(0,baselineBoundary-actualBoundary);
                massArea += extension;
                if (connected && extension > 0) contiguous++; else connected = false;
                if (below % 40 == 0 || below == cap) samples.add("{\"belowCapMetres\":"+below
                        +",\"actualBoundaryRadialMetres\":"+actualBoundary+",\"bareBoundaryRadialMetres\":"+baselineBoundary
                        +",\"additionalSolidRunMetres\":"+extension+"}");
            }
            profiles.add("{\"axis\":"+axis+",\"capY\":"+y+",\"queryDepthCapMetres\":"+cap
                    +",\"contiguousExtraRockRowsIncludingCap\":"+contiguous
                    +",\"extraSolidCrossSectionAreaSquareMetresWithinQueryCap\":"+massArea
                    +",\"samples\":["+String.join(",",samples)+"]}");
        }
        Files.writeString(output.resolve("support-depth-metrics.json"),
                "{\"status\":\"RECONSTRUCTION_DIAGNOSTIC_ACTUAL_VOXELS\",\"userShapeReference\":\"1-\u0424\u043e\u0442\u043e-1.jpg\","
                +"\"measurement\":\"full overlapping sampled profile versus identical parameters with zero ledgeProjection; rows1m, radial1m\","
                +"\"overlapCaveat\":\"metrics include merged neighbouring bodies; not isolated support attribution\","
                +"\"characteristicSupportDepthMetresProposal\":170,\"seededIndividualSupportDepthRangeMetres\":[102,238],"
                +"\"profiles\":["+String.join(",\n",profiles)+"]}\n",StandardOpenOption.CREATE_NEW);
    }

    private static int firstSolid(ParametricTerrainSampler sampler, int axis, int y) {
        for (int radial = 0; radial <= RADIAL_EXTENT; radial++) {
            int[] p = point(axis,radial,0);
            if (solid(sampler.sample(p[0],y,p[1]))) return radial;
        }
        throw new IllegalStateException("Missing known wall within scientific slice");
    }

    private record Habitat(int axis,int minX,int maxX,int referenceY,int minZ,int maxZ,
            int grass,int rock,int soil,int minY,int maxY,int maxStep,int[][] floors) {}

    private static void writeHabitats(Path output, ParametricTerrainSampler sampler) throws Exception {
        List<Habitat> habitats=new ArrayList<>();
        for(int axis=0;axis<4;axis++) for(int depth=80;depth<1300;depth+=4) {
            int y=64-depth;
            Habitat best=null;
            for(int shift=-120;shift<=120;shift+=20) for(int radial=180;radial<=650;radial+=5) {
                int[] anchor=point(axis,radial,shift);
                if(!solid(sampler.sample(anchor[0],y,anchor[1]))||!air(sampler.sample(anchor[0],y+1,anchor[1])))continue;
                Habitat actual=checkHabitat(sampler,axis,radial,shift,y);
                if(actual==null)continue;
                if(best==null||actual.grass>best.grass)best=actual;
                break;
            }
            if(best!=null){habitats.add(best);depth+=40;}
        }
        long fertile=habitats.stream().filter(h->h.grass>=4800&&h.soil>=4800).count();
        long fullBoth=habitats.stream().filter(h->h.grass==6400&&h.soil==6400).count();
        long fullGrass=habitats.stream().filter(h->h.grass==6400).count();
        if(habitats.size()<3||fertile<2)throw new IllegalStateException("Proposal lacks connected natural broad habitats");
        List<String> json=new ArrayList<>();
        for(Habitat h:habitats){
            StringBuilder positions=new StringBuilder();
            for(int x:new int[]{h.minX,h.maxX})for(int z:new int[]{h.minZ,h.maxZ}){
                int groundY=h.floors[x-h.minX][z-h.minZ];
                if(!positions.isEmpty())positions.append(',');
                positions.append("{\"x\":").append(x).append(",\"groundY\":").append(groundY).append(",\"z\":").append(z)
                        .append(",\"ground\":\"").append(((TerrainSample.Known)sampler.sample(x,groundY,z)).material())
                        .append("\",\"clearHeadroomThroughQueryCapMetres\":24}");
            }
            json.add("{\"axis\":"+h.axis+",\"minX\":"+h.minX+",\"maxX\":"+h.maxX+",\"referenceY\":"+h.referenceY
                    +",\"groundYMin\":"+h.minY+",\"groundYMax\":"+h.maxY+",\"maximumFourNeighbourStepMetres\":"+h.maxStep
                    +",\"minZ\":"+h.minZ+",\"maxZ\":"+h.maxZ+",\"sideMetres\":80,\"connectedFourNeighbours\":true,"
                    +"\"areaSquareMetres\":6400,\"fullyCheckedGroundCells\":6400,\"fullyCheckedAirCells\":153600,"
                    +"\"minimumClearHeadroomFromEachGroundThroughQueryCapMetres\":24,\"grassCells\":"+h.grass
                    +",\"bareRockCells\":"+h.rock+",\"soilCellsOneBelow\":"+h.soil+",\"grassFraction\":"+(h.grass/6400.0)
                    +",\"soilFractionOneBelow\":"+(h.soil/6400.0)+",\"proofPositions\":["+positions+"]}");
        }
        Habitat chosen=habitats.stream().filter(h->h.grass>=4800&&h.soil>=4800).findFirst().orElseThrow();
        try(BufferedWriter csv=Files.newBufferedWriter(output.resolve("habitat-ground-grid.csv"),StandardOpenOption.CREATE_NEW)){
            csv.write("x,z,groundY,groundMaterial,soilOneBelow,clearHeadroomCapMetres\n");
            for(int x=chosen.minX;x<=chosen.maxX;x++)for(int z=chosen.minZ;z<=chosen.maxZ;z++){
                int floor=chosen.floors[x-chosen.minX][z-chosen.minZ];
                TerrainMaterial material=((TerrainSample.Known)sampler.sample(x,floor,z)).material();
                boolean soil=sampler.sample(x,floor-1,z) instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.SOIL;
                csv.write(x+","+z+","+floor+","+material+","+soil+",24\n");
            }
        }
        Files.writeString(output.resolve("habitat-proof.json"),"{\"status\":\"REVIEW_PROPOSAL_NOT_ECOSYSTEM_IMPLEMENTATION\","
                +"\"samplingStepMetres\":1,\"sideMetres\":80,\"headroomDiagnosticCapMetres\":24,\"capIsNotCanonicalTreeHeight\":true,"
                +"\"basis\":\"each ground varies by column; all153600 AIR voxels checked from each floor; four-neighbour step<=1m\","
                +"\"habitatNeighbourhoodCount\":"+habitats.size()+",\"mostlyFertile75PercentPatches\":"+fertile
                +",\"fullyGrassPatches\":"+fullGrass+",\"fullyGrassAndSoilPatches\":"+fullBoth
                +",\"chosenGrid\":{\"filename\":\"habitat-ground-grid.csv\",\"minX\":"+chosen.minX+",\"maxX\":"+chosen.maxX
                +",\"minZ\":"+chosen.minZ+",\"maxZ\":"+chosen.maxZ+"},\"habitats\":["+String.join(",\n",json)+"]}\n",StandardOpenOption.CREATE_NEW);
    }

    private static Habitat checkHabitat(ParametricTerrainSampler sampler,int axis,int radial,int shift,int referenceY){
        for(int dr:new int[]{0,39,79})for(int tangent:new int[]{shift-40,shift,shift+39}){
            int[] p=point(axis,radial+dr,tangent);
            int floor=findGround(sampler,p[0],p[1],referenceY);
            if(floor==Integer.MIN_VALUE)return null;
            for(int dy=1;dy<=24;dy++)if(!air(sampler.sample(p[0],floor+dy,p[1])))return null;
        }
        int[] a=point(axis,radial,shift-40),b=point(axis,radial+79,shift+39);
        int minX=Math.min(a[0],b[0]),maxX=Math.max(a[0],b[0]),minZ=Math.min(a[1],b[1]),maxZ=Math.max(a[1],b[1]);
        int[][] floors=new int[80][80];
        int grass=0,rock=0,soil=0,minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE,maxStep=0;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++){
            int floor=findGround(sampler,x,z,referenceY);
            if(floor==Integer.MIN_VALUE)return null;
            int ix=x-minX,iz=z-minZ;
            if(ix>0)maxStep=Math.max(maxStep,Math.abs(floor-floors[ix-1][iz]));
            if(iz>0)maxStep=Math.max(maxStep,Math.abs(floor-floors[ix][iz-1]));
            if(maxStep>1)return null;
            floors[ix][iz]=floor;minY=Math.min(minY,floor);maxY=Math.max(maxY,floor);
            for(int dy=1;dy<=24;dy++)if(!air(sampler.sample(x,floor+dy,z)))return null;
            TerrainMaterial m=((TerrainSample.Known)sampler.sample(x,floor,z)).material();
            if(m==TerrainMaterial.GRASS)grass++;if(m==TerrainMaterial.ROCK)rock++;
            if(sampler.sample(x,floor-1,z) instanceof TerrainSample.Known k&&k.material()==TerrainMaterial.SOIL)soil++;
        }
        if(maxY-minY<3)return null;
        return new Habitat(axis,minX,maxX,referenceY,minZ,maxZ,grass,rock,soil,minY,maxY,maxStep,floors);
    }

    private static int findGround(ParametricTerrainSampler sampler,int x,int z,int referenceY){
        for(int y=referenceY+40;y>=referenceY-40;y--)if(solid(sampler.sample(x,y,z))&&air(sampler.sample(x,y+1,z)))return y;
        return Integer.MIN_VALUE;
    }
    private static int[] point(int axis, int radial, int tangent) {
        return switch (axis) {
            case 0 -> new int[]{radial,tangent}; case 1 -> new int[]{-radial,tangent};
            case 2 -> new int[]{tangent,radial}; default -> new int[]{tangent,-radial};
        };
    }
    private static boolean solid(TerrainSample sample) {
        return sample instanceof TerrainSample.Known k && (k.material() == TerrainMaterial.ROCK
                || k.material() == TerrainMaterial.SOIL || k.material() == TerrainMaterial.GRASS);
    }
    private static boolean air(TerrainSample sample) {
        return sample instanceof TerrainSample.Known k && k.material() == TerrainMaterial.AIR;
    }

    private static String wall(ParametricTerrainSampler sampler, int y, boolean alongX, int sign) {
        for (int distance = 0; distance <= RADIAL_EXTENT; distance++) {
            int coordinate = sign * distance;
            var sample = sampler.sample(alongX ? coordinate : 0, y, alongX ? 0 : coordinate);
            // At the surface, exposed grass is the first geological solid, too.
            if (sample instanceof TerrainSample.Known known && (known.material() == TerrainMaterial.ROCK
                    || known.material() == TerrainMaterial.SOIL || known.material() == TerrainMaterial.GRASS))
                return Integer.toString(coordinate);
        }
        return "";
    }

    private static int rgb(TerrainSample sample) {
        if (!(sample instanceof TerrainSample.Known known)) return 0xd92e8a;
        return switch (known.material()) {
            case AIR -> 0xe8f1fa; case ROCK -> 0x5b6470; case SOIL -> 0x84664d;
            case GRASS -> 0x5c8a46; case WATER -> 0x377cac;
        };
    }
}


