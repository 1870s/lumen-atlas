# Building and testing

## Requirements

* JDK 25 (Minecraft 26.x runs on Java 25). Gradle downloads a matching JDK automatically through the foojay
  toolchain resolver if none is installed.
* Nothing else: the Gradle wrapper is included.

## Commands

```bash
./gradlew build                          # unit tests + Fabric and NeoForge jars -> build/libs/
./gradlew :core-tests:test               # unit tests only (no Minecraft needed)
./gradlew :fabric-26.3:runClient         # start a Fabric development client
./gradlew :neoforge-26.3:runClient       # start a NeoForge development client
./gradlew :fabric-26.3:runSelfTest       # automated in-game test, see below
./gradlew :neoforge-26.3:runSelfTest
```

On Windows use `gradlew.bat`. When the project sits in a very deep folder, build output and test game folders are
placed in `~/.gradle/lumenatlas/` because Windows cannot start programs from paths longer than 260 characters.

## The in-game self test

`runSelfTest` starts a real client with `-Dlumenatlas.selftest=true` and drives it without any input:

1. creates a normal world with seed `20261031` (override with `-Dlumenatlas.selftest.seed=<seed>`),
2. creates a home waypoint, dies once and checks that a death waypoint appeared,
3. waits for the seed map, then for every structure kind takes the nearest prediction near spawn and
   * compares it with vanilla's structure search (`ServerLevel#findNearestMapStructure`, the code behind `/locate`),
   * flies there in spectator mode and checks that the detector marks the same structure,
4. compares the offline world generation used for manually entered seeds with the integrated server's,
5. visits a predicted Nether fortress,
6. takes the screenshots used in the README and quits.

Start-up notices that wait for a click (accessibility onboarding and similar) are clicked through automatically.
Every check is logged as `[SelfTest] PASS ...` or `[SelfTest] FAIL ...`, followed by a summary. Screenshots are in the
run folder's `screenshots/` directory.

## Code style

* All user-visible text is English and lives in `assets/lumenatlas/lang/en_us.json`.
* Loader-specific code stays in `fabric/` and `neoforge/`; version-specific calls go to `common/src/compat/`.
* Minecraft-independent logic goes to `common/src/core` and gets a unit test in `core-tests/`.
* Files are always written through `AtomicFiles`.
