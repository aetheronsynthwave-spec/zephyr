# Zephyr Client

Open-source Fabric client mod for **Minecraft 1.21.11** (current version: v1.13.5).

Licensed under the [MIT License](LICENSE).

## Download

**Latest: v1.13.5**: [zephyr-client-1.21.11-v1.13.5.jar](release/zephyr-client-1.21.11-v1.13.5.jar) (compiled mod for Minecraft 1.21.11).

Needs Fabric Loader 0.18+ and Fabric API 0.141.x. Put the jar in `.minecraft/mods`.

## Build

Requires Java 21 and Gradle (Fabric Loom 1.14):

```
gradle build
```

The mod jar is written to `build/libs/`. Drop it into `.minecraft/mods` with Fabric Loader 0.18+
and Fabric API 0.141.x for 1.21.11.

## History

Every release is a tagged commit (`v1.4.0` ... `v1.13.5`). See `README.txt` for the per-version changelog
and module reference.
