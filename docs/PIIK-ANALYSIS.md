# Piik 逆向分析 & TicketForTwo 重构路线

> 日期：2026-09-23 · 只做分析，未改动任何代码
> 分析对象：[TNTcraftHIM/Piik](https://github.com/TNTcraftHIM/Piik)（MIT，Go + TypeScript + 原生采集）
> 对照项目：TicketForTwo（本项目）

---

## 0. 一句话结论

**Piik 并非"不需要服务器"。** 它把服务器搬到了**房主自己的设备**上，再用 Cloudflare 的**免费出站隧道**给这台服务器开一个临时公网门牌。

观众之所以"点链接就进"，是因为房主设备与观众浏览器之间有一条**实时双向的信令通道**——不是靠人工回传 SDP。媒体仍然走 WebRTC P2P，**不经任何服务器**。这三件事组合起来，才产生"零配置、发个链接就能看"的体感。

而 TicketForTwo 选的是"零服务器 + 手工信令"：SDP 塞进链接、answer 回传。**它缺的从来不是媒体服务器，而是一条双方可达的信令往返通道。**

---

## 1. Piik 的真实架构（三层）

### 第一层：房间服务跑在房主设备上，不在云上

Piik 的房间服务（房间创建、准入、信令、路由决策）是一个 Go 二进制。它有两种承载方式：

| 形态 | 房间服务在哪 | 公网入口 |
| --- | --- | --- |
| **Hosted（自建站点）** | 一台 Linux x64 服务器上，SQLite 存房间 | 你自己的域名 + 反代 |
| **App + Public invite** | **房主自己的电脑上**，进程内，监听 `127.0.0.1:<port>` | **Cloudflare Quick Tunnel** |
| **App + Local room** | 房主自己的电脑上，局域网可达 | 无（只给同网段的 IP） |

关键源码证据：

- `internal/server/room/` —— 房间权威与持久化（`store.go` / `database.go` / `password.go`）
- `internal/server/signal/` —— 已认证的信令 owner，串行化房间/会话/路由变更
- `internal/server/route/` —— 房间拓扑图，返回资源效果给 `signal`
- `internal/app/` —— "Local/public-link/Site 三种组合、loopback 服务"（模块地图原话），并且明确写着 **"never a second room backend"**（不另起第二套房间后端）

也就是说：**App 模式和 Hosted 模式跑的是同一份房间服务代码**，区别只是前者跑在用户机器上。

### 第二层：Cloudflare Quick Tunnel 提供公网入口

这是整个"发链接就能进"的**承重墙**。铁证在 `internal/app/publictunnel/process.go`：

```go
// App 内打包的隧道客户端位置
func PackagedExecutable() string {
    name := "cloudflared"
    if runtime.GOOS == "windows" { name += ".exe" }
    return filepath.Join(filepath.Dir(executable), "runtime", "tunnel", name)
}

// 实际拉起的命令行
child := exec.CommandContext(ctx, executable,
    "tunnel",
    "--config", configPath,
    "--no-autoupdate",
    "--loglevel", "info",
    "--output", "json",
    // Quick Tunnels 默认强制 QUIC；让它在首次边缘失败后回退 TCP
    "--protocol", "auto",
    "--max-edge-addr-retries", "0",
    "--url", localOrigin,          // 形如 http://127.0.0.1:54321
)

// 从 cloudflared 的 JSON 日志里抓公网地址
var quickOriginPattern = regexp.MustCompile(
    `https://[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.trycloudflare\.com`,
)
```

几个值得记的工程细节：

- **30 秒启动闸门**（`startupTimeout`）：拿不到 `trycloudflare.com` 地址或终端未注册连接，就直接报错退出，不挂死。
- **抓两条日志信号**：正则抓 origin + 等 `"Registered tunnel connection"` 字符串，两者都满足才算就绪。
- **只允许 loopback origin**：`validateLocalOrigin()` 强制要求 `http://127.0.0.1:<port>`，不允许绑 `0.0.0.0`。隧道对外只暴露这一个本地房间服务。
- **协议回退**：`--protocol auto` 让 QUIC 失败时退 TCP——注释里明说了原因。
- **随机域名、随进程生灭**：官方文档原话——"The random `trycloudflare.com` origin lasts only for that App run; a later launch creates a new temporary link. Cloudflare Quick Tunnels provide no uptime guarantee."

