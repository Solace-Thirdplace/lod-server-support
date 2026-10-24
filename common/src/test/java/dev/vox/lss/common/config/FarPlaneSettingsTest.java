package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Far-plane plan #364 §3: {@code far_players.extend_far_plane} is off by default and lives in
 *  the experimental sidecar, so turning it on never makes the main file unreadable by 0.19.1. */
class FarPlaneSettingsTest {
    private static final String PATH = "far_players.extend_far_plane";

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test void offByDefaultAndAbsentFromNewFiles() {
        var schema = SettingsSchema.client();
        var codec = new YamlSettingsCodec<>(schema);
        assertFalse(schema.defaults().farPlayers().extendFarPlane());
        assertNull(SettingsSchema.lookup(YamlSettingsCodec.readTree(codec.defaults()), PATH),
                "new main files carry no key an older client would reject");
        assertTrue(YamlSettingsCodec.SIDECAR_PATHS.contains(PATH));
        var descriptor = schema.descriptorsByPath().get(PATH);
        assertNotNull(descriptor);
        assertFalse(descriptor.description().isBlank(), "the reference documents it");
    }

    @Test void turningItOnWritesOnlyTheSidecar() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.client());
        var on = codec.editSplit(codec.parse(codec.defaults()), Map.of(PATH, true));
        String main = new String(on.main(), StandardCharsets.UTF_8);
        assertFalse(main.contains("extend_far_plane:"), main);
        assertEquals(Boolean.TRUE, SettingsSchema.lookup(YamlSettingsCodec.readTree(on.sidecar()), PATH));
        assertTrue(codec.parse(on.main(), on.sidecar()).normalized().farPlayers().extendFarPlane());
        var off = codec.editSplit(codec.parse(on.main(), on.sidecar()), Map.of(PATH, false));
        assertNull(off.sidecar(), "the default needs no sidecar");
    }

    @Test void aMainFileCopyIsHonouredAndMovedOutOnTheNextSave() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.client());
        String template = new String(codec.defaults(), StandardCharsets.UTF_8);
        String withKey = template.replace("far_players:\n", "far_players:\n  extend_far_plane: true\n");
        assertNotEquals(template, withKey);
        var document = codec.parse(bytes(withKey));
        assertTrue(document.configured().farPlayers().extendFarPlane());
        var saved = codec.editSplit(document, Map.of("lod.receive", false));
        assertFalse(new String(saved.main(), StandardCharsets.UTF_8).contains("extend_far_plane:"));
        assertTrue(codec.parse(saved.main(), saved.sidecar()).configured().farPlayers().extendFarPlane());
    }
}
