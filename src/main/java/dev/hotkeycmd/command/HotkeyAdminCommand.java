package dev.hotkeycmd.command;

import dev.hotkeycmd.HotkeyPlugin;
import dev.hotkeycmd.config.HotkeyDefinition;
import dev.hotkeycmd.config.KeyTrigger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HotkeyAdminCommand implements CommandExecutor, TabCompleter {

    private final HotkeyPlugin plugin;

    public HotkeyAdminCommand(HotkeyPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ //
    //  Command execution
    // ------------------------------------------------------------------ //

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!sender.hasPermission("hkcmd.admin")) {
            plugin.getLangManager().send(sender, "admin-no-permission");
            return true;
        }

        if (args.length == 0) {
            plugin.getLangManager().send(sender, "usage");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> doReload(sender);
            case "list"   -> doList(sender);
            case "info"   -> {
                if (args.length < 2) {
                    plugin.getLangManager().send(sender, "usage");
                } else {
                    doInfo(sender, args[1]);
                }
            }
            default -> plugin.getLangManager().send(sender, "usage");
        }

        return true;
    }

    private void doReload(CommandSender sender) {
        try {
            plugin.getConfigManager().load();
            plugin.getLangManager().load(plugin.getConfigManager().getLocale());
            plugin.getLangManager().send(sender, "reload-success");
        } catch (Exception e) {
            plugin.getLogger().severe("Reload failed: " + e.getMessage());
            plugin.getLangManager().send(sender, "reload-fail");
        }
    }

    private void doList(CommandSender sender) {
        List<HotkeyDefinition> list = plugin.getConfigManager().getHotkeys();
        if (list.isEmpty()) {
            plugin.getLangManager().send(sender, "no-hotkeys");
            return;
        }
        plugin.getLangManager().send(sender, "list-header");
        for (HotkeyDefinition hk : list) {
            String statusKey = hk.enabled() ? "list-status-enabled" : "list-status-disabled";
            sender.sendMessage(plugin.getLangManager().get("list-entry", Map.of(
                    "id",      hk.id(),
                    "key",     keysStr(hk),
                    "command", commandsStr(hk),
                    "status",  plugin.getLangManager().get(statusKey)
            )));
        }
    }

    private void doInfo(CommandSender sender, String id) {
        HotkeyDefinition def = plugin.getConfigManager().getHotkeys().stream()
                .filter(hk -> hk.id().equalsIgnoreCase(id))
                .findFirst()
                .orElse(null);

        if (def == null) {
            plugin.getLangManager().send(sender, "info-not-found", Map.of("id", id));
            return;
        }

        String statusKey = def.enabled() ? "list-status-enabled" : "list-status-disabled";
        plugin.getLangManager().send(sender, "info-header",   Map.of("id", def.id()));
        sender.sendMessage(plugin.getLangManager().get("info-key",        Map.of("key",     keysStr(def))));
        sender.sendMessage(plugin.getLangManager().get("info-command",    Map.of("command", commandsStr(def))));
        sender.sendMessage(plugin.getLangManager().get("info-permission", Map.of("permission", def.permission().isEmpty() ? "(none)" : def.permission())));
        sender.sendMessage(plugin.getLangManager().get("info-cancel",     Map.of("cancel",  String.valueOf(def.cancelAction()))));
        sender.sendMessage(plugin.getLangManager().get("info-run-as",     Map.of("run_as",  def.runAs().name())));
        sender.sendMessage(plugin.getLangManager().get("info-status",     Map.of("status",  plugin.getLangManager().get(statusKey))));
    }

    // ------------------------------------------------------------------ //
    //  Tab completion
    // ------------------------------------------------------------------ //

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String label,
                                                @NotNull String[] args) {

        if (!sender.hasPermission("hkcmd.admin")) return List.of();

        if (args.length == 1) {
            return filter(List.of("reload", "list", "info"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            List<String> ids = plugin.getConfigManager().getHotkeys()
                    .stream()
                    .map(HotkeyDefinition::id)
                    .toList();
            return filter(ids, args[1]);
        }

        return List.of();
    }

    /** All of a hotkey's keys as a comma-separated string for display. */
    private static String keysStr(HotkeyDefinition def) {
        return def.keys().stream().map(KeyTrigger::name).collect(Collectors.joining(", "));
    }

    /** All of a hotkey's commands as a comma-separated string for display ("(none)" if empty). */
    private static String commandsStr(HotkeyDefinition def) {
        return def.commands().isEmpty() ? "(none)" : String.join(", ", def.commands());
    }

    private List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(lower))
                .toList();
    }
}
