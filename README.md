<p align="center">
  <img src="src/main/resources/assets/redemptioncodefabric/icon.png" alt="RedemptionCodeFabric" width="128" />
</p>

<h1 align="center">RedemptionCodeFabric</h1>

<p align="center">
  A server-side Minecraft Fabric mod that adds a powerful redemption code system to your server.<br/>
  <a href="README_zh.md">中文文档</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.20.1-brightgreen" alt="Minecraft 1.20.1" />
  <img src="https://img.shields.io/badge/Fabric-0.18.4+-blue" alt="Fabric Loader" />
  <img src="https://img.shields.io/badge/Java-17%2B-orange" alt="Java 17+" />
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="License" />
</p>

---

## Overview

RedemptionCodeFabric is a redemption code mod for Minecraft Fabric servers (1.20.1). It allows server operators to generate and distribute codes that players can redeem for items, experience, or permission levels. The mod supports seven different code types to cover a wide range of use cases — from one-time giveaways to recurring daily rewards.

Only the server needs to install the mod; clients can connect without it. However, when clients also have the mod installed, messages are displayed using Minecraft's built-in translation system for a better experience.

## Features

**Seven Code Types** — ONCE, PERMANENT, PERSONAL, GLOBAL_UNLIMITED, GLOBAL_LIMIT, TIMED, and CYCLE, covering everything from single-use codes to recurring rewards.

**Flexible Rewards** — Give items (with NBT data), experience points/levels, or temporary permission levels. You can even capture the item in your hand as a reward.

**Three Storage Backends** — Persist data to local JSON files, MySQL databases, or Redis. Automatic fallback to file storage if the configured backend is unreachable.

**Built-in Web Management Panel** — A browser-based admin UI with login authentication. Create, view, and delete codes; browse operation logs; check server status; and view configuration — all from your browser.

**Production-grade Connection Pool** — A custom SQL connection pool modeled after HikariCP, with elastic pool sizing, connection lifecycle management, idle timeout eviction, leak detection, keepalive heartbeats, and graceful shutdown.

**Async I/O Architecture** — All blocking I/O (SQL, Redis, file, web) runs on a dedicated thread pool, ensuring the Minecraft server thread is never blocked.

**Hot Configuration Reload** — Change settings and reload them at runtime with `/rcode reload` or via the web panel. The Web server, SQL pool, and Redis pool are all re-initialized dynamically.

**i18n Support** — Server-side messages adapt to the system locale (Chinese and English). Client-side messages use Minecraft's translation system when the mod is installed.

**Detailed Operation Logging** — Every generate, redeem, and delete action is logged with timestamps, executor info, and details for auditing.

## Code Types

| Type | Description |
|------|-------------|
| `ONCE` | Can be redeemed exactly once by any single player. |
| `PERMANENT` | Unlimited total uses; each player can redeem it any number of times. |
| `PERSONAL` | Restricted to a specific player (by UUID, name, or `@selector`); can be redeemed once. |
| `GLOBAL_UNLIMITED` | Every player can redeem it once; no global cap. |
| `GLOBAL_LIMIT` | Shared global usage limit. Optionally restricted to players matching a Tag or Scoreboard ranking (top N or bottom N). |
| `TIMED` | Only active within a specified time window; each player can redeem once. |
| `CYCLE` | Repeating time windows (e.g., daily). Each player can redeem once per cycle. |

## Reward Types

| Format | Description | Example |
|--------|-------------|---------|
| `item@<id>{nbt}` | Gives an item, optionally with NBT data. | `item@minecraft:diamond_sword{Enchantments:[{id:"sharpness",lvl:5}]}` |
| `[hand]` | Captures the item currently held in your main hand (at generation time). | `[hand]` |
| `exp@<amount>P` | Gives experience **points**. | `exp@100P` |
| `exp@<amount>L` | Gives experience **levels**. | `exp@5L` |
| `permissions@<level>` | Grants a temporary permission level (admin contact required). | `permissions@2` |

If no suffix is specified, experience points are assumed (default = points).

## Commands

All commands are registered server-side via `CommandRegistrationCallback`. The base command is `/rcode`.

### Player Command

```
/rcode redeem <code>
```
Redeems a code. Available to all players.

### Operator Commands (Permission Level 2+)

```
/rcode generate <type> [options] <reward>
```
Generates a new code. If no custom code string is specified, a random 16-character alphanumeric code is generated.

**Examples:**

```bash
# One-time code with auto-generated ID
/rcode generate once item@minecraft:diamond 64

# Permanent code with custom ID
/rcode generate permanent code MYCODE exp@10L

# Personal code for a specific player
/rcode generate personal Steve item@minecraft:netherite_sword

# Global limited code (top 3 by tag "vip")
/rcode generate global_limit tag vip 3 item@minecraft:elytra

# Global limited code (bottom 5 by scoreboard "score")
/rcode generate global_limit scoreboard score -5 item@minecraft:gold_ingot 32

# Timed code (active from 2025-01-01 to 2025-12-31)
/rcode generate timed 2025-01-01_00-00-00 2025-12-31_23-59-59 exp@100P

# Cycle code (daily, starting now)
/rcode generate cycle 0-0-0_0-0-0 86400 item@minecraft:golden_apple
```

