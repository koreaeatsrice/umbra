# Umbra

**Modern Minecraft hostile-spawn rules for GTNH 1.7.10 — server-side only.**

Umbra makes hostile mobs spawn the way they do in modern Minecraft instead of
vanilla 1.7.10 — with the **per-dimension rules** modern MC actually uses:

- **Overworld:** block light 0 AND (night OR sky light ≤ 7) — torch-lit caves,
  bases and corridors are safe; the night surface still spawns.
- **Nether:** block light ≤ 7 (the modern nether rule — the Nether is
  light-permissive by design, unlike the Overworld).
- **End:** block light 0.
- **Other (modded) dimensions:** configurable (defaults to the Overworld rule).
- **Lava spawn rule:** a spawn position inside lava is allowed at ANY light
  (modern "strider" analog) — this is what lets lava dwellers such as
  SpecialMobs' LavaMonster/LavaWebSpider spawn, since their own spawner places
  them INTO lava (light 15).
- **Exempt entities (config):** class names that bypass the light rule entirely
  (defaults: `EntityLavaMonster`, `EntityLavaWebSpider`).
- **Mob spawner blocks are exempt** — dungeons, blaze cages etc. keep working.

Clients need nothing installed (`acceptableRemoteVersions = "*"`).

## How it works

The mod hooks `LivingSpawnEvent.CheckSpawn` (highest priority) and applies the
per-dimension rule above to hostile mobs. Day/night comes from the **world
clock** (`World.getWorldTime()`), not the stored sky-light array — in 1.7.10 the
raw sky array stays at 15 even at night (the night darkness lives in
`skylightSubtracted`), which is what makes naive implementations kill
night-surface spawns.

## Config (`config/umbra.cfg`)

```
general {
    B:enabled=true                 # master switch
    B:debugLogging=false           # 60 allow samples instead of 30
    B:allowLavaSpawns=true         # lava-position rule (strider analog)
    S:exemptEntities=EntityLavaMonster,EntityLavaWebSpider
}
light {
    I:overworldMaxBlockLight=0     # modern overworld cap
    I:netherMaxBlockLight=7        # modern nether cap
    I:endMaxBlockLight=0
    I:otherDimsMaxBlockLight=0
    I:maxSkyLight=7                # daytime sky gate
}
```

All runtime lookups are reflective and resolved once at boot against the real
RFB runtime (SRG-first), with clear diagnostics:

- `Umbra: ARMED — modern per-dimension rules live (...)` — rule active.
- `Umbra: init FAILED (...)` — vanilla fallback, with the exact reason.
- `ALLOW (dark|lava|exempt): ...` / `DENY #N: entity=...` — sampled decision log.

The mod is **fail-safe**: it only arms when the core light checks resolved; a
partial state falls back to vanilla behavior and never crashes the server.

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