> **为什么这招能绕过 CGNAT？** 因为它是**出站**连接。cloudflared 主动连 Cloudflare 边缘，NAT 允许出站；边缘把公网请求反向隧道回房主设备。房主**不需要公网 IP、不需要端口映射、不需要入站可达**。
> 这一条恰好正对着 TicketForTwo 在 PLAN.md §9 里记录的死穴："手机蜂窝网在 CGNAT 后不能入站"。

### 第三层：媒体走 WebRTC P2P，在隧道之外

官方文档原话：

> "**Public invite** uses the packaged Cloudflare Tunnel helper to expose that same room service at a temporary HTTPS address. **Picture and sound travel between participants (P2P), outside the tunnel.**"

隧道**只**承载：页面加载（HTTPS）+ 信令（WSS）。一旦 ICE 打通，SRTP 直接两端互发，不经过 Cloudflare，也不经过 Piik 服务器。所以延迟与服务器所在地无关。

### STUN 自建，TURN 被明确移除

- `STUN_URLS` + `STUN_LISTEN_HOST`：**进程内自建 STUN 监听**（UDP 3478），不依赖 Google/Twilio 的公共 STUN。
- 可选 `NAT_PREDICTION_ENABLED`：额外绑 3479/3480 做 NAT 类型预测增强。
- `TURN`：**配置变量已被移除，且留空也会导致启动失败**；TCP 3478 / 5349 / TURN relay 段全部保持关闭。

官方端口表：

| 端口 | 作用 | 归属 |
| --- | --- | --- |
| TCP 80/443 | HTTPS / WSS 反向代理 | 公网 |
| UDP 3478 | 内置 STUN | 公网 |
| UDP 3479/3480 | NAT 预测（可选） | 公网 |
| UDP 7882 | SFU 媒体转发（可选，官方 demo 站点未启用） | 公网 |
| TCP 8787 | Piik 应用本体 | 私有 |

---

## 2. 为什么"发链接就能看"——两种信令的本质差别

| 维度 | Piik | TicketForTwo（当前） |
| --- | --- | --- |
| 信令通道 | 房主设备上的 WebSocket 服务，经隧道公网可达 | **没有通道**，SDP 塞在 URL `#hash` 里 |
| 进房往返 | **1 次**（观众打开链接 → 自动交换 SDP/ICE → 出图） | **2 次**（房主发 offer 链接 → 观众生成 answer → **人工回传** → 房主点开） |
| 握手耗时 | 秒级 | PLAN.md §10 记录约 **20–40 秒** |
| 链接体积 | 短（只有房间标识） | offer token **1939 字符**（实测），微信有折叠/截断风险 |
| ICE 重连 | 可（信令通道在，能重发 offer） | **不能**（PLAN.md §10 明确"故意不调 `restartIce()`"） |
| 观众端页面 | 房主设备提供，经隧道 | 静态托管（当前在 Qoder） |
| 媒体路径 | WebRTC P2P，无 TURN | WebRTC P2P，无 TURN |
| 公网依赖 | Cloudflare Quick Tunnel（免费） | 无（但也没有公网可达性） |

**一句话：Piik 拿"房主设备 + 免费隧道"换掉了 TicketForTwo 的"人工两轮回传"。**

---

## 3. 关于你说的"依赖 QODER 的服务器"

打开 `viewer/.双人票 · 观众端.qoder.site` 验证过了，它是一个**部署清单 JSON**：

```json
{"version":1,"name":"双人票 · 观众端","actionId":"...","projectId":"...",
 "siteId":"...","deploymentId":"...","artifactSha256":"...",
 "indexSha256":"...","releaseId":"...","indexHtml":"PCFkb2N0eXBlIGh0bWw+..."}
```

`indexHtml` 是 base64 的观众端页面本体。所以 **`.qoder.site` 是 Qoder 提供的免费静态站点托管**，用途只有一个：给观众端 HTML 一个 HTTPS 地址（WebRTC 要求 HTTPS 或 localhost）。

> 该站点已于 2026-09-25 删除，这个清单文件也随之下架（观众页现在由手机 APK 直接发出，
> 不再需要"先放个地方"）。文件内容还在 git 历史里，本节那段核对过程照样可复查。

**它不是"依赖"，更不是服务器**——它无状态、不参与信令、不碰媒体。换成 GitHub Pages / Cloudflare Pages / Vercel / 任意对象存储都一样。

真正缺的是：**一条双方可达的实时信令通道**。这才是 Piik 与你现在的差距所在。

