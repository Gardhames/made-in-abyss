package dev.madeinabyss.kernel.cube;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeFluidBudgetTest {
    @Test void doubleSidedOceanSurfaceFits64And128MetreWindows() {
        for(int radius:new int[]{64,128}){
            int width=2*(radius+8);
            assertTrue(NativeFluidBudget.facesForRadius(radius)>=2*width*width);
        }
    }
    @Test void boundedAdmissionNeverPartiallyAddsOrOverflows() {
        int budget=NativeFluidBudget.facesForRadius(64);
        assertEquals(budget,NativeFluidBudget.add(budget-2,2,budget));
        assertThrows(IllegalStateException.class,()->NativeFluidBudget.add(budget,1,budget));
        assertThrows(IllegalArgumentException.class,()->NativeFluidBudget.add(-1,2,budget));
        assertThrows(IllegalArgumentException.class,()->NativeFluidBudget.facesForRadius(129));
        assertThrows(IllegalArgumentException.class,()->NativeFluidBudget.facesForRadius(0));
    }
    @Test void peakRetainedAndUploadAllocationHaveFixedCaps() {
        assertEquals(196608,NativeFluidBudget.MAX_FACES);
        assertEquals(8192,NativeFluidBudget.UPLOAD_BATCH_FACES);
        assertTrue(NativeFluidBudget.maximumRetainedCpuBytes()<=192L*1024*1024);
        assertTrue(NativeFluidBudget.maximumBatchBytes()<=4L*1024*1024);
        assertTrue(NativeFluidBudget.facesForRadius(128)>NativeFluidBudget.facesForRadius(64));
    }
}
