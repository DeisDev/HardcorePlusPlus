package insidate.hardcoreplus;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
// import java.io.OutputStream; // unused
// import java.io.Reader; // unused
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

public class ConfigManager {
    private static final String FILE_NAME = "hardcoreplus.properties";
    private static Properties properties;
    private static Path configPath;

    // Section -> ordered keys
    private static final LinkedHashMap<String, List<String>> SECTIONS = new LinkedHashMap<>();
    // Key -> help text (single or multi-line, without leading '#')
    private static final Map<String, String> KEY_COMMENTS = new LinkedHashMap<>();

    static {
        // Define sections and their keys in the order we want to render
        SECTIONS.put("General", list(
                "confirm_timeout_ms"
        ));

        SECTIONS.put("Restart", list(
                "auto_restart",
                "restart_delay_seconds",
                "restart_command"
        ));

        SECTIONS.put("World Rotation", list(
                "new_level_name_format",
                "time_format",
                "force_new_seed",
                "seed_mode",
                "custom_seed"
        ));

        SECTIONS.put("Backups", list(
                "backup_old_worlds",
                "delete_instead_of_backup",
                "backup_folder_name",
                "backup_name_format"
        ));

        // Comments for each key
        KEY_COMMENTS.put("confirm_timeout_ms", "Milliseconds allowed to confirm destructive admin actions (e.g. /hcp masskill). Default: 30000");

        KEY_COMMENTS.put("auto_restart", "If true, the server will stop cleanly after a reset request. A wrapper or panel should restart it.");
        KEY_COMMENTS.put("restart_delay_seconds", "Seconds to wait before stopping the server after a reset request. Gives players time to see the message.");
        KEY_COMMENTS.put("restart_command", "Reserved for external wrappers. The mod itself does not execute this; informational only.");

        KEY_COMMENTS.put("new_level_name_format", "Template for the NEW world folder name created on reset. Tokens: %name% (old level-name), %time% (formatted), %id% (8-char id). Example default: world_15-02-31_2025-10-22");
        KEY_COMMENTS.put("time_format", "Java DateTimeFormatter pattern for %time%. Avoid ':' on Windows. Default: HH-mm-ss_uuuu-MM-dd");
        KEY_COMMENTS.put("force_new_seed", "If true, write a seed to server.properties on reset.");
        KEY_COMMENTS.put("seed_mode", "Seed selection: 'random' (default) or 'custom'. If custom, the value from custom_seed is used.");
        KEY_COMMENTS.put("custom_seed", "Used only when seed_mode=custom. Can be numeric (e.g. 123456) or any string (e.g. MySeed).");

        KEY_COMMENTS.put("backup_old_worlds", "If true, move/copy the old world to a backup folder at next startup.");
        KEY_COMMENTS.put("delete_instead_of_backup", "If true, delete the old world instead of backing it up. Overrides backup_old_worlds.");
        KEY_COMMENTS.put("backup_folder_name", "Folder (relative to server root) where backups are stored. Default: Old Worlds");
        KEY_COMMENTS.put("backup_name_format", "Template for backup folder name. Tokens: %name% (level name), %ts% (timestamp), %id% (8-char id). Default: %name%_%ts%");
    }

    public static synchronized Properties load() {
        if (properties != null) return properties;

        // Build defaults and then overlay existing values
        Properties defaults = buildDefaults();
        properties = new Properties();
        properties.putAll(defaults);

        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            if (!Files.exists(configDir)) Files.createDirectories(configDir);
            configPath = configDir.resolve(FILE_NAME);
            if (Files.exists(configPath)) {
                // Load existing values
                Properties existing = new Properties();
                try (InputStream in = Files.newInputStream(configPath, StandardOpenOption.READ)) {
                    existing.load(in);
                }
                // Overlay onto defaults
                for (String name : existing.stringPropertyNames()) {
                    properties.setProperty(name, existing.getProperty(name));
                }
                // Re-write in formatted, categorized style
                saveFormatted(properties, configPath, existing);
            } else {
                // Write defaults in formatted, categorized style
                saveFormatted(properties, configPath, null);
            }
        } catch (IOException e) {
            Hardcoreplus.LOGGER.warn("Failed to load or create config file; using defaults", e);
        }

        return properties;
    }

    public static synchronized String get(String key) {
        load();
        return properties.getProperty(key);
    }

    public static synchronized boolean getBoolean(String key) {
        String v = get(key);
        return v != null && (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("1") || v.equalsIgnoreCase("yes"));
    }

    public static synchronized int getInt(String key, int def) {
        String v = get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static Properties buildDefaults() {
        Properties p = new Properties();
        // General
        p.setProperty("confirm_timeout_ms", "30000");
        // Restart
        p.setProperty("auto_restart", "true");
        p.setProperty("restart_delay_seconds", "5");
        p.setProperty("restart_command", "");
        // World Rotation
        p.setProperty("new_level_name_format", "%name%_%time%");
        p.setProperty("time_format", "HH-mm-ss_uuuu-MM-dd");
        p.setProperty("force_new_seed", "true");
        p.setProperty("seed_mode", "random");
        p.setProperty("custom_seed", "");
        // Backups
        p.setProperty("backup_old_worlds", "true");
        p.setProperty("delete_instead_of_backup", "false");
        p.setProperty("backup_folder_name", "Old Worlds");
        p.setProperty("backup_name_format", "%name%_%ts%");
        return p;
    }

    private static void saveFormatted(Properties values, Path file, Properties originalLoaded) throws IOException {
        String ls = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        sb.append("# HardcorePlus+ configuration").append(ls);
        sb.append("# This file is generated and organized by the mod. You can edit values on the right-hand side.").append(ls);
        sb.append("# Last written: ").append(fmt.format(Instant.now())).append(ls);
        sb.append("# Lines starting with '#' are comments.").append(ls).append(ls);

        // Render known sections
        for (Map.Entry<String, List<String>> entry : SECTIONS.entrySet()) {
            String section = entry.getKey();
            sb.append("# --------------------------------------------------").append(ls);
            sb.append("# ").append(section).append(ls);
            sb.append("# --------------------------------------------------").append(ls);
            for (String key : entry.getValue()) {
                String comment = KEY_COMMENTS.get(key);
                if (comment != null && !comment.isBlank()) {
                    for (String line : comment.split("\n")) {
                        sb.append("# ").append(line).append(ls);
                    }
                }
                String value = values.getProperty(key, "");
                sb.append(key).append("=").append(value).append(ls).append(ls);
            }
            sb.append(ls);
        }

        // Preserve unknown keys at the bottom
        if (originalLoaded != null) {
            Set<String> known = new TreeSet<>();
            for (List<String> keys : SECTIONS.values()) known.addAll(keys);
            sb.append("# --------------------------------------------------").append(ls);
            sb.append("# Other (preserved)").append(ls);
            sb.append("# Keys not recognized by this version are kept here.").append(ls);
            sb.append("# --------------------------------------------------").append(ls);
            for (String key : originalLoaded.stringPropertyNames()) {
                if (!known.contains(key)) {
                    sb.append(key).append("=").append(originalLoaded.getProperty(key, "")).append(ls);
                }
            }
        }

        // Write atomically where possible
        if (!Files.exists(file.getParent())) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(sb.toString());
        }
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFail) {
            // Fallback to non-atomic replace
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> list(String... keys) {
        List<String> l = new ArrayList<>(keys.length);
        java.util.Collections.addAll(l, keys);
        return l;
    }

    // Public method to force a reload from disk, merging onto defaults and rewriting in a formatted style.
    public static synchronized void reload() {
        properties = null;
        load();
    }
}
