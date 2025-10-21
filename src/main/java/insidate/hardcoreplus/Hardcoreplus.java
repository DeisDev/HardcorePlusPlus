package insidate.hardcoreplus;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class Hardcoreplus implements ModInitializer {
	public static final String MOD_ID = "hardcoreplus";

	// This logger is used to write text to the console and the log file.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Shared guard used by the mixin and the debug command to prevent re-entrant mass-kills
	public static final AtomicBoolean PROCESSING = new AtomicBoolean(false);

	@Override
	public void onInitialize() {
		LOGGER.info("HardcorePlus+ initializing");

		// Register debug commands for development / debugging purposes
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
				CommandManager.literal("hcp")
					.then(CommandManager.literal("masskill").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
						ServerCommandSource source = ctx.getSource();
						MinecraftServer server = source.getServer();
						if (server == null) {
							source.sendFeedback(() -> Text.literal("Server not available."), false);
							return 0;
						}

						boolean isHardcore = false;
						if (server.getSaveProperties() != null) {
							isHardcore = server.getSaveProperties().isHardcore();
						}

						if (!isHardcore) {
							source.sendFeedback(() -> Text.literal("World is not hardcore; aborting masskill."), false);
							return 0;
						}

						if (!PROCESSING.compareAndSet(false, true)) {
							source.sendFeedback(() -> Text.literal("Mass-kill already in progress."), false);
							return 0;
						}

						try {
							server.getPlayerManager().getPlayerList().forEach(player -> {
								try {
									if (!player.isDead() && player.isAlive()) {
										LOGGER.info("[hcp masskill] Killing player: {}", player.getGameProfile().getName());
										try {
											net.minecraft.server.world.ServerWorld serverWorld = (net.minecraft.server.world.ServerWorld) player.getWorld();
											player.kill(serverWorld);
										} catch (Throwable t) {
											LOGGER.warn("[hcp masskill] kill() failed for {} - falling back to setHealth(0)", player.getGameProfile().getName(), t);
											try { player.setHealth(0.0F); } catch (Throwable ignored) {}
										}
									}
								} catch (Throwable t) {
									LOGGER.warn("[hcp masskill] Exception while attempting to kill player {}", player.getGameProfile().getName(), t);
								}
							});
						} finally {
							PROCESSING.set(false);
						}

						source.sendFeedback(() -> Text.literal("Mass-kill executed."), false);
						return 1;
					}))
					.then(CommandManager.literal("status").requires(src -> src.hasPermissionLevel(0)).executes(ctx -> {
						ServerCommandSource source = ctx.getSource();
						MinecraftServer server = source.getServer();
						if (server == null) {
							source.sendFeedback(() -> Text.literal("Server not available."), false);
							return 0;
						}

						boolean isHardcore = false;
						if (server.getSaveProperties() != null) isHardcore = server.getSaveProperties().isHardcore();

						String msg = String.format("Hardcore: %s, Processing: %s, Online players: %d",
								isHardcore, PROCESSING.get(), server.getPlayerManager().getPlayerList().size());
						source.sendFeedback(() -> Text.literal(msg), false);
						return 1;
					}))
			);
		});
	}
}