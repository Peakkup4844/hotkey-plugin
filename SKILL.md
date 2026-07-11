# HotkeyCommands — SKILL.md

> **กฎสำคัญ**: ทุกครั้งที่มีการแก้โครงสร้างโปรเจกต์ (เพิ่ม/ลบ/ย้าย class, เปลี่ยน dependency,
> เปลี่ยน enum values, เปลี่ยน config schema) **ต้องมาอัปเดตไฟล์นี้ด้วยเสมอ**

---

## โครงสร้างโปรเจกต์

```
hotkey-plugin/
├── pom.xml
└── src/main/
    ├── java/dev/hotkeycmd/
    │   ├── HotkeyPlugin.java                ← main class
    │   ├── command/
    │   │   └── HotkeyAdminCommand.java      ← /hkcmd + TabCompleter
    │   ├── config/
    │   │   ├── ConfigManager.java           ← โหลด config.yml
    │   │   ├── HotkeyDefinition.java        ← record (id, keys[], commands[], …)
    │   │   ├── KeyTrigger.java              ← enum hotkey identifiers
    │   │   └── RunAs.java                   ← enum PLAYER | CONSOLE
    │   ├── lang/
    │   │   └── LangManager.java             ← lang_<locale>.yml
    │   ├── listener/
    │   │   ├── PacketHotkeyListener.java    ← PacketEvents (Netty thread)
    │   │   └── PlayerQuitListener.java      ← cleanup on disconnect
    │   ├── manager/
    │   │   └── HotkeyManager.java           ← core trigger logic
    │   └── util/
    │       └── FoliaUtil.java               ← scheduler abstraction
    └── resources/
        ├── plugin.yml
        ├── config.yml
        ├── lang_en.yml                 ← English (default locale)
        ├── lang_zh.yml                 ← 简体中文
        └── lang_th.yml                 ← ไทย
```

---

## Dependencies (pom.xml)

| Artifact | Version | Scope | หมายเหตุ |
|---|---|---|---|
| `io.papermc.paper:paper-api` | `1.21.4-R0.1-SNAPSHOT` | provided | รองรับ Paper/Spigot/Folia |
| `com.github.retrooper:packetevents-spigot` | `2.12.0` | provided | hard depend — ผู้ใช้ต้องติดตั้งเองบน server |

### Repositories ที่จำเป็น

```xml
https://repo.papermc.io/repository/maven-public/   (snapshots=true)
https://repo.codemc.io/repository/maven-releases/
https://libraries.minecraft.net/                    ← ต้องมี! Maven 4 strict enforcement บล็อก com.mojang:brigadier จาก Central
```

> **หมายเหตุ Maven 4**: `com.mojang:brigadier` (transitive ของ Paper API) ต้องดึงจาก
> `libraries.minecraft.net` โดยตรง ถ้าไม่ใส่ repo นี้จะ build ไม่ผ่านบน Maven 4.x

---

## PacketEvents 2.12.0 — API จริงที่ต้องใช้

> ชื่อ class/enum เหล่านี้ได้จากการ inspect JAR โดยตรง (`javap`) อย่าเปลี่ยนโดยไม่ verify

### Wrapper classes (client → server)

| การกด | Packet Type constant | Wrapper class |
|---|---|---|
| Shift / Sprint / Elytra | `PacketType.Play.Client.ENTITY_ACTION` | `WrapperPlayClientEntityAction` |
| F (swap hand), Q (drop), Ctrl+Q (drop stack) | `PacketType.Play.Client.PLAYER_DIGGING` | `WrapperPlayClientPlayerDigging` |
| 1–9 / scroll (hotbar) | `PacketType.Play.Client.HELD_ITEM_CHANGE` | `WrapperPlayClientHeldItemChange` |

> **ข้อจำกัดสำคัญ**: server เห็นเฉพาะ key action ชุดคงที่นี้เท่านั้น ปุ่มตัวอักษร (L/K/G/R/…)
> client จัดการเองล้วน **ไม่ส่งมา server** → bind เป็น hotkey ไม่ได้ถ้าไม่มี client mod

### Enum values จริง

```java
// WrapperPlayClientEntityAction.Action (inner enum ของ wrapper)
WrapperPlayClientEntityAction.Action.START_SNEAKING
WrapperPlayClientEntityAction.Action.STOP_SNEAKING
WrapperPlayClientEntityAction.Action.START_SPRINTING
WrapperPlayClientEntityAction.Action.STOP_SPRINTING
// …และอื่น ๆ

// com.github.retrooper.packetevents.protocol.player.DiggingAction
DiggingAction.SWAP_ITEM_WITH_OFFHAND   ← F key  (ไม่ใช่ SWAP_ITEM_WITH_HAND!)
DiggingAction.DROP_ITEM                ← Q key (single)     → DROP / SNEAK_DROP
DiggingAction.DROP_ITEM_STACK          ← Ctrl+Q (whole stack) → DROP_STACK / SNEAK_DROP_STACK
DiggingAction.START_DIGGING
DiggingAction.FINISHED_DIGGING
```

