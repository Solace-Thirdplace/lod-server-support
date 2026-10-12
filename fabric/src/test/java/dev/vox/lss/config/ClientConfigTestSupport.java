package dev.vox.lss.config;

import dev.vox.lss.common.config.ClientSettings;
import dev.vox.lss.common.config.SettingsHandle;
import java.util.LinkedHashMap;

/** Test-only immutable snapshot injection, including intentional values below schema clamps. */
public final class ClientConfigTestSupport {
    private ClientConfigTestSupport() {}
    public static void set(String path, Object value) {
        try {
            var field = LSSClientConfig.class.getDeclaredField("handle");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") var handle = (SettingsHandle<ClientSettings>) field.get(LSSClientConfig.CONFIG);
            var prior = handle.state();
            var values = new LinkedHashMap<>(prior.effective().values());
            // ClientSettings.fromValues ignores unknown keys; a renamed path must fail loudly
            // instead of silently leaving the test on the persistent config's value.
            if (!values.containsKey(path)) throw new AssertionError("Unknown settings path: " + path);
            values.put(path, value);
            ClientSettings next = ClientSettings.fromValues(values);
            var state = SettingsHandle.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(handle, new SettingsHandle.State<>(next, next, next, next,
                    prior.revision() + 1, java.util.Set.of(), java.util.Set.of()));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
