package dev.madeinabyss.kernel.terrain.natural;

import java.util.*;

/** Exact immutable union of inclusive protected rectangles, indexed by signed 16m XZ cells.
 * Expanded long bounds are clipped for index construction, never wrapped. This is a small
 * profile-footprint index, not a world-sized map; oversized plans refuse before large allocation. */
public final class NaturalProtection {
    public static final int MAX_BOXES=65536,MAX_INDEXED_CELLS=65536,MAX_INDEXED_REFERENCES=262144;
    public record Box(long minX,long minY,long minZ,long maxX,long maxY,long maxZ) {
        public Box {if(minX>maxX||minY>maxY||minZ>maxZ)throw new IllegalArgumentException("Protected box needs ordered inclusive XYZ bounds");}
        boolean contains(int x,int y,int z){return x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
    }
    private record Cell(int x,int z) {}
    private final Map<Cell,Box[]> cells;
    private final int referenceCount;
    public NaturalProtection(List<Box> input) {
        Objects.requireNonNull(input);if(input.size()>MAX_BOXES)throw new IllegalArgumentException("Protected box count exceeds explicit budget");
        var staged=new HashMap<Cell,ArrayList<Box>>();int references=0;
        for(var b:List.copyOf(input)) {
            if(b.maxX()<Integer.MIN_VALUE||b.minX()>Integer.MAX_VALUE||b.maxY()<Integer.MIN_VALUE||b.minY()>Integer.MAX_VALUE||b.maxZ()<Integer.MIN_VALUE||b.minZ()>Integer.MAX_VALUE)continue;
            int minX=(int)Math.max(Integer.MIN_VALUE,b.minX()),maxX=(int)Math.min(Integer.MAX_VALUE,b.maxX());
            int minZ=(int)Math.max(Integer.MIN_VALUE,b.minZ()),maxZ=(int)Math.min(Integer.MAX_VALUE,b.maxZ());
            int firstX=Math.floorDiv(minX,16),lastX=Math.floorDiv(maxX,16),firstZ=Math.floorDiv(minZ,16),lastZ=Math.floorDiv(maxZ,16);
            long added=((long)lastX-firstX+1)*((long)lastZ-firstZ+1);
            if(added>MAX_INDEXED_REFERENCES-references)throw new IllegalArgumentException("Protected cell references exceed explicit budget");
            for(int x=firstX;x<=lastX;x++)for(int z=firstZ;z<=lastZ;z++) {
                var key=new Cell(x,z);var values=staged.get(key);
                if(values==null){if(staged.size()>=MAX_INDEXED_CELLS)throw new IllegalArgumentException("Protected XZ cells exceed explicit budget");values=new ArrayList<>();staged.put(key,values);}
                values.add(b);references++;
            }
        }
        var frozen=new HashMap<Cell,Box[]>();staged.forEach((key,values)->frozen.put(key,values.toArray(Box[]::new)));
        cells=Collections.unmodifiableMap(frozen);referenceCount=references;
    }
    public boolean contains(int x,int y,int z) {
        var boxes=cells.get(new Cell(Math.floorDiv(x,16),Math.floorDiv(z,16)));
        if(boxes==null)return false;
        for(var box:boxes)if(box.contains(x,y,z))return true;
        return false;
    }
    public int indexedCellCount(){return cells.size();}
    public int indexedReferenceCount(){return referenceCount;}
}