### Sneak tracking

Packet ของ sneak และ action อาจมาใน Netty flush เดียวกัน ทำให้ `player.isSneaking()`
ยังเป็น state เก่าได้ → track เองใน `ConcurrentHashMap.newKeySet()` ผ่าน `ENTITY_ACTION` packet
ใน `PacketHotkeyListener`

> **แต่ต้อง OR กับ `player.isSneaking()` ตอนอ่าน** (`PacketHotkeyListener.isSneaking(player)`):
> set ครอบเคส same-flush race, ส่วน Bukkit flag ครอบเคสที่ **ไม่เคยเห็น START_SNEAKING เลย**
> (ผู้เล่นกด Shift ค้างอยู่แล้วตอน plugin enable/reload หรือ Folia region handoff) — ถ้าไม่มี fallback นี้
> SNEAK_* hotkey จะถูกอ่านเป็น non-sneak variant เงียบ ๆ แล้ว "กดไม่ติด"

---

## KeyTrigger enum — hotkeys ที่รองรับ

```
SWAP_HAND          F
SNEAK_SWAP_HAND    Shift + F
DROP               Q
SNEAK_DROP         Shift + Q
DROP_STACK         Ctrl + Q
SNEAK_DROP_STACK   Shift + Ctrl + Q
HOTBAR_1…9         กด 1–9 (หรือ scroll)
SNEAK_HOTBAR_1…9   Shift + 1–9
```

`KeyTrigger.hotbar(int slot, boolean sneak)` — helper สร้าง enum จาก slot 0-based

> ปุ่มตัวอักษร (L/K/G/…) เพิ่มเข้า enum ไม่ได้ผล — server ไม่ส่ง packet ของปุ่มพวกนี้มา (ดู PacketEvents section)

---

## Bedrock / Geyser handling

`PacketHotkeyListener` ข้ามผู้เล่น Bedrock เมื่อ `ignore-bedrock: true` (default) — early-return
ก่อนแตะ logic ใด ๆ (รวม sneak tracking)

```java
// PacketHotkeyListener.isBedrock(player)
player.getUniqueId().getMostSignificantBits() == 0L
```

- ตรวจจาก **ลายเซ็น UUID ของ Floodgate** — มันสร้าง UUID ของ Bedrock player เป็น `new UUID(0, xuid)`
  → MSB เป็น 0 เสมอ ซึ่ง Java UUID (v4 random / v3 name-based) ไม่มีทางเป็น
- **ไม่ต้องเพิ่ม dependency** (ไม่ต้อง depend Floodgate API) เป็นแค่ field read เดียวบน Netty thread
- ใช้ได้เฉพาะกับ **Floodgate** เท่านั้น — Geyser proxy ล้วน ผู้เล่น Bedrock จะดูเหมือน Java แยกไม่ออก

---

## Folia Compatibility

```java
// FoliaUtil.IS_FOLIA  — ตรวจ runtime ด้วย Class.forName("io.papermc.paper.threadedregions.RegionizedServer")

// ใช้แทน Bukkit.getScheduler().runTask() เสมอ:
FoliaUtil.runTask(plugin, player, () -> { /* code ที่ต้องรันบน main/region thread */ });
```

PacketEvents process บน Netty thread → ทุก command dispatch / message send ต้องผ่าน `FoliaUtil.runTask`
แต่ **cancel event (`event.setCancelled(true)`) ต้องทำบน Netty thread ก่อน return** — ห้าม defer

---

## Config Schema

```yaml
# config.yml
locale: en             # en (default) | zh | th | <custom>  — unknown → fallback en
global-cooldown: 1.5   # วินาที, 0 = ปิด (มี hard floor 50ms บังคับเสมอ)
debug: false
ignore-bedrock: true   # ข้ามผู้เล่น Bedrock (Geyser/Floodgate) — default true
hotkeys:
  <id>:
    key: <KeyTrigger>       # single — หรือใช้ `keys: [A, B]` ผูกหลายปุ่มใน entry เดียว
    command: "/..."         # single — หรือใช้ `commands: ["/a", "/b"]` รันหลายคำสั่ง
    permission: "hkcmd.key.<id>"
    enabled: true
    cancel-action: true     # per-hotkey
    run-as: PLAYER          # PLAYER | CONSOLE
```

