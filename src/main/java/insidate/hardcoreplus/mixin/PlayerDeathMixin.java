package insidate.hardcoreplus.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import insidate.hardcoreplus.Hardcoreplus;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ServerPlayerEntity.class)
public class PlayerDeathMixin {
    // Use mod-wide guard to prevent re-entrant kills while we are already processing the mass-death
    // Shared with the debug command
    // Note: Hardcoreplus.PROCESSING ensures a single shared guard across classes

    // Inject into the start of the onDeath method for server players
    @Inject(at = @At("HEAD"), method = "onDeath(Lnet/minecraft/entity/damage/DamageSource;)V")
    private void onDeath(net.minecraft.entity.damage.DamageSource source, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        MinecraftServer server = self.getServer();

        if (server == null) return;
        // Only run this logic on dedicated servers (leave singleplayer vanilla)
        try {
            if (!server.isDedicated()) {
                Hardcoreplus.LOGGER.debug("[hcp mixin] Not a dedicated server; skipping reset behavior");
                return;
            }
        } catch (Throwable ignored) {
            // If mapping differs or method is unavailable, conservatively do nothing
            return;
        }
    Hardcoreplus.LOGGER.debug("[hcp mixin] onDeath invoked for {}", self.getGameProfile().getName());

        // SaveProperties / world properties expose whether the world is hardcore
        try {
            boolean isHardcore = false;
            if (server.getSaveProperties() != null) {
                // SaveProperties has an isHardcore() method in current mappings
                isHardcore = server.getSaveProperties().isHardcore();
            }

            if (!isHardcore) return;
            Hardcoreplus.LOGGER.debug("[hcp mixin] World is hardcore, proceeding to mass-kill");
            // If already processing (we triggered reset), don't re-enter
            if (!Hardcoreplus.PROCESSING.compareAndSet(false, true)) {
                Hardcoreplus.LOGGER.debug("[hcp mixin] Reset already in progress; skipping re-entry");
                return;
            }

            Hardcoreplus.LOGGER.info("[hcp mixin] All players dead in hardcore world — requesting reset and server stop");
            try {
                Hardcoreplus.requestResetAndStop(server);
            } finally {
                Hardcoreplus.PROCESSING.set(false);
            }
        } finally {
            Hardcoreplus.PROCESSING.set(false);
            Hardcoreplus.LOGGER.debug("[hcp mixin] Mass-kill processing flag cleared");
        }
    }
}
