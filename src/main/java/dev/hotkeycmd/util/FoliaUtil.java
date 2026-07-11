package dev.hotkeycmd.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class FoliaUtil {

    public static final boolean IS_FOLIA;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException ignored) {}
        IS_FOLIA = folia;
    }

    private FoliaUtil() {}

    /**
     * Schedules a task on the correct thread for the given player.
     * On Folia this uses the player's regional scheduler; on Paper/Spigot it uses
     * the global BukkitScheduler so the task always runs on the main thread.
     */
    public static void runTask(Plugin plugin, Player player, Runnable task) {
        if (IS_FOLIA) {
            // Folia: run on the region that owns the player's current chunk
            player.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
