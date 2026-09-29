# Security Policy

## Supported Versions

Security updates are provided for the latest minor release line:

| Version | Status |
| ------- | ------ |
| 1.3.x   | Maintained |
| < 1.3.0 | Unsupported |

---

## Reporting a Vulnerability

If you identify a security vulnerability in `umbra` (for example anything that
could abuse the reflection handles it uses against Minecraft/Forge internals,
the config parsing, or a crafted input that could crash or un-protect a
server), please report it responsibly.

### How to Report
- Open a private advisory report through GitHub Security Advisories at
  https://github.com/koreaeatsrice/umbra/security/advisories/new
- Or email `koreaeatsrice@gmail.com` with the subject line
  `[SECURITY] umbra Vulnerability Report`.

### What to Include
1. Clear description of the vulnerability and potential impact.
2. Steps to reproduce or proof-of-concept (a small test pack is ideal).
3. Mod version, GTNH pack version, and the relevant `logs/fml-server-latest.log` excerpt.
4. Any suggested remediations or mitigations.

### Response Expectations
Reports are handled on a best-effort basis as time permits. There are no formal
response timelines or resolution SLAs. Confirmed security fixes will be tagged
and released via standard repository releases.

---

## Security Model & Threat Boundaries

Umbra is a **server-side spawn-rule addon** that changes light-based spawning
to match modern Minecraft. Its security-relevant boundaries are:

1. **Fixed reflection handles:** the mod resolves a small set of Minecraft
   internals (light lookups, dimension ids, the day/night clock) once at
   start-up and caches them. It loads no user-supplied class names.
2. **No network, no code loading:** the mod opens no sockets, downloads
   nothing, and executes no code from data. Client machines need nothing
   installed (`acceptableRemoteVersions = "*"`).
3. **Fail-safe by design:** if any handle fails to resolve, the mod logs a
   `DISARMED` / `init FAILED` line and falls back to vanilla behaviour instead
   of crashing or half-applying rules.
4. **Server operator's control:** the whole rule set is config-gated
   (`config/umbra.cfg`), including per-dimension caps and the exempt-entity list.