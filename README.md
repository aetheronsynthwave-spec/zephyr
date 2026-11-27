# Zephyr

Multi-layered combat/utility client framework for **Minecraft Java Edition 1.21.11 (Fabric)**.
Built as an engineering reference for crystal PvP, anchor PvP, mace/spear-mace mechanics and
movement/utility macros — clean interfaces, explicit state machines, randomized ms/CPS timing
controllers, bounded rotation smoothers, and packet-level silent rotations.

> **Heads up:** tooling of this kind violates the rules of most multiplayer servers and the
> Minecraft EULA. Use it in single-player, private practice servers, or wherever you have
> explicit permission. You are responsible for where you run it.

## Build

Requirements: JDK 21, Gradle 8.9+.

```bash
cd zephyr-client
gradle wrapper --gradle-version 8.14
./gradlew build
# jar: build/libs/zephyr-1.0.0.jar
```

Toolchain (verified against https://fabricmc.net/develop, Dec 2025):

| Component   | Version            |
|-------------|--------------------|
| Minecraft   | 1.21.11            |
| Yarn        | 1.21.11+build.6    |
| Loader      | 0.18.1             |
| Fabric API  | 0.141.6+1.21.11    |
| Loom        | 1.14-SNAPSHOT      |

`defaultRequire: 0` is set in `zephyr.mixins.json`, so a mapping rename in a future point
release degrades one feature instead of crashing the client — but check the mixin list below
if a subsystem silently does nothing after a version bump.

### 1.21.11 API notes baked into this codebase

All member names below were verified against the official `yarn 1.21.11+build.6` mappings
and the published `fabric-api 0.141.6+1.21.11` jar:

- World render events moved to `net.fabricmc.fabric.api.client.rendering.v1.world.*`
  (`WorldRenderEvents.AFTER_ENTITIES` + a slimmer `WorldRenderContext` without
  `camera()`/`tickCounter()`; camera position comes from the focused entity, tick progress
  from `MinecraftClient.getRenderTickCounter().getTickProgress(false)`).
- `HudRenderCallback` passes a `RenderTickCounter`, not a float delta.
- The lines layer is `RenderLayers.lines()` (old `RenderLayer.getLines()` is gone) and the
  vertex stream now carries a trailing line-width element (`VertexConsumer#lineWidth`).
- `Camera#update(World, Entity, boolean, boolean, float)`; `Camera#getPos()` is gone.
- `ClientPlayerEntity` no longer overrides `tickMovement` — the movement/landing/jump hooks
  live on `LivingEntity#tickMovement` guarded to the local player; the jump-delay field is
  `LivingEntity.jumpingCooldown`.
- `Entity#isRemoved()` / `Entity#getPos()` / `LivingEntity#getArmorItems()` /
  `AbstractTeam#isFriendlyTo` / `MatrixStack#multiplyYaw` / `ItemCooldownManager#set(Item,int)`
  no longer exist in that form; the code uses `isAlive()`, `getLerpedPos(1.0F)`,
  `getEquippedStack(EquipmentSlot)`, `Entity#isTeammate`, `RotationAxis` and the
  `(Identifier|ItemStack, int)` cooldown overloads respectively.
- Enchantment data is read through `EnchantmentHelper#getEnchantments(stack)` +
  `ItemEnchantmentsComponent#getLevel(RegistryEntry)` (the `DataComponentTypes.ENCHANTMENTS`
  constant is unmapped in yarn 1.21.11).

## Architecture

```
dev.zephyr
├── ZephyrClient            entrypoint: wiring, keybind polling, event registration
├── ZephyrConfig            JSON config (config/zephyr.json), plain key/value per module
├── command/ZephyrCommand   /zephyr list|toggle|set|bind|save (client commands)
├── core
│   ├── Module / ModuleCategory / ModuleManager     registry + guarded tick dispatch
│   ├── settings/           Setting, Boolean/Number/Mode/Key settings
│   ├── event/              EventBus, Cancellable, mixin-fired events
│   │                       (PACKET_SEND, PLAYER_MOVEMENT, LOOK_DELTA, GROUND_LAND)
│   ├── rotation/           RotationManager + RotationRequest
│   │                       bounded smoothing (min/max deg/tick caps, micro-jitter),
│   │                       silent mode rides vanilla movement packets; interact
│   │                       packets (1.21.2+ yaw/pitch) use withServerRotation()
│   ├── target/             TargetManager + TargetCriteria scoring pipeline
│   │                       (distance/low-health/crosshair/threat, sticky hysteresis)
│   ├── combat/             DamageCalculator (vanilla explosion exposure formula,
│   │                       blast-protection/armor reductions, mace/fall math),
│   │                       CombatActions (all interactions via vanilla paths)
│   ├── world/              RaycastValidator (LOS, hitbox probes, crystal/anchor legality)
│   ├── inventory/          InventoryManager (hotbar select, offhand/hotbar swaps)
│   ├── timing/             RandomController (gaussian-centered ms/CPS rolls), MsTimer
│   └── util/               MathUtil (rotation math), ItemUtil (version-tolerant
│                           item/enchant matching by registry path)
├── mixin/                  see below
└── modules
    ├── combat/             AutoMace, BreachSwap, LungeMacro, LegitAura
    ├── crystal/            AutoCrystal, AnchorMacro (+ SafeAnchor subclass)
    ├── survival/           AutoTotem (STANDARD/HOVER), FastXP, PearlCatch, AutoLog
    ├── movement/           NoDelay, NoPush, Freelook
    ├── render/             Esp, CrystalEsp, NameTags, DamageNumbers, TargetHud,
    │                       FancyOutlines, RenderUtil (cached, allocation-free hot path)
    └── hud/                ActiveHud (plain-text active module list)
```

## Mixin surface (deliberately tiny)

| Mixin                    | Purpose                                                            |
|--------------------------|--------------------------------------------------------------------|
| `MixinClientConnection`  | cancellable outbound packet hook                                   |
| `MixinClientPlayerEntity`| silent rotation ride-along, cancellable movement, jump-cooldown reset, ground-landing event |
| `MixinMinecraftClient`   | NoDelay attack-cooldown gate                                       |
| `MixinItemCooldownManager` | NoDelay item-use cooldown gate                                   |
| `MixinEntity`            | NoPush entity push + fluid push (local player only)                |
| `MixinLivingEntity`      | NoPush knockback (local player only)                               |
| `MixinCamera`            | Freelook detached camera                                           |
| `MixinMouse`             | Freelook input interception (deltas never reach the player)        |
| accessors                | `MinecraftClient.attackCooldown`, `LivingEntity.jumpCooldown`      |

## Module notes

- **AutoMace** — STANDARD mode gates on descent velocity, banked fall distance, height
  advantage and a closable ground gap; DENSITY mode arms a 0-tick trigger that fires the
  strike in the same tick as the landing event. Respects target hurt-time and cooldown.
- **BreachSwap** — hotbar-first search for a Breach mace, vanilla swap-click fallback from
  inventory, randomized 20–60 ms swap / 80–220 ms restore pacing.
- **LungeMacro (SpearMace)** — spear engage → swap → mace strike → restore, all stages
  re-validated against target hurt-time and cooldowns; HUD shows the live stage.
- **LegitAura** — CPS-gaussian attack pacing, hitbox raycast validation, rotation
  convergence gate, 4% natural-swing-skip.
- **AutoCrystal** — DOUBLE_TAP / INFINITE / HIT_ONLY, 18–22 CPS gaussian attack pacing,
  obsidian-support placement scoring by damage minus self-damage, loot pause, kill pause.
- **AnchorMacro / SafeAnchor** — PLACE → CHARGE → DETONATE FSM; 3–11 ms and 9–13 ms
  randomized pacing respectively; SafeAnchor places protective cover before detonating
  and refuses unshielded detonations.
- **AutoTotem/HoverTotem** — 64–112 ms randomized restock, cursor-stack priority in HOVER,
  crystal/fire/fall danger gates.
- **FastXP** — 14–17 CPS bottle throwing with ground-focus silent pitch.
- **PearlCatch** — pre-swap while your pearl is airborne, 15–45 ms reaction throw on landing.
- **Render** — everything renders through `RenderLayer.getLines()` and cached text objects;
  entity lists cached per tick, damage numbers pooled, no shaders/framebuffer passes.

## Commands

```
/zephyr list
/zephyr toggle AutoCrystal
/zephyr set AutoCrystal cpsMax 21
/zephyr set AutoCrystal mode INFINITE
/zephyr bind AutoCrystal 34      # GLFW keycode (G = 34)
/zephyr save
```

Keybinds are plain GLFW keycodes stored per module; press the bound key in-game to toggle.
