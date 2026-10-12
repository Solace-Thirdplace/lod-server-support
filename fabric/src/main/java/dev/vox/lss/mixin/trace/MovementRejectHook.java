package dev.vox.lss.mixin.trace;

import dev.vox.lss.trace.MoveDesyncHooks;
import dev.vox.lss.trace.MoveDesyncTracer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The move-desync tracer's four observation points (move-desync-tracer-plan.md §1.2). Every
 * body is one static-gate check plus a delegation to {@link MoveDesyncHooks} (the
 * {@code ChunkSaveDataHook} pattern — logic in a mixin is untestable logic); all injects are
 * {@code require = 0} and this class lives in the non-required {@code lss-trace.mixins.json},
 * so any drift degrades to missing rows, never a crash (§0 constraint 4).
 *
 * <p>LINE FLAVOR (26.3, bytecode-verified 2026-10-12): vanilla moved the position checks out
 * of {@code handleMovePlayer} into the private
 * {@code handlePlayerPositionChange(DDDFFZZ)V}, which {@code handleMovePlayer} calls with the
 * packet's claim resolved against the player's current position — and which
 * {@code handleAcceptTeleportPacket} ALSO calls. The entry capture stays on
 * {@code handleMovePlayer} (post-ensure) and stashes the packet; the three event injects
 * target {@code handlePlayerPositionChange} and fire only when its arguments are exactly that
 * packet's claim ({@link #lss$fromMovePacket}), so the teleport-accept path never emits a row
 * against a stale packet. The shared {@link MoveDesyncHooks} API is unchanged.
 *
 * <p>Targeting scheme (reviews F-6/F-7): the two vanilla warns have DISTINCT slf4j
 * descriptors — {@code warn(String,Object[])} is the "moved too quickly!" site,
 * {@code warn(String,Object)} the "moved wrongly!" site — so no ordinals are needed there
 * ({@code remap = false}: slf4j is not an MC class). The rejection teleport is anchored
 * semantically: "the first {@code teleport(DDDFF)V} after the wrongly-warn site" via a
 * {@code @Slice}, which also fires for the SILENT {@code isEntityCollidingWithAnythingNew}
 * rejection (bytecode-ordered after the warn, whether or not the warn executed at runtime).
 * A bare ordinal would silently retarget if vanilla reordered its teleports; the slice encodes
 * what the code means, and the Tier 1 ASM contract scan is its tripwire.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MovementRejectHook {

    /** Pre-move position captured at the post-ensure entry (review F-5): at that point
     *  the entity has not moved — these equal the method's {@code startX/Y/Z} locals
     *  exactly, making claimed-target recomputation exact for Rot/StatusOnly packets
     *  whose {@code packet.get*(default)} falls back to the current position. Also the
     *  rejection's {@code restored} target. */
    @Unique
    private double lss$startX;

    @Unique
    private double lss$startY;

    @Unique
    private double lss$startZ;

    /** True once the post-ensure capture has run at least once. Java-default false with
     *  no initializer (mixin field initializers do not merge), so a partially-applied
     *  config — every inject is require = 0 — degrades to {@code restored} ABSENT
     *  instead of a confidently-wrong zero triple (review C-10). */
    @Unique
    private boolean lss$startCaptured;

    /** The packet the latest post-ensure entry captured (26.3: the event injects run in
     *  {@code handlePlayerPositionChange}, which has no packet parameter). */
    @Unique
    private ServerboundMovePlayerPacket lss$movePacket;

    /** Packet-identity token, not a boolean (review F-3): {@code logged_wrongly} is "the
     *  wrongly warn fired for THIS packet", self-expiring with the packet — no HEAD clear
     *  to forget, and a partially-applied mixin degrades to {@code logged_wrongly:false}
     *  instead of latching true. Retains a strong reference to the last rejected packet
     *  for the listener's lifetime — a few hundred bytes that die with the connection;
     *  deliberate (Fable F2-11). */
    @Unique
    private Object lss$wronglyPacket;

    // NOT a HEAD inject: handleMovePlayer's first statement is
    // PacketUtils.ensureRunningOnSameThread, which on the NETTY thread schedules the
    // packet onto the main thread and THROWS — so a HEAD inject runs on the netty
    // event loop for every packet (and again on the server thread), racing the gap
    // clock and the @Unique captures (review A-1/C-1). Injecting AFTER that call
    // executes exactly once, on the server thread, before anything has moved the
    // player — the F-5 capture semantics preserved verbatim. The Tier 1 ASM pin
    // asserts ensureRunningOnSameThread is the method's first INVOKE.
    @Inject(method = "handleMovePlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void lss$onMoveHead(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!MoveDesyncTracer.enabled()) return;
        var self = (ServerGamePacketListenerImpl) (Object) this;
        this.lss$startX = self.player.getX();
        this.lss$startY = self.player.getY();
        this.lss$startZ = self.player.getZ();
        this.lss$startCaptured = true;
        this.lss$movePacket = packet;
        MoveDesyncHooks.onMoveHead(self);
    }

    /** The captured packet when this position change is exactly its claim (the
     *  {@code handleMovePlayer} call), else null (the teleport-accept call, or no capture). */
    @Unique
    private ServerboundMovePlayerPacket lss$fromMovePacket(double x, double y, double z) {
        var packet = this.lss$movePacket;
        if (packet == null || !this.lss$startCaptured) return null;
        return packet.getX(this.lss$startX) == x && packet.getY(this.lss$startY) == y
                && packet.getZ(this.lss$startZ) == z ? packet : null;
    }

    @Inject(method = "handlePlayerPositionChange",
            at = @At(value = "INVOKE",
                    target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;[Ljava/lang/Object;)V",
                    remap = false),
            require = 0)
    private void lss$onMovedTooQuickly(double x, double y, double z, float yRot, float xRot,
                                       boolean onGround, boolean horizontalCollision, CallbackInfo ci) {
        if (!MoveDesyncTracer.enabled()) return;
        var packet = lss$fromMovePacket(x, y, z);
        if (packet == null) return;
        var self = (ServerGamePacketListenerImpl) (Object) this;
        MoveDesyncHooks.onMovedTooQuickly(self, packet, lss$startX, lss$startY, lss$startZ, lss$startCaptured);
    }

    @Inject(method = "handlePlayerPositionChange",
            at = @At(value = "INVOKE",
                    target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V",
                    remap = false),
            require = 0)
    private void lss$onMovedWrongly(double x, double y, double z, float yRot, float xRot,
                                    boolean onGround, boolean horizontalCollision, CallbackInfo ci) {
        if (!MoveDesyncTracer.enabled()) return;
        var packet = lss$fromMovePacket(x, y, z);
        if (packet == null) return;
        var self = (ServerGamePacketListenerImpl) (Object) this;
        this.lss$wronglyPacket = packet;
        MoveDesyncHooks.onMovedWrongly(self, packet, lss$startX, lss$startY, lss$startZ, lss$startCaptured);
    }

    @Inject(method = "handlePlayerPositionChange",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V",
                    ordinal = 0),
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V",
                    remap = false)),
            require = 0)
    private void lss$onMoveRejected(double x, double y, double z, float yRot, float xRot,
                                    boolean onGround, boolean horizontalCollision, CallbackInfo ci) {
        if (!MoveDesyncTracer.enabled()) return;
        var packet = lss$fromMovePacket(x, y, z);
        if (packet == null) return;
        var self = (ServerGamePacketListenerImpl) (Object) this;
        MoveDesyncHooks.onMoveRejected(self, packet, lss$wronglyPacket == packet,
                lss$startX, lss$startY, lss$startZ, lss$startCaptured);
    }
}
