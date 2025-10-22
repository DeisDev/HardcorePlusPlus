package insidate.hardcoreplus;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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

	// Track current world's start time and name to compute lifetime durations
	public static volatile long WORLD_START_MILLIS = 0L;
	public static volatile String CURRENT_LEVEL_NAME = "world";

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
			// Reload config at startup to pick up live edits before handling rotation
			try { ConfigManager.reload(); } catch (Throwable ignored) {}
				try {
				java.nio.file.Path runDir = server.getRunDirectory();
				java.nio.file.Path marker = runDir.resolve("hc_reset.flag");
				if (!Files.exists(marker)) return;

				LOGGER.info("hc_reset.flag detected; preparing to rotate world");

				// determine OLD level name from marker (preferred) or fall back to server.properties
				String levelName = "world";
				try {
					Properties markerProps = new Properties();
					try (java.io.Reader r = Files.newBufferedReader(marker)) {
						markerProps.load(r);
					}
					String fromMarker = markerProps.getProperty("old-level-name");
					if (fromMarker != null && !fromMarker.isBlank()) {
						levelName = fromMarker;
					}
				} catch (IOException ignored) {
					// ignore; will fallback to server.properties
				}

				if ("world".equals(levelName)) {
					java.nio.file.Path propsFile = runDir.resolve("server.properties");
					if (Files.exists(propsFile)) {
						try {
							Properties p = new Properties();
							p.load(Files.newInputStream(propsFile));
							levelName = Optional.ofNullable(p.getProperty("level-name")).orElse(levelName);
						} catch (IOException ignored) {}
					}
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
						boolean moved = false;
						try {
							Files.move(worldDir, backupTarget, StandardCopyOption.ATOMIC_MOVE);
							LOGGER.info("Moved old world to {}", backupTarget.toAbsolutePath());
							moved = true;
						} catch (IOException e) {
							LOGGER.warn("Failed to move world to backup atomically; attempting non-atomic move", e);
							try { Files.move(worldDir, backupTarget); moved = true; } catch (IOException ex) { LOGGER.warn("Non-atomic move failed; will attempt copy fallback", ex); }
						}

						if (!moved) {
							// Fallback: copy recursively then delete source
							try {
								Files.walk(worldDir).forEach(source -> {
									try {
										java.nio.file.Path dest = backupTarget.resolve(worldDir.relativize(source));
										if (Files.isDirectory(source)) {
											if (!Files.exists(dest)) Files.createDirectories(dest);
										} else {
											// Skip session.lock and continue on copy errors
											if (source.getFileName().toString().equalsIgnoreCase("session.lock")) {
												LOGGER.info("Skipping locked file during backup copy: {}", source);
												return;
											}
											Files.copy(source, dest);
										}
									} catch (IOException ex) { LOGGER.warn("Error copying file to backup (continuing): {}", source); }
								});
								// After copy, delete original
								Files.walk(worldDir)
										.sorted((a, b) -> b.compareTo(a))
										.forEach(p -> {
											try { Files.deleteIfExists(p); } catch (IOException ignored) {}
										});
								LOGGER.info("Copied old world to {} and deleted original", backupTarget.toAbsolutePath());
							} catch (IOException ex) {
								LOGGER.error("Failed to copy-and-delete old world to backup", ex);
							}
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

						// Also read server.properties 'hardcore' for comparison
						boolean propsHardcore = false;
						try {
							java.nio.file.Path propsFile = server.getRunDirectory().resolve("server.properties");
							if (java.nio.file.Files.exists(propsFile)) {
								java.util.Properties p = new java.util.Properties();
								try (java.io.InputStream in = java.nio.file.Files.newInputStream(propsFile)) { p.load(in); }
								String hv = p.getProperty("hardcore");
								if (hv != null) propsHardcore = hv.equalsIgnoreCase("true") || hv.equalsIgnoreCase("1") || hv.equalsIgnoreCase("yes");
							}
						} catch (Throwable ignored) {}

						String msg = String.format("Hardcore (world): %s, server.properties: %s, Processing: %s, Online players: %d",
								isHardcore, propsHardcore, PROCESSING.get(), server.getPlayerManager().getPlayerList().size());
						source.sendFeedback(() -> Text.literal(msg), false);
						return 1;
					}))
					.then(CommandManager.literal("config").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
						// Show effective config values
						try { ConfigManager.reload(); } catch (Throwable ignored) {}
						ServerCommandSource source = ctx.getSource();
						String msg2 = String.join("\n",
							"HardcorePlus+ config:",
							"  new_level_name_format=" + String.valueOf(ConfigManager.get("new_level_name_format")),
							"  time_format=" + String.valueOf(ConfigManager.get("time_format")),
							"  force_new_seed=" + ConfigManager.getBoolean("force_new_seed"),
							"  seed_mode=" + String.valueOf(ConfigManager.get("seed_mode")),
							"  custom_seed=" + String.valueOf(ConfigManager.get("custom_seed")),
							"  backup_old_worlds=" + ConfigManager.getBoolean("backup_old_worlds"),
							"  delete_instead_of_backup=" + ConfigManager.getBoolean("delete_instead_of_backup"),
							"  backup_folder_name=" + String.valueOf(ConfigManager.get("backup_folder_name")),
							"  backup_name_format=" + String.valueOf(ConfigManager.get("backup_name_format")),
							"  restart_delay_seconds=" + ConfigManager.getInt("restart_delay_seconds", 10),
							"  auto_restart=" + ConfigManager.getBoolean("auto_restart")
						);
						source.sendFeedback(() -> Text.literal(msg2), false);
						return 1;
					}))
					.then(CommandManager.literal("preview").requires(src -> src.hasPermissionLevel(0)).executes(ctx -> {
						// Preview next world name and seed without writing
						try { ConfigManager.reload(); } catch (Throwable ignored) {}
						ServerCommandSource source = ctx.getSource();
						String oldLevelName2 = "world";
						try {
							java.nio.file.Path propsFile = source.getServer().getRunDirectory().resolve("server.properties");
							if (java.nio.file.Files.exists(propsFile)) {
								java.util.Properties p = new java.util.Properties();
								try (java.io.InputStream in = java.nio.file.Files.newInputStream(propsFile)) { p.load(in); }
								oldLevelName2 = java.util.Optional.ofNullable(p.getProperty("level-name")).orElse(oldLevelName2);
							}
						} catch (Throwable ignored) {}

						// Prefer a stable base name if recorded
						String baseLevelName2 = oldLevelName2;
						try {
							java.nio.file.Path baseFile = source.getServer().getRunDirectory().resolve("hc_base_name.txt");
							if (java.nio.file.Files.exists(baseFile)) {
								String tmp = java.nio.file.Files.readString(baseFile).trim();
								if (!tmp.isEmpty()) baseLevelName2 = tmp;
							}
						} catch (Throwable ignored) {}

						String timePattern = ConfigManager.get("time_format");
						if (timePattern == null || timePattern.isBlank()) timePattern = "HH-mm-ss_uuuu-MM-dd";
						java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(timePattern).withZone(java.time.ZoneId.systemDefault());
						String timeStr = fmt.format(java.time.Instant.now());
						String nameFormat = ConfigManager.get("new_level_name_format");
						if (nameFormat == null || nameFormat.isBlank()) nameFormat = "%name%_%time%";
						String id = java.util.UUID.randomUUID().toString().substring(0, 8);
						String newLevelName2 = nameFormat.replace("%name%", baseLevelName2).replace("%time%", timeStr).replace("%id%", id);
						newLevelName2 = sanitizeName(newLevelName2);

						String seedInfo2 = "(unchanged)";
						if (ConfigManager.getBoolean("force_new_seed")) {
							String seedMode = String.valueOf(ConfigManager.get("seed_mode")).trim().toLowerCase();
							if (seedMode.equals("custom")) {
								String customSeed = String.valueOf(ConfigManager.get("custom_seed"));
								if (customSeed != null && !customSeed.isBlank()) seedInfo2 = customSeed; else seedInfo2 = "<empty custom_seed> -> random";
							} else {
								seedInfo2 = Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong());
							}
						}

						String msg3 = "Preview rotation => new level-name: '" + newLevelName2 + "', seed: " + seedInfo2;
						ctx.getSource().sendFeedback(() -> Text.literal(msg3), false);
						return 1;
					}))
					.then(CommandManager.literal("reload").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> {
						ConfigManager.reload();
						ctx.getSource().sendFeedback(() -> Text.literal("HardcorePlus+ config reloaded."), false);
						return 1;
					}))
					.then(CommandManager.literal("reset").requires(src -> src.hasPermissionLevel(2))
						.then(CommandManager.literal("confirm").executes(ctx -> {
							ServerCommandSource source = ctx.getSource();
							MinecraftServer server = source.getServer();
							if (server == null) {
								source.sendFeedback(() -> Text.literal("Server not available."), false);
								return 0;
							}
							// Execute rotation immediately regardless of current hardcore status
							requestResetAndStop(server);
							source.sendFeedback(() -> Text.literal("Reset scheduled. Server will stop shortly."), false);
							return 1;
						}))
						.executes(ctx -> {
							ServerCommandSource source = ctx.getSource();
							// simple confirm gate (reuse map)
							UUID who = CONSOLE_UUID;
							try { if (source.getEntity() != null) who = source.getEntity().getUuid(); } catch (Throwable ignored) {}
							long expiry = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
							PENDING_CONFIRM.put(who, expiry);
							source.sendFeedback(() -> Text.literal("Reset requested. Confirm with /hcp reset confirm within 30 seconds."), false);
							return 1;
						})
					)
					.then(CommandManager.literal("help").requires(src -> src.hasPermissionLevel(0)).executes(ctx -> {
						ServerCommandSource src = ctx.getSource();
						boolean isOp = false;
						try { isOp = src.hasPermissionLevel(2); } catch (Throwable ignored) {}
						StringBuilder sb = new StringBuilder();
						sb.append("HardcorePlus+ commands:\n");
						sb.append("  /hcp status - Show hardcore/processing/players\n");
						sb.append("  /hcp preview - Show next world name and seed\n");
						sb.append("  /hcp time - Show current world time and real uptime\n");
						if (isOp) {
							sb.append("  /hcp masskill - Request mass-kill (confirm required)\n");
							sb.append("  /hcp masskill confirm - Confirm mass-kill\n");
							sb.append("  /hcp reset - Schedule world rotation (confirm required)\n");
							sb.append("  /hcp reset confirm - Confirm rotation and stop server\n");
							sb.append("  /hcp config - Show effective config\n");
							sb.append("  /hcp reload - Reload config file\n");
						} else {
							sb.append("  (Op-only) masskill, reset, config, reload\n");
						}
						src.sendFeedback(() -> Text.literal(sb.toString()), false);
						return 1;
					}))
					.then(CommandManager.literal("time").requires(src -> src.hasPermissionLevel(0)).executes(ctx -> {
						ServerCommandSource source = ctx.getSource();
						MinecraftServer server = source.getServer();
						if (server == null) {
							source.sendFeedback(() -> Text.literal("Server not available."), false);
							return 0;
						}
						net.minecraft.server.world.ServerWorld world = null;
						try { world = server.getOverworld(); } catch (Throwable ignored) {}
						if (world == null) {
							source.sendFeedback(() -> Text.literal("World not available."), false);
							return 0;
						}
						long todFull;
						long timeTotal;
						try {
							todFull = world.getTimeOfDay();
							timeTotal = world.getTime();
						} catch (Throwable t) {
							// Fallback in case mappings differ
							todFull = 0L;
							timeTotal = 0L;
						}
						long tod = Math.floorMod(todFull, 24000L);
						long day = Math.floorDiv(todFull, 24000L);
						long hour = (tod / 1000L + 6L) % 24L; // 0 ticks corresponds to 06:00
						long minute = (tod % 1000L) * 60L / 1000L;
						String mcClock = String.format("Day %d, %02d:%02d", day, hour, minute);

						long uptimeMs = 0L;
						long start = WORLD_START_MILLIS;
						if (start > 0) uptimeMs = System.currentTimeMillis() - start;
						String uptime = formatDuration(uptimeMs);

						Text msg = Text.empty()
							.append(Text.literal("World Time: ").formatted(Formatting.GRAY))
							.append(Text.literal(mcClock).formatted(Formatting.AQUA, Formatting.BOLD))
							.append(Text.literal("  ").formatted(Formatting.DARK_GRAY))
							.append(Text.literal("Ticks: ").formatted(Formatting.GRAY))
							.append(Text.literal(Long.toString(timeTotal)).formatted(Formatting.YELLOW))
							.append(Text.literal("  ").formatted(Formatting.DARK_GRAY))
							.append(Text.literal("Uptime: ").formatted(Formatting.GRAY))
							.append(Text.literal(uptime).formatted(Formatting.GOLD));

						source.sendFeedback(() -> msg, false);
						return 1;
					}))
					.executes(ctx -> {
						ctx.getSource().sendFeedback(() -> Text.literal("Use /hcp help for available commands."), false);
						return 1;
					})
			);
			LOGGER.info("[hcp] Registered /hcp commands (masskill, status, config, preview, reload, help)");
		});

		// After server has started and the world is loaded, record or restore world start time
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			try {
				java.nio.file.Path runDir = server.getRunDirectory();
				java.nio.file.Path propsFile = runDir.resolve("server.properties");
				String levelName = "world";
				try {
					if (java.nio.file.Files.exists(propsFile)) {
						java.util.Properties p = new java.util.Properties();
						try (java.io.InputStream in = java.nio.file.Files.newInputStream(propsFile)) { p.load(in); }
						levelName = java.util.Optional.ofNullable(p.getProperty("level-name")).orElse(levelName);
					}
				} catch (Throwable ignored) {}

				CURRENT_LEVEL_NAME = levelName;

				java.nio.file.Path worldStart = runDir.resolve("hc_world_start.flag");
				long start = System.currentTimeMillis();
				boolean matched = false;
				try {
					if (java.nio.file.Files.exists(worldStart)) {
						java.util.Properties pp = new java.util.Properties();
						try (java.io.Reader r = java.nio.file.Files.newBufferedReader(worldStart)) { pp.load(r); }
						String ln = pp.getProperty("level-name");
						String st = pp.getProperty("start");
						if (ln != null && ln.equals(levelName) && st != null) {
							try { start = Long.parseLong(st); matched = true; } catch (NumberFormatException ignored) {}
						}
					}
				} catch (Throwable ignored) {}

				// Write current mapping to file (new or updated)
				try {
					java.util.Properties out = new java.util.Properties();
					out.setProperty("level-name", levelName);
					out.setProperty("start", Long.toString(start));
					try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(worldStart)) {
						out.store(w, "HardcorePlus+ world start timestamp");
					}
				} catch (Throwable t) {
					LOGGER.warn("Failed to write world start flag", t);
				}

				WORLD_START_MILLIS = start;
				LOGGER.info("World '{}' start time set{}: {}", levelName, matched ? " (restored)" : "", new java.util.Date(start));
			} catch (Throwable t) {
				LOGGER.warn("Failed to initialize world start tracking", t);
			}
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
			// Reload config at reset time so live edits are honored during rotation
			try { ConfigManager.reload(); } catch (Throwable ignored) {}
			java.nio.file.Path runDir = server.getRunDirectory();
			// If a reset is already scheduled, avoid duplicate scheduling and repeated name appends
			java.nio.file.Path existingMarker = runDir.resolve("hc_reset.flag");
			if (java.nio.file.Files.exists(existingMarker)) {
				LOGGER.warn("hc_reset.flag already exists; a reset is already scheduled. Skipping duplicate request.");
				return;
			}
			java.nio.file.Path propsFile = runDir.resolve("server.properties");
			java.util.Properties p = new java.util.Properties();
			String oldLevelName = "world";
			if (Files.exists(propsFile)) {
				try (java.io.InputStream in = Files.newInputStream(propsFile)) { p.load(in); }
				oldLevelName = java.util.Optional.ofNullable(p.getProperty("level-name")).orElse(oldLevelName);
			}

			// Determine a stable base level-name to prevent compounded time suffixes across rotations
			java.nio.file.Path baseFile = runDir.resolve("hc_base_name.txt");
			String baseLevelName = oldLevelName;
			try {
				if (java.nio.file.Files.exists(baseFile)) {
					baseLevelName = java.nio.file.Files.readString(baseFile).trim();
					if (baseLevelName.isEmpty()) baseLevelName = oldLevelName;
				} else {
					// First rotation: persist the current level-name as the base for future rotations
					java.nio.file.Files.writeString(baseFile, baseLevelName, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
					LOGGER.info("Saved base level-name '{}' to {}", baseLevelName, baseFile.toAbsolutePath());
				}
			} catch (Throwable t) {
				LOGGER.warn("Failed to read/write base level-name; using current level-name as base", t);
				baseLevelName = oldLevelName;
			}

			// Generate a new human-readable level-name using configurable format
			String timePattern = ConfigManager.get("time_format");
			if (timePattern == null || timePattern.isBlank()) timePattern = "HH-mm-ss_uuuu-MM-dd";
			java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern(timePattern).withZone(java.time.ZoneId.systemDefault());
			String timeStr = fmt.format(java.time.Instant.now());
			String nameFormat = ConfigManager.get("new_level_name_format");
			if (nameFormat == null || nameFormat.isBlank()) nameFormat = "%name%_%time%";
			String id = java.util.UUID.randomUUID().toString().substring(0, 8);
			String newLevelName = nameFormat.replace("%name%", baseLevelName).replace("%time%", timeStr).replace("%id%", id);
			newLevelName = sanitizeName(newLevelName);
			p.setProperty("level-name", newLevelName);

			// Optionally force a new seed by writing an explicit level-seed in server.properties
			String newSeedWritten = null;
			boolean forceNewSeed = ConfigManager.getBoolean("force_new_seed");
			if (forceNewSeed) {
				String seedMode = ConfigManager.get("seed_mode");
				if (seedMode == null) seedMode = "random";
				seedMode = seedMode.trim().toLowerCase();
				if (seedMode.equals("custom")) {
					String customSeed = ConfigManager.get("custom_seed");
					if (customSeed != null && !customSeed.isBlank()) {
						p.setProperty("level-seed", customSeed);
						newSeedWritten = customSeed;
					} else {
						LOGGER.warn("[hcp] seed_mode=custom but custom_seed is empty; falling back to random seed");
						long newSeed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
						p.setProperty("level-seed", Long.toString(newSeed));
						newSeedWritten = Long.toString(newSeed);
					}
				} else {
					long newSeed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
					p.setProperty("level-seed", Long.toString(newSeed));
					newSeedWritten = Long.toString(newSeed);
				}
			}

			// Persist updated server.properties
			try (java.io.OutputStream out = Files.newOutputStream(propsFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
				p.store(out, "server.properties (modified by HardcorePlus+ to rotate world: new level-name and optional seed)");
			}
			LOGGER.info("Prepared rotation: old-level-name='{}' -> new-level-name='{}'{}",
					oldLevelName, newLevelName,
					newSeedWritten == null ? "" : ", level-seed=" + newSeedWritten);

			// Write marker with details so startup can back up the OLD world by name
			java.nio.file.Path marker = runDir.resolve("hc_reset.flag");
			java.util.Properties markerProps = new java.util.Properties();
			markerProps.setProperty("requestedBy", "mod");
			markerProps.setProperty("time", Long.toString(System.currentTimeMillis()));
			markerProps.setProperty("old-level-name", oldLevelName);
			markerProps.setProperty("new-level-name", newLevelName);
			markerProps.setProperty("base-level-name", baseLevelName);
			if (newSeedWritten != null) markerProps.setProperty("new-seed", newSeedWritten);
			try (java.io.Writer w = Files.newBufferedWriter(marker)) {
				markerProps.store(w, "HardcorePlus+ world rotation metadata");
			}
			LOGGER.info("Wrote hc_reset.flag at {} with rotation metadata", marker.toAbsolutePath());

			boolean autoRestart = ConfigManager.getBoolean("auto_restart");
			int delay = ConfigManager.getInt("restart_delay_seconds", 10);

			// Schedule server stop without blocking the server thread (avoid freezing the world)
			LOGGER.info("HardcorePlus+ initiating server stop for reset in {} seconds", delay);
			try {
				Thread t = new Thread(() -> {
					try { Thread.sleep(delay * 1000L); } catch (InterruptedException ignored) {}
					try {
						server.execute(() -> {
							server.stop(false);
							if (autoRestart) {
								LOGGER.info("auto_restart is true; server process should be restarted by wrapper if present");
							}
						});
					} catch (Throwable ex) {
						LOGGER.error("Failed to stop server after delay", ex);
					}
				}, "hcp-restart-timer");
				t.setDaemon(true);
				t.start();
			} catch (Throwable t) {
				LOGGER.warn("Failed to start restart timer thread; stopping immediately as fallback", t);
				try { server.stop(false); } catch (Throwable ex) { LOGGER.error("Failed to stop server", ex); }
			}
		} catch (Throwable t) {
			LOGGER.error("Exception while requesting reset and stop", t);
		}
	}

	/**
	 * Variant that also broadcasts a single restart notice naming the triggering player.
	 * Ensures the message is only sent if a reset has not already been scheduled.
	 */
	public static void requestResetAndStop(MinecraftServer server, String triggeringPlayerName) {
		if (server == null) return;
		try {
			if (!server.isDedicated()) return;
		} catch (Throwable ignored) { return; }
		try { ConfigManager.reload(); } catch (Throwable ignored) {}
		try {
			java.nio.file.Path runDir = server.getRunDirectory();
			java.nio.file.Path existingMarker = runDir.resolve("hc_reset.flag");
			if (java.nio.file.Files.exists(existingMarker)) {
				LOGGER.debug("Reset already scheduled; suppressing duplicate restart announcement");
				return;
			}
			int delay = ConfigManager.getInt("restart_delay_seconds", 10);
			String name = (triggeringPlayerName != null && !triggeringPlayerName.isBlank()) ? triggeringPlayerName : "A player";
			// Compute world lifetime since start
			long startMs = WORLD_START_MILLIS;
			if (startMs <= 0) {
				try {
					java.util.Properties pp = new java.util.Properties();
					java.nio.file.Path worldStart = runDir.resolve("hc_world_start.flag");
					if (java.nio.file.Files.exists(worldStart)) {
						try (java.io.Reader r = java.nio.file.Files.newBufferedReader(worldStart)) { pp.load(r); }
						String ln = pp.getProperty("level-name");
						String st = pp.getProperty("start");
						if (ln != null && st != null && ln.equals(CURRENT_LEVEL_NAME)) {
							try { startMs = Long.parseLong(st); } catch (NumberFormatException ignored) {}
						}
					}
				} catch (Throwable ignored) {}
			}
			String dur = formatDuration(Math.max(0L, System.currentTimeMillis() - Math.max(0L, startMs)));
			try {
				Text msg = Text.empty()
					.append(Text.literal(name).formatted(Formatting.GOLD, Formatting.BOLD))
					.append(Text.literal(" has died. ").formatted(Formatting.RED))
					.append(Text.literal("World lasted ").formatted(Formatting.GRAY))
					.append(Text.literal(dur).formatted(Formatting.AQUA, Formatting.BOLD))
					.append(Text.literal(". Restart in ").formatted(Formatting.GRAY))
					.append(Text.literal(Integer.toString(delay)).formatted(Formatting.YELLOW, Formatting.BOLD))
					.append(Text.literal(" seconds.").formatted(Formatting.GRAY));
				server.getPlayerManager().broadcast(msg, false);
			} catch (Throwable t) {
				LOGGER.warn("Failed to broadcast restart message", t);
			}
		} catch (Throwable t) {
			LOGGER.warn("Announcement pre-check failed; proceeding with reset request", t);
		}
		// Delegate to the main implementation which performs rotation and schedules stop
		requestResetAndStop(server);
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

	// Replace characters that are illegal in Windows/macOS/Linux filenames and tidy up
	private static String sanitizeName(String input) {
		if (input == null) return "world_" + System.currentTimeMillis();
		String t = input.replaceAll("[\\\\/:*?\"<>|]", "-");
		t = t.trim();
		// Windows forbids trailing spaces or dots in folder names
		while (!t.isEmpty() && (t.endsWith(" ") || t.endsWith("."))) {
			t = t.substring(0, t.length() - 1);
		}
		return t.isEmpty() ? ("world_" + System.currentTimeMillis()) : t;
	}

	// Format a duration in milliseconds as HH:mm:ss (hours may exceed 24)
	private static String formatDuration(long millis) {
		if (millis < 0) millis = 0;
		long seconds = millis / 1000;
		long s = seconds % 60;
		long minutes = (seconds / 60) % 60;
		long hours = (seconds / 3600);
		return String.format("%02d:%02d:%02d", hours, minutes, s);
	}
}