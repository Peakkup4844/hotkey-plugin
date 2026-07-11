package dev.hotkeycmd.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import dev.hotkeycmd.HotkeyPlugin;
import dev.hotkeycmd.config.KeyTrigger;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PacketHotkeyListener extends PacketListenerAbstract {

    private final HotkeyPlugin plugin;

    /**
     * We track sneak state ourselves because on the Netty thread player.isSneaking()
     * may still reflect the previous tick's value when the ENTITY_ACTION (sneak start)
     * packet and the action packet arrive in the same Netty flush.
     *
     * <p>This is combined with player.isSneaking() at read time (see isSneaking()): the set
     * covers the same-flush race, and the Bukkit flag covers the case where we never saw the
     * START_SNEAKING packet at all — e.g. the player was already holding Shift when the plugin
     * enabled/reloaded, or a Folia region handoff. Without that fallback, SNEAK_* hotkeys would
     * silently misfire as their non-sneak variant and appear to "do nothing".
     */
    private final Set<UUID> sneaking = ConcurrentHashMap.newKeySet();

    public PacketHotkeyListener(HotkeyPlugin plugin) {
        super(PacketListenerPriority.LOWEST);   // intercept before other plugins
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        // Bedrock players (via Geyser/Floodgate) have a different control scheme — F/Q and
        // hotbar keys don't map cleanly and would fire hotkeys unexpectedly. Skip them
        // entirely when configured. The check is essentially free, so it stays on the Netty
        // thread. See isBedrock() for the detection method.
        if (plugin.getConfigManager().isIgnoreBedrock() && isBedrock(player)) return;

        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            handleEntityAction(player, event);

        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            handlePlayerDigging(player, event);

        } else if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            handleHeldItemChange(player, event);
        }
    }

    // --------------------------------------------------------------------- //

    private void handleEntityAction(Player player, PacketReceiveEvent event) {
        WrapperPlayClientEntityAction packet = new WrapperPlayClientEntityAction(event);
        WrapperPlayClientEntityAction.Action action = packet.getAction();
        if (action == WrapperPlayClientEntityAction.Action.START_SNEAKING) {
            sneaking.add(player.getUniqueId());
        } else if (action == WrapperPlayClientEntityAction.Action.STOP_SNEAKING) {
            sneaking.remove(player.getUniqueId());
        }
    }

    private void handlePlayerDigging(Player player, PacketReceiveEvent event) {
        WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
        DiggingAction action = packet.getAction();
        boolean isSneaking = isSneaking(player);

        if (action == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
            // F key — swap main hand ↔ offhand
            KeyTrigger trigger = isSneaking ? KeyTrigger.SNEAK_SWAP_HAND : KeyTrigger.SWAP_HAND;
            plugin.getHotkeyManager().handleTrigger(player, trigger, () -> event.setCancelled(true));

        } else if (action == DiggingAction.DROP_ITEM) {
            // Q key — drop a single item
            KeyTrigger trigger = isSneaking ? KeyTrigger.SNEAK_DROP : KeyTrigger.DROP;
            plugin.getHotkeyManager().handleTrigger(player, trigger, () -> event.setCancelled(true));

        } else if (action == DiggingAction.DROP_ITEM_STACK) {
            // Ctrl+Q — drop the whole stack
            KeyTrigger trigger = isSneaking ? KeyTrigger.SNEAK_DROP_STACK : KeyTrigger.DROP_STACK;
            plugin.getHotkeyManager().handleTrigger(player, trigger, () -> event.setCancelled(true));
        }
    }

    private void handleHeldItemChange(Player player, PacketReceiveEvent event) {
        WrapperPlayClientHeldItemChange packet = new WrapperPlayClientHeldItemChange(event);
        int slot = packet.getSlot();   // 0-based: 0 = slot 1, 8 = slot 9
        if (slot < 0 || slot > 8) return;

        KeyTrigger trigger = KeyTrigger.hotbar(slot, isSneaking(player));
        plugin.getHotkeyManager().handleTrigger(player, trigger, () -> event.setCancelled(true));
    }

    /**
     * True if the player is sneaking. Prefers our packet-tracked state (accurate even within the
     * same Netty flush as the sneak-start packet) and falls back to the Bukkit flag for the case
     * where we never saw a START_SNEAKING packet (already sneaking on enable/reload / region handoff).
     */
    private boolean isSneaking(Player player) {
        return sneaking.contains(player.getUniqueId()) || player.isSneaking();
    }

    /**
     * Detects Bedrock players connecting through Floodgate. Floodgate derives a Bedrock
     * player's UUID from their XUID as {@code new UUID(0, xuid)}, so the most-significant
     * bits are always 0 — a value real Java UUIDs (random v4 / name-based v3) never have.
     * This needs no Floodgate dependency and is a single field read on the Netty thread.
     *
     * <p>Note: only works with Floodgate. With Geyser in pure proxy mode the player appears
     * as an ordinary Java account and cannot be distinguished (nor would skipping be possible).
     */
    private static boolean isBedrock(Player player) {
        return player.getUniqueId().getMostSignificantBits() == 0L;
    }

    /** Called by PlayerQuitListener to clean up on disconnect. */
    public void removeSneaking(UUID playerId) {
        sneaking.remove(playerId);
    }
}
