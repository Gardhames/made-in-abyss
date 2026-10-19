package dev.madeinabyss.forge.cube;

/** Owner-thread native section input version. Palette writes outside section APIs are not covered. */
public interface NativeLightRevision {
    long mia$lightContentRevision();
    boolean mia$lightInputReady();
}
