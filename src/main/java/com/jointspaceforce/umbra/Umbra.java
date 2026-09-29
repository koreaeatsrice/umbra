package com.jointspaceforce.umbra;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Umbra — modern hostile-spawn light rules on 1.7.10, ALL dimensions.
 *
 * Per-dimension rules (matching modern Java Edition, wiki: Mob_spawning +
 * Light#Mobs):
 * (a) OVERWORLD: block light == 0 AND (night OR sky light <= 7) — torch-lit
 * caves/bases are SAFE; the night surface still spawns.
 * (b) NETHER: block light <= 7 (the modern nether rule — NOT 0; the nether is
 * light-permissive by design).
 * (c) END: block light == 0.
 * (d) OTHER dims: configurable (default = overworld rule).
 * Plus, matching modern per-mob spawn rules:
 * (e) LAVA SPAWN RULE: a spawn position inside lava is allowed at any light
 * (modern "strider" analog) — this is what lets modded lava dwellers
 * (SpecialMobs' LavaMonster/LavaWebSpider) spawn, since their own spawner
 * places them INTO lava (light 15).
 * (f) EXEMPT ENTITY LIST (config): mobs that bypass the light rule entirely.
 * Spawner-block spawns (dungeons, blaze cages) are never touched.
 *
 * Why reflection: GTNH 1.7.10's forge event classes reference the OBFUSCATED vanilla
 * classes at runtime, and there is no MCP-named minecraft jar on disk to compile
 * against (the GTNH maven that hosts it is down). Reflection with cached handles on
 * the verified runtime names keeps the mod dependency-free and bytecode-safe. The
 * handles resolve once and are then as fast as direct calls for practical purposes.
 *
 * Verified runtime references (joined.srg + live jar javap):
 * ahb.b(Lahn;III)I = World.getSavedLightValue(EnumSkyBlock,int,int,int)
 * ahn.a = EnumSkyBlock.Sky ; ahn.b = EnumSkyBlock.Block
 * World field field_73011_w = provider ; WorldProvider field_76574_g = dimensionId
 * Block.func_149688_o = getMaterial ; Material.field_151587_i = lava
 * net.minecraft.entity.monster.EntityMob resolves via the RFB deobf alias.
 */
@Mod(modid = Umbra.MODID, name = "Umbra", version = Tags.VERSION, acceptableRemoteVersions = "*")
public class Umbra {

    public static final String MODID = "umbra";
    private static final int MAX_SKY_LIGHT = 7;

    // ---- config (loaded from config/umbra.cfg in preInit) ----
    private static volatile boolean cfgEnabled = true;
    private static volatile boolean cfgDebug = false;
    private static volatile boolean cfgLavaRule = true;
    private static volatile Set<String> cfgExempt = new HashSet<>(
        Arrays.asList("EntityLavaMonster", "EntityLavaWebSpider"));
    private static volatile int cfgOverworldMax = 0;
    private static volatile int cfgNetherMax = 7;
    private static volatile int cfgEndMax = 0;
    private static volatile int cfgOtherMax = 0;
    private static volatile int cfgMaxSky = MAX_SKY_LIGHT;

    // cached reflective handles
    private static volatile Method getSavedLightValue; // (EnumSkyBlock,int,int,int)I
    private static volatile Object enumSkyBlockBlock; // EnumSkyBlock.Block
    private static volatile Object enumSkyBlockSky; // EnumSkyBlock.Sky
    private static volatile Field worldProviderField; // World.provider (field_73011_w)
    private static volatile Field dimensionIdField; // WorldProvider.dimensionId
    private static volatile Class<?> entityMobClass;

    // Diagnostic state — this mod must never be silent again.
    private static final int MAX_INIT_ATTEMPTS = 20; // bounded retries, then stop trying
    private static volatile int initAttempts = 0;
    private static volatile boolean failureReported = false; // init failure logged once
    private static volatile boolean runtimeFailureLogged = false; // runtime invoke failure logged once
    private static volatile long deniedCount = 0; // spawns denied (log proof)
    private static volatile boolean firstDenyLogged = false;
    private static volatile boolean armed = false; // true ONLY when core light checks resolved
    private static volatile int allowSamplesLogged = 0; // ALLOW sampling (first N with values)
    private static volatile Method getWorldTimeMethod; // World.getWorldTime() -> long (day/night clock)
    private static volatile Method getBlockMethod; // World.getBlock(x,y,z) -> Block (spawner exemption)
    private static volatile Method getTileEntityMethod; // World.getTileEntity(x,y,z) -> TileEntity (spawner exemption)
    private static volatile Method blockGetMaterial; // Block.getMaterial() -> Material (lava rule)
    private static volatile Object materialLava; // Material.lava (SRG field_151587_i)

    private static void ensureInit() {
        if (getSavedLightValue != null || initAttempts >= MAX_INIT_ATTEMPTS) return;
        initAttempts++;
        try {
            // The event's world object at runtime is the obfuscated World (ahb).
            // Resolve its class via the RFB deobf alias so we are obfuscation-stable.
            ClassLoader cl = Umbra.class.getClassLoader();
            Class<?> worldClass = Class.forName("net.minecraft.world.World", false, cl);

            // getSavedLightValue(EnumSkyBlock, int, int, int) -> int
            // DO NOT Class.forName the enum type: RFB's alias table does not cover
            // EnumSkyBlock ("Class bytes are null" — proven on the live server).
            // Instead take the enum type straight from the method's own signature:
            // whatever class object the JVM linked is by definition the right one.
            // Try SRG name first, then MCP (dev environment).
            Method m = null;
            Class<?> skyEnum = null;
            for (Method mm : worldClass.getMethods()) {
                if (mm.getParameterTypes().length == 4 && (mm.getName()
                    .equals("func_72972_b")
                    || mm.getName()
                        .equals("getSavedLightValue"))) {
                    Class<?>[] pts = mm.getParameterTypes();
                    if (pts[0].isEnum()) {
                        m = mm;
                        skyEnum = pts[0];
                        break;
                    }
                }
            }
            if (m == null || skyEnum == null) {
                throw new IllegalStateException("getSavedLightValue not found on World (SRG or MCP)");
            }
            m.setAccessible(true);
            getSavedLightValue = m;

            // EnumSkyBlock constants: at RUNTIME the enum constant NAMES are the obf
            // letters (ahn.a = Sky, ahn.b = Block per joined.srg) even when the class
            // is reached via the deobf alias. Resolve by SRG-field map, not Enum.valueOf.
            enumSkyBlockBlock = enumConstByField(skyEnum, "b", "Block");
            enumSkyBlockSky = enumConstByField(skyEnum, "a", "Sky");
            if (enumSkyBlockBlock == null || enumSkyBlockSky == null) {
                throw new IllegalStateException("EnumSkyBlock constants not found");
            }

            // Dimension fields (World.provider -> WorldProvider.dimensionId) are
            // diagnostic-only now — dimOf() in the decision logs. Never throw.
            Class<?> providerClass = null;
            try {
                providerClass = Class.forName("net.minecraft.world.WorldProvider", false, cl);
            } catch (Throwable ignored) {}
            worldProviderField = providerClass == null ? null : findField(worldClass, "field_73011_w", "provider");
            if (worldProviderField != null) worldProviderField.setAccessible(true);
            dimensionIdField = providerClass == null ? null : findField(providerClass, "field_76574_g", "dimensionId");
            if (dimensionIdField != null) dimensionIdField.setAccessible(true);

            // EntityMob may hit the same alias gap — null means the name-walk
            // fallback in isHostile() takes over. Never throw.
            try {
                entityMobClass = Class.forName("net.minecraft.entity.monster.EntityMob", false, cl);
            } catch (Throwable ignored) {
                entityMobClass = null;
            }

            // Day/night clock + spawner exemption: getWorldTime (SRG func_72820_D) and
            // getBlock (func_147439_a) / getTileEntity (func_147438_*) — with
            // MCP-name fallback scans.
            getWorldTimeMethod = resolveWorldTime(worldClass);
            getBlockMethod = resolveByReturnType(worldClass, "net.minecraft.block.Block", "getBlock");
            getTileEntityMethod = resolveByReturnType(
                worldClass,
                "net.minecraft.tileentity.TileEntity",
                "getTileEntity");
            FMLLog.info(
                "Umbra: resolved worldTime=%s block=%s tileEntity=%s",
                getWorldTimeMethod,
                getBlockMethod,
                getTileEntityMethod);
            if (getWorldTimeMethod == null) {
                // Without the day/night clock we cannot tell day surface from night
                // surface — and wrongly denying night surface is THE reported bug.
                FMLLog.warning("Umbra: day/night clock UNRESOLVABLE — rule DISARMED (vanilla behavior)");
                return;
            }
            if (getBlockMethod == null && getTileEntityMethod == null) {
                FMLLog.warning("Umbra: spawner-exemption UNRESOLVABLE — spawner blocks may be affected (non-fatal)");
            }

            // Lava rule handles: Block.getMaterial() + Material.lava. Resolved off
            // getBlock's RETURN TYPE (same alias-gap trick as the sky enum) so we
            // never Class.forName a class RFB may not cover.
            if (getBlockMethod != null) {
                Class<?> blockClass = getBlockMethod.getReturnType();
                try {
                    blockGetMaterial = blockClass.getMethod("func_149688_o");
                } catch (NoSuchMethodException ignored) {
                    try {
                        blockGetMaterial = blockClass.getMethod("getMaterial");
                    } catch (NoSuchMethodException ignored2) {}
                }
                if (blockGetMaterial != null) {
                    Class<?> materialClass = blockGetMaterial.getReturnType();
                    try {
                        Field f = materialClass.getField("field_151587_i");
                        materialLava = f.get(null);
                    } catch (Throwable ignored) {
                        try {
                            materialLava = materialClass.getField("lava")
                                .get(null);
                        } catch (Throwable ignored2) {}
                    }
                }
            }
            FMLLog.info("Umbra: lava-rule handles: getMaterial=%s materialLava=%s", blockGetMaterial, materialLava);

            // Dimension fields are diagnostic-only now (dimOf in logs). The rule
            // applies to ALL dimensions, so a missing dimension gate must NOT
            // disarm the mod — the light checks above are what matter.
            armed = true;
            FMLLog.info(
                "Umbra: ARMED — modern per-dimension rules live (overworld block=0+sky<=%d; nether block<=%d; end block=0; lava rule=%s; exempt=%s). method=%s, skyEnum=%s, blockConst=%s, skyConst=%s, providerField=%s, dimIdField=%s, hostileCheck=%s, worldTime=%s, block=%s, tileEntity=%s",
                cfgMaxSky,
                cfgNetherMax,
                cfgLavaRule,
                cfgExempt,
                m.getName(),
                skyEnum.getName(),
                constName(enumSkyBlockBlock),
                constName(enumSkyBlockSky),
                worldProviderField != null ? "OK" : "missing",
                dimensionIdField != null ? "OK" : "missing",
                entityMobClass != null ? "class" : "namewalk",
                getWorldTimeMethod,
                getBlockMethod,
                getTileEntityMethod);
        } catch (Throwable t) {
            // Never crash the server: vanilla fallback. But ALWAYS say why — silence
            // is exactly what made this mod undiagnosable for two weeks.
            if (!failureReported) {
                failureReported = true;
                FMLLog.warning(
                    "Umbra: init FAILED (%s: %s) — vanilla fallback active; retrying up to %d times",
                    t.getClass()
                        .getName(),
                    t.getMessage() == null ? "no message" : t.getMessage(),
                    MAX_INIT_ATTEMPTS);
                for (StackTraceElement se : t.getStackTrace()) {
                    FMLLog.warning("Umbra:   at %s", se.toString());
                }
            }
        }
    }

    private static String constName(Object o) {
        return o instanceof Enum ? ((Enum<?>) o).name() : String.valueOf(o);
    }

    /** Resolve an enum constant by its runtime (obfuscated) field name, with MCP fallback. */
    private static Object enumConstByField(Class<?> enumClass, String obfName, String mcpName) {
        try {
            Field f = enumClass.getDeclaredField(obfName);
            if (f.isEnumConstant()) return f.get(null);
        } catch (Throwable ignored) {}
        try {
            Field f = enumClass.getDeclaredField(mcpName);
            if (f.isEnumConstant()) return f.get(null);
        } catch (Throwable ignored) {}
        // last resort: Enum.valueOf on the MCP name
        try {
            return Enum.valueOf((Class<? extends Enum>) enumClass, mcpName);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Field findField(Class<?> owner, String mcpName, String srgName) {
        for (String n : new String[] { mcpName, srgName }) {
            try {
                Field f = owner.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        // walk superclasses
        Class<?> sup = owner.getSuperclass();
        while (sup != null && sup != Object.class) {
            for (String n : new String[] { mcpName, srgName }) {
                try {
                    Field f = sup.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
            sup = sup.getSuperclass();
        }
        return null;
    }

    private static boolean isHostile(Object entity) {
        if (entityMobClass != null) return entityMobClass.isInstance(entity);
        // Fallback when the class alias is missing: walk the REAL superclass chain.
        // Vanilla and GTNH-modded hostiles all carry the MCP name in their chain
        // (EntityMob) at runtime — proven by ServerUtilities-style bytecode.
        Class<?> c = entity.getClass();
        while (c != null && c != Object.class) {
            if (c.getName()
                .equals("net.minecraft.entity.monster.EntityMob")) return true;
            c = c.getSuperclass();
        }
        return false;
    }

    private static int savedLight(Object world, Object enumSky, int x, int y, int z) {
        try {
            return (Integer) getSavedLightValue.invoke(world, enumSky, x, y, z);
        } catch (Throwable t) {
            if (!runtimeFailureLogged) {
                runtimeFailureLogged = true;
                FMLLog.warning(
                    "Umbra: savedLight invoke FAILED at runtime (%s) — not denying (vanilla behavior)",
                    t.toString());
            }
            return -1; // -1: cannot determine -> do not deny (vanilla behavior)
        }
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        loadConfig(event.getSuggestedConfigurationFile());
        MinecraftForge.EVENT_BUS.register(this);
        ensureInit();
    }

    /** config/umbra.cfg — loaded once at preInit; defaults = modern rules. */
    private static void loadConfig(File file) {
        try {
            Configuration cfg = new Configuration(file);
            cfg.load();
            cfgEnabled = cfg.getBoolean("enabled", "general", true, "Master switch for the light rules.");
            cfgDebug = cfg.getBoolean("debugLogging", "general", false, "Log more allow samples (60 instead of 30).");
            cfgLavaRule = cfg.getBoolean(
                "allowLavaSpawns",
                "general",
                true,
                "Allow spawns whose position is inside lava regardless of light (modern strider-style rule; lets SpecialMobs lava monsters spawn).");
            String[] exempt = cfg.getStringList(
                "exemptEntities",
                "general",
                new String[] { "EntityLavaMonster", "EntityLavaWebSpider" },
                "Entity class SIMPLE names that bypass the light rule entirely.");
            cfgExempt = new HashSet<>(Arrays.asList(exempt));
            cfgOverworldMax = cfg
                .getInt("overworldMaxBlockLight", "light", 0, 0, 15, "Modern overworld: block light cap (0).");
            cfgNetherMax = cfg.getInt("netherMaxBlockLight", "light", 7, 0, 15, "Modern nether: block light cap (7).");
            cfgEndMax = cfg.getInt("endMaxBlockLight", "light", 0, 0, 15, "Modern end: block light cap (0).");
            cfgOtherMax = cfg
                .getInt("otherDimsMaxBlockLight", "light", 0, 0, 15, "Modded dimensions: block light cap (0).");
            cfgMaxSky = cfg.getInt("maxSkyLight", "light", MAX_SKY_LIGHT, 0, 15, "Day-time sky light cap (7).");
            if (cfg.hasChanged()) cfg.save();
            FMLLog.info(
                "Umbra: config loaded — enabled=%s lavaRule=%s exempt=%s caps[ow=%d nether=%d end=%d other=%d sky=%d]",
                cfgEnabled,
                cfgLavaRule,
                cfgExempt,
                cfgOverworldMax,
                cfgNetherMax,
                cfgEndMax,
                cfgOtherMax,
                cfgMaxSky);
        } catch (Throwable t) {
            FMLLog.warning("Umbra: config load FAILED (%s) — defaults in effect", t.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onCheckSpawn(LivingSpawnEvent.CheckSpawn event) {
        ensureInit();
        if (!armed || !cfgEnabled) return; // not safely armed / disabled -> vanilla
        Object world = event.world;
        if (world == null) return; // no world -> vanilla
        if (!isHostile(event.entityLiving)) return; // monsters only

        int x = (int) Math.floor(event.x);
        int y = (int) Math.floor(event.y);
        int z = (int) Math.floor(event.z);

        // NEVER block spawns coming from a spawner block (dungeons, blaze cages...).
        if (spawnerBlockAt(world, x, y, z)) return;

        String who = entityName(event);
        int dim = dimOf(world);

        // (f) per-entity exemptions (config) — mobs with their own spawn rules.
        if (cfgExempt.contains(who)) {
            allowSample("exempt", who, dim, x, y, z, -1, -1, false);
            return;
        }

        // (e) modern "lava spawn" rule: a position INSIDE lava is allowed at any
        // light (strider analog). This is what lets modded lava dwellers spawn —
        // their spawner places them into lava, which is light 15.
        if (cfgLavaRule && lavaAt(world, x, y, z)) {
            allowSample("lava", who, dim, x, y, z, -1, -1, false);
            return;
        }

        int block = savedLight(world, enumSkyBlockBlock, x, y, z);
        int sky = savedLight(world, enumSkyBlockSky, x, y, z);
        boolean day = worldDaytime(world);
        if (block < 0) return; // light unreadable -> vanilla behavior

        // Per-dimension rules (modern Java Edition):
        // (a) overworld: block == 0 AND (night OR sky <= 7)
        // (b) nether: block <= 7 (no sky light exists there)
        // (c) end: block == 0
        // (d) other dims: configurable (default overworld rule)
        int maxBlock = maxBlockFor(dim);
        boolean useSkyGate = dim != -1; // the nether has no sky light
        if (block > maxBlock || (useSkyGate && day && sky > cfgMaxSky)) {
            deny(event, who, dim, x, y, z, block, sky, day);
            return;
        }

        allowSample("dark", who, dim, x, y, z, block, sky, day);
    }

    /** Modern per-dimension block-light caps (config-driven). */
    private static int maxBlockFor(int dim) {
        if (dim == 0) return cfgOverworldMax;
        if (dim == -1) return cfgNetherMax;
        if (dim == 1) return cfgEndMax;
        return cfgOtherMax;
    }

    /** True when the spawn position (or the block above) is lava. */
    private static boolean lavaAt(Object world, int x, int y, int z) {
        if (blockGetMaterial == null || materialLava == null || getBlockMethod == null) return false;
        try {
            for (int yy = y; yy <= y + 1; yy++) {
                Object block = getBlockMethod.invoke(world, x, yy, z);
                if (block == null) continue;
                Object mat = blockGetMaterial.invoke(block);
                if (mat != null && mat == materialLava) return true;
            }
        } catch (Throwable t) {
            return false; // never crash; worst case the light rule applies
        }
        return false;
    }

    /** ALLOW sampling so the log SHOWS allowed spawns happening (proof of life). */
    private static void allowSample(String why, String who, int dim, int x, int y, int z, int block, int sky,
        boolean day) {
        int cap = cfgDebug ? 60 : 30;
        if (allowSamplesLogged >= cap) return;
        allowSamplesLogged++;
        FMLLog.info(
            "Umbra: ALLOW (%s): entity=%s dim=%d pos=(%d,%d,%d) block=%d sky=%d day=%s",
            why,
            who,
            dim,
            x,
            y,
            z,
            block,
            sky,
            day);
    }

    /** DENY + keep diagnostics counters so the log can PROVE the rule is live and why. */
    private static void deny(LivingSpawnEvent.CheckSpawn event, String entity, int dim, int x, int y, int z, int block,
        int sky, boolean day) {
        event.setResult(Event.Result.DENY);
        deniedCount++;
        if (deniedCount <= 10 || deniedCount % 1000 == 0) {
            FMLLog.info(
                "Umbra: DENY #%d: entity=%s dim=%d pos=(%d,%d,%d) block=%d sky=%d day=%s",
                deniedCount,
                entity,
                dim,
                x,
                y,
                z,
                block,
                sky,
                day);
        }
    }

    private static String entityName(LivingSpawnEvent.CheckSpawn event) {
        try {
            String n = event.entityLiving.getClass()
                .getName();
            return n.substring(n.lastIndexOf('.') + 1);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static int dimOf(Object world) {
        try {
            if (worldProviderField == null || dimensionIdField == null) return -999;
            Object provider = worldProviderField.get(world);
            if (provider == null) return -999;
            return ((Number) dimensionIdField.get(provider)).intValue();
        } catch (Throwable t) {
            return -999;
        }
    }

    /** World.getWorldTime() — day/night clock. SRG func_72820_D, MCP fallback scan. */
    private static Method resolveWorldTime(Class<?> worldClass) {
        try {
            Method m = worldClass.getMethod("func_72820_D");
            if (m.getReturnType() == long.class) return m;
        } catch (NoSuchMethodException ignored) {}
        for (Method mm : worldClass.getMethods()) {
            if (mm.getParameterTypes().length == 0 && mm.getReturnType() == long.class) {
                return mm; // getWorldTime / getTotalWorldTime — first long-0-arg
            }
        }
        return null;
    }

    /**
     * World.getBlock(x,y,z) or getTileEntity(x,y,z) — spawner exemption.
     * Resolved by RETURN TYPE NAME: runtime classes keep MCP names (proven:
     * skyEnum=net.minecraft.world.EnumSkyBlock), so a 3-int method returning
     * net.minecraft.block.Block / net.minecraft.tileentity.TileEntity is the
     * one we want (SRG func_147439_a / func_147438_*).
     */
    private static Method resolveByReturnType(Class<?> worldClass, String returnTypeName, String mcpName) {
        // exact SRG-name attempts first (func_147439_a for getBlock)
        for (String srg : new String[] { "func_147439_a", "func_147438_o" }) {
            try {
                Method m = worldClass.getMethod(srg, int.class, int.class, int.class);
                if (m.getReturnType()
                    .getName()
                    .equals(returnTypeName)) return m;
            } catch (NoSuchMethodException ignored) {}
        }
        for (Method mm : worldClass.getMethods()) {
            if (mm.getParameterTypes().length == 3 && mm.getReturnType()
                .getName()
                .equals(returnTypeName)) {
                return mm;
            }
        }
        // last resort: MCP name (dev environment)
        try {
            Method m = worldClass.getMethod(mcpName, int.class, int.class, int.class);
            if (m.getReturnType()
                .getName()
                .equals(returnTypeName)) return m;
        } catch (NoSuchMethodException ignored) {}
        return null;
    }

    /** True = in-game clock says day (or the clock is unreadable -> treat as day, fail-safe). */
    private static boolean worldDaytime(Object world) {
        try {
            return ((Number) getWorldTimeMethod.invoke(world)).longValue() % 24000L < 13000L;
        } catch (Throwable t) {
            if (!runtimeFailureLogged) {
                runtimeFailureLogged = true;
                FMLLog.warning("Umbra: worldTime invoke FAILED (%s) — treating as DAY (fail-safe)", t.toString());
            }
            return true;
        }
    }

    /** A class is the mob spawner block or its tile entity (class-name matched). */
    private static boolean isSpawnerClass(Class<?> c) {
        while (c != null && c != Object.class) {
            String n = c.getName();
            if (n.equals("net.minecraft.block.BlockMobSpawner")
                || n.equals("net.minecraft.tileentity.TileEntityMobSpawner")
                || n.endsWith(".BlockMobSpawner")
                || n.endsWith(".TileEntityMobSpawner")) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /** Skip spawns ORIGINATING from a mob-spawner block (dungeons, blaze cages...). */
    private static boolean spawnerBlockAt(Object world, int x, int y, int z) {
        try {
            if (getBlockMethod != null) {
                for (int yy = y - 1; yy <= y + 1; yy++) {
                    Object block = getBlockMethod.invoke(world, x, yy, z);
                    if (block != null && isSpawnerClass(block.getClass())) return true;
                }
            }
            if (getTileEntityMethod != null) {
                for (int yy = y - 1; yy <= y + 1; yy++) {
                    Object te = getTileEntityMethod.invoke(world, x, yy, z);
                    if (te != null && isSpawnerClass(te.getClass())) return true;
                }
            }
        } catch (Throwable t) {
            return false; // never crash; worst case a spawner spawn gets blocked once
        }
        return false;
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        // Second chance: the RFB/deobf pipeline is definitely live once the server
        // is up. If init failed at preInit for a timing reason, this arms it.
        ensureInit();
    }
}
