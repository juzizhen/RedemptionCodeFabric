# RedemptionCodeFabric — 原生 JDK 替换 Jedis 方案

## 一、背景与动机

当前项目 web 安全层（SecurityFilter）使用 Jedis 作为 Redis 客户端，通过 shadow 打包将 Jedis 及其传递依赖（commons-pool2、slf4j-api 等）捆绑进最终 mod jar。替换为原生 JDK Socket 实现可以：

- 减少最终 jar 体积约 400~600KB（Jedis ~300KB + commons-pool2 ~120KB + slf4j 等）
- 消除 shadow relocate 时 Jedis 内部反射/ServiceLoader 的兼容风险
- 去除对第三方库版本更新的跟踪负担
- web 层 Redis 用法极为有限（SET/GET/DEL/INCR/EXPIRE），原生实现完全可控

## 二、当前 Jedis 使用范围

根据 SecurityFilter 及 WebServer 现有代码，Redis 操作仅涉及：

| 用途              | 命令                             | 说明                               |
|-------------------|----------------------------------|------------------------------------|
| 管理员 token 存储 | `SET key value EX ttl`           | admin:active_token，30min 滑动 TTL |
| token 验证        | `GET key`                        | 每次请求校验 X-Admin-Token         |
| token 续期        | `EXPIRE key seconds`             | 滑动窗口续期                       |
| 单点登录抢占      | `SET key value EX ttl`（覆盖写） | 新登录踢掉旧会话                   |
| 登出/失效         | `DEL key`                        | 主动注销                           |
| 登录限流          | `INCR key` + `EXPIRE key 60`     | 5次/分钟                           |
| API 限流          | `INCR key` + `EXPIRE key 1`      | 20次/秒                            |

全部为基础字符串/计数器操作，无 Pub/Sub、Stream、Lua 脚本、事务等高级用法。

## 三、RESP 协议基础

Redis 服务端使用 RESP（REdis Serialization Protocol）通信，基于 TCP 纯文本：

### 3.1 请求格式（客户端 -> 服务端）

命令以"批量字符串数组"发送：

```
*<参数个数>\r\n
$<参数1字节长度>\r\n
<参数1内容>\r\n
$<参数2字节长度>\r\n
<参数2内容>\r\n
...
```

示例 — `SET admin:active_token abc123 EX 1800`：

```
*5\r\n
$3\r\nSET\r\n
$20\r\nadmin:active_token\r\n
$6\r\nabc123\r\n
$2\r\nEX\r\n
$4\r\n1800\r\n
```

### 3.2 响应格式（服务端 -> 客户端）

| 首字符 | 类型          | 示例                       | 说明                           |
|--------|---------------|----------------------------|--------------------------------|
| `+`    | Simple String | `+OK\r\n`                  | 成功                           |
| `-`    | Error         | `-ERR unknown command\r\n` | 错误                           |
| `:`    | Integer       | `:1\r\n`                   | INCR/DEL 返回值                |
| `$`    | Bulk String   | `$6\r\nfoobar\r\n`         | GET 返回值；`$-1\r\n` 表示 nil |
| `*`    | Array         | `*2\r\n...`                | 多键返回；`*-1\r\n` 表示 nil   |

## 四、核心实现：RawRedisClient

### 4.1 类结构设计

```
com.juzizhen.redemptioncodefabric.web.redis/
├── RawRedisClient.java      // 核心：连接管理 + RESP 编解码
├── RedisConnectionException.java  // 连接/协议异常
└── (可选) SimpleRedisPool.java    // 后续扩展用，初期不需要
```

### 4.2 完整代码

