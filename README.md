# Umbra

**Modern Minecraft hostile-spawn rules for GTNH 1.7.10 — server-side only.**

Umbra makes hostile mobs spawn the way they do in modern Minecraft (1.18+)
instead of vanilla 1.7.10 — mobs belong to the shadows:

- **Overworld hostiles spawn only in total darkness (block light 0)** —
  torch-lit caves, bases and corridors are safe, like modern Minecraft.
- **During the day, sky-exposed spots are dead too**; at night the surface
  spawns normally (modern behavior).
- **Mob spawner blocks are exempt** — dungeons, blaze cages etc. keep working.
- **Nether and other dimensions are untouched.**

Clients need nothing installed (`acceptableRemoteVersions = "*"`).

## How it works

The mod hooks `LivingSpawnEvent.CheckSpawn` (highest priority) and, for
Overworld hostiles only, denies spawns where:
1. block light > 0 (always), or
2. it is daytime and the spot can see the sky.

Day/night comes from the **world clock** (`World.getWorldTime()`), not the
stored sky-light array — in 1.7.10 the raw sky array stays at 15 even at night
(the night darkness lives in `skylightSubtracted`), which is what makes naive
implementations kill night-surface spawns.

All runtime lookups are reflective and resolved once at boot against the real
RFB runtime (SRG-first), with clear diagnostics:

- `Umbra: ARMED — ...` — every component resolved, rule active.
- `Umbra: init FAILED (...)` — vanilla fallback, with the exact reason.
- `DENY #N: entity=... dim=... pos=... block=... sky=... day=...` — sampled
  decision log (first 10 denies + every 1000th, first 30 allows).

The mod is **fail-safe**: it only arms when every component resolved; a partial
state falls back to vanilla behavior and never crashes the server.

## Building

Requires JDK 25 (GTNH Gradle toolchain) and network access to the GTNH maven:

```bash
JAVA_HOME=/opt/jdk-25 ./gradlew clean build --no-daemon
# output: build/libs/umbra-<version>.jar
```

## Deploying (server)

1. Stop the server.
2. Drop the jar into `mods/`.
3. Start; verify `Umbra: ARMED` in `logs/fml-server-latest.log`.

## Layout

```
src/main/java/com/jointspaceforce/umbra/Umbra.java   the mod
src/main/resources/mcmod.info                        metadata
gradle.properties                                    modId=umbra
```

## Credits

Joint Space Force.
