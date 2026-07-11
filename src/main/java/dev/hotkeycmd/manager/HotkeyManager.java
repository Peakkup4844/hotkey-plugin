package dev.hotkeycmd.manager;

import dev.hotkeycmd.HotkeyPlugin;
import dev.hotkeycmd.config.HotkeyDefinition;
import dev.hotkeycmd.config.KeyTrigger;
import dev.hotkeycmd.config.RunAs;
import dev.hotkeycmd.util.FoliaUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HotkeyManager {

    private final HotkeyPlugin plugin;

    // Minimum gap between "on cooldown" messages per player. Without this, a client
    // spamming hotkey packets would schedule one main/region-thread task per packet,
    // a cheap DoS-amplification vector. We notify at most once per this interval.
    private static final long NOTIFY_THROTTLE_MS = 1000L;

    // Absolute floor between actual command dispatches per player, independent of the
    // configurable global-cooldown. Even with global-cooldown: 0 (cooldown disabled), a
    // client can packet-flood (hotbar/F/Q) at thousands/sec; without this floor each packet
    // would dispatch a command on the main/region thread = DoS amplification from a single
    // client. 50ms = 20 dispatches/sec max — far above any human keypress rate, far below a
    // flood. Triggers above this rate are silently dropped (no message — it's flood territory).
    private static final long MIN_DISPATCH_INTERVAL_MS = 50L;

    // Last trigger timestamp per player; accessed from Netty threads → ConcurrentHashMap
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    // Last time we sent an "on cooldown" message to a player; throttles notification spam
    private final ConcurrentHashMap<UUID, Long> lastNotify = new ConcurrentHashMap<>();

    // Last time we actually dispatched a command for a player; enforces the hard floor above
    private final ConcurrentHashMap<UUID, Long> lastDispatch = new ConcurrentHashMap<>();

    public HotkeyManager(HotkeyPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Called from the PacketEvents Netty thread.
     *
     * @param cancelPacket Runnable that sets event.setCancelled(true) — must be called
     *                     from this Netty thread before the method returns.
     */
    public void handleTrigger(Player player, KeyTrigger trigger, Runnable cancelPacket) {
        // O(1) lookup of the ENABLED hotkeys bound to this trigger (pre-indexed at config load).
        // The vast majority of packets hit keys with no binding and return here with no allocation.
        List<HotkeyDefinition> bound = plugin.getConfigManager().getHotkeysFor(trigger);
        if (bound.isEmpty()) return;

        // Keep only the ones this player is allowed to use. A list is allocated only when something
        // is actually bound to the key — the common no-binding case already returned above.
        List<HotkeyDefinition> valid = null;
        for (HotkeyDefinition hk : bound) {
            if (hk.permission().isEmpty() || player.hasPermission(hk.permission())) {
                if (valid == null) valid = new ArrayList<>(bound.size());
                valid.add(hk);
            }
        }
        if (valid == null) return;

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        // Hard floor — always enforced, even when global-cooldown is disabled. Silently
        // drops anything faster than MIN_DISPATCH_INTERVAL_MS so a packet flood can't turn
        // into unbounded command dispatch / scheduled tasks on the main/region thread.
        long lastDisp = lastDispatch.getOrDefault(uuid, 0L);
        if (now - lastDisp < MIN_DISPATCH_INTERVAL_MS) return;

        // Cooldown check — done on Netty thread; ConcurrentHashMap guarantees atomicity
        double cooldownSec = plugin.getConfigManager().getGlobalCooldown();
        if (cooldownSec > 0) {
            long last = cooldowns.getOrDefault(uuid, 0L);
            long remaining = (long)(cooldownSec * 1000) - (now - last);

            if (remaining > 0) {
                // Throttle the notification so packet-flooding can't schedule unbounded
                // main/region-thread tasks. Only message once per NOTIFY_THROTTLE_MS.
                long lastMsg = lastNotify.getOrDefault(uuid, 0L);
                if (now - lastMsg >= NOTIFY_THROTTLE_MS) {
                    lastNotify.put(uuid, now);
                    String remainingStr = String.format("%.1f", remaining / 1000.0);
                    FoliaUtil.runTask(plugin, player, () ->
                            plugin.getLangManager().send(player, "on-cooldown",
                                    Map.of("remaining", remainingStr)));
                }
                return;
            }
            cooldowns.put(uuid, now);
        }

        // Passed every gate — this is a real dispatch; record it for the hard floor.
        lastDispatch.put(uuid, now);

        // Cancel the underlying game action for any hotkey that requests it
        // Must happen here on the Netty thread — cannot be deferred
        boolean shouldCancel = valid.stream().anyMatch(HotkeyDefinition::cancelAction);
        if (shouldCancel) cancelPacket.run();

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[Debug] " + player.getName()
                    + " → trigger=" + trigger.name()
                    + " matched=" + valid.stream().map(HotkeyDefinition::id).toList());
        }

        // Execute commands on the correct server thread. Each matched hotkey may carry several
        // commands (multi-command entry); every command runs, in listed order.
        for (HotkeyDefinition def : valid) {
            for (String cmd : def.commands()) {
                String rawCmd = cmd.startsWith("/") ? cmd.substring(1) : cmd;

                FoliaUtil.runTask(plugin, player, () -> {
                    if (def.runAs() == RunAs.CONSOLE) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rawCmd);
                    } else {
                        player.performCommand(rawCmd);
                    }
                });
            }
        }
    }

    public void removeCooldown(UUID playerId) {
        cooldowns.remove(playerId);
        lastNotify.remove(playerId);
        lastDispatch.remove(playerId);
    }
}