```java
package com.juzizhen.redemptioncodefabric.web.redis;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 零依赖 Redis 客户端，基于原生 Socket + RESP 协议。
 * 线程安全：内部 synchronized，适用于低并发场景（单管理员 web 面板）。
 */
public class RawRedisClient implements Closeable {

    private final String host;
    private final int port;
    private final String password;   // null = 无密码
    private final int database;      // 默认 0
    private final int timeoutMs;

    private Socket socket;
    private OutputStream out;
    private InputStream in;
    private volatile boolean closed = false;

    public RawRedisClient(String host, int port, String password, int database, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
        this.timeoutMs = timeoutMs;
    }

    public RawRedisClient(String host, int port) {
        this(host, port, null, 0, 3000);
    }

    // ==================== 连接管理 ====================

    private synchronized void ensureConnected() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        connect();
    }

    private void connect() throws IOException {
        closeQuietly();
        socket = new Socket();
        socket.setSoTimeout(timeoutMs);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        out = new BufferedOutputStream(socket.getOutputStream(), 8192);
        in = new BufferedInputStream(socket.getInputStream(), 8192);

        // AUTH
        if (password != null && !password.isEmpty()) {
            String reply = executeRaw("AUTH", password);
            if (!"OK".equals(reply)) {
                throw new IOException("Redis AUTH failed: " + reply);
            }
        }
        // SELECT db
        if (database != 0) {
            String reply = executeRaw("SELECT", String.valueOf(database));
            if (!"OK".equals(reply)) {
                throw new IOException("Redis SELECT failed: " + reply);
            }
        }
    }

    // ==================== 公开 API ====================

    /** 通用命令入口 */
    public synchronized String command(String... args) throws IOException {
        ensureConnected();
        try {
            return executeRaw(args);
        } catch (IOException e) {
            // 连接可能断开，重连一次重试
            closeQuietly();
            ensureConnected();
            return executeRaw(args);
        }
    }

    /** SET key value EX seconds */
    public String setEx(String key, String value, long seconds) throws IOException {
        return command("SET", key, value, "EX", String.valueOf(seconds));
    }

    /** GET key，不存在返回 null */
    public String get(String key) throws IOException {
        return command("GET", key);
    }

    /** DEL key，返回删除数量 */
    public long del(String key) throws IOException {
        String r = command("DEL", key);
        return r == null ? 0 : Long.parseLong(r);
    }

    /** INCR key，返回自增后的值 */
    public long incr(String key) throws IOException {
        String r = command("INCR", key);
        return Long.parseLong(r);
    }

    /** EXPIRE key seconds */
    public boolean expire(String key, long seconds) throws IOException {
        String r = command("EXPIRE", key, String.valueOf(seconds));
        return "1".equals(r);
    }

    /** EXISTS key */
    public boolean exists(String key) throws IOException {
        String r = command("EXISTS", key);
        return "1".equals(r);
    }

    /** PING，用于连接健康检测 */
    public boolean ping() {
        try {
            return "PONG".equals(command("PING"));
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== RESP 编解码 ====================

    private String executeRaw(String... args) throws IOException {
        writeCommand(args);
        out.flush();
        return readReply();
    }

    private void writeCommand(String... args) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        sb.append('*').append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(bytes.length).append("\r\n");
            // 注意：参数内容可能含非 ASCII，需要分段写
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            sb.setLength(0);
            out.write(bytes);
            out.write('\r');
            out.write('\n');
        }
    }

    private String readReply() throws IOException {
        String line = readLine();
        if (line.isEmpty()) throw new IOException("Empty reply from Redis");
        char type = line.charAt(0);
        String body = line.substring(1);

        switch (type) {
            case '+':
                return body;
            case '-':
                throw new RedisConnectionException(body);
            case ':':
                return body;
            case '$': {
                int len = Integer.parseInt(body);
                if (len == -1) return null;
                byte[] data = readExact(len + 2); // 包含尾部 \r\n
                return new String(data, 0, len, StandardCharsets.UTF_8);
            }
            case '*': {
                int count = Integer.parseInt(body);
                if (count == -1) return null;
                // 当前场景不需要数组，简单拼接返回
                StringBuilder arr = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    if (i > 0) arr.append('\n');
                    arr.append(readReply());
                }
                return arr.toString();
            }
            default:
                throw new IOException("Unknown RESP type: " + type);
        }
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder(32);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                in.read(); // 消费 \n
                break;
            }
            sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) throw new EOFException("Connection closed");
        return sb.toString();
    }

    private byte[] readExact(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) throw new EOFException("Connection closed mid-read");
            off += r;
        }
        return buf;
    }

    // ==================== 生命周期 ====================

    private void closeQuietly() {
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        in = null; out = null; socket = null;
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeQuietly();
    }

    public boolean isClosed() { return closed; }
}
```

### 4.3 异常类

```java
package com.juzizhen.redemptioncodefabric.web.redis;

import java.io.IOException;

/** Redis 服务端返回 -ERR 时抛出 */
public class RedisConnectionException extends IOException {
    public RedisConnectionException(String message) {
        super(message);
    }
}
```

## 五、SecurityFilter 迁移对照

### 5.1 替换映射

| 原 Jedis 代码                                              | 替换为                                                  |
|------------------------------------------------------------|---------------------------------------------------------|
| `new JedisPool(config, host, port, timeout, password, db)` | `new RawRedisClient(host, port, password, db, timeout)` |
| `pool.getResource()` + try-with-resources                  | 直接使用 client 实例（内部 synchronized）               |
| `jedis.setex(key, seconds, value)`                         | `client.setEx(key, value, seconds)`                     |
| `jedis.get(key)`                                           | `client.get(key)`                                       |
| `jedis.del(key)`                                           | `client.del(key)`                                       |
| `jedis.incr(key)`                                          | `client.incr(key)`                                      |
| `jedis.expire(key, seconds)`                               | `client.expire(key, seconds)`                           |
| `jedis.close()` / 归还连接                                 | 不需要（长连接复用）                                    |
| `pool.close()`                                             | `client.close()`                                        |

