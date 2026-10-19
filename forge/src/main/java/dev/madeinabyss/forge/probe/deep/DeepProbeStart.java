package dev.madeinabyss.forge.probe.deep;

/** Bounded development setup, separate from gameplay traversal. */
public record DeepProbeStart(int startY, boolean flying, float flightSpeed) {
    public DeepProbeStart {
        if (startY < -21999 || startY > 80 || !Float.isFinite(flightSpeed) || flightSpeed <= 0 || flightSpeed > .6f)
            throw new IllegalArgumentException("Invalid bounded development start");
    }
    public static DeepProbeStart vertical(String direction, Integer startY, boolean flying) {
        return switch (direction) {
            case "descent" -> new DeepProbeStart(startY == null ? 80 : startY, flying, .05f);
            case "ascent" -> {
                if (startY != null) throw new IllegalArgumentException("Ascent has a fixed diagnostic floor start");
                yield new DeepProbeStart(-21999, true, .6f);
            }
            default -> throw new IllegalArgumentException("Unknown diagnostic direction");
        };
    }
}
