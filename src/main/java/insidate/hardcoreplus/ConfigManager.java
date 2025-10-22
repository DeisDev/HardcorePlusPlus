package insidate.hardcoreplus;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

public class ConfigManager {
    private static final String FILE_NAME = "hardcoreplus.properties";
    private static Properties properties;

    public static synchronized Properties load() {
        if (properties != null) return properties;

        properties = new Properties();

        // defaults
        properties.setProperty("auto_restart", "true");
        properties.setProperty("backup_old_worlds", "true");
        properties.setProperty("delete_instead_of_backup", "false");
        properties.setProperty("backup_folder_name", "Old Worlds");
        // Template for backup folder name. Supported tokens: %name% (level name), %ts% (timestamp), %id% (short UUID)
        // Default appends date/time: e.g. world_20251021-143012
        properties.setProperty("backup_name_format", "%name%_%ts%");
        properties.setProperty("confirm_timeout_ms", "30000");
        properties.setProperty("restart_delay_seconds", "5");
        properties.setProperty("restart_command", "");

        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            if (!Files.exists(configDir)) Files.createDirectories(configDir);
            Path file = configDir.resolve(FILE_NAME);
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
                    properties.load(in);
                }
            } else {
                // write defaults
                try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                    properties.store(out, "HardcorePlus+ configuration");
                }
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
}
