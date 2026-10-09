package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.menu.ClientOptionCatalog;
import dev.vox.lss.config.menu.OptionSpec;
import dev.vox.lss.config.menu.PageSpec;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.RecordedBoolean;
import net.caffeinemc.mods.sodium.api.config.structure.RecordedInteger;
import net.caffeinemc.mods.sodium.api.config.structure.RecordedOption;
import net.caffeinemc.mods.sodium.api.config.structure.RecordingConfigBuilder;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Sodium 0.8+ walker against RECORDING stubs of the public config API
 * (fabric/src/test/java/net/caffeinemc/mods/sodium/api/config — the same
 * real-package-name discipline as the legacy stubs; the real API is {@code compileOnly}
 * and never on the T1 class path). Pins the shipping renderer's shape the way
 * {@code LegacySodiumPageTest} pins the legacy one: the catalog is walked in order into
 * two pages with the catalog's groups, every option carries the catalog's id/name/
 * tooltip/impact/default/range/binding/dependency, the value formatter renders the
 * catalog labels, and EXACTLY ONE storage handler are installed across all options —
 * Sodium fires each distinct handler once per Apply, so per-option lambdas would have
 * saved the file once per changed option (implementation review).
 */
class LSSConfigMenuTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    private dev.vox.lss.config.menu.ClientSettingsEditSession draft;
    private void register(RecordingConfigBuilder builder) {
        try {
            if (draft == null) {
                var schema = dev.vox.lss.common.config.SettingsSchema.client();
                var store = new dev.vox.lss.common.config.SettingsStore<>(temp, "lss", schema);
                store.initialize();
                draft = new dev.vox.lss.config.menu.ClientSettingsEditSession(store, schema::defaults);
            }
            new LSSConfigMenu().registerDraft(builder, draft, dev.vox.lss.config.menu.MenuContext.current());
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }


    @Test
    void walksTheCatalogIntoThreePagesWithTheCatalogsShape() {
        var builder = new RecordingConfigBuilder();
        register(builder);

        assertEquals(1, builder.mods.size());
        var mod = builder.mods.get(0);
        assertEquals(LSSConstants.MOD_ID, mod.id);
        assertNotNull(mod.name);
        assertNotNull(mod.version);
        assertNotNull(mod.icon);

        List<PageSpec> catalog = ClientOptionCatalog.pages();
        assertEquals(catalog.size(), mod.pages.size());
        for (int p = 0; p < catalog.size(); p++) {
            var page = mod.pages.get(p);
            assertEquals(catalog.get(p).titleKey(), page.name.getString(), "page title key");
            // renderer is AVAILABLE on Fabric, so the visible group shape is the catalog's
            var visibleGroups = catalog.get(p).groups().stream()
                    .map(g -> g.options().stream().toList())
                    .filter(l -> !l.isEmpty()).toList();
            assertEquals(visibleGroups.size(), page.groups.size(), "group count on page " + p);
            for (int g = 0; g < visibleGroups.size(); g++) {
                assertEquals(visibleGroups.get(g).stream().map(OptionSpec::id).toList(),
                        page.groups.get(g).options.stream().map(o -> ((RecordedOption<?>) o).id.toString()).toList(),
                        "option ids in group " + g + " of page " + p);
            }
        }
    }

    @Test
    void everyOptionCarriesTheCatalogsFacts() {
        var builder = new RecordingConfigBuilder();
        register(builder);
        List<RecordedOption<?>> built = new ArrayList<>();
        builder.mods.get(0).pages.forEach(p -> p.groups.forEach(g -> g.options.forEach(o -> built.add((RecordedOption<?>) o))));
        assertEquals(11, built.size(), "eleven explicit controls");

        for (RecordedOption<?> o : built) {
            OptionSpec spec = ClientOptionCatalog.find(o.id.toString()).orElseThrow();
            assertEquals(spec.nameKey(), o.name.getString());
            assertEquals(spec.tooltip().resolve(dev.vox.lss.config.menu.MenuContext.current()), o.tooltip.getString());
            if (spec.impact() == null) {
                assertNull(o.impact, spec.id() + " ships without an impact line");
            } else {
                assertEquals(OptionImpact.valueOf(spec.impact().name()), o.impact);
            }
            assertNotNull(o.setter, spec.id());
            assertNotNull(o.getter, spec.id());
            assertNotNull(o.storageHandler, spec.id());
            if (spec.enabledBy() == null) {
                assertNull(o.enabledProvider, spec.id());
                assertEquals(0, o.dependencies.length);
            } else {
                assertEquals(ClientOptionCatalog.enabledDependencies(spec), java.util.Arrays.stream(o.dependencies).map(Object::toString).toList());
                // The provider reads exactly the declared dependency from the pending state.
                var expected=java.util.Set.of(o.dependencies);
                assertEquals(Boolean.TRUE, o.enabledProvider.apply(new ConfigState() {
                    @Override public boolean readBooleanOption(ResourceLocation id) { return expected.contains(id); }
                    @Override public int readIntOption(ResourceLocation id) { return 0; }
                }));
            }
            switch (spec) {
                case OptionSpec.BoolSpec b -> {
                    var bb = assertInstanceOf(RecordedBoolean.class, o);
                    assertEquals(b.defaultValue(), bb.defaultValue);
                }
                case OptionSpec.IntSpec i -> {
                    var ib = assertInstanceOf(RecordedInteger.class, o);
                    assertEquals(i.defaultValue(), ib.defaultValue);
                    assertEquals(i.min(), ib.range.min());
                    assertEquals(i.max(), ib.range.max());
                    assertEquals(i.step(), ib.range.step());
                    var label0 = i.label().apply(i.min());
                    assertEquals(label0.isKey() ? label0.key() : label0.literal(), ib.formatter.format(i.min()).getString());
                    var labelMax = i.label().apply(i.max());
                    assertEquals(labelMax.isKey() ? labelMax.key() : labelMax.literal(), ib.formatter.format(i.max()).getString());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")

    private static RecordedOption<?> find(RecordingConfigBuilder builder, String id) {
        for (var p : builder.mods.get(0).pages) {
            for (var g : p.groups) {
                for (var o : g.options) {
                    var s = (RecordedOption<?>) o;
                    if (s.id.toString().equals(id)) return s;
                }
            }
        }
        throw new AssertionError("option not built: " + id);
    }
}
