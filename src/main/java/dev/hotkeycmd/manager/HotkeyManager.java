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

    // Per-hotkey cooldown state: player → (hotkey id → last-trigger timestamp). Each hotkey
    // tracks its own cooldown independently so one hotkey's cooldown never blocks another.
    // Accessed from Netty threads → ConcurrentHashMap (outer and inner). The inner map is
    // created lazily on a player's first cooldownable trigger and dropped whole on quit.
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Long>> cooldowns =
            new ConcurrentHashMap<>();

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

        UUID uuid = player.getUniqueId();
        long now  = System.currentTimeMillis();

        // Hard floor FIRST — the cheapest gate, zero allocation. Always enforced, even when
        // global-cooldown is disabled: silently drops anything faster than MIN_DISPATCH_INTERVAL_MS
        // so a client holding/flooding a bound key can't turn into unbounded command dispatch on the
        // main/region thread. Checking it before the permission scan below means a flood never even
        // allocates a list or runs a permission check. (lastDispatch is only written on a real
        // dispatch, so a never-permitted player keeps lastDispatch=0 and always passes here — this
        // ordering changes no dispatch outcome, it only skips wasted work under a flood.)
        long lastDisp = lastDispatch.getOrDefault(uuid, 0L);
        if (now - lastDisp < MIN_DISPATCH_INTERVAL_MS) return;

        // Keep only the hotkeys this player may use. A list is allocated only for a real keypress
        // that passed the floor — the common no-binding case and floods already returned above.
        List<HotkeyDefinition> valid = null;
        for (HotkeyDefinition hk : bound) {
            if (hk.permission().isEmpty() || player.hasPermission(hk.permission())) {
                if (valid == null) valid = new ArrayList<>(bound.size());
                valid.add(hk);
            }
        }
        if (valid == null) return;

        // Per-hotkey cooldown check — on the Netty thread; ConcurrentHashMap guarantees atomicity.
        // Each matched hotkey is gated by its OWN cooldown (its configured `cooldown`, or the
        // global-cooldown when it left that unset). Ready hotkeys go into `ready`; ones still
        // cooling down are skipped (they neither run nor cancel). A hotkey with an effective
        // cooldown of 0 has no gate and is always ready.
        double globalCd = plugin.getConfigManager().getGlobalCooldown();
        ConcurrentHashMap<String, Long> playerCd = null;   // inner map, fetched lazily
        List<HotkeyDefinition> ready = null;
        long minRemaining = Long.MAX_VALUE;                // shortest wait among skipped hotkeys
        for (HotkeyDefinition hk : valid) {
            double cd = hk.cooldown() >= 0 ? hk.cooldown() : globalCd;
            if (cd > 0) {
                if (playerCd == null) {
                    playerCd = cooldowns.computeIfAbsent(uuid, u -> new ConcurrentHashMap<>());
                }
                long last = playerCd.getOrDefault(hk.id(), 0L);
                long remaining = (long)(cd * 1000) - (now - last);
                if (remaining > 0) {
                    if (remaining < minRemaining) minRemaining = remaining;
                    continue;   // this hotkey is on cooldown — skip it
                }
                playerCd.put(hk.id(), now);
            }
            if (ready == null) ready = new ArrayList<>(valid.size());
            ready.add(hk);
        }

        if (ready == null) {
            // Every matched hotkey is on cooldown. Notify once per NOTIFY_THROTTLE_MS so a
            // client flooding a bound key can't schedule unbounded main/region-thread tasks.
            long lastMsg = lastNotify.getOrDefault(uuid, 0L);
            if (now - lastMsg >= NOTIFY_THROTTLE_MS) {
                lastNotify.put(uuid, now);
                String remainingStr = String.format("%.1f", minRemaining / 1000.0);
                FoliaUtil.runTask(plugin, player, () ->
                        plugin.getLangManager().send(player, "on-cooldown",
                                Map.of("remaining", remainingStr)));
            }
            return;
        }

        // Passed every gate — this is a real dispatch; record it for the hard floor.
        lastDispatch.put(uuid, now);

        // Single pass over the ready hotkeys: decide whether to cancel the vanilla action
        // and whether there's anything to run. Cancel must happen here on the Netty thread.
        boolean shouldCancel = false;
        boolean anyCommand   = false;
        for (HotkeyDefinition def : ready) {
            if (def.cancelAction())         shouldCancel = true;
            if (!def.commands().isEmpty())  anyCommand   = true;
        }
        if (shouldCancel) cancelPacket.run();

        if (plugin.getConfigManager().isDebug()) {
            List<HotkeyDefinition> matched = ready;
            plugin.getLogger().info("[Debug] " + player.getName()
                    + " → trigger=" + trigger.name()
                    + " matched=" + matched.stream().map(HotkeyDefinition::id).toList());
        }

        // Execute every matched hotkey's commands on the correct server thread — in ONE scheduled
        // task per keypress instead of one per command (fewer main/region-thread hops), preserving
        // listed order. String work ({player} substitution, slash strip) is deferred into that task
        // so it stays off the Netty hot path. `valid` is a local list, fully built on this thread
        // before submission; the scheduler hand-off publishes it safely to the target thread.
        // Cancel-only hotkeys (no commands) schedule nothing.
        if (anyCommand) {
            List<HotkeyDefinition> toRun = ready;
            boolean debug = plugin.getConfigManager().isDebug();
            FoliaUtil.runTask(plugin, player, () -> {
                String playerName = player.getName();
                for (HotkeyDefinition def : toRun) {
                    boolean console = def.runAs() == RunAs.CONSOLE;
                    for (String cmd : def.commands()) {
                        // {player} → the triggering player's name. Mainly so a run-as: CONSOLE
                        // command can target the player (e.g. "heal {player}"); harmless for PLAYER.
                        String resolved = cmd.replace("{player}", playerName);
                        // Config commands are written without a leading '/'; tolerate a stray one.
                        String rawCmd = resolved.startsWith("/") ? resolved.substring(1) : resolved;
                        boolean ok;
                        if (console) {
                            ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rawCmd);
                        } else {
                            ok = player.performCommand(rawCmd);
                        }
                        // A false result means the command was not found or reported failure —
                        // the usual reason a hotkey "does nothing" (wrong run-as for a command
                        // that needs a player, unregistered command, missing permission, etc.).
                        // Surfaced only under debug so normal play stays silent.
                        if (debug && !ok) {
                            plugin.getLogger().warning("[Debug] command returned failure for "
                                    + playerName + " (" + def.id() + ", run-as "
                                    + (console ? "CONSOLE" : "PLAYER") + "): /" + rawCmd);
                        }
                    }
                }
            });
        }
    }

    public void removeCooldown(UUID playerId) {
        cooldowns.remove(playerId);
        lastNotify.remove(playerId);
        lastDispatch.remove(playerId);
    }
}
