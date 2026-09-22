# 安卓屏幕分享 + 连麦 · 方案（待确认）

> 状态：**方案待你确认**，尚未写任何产品代码。效果图（可交互 HTML）在方案定稿后出。
> 日期：2026-09-22

## 0. 已定的四个选择

| 维度 | 你的选择 |
| --- | --- |
| 实时底座 | **纯 P2P，不要媒体服务器** |
| 连麦范围 | 房主 + **1 位观众**双工 |
| 观众端 | 网页 viewer + **同一个 APK 也能当观众** |
| 玻璃底座 | **backdrop（原 AndroidLiquidGlass）**——初版选 haze，看过效果图后改回，见 §6.1 |
| 信令通道 | **一期零服务器：链接自带 SDP + 应答回传**（二期再考虑 ~100 行 WebSocket 中继） |


先说一个好消息：**你选的两个组合是自洽的。** 1v1 恰好是纯 P2P 里唯一不折腾的规模——一条 `RTCPeerConnection`、双向、不需要 SFU、不需要 mesh、不需要树形转发、不需要 simulcast。如果你选了"全员可开麦 + 纯 P2P"，那条路要额外做转发树（Piik 的 ADR-0005 那套），成本翻倍且不稳。

## 1. 为什么必须自研 App

- [Chromium #40418135](https://issues.chromium.org/issues/40418135)（标题 "Android doesn't support screen capturing"）、[WebRTC #42228523](https://issues.webrtc.org/issues/42228523)：Android 上的浏览器至今没有 `getDisplayMedia()`。**任何纯网页方案都无法让安卓手机当分享端**，这是 Piik 的 `native/capture/` 只覆盖 Win/Mac/Linux 的根因。
- 所以采集必须是原生 `MediaProjection`，而观众端浏览器只负责**收**——收流是支持的。

## 2. 总体架构

```
┌──────────────── 房主手机（Android）────────────────┐
│ MediaProjection                                    │
│   ├─ VirtualDisplay ──► Surface ──► ScreenCapturer │
│   │                        Android (libwebrtc)     │
│   │                              └─► 视频轨(H.264)  │
│ ├─ AudioPlaybackCapture(API 29+) ──► 自定义源 ─► 屏幕音频轨(Opus)
│ └─ AudioRecord(VOICE_COMMUNICATION) ─────────────► 麦克风轨(Opus)
│                                     ▲ AEC / NS / AGC│
│ 下行：观众麦克风 ◄───────────────────┘              │
│   由 libwebrtc AudioTrack 播放（作为 AEC 参考）      │
└──────────────┬─────────────────────────────────────┘
               │  ① 信令通道（只传 SDP/ICE 文本，不碰媒体）
               │  ② 媒体：SRTP over UDP，STUN 打洞，无 TURN
               ▼
┌──── 观众：手机/电脑浏览器 ────┐   或   ┌── 观众：同一个 APK ──┐
│ RTCPeerConnection            │        │ 同一条 P2P 通路，角色 │
│ livekit-free / 裸 WebRTC 网页 │        │ 翻转，复用同一套代码   │
└──────────────────────────────┘        └─────────────────────┘
```

**技术栈选型**

| 层 | 选型 | 理由 |
| --- | --- | --- |
| 语言/UI | Kotlin + Jetpack Compose | 与 HaoAI 同栈，玻璃套件与既有经验可复用 |
| 实时 | 预编译 libwebrtc [`io.github.webrtc-sdk:android`](https://github.com/webrtc-sdk/android) v150.7871.01（MIT） | 自带 `org.webrtc.ScreenCapturerAndroid`，采集/编码/AEC/拥塞控制全白送。备选 [GetStream/webrtc-android](https://github.com/GetStream/webrtc-android)（Apache-2.0）做 PeerConnection 生命周期封装 |
| 视频编解码 | **H.264 为唯一主链路**，Profile 走 Constrained Baseline | iOS Safari ≤17 不能解 WebRTC HEVC（[WebKit 204283](https://bugs.webkit.org/show_bug.cgi?id=204283) 在 Safari 18.0 才落地），AV1 在 iOS 覆盖率约 33%。H.265/AV1 只做"观众是 Chrome 时"的可选加分项 |
| 音频 | Opus，麦克风 48k / 屏幕音频 48k 双轨 | 见 §4 |
| 玻璃 | [`io.github.kyant0:backdrop`](https://github.com/Kyant0/AndroidLiquidGlass) 2.0.x（Apache-2.0） | HaoAI 已在用同一库，`ui/common/Glass.kt` 那套组件可直接搬；见 §6.1 |
| 网页 viewer | 单文件 HTML + 裸 `RTCPeerConnection`，无框架无构建 | 便于塞进任意静态托管，也便于本地起服务调试 |
| minSdk | **建议 33（Android 13）**，原提 29 | 两条约束打架：AudioPlaybackCapture 要 29+，但 backdrop 的 lens 折射与 Highlight 要 **33**（blur 要 31）。观众主路径是浏览器不受影响，只有装 APK 当观众的朋友被门槛挡住 ⇒ 取 33。详见 §6.1 |
| 目标/编译 | targetSdk 36，compileSdk 37，AGP 9.4.0 / Kotlin 2.4.10 / Compose BOM 2026.09.00 | 对齐 HaoAI 已验证可用的基线 |

## 3. 平台硬限制（不可绕过，必须设计进 UI）

1. **Android 15 QPR1+ 锁屏即自动停止投屏**。AOSP `MediaProjectionManager` 的 `onStop()` 触发条件明确包含"屏幕被锁定"。
   → 产品形态必须是**亮屏分享**。"手机锁屏揣兜里朋友还在看"做不到，一期不要为此设计任何 UI。
2. **Android 14+ 每次分享都要重新点一次系统授权**；复用 `createScreenCaptureIntent` 的 Intent 再次 `getMediaProjection()` 直接抛 `SecurityException`。
3. **授权弹窗默认提供"整个屏幕 / 单个应用"二选一** → 选错朋友就只看到你的 App 窗口。UI 里必须有一步明确引导，并在检测到"单应用来源"时给出可见提示。
4. **必须先授权、再起前台服务、再 `getMediaProjection()`**，manifest 需 `foregroundServiceType="mediaProjection"` + `FOREGROUND_SERVICE_MEDIA_PROJECTION`；`microphone` 类型受 while-in-use 限制 ⇒ **不能从后台拉起分享**。
5. **Android 15+ 分享期间通知内容被系统遮蔽**；系统状态栏有大号"停止分享"chip，用户随时可从系统侧切断。
6. **画面黑屏 / 声音采不到的固有情况**：`FLAG_SECURE`/DRM 内容（银行 App、Netflix、部分游戏登录页）是纯黑；对方 App 设 `allowAudioPlaybackCapture="false"`（取最严格策略）就拿不到它的声音——游戏内建语音、Discord 大概率属于此类。
7. **你自己的 App 界面会出现在分享画面里**（MediaProjection 镜像整个 display），包括 `TYPE_APPLICATION_OVERLAY` 悬浮窗。
   → 正解：控制岛用悬浮 overlay 且默认在"分享预览"里可见；或用 Android 15 的 `View.setContentSensitivity()` 把自己藏掉。需要真机实测确认哪条路在你的游戏场景里更好看。
8. **发热与温控**：持续采集 + 硬编 + 上行，多数机型 10–20 分钟后会因温控降帧（*推断，需实测*）。默认档必须保守：720p30 @ 1.5–3 Mbps，而不是 1080p。

> 第 3、6、7、8 条都要求"分享前预览 + 分享中自检 + 明确的失败文案"，不能只做一个"开始分享"按钮了事。

## 4. 音频与连麦（这里有一个真坑）

**双轨设计**：房主推 **① 麦克风轨** 和 **② 屏幕音频轨** 两条独立轨道，在**观众端混合播放**，而不是房主端混成一路。

- 为什么不在房主端混音：麦克风轨必须走 libwebrtc 的 `AudioDeviceModule`（`AudioSource.VOICE_COMMUNICATION`，[WebRtcAudioRecord.java](https://chromium.googlesource.com/external/webrtc/+/refs/heads/main/modules/audio_device/android/java/src/org/webrtc/voiceengine/WebRtcAudioRecord.java)）才能吃到平台/libwebrtc 的 AEC。如果先把屏幕 PCM 混进这条采集链，混音点在 AEC 之后还是之前会直接决定啸叫与否，是一个极难调的坑。Piik 之所以能"mix PCM before Opus encoder"，是因为它的原生侧自己完整拥有整条音频图（`internal/app/nativeaudio/encoder.go`），我们没有那个前提。
- **AEC 生效条件**：下行（朋友的声音）**必须交给 libwebrtc 自己的 AudioTrack 播放**，不要改用 `MediaPlayer`/`SoundPool`——AEC 的 render reference 只认 libwebrtc 的播放路径。这是双工连麦不啸叫的第一原则。
- **顺带躲开的一个坑（推断，需实测验证）**：屏幕音频走 AudioPlaybackCapture 会把系统混音里的媒体声再采一遍。libwebrtc 播放远端声音用的是 `USAGE_VOICE_COMMUNICATION`，而可采范围只有 `MEDIA/GAME/UNKNOWN` → 理论上不会形成"朋友声音被二次采回去"的延迟回声。**这条必须真机验证，不能当定论。**
- 观众端浏览器：`getUserMedia({audio:{echoCancellation:true, noiseSuppression:true, autoGainControl:true}})`，并在 UI 上主动劝戴耳机。
- 硬件 AEC 可用性参差：`JavaAudioDeviceModule` 默认在设备上报支持时启用硬件 AEC/NS，否则退回 AEC3。Flyme/魅族 的音频 HAL 是否有效需要实测；实测啸叫时的开关是 `setUseHardwareAcousticEchoCanceler(false)`。

## 5. 信令：纯 P2P 唯一没有解决的问题（**需要你定**）

你选的"不要媒体服务器"我完全能做到——但**信令必须有某个通道**，用来交换 SDP 与 ICE 候选。信令只传几百字节到几 KB 的文本、不接触任何画面和声音，严格意义上它不是媒体服务器。可这是纯 P2P 的固有约束，绕不过去。四条路我列在 `PLAN-questions` 里让你选。

技术细节上无论选哪条都要处理：
- **必须等 ICE gathering 完成**（约 2–3s）才能生成邀请，否则链接/二维码里缺 srflx 候选，跨网必不通。
- **候选裁剪**：丢弃 IPv6 link-local 与 mDNS `.local` 候选（跨网无意义且泄隐私），host + srflx 上限 6 条。这决定 SDP 能否塞进 URL / 二维码。
- **观众也要可达**：对称 NAT 在**任意一端**都会导致打洞失败。

**关于失败率，我必须把话说清楚**：没有 TURN 就一定有连不通的比例。业界常引用"约 10–20% 的会话必须要 TURN"，但那是厂商博客口径，我没找到可复现的公开测量；国内三大运营商 4G/5G 普遍是 CGNAT，风险显著高于欧美固网。所以设计上必须给"连不通"留体面出口：

1. ICE 失败要有**明确的诊断文案**，不是转圈（借 Piik ADR-0001 的原则：*every attempted route must end in bounded success or a clear failure*）。
2. **同网兜底几乎必成**：两端在同一 WiFi 下只用 host candidate 就能直连——"让朋友连你热点 / 你们到同一个 WiFi"是真有效的建议，要写进失败页。
3. 可选借鉴 Piik 的 **NAT 预测增强**（[ADR-0009](https://github.com/TNTcraftHIM/Piik/blob/main/docs/adr/0009-optional-nat-prediction.md)：三目的地 STUN 探测），把成功率往上抬一截。
4. 一期**不做 TURN**，二期如果失败率真实不可接受，再加一个 coturn 只做兜底中继（那时才真的引入"中继"，但只在 P2P 失败时启用）。

## 6. UI / 视觉

### 6.1 玻璃底座：回到 backdrop（AndroidLiquidGlass）

第一版选了 haze，看过效果图后**改回 backdrop**——这与你 HaoAI 里"一律优先 AndroidLiquidGlass 玻璃语言"的既定取向一致。换完的结论有保有废，逐条记清：

**换库没有改变的两件事**

1. **SurfaceView 的内容仍然抓不到。** 这是库无关的硬限制（backdrop 维护者在 [issue #98](https://github.com/Kyant0/AndroidLiquidGlass/issues/98) 亲口确认，#82 是叠在 VideoView 上严重闪烁，haze 文档同一结论）。而我们要显示的恰恰是视频。所以"玻璃只盖非视频区、视频区用 `rgba(0,0,0,.60)` scrim"这条**不随选库改变**——分享中那一屏几乎不会因为换库而变好看，这是该限制的实际后果。
2. **库本身不给高层组件。** `GlassCard`/`GlassGroup`/`LiquidTabRow`/`GlassCapChip`/`LiquidPillButton` 全在仓库的 `:app` demo 模块里，库只暴露 `Modifier.drawBackdrop` + `blur()/lens()/Highlight()/Shadow()/InnerShadow()` 这些原语。

**换库换来的收益**

- **HaoAI 那套是搬代码，不是搬规格。** `ui/common/Glass.kt`（1461 行）+ `ui/common/LiquidSlider.kt`（346 行）本来就建在 `io.github.kyant0:backdrop:2.0.0` 上（`gradle/libs.versions.toml:12,40`），`Modifier.drawBackdrop` + `LayerBackdrop` 的调用形状可以直接复用，省掉 haze 方案下五个原语重写的全部成本。
- 视觉上真正拉开差距的是 `lens()` 的**边缘折射 + chromaticAberration**、`Highlight(width=0.5dp, angle=45°)` 的**边缘光**、`colorControls/vibrancy` 的**提饱和**，以及 demo 的**弹性挤压**。效果图里已按这四样重做。

**新引入的硬门槛（这条会改 minSdk）**

`backdrop/src/androidMain/.../Platform.kt`：`isRenderEffectSupported() = SDK_INT >= 31`（blur），`isRuntimeShaderSupported() = SDK_INT >= 33`（**lens 折射与 Highlight**）。不满足时库**静默 return**，降级成普通半透明层，不报错。

⇒ 原方案定的 minSdk 29 意味着 Android 10–12 用户完全看不到这套视觉。**建议改 minSdk = 33（Android 13）**：观众端主路径是浏览器，不受影响；受影响的只有装 APK 当观众的朋友，而 Android 13 在 2026 年已是可接受的门槛。要保守就取 31（有 blur、无折射与边缘光）。效果图左侧"系统门槛"三档就是在预览这个差别。

**工程红线（从 HaoAI 直接带过来）**：弹窗内禁 `drawBackdrop` 自采样；`appLayer` 子树里禁止新增包裹 Box（会切断采样链，症状是整屏蒙白纱）；参与 `AnimatedContent` 转场的页面根容器必须铺背景色；scrim 归零放 `finally`。另外 `effects/Lens.kt` 对非 `RoundedRectangularShape`/`CornerBasedShape` 的形状是 **抛异常而非降级**（`throwUnsupportedSDFException`），自定义异形玻璃要先验形状类型。

**性能**：每块玻璃 = 一次 GraphicsLayer 离屏 record + 一条 RenderEffect 链 + 全屏像素 AGSL 重算，而我们是**跟硬件编码器抢 GPU 带宽**的场景。仓库无官方基准，issue #41 有三星低端机滚动卡顿的未决报告。约束：同屏玻璃块 ≤ 3，分享进行中把 blurRadius 与折射强度往下收，低端机准备"无玻璃"Material 兜底开关（效果图里已做这个开关）。

### 6.2 设计 token 来源

[awesome-design-md](https://github.com/VoltAgent/awesome-design-md) 是真把 74 份 `DESIGN.md` 抽成机器可用 token 存在仓库里（`design-md/<slug>/DESIGN.md`，64/74 带 YAML front matter），但**因为全抽自公开营销页，Discord/Zoom/YouTube/Netflix/WhatsApp/Meet 一个都没有**；全库**没有 motion/duration token**（只有 Starbucks 给了 `cubic-bezier(0.32,2.32,0.61,0.27)` + `0.2s ease`）；搜 `mute|latency|waveform|PiP` **零命中**。所以通话界面的静音色语义、网络质量条、"谁在说话"指示只能我们自己定。

能白拿的三份，刚好互补：

| 用途 | 取值 | 来源 |
| --- | --- | --- |
| 视频区底色 | `#000000`（明确专用于 video player 背景） | [Apple](https://getdesign.md/apple/design-md) |
| 控制浮层 scrim | `#000000 @ 60%`（"behind modal / video-overlay surfaces"） | [Figma](https://getdesign.md/figma/design-md) |
| 三层暗面 | `#121212 / #181818 / #1f1f1f` | [Spotify](https://getdesign.md/spotify/design-md) |
| 主文 / 次文 | `#FFFFFF` / `#B3B3B3`；暗底强调换 `#2997FF`（`#0066CC` 在暗底会消失） | Spotify / Apple |
| 圆形图标按钮 | **44×44dp**（Apple `button-icon-circular`） | Apple |
| 半透明 chip | `#D2D2D7 @ ~64%` | Apple |
| 按压反馈 | `scale(0.95)` + 玻璃弹性 spring | Apple + HaoAI 现成参数 |
| 圆角 / 间距 scale | `xl 16px`、`pill 9999px`；4px 基准 4/8/12/16/20/24/32/48/96 | Apple |
| 语义色 | 发言中 `#1ED760`、错误/断连 `#F3727F`、弱网警告 `#FFA42B`、信息 `#539DF5` | Spotify（mute 语义库里没有，我们补） |
| 底部常驻控制栏 | "Now-playing bar maintained at all sizes" → 映射为常驻控制岛 | Spotify |
| motion | **无来源可抄**，自定（倾向 Material 3 emphasized 规格 + Starbucks 那条 spring 做弹性） | — |

字体：SF Pro、SpotifyMixUI、figmaSans 全是专有，**不可打进 APK**；中文用系统默认，不打包 MiSans 之类（授权要另查）。
许可：仓库 MIT 只覆盖文档本身，README 免责声明明说"抽取的是公开可见 CSS 值，不主张任何站点视觉识别的所有权"→ **对外宣传不要打"XX 风格"**。

### 6.3 玻璃用在哪（受 SurfaceView 限制约束）

可以放玻璃：顶栏、控制岛、画质/音频弹层、抽屉、邀请面板、房间码卡、观众列表。
**不能**放玻璃：视频画面本身（SurfaceView 抓不到）。视频区之上统一用 `#000000 @ 60%` scrim + 玻璃浮层。
另外把 HaoAI 那四条经验直接带过来当工程红线：弹窗内禁 backdrop/haze 自采样、玻璃子树里别乱加包裹 Box 断采样链、参与转场的页面根容器必须铺背景色、scrim 归零放 `finally`。
性能：同屏玻璃块 ≤ 3，`HazePerformanceMode.Balanced` 起步，分享中（编码器在跑时）自动降到 `Performance`。

## 7. 里程碑（含可行性闸门）

**M0 · 可行性闸门（必须先跑，任何一条崩了整个方案要改）**
1. `MediaProjection → libwebrtc 视频轨 → Chrome/Safari` 出图，帧率稳定、无花屏。
2. 麦克风 + 屏幕音频双轨 + **外放**连麦不啸叫（真机：你的魅族 20 Pro + 朋友的手机；默认走模拟器不碰真机，需要真机时我先问你）。
3. 你和你朋友**真实网络组合**下的打洞成功率：同 WiFi / 你家宽带+对方 4G / 双向 4G 三种各测 10 次。

M0 只做三件事，不写任何产品代码、不做 UI。

**M1 · 能分享给一个人**：前台服务 + 授权引导 + 房间码 + 邀请链接 + 网页 viewer 收流 + 悬浮"停止分享"岛。
**M2 · 连麦**：观众开麦、AEC 调通、"对方在说话"指示、耳机引导。
**M3 · 原生 viewer**：同一个 APK 角色翻转（复用同一套 `Peer` 代码），含画中画/后台音频。
**M4 · 鲁棒性**：弱网降档、重连、ICE 失败诊断页、码率/延迟/丢包实时读数、`setContentSensitivity` 与 overlay 取舍实测。
**M5 · UI 打磨**：搬 HaoAI 的 backdrop 玻璃套件（`Glass.kt` + `LiquidSlider.kt`）→ 按本项目改 `lens`/`Highlight` 参数 → 明暗两态 + 弹性按压 + 低端机无玻璃兜底开关。

工作量判断（*估算，不是承诺*）：因为砍掉了 SFU / 多人 / mesh / simulcast，这条路大概是"LiveKit 路线"的 **2–2.5 倍**，而不是通用场景下的 4 倍。最大单项成本是"裸 libwebrtc 自己管 PeerConnection 生命周期 + ICE 状态机 + 重连"。

## 8. 尚未验证 / 有风险的部分（诚实清单）

- 打洞成功率在你的真实网络下是多少 —— 只有 M0 第 3 项能回答，现在没人知道。
- 双轨音频是否真的不会二次采到远端声音 —— §4 标注为推断。
- Flyme 上硬件 AEC 是否有效 —— 需实测。
- minSdk 取 33 还是 31 —— 取决于你愿不愿意把 Android 10–12 的朋友挡在 APK 观众之外。
- 温控降帧发生在第几分钟、降多少 —— 需实测。
- 预编译 libwebrtc AAR 在 Android 16 上的采集兼容性 —— 未查。
- Android 14+ "单应用分享"选项在所有厂商 ROM 上的表现是否一致 —— 未查。

## 9. 信令已定：一期零服务器（链接自带 SDP + 应答回传）

你选了"链接自带 SDP，双向回传"。落到实现上是这几条：

- 房主侧：`createOffer()` → **等 ICE gathering 完成**（约 2–3s，UI 要有进度而非直接出链接）→ 裁剪候选 → `SDP → base64 → URL 的 #hash`（hash 段不进服务器日志，比 query 好）。
- 观众侧：浏览器打开链接 → 解码 offer → `setRemoteDescription` → `createAnswer()` → 同样打包成一条**应答链接**，界面上一键"复制并回传"。
- 房主侧：粘贴/点开应答链接 → `setRemoteDescription(answer)` → 直连建立，媒体全程两端直达。
- 抽象成 `interface SignalingChannel { suspend fun offer(): String; suspend fun awaitAnswer(): String }`，二期换自建 ~100 行 WebSocket 中继时只替换这一个实现，观众端"回传应答"那一屏整块消失。

**代价要认：**建立分享要两次消息往返、约 20–40 秒。这跟 Piik 那种"复制链接朋友就进"比是明显退步，是"不要服务器"的直接后果。

**尺寸风险（未实测，属于 M0 要验的第一批）：**4G+WiFi 双栈下 SDP 可能到 2–2.5KB，base64 后约 3KB，作为 URL 塞进微信消息在长度上是可行的，但需要实测确认：① 候选裁剪到 6 条以内是否还能保证跨网连通；② 微信/QQ 对超长文本链接是否会自动截断或折叠成卡片（折叠后 hash 段可能被丢掉）。若②不成立，就自动退到"二维码互扫"那条备选路（效果图里已保留入口）。

## 10. M0 闸门实测结果（2026-09-22，t2test 模拟器 ↔ 宿主浏览器）

**① 手机采集 → 直连 → 浏览器出图：通过。** 硬数据：

| 指标 | 实测 |
| --- | --- |
| 采集几何 | display 1080×2400 → capture **810×1800@30**（scale 0.75，取偶） |
| 浏览器解码 | `framesDecoded=1472`，`frameWidth×Height=810×1800`（与采集一致） |
| 播放状态 | `readyState=4`，`currentTime=29.97s`，画面确实在动 |
| 传输 | candidate-pair `succeeded`，**RTT 2ms**，抖动缓冲 **8.6ms**，收 774KB |
| 质量事件 | `nack=0`、`pli=0`（同机回环，无拥塞） |
| 信令链长 | offer token **1939 字符**（SDP 含 10 条候选，裁剪到 6） |

**② 连麦（双向语音）：轨道与媒体通路已验证；回声效果未验证。**
内置浏览器 `getUserMedia` 返回 `NotAllowedError`，改用**真实 Chromium + 假媒体设备**
（`scripts/run_viewer_edge.py`，`--use-fake-ui-for-media-stream --use-fake-device-for-media-stream`）
跑通闭环，实测：App 侧 `收到对方音频轨`、浏览器 `out:audio bytes=64116`、
浏览器 `in:audio bytes=74000`、`in:video fps=45`，两侧 ICE `COMPLETED`。
⇒ **双向音频通路成立**。但假设备**证明不了回声消除效果**——模拟器 `hwAec=false`，
真机外放连麦是否啸叫仍必须在真机上听。

### ③ 打洞成功率

只覆盖了"同机模拟器 ↔ 宿主浏览器"这一种组合，成功。
跨运营商 / CGNAT / 真实两地的数据仍然为零，必须等真机或两台异地设备。

### 断连处理：已实现并实测

实测轨迹（杀掉对端浏览器进程）：

```
:57.346  ice=DISCONNECTED
:05.360  直连中断且未能自愈。一期没有信令通道，无法自动重连，请重新发起分享。   ← 8.014s 后
:07.379  ice=FAILED                                                        ← 系统自身超时
```

`DISCONNECTED` 不再直接判死，先给 **8 秒自愈窗口**（已协商好的 candidate pair 在
WiFi↔蜂窝切换、NAT 映射过期后常能自己回来），超时才报可行动的失败；
我们的判定早于系统 FAILED 到达，用户看到的是人话而不是卡死。

**故意不调 `restartIce()`**：ICE restart 会改 ufrag/pwd，必须把新 offer 再送一次给对端
才生效，而一期是零服务器、链接靠人工复制粘贴，没有通道投递这份新 offer
（DataChannel 本身就在这条将断的连接上）。调它只是假装在重连。
⇒ 等二期有了 WebSocket 信令中继，再启用真正的 ICE restart。

### 过程中修掉的两个真缺陷

1. **前台服务竞态**（崩溃）：`startForegroundService()` 是异步的，在它真正 `startForeground()`
   之前就调 `getMediaProjection()`，Android 14+ 直接抛
   `SecurityException: Media projections require a foreground service of type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`。
   ⇒ 修成 `ShareService.awaitReady()`：服务上报已进入前台后上层才开始采集。
   **方案 §3 第 4 条原来只写"先授权再起服务再取 projection"，不够，顺序之外还要等就绪。**
2. **offer 里没有候选**（静默失效，最阴的一种）：`emit()` 取了 `onCreateSuccess`
   回调给的 SDP，那是**收集前**的裸 SDP。表现为"链长 1554 字符看着挺正常，但
   `kept=0`"，永远连不通。⇒ 改读 `pc.localDescription`。
   教训：信令负载必须有 `kept/dropped/had` 这类**内容计数**，只看长度会被骗。

### 实测推翻的一条原方案假设

**这个 AAR 没有公开的 Java API 能注入外部 PCM**，所以"屏幕声做成独立第二条音频轨、
在观众端混合"做不到。`JavaAudioDeviceModule.Builder` 里那个 `setAudioBufferCallback`
是 fork 私有、GitHub 上搜不到任何文档的钩子，不能当架构地基。
⇒ 屏幕声降级为独立 spike，三条候选路线待验：① 试 `setAudioBufferCallback`；
② 屏幕声走 DataChannel（MediaCodec Opus → 浏览器 WebCodecs 解码，Safari 覆盖有风险）；
③ 兜底是"外放让麦克风收"（零成本，但 NS 会当噪声压掉一部分）。
双轨设计**仅对麦克风轨保留**（它必须走 VOICE_COMMUNICATION 才有 AEC）。

### 其它实测事实

- 模拟器 `hwAec=false hwNs=false`：不报硬件回声消除，会退回 libwebrtc 的 AEC3。真机需另测。
- **Android 16 的投屏授权对话框默认落在「Share one app」**，要用户主动展开下拉改成
  「Share entire screen」；改完后确认按钮文案从 **Next 变成「Share screen」**。
  授权指引那屏的文案要照这个写。
- MediaProjection 对话框是**系统进程**的窗口，`am force-stop` 我们的 App 不会关掉它，
  残留对话框会让下一次流程错位。
- 单测在 `G:\工作台` 这个非 ASCII 路径下跑不起来：`gradlew testDebugUnitTest` 抛
  `ClassNotFoundException`，但 classpath 里确实带着编译产物目录。原因未定位。
  ⇒ 用 `scripts/run-unit-tests.sh`（JUnitCore 直跑已编译 class）代替，等价。
- 信令格式跨语言字节级校验：`scripts/check-signaling-interop.sh`，
  Kotlin↔浏览器同源 JS 双向 sha256 比对。


## 11. 效果图

`docs/mockup/index.html` —— 单文件零依赖，10 个屏（房主 6 / 观众 4），`#0`…`#9` 深链到指定屏。

本地看：`cd docs/mockup && python -m http.server 8791 --bind 127.0.0.1`，开 http://127.0.0.1:8791/index.html

可交互：点任意元素弹规格与 token 出处；可切 SurfaceView 采样限制、lens 折射四档、系统门槛（API 33+/31/≤30 降级预览）、玻璃/Material 兜底、明暗两态、0.25× 慢放、尺寸标注、弱网/失败注入、亮暗画面、显示比例。

`mockup/shots/s00–s09.png` 是逐屏出图记录（无头 Edge 生成），改版前后对比用。

