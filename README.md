# Zephyr Client

Open-source Fabric client mod for **Minecraft 1.21.11** (current version: v1.15.0).

Licensed under the [MIT License](LICENSE).

## Download

**Latest: v1.15.0**: [zephyr-client-1.21.11-v1.15.0.jar](release/zephyr-client-1.21.11-v1.15.0.jar) (compiled mod for Minecraft 1.21.11).

Needs Fabric Loader 0.18+ and Fabric API 0.141.x. Put the jar in `.minecraft/mods`.

## Build

Requires Java 21 and Gradle (Fabric Loom 1.14):

```
gradle build
```

The mod jar is written to `build/libs/`. Drop it into `.minecraft/mods` with Fabric Loader 0.18+
and Fabric API 0.141.x for 1.21.11.

## History

Source for each release is a tagged commit (`v1.4.0` ... `v1.13.5`, `v1.15.0`). `v1.0.0`, `v1.2.0` and
`v1.3.0` are tagged on the `legacy` branch. All compiled jars are listed in [release/](release/README.md).
See `README.txt` for the changelog and module reference.