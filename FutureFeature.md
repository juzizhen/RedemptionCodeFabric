# RedemptionCodeFabric — 未来特性与优化路线

## 一、当前架构状态（v1.x 已完成）

经过架构评估与优化（参见 function-1.md 排查清单），当前版本已落实以下核心保障：

| 模块 | 状态 | 实现方式 |
|------|------|----------|
| 线程安全 | 已完成 | HTTP 读操作通过 `CompletableFuture` + `server.execute()` 投递到 MC 主线程；写操作（addCode/deleteCode）同理；CodeManager 使用 ConcurrentHashMap |
| 生命周期管理 | 已完成 | `SERVER_STOPPING` 事件触发 `AsyncIoManager.shutdown()`，按依赖链逆序关闭：WebServer -> SQL Pool -> Redis Pool -> IO Executor |
| JDBC 连接池 | 已完成 | 对标 HikariCP：弹性池大小、借出验证（isValid）、maxLifetime 淘汰、空闲驱逐、泄漏检测、keepalive 心跳、优雅关闭 |
| Redis 线程安全 | 已完成 | JedisPool + try-with-resources，每次请求独立获取/归还连接 |
| 并发防重领 | 已完成 | 兑换逻辑仅运行在 MC 主线程（命令系统），天然串行无竞态 |
| HTTP 线程池 | 已完成 | `AsyncIoManager.getIoExecutor()` 固定 4 线程池，HttpServer 非单线程模式 |
| 安全防护 | 已完成 | SecurityFilter：Token 鉴权（滑动 TTL 30min）、登录限流（5次/分钟）、API 限流（20次/秒）、安全响应头注入 |
| SQL 注入防护 | 已完成 | 全部使用 PreparedStatement 参数绑定 |
| 依赖隔离 | 已完成 | shadow relocate：mysql、jedis、commons-pool2、gson、slf4j 均重定位到 `com.juzizhen.redemptioncodefabric.rcode.shadow.*` |

## 二、计划中：Web 安全层去 Jedis 化（原生 RESP 实现）

### 2.1 动机

当前 SecurityFilter 使用 Jedis 作为 Redis 客户端。Web 安全层的 Redis 用法极为有限（SET/GET/DEL/INCR/EXPIRE），替换为原生 JDK Socket + RESP 协议实现可以：

- 减少最终 jar 体积约 400~600KB（Jedis ~300KB + commons-pool2 ~120KB + slf4j 等）
- 消除 shadow relocate 时 Jedis 内部反射/ServiceLoader 的兼容风险
- 去除对第三方库版本更新的跟踪负担

注意：数据存储层（RedisRepository）仍保留 Jedis，因其使用了 Hash/Set/Pipeline/List 等高级命令。此计划仅针对 Web 安全层。

### 2.2 涉及范围

| 用途 | 命令 | 说明 |
|------|------|------|
| 管理员 token 存储 | `SET key value EX ttl` | admin:active_token，30min 滑动 TTL |
| token 验证 | `GET key` | 每次请求校验 X-Admin-Token |
| token 续期 | `EXPIRE key seconds` | 滑动窗口续期 |
| 单点登录抢占 | `SET key value EX ttl`（覆盖写） | 新登录踢掉旧会话 |
| 登出/失效 | `DEL key` | 主动注销 |
| 登录限流 | `INCR key` + `EXPIRE key 60` | 5次/分钟 |
| API 限流 | `INCR key` + `EXPIRE key 1` | 20次/秒 |

全部为基础字符串/计数器操作，无 Pub/Sub、Stream、Lua 脚本、事务等高级用法。

### 2.3 实现方案概要

新建 `com.juzizhen.redemptioncodefabric.rcode.web.redis.RawRedisClient`：

- 基于原生 Socket + BufferedInputStream/OutputStream
- RESP 协议编解码（Simple String / Error / Integer / Bulk String / Array）
- 所有公开方法 `synchronized`，保证单连接串行执行（单管理员场景无瓶颈）
- 断线自动重连（catch IOException -> close -> reconnect -> retry once）
- 失败时降级到现有 in-memory 逻辑，不影响可用性

### 2.4 迁移步骤

1. 新建 RawRedisClient + RedisConnectionException
2. SecurityFilter 中将 JedisPool 字段替换为 RawRedisClient 字段
3. 逐方法替换 Jedis 调用（setEx/get/del/incr/expire）
4. 移除 SecurityFilter 中 `import redis.clients.jedis.*`
5. build.gradle 中 jedis 依赖保留（数据存储层仍需要），但 web 层不再引用
6. 本地全流程测试（登录/限流/抢占/登出/Redis 断开降级）

### 2.5 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 单连接成为瓶颈 | 低 | 单管理员场景无压力；未来可加池 |
| 手写协议解析有 bug | 中 | 单元测试覆盖所有 RESP 类型 + 集成测试 |
| 长连接被 NAT 断开 | 低 | 已有重连重试 + in-memory 降级 |

## 三、远期规划

### 3.1 连接池后台心跳主动探活

当前 SimpleConnectionPool 的 keepalive 仅在维护周期（30s）内对空闲连接做 isValid 检测。未来可考虑：

- 在 MySQL 端配置 `wait_timeout` > `maxLifetime`，从根源避免僵尸连接
- 可选：TCP KeepAlive 参数调优（操作系统层面）

### 3.2 Web 面板 HTTPS 支持

当前 HttpServer 为纯 HTTP。若面板需暴露到公网，可考虑：

- 使用 `HttpsServer` + 自签名证书 / Let's Encrypt
- 或建议用户通过 Nginx/Caddy 反向代理提供 TLS 终止

### 3.3 数据库层原子兑换（多服共享数据库场景）

当前兑换逻辑在 MC 主线程串行执行，单服无竞态。若未来多服共享同一数据库：

- SQL 层：`UPDATE redemption_codes SET used_by = ? WHERE code = ? AND used_by NOT LIKE ?`（CAS 原子更新）
- Redis 层：Lua 脚本原子化"查+锁"

### 3.4 操作日志持久化优化

当前操作日志与兑换码存储在同一后端。未来可考虑：

- 日志独立存储（如 SQLite / 独立 MySQL 表分区）
- 按时间自动归档/清理（配合 `log.max.entries` 配置）

### 3.5 Web 面板 WebSocket 实时推送

当前面板通过轮询获取数据。未来可考虑：

- JDK HttpServer 不原生支持 WebSocket，需手写 Upgrade 握手
- 或引入轻量 WebSocket 库（需评估 shadow 打包兼容性）
- 实现兑换实时通知、在线玩家变动推送
