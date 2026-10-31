# Architecture and technical decisions

This document explains how Lumen Atlas is built and why. It is meant for contributors and for anyone who wants to
port the mod to a newer Minecraft version.

## Project layout

```
common/src/core/java      Minecraft-independent logic (map region codec, shading, marker clustering,
                          waypoint storage, config, seed parsing). Unit tested by core-tests/.
common/src/main/java      Everything else that is shared by both loaders (uses Minecraft classes only).
common/src/compat/<ver>   The few calls that differ between Minecraft versions (one folder per version family).
common/src/dev/java       The in-game self test. Compiled for development runs, excluded from release jars.
fabric/                   Fabric entry point (+ fabric.mod.json).
neoforge/                 NeoForge entry point (+ neoforge.mods.toml template).
versions/<mc>/            One Gradle project per loader and Minecraft version; target.properties holds the
                          Minecraft, Fabric API and NeoForge versions. Build logic is shared in gradle/*.gradle.
core-tests/               Plain Java project that runs the unit tests of common/src/core.
tools/make_icons.py       Generates the icon atlas and the logo (all icons are original pixel art).
```

There is no Architectury dependency. Minecraft 26.x is unobfuscated on every loader (official names, no remapping),
so the shared code is simply compiled into each loader project as an extra source folder. Each loader module only
contains its entry point: it registers the key mappings and the HUD layer and forwards the client tick. All
behavior lives in `common`.

The mod is **client-only**: `"environment": "client"` (Fabric) and `displayTest="IGNORE_ALL_VERSION"` /
`dist = Dist.CLIENT` (NeoForge). It never sends packets and needs nothing on the server, so it works on vanilla
servers, modded servers and Realms.

## Threads

| Thread | Work |
|---|---|
| Client (render) thread | Chunk scanning, structure detection, HUD and screens, texture uploads. Every per-tick job has a time budget. |
| `Lumen Atlas IO` | All file reads and writes, in submission order (a later save of a file always wins). |
| `Lumen Atlas Seed Map` | Seed-map predictions. Low priority, newest requests first. |

Minecraft world data (`ClientLevel`, `LevelChunk`) is only read on the client thread. The IO thread only receives
copies (`MapRegion.copy()`, JSON strings), so no locking of game objects is needed.

## Map data

* The explored map is stored per world and dimension in regions of 256 x 256 blocks
  (`lumenatlas/worlds/<world>/dim/<dimension>/r.<x>.<z>.lamr`). Per column: surface color with water depth,
  surface height and a biome index (region-local palette). Files are deflate-compressed, typically 20-120 KB.
* Shading is **not** stored. Textures are shaded when they are built, so the view mode (shaded, flat,
  topographic, biomes) can change instantly and old maps benefit from shading improvements.
* `ChunkScanner` reads every chunk the client receives once (and the 3 x 3 chunks around the player every second),
  nearest first, within a configurable time budget per tick (default 2.5 ms).
* The surface color is the block's vanilla map color, tinted with the real biome grass/foliage/water color for
  tinted blocks. Plants, glass and other see-through decoration are skipped. In dimensions with a ceiling (the
  Nether) the scanner looks below the roof for the first floor.
* `MapStore` keeps up to 96 regions in memory (LRU, ~ 35 MB worst case). Regions are loaded asynchronously; while a
  region is loading, scan results for it are postponed so they can never overwrite data that is still on its way
  from disk.

## Rendering

* **Minimap**: the map image is composed on the CPU into a `DynamicTexture` whose size equals the minimap's size in
  real screen pixels. Rotation and the round mask are applied per pixel, so the map stays crisp at every GUI scale
  and the round shape needs no stencil buffer or shader. Composition is throttled (60 fps by default, configurable). Markers, entities and text are normal GUI draw calls on top.
* **Cave view**: underground (no sky above the player's head) and in the Nether, the minimap shows the first floor
  below the player's head height. These columns are computed per chunk on demand and never stored.
* **World map**: one texture per region and detail level (1, 4 or 16 blocks per pixel), drawn with the GUI pose
  matrix for smooth, sub-pixel zooming. At most 4 ms per frame are spent building textures, nearest regions first;
  the least recently drawn textures are freed when the cache is full (160 per level by default).
* **Waypoints in the world** are projected to the screen with the camera rotation and field of view and drawn as
  part of the HUD. The mod has **no world-rendering hooks and no mixins at all**, which avoids conflicts with
  Sodium, Iris and other rendering mods.

## Structure detection

The client does not know which structures exist; servers do not send that information. Detection therefore uses
what the client does receive:

1. **Block signatures** in loaded chunks. Every chunk is checked once; `LevelChunkSection.maybeHas` checks the
   section palette first, so only sections that contain at least one interesting block are scanned block by block.
2. **Entities** around the player (checked once per second): elder guardians, blazes, piglin brutes, shulkers in the
   End, evokers/vindicators in dark forests, chest minecarts deep underground, and groups of three or more villagers.

Detections of the same structure kind within the kind's merge radius are combined into one marker whose position
is the weighted average of all detections (`MarkerSet`), so a village ends up as one icon near its center.
The full list of signatures and their reliability is in [STRUCTURES.md](STRUCTURES.md).

## Seed map

Structure placement in Minecraft is deterministic: for each structure set the world is divided into a grid of
`spacing x spacing` chunks, and the seed decides one candidate chunk per grid cell (strongholds use rings instead).
Whether a structure really generates there depends on the biome and, for some structures, the terrain. That needs
the full world generator (noise, biome source, structure templates).

Lumen Atlas does **not** re-implement any of this. It calls Minecraft's own code:

* `RandomSpreadStructurePlacement.getPotentialStructureChunk` / `isStructureChunk` (grid, frequency, exclusion zones),
* `ChunkGeneratorStructureState.getRingPositionsFor` (strongholds),
* the weighted choice between a set's structures (same random sequence as `ChunkGenerator#createStructures`),
* `Structure.findValidGenerationPoint` (each structure's own biome and terrain checks).

Where the generator comes from:

* **Singleplayer**: the integrated server's real chunk generator, random state and structure state, so the
  predictions honor data packs and world presets exactly.
* **Servers / Realms**: the seed must be entered by the player. The vanilla data pack is loaded with
  `WorldLoader` (the same path as the "Create World" screen), the chosen world preset (Default, Large Biomes,
  Amplified) provides the chunk generators, and a `StructureTemplateManager` is built over the vanilla resources.
  The self test verifies that this offline path predicts exactly the same structures as the integrated server.

Because the game's own code is used, predictions automatically follow the world generation of whatever Minecraft
version the mod is built for. Limitations are listed in the README.

## Saving and data safety

* All files are written by `AtomicFiles`: write to `<name>.tmp`, keep the previous version as `<name>.bak`, then
  atomically move the new file in place. Reads fall back to the backup if the main file is damaged.
* If both copies of `waypoints.json` or `markers.json` are unreadable, the broken file is copied to
  `<name>.broken-<time>` before anything new is written, so a bug can never silently destroy user data.
* Saves happen every 30 seconds, when screens close and when the world is left.

## Porting to a new Minecraft version

1. Add `versions/<mc>/target.properties` (and the two small build.gradle files) and list the version in
   `lumen_versions` in `gradle.properties`.
2. Build. Fix the calls that changed; if they differ between versions, move them into `common/src/compat/<family>`
   and point `compat=` in `target.properties` at the right folder.
3. Run `./gradlew :fabric-<mc>:runSelfTest` and `:neoforge-<mc>:runSelfTest` and check the `[SelfTest]` summary.