### 5.2 初始化变更

```java
// === 旧代码 ===
JedisPoolConfig poolConfig = new JedisPoolConfig();
poolConfig.setMaxTotal(8);
JedisPool jedisPool = new JedisPool(poolConfig, host, port, 2000, password, database);

// === 新代码 ===
RawRedisClient redis = new RawRedisClient(host, port, password, database, 3000);
// 启动时验证连通性
if (!redis.ping()) {
    logger.warn("Redis unavailable, falling back to in-memory mode");
    redis = null;
}
```

### 5.3 调用处变更示例（token 抢占）

```java
// === 旧代码 ===
try (Jedis jedis = jedisPool.getResource()) {
    jedis.setex("admin:active_token", 1800, newToken);
}

// === 新代码 ===
try {
    redis.setEx("admin:active_token", newToken, 1800);
} catch (IOException e) {
    logger.warn("Redis write failed, token not persisted: " + e.getMessage());
    // 降级到 in-memory
}
```

### 5.4 限流逻辑变更

```java
// === 旧代码 ===
try (Jedis jedis = jedisPool.getResource()) {
    long count = jedis.incr(rateKey);
    if (count == 1) jedis.expire(rateKey, 60);
    if (count > maxAttempts) { /* 拒绝 */ }
}

// === 新代码 ===
try {
    long count = redis.incr(rateKey);
    if (count == 1) redis.expire(rateKey, 60);
    if (count > maxAttempts) { /* 拒绝 */ }
} catch (IOException e) {
    // Redis 不可用时放行（或走 in-memory 计数）
}
```

## 六、配置兼容

现有 `web.redis.*` 配置项完全不变：

```properties
web.redis.enabled=false
web.redis.host=127.0.0.1
web.redis.port=6379
web.redis.password=
web.redis.database=0
web.redis.timeout=3000
```

仅移除连接池相关配置（如 `web.redis.pool.maxTotal`），因为原生实现使用单长连接，无需池化。

## 七、打包变更

### 7.1 build.gradle / shadow 配置

```groovy
// 移除 Jedis 依赖
// implementation 'redis.clients:jedis:5.x.x'  <- 删除

// shadow jar 不再需要 relocate redis.clients 包
// relocate 'redis.clients', 'com.juzizhen.redemptioncodefabric.shaded.redis'  <- 删除
```

### 7.2 体积对比预估

| 项目                       | 替换前 | 替换后               |
|----------------------------|--------|----------------------|
| Jedis jar                  | ~300KB | 0                    |
| commons-pool2              | ~120KB | 0                    |
| slf4j-api                  | ~60KB  | 0（如仅 Jedis 使用） |
| RawRedisClient.java 编译后 | 0      | ~8KB                 |
| 净减少                     | —      | 约 470KB             |

## 八、线程安全与并发模型

当前 web 面板为单管理员使用，HTTP 请求由 JDK HttpServer 的线程池处理（默认无界），并发量极低。

设计决策：

- RawRedisClient 所有公开方法加 `synchronized`，保证单连接上的命令串行执行
- 对于 5~10 并发的 web 面板，串行 Redis 调用（每次 <1ms 局域网延迟）不构成瓶颈
- 若未来并发需求增长，见第十节扩展方案

## 九、容错与降级策略

保持现有 in-memory fallback 架构不变：

```
请求进入
  -> web.redis.enabled=true 且 RawRedisClient 可用？
      -> 是：走 Redis（SET/GET/INCR）
      -> 否：走 ConcurrentHashMap in-memory 逻辑
```

新增重连逻辑：

- `command()` 内部捕获 IOException 后自动 close + reconnect + 重试一次
- 若重试仍失败，抛出 IOException，由调用方 catch 后降级到 in-memory
- 可选：后台定时 PING（每 30s），提前发现断连

## 十、后续扩展逻辑

### 10.1 Pipeline 批量命令

场景：一次性写入多个限流 key 或批量查询。

```java
/** 批量发送，一次性读取所有响应 */
public synchronized List<String> pipeline(List<String[]> commands) throws IOException {
    ensureConnected();
    for (String[] args : commands) {
        writeCommand(args);
    }
    out.flush();
    List<String> results = new ArrayList<>(commands.size());
    for (int i = 0; i < commands.size(); i++) {
        results.add(readReply());
    }
    return results;
}
```

原理：Redis Pipeline 不是独立协议，只是"连续写 N 条命令，再连续读 N 个响应"，减少 RTT。

### 10.2 Pub/Sub

场景：多实例部署时 token 失效广播。

