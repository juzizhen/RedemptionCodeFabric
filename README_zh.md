<p align="center">
  <img src="src/main/resources/assets/redemptioncodefabric/icon.png" alt="RedemptionCodeFabric" width="128" />
</p>

<h1 align="center">RedemptionCodeFabric</h1>

<p align="center">
  一个仅需服务端安装的 Minecraft Fabric 兑换码模组，为你的服务器提供强大的兑换码系统。<br/>
  <a href="README.md">English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.20.1-brightgreen" alt="Minecraft 1.20.1" />
  <img src="https://img.shields.io/badge/Fabric-0.18.4+-blue" alt="Fabric Loader" />
  <img src="https://img.shields.io/badge/Java-17%2B-orange" alt="Java 17+" />
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue" alt="License" />
</p>

---

## 简介

RedemptionCodeFabric 是一个面向 Minecraft Fabric 服务端（1.20.1）的兑换码模组。它允许服务器管理员生成并分发兑换码，玩家可通过兑换码获取物品、经验或权限等级。模组支持七种不同的兑换码类型，覆盖从一次性礼包码到每日循环奖励等多种场景。

只需服务器安装模组即可使用；客户端可以不安装。但如果客户端也安装了本模组，消息将通过 Minecraft 内置翻译系统显示，体验更佳。

## 功能特性

**七种兑换码类型** — ONCE、PERMANENT、PERSONAL、GLOBAL_UNLIMITED、GLOBAL_LIMIT、TIMED 和 CYCLE，覆盖从一次性兑换到周期性奖励的全部场景。

**灵活的奖励内容** — 支持物品（含 NBT 数据）、经验值/经验等级、权限等级。甚至可以直接捕获手中持有的物品作为奖励。

**三种存储后端** — 支持本地 JSON 文件、MySQL 数据库和 Redis 持久化。当配置的后端不可用时，自动回退到文件存储。

**内置 Web 管理面板** — 基于浏览器的管理界面，支持登录认证。可在网页上创建、查看和删除兑换码，浏览操作日志，查看服务器状态和配置信息。

**生产级连接池** — 自研 SQL 连接池，对标 HikariCP 核心特性：弹性池大小、连接生命周期管理、空闲超时驱逐、泄漏检测、Keepalive 心跳、优雅关闭。

**异步 I/O 架构** — 所有阻塞式 I/O（SQL、Redis、文件、Web）均在专用线程池中运行，确保 Minecraft 主线程永不被阻塞。

**热加载配置** — 通过 `/rcode reload` 命令或 Web 面板即可在运行时重载配置，SQL 连接池、Redis 连接池和 Web 服务器均会动态重新初始化。

**国际化支持** — 服务端消息根据系统区域设置自适应（支持中文和英文）。客户端在安装了本模组时使用 Minecraft 翻译系统。

**详细操作日志** — 每次生成、兑换和删除操作都会被记录，包含时间戳、执行人信息和详细参数，方便审计追溯。

## 兑换码类型

| 类型 | 说明 |
|------|------|
| `ONCE` | 全局只能被一个玩家兑换一次。 |
| `PERMANENT` | 无限次使用，每个玩家可重复兑换。 |
| `PERSONAL` | 仅限指定玩家（通过 UUID、玩家名或 `@选择器`）兑换一次。 |
| `GLOBAL_UNLIMITED` | 每个玩家可兑换一次，无全局次数上限。 |
| `GLOBAL_LIMIT` | 全局共享使用次数。可通过 Tag 或计分板排名筛选允许兑换的玩家（前 N 名或后 N 名）。 |
| `TIMED` | 仅在指定时间段内有效，每个玩家可兑换一次。 |
| `CYCLE` | 重复时间窗口（如每日），每个玩家每个周期可兑换一次。 |

## 奖励类型

| 格式 | 说明 | 示例 |
|------|------|------|
| `item@<id>{nbt}` | 给予物品，可选附带 NBT 数据。 | `item@minecraft:diamond_sword{Enchantments:[{id:"sharpness",lvl:5}]}` |
| `[hand]` | 捕获生成兑换码时主手持有的物品。 | `[hand]` |
| `exp@<数量>P` | 给予经验**值**（Points）。 | `exp@100P` |
| `exp@<数量>L` | 给予经验**等级**（Levels）。 | `exp@5L` |
| `permissions@<等级>` | 授予临时权限等级（需联系管理员）。 | `permissions@2` |

不写后缀时默认给予经验值（Points）。

## 指令

所有指令通过 `CommandRegistrationCallback` 在服务端注册，基础指令为 `/rcode`。

### 玩家指令

```
/rcode redeem <兑换码>
```
兑换一个兑换码，所有玩家均可使用。

### 管理员指令（权限等级 2+）

```
/rcode generate <类型> [选项] <奖励>
```
生成新的兑换码。如果未指定自定义码，将自动生成 16 位随机字母数字码。

**示例：**

```bash
# 一次性兑换码，自动生成码
/rcode generate once item@minecraft:diamond 64

# 永久兑换码，自定义码
/rcode generate permanent code MYCODE exp@10L

# 个人兑换码，指定玩家
/rcode generate personal Steve item@minecraft:netherite_sword

# 全服限次兑换码（Tag "vip" 前 3 名）
/rcode generate global_limit tag vip 3 item@minecraft:elytra

# 全服限次兑换码（计分板 "score" 后 5 名）
/rcode generate global_limit scoreboard score -5 item@minecraft:gold_ingot 32

# 限时兑换码（2025-01-01 至 2025-12-31）
/rcode generate timed 2025-01-01_00-00-00 2025-12-31_23-59-59 exp@100P

# 循环兑换码（每日，立即开始）
/rcode generate cycle 0-0-0_0-0-0 86400 item@minecraft:golden_apple
```

在生成指令末尾加上 `cover` 可覆盖已存在的同名兑换码：
```
/rcode generate once code MYCODE item@minecraft:diamond cover
```

**时间格式：** `yyyy-MM-dd_HH-mm-ss`。使用 `0-0-0_0-0-0` 或 `0` 表示"立即开始"。

删除一个兑换码。
```
/rcode delete <兑换码>
```

查看兑换码的详细信息，包括类型、奖励、使用统计以及兑换记录。在线玩家显示玩家名，离线玩家显示 UUID。
```
/rcode info <兑换码>
```

### 管理员指令（权限等级 4）

```
/rcode reload
```
热重载配置文件并重新初始化所有 I/O 资源（SQL 连接池、Redis 连接池、Web 服务器）。

## Web 管理面板

在配置文件中启用 Web 面板：

```properties
web.enabled=true
web.port=8080
```

面板功能包括：

- **登录页面**（`index.html`）— 通过用户名和密码认证（在配置文件中设置）。
- **管理后台**（自定义路径，首次启动自动生成）— 完整管理界面，包括：
  - 服务器状态概览（在线玩家数、兑换码总数、存储后端类型、模组版本）
  - 兑换码管理（列表、创建、查看详情、删除）
  - 操作日志浏览器（支持分页）
  - 在线玩家列表（UUID、延迟、IP、Tag）
  - 计分板目标查看器
  - 配置信息查看器（敏感信息脱敏显示）
  - 一键热重载按钮

管理后台路径在首次启动时随机生成以确保安全，可在配置文件的 `web.adminPath` 中查看。密码同样自动生成并存储在 `web.password` 中。

当 `web.sendUrlToOP=true` 时，管理员加入服务器时会在聊天中收到面板链接。

## 存储后端

在 `redemptioncodefabric.properties` 中配置存储后端：

```properties
# 可选值：file、sql、redis
datastore.type=file
```

**File（文件，默认）** — 将兑换码和日志以 JSON 文件形式存储在模组的配置目录中。零配置，开箱即用。

**SQL（MySQL）** — 需要运行中的 MySQL 服务器。首次连接时自动创建数据表（`redemption_codes`、`operation_logs`）。连接参数：

```properties
sql.host=localhost
sql.port=3306
sql.user=admin
sql.password=
sql.database=redemptioncode
```

**Redis** — 需要运行中的 Redis 服务器。兑换码以 Redis Hash 形式存储，配合 Set 索引。Redis 不可用时自动回退到文件存储。

```properties
redis.host=localhost
redis.port=6379
redis.password=
redis.database=0
```

SQL 和 Redis 连接均使用重试逻辑（最多 3 次，每次间隔 2 秒）。如果所有尝试均失败，模组自动回退到文件存储并向在线管理员发送警报。

## 连接池

使用 SQL 后端时，简易连接池提供生产级连接管理能力：

| 特性 | 说明 |
|------|------|
| 弹性池大小 | 在 `minIdle` 和 `maxPoolSize` 之间按需伸缩 |
| 连接生命周期 | 超过 `maxLifetime` 的连接在归还时被淘汰 |
| 空闲超时驱逐 | 空闲时间超过 `idleTimeout` 的连接被驱逐（保留 `minIdle` 个） |
| 泄漏检测 | 连接借出超过 `leakDetectionThreshold` 未归还时打印警告 |
| Keepalive 心跳 | 定期对空闲连接执行 `isValid()` 验证 |
| 借出验证 | 每次 `getConnection()` 均验证连接有效性 |
| 获取超时 | 池满时最多等待 `connectionTimeout` 毫秒 |
| 优雅关闭 | 停维护 → 排空空闲 → 等待在用 → 强制关闭 |
| 初始化 SQL | 每个新连接建立后执行 `connectionInitSql` |

所有连接池参数均可在配置文件的 `pool.*` 命名空间下配置。

## 配置文件

配置文件位于 `config/redemptioncodefabric/redemptioncodefabric.properties`，首次启动时自动生成，包含分段的注释说明。

```properties
# ── 数据存储 ────────────────────────────────────────────
datastore.type=file

# ── 日志 ────────────────────────────────────────────────
log.redemption.history=true
log.max.entries=20000

# ── Web 服务器 ──────────────────────────────────────────
web.enabled=false
web.url=http://localhost
web.port=8080
web.user=admin
web.password=<自动生成>
web.adminPath=<自动生成>
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

# ── 连接池 ──────────────────────────────────────────────
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

## 依赖项

- **Minecraft** 1.20.1
- **Fabric Loader** >= 0.18.4
- **Fabric API**
- **Java** >= 17
- **MySQL Connector/J** 9.7.0（内置，已重定位）
- **Jedis** 5.2.0（内置，已重定位）

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 开源。

第三方依赖许可证详见 [CREDITS.md](CREDITS.md)。

## 相关链接

- **GitHub：** https://github.com/juzizhen/RedemptionCodeFabric
- **问题反馈：** https://github.com/juzizhen/RedemptionCodeFabric/issues