顺带一提：`RtcEngine.kt` 里那三个 STUN 是 `stun.l.google.com:19302` / `stun1.l.google.com:19302` / `global.stun.twilio.com:3478`——**这两个域名在国内网络下都不稳定**。ICE 拿不到 srflx 候选，跨 NAT 就必然失败。Piik 是自建 STUN，完全不吃这个亏。这条是独立于信令之外、必须一起解决的。

---

## 4. 三条可选重构路线

### 路线 A｜完整复刻 Piik：App 内打包 cloudflared + 本地房间服务

在 Android App 内起一个极轻的信令/房间服务（Ktor / NanoHTTPD 都够），再用打包进 App 的 `cloudflared` 把它暴露成 `https://随机.trycloudflare.com`。

**可行性已验证**（不是理论）：

- Termux 社区实测：直接下 `cloudflared-linux-arm64` 就能在 Android 上跑（Go 静态链接，不依赖 glibc）。
- 现成 Flutter 插件 `cloudflared_tunnel` 已把 cloudflared 预编译进 Android（arm64-v8a / armeabi-v7a），并用**前台服务保活**——证明这条路工程上成熟。
- 开源项目 `Droidploy` 展示了标准做法：把二进制放到 `jniLibs/arm64-v8a/libcloudflared.so`，用 `ProcessBuilder` 启动。**这是绕开 Android 10+ W^X 限制的正当手法**（只有 `nativeLibraryDir` 这种只读目录里的可执行文件才允许执行），并需要 `useLegacyPackaging = true`。

**代价：**

| 项 | 影响 |
| --- | --- |
| APK 体积 | cloudflared 单二进制约 +20–30MB（可只留 arm64-v8a） |
| 电量与保活 | 必须常驻前台服务；Android 后台管理会反复杀 |
| 稳定性 | Quick Tunnel **无 uptime 保证**，域名每次启动都变，中途断线就换地址 |
| 合规风险 | 打包并执行第三方可执行文件，部分应用商店会关注 |
| 实现复杂度 | 进程生命周期、日志解析、端口分配、失败恢复，都要自己写 |

**判断：** 这是"最像 Piik"的方案，但也是工程量最大、稳定性最差的一条。如果目标是"自己用 + 给朋友用"，可以接受；如果要做成产品，Quick Tunnel 的隐式失效是个长期痛点——Piik 官方自己也在文档里建议"需要持久可用性就用配置好的 Site"。

### 路线 B｜Cloudflare Worker + Durable Objects 做纯信令中继 ⭐ 推荐

不搬服务器，只补那条缺失的**信令通道**。用一个 Worker + 一个 Durable Object 类实现"房间 + WebSocket 广播"。

**免费额度足够（已核实 2026 年现行政策）：**

| 项 | Workers 免费版 |
| --- | --- |
| 请求 | **100,000 / 天**（含 HTTP、RPC、WebSocket 消息、alarm） |
| Durable Objects | **可用**（2025-04-07 起开放到免费版；仅限 SQLite 后端） |
| 计算时长 | 13,000 GB-s / 天 |
| WebSocket 消息计费 | 按 **20:1** 折算成请求（小消息实时通信有折扣） |
| 连接能力 | 单个 DO 可承载**数千个** WebSocket 连接 |

配合 **WebSocket Hibernation API**，连接空闲时不计 duration——一个 1v1 房间的常态占用几乎为零。一次分享按 100 条信令消息算，折算 5 个请求，**一天 10 万次分享都在免费额度内**。

**架构：**

```
房主 App  ──WSS──┐
                 ├──► Cloudflare Worker（路由）──► Durable Object（房间状态）
观众浏览器 ──WSS──┘
       │
       └──────► WebRTC P2P（SRTP，直连，不经过 Cloudflare）
```

**收益：**

- 进房从"两轮回传、20–40 秒"变成 **Piik 同级的一次点击**。
- 观众端页面可以和 Worker 一起部署（Workers 静态资源绑定），**一个 `wrangler deploy` 同时解决页面托管 + 信令中继**，Qoder 那份部署可以退役。
- 地址永久固定（`https://xxx.workers.dev`），不像 Quick Tunnel 每次都变。
- `SignalingChannel` 抽象你已经在 PLAN.md §9 里设计好了——**只替换这一个实现**，观众端"回传应答"那一屏整块删除。
- `restartIce()` 从此可以真正启用（新 offer 有通道投递了）。

**代价：**

- 需要注册一个 Cloudflare 账号（免费），部署一次 Worker。
- 信令经过第三方边缘节点。**但信令只传 SDP/ICE 文本，不含任何媒体帧**；如需更强保证，可以在 SDP 之上再套一层端到端加密（房间口令派生密钥）。
- 严格来说这确实是"引入第三方服务"——这一点需要你拍板，因为它推翻了 PLAN.md §9 里"不引入第三方服务"的前提。