Append `cover` to the end of a generate command to overwrite an existing code:
```
/rcode generate once code MYCODE item@minecraft:diamond cover
```

**Time format:** `yyyy-MM-dd_HH-mm-ss`. Use `0-0-0_0-0-0` or `0` for "start now".

Deletes a code.
```
/rcode delete <code>
```

Shows detailed information about a code, including type, reward, usage statistics, and who has redeemed it. Online players are shown by name; offline players are shown by UUID.
```
/rcode info <code>
```

### Admin Command (Permission Level 4)

```
/rcode reload
```
Hot-reloads the configuration file and re-initializes all I/O resources (SQL pool, Redis pool, Web server).

## Web Management Panel

Enable the web panel in the configuration file:

```properties
web.enabled=true
web.port=8080
```

The panel provides:

- **Login Page** (`index.html`) — Authentication with username/password (configured in properties).
- **Admin Dashboard** (custom path, auto-generated on first run) — Full management interface including:
  - Server status overview (online players, total codes, storage backend, mod version)
  - Code management (list, create, view details, delete)
  - Operation log browser with pagination
  - Online player list with UUID, ping, IP, and tags
  - Scoreboard objectives viewer
  - Configuration viewer (sensitive values masked)
  - Hot reload button

The admin panel path is randomly generated on first launch for security. Find it in your config file under `web.adminPath`. A password is also auto-generated and stored in `web.password`.

When `web.sendUrlToOP=true`, operators receive the panel URL in chat upon joining the server.

## Storage Backends

Configure the storage backend in `redemptioncodefabric.properties`:

```properties
# Options: file, sql, redis
datastore.type=file
```

**File** (default) — Stores codes and logs as JSON files in the mod's config directory. Zero setup required.

**SQL (MySQL)** — Requires a running MySQL server. Tables (`redemption_codes`, `operation_logs`) are auto-created on first connection. Connection parameters:

```properties
sql.host=localhost
sql.port=3306
sql.user=admin
sql.password=
sql.database=redemptioncode
```

**Redis** — Requires a running Redis server. Codes are stored as Redis hashes with a set-based index. Falls back to file storage if Redis is unavailable.

```properties
redis.host=localhost
redis.port=6379
redis.password=
redis.database=0
```

Both SQL and Redis connections use retry logic (3 attempts with 2-second delays). If all attempts fail, the mod automatically falls back to file storage and alerts online operators.

## Connection Pool

When using the SQL backend, a Simple connection pool provides production-grade connection management:

| Feature | Description |
|---------|-------------|
| Elastic pool sizing | Scales between `minIdle` and `maxPoolSize` on demand |
| Connection lifecycle | Connections exceeding `maxLifetime` are retired on release |
| Idle timeout eviction | Idle connections exceeding `idleTimeout` are evicted (respects `minIdle`) |
| Leak detection | Warns when connections are held longer than `leakDetectionThreshold` |
| Keepalive heartbeat | Periodically validates idle connections with `isValid()` |
| Borrow validation | Every connection is verified before being handed out |
| Acquisition timeout | Waits up to `connectionTimeout` ms when the pool is full |
| Graceful shutdown | Stops maintenance, drains idle, waits for in-use, then force-closes |
| Init SQL | Executes `connectionInitSql` on every new connection |

All pool parameters are configurable in the properties file under the `pool.*` namespace.

## Configuration

The configuration file is located at `config/redemptioncodefabric/redemptioncodefabric.properties`. It is auto-created on first launch with organized sections and comments.

```properties
# ── Datastore ──────────────────────────────────────────
datastore.type=file

# ── Logging ────────────────────────────────────────────
log.redemption.history=true
log.max.entries=20000

# ── Web Server ─────────────────────────────────────────
web.enabled=false
web.url=http://localhost
web.port=8080
web.user=admin
web.password=<auto-generated>
web.adminPath=<auto-generated>
web.sendUrlToOP=true

# ── SQL (MySQL) ────────────────────────────────────────
sql.host=localhost
sql.port=3306
sql.user=admin
sql.password=
sql.database=redemptioncode
sql.url=jdbc:mysql://localhost:3306/redemptioncode?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true

# ── Redis ──────────────────────────────────────────────
redis.host=localhost
redis.port=6379
redis.password=
redis.database=0

# ── Connection Pool ────────────────────────────────────
pool.maxPoolSize=10
pool.minIdle=2
pool.connectionTimeout=30000
pool.idleTimeout=600000
pool.maxLifetime=1800000
pool.leakDetectionThreshold=60000
pool.keepaliveTime=300000
pool.validationTimeout=5000
pool.connectionInitSql=
```

## Dependencies

- **Minecraft** 1.20.1
- **Fabric Loader** >= 0.18.4
- **Fabric API**
- **Java** >= 17
- **MySQL Connector/J** 9.7.0 (bundled, shadowed)
- **Jedis** 5.2.0 (bundled, shadowed)

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

See [CREDITS.md](CREDITS.md) for third-party dependency licenses.

## Links

- **GitHub:** https://github.com/juzizhen/RedemptionCodeFabric
- **Issues:** https://github.com/juzizhen/RedemptionCodeFabric/issues
- **Curseforge:** https://www.curseforge.com/minecraft/mc-mods/redemptioncodefabric
