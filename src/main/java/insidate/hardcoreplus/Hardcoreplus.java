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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Hardcoreplus implements ModInitializer {
	public static final String MOD_ID = "hardcoreplus";

	// This logger is used to write text to the console and the log file.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Shared guard used by the mixin and the debug command to prevent re-entrant mass-kills
	public static final AtomicBoolean PROCESSING = new AtomicBoolean(false);

	// Pending confirmation map for two-step dangerous commands (UUID -> expiryMillis)
	private static final UUID CONSOLE_UUID = new UUID(0L, 0L);
	private static final long CONFIRM_TIMEOUT_MS = 30_000L; // 30 seconds to confirm
	public static final Map<UUID, Long> PENDING_CONFIRM = new ConcurrentHashMap<>();

	@Override
	public void onInitialize() {
		LOGGER.info("HardcorePlus+ initializing");

		// Register debug commands for development / debugging purposes
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(
				CommandManager.literal("hcp")
					.then(CommandManager.literal("masskill").requires(src -> src.hasPermissionLevel(2))
						// Confirm literal: /hcp masskill confirm
						.then(CommandManager.literal("confirm").executes(ctx -> {
							ServerCommandSource source = ctx.getSource();
							MinecraftServer server = source.getServer();
							if (server == null) {
								source.sendFeedback(() -> Text.literal("Server not available."), false);
								return 0;
							}

							// Identify requester (player UUID or console UUID)
							UUID who = CONSOLE_UUID;
							try {
								if (source.getEntity() != null) who = source.getEntity().getUuid();
							} catch (Throwable ignored) { who = CONSOLE_UUID; }

							Long expiry = PENDING_CONFIRM.get(who);
							long now = System.currentTimeMillis();
							if (expiry == null || expiry < now) {
								PENDING_CONFIRM.remove(who);
								source.sendFeedback(() -> Text.literal("No pending mass-kill confirmation. Run /hcp masskill to request one."), false);
								return 0;
							}

							// Remove pending and execute
							PENDING_CONFIRM.remove(who);

							boolean isHardcore = false;
							if (server.getSaveProperties() != null) isHardcore = server.getSaveProperties().isHardcore();
							if (!isHardcore) {
								source.sendFeedback(() -> Text.literal("World is not hardcore; aborting masskill."), false);
								return 0;
							}

							performMassKill(server);
							source.sendFeedback(() -> Text.literal("Mass-kill executed."), false);
							return 1;
						}))
						// Initial request: /hcp masskill -> ask for confirmation
						.executes(ctx -> {
							ServerCommandSource source = ctx.getSource();
							MinecraftServer server = source.getServer();
							if (server == null) {
								source.sendFeedback(() -> Text.literal("Server not available."), false);
								return 0;
							}

							// Identify requester (player UUID or console UUID)
							UUID who = CONSOLE_UUID;
							try {
								if (source.getEntity() != null) who = source.getEntity().getUuid();
							} catch (Throwable ignored) { who = CONSOLE_UUID; }

							long expiry = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
							PENDING_CONFIRM.put(who, expiry);
							source.sendFeedback(() -> Text.literal("Mass-kill requested. Confirm with /hcp masskill confirm within 30 seconds."), false);
							return 1;
						})
					)
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

	/**
	 * Perform the mass-kill operation on all online players. This method is safe to call
	 * from the server thread and will use the PROCESSING guard to avoid re-entrancy.
	 */
	public static void performMassKill(MinecraftServer server) {
		if (server == null) return;
		if (!PROCESSING.compareAndSet(false, true)) {
			LOGGER.info("[hcp] performMassKill called but processing already true");
			return;
		}

		try {
			server.getPlayerManager().getPlayerList().forEach(player -> {
				try {
					if (!player.isDead() && player.isAlive()) {
						LOGGER.info("[hcp] Killing player: {}", player.getGameProfile().getName());
						try {
							net.minecraft.server.world.ServerWorld serverWorld = (net.minecraft.server.world.ServerWorld) player.getWorld();
							player.kill(serverWorld);
						} catch (Throwable t) {
							LOGGER.warn("[hcp] kill() failed for {} - falling back to setHealth(0)", player.getGameProfile().getName(), t);
							try { player.setHealth(0.0F); } catch (Throwable ignored) {}
						}
					}
				} catch (Throwable t) {
					LOGGER.warn("[hcp] Exception while attempting to kill player {}", player.getGameProfile().getName(), t);
				}
			});
		} finally {
			PROCESSING.set(false);
			LOGGER.debug("[hcp] performMassKill processing flag cleared");
		}
	}
}