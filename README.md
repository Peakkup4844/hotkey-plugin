# HotkeyCommands

> ⚠️ **Beta software.** This plugin works but is still being tested in the wild. Config keys and
> behavior may change between versions. Test on a staging server before using it in production,
> and please report anything that breaks.

Run any server command from an in-game **hotkey**. Bind commands to keys players already press —
swap-hand (F), drop (Q), hotbar slots (1–9) — and their Shift variants, with optional cancelling
of the original action.

Built on [PacketEvents](https://github.com/retrooper/packetevents) and works on
**Paper, Spigot, and Folia** (Minecraft 1.21+).

---

## Features

- 🎯 **Bind commands to keys** — F, Q, Ctrl+Q, hotbar 1–9, plus every Shift variant.
- 🔗 **Multi-key** — one entry can trigger on several keys (`keys: [SWAP_HAND, DROP]`).
- ⛓️ **Multi-command** — one keypress can run several commands in order (`commands: [...]`).
- 🚫 **Cancel the vanilla action** — e.g. press Ctrl+Q to open a shop *without* dropping your stack.
- 👤 **Run as player or console** — per hotkey (`run-as: PLAYER | CONSOLE`).
- 🌍 **Multi-language** — English (default), 简体中文, ไทย; unknown locale falls back to English.
- 🛡️ **Anti-flood** — global cooldown plus a hard 50 ms dispatch floor that can't be disabled.
- 🧵 **Folia-aware** — commands dispatch on the correct region/main thread automatically.
- ♻️ **Safe config upgrades** — new settings are merged into an edited `config.yml` without
  touching your values or your hotkeys.

---

## Requirements

| | |
|---|---|
| **Server** | Paper / Spigot / Folia, Minecraft **1.21+** |
| **Java** | **21** or newer |
| **Dependency** | [**PacketEvents**](https://modrinth.com/plugin/packetevents) **2.12.0+** (Spigot build) — required |

> PacketEvents is a **hard dependency** and must be installed separately. Without it the plugin
> will not load (you'll see `UnknownDependencyException: [packetevents]` in the console).

---

## Installation

1. Download **PacketEvents** (Spigot build, 2.12.0+) and drop it in `plugins/`.
2. Drop `HotkeyCommands-x.x.x.jar` in `plugins/`.
3. **Restart** the server (a `/reload` is not enough — hard dependencies must be present at load).
4. Edit `plugins/HotkeyCommands/config.yml`, then run `/hkcmd reload`.

---

## Configuration

```yaml
locale: en              # en | zh | th | <custom>
global-cooldown: 1.5    # seconds shared across all hotkeys per player (0 = off)
debug: false
ignore-bedrock: true    # skip Bedrock players (Floodgate only)

hotkeys:
  warp_menu:
    key: SNEAK_SWAP_HAND       # Shift + F
    command: "/warps"
    permission: hkcmd.key.warp_menu
    enabled: true
    cancel-action: true        # don't actually swap hands
    run-as: PLAYER

  # Multi-key + multi-command in one entry
  buff_combo:
    keys: [SNEAK_HOTBAR_1, SNEAK_HOTBAR_2]
    commands:
      - "/heal"
      - "/feed"
    permission: hkcmd.key.buff_combo
    enabled: true
    cancel-action: false
    run-as: PLAYER
```

### Available keys

| Identifier | Key | | Identifier | Key |
|---|---|---|---|---|
| `SWAP_HAND` | F | | `SNEAK_SWAP_HAND` | Shift + F |
| `DROP` | Q | | `SNEAK_DROP` | Shift + Q |
| `DROP_STACK` | Ctrl + Q | | `SNEAK_DROP_STACK` | Shift + Ctrl + Q |
| `HOTBAR_1` … `HOTBAR_9` | Number 1–9 / scroll | | `SNEAK_HOTBAR_1` … `SNEAK_HOTBAR_9` | Shift + 1–9 |

`key` / `keys` and `command` / `commands` each accept either a single value or a list.
Leaving `commands` empty with `cancel-action: true` makes a hotkey that only **blocks** the
vanilla action and runs nothing.

---

## Commands & permissions

| Command | Description |
|---|---|
| `/hkcmd reload` | Reload config and language files |
| `/hkcmd list` | List all hotkeys |
| `/hkcmd info <id>` | Show details of one hotkey |

Aliases: `/hotkeycmd`, `/hkc`

| Permission | Default | Description |
|---|---|---|
| `hkcmd.admin` | op | Access to `/hkcmd` sub-commands |
| `hkcmd.key.<id>` | false | Allow a specific hotkey |
| `hkcmd.key.*` | false | Allow every hotkey |

A player without the required permission is silently ignored (no message).

---

## Limitations

- **Only a fixed set of keys reaches the server.** Minecraft never tells the server about ordinary
  letter keys (L, K, G, R, …) — they're handled entirely by the client. Only F, Q, Ctrl+Q and the
  hotbar keys (plus sneak state) are visible, so those are all you can bind **without a client-side
  mod**. *Workaround:* a player can rebind "Swap Item With Offhand" or "Drop Item" to another key in
  **Options → Controls**, and that key will then trigger the matching hotkey.
- **Cooldown is global per player.** All hotkeys share one `global-cooldown`; there is no per-hotkey
  cooldown yet.
- **Bedrock detection needs Floodgate.** `ignore-bedrock` identifies Bedrock players by their
  Floodgate UUID signature. With Geyser in standalone-proxy mode, Bedrock players look like Java
  players and can't be skipped.
- **CONSOLE hotkeys must have a permission.** A `run-as: CONSOLE` hotkey without a `permission` node
  is refused at load (it would let any player run console commands).
- **PacketEvents required.** See [Requirements](#requirements).

---

## Building from source

```bash
mvn clean package
# → target/HotkeyCommands-x.x.x.jar
```

Requires JDK 21+. Build **online** — an offline build (`-o`) fails to resolve a transitive
dependency needed by the Paper API.

---

## Status

Beta. Feedback and bug reports are welcome via the issue tracker.

## License

Licensed under the [GNU General Public License v3.0](LICENSE). You may use, modify, and redistribute
this plugin, but any distributed derivative must remain open source under the same license.
