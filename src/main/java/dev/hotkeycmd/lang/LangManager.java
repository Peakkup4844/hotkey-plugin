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
    // Hot-swapped by load() on /hkcmd reload; read from main/region threads (on Folia a region
    // thread may be in send() when the main thread reloads). volatile = safe publication.
    private volatile FileConfiguration lang;

    public LangManager(HotkeyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(String locale) {
        String fileName = "lang_" + locale + ".yml";
        File file = new File(plugin.getDataFolder(), fileName);

        if (!file.exists()) {
            if (plugin.getResource(fileName) != null) {
                plugin.saveResource(fileName, false);
            } else {
                // Locale not bundled – fall back to English (the default locale)
                plugin.getLogger().warning("Language file '" + fileName + "' not found, falling back to lang_en.yml");
                fileName = "lang_en.yml";
                file = new File(plugin.getDataFolder(), fileName);
                if (!file.exists()) plugin.saveResource(fileName, false);
            }
        }

        lang = YamlConfiguration.loadConfiguration(file);

        // Merge missing keys from the bundled default
        InputStream defStream = plugin.getResource(fileName);
        if (defStream != null) {
            lang.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8)));
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
