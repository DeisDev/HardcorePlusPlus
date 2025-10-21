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

            // If already processing (we triggered kills), don't re-enter
            if (!Hardcoreplus.PROCESSING.compareAndSet(false, true)) {
                Hardcoreplus.LOGGER.debug("[hcp mixin] Mass-kill already in progress; skipping re-entry");
                return;
            }

            Hardcoreplus.LOGGER.info("[hcp mixin] Initiating mass-kill for all players");

            // Kill every player on the server (except already-dead ones)
            server.getPlayerManager().getPlayerList().forEach(player -> {
                try {
                    if (!player.isDead() && player.isAlive()) {
                        // Call kill() to mark the entity as dead and trigger death handling
                            try {
                                Hardcoreplus.LOGGER.info("[hcp mixin] Killing player: {}", player.getGameProfile().getName());
                                net.minecraft.server.world.ServerWorld serverWorld = (net.minecraft.server.world.ServerWorld) player.getWorld();
                                player.kill(serverWorld);
                            } catch (Throwable t) {
                                // Fallback: set health to 0
                                Hardcoreplus.LOGGER.warn("[hcp mixin] kill() failed for {} - falling back to setHealth(0)", player.getGameProfile().getName(), t);
                                try { player.setHealth(0.0F); } catch (Throwable ignored) {}
                            }
                    }
                } catch (Throwable t) {
                    Hardcoreplus.LOGGER.warn("[hcp mixin] Exception while attempting to kill player {}", player.getGameProfile().getName(), t);
                }
            });
        } finally {
            Hardcoreplus.PROCESSING.set(false);
            Hardcoreplus.LOGGER.debug("[hcp mixin] Mass-kill processing flag cleared");
        }
    }
}
