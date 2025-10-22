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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Optional;

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

	// Load config early
	ConfigManager.load();

		// Check for reset marker on server starting and handle backup/delete before world loads
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			// Only process reset marker for dedicated servers
			try {
				if (!server.isDedicated()) {
					LOGGER.debug("hc_reset check skipped: not a dedicated server");
					return;
				}
			} catch (Throwable ignored) {
				// If method unavailable, conservatively skip
				return;
			}
				try {
				java.nio.file.Path runDir = server.getRunDirectory();
				java.nio.file.Path marker = runDir.resolve("hc_reset.flag");
				if (!Files.exists(marker)) return;

				LOGGER.info("hc_reset.flag detected; preparing to rotate world");

				// determine level name
				String levelName = "world";
				java.nio.file.Path propsFile = runDir.resolve("server.properties");
				if (Files.exists(propsFile)) {
					try {
						Properties p = new Properties();
						p.load(Files.newInputStream(propsFile));
						levelName = Optional.ofNullable(p.getProperty("level-name")).orElse(levelName);
					} catch (IOException ignored) {}
				}

				java.nio.file.Path worldDir = runDir.resolve(levelName);
				boolean doBackup = ConfigManager.getBoolean("backup_old_worlds");
				boolean deleteInstead = ConfigManager.getBoolean("delete_instead_of_backup");
				String backupFolderName = ConfigManager.get("backup_folder_name");
				if (backupFolderName == null || backupFolderName.isEmpty()) backupFolderName = "Old Worlds";

				if (Files.exists(worldDir)) {
					if (doBackup && !deleteInstead) {
						java.nio.file.Path backupRoot = runDir.resolve(backupFolderName);
						if (!Files.exists(backupRoot)) Files.createDirectories(backupRoot);
						// Build backup target name using configurable format
						String format = ConfigManager.get("backup_name_format");
						if (format == null || format.isEmpty()) format = "%name%_%ts%";
						java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault());
						String ts = fmt.format(java.time.Instant.now());
						String id = java.util.UUID.randomUUID().toString().substring(0, 8);
						String backupName = format.replace("%name%", levelName).replace("%ts%", ts).replace("%id%", id);
						java.nio.file.Path backupTarget = backupRoot.resolve(backupName);
						try {
							Files.move(worldDir, backupTarget, StandardCopyOption.ATOMIC_MOVE);
							LOGGER.info("Moved old world to {}", backupTarget.toAbsolutePath());
						} catch (IOException e) {
							LOGGER.warn("Failed to move world to backup; attempting non-atomic move", e);
							try { Files.move(worldDir, backupTarget); } catch (IOException ex) { LOGGER.error("Failed to move old world", ex); }
						}
					} else {
						// delete
						try {
							// recursive delete
							Files.walk(worldDir)
									.sorted((a, b) -> b.compareTo(a))
									.forEach(p -> {
										try { Files.deleteIfExists(p); } catch (IOException ignored) {}
									});
							LOGGER.info("Deleted old world folder {}", worldDir.toAbsolutePath());
						} catch (IOException e) {
							LOGGER.error("Failed to delete old world folder", e);
						}
					}
				}

				// remove marker
				try { Files.deleteIfExists(marker); } catch (IOException ignored) {}
			} catch (Throwable t) {
				LOGGER.error("Exception while handling hc_reset.flag", t);
			}
		});

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
							try {
								if (!server.isDedicated() && source.getEntity() != null) {
									source.sendFeedback(() -> Text.literal("This command is only available on dedicated servers."), false);
									return 0;
								}
							} catch (Throwable ignored) {
								// conservative: reject
								source.sendFeedback(() -> Text.literal("This command is only available on dedicated servers."), false);
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
							try {
								if (!server.isDedicated() && source.getEntity() != null) {
									source.sendFeedback(() -> Text.literal("This command is only available on dedicated servers."), false);
									return 0;
								}
							} catch (Throwable ignored) {
								source.sendFeedback(() -> Text.literal("This command is only available on dedicated servers."), false);
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
	 * Write a reset marker and request a graceful server stop. The on-startup handler will
	 * move or delete the old world depending on config.
	 */
	public static void requestResetAndStop(MinecraftServer server) {
		if (server == null) return;
		try {
			if (!server.isDedicated()) {
				LOGGER.info("RequestResetAndStop refused: not a dedicated server");
				return;
			}
		} catch (Throwable ignored) {
			// conservative: refuse
			LOGGER.info("RequestResetAndStop refused: unable to determine server type");
			return;
		}
		try {
			java.nio.file.Path runDir = server.getRunDirectory();
			java.nio.file.Path marker = runDir.resolve("hc_reset.flag");
			String info = "requestedBy=mod time=" + System.currentTimeMillis();
			try {
				Files.writeString(marker, info);
				LOGGER.info("Wrote hc_reset.flag at {}", marker.toAbsolutePath());
			} catch (IOException e) {
				LOGGER.error("Failed to write reset marker", e);
				return;
			}

			boolean autoRestart = ConfigManager.getBoolean("auto_restart");
			int delay = ConfigManager.getInt("restart_delay_seconds", 5);

			// Stop server gracefully on server thread
			try {
				server.execute(() -> {
					LOGGER.info("HardcorePlus+ initiating server stop for reset in {} seconds", delay);
					try { Thread.sleep(delay * 1000L); } catch (InterruptedException ignored) {}
					server.stop(false);
					if (autoRestart) {
						// Some server hosts expect a wrapper to restart; we just exit normally.
						LOGGER.info("auto_restart is true; server process should be restarted by wrapper if present");
					}
				});
			} catch (Throwable t) {
				LOGGER.warn("Failed to schedule server stop on server thread; invoking stop directly", t);
				try { server.stop(false); } catch (Throwable ex) { LOGGER.error("Failed to stop server", ex); }
			}
		} catch (Throwable t) {
			LOGGER.error("Exception while requesting reset and stop", t);
		}
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