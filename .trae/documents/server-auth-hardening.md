# Gradum 服务端鉴权加固

## Context

Gradum 内嵌的 Ktor 服务默认绑定 `localhost`，但**全部路由零鉴权**（`App.kt` 只装了 `ContentNegotiation`）。这带来两个现实问题：

- `POST /skills/deploy` 会往 `~/.gradum/skills` 写任意 `.kt`，目录 watcher 自动编译加载，等价于 RCE；`POST /events` 能驱动带 RunCommand/WriteFile 的 agent，同样是 RCE。
- `GET /skills/editor` 及其静态资源完全公开，编辑器页面「裸奔」。

攻击面有两类：
1. **本机其他进程/用户**：loopback 绑定挡不住，直接 curl 即可 deploy。
2. **浏览器恶意页面 + DNS rebinding**：普通跨域 fetch 因 `application/json` 触发预检而受阻，但 rebinding 后请求变为同源、预检/CORS 全部失效；服务端不校验 `Host`，于是可完整读写。

目标：Host 白名单 + 强制 loopback + Bearer token 三层收口；编辑器页面在外部浏览器打开，用 cookie 承载凭证。

## 设计

### 1. Token 生成与存储（server）

新增 `src/main/kotlin/gradum/server/ServerAuth.kt`：

- 复用 `ServerSettingsStore.configDir()`（`gradum.server.configDir` 系统属性可覆盖，测试用）。
- Token = 32 字节 `SecureRandom` → base64url 无 padding。
- 文件 `~/.gradum/server.token`：存在且非空则复用，否则生成并以 **0600** 写入（POSIX；非 POSIX best-effort）。
- 支持环境变量 `GRADUM_SERVER_TOKEN` 覆盖，便于容器与测试。
- 提供常量时间比较（`MessageDigest.isEqual`）的校验函数。

### 2. 配置传递

- `ServerConfiguration` 增加 `val authToken: String? = null`。
  - `null` = 不启用鉴权（保留给测试与嵌入式调用）。
  - `Main.kt` **始终**传入真实 token，因此生产 HTTP 入口始终开启鉴权。
- `App.kt` 的 `module()` 在安装 `ContentNegotiation` 后安装鉴权中间件。

### 3. 鉴权中间件（server，`App.kt`）

按序执行：

1. **Host 白名单**：读 `Host` 头，解析主机名（去端口，处理 `[::1]` 形式）。不在 `{localhost, 127.0.0.1, ::1, ::ffff:127.0.0.1}` → `403`。
2. **Bearer token**（仅当 `authToken != null`）：
   - 跳过 `/health`（build.gradle 的 `waitForServerHealth` 与监控探测依赖它）。
   - 依次从 `Authorization: Bearer <t>`、cookie `gradum_token`、query `?token=` 取候选值，常量时间比较；失败 → `401` + JSON `{"error":"unauthorized"}`。

### 4. 编辑器页面（server，`Routes.kt`）

- `/skills/editor` 路由：当鉴权启用且请求带合法 `?token=` 时，响应追加
  `Set-Cookie: gradum_token=<t>; Path=/; HttpOnly; SameSite=Strict`（不加 `Secure`，否则回环 http 下浏览器不发送）。
- 静态资源与页面内 `fetch` 靠该 cookie 自动通过 → **`skills-editor` 下 JS 无需改动**。
- 无 token 且无 cookie 访问 `/skills/editor` → `401` + 纯文本提示（含获取 token 的命令）。
- `Main.kt` 启动日志打印带 token 的编辑器 URL，方便直接点开。

### 5. 强制 loopback（server，`Main.kt`）

- 校验 `settings.host` ∈ `{localhost, 127.0.0.1, ::1}`；否则打 ERROR 并**拒绝启动**，提示修改 `~/.gradum/settings.json` 的 `server.host`。

### 6. 插件客户端

- 新增读取 token 的小工具（plugin 侧，读 `~/.gradum/server.token`，按 mtime 缓存；读到 401 时失效重读一次，兼容服务器轮换 token）。
- `plugin/src/main/kotlin/gradum/idea/chat/api/GradumApiClient.kt`：把所有 `HttpRequest.newBuilder()` 收敛到一个私有 `newRequest(uri)` 帮助函数，统一加 `Authorization: Bearer`。
- `plugin/src/main/kotlin/gradum/idea/provider/ProviderProbe.kt`：同样加 header（它调用 `POST /provider/probe`）。
- 其余命中 `localhost:8765` 的位置只有上述两处（已 grep 确认）。

### 7. 文档

- `PRIVACY.md`：补充「本地 HTTP 接口受 Host 白名单 + Bearer token 保护，token 存于 `~/.gradum/server.token`」。

## 涉及文件

| 文件 | 改动 |
|---|---|
| `src/main/kotlin/gradum/server/ServerAuth.kt` | 新增：token 生成/存储/校验 |
| `src/main/kotlin/gradum/server/ServerConfiguration.kt` | 新增 `authToken` 字段 |
| `src/main/kotlin/gradum/server/App.kt` | 安装 Host 校验 + 鉴权中间件 |
| `src/main/kotlin/gradum/server/Main.kt` | 传入 token、打印编辑器 URL、loopback fail-fast |
| `src/main/kotlin/gradum/server/Routes.kt` | `/skills/editor` 设 cookie、无凭证 401 |
| `plugin/.../chat/api/GradumApiClient.kt` | 统一加 Authorization 头 |
| `plugin/.../provider/ProviderProbe.kt` | 加 Authorization 头 |
| `plugin/.../server/ServerTokenStore.kt`（新） | 插件侧读 token |
| `src/test/kotlin/gradum/server/ServerAuthTest.kt`（新） | token 生命周期测试 |
| `src/test/kotlin/gradum/server/ServerRoutesTest.kt` | 现有用例保持 `authToken=null`；新增鉴权用例 |
| `PRIVACY.md` | 文档补充 |

## 验证

1. `./gradlew test`：现有 3 个路由用例（`authToken=null`）保持通过；新增用例覆盖 401 / 200 / Host 403 / `/health` 豁免。
2. 测试通过 `-Dgradum.server.configDir=<tmp>` 指向临时目录，绝不写真实 `~/.gradum`。
3. 手动端到端（由你执行）：
   - 启动 server，`curl -s -o /dev/null -w "%{http_code}" http://localhost:8765/skills` → `401`。
   - 带 `-H "Authorization: Bearer $(cat ~/.gradum/server.token)"` → `200`。
   - `curl -H "Host: evil.com" http://127.0.0.1:8765/health` → `403`。
   - 浏览器打开启动日志中带 token 的编辑器 URL → 页面加载、运行技能正常；再开不带 token 的 URL → 401 提示。
   - 插件侧发一条消息与一次 provider 探测，确认功能不回归。

## 已知取舍

- token 明文存于 0600 文件：与「本机同用户进程」等价，属可接受的本地信任边界。
- `ServerConfiguration.authToken` 默认 `null` 会关闭鉴权，是留给测试/嵌入的口子；生产唯一 HTTP 入口 `Main.kt` 始终启用。
- cookie 不加 `Secure` 且 `SameSite=Strict`：回环 http 下的必要取舍，同时阻断跨站携带。
