package xaero.map.world;

/** Tier-1 stub — world-save mode (singleplayer) ORs into both map-writing switches; the
 *  cave-mode type (0 off, 1 Layered, 2 Full) decides whether best-effort bands are written. */
public class MapDimension {
    public boolean usingWorldSave;
    public int caveModeType = 1;

    public boolean isUsingWorldSave() { return this.usingWorldSave; }

    public int getCaveModeType() { return this.caveModeType; }
}