```java
/** 订阅频道（阻塞，需在独立线程运行） */
public void subscribe(String channel, Consumer<String> onMessage) throws IOException {
    ensureConnected();
    writeCommand("SUBSCRIBE", channel);
    out.flush();
    // 持续读取数组响应: *3\r\n$7\r\nmessage\r\n$<ch>\r\n...\r\n$<msg>\r\n...
    while (!closed) {
        String reply = readReply(); // 返回拼接的数组内容
        // 解析 message 类型，提取 payload
        onMessage.accept(reply);
    }
}
```

注意：订阅连接不能复用做普通命令，需独立 RawRedisClient 实例。

### 10.3 Lua 脚本（EVAL）

场景：原子化"读取+续期"或"限流+过期"合并为单次调用。

```java
/** EVAL script numkeys key... arg... */
public String eval(String script, int numKeys, String... keysAndArgs) throws IOException {
    String[] cmd = new String[3 + keysAndArgs.length];
    cmd[0] = "EVAL";
    cmd[1] = script;
    cmd[2] = String.valueOf(numKeys);
    System.arraycopy(keysAndArgs, 0, cmd, 3, keysAndArgs.length);
    return command(cmd);
}
```

示例 — 原子限流：

```lua
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
return count
```

### 10.4 连接池（高并发场景）

若未来 web 面板扩展为多管理员或对外开放 API：

```java
public class SimpleRedisPool {
    private final BlockingQueue<RawRedisClient> pool;
    private final String host;
    private final int port;
    // ...

    public SimpleRedisPool(int size, String host, int port, String password, int db, int timeout) {
        pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            pool.offer(new RawRedisClient(host, port, password, db, timeout));
        }
    }

    public RawRedisClient borrow() throws InterruptedException {
        return pool.poll(3, TimeUnit.SECONDS); // 超时返回 null -> 降级
    }

    public void release(RawRedisClient client) {
        if (client != null && !client.isClosed()) pool.offer(client);
    }
}
```

当前阶段不需要，单连接 synchronized 足够。

### 10.5 TLS/SSL 连接

若 Redis 启用 TLS：

```java
// 替换 new Socket() 为：
SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
socket = factory.createSocket();
// 或使用自定义 SSLContext 加载证书
```

### 10.6 Redis 6+ ACL / RESP3

- ACL：AUTH 命令变为 `AUTH username password`，只需在 connect() 中多传一个参数
- RESP3：新增 `%`（Map）、`~`（Set）、`>`（Push）等类型，在 readReply() 的 switch 中扩展 case 即可

## 十一、测试验证清单

| 测试项      | 方法                                                     | 预期                           |
|-------------|----------------------------------------------------------|--------------------------------|
| 基本连通    | `ping()`                                                 | 返回 true                      |
| SET/GET     | `setEx("test","v",60)` -> `get("test")`                  | "v"                            |
| DEL         | `del("test")` -> `get("test")`                           | 1, null                        |
| INCR+EXPIRE | `incr("c")` x3 -> `expire("c",1)` -> 等 2s -> `get("c")` | 3, true, null                  |
| 断线重连    | 手动重启 Redis -> 立即 `get()`                           | 自动重连成功                   |
| AUTH 失败   | 错误密码构造                                             | 抛 IOException，降级 in-memory |
| 并发安全    | 10 线程同时 `incr` 同一 key                              | 最终值 = 10                    |
| shadow 打包 | `gradlew shadowJar`                                      | jar 内无 redis.clients 包      |
| 体积对比    | 对比替换前后 jar 大小                                    | 减少 ~470KB                    |

## 十二、迁移步骤总结

1. 新建 `com.juzizhen.redemptioncodefabric.web.redis` 包，写入 RawRedisClient + RedisConnectionException
2. SecurityFilter 中将 JedisPool 字段替换为 RawRedisClient 字段
3. 逐方法替换 Jedis 调用（参照第五节映射表）
4. 移除 `import redis.clients.jedis.*` 及 commons-pool2 相关 import
5. build.gradle 删除 jedis 依赖和 shadow relocate 规则
6. 本地启动 Redis，运行 web 面板全流程测试（登录/限流/抢占/登出）
7. 停止 Redis，验证 in-memory fallback 正常
8. `gradlew shadowJar`，确认打包成功且体积减小
9. 提交，更新 README 中依赖说明

## 十三、风险评估

| 风险                    | 等级 | 缓解措施                              |
|-------------------------|------|---------------------------------------|
| 单连接成为瓶颈          | 低   | 当前单管理员场景无压力；未来可加池    |
| 手写协议解析有 bug      | 中   | 单元测试覆盖所有 RESP 类型 + 集成测试 |
| Redis 大 value 传输     | 低   | web 层仅存 token/计数器，无大 value   |
| 长连接被防火墙/NAT 断开 | 低   | 已有重连重试 + in-memory 降级         |
| 后续需要高级 Redis 特性 | 低   | 第十节已预留扩展路径                  |
