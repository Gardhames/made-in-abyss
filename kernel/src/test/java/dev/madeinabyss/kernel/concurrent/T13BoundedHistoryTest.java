package dev.madeinabyss.kernel.concurrent;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class T13BoundedHistoryTest {
    @Test void diagnosticRepeatHistoryPlateausAndReportsLostCoverage()throws Exception {
        Class<?> type=assertDoesNotThrow(()->Class.forName("dev.madeinabyss.kernel.concurrent.BoundedDiagnosticHistory"),"Repeat diagnostics must have a finite history");
        Object history=type.getConstructor(int.class).newInstance(64);var add=type.getMethod("add",Object.class);
        for(int i=0;i<64;i++)assertEquals(true,add.invoke(history,i));
        for(int i=0;i<64;i++)assertEquals(false,add.invoke(history,i));
        for(int i=64;i<10000;i++)assertEquals(true,add.invoke(history,i));
        assertEquals(64,type.getMethod("size").invoke(history));assertEquals(9936L,type.getMethod("untracked").invoke(history));
    }
}
