package dev.hotkeycmd;

import com.github.retrooper.packetevents.PacketEvents;
import dev.hotkeycmd.command.HotkeyAdminCommand;
import dev.hotkeycmd.config.ConfigManager;
import dev.hotkeycmd.lang.LangManager;
import dev.hotkeycmd.listener.PacketHotkeyListener;
import dev.hotkeycmd.listener.PlayerQuitListener;
import dev.hotkeycmd.manager.HotkeyManager;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class HotkeyPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private LangManager langManager;
    private HotkeyManager hotkeyManager;
    private PacketHotkeyListener packetHotkeyListener;

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    @Override
    public void onLoad() {
        // PacketEvents must be initialised before onEnable so other plugins that
        // depend on it during their own onLoad can see it.
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        langManager = new LangManager(this);
        langManager.load(configManager.getLocale());

        hotkeyManager = new HotkeyManager(this);

        packetHotkeyListener = new PacketHotkeyListener(this);
        PacketEvents.getAPI().getEventManager().registerListener(packetHotkeyListener);
        PacketEvents.getAPI().init();

        getServer().getPluginManager().registerEvents(
                new PlayerQuitListener(this, packetHotkeyListener), this);

        HotkeyAdminCommand adminCmd = new HotkeyAdminCommand(this);
        PluginCommand cmd = getCommand("hkcmd");
        if (cmd != null) {
            cmd.setExecutor(adminCmd);
            cmd.setTabCompleter(adminCmd);
        }

        getLogger().info("HotkeyCommands enabled.");
    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
        getLogger().info("HotkeyCommands disabled.");
    }

    // ------------------------------------------------------------------ //
    //  Getters
    // ------------------------------------------------------------------ //

    public ConfigManager getConfigManager()         { return configManager; }
    public LangManager getLangManager()             { return langManager; }
    public HotkeyManager getHotkeyManager()         { return hotkeyManager; }
}
