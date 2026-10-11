package dev.vox.lss.sponge;

import dev.vox.lss.platform.LoaderServices;
import dev.vox.lss.platform.SpongeLoaderServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The NeoForge seam test's twin (sponge-support-plan.md §5.1): SpongeLoaderServices
 * overrides every LoaderServices method — abstract AND every default that the NeoForge
 * server impl overrides (permissions, the diag token, diagnostics versions) — plus the
 * vanish seam, the one default only Sponge must replace. A default silently inherited
 * here would answer the no-provider fallback on a live server.
 */
class SpongeLoaderSeamContractTest {

    private static final Pattern OVERRIDE = Pattern.compile(
            "@Override\\s+(?:@\\w+\\s+)*public\\s+[\\w.<>?, \\[\\]]+\\s+(\\w+)\\s*\\(");

    @Test
    void overridesEverySeamMethodTheNeoForgeServerImplOverridesPlusVanish() {
        var spongeDeclared = Arrays.stream(SpongeLoaderServices.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic()).map(Method::getName).collect(Collectors.toSet());
        var required = new TreeSet<String>();
        for (Method m : LoaderServices.class.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) required.add(m.getName());
        }
        String neo = SpongeTestSupport.read(SpongeTestSupport.repoRoot()
                .resolve("neoforge/src/main/java/dev/vox/lss/platform/NeoForgeLoaderServices.java"));
        var matcher = OVERRIDE.matcher(neo);
        Set<String> seam = Arrays.stream(LoaderServices.class.getMethods()).map(Method::getName).collect(Collectors.toSet());
        while (matcher.find()) {
            if (seam.contains(matcher.group(1))) required.add(matcher.group(1));
        }
        required.add("vanishVisibility");
        var missing = new TreeSet<>(required);
        missing.removeAll(spongeDeclared);
        assertTrue(missing.isEmpty(), "SpongeLoaderServices inherits seam defaults: " + missing);
    }
}
