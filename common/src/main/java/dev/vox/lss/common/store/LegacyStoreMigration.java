package dev.vox.lss.common.store;

/**
 * The one-shot migration rule for a store written before the {@code build_mc} marker
 * existed (paper-version-guard-store-plan §3.2). It is a migration rule, not a trust model:
 * it decides only what happens to a store that carries no marker, once, and every later
 * open compares the stamped marker like any other core key.
 */
public enum LegacyStoreMigration {
    /** Stamp the marker and keep the rows. Fabric, NeoForge and Sponge: their loaders refuse
     *  a jar built for another Minecraft version, so an unmarked store was written by a
     *  matching jar. */
    REFRESH,
    /** Drop and rebuild once. Paper and Folia: before 0.20.0 nothing stopped a jar for another
     *  Minecraft version from enabling, and a store it poisoned carries the same meta as a
     *  healthy one. */
    DROP
}
