package dev.hotkeycmd.config;

import dev.hotkeycmd.HotkeyPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class ConfigManager {

    private final HotkeyPlugin plugin;

    // These are hot-swapped by load() (main thread, via /hkcmd reload) but read from the
    // PacketEvents Netty thread. They MUST stay volatile so a reader observes the post-reload
    // value instead of an indefinitely-stale cached copy. The referenced collections are all
    // immutable/effectively-immutable, so a reader only ever sees a fully-built old-or-new state.
    private volatile List<HotkeyDefinition> hotkeys = List.of();
    private volatile Map<KeyTrigger, List<HotkeyDefinition>> byKey = new EnumMap<>(KeyTrigger.class);
    private volatile double globalCooldown;
    private volatile String locale;
    private volatile boolean debug;
    private volatile boolean ignoreBedrock;

    public ConfigManager(HotkeyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        // Bring an older, user-edited config.yml up to date without clobbering it (see method).
        migrateConfig(cfg);

        globalCooldown = cfg.getDouble("global-cooldown", 1.5);
        locale = cfg.getString("locale", "en");
        debug = cfg.getBoolean("debug", false);
        ignoreBedrock = cfg.getBoolean("ignore-bedrock", true);

        List<HotkeyDefinition> loaded = new ArrayList<>();
        ConfigurationSection section = cfg.getConfigurationSection("hotkeys");

        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection hk = section.getConfigurationSection(id);
                if (hk == null) continue;

                // Keys: accept a single value (key: SWAP_HAND) or a list (key: [SWAP_HAND, DROP]),
                // under either 'key' or 'keys'. Each is validated explicitly so an unsupported name
                // gives an actionable error — Minecraft only sends a fixed set of keys to the server;
                // letter keys (L/K/G/…) are client-side only and can never be bound. Without this
                // clear message the entry would just vanish and the hotkey would silently "do nothing".
                List<String> rawKeys = readStringList(hk, "keys", "key");
                if (rawKeys.isEmpty()) {
                    plugin.getLogger().log(Level.WARNING,
                            "Skipping hotkey ''{0}'': no ''key'' set.", id);
                    continue;
                }
                // LinkedHashSet dedups a key repeated in the same entry (would otherwise double-fire).
                LinkedHashSet<KeyTrigger> keySet = new LinkedHashSet<>();
                boolean badKey = false;
                for (String rk : rawKeys) {
                    try {
                        keySet.add(KeyTrigger.valueOf(rk.toUpperCase().replace("-", "_")));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().log(Level.WARNING,
                                "Skipping hotkey ''{0}'': ''{1}'' is not a valid key. Minecraft only "
                                + "sends a fixed set of keys to the server (letter keys like L/K/G "
                                + "cannot be used). Valid keys: {2}",
                                new Object[]{id, rk, validKeys()});
                        badKey = true;
                        break;
                    }
                }
                if (badKey) continue;
                List<KeyTrigger> keys = List.copyOf(keySet);

                String rawRunAs = hk.getString("run-as", "PLAYER");
                RunAs runAs;
                try {
                    runAs = RunAs.valueOf(rawRunAs.toUpperCase());
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Skipping hotkey ''{0}'': run-as ''{1}'' is invalid. Use PLAYER or CONSOLE.",
                            new Object[]{id, rawRunAs});
                    continue;
                }

                // Commands: accept a single value (command: "/spawn") or a list
                // (commands: ["/spawn", "/heal"]). Blank entries are dropped. An empty result is
                // allowed — a cancel-action-only hotkey (block the vanilla action, run nothing).
                List<String> commands = new ArrayList<>();
                for (String c : readStringList(hk, "commands", "command")) {
                    if (c != null && !c.isBlank()) commands.add(c);
                }
                commands = List.copyOf(commands);

                String perm     = hk.getString("permission", "");
                boolean enabled = hk.getBoolean("enabled", true);
                boolean cancel  = hk.getBoolean("cancel-action", true);

                // Security: a CONSOLE hotkey runs with full server privileges and
                // bypasses the plugin's own permission check. If no permission node is
                // set, EVERY player would be able to trigger it — a privilege-escalation
                // hole. Refuse to load such an entry rather than silently exposing it.
                if (runAs == RunAs.CONSOLE && perm.isEmpty()) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Refusing to load hotkey ''{0}'': run-as: CONSOLE requires a "
                            + "non-empty permission. Without one, any player could run this "
                            + "console command. Set a 'permission' node to enable it.", id);
                    continue;
                }

                loaded.add(new HotkeyDefinition(id, keys, commands, perm, enabled, cancel, runAs));
            }
        }

        List<HotkeyDefinition> finalList = List.copyOf(loaded);

        // Pre-index ENABLED hotkeys by trigger so the Netty hot path (handleTrigger, fired per
        // keypress/scroll) is an O(1) map lookup instead of streaming+filtering the whole list on
        // every packet — most packets hit keys with no binding and should cost nothing. Disabled
        // entries are excluded here so the trigger path needs no enabled-check. The flat `hotkeys`
        // list still holds every entry (enabled or not) for the admin list/info commands.
        EnumMap<KeyTrigger, List<HotkeyDefinition>> index = new EnumMap<>(KeyTrigger.class);
        for (HotkeyDefinition def : finalList) {
            if (!def.enabled()) continue;
            // A multi-key entry is indexed under each of its keys (the same def object under all).
            for (KeyTrigger k : def.keys()) {
                index.computeIfAbsent(k, kk -> new ArrayList<>()).add(def);
            }
        }
        // Freeze the per-key lists so a Netty reader can never observe mid-mutation state.
        index.replaceAll((k, v) -> List.copyOf(v));

        this.hotkeys = finalList;
        this.byKey = index;   // volatile publish — safe: never mutated after this assignment
        plugin.getLogger().info("Loaded " + hotkeys.size() + " hotkey(s).");
    }

    public List<HotkeyDefinition> getHotkeys()  { return hotkeys; }

    /** Enabled hotkeys bound to {@code key}, pre-indexed at load. Never null; empty if none. */
    public List<HotkeyDefinition> getHotkeysFor(KeyTrigger key) {
        return byKey.getOrDefault(key, List.of());
    }

    public double getGlobalCooldown()            { return globalCooldown; }
    public String getLocale()                    { return locale; }
    public boolean isDebug()                     { return debug; }
    public boolean isIgnoreBedrock()             { return ignoreBedrock; }

    /**
     * Non-destructive upgrade of an existing config.yml.
     *
     * <p>When a newer plugin version introduces a top-level setting, an already-customized
     * config.yml on disk won't have it. This copies any such missing top-level setting from the
     * bundled default into the user's file so it becomes visible and editable — but:
     * <ul>
     *   <li>values the user already set are never overwritten,</li>
     *   <li>the entire {@code hotkeys:} section is left completely untouched (so example hotkeys
     *       the user deleted are never re-added, and their own hotkeys are never modified),</li>
     *   <li>the file is only rewritten when something was actually added, so an up-to-date config
     *       is never re-serialized and its comments/formatting stay exactly as the user left them.</li>
     * </ul>
     * If saving fails the plugin still runs — every read falls back to a hard-coded default.
     */
    private void migrateConfig(FileConfiguration cfg) {
        InputStream in = plugin.getResource("config.yml");
        if (in == null) return;   // no bundled default to compare against — nothing to do
        FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));

        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            // Never merge the user-owned hotkeys map, and only merge simple scalar settings.
            if (key.equals("hotkeys") || defaults.isConfigurationSection(key)) continue;
            // contains(key, true) ignores the bundled default → true only if in the user's file.
            if (!cfg.contains(key, true)) {
                cfg.set(key, defaults.get(key));
                changed = true;
                plugin.getLogger().info("config.yml: added new setting '" + key
                        + "' = " + defaults.get(key) + " (default from this version)");
            }
        }

        if (changed) {
            try {
                cfg.save(new File(plugin.getDataFolder(), "config.yml"));
                plugin.getLogger().info(
                        "config.yml upgraded — your existing values and hotkeys were left unchanged.");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not save the upgraded config.yml (using in-memory defaults for now): {0}",
                        e.getMessage());
            }
        }
    }

    /**
     * Reads a config value that may be written as either a single scalar or a YAML list, trying
     * each candidate path in order and returning the first that yields something (e.g. "commands"
     * then "command"). Lets one option accept both {@code key: X} and {@code key: [X, Y]}.
     */
    private static List<String> readStringList(ConfigurationSection sec, String... paths) {
        for (String path : paths) {
            if (sec.isList(path)) {
                List<String> list = sec.getStringList(path);
                if (!list.isEmpty()) return list;
            } else if (sec.isString(path)) {
                String s = sec.getString(path);
                if (s != null && !s.isEmpty()) return List.of(s);
            }
        }
        return List.of();
    }

    /** Comma-separated list of every valid key name — used only in the invalid-key warning. */
    private static String validKeys() {
        StringBuilder sb = new StringBuilder();
        for (KeyTrigger k : KeyTrigger.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(k.name());
        }
        return sb.toString();
    }
}
