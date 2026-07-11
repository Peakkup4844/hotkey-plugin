package dev.hotkeycmd.config;

import java.util.List;

/**
 * One hotkey entry from config.yml.
 *
 * <p>{@code keys} and {@code commands} are lists so a single entry can be bound to several
 * keys and/or run several commands. Both are held as immutable {@link List#copyOf} copies by
 * {@code ConfigManager} — this is what lets a definition be published to the Netty-thread hot
 * path via a single volatile reference and read without further synchronization (see the
 * concurrency invariants in SKILL.md). Never mutate these lists after construction.
 */
public record HotkeyDefinition(
        String id,
        List<KeyTrigger> keys,
        List<String> commands,
        String permission,
        boolean enabled,
        boolean cancelAction,
        RunAs runAs
) {}
