package dev.hotkeycmd.listener;

import dev.hotkeycmd.HotkeyPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerQuitListener implements Listener {

    private final HotkeyPlugin plugin;
    private final PacketHotkeyListener packetListener;

    public PlayerQuitListener(HotkeyPlugin plugin, PacketHotkeyListener packetListener) {
        this.plugin = plugin;
        this.packetListener = packetListener;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        packetListener.removeSneaking(uuid);
        plugin.getHotkeyManager().removeCooldown(uuid);
    }
}
