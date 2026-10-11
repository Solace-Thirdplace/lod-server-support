package dev.vox.lss.sponge;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSPermissions;
import net.kyori.adventure.text.Component;
import org.spongepowered.api.service.permission.PermissionService;
import org.spongepowered.api.util.Tristate;
import org.spongepowered.plugin.PluginContainer;

import java.util.List;

/**
 * Sponge's equivalent of Paper's plugin.yml permission block (sponge-support-plan.md
 * §1.6). Sponge has no descriptor defaults, so the nodes are registered as
 * {@code PermissionDescription}s at {@code StartingEngineEvent} — after a replacement
 * permission service (LuckPerms) is final. The table mirrors plugin.yml exactly,
 * SpongePermissionsTest pins it:
 * <ul>
 *   <li>{@code lss.use} / {@code vss.use}: TRUE — the service gate is a DENY tool; arming
 *       it changes nothing until an admin grants a negative.</li>
 *   <li>{@code lss.farplayers.hidden} / {@code vss.farplayers.hidden}: FALSE — the
 *       grant-model privacy lever; both spellings, for the LSS↔VSS jar swap.</li>
 *   <li>{@code <brand>.admin}: NO default (UNDEFINED), so Sponge's default service falls
 *       back to op status — but only for ops at or above the server's
 *       {@code op-permission-level} (default 4), unlike Paper's {@code default: op},
 *       which admits any op level. Grant the node explicitly for lower-level ops.</li>
 * </ul>
 */
public final class SpongePermissions {
    private SpongePermissions() {}

    /** One registered node: id, description, default (null = UNDEFINED). */
    public record Node(String id, String description, Tristate defaultValue) {}

    public static List<Node> nodes() {
        return List.of(
                new Node(Brand.lowerShortName() + ".admin",
                        "Access to " + Brand.shortName() + " admin commands", null),
                new Node(LSSPermissions.FARPLAYERS_HIDDEN_LSS,
                        "Players with this permission are never shown as far players", Tristate.FALSE),
                new Node(LSSPermissions.FARPLAYERS_HIDDEN_VSS,
                        "Players with this permission are never shown as far players (VSS spelling)", Tristate.FALSE),
                new Node(LSSPermissions.SERVICE_LSS,
                        "Receive LOD data when requireServicePermission is enabled", Tristate.TRUE),
                new Node(LSSPermissions.SERVICE_VSS,
                        "Receive LOD data when requireServicePermission is enabled (VSS spelling)", Tristate.TRUE));
    }

    static void register(PermissionService service, PluginContainer container) {
        for (var node : nodes()) {
            var builder = service.newDescriptionBuilder(container)
                    .id(node.id())
                    .description(Component.text(node.description()));
            if (node.defaultValue() != null) {
                builder.defaultValue(node.defaultValue());
            }
            builder.register();
        }
    }
}
