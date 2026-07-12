package dev.hotkeycmd.lang;

import dev.hotkeycmd.HotkeyPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class LangManager {

    private final HotkeyPlugin plugin;

    // Locales shipped inside the jar. All of them are written into the lang/ folder on first run
    // so the admin can see every choice and edit any of them; 'locale' in config.yml picks one.
    private static final String[] BUNDLED_LOCALES = {"en", "zh", "th", "es", "ru", "vi", "id", "ko"};

    // Hot-swapped by load() on /hkcmd reload; read from main/region threads (on Folia a region
    // thread may be in send() when the main thread reloads). volatile = safe publication.
    private volatile FileConfiguration lang;

    public LangManager(HotkeyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(String locale) {
        File langDir = new File(plugin.getDataFolder(), "lang");

        // One-time upgrade from older versions that saved lang_<code>.yml in the data-folder
        // root. Move any such file into lang/ (preserving admin edits) so nothing is orphaned.
        migrateFlatLangFiles(langDir);

        // Ensure every bundled language exists in lang/ so all choices are visible and editable.
        // Existing (possibly edited) files are never overwritten.
        for (String loc : BUNDLED_LOCALES) {
            if (!new File(langDir, loc + ".yml").exists()) {
                plugin.saveResource("lang/" + loc + ".yml", false);
            }
        }

        String resourcePath = "lang/" + locale + ".yml";
        File file = new File(plugin.getDataFolder(), resourcePath);

        if (!file.exists()) {
            // Selected locale isn't bundled and the admin hasn't added it — fall back to English.
            plugin.getLogger().warning("Language file '" + resourcePath
                    + "' not found, falling back to lang/en.yml");
            resourcePath = "lang/en.yml";
            file = new File(plugin.getDataFolder(), resourcePath);
            if (!file.exists()) plugin.saveResource(resourcePath, false);
        }

        lang = YamlConfiguration.loadConfiguration(file);

        // Merge missing keys from the bundled default of the same locale (English if that locale
        // isn't one we ship) so a partially edited or outdated file still resolves every key.
        InputStream defStream = plugin.getResource(resourcePath);
        if (defStream == null) defStream = plugin.getResource("lang/en.yml");
        if (defStream != null) {
            lang.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8)));
        }
    }

    /**
     * Moves pre-folder language files (lang_en.yml, … in the data-folder root, written by older
     * versions) into the lang/ folder. If the folder already has that locale the flat copy is
     * redundant and removed; otherwise it is moved in so admin edits carry over. No-op on a fresh
     * install.
     */
    private void migrateFlatLangFiles(File langDir) {
        for (String loc : BUNDLED_LOCALES) {
            File old = new File(plugin.getDataFolder(), "lang_" + loc + ".yml");
            if (!old.exists()) continue;
            File dest = new File(langDir, loc + ".yml");
            if (dest.exists()) {
                if (old.delete()) plugin.getLogger().info("Removed obsolete " + old.getName()
                        + " (now using lang/" + loc + ".yml).");
            } else {
                langDir.mkdirs();
                if (old.renameTo(dest)) plugin.getLogger().info("Moved " + old.getName()
                        + " into lang/" + loc + ".yml.");
            }
        }
    }

    /** Resolve a message key, replace {prefix} and any extra placeholders, colorize. */
    public String get(String key, Map<String, String> placeholders) {
        String raw = lang.getString(key, key);
        String prefix = lang.getString("prefix", "");
        raw = raw.replace("{prefix}", prefix);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            raw = raw.replace("{" + e.getKey() + "}", e.getValue());
        }
        return colorize(raw);
    }

    public String get(String key) {
        return get(key, Map.of());
    }

    public void send(CommandSender target, String key, Map<String, String> placeholders) {
        target.sendMessage(get(key, placeholders));
    }

    public void send(CommandSender target, String key) {
        send(target, key, Map.of());
    }

    private String colorize(String text) {
        return text.replace("&", "§");
    }
}