### 路线 C｜混合：同网走零服务器，异地自动带中继

保留现在的链接内嵌 SDP 作为**同网/热点**路径（零依赖、必成），检测到跨网时自动切换到 Worker 中继。

**判断：** 复杂度最高，收益最小。两条路径都要维护、都要测。如果你已经决定引入 Worker，路线 B 单独一条就够了——它在同网下同样工作（同网也能连 Worker，只是绕了一圈，延迟增加一个 RTT，可接受）。

### 附带必修项：STUN

无论选哪条路线，`RtcEngine.kt` 的三个公共 STUN 都应该换掉：

- 最省事：换成国内可达的公共 STUN（各家云厂商都有提供）。
- 最彻底：学 Piik，**在 App 内起一个 STUN 监听**（Android 上实现量不大，只需响应 Binding Request 并回 XOR-MAPPED-ADDRESS）。这样完全不吃任何外部服务。
- 折中：路线 A 的 cloudflared 隧道如果是自建站点形态，可以顺手用服务端的 STUN。

---

## 5. 动手前必须先验证的 5 件事

| # | 闸门 | 为什么必须先验 |
| --- | --- | --- |
| 1 | **CGNAT 下 cloudflared 是否真能起隧道**（路线 A 专属） | 手机蜂窝网 + 前台服务 + 省电策略，实际存活率只有真机能答 |
| 2 | **cloudflared 二进制在目标 ABI 上是否可执行**（路线 A 专属） | `jniLibs` + `useLegacyPackaging=true` 这套在 AGP 9 / minSdk 33 下要重新确认 |
| 3 | **Worker + DO 的 WebSocket 在真实运营商网络下能否稳定持有** | 长连接在移动网络下的存活、重连策略需要实测 |
| 4 | **国内可达的 STUN 能否稳定拿到 srflx** | 这是跨 NAT 成功的唯一前提，比信令更重要 |
| 5 | **真实异地打洞成功率**（PLAN.md §8 那条仍未关闭） | 无论走哪条路线，P2P 失败率决定要不要引入 TURN 兜底 |

---

## 6. 推荐结论

1. **先把认知纠正过来**：Piik 不是"没有服务器"，而是"服务器在房主设备上 + 免费出站隧道做公网入口 + 媒体 P2P"。这个组合是它的全部秘密，**没有任何黑魔法**。
2. **优先做路线 B**。它用最小的改动（替换一个 `SignalingChannel` 实现 + 一次免费部署）拿回 Piik 的核心体验——单轮进房、可重连、链接短。而且它**不解决**跨 NAPT 打洞失败这个物理问题，路线 A 同样不解决，所以复杂度省下来是净收益。
3. **路线 A 作为后续加分项**：当用户量上来、或者需要完全脱离任何第三方时再考虑。它的最大价值是"连信令都放在自己设备上"，但换来的是体积、电量、不稳定域名三笔账。
4. **STUN 单独拉出来先修**（第 4、5 项闸门），这条的性价比比信令改造还高——**很可能你现在"网络延迟差"的直接原因就在这里，而不是信令方式。**
5. PLAN.md §9 的 `SignalingChannel` 抽象设计是对的，**保留它**。它正是为今天这个决策准备的。

---

## 附：本报告的证据来源

| 结论 | 来源 |
| --- | --- |
| cloudflared 命令行、正则、超时、loopback 校验 | `internal/app/publictunnel/process.go`（已通读） |
| Public invite 定义、P2P 走隧道外、无 SFU/TURN 回退 | `cmd/piik-app/README.md`（Modes 节） |
| 端口表、STUN 自建、TURN 移除 | `docs/standards/configuration.md` |
| 房间/信令/路由模块归属 | `docs/standards/engineering.md`（Module Map） |
| 三种模式差异、欢迎页行为 | `docs/guide/getting-started.md` |
| Worker 免费额度、DO 免费可用、WS 20:1 折算 | Cloudflare 官方 Pricing 文档（2026 现行） |
| cloudflared 可在 Android 运行 | Termux 实测博客、`cloudflared_tunnel` Flutter 插件、`Droidploy` 项目 |
| 本项目现状 | `SignalingCodec.kt`、`RtcEngine.kt`、`CallSession.kt`、`docs/PLAN.md` §9/§10、`viewer/.双人票 · 观众端.qoder.site`（站点已删，仅存 git 历史） |