**Multi-key / multi-command** (`ConfigManager.readStringList(sec, paths…)` — รับทั้ง scalar และ list):
- `key`/`keys` → parse เป็น `List<KeyTrigger>` (dedup ด้วย LinkedHashSet); ถ้ามีปุ่มใด invalid → skip ทั้ง entry + WARNING
- `command`/`commands` → parse เป็น `List<String>` (ตัด blank ออก); **empty list ได้** = hotkey ที่ cancel action เฉย ๆ ไม่รันคำสั่ง
- `HotkeyDefinition` เก็บ `List<KeyTrigger> keys` + `List<String> commands` (ทั้งคู่ `List.copyOf` = immutable ตาม invariant)
- `HotkeyManager` dispatch: วน `valid` → วน `def.commands()` รันทุกคำสั่งตามลำดับ; `shouldCancel = anyMatch(cancelAction)`
- 1 entry ที่มีหลาย key ถูก index **ซ้ำใต้ทุก key** ใน `byKey` (def object เดียวกัน)

### Config migration (ConfigManager.migrateConfig — รันทุก load / reload)

Upgrade config เก่าที่ผู้ใช้แก้ไว้แบบ **additive, non-destructive**:
- เติมเฉพาะ **top-level scalar setting ที่ขาด** จาก bundled default (`plugin.getResource("config.yml")`)
  → ค่าใหม่โผล่ในไฟล์ผู้ใช้โดยไม่ทับค่าที่ตั้งไว้ (`cfg.contains(key, true)` = เช็คว่าอยู่ในไฟล์จริงไหม โดย ignore default)
- **ข้าม `hotkeys:` เสมอ** (และข้าม section อื่น ๆ) — กันการยัด example hotkey ที่ผู้ใช้ลบไปกลับมา / ไม่แตะ hotkey ผู้ใช้
- เขียนไฟล์กลับ **เฉพาะเมื่อมีของขาดจริง** (`changed`) → config ที่ครบอยู่แล้วไม่ถูก re-serialize, comment/format เดิมอยู่ครบ
- save fail → ยังรันต่อได้ (ทุก read มี hard-coded default fallback)

> ถ้าเพิ่ม top-level setting ใหม่ใน config.yml (bundled) → migration หยิบไปให้ user เดิมอัตโนมัติ
> **แต่ถ้าเพิ่มเป็น section** (`isConfigurationSection`) จะถูกข้าม — ต้องเพิ่ม logic เอง

### Validation rules (ConfigManager.load)

- `key` ที่ผิด enum → ข้าม entry นั้น + log WARNING ที่ **list ปุ่มที่ valid ทั้งหมด** (`validKeys()`)
  และบอกว่าปุ่มตัวอักษรใช้ไม่ได้ (กัน silent "กดไม่ติด" จากการพิมพ์ชื่อปุ่มที่ไม่มี เช่น `L`)
- `run-as` ที่ผิด enum → ข้าม entry นั้น + log WARNING (บอกให้ใช้ PLAYER/CONSOLE)
- **`run-as: CONSOLE` ต้องมี `permission` ไม่ว่าง** ถ้าว่างจะถูก **ปฏิเสธไม่โหลด** + log SEVERE
  (กัน privilege escalation — console hotkey ที่ไม่มี permission = ผู้เล่นทุกคนรันคำสั่ง console ได้)

---

## Lang Schema

ไฟล์ที่ bundle มาด้วย: `lang_en.yml` (English, **default**), `lang_zh.yml` (简体中文), `lang_th.yml` (ไทย)
— ทุกไฟล์มี key ชุดเดียวกัน ต้อง sync กันเมื่อเพิ่ม/แก้ message

Placeholders สำคัญ:
- `{prefix}` — ถูก inject ทุก key อัตโนมัติ
- `{remaining}` — ใน `on-cooldown`
- `{id}`, `{key}`, `{command}`, `{status}` — ใน `list-entry`, `info-*`
- `{run_as}`, `{cancel}`, `{permission}` — ใน `info-*`

เพิ่มภาษาใหม่: สร้างไฟล์ `lang_<locale>.yml` ใน `src/main/resources/`
แล้วเปลี่ยน `locale:` ใน config.yml — ถ้า locale file ไม่มีจะ fallback เป็น `lang_en.yml`
(`LangManager.load` + default ใน `ConfigManager` = `en`)

> **หมายเหตุ**: `no-permission` และ `hotkey-disabled` มีใน lang ไฟล์แต่ **ยังไม่ถูกส่งจาก code**
> (permission fail = silent ตาม design) — เก็บไว้เผื่ออนาคต

---

## Permission Nodes

| Node | Default | คำอธิบาย |
|---|---|---|
| `hkcmd.admin` | op | `/hkcmd reload\|list\|info` |
| `hkcmd.key.<id>` | false | hotkey แต่ละชุดตาม config |
| `hkcmd.key.*` | false | ทุก hotkey |

ถ้าไม่มี permission → **silent fail** (ไม่มีข้อความ)

---

## Admin Command

