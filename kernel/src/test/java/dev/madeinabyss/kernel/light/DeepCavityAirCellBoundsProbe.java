package dev.madeinabyss.kernel.light;

import dev.madeinabyss.kernel.terrain.*;
import java.util.Random;

/** Standalone independent mathematical oracle checks; neither Forge nor native acceptance. */
public final class DeepCavityAirCellBoundsProbe {
    static TerrainParameters approved(){return new TerrainParameters(20261003,
        new TerrainParameters.Surface(0,-64,1000,1000,80,64,650,800,100,160,3),
        new TerrainParameters.Cavity(500,64,-1286,650,180,170,280),new TerrainParameters.Noise(140,20,60,70));}
    public static void main(String[] args) {
        var p=approved();var bounds=new DeepCavityAirCellBounds(p);var raw=new ParametricTerrainSampler(p);
        var fast=new CompiledTerrainSampler(p);var c=p.cavity();
        var noShelves=new CompiledTerrainSampler(new TerrainParameters(p.seed(),p.surface(),
            new TerrainParameters.Cavity(500,64,-1286,650,180,170,0),p.noise()));
        int certified=0,carves=0,halo=0;var random=new Random(141024);long start=System.nanoTime();
        for(int y=-1286;y<=63;y++) {
            for(int s=0;s<23;s++)for(double sectorFraction:new double[]{-.001,0,.499,.999,1.001}) {
                double angle=(s+sectorFraction)*2*Math.PI/23;
                for(int r=128;r<=608;r+=16) {
                    int x=(int)Math.round(r*Math.cos(angle)),z=(int)Math.round(r*Math.sin(angle));
                    if(bounds.deepCellAir(x,y,z)) {
                        require(raw.sample(x,y,z).equals(new TerrainSample.Known(TerrainMaterial.AIR)),"Uncompiled geology "+x+","+y+","+z);
                        require(fast.sample(x,y,z).equals(new TerrainSample.Known(TerrainMaterial.AIR)),"Compiled geometry");certified++;
                    }
                    if(bounds.finalCarveAir(x,y,z)) {
                        double wall=noShelves.cavityRadius(x,y,z),retention=y>=-250?0:smooth((-250-y)/150.0);
                        double opening=wall-retention*Math.max(0,wall-fast.cavityRadius(x,y,z));
                        require((double)x*x+(double)z*z<opening*opening,"Actual compiled upper carve formula");carves++;
                    }
                }
            }
            for(int i=0;i<8;i++) {
                int x=random.nextInt(1280)-640,z=random.nextInt(1280)-640;
                if(bounds.deepCellAir(x,y,z)) {require(raw.sample(x,y,z).equals(new TerrainSample.Known(TerrainMaterial.AIR)),"Random signed oracle");certified++;}
            }
        }
        // Entire admitted cell boundary plus support extents, not merely a point-centred halo.
        for(int y:new int[]{-1286,-1254,-900,-700,-432,-431,-430,-400,-283,-251})
            for(int cx=-40;cx<40;cx+=3)for(int cz=-40;cz<40;cz+=3)if(bounds.deepCellAir(cx*16,y,cz*16))
                for(int ox:new int[]{-10,-1,0,7,15,16,25})for(int oz:new int[]{-10,-1,0,7,15,16,25})
                    for(int yy:new int[]{Math.max(-1286,Math.floorDiv(y,16)*16-32),Math.min(63,Math.floorDiv(y,16)*16+47)}) {
                        require(raw.sample(cx*16+ox,yy,cz*16+oz).equals(new TerrainSample.Known(TerrainMaterial.AIR)),"Expanded full-cell halo");halo++;
                    }
        for(int x:new int[]{Integer.MIN_VALUE,Integer.MAX_VALUE,-641,640})
            require(!bounds.guaranteedAir(x,-900,x),"Signed range guard");
        for(int y:new int[]{Integer.MIN_VALUE,-1287,64,Integer.MAX_VALUE})require(!bounds.guaranteedAir(0,y,0),"Unknown/upper target refusal");
        var wrong=new DeepCavityAirCellBounds(new TerrainParameters(p.seed()+1,p.surface(),p.cavity(),p.noise()));
        require(!wrong.guaranteedAir(0,-430,0)&&wrong.residentArrayBytes()==0,"Different source is refused");
        require(certified>100000&&carves>100000&&halo>10000,"Non-vacuous independent coverage");
        require(bounds.residentArrayBytes()<=55*1024,"Bounded resident primitive arrays");
        System.out.println("CANDIDATE_MATH_PASS constructionMs="+bounds.constructionNanos()/1e6+" residentArrayBytes="+bounds.residentArrayBytes()+" admitted16Cells="+bounds.admittedCellCount()+" geology="+certified+" finalCarve="+carves+" halo="+halo+" verificationMs="+(System.nanoTime()-start)/1e6);
        for(int y:new int[]{-430,-700,-900,-1200}) {
            int cells=0;for(int x=-640;x<640;x+=16)for(int z=-640;z<640;z+=16)if(bounds.deepCellAir(x,y,z))cells++;
            System.out.println("CANDIDATE_AREA y="+y+" admittedM2="+cells*256+" equivalentCircleRadius="+Math.sqrt(cells*256/Math.PI));
        }
    }
    private static double smooth(double x){return x*x*(3-2*x);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
