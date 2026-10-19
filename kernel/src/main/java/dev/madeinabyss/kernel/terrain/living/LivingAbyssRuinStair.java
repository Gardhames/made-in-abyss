package dev.madeinabyss.kernel.terrain.living;

/** Section20.6: seven-cell smoothing, monotonic profile, 1m rise and >=2m tread. */
public final class LivingAbyssRuinStair {
    private LivingAbyssRuinStair(){}
    public static int[] build(double[] ground){
        if(ground.length==0)return new int[0];double[] smooth=new double[ground.length];double previous=-Double.MAX_VALUE;
        for(int i=0;i<smooth.length;i++){double sum=0;for(int n=-3;n<=3;n++)sum+=ground[Math.max(0,Math.min(ground.length-1,i+n))];smooth[i]=previous=Math.max(previous,sum/7);}
        int[] steps=new int[ground.length];int y=(int)Math.floor(smooth[0]+.5),run=0;
        for(int i=0;i<steps.length;i++){if(smooth[i]-y>=.75&&run>=2){y++;run=0;}steps[i]=y;run++;}return steps;
    }
    public static double decay(double t){return Math.pow(1-LivingAbyssLedgeBody.clamp(t),2.2);}
}