```
/hkcmd reload          → โหลด config + lang ใหม่
/hkcmd list            → แสดง hotkey ทั้งหมด
/hkcmd info <id>       → รายละเอียด hotkey ชุดนั้น
```
Aliases: `/hotkeycmd`, `/hkc`  
Tab complete รองรับทุก sub-command และ `<id>` จาก config ปัจจุบัน

---

## Build

```bash
mvn package
# Output: target/HotkeyCommands-1.0.0.jar
```

Java 21+ required (Maven 4 + JDK 24 ทดสอบแล้ว — ใช้ได้)

### ติดตั้ง

1. วาง **PacketEvents** jar ก่อน (`plugins/`)
2. วาง **HotkeyCommands** jar
3. Restart server → `config.yml` + `lang_th.yml` ถูกสร้างอัตโนมัติ

---

## Security notes

- **CONSOLE ต้องมี permission** — บังคับใน `ConfigManager.load` (ดู Validation rules ข้างบน)
- **Cooldown notify throttle** — `HotkeyManager` ส่งข้อความ `on-cooldown` ได้สูงสุด 1 ครั้ง/วินาที/ผู้เล่น
  (`NOTIFY_THROTTLE_MS = 1000`, map `lastNotify`) กัน packet-flood สร้าง main/region-thread task ไม่จำกัด
- **Hard dispatch floor** — `HotkeyManager` จำกัดการ dispatch command ขั้นต่ำ 50ms/ผู้เล่น
  (`MIN_DISPATCH_INTERVAL_MS = 50`, map `lastDispatch`) **ทำงานเสมอแม้ `global-cooldown: 0`**
  trigger ที่เร็วกว่านี้จะถูก drop เงียบ ๆ (ไม่มีข้อความ) → กัน DoS amplification จาก client เดียว
  ที่สแปม packet เป็นพัน/วินาทีให้กลายเป็น command dispatch ไม่จำกัดบน main/region thread
  → ทั้ง `lastNotify` และ `lastDispatch` ถูกล้างใน `removeCooldown()` (เรียกตอน quit ด้วย)
- Command มาจาก config แอดมินล้วน ไม่มี input ผู้เล่นไหลเข้า command string → ไม่มี command injection
- ผู้เล่นไม่มี input ไหลเข้า lang message (`on-cooldown` มีแค่ `{remaining}` ที่ฟอร์แมตเป็นตัวเลข)
  → ไม่มี color-code / format-string injection

---

## Concurrency & performance invariants (ห้ามทำพัง)

อ่าน/เขียน field ข้าม thread: `load()` ของ `ConfigManager`/`LangManager` รันบน **main thread** (ผ่าน `/hkcmd reload`)
แต่ hot path อ่านบน **Netty thread** ของ PacketEvents → มี invariant ที่ต้องรักษา:

- **`volatile` บน field ที่ถูก hot-swap ตอน reload** — `ConfigManager.hotkeys` / `byKey` / `globalCooldown` /
  `locale` / `debug` / `ignoreBedrock` และ `LangManager.lang` ต้องเป็น `volatile` เพื่อ safe publication
  (กัน Netty thread เห็นค่าเก่าค้างหลัง reload). collection ที่ชี้ไปต้อง immutable/effectively-immutable
  — สร้างให้เสร็จก่อนแล้วค่อย assign ทับ reference เดียว **ห้าม mutate หลัง publish**
- **`byKey` = EnumMap index (เฉพาะ `enabled`) สำหรับ hot path** — `HotkeyManager.handleTrigger` lookup
  `getHotkeysFor(trigger)` เป็น O(1) ไม่มี allocation ในเคส "ไม่มี hotkey ผูกกับปุ่มนั้น" (เคสส่วนใหญ่)
  → **ถ้าแก้ `load()` ต้อง rebuild `byKey` ให้ sync กับ `hotkeys` เสมอ** และ index เก็บเฉพาะ enabled
  (flat list `hotkeys` เก็บครบทุก entry ไว้ให้ admin list/info) — ถ้าลืม index ปุ่มจะกดไม่ติด
  → entry ที่มีหลาย key ถูก add เข้า index **ทุก key** (loop `def.keys()`) — def เดียวโผล่ในหลาย bucket ได้
- **`ArrayList valid` ถูก alloc เฉพาะตอนมี hotkey ผูกจริง** (lazy) ห้ามเปลี่ยนกลับไป stream ทุก packet

---

## สิ่งที่ยังไม่รองรับ / Future work

- Per-hotkey cooldown (ปัจจุบันเป็น global เท่านั้น)
- Sprint / Elytra เป็น trigger (ENTITY_ACTION พร้อมแล้ว แค่ยังไม่ expose ใน KeyTrigger —
  จงใจไม่ทำเพราะ START_SPRINTING fire ทุกครั้งที่เริ่มวิ่ง = spam/footgun)
