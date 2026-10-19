package dev.madeinabyss.kernel.concurrent;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Soft owner-thread budget. Individual native calls cannot be preempted; overruns are measured. */
public final class FrameWorkBudget {
    private final LongSupplier clock;private final long start,budget;
    public FrameWorkBudget(LongSupplier clock,long budget){this.clock=Objects.requireNonNull(clock);if(budget<=0)throw new IllegalArgumentException("Frame budget must be positive");this.budget=budget;start=clock.getAsLong();}
    public boolean available(){return elapsed()<budget;}
    public long elapsed(){return clock.getAsLong()-start;}
}
