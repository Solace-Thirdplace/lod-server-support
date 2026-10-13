package xaero.map.common.config.option;

import xaero.lib.common.config.option.BooleanConfigOption;
import xaero.lib.common.config.option.RangeConfigOption;

/** Tier-1 stub — the map-writing switches and the writing distance the native ladder reads
 *  (WRITING_DISTANCE: -1 = unlimited, Xaero's own sentinel). */
public class WorldMapProfiledConfigOptions {
    public static final BooleanConfigOption LOAD_NEW_CHUNKS = new BooleanConfigOption();
    public static final BooleanConfigOption UPDATE_CHUNKS = new BooleanConfigOption();
    public static final RangeConfigOption WRITING_DISTANCE = new RangeConfigOption(-1);
}
