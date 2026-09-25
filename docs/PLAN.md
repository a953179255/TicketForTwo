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

> 2026-09-23 更新：真机（魅族 20 Pro / Android 16）跑过两轮，几条已经结账。

**已实测结清：**

- ~~预编译 libwebrtc AAR 在 Android 16 上的采集兼容性~~ → **通**：
  `display=1440x3200 -> capture=1080x2400 @30fps dpi=544`，采集与编码正常出帧。
- ~~Flyme 上硬件 AEC 是否有效~~ → **平台确实暴露了**：`hwAec=true hwNs=true`（模拟器是 false）。
  但这只证明"拿得到"，**消得干净不干净仍要人耳判断**。
- ~~minSdk 取 33 还是 31~~ → 已定 33（backdrop 的 lens/Highlight 门槛）。

**仍未结清：**

- **打洞成功率：两个真机样本，一成一败，且都不可靠。** 一次 `CONNECTED` 后 32 秒掉线
  且无法自愈，一次 `CHECKING` 16 秒后直接 `FAILED`。**但这两次的网络拓扑没有记录**
  （手机走 WiFi 还是蜂窝、电脑是否同一网段），所以只能算"出现了问题"，不算成功率。
- **更正（2026-09-24）**：本清单上一条曾写"已补候选类型分布与无 srflx 告警（commit
  `b5c2cbe`），下次实测判定超时是不是病因"。那句话在写下时就已经失效 ——
  `b5c2cbe` 改的 `SignalingCodec.kt` 在 `a0cc901` 里被整个删掉了，我没察觉。
  现状是：**8 秒收集超时这个前提本身不存在了**（trickle ICE 不再等收集窗口），
  而 STUN 也换成了逐台实测过的国内三台（见 `RtcEngine.stunServers` 注释，22/37/47ms）。
  取而代之的失败诊断是 `rtc/IceProbe`：把双方候选类型与 ICE 轨迹摊到失败屏上，
  由证据决定说"对称 NAT"还是说"你这边 STUN 出不去"。
- 双轨音频是否真的不会二次采到远端声音 —— §4 标注为推断，仍未验证。
- 温控降帧发生在第几分钟、降多少 —— 掉线那次只跑了 32 秒，不够长，未测出。
- Android 14+ "单应用分享"选项在所有厂商 ROM 上的表现是否一致 ——
  Flyme 弹窗的默认项与文案**还没记录**。
- **Flyme 的 adb 安装闸门**：`INSTALL_FAILED_USER_RESTRICTED`，重试第二次才过
  （`/data/local/tmp` 里的 `allowcheck.sh`/`allowres.sh` 就是它的授权流程）。
  以后装机失败先怀疑这个，别怀疑包本身。

## 9. 信令：现状是"手机本机传话员 + 出站隧道"，单轮即入

> **先读这段，再看下面的历史正文。**
>
> 下面那套"链接自带 SDP + 应答回传"**已经作废**（`a0cc901` 推翻，实现代码
> `SignalingCodec.kt` 已删除）。它留在文档里只是决策过程记录，**不是现状**。
> 作废声明原先埋在本节末尾，从上面往下读的人会把过期流程当现行设计 —— 这就是问题。
>
> **现行设计**：房主手机里跑一个极小的 HTTP + WebSocket 传话员（`signaling/SignalHub`），
> 用一条**出站**的 Cloudflare Quick Tunnel（`tunnel/TunnelManager`，重编译进 APK 的
> `libcloudflared.so`）把它挂到一个临时公网 HTTPS 地址；观众点开链接即连上那条
> WebSocket，双方 SDP 与 ICE 候选自动交换，**房主零操作、不再有"回传应答"那一屏**。
> 媒体仍然两端直达（SRTP），隧道只承载控制信息。
> 随之解锁的还有 **trickle ICE**（不再等收集窗口）与网络切换后补发候选的能力。
> 分析与补丁分别见 `docs/PIIK-ANALYSIS.md`、`docs/cloudflared-android-patch.md`。
>
> **换来的代价**：APK 多约 40MB（隧道程序），且整条流程现在**硬依赖** Cloudflare
> 免费隧道 —— 它起不来就发不出邀请，目前没有备用路径。

<details>
<summary>以下是已作废的历史设计（决策过程留档，别照着改代码）</summary>

你选了"链接自带 SDP，双向回传"。落到实现上是这几条：

- 房主侧：`createOffer()` → **等 ICE gathering 完成**（约 2–3s，UI 要有进度而非直接出链接）→ 裁剪候选 → `SDP → base64 → URL 的 #hash`（hash 段不进服务器日志，比 query 好）。
- 观众侧：浏览器打开链接 → 解码 offer → `setRemoteDescription` → `createAnswer()` → 同样打包成一条**应答链接**，界面上一键"复制并回传"。
- 房主侧：粘贴/点开应答链接 → `setRemoteDescription(answer)` → 直连建立，媒体全程两端直达。
- 抽象成 `interface SignalingChannel { suspend fun offer(): String; suspend fun awaitAnswer(): String }`，二期换自建 ~100 行 WebSocket 中继时只替换这一个实现，观众端"回传应答"那一屏整块消失。

**代价要认：**建立分享要两次消息往返、约 20–40 秒。这跟 Piik 那种"复制链接朋友就进"比是明显退步，是"不要服务器"的直接后果。

**尺寸风险（未实测，属于 M0 要验的第一批）：**4G+WiFi 双栈下 SDP 可能到 2–2.5KB，base64 后约 3KB，作为 URL 塞进微信消息在长度上是可行的，但需要实测确认：① 候选裁剪到 6 条以内是否还能保证跨网连通；② 微信/QQ 对超长文本链接是否会自动截断或折叠成卡片（折叠后 hash 段可能被丢掉）。若②不成立，就自动退到"二维码互扫"那条备选路（效果图里已保留入口）。

> ⚠️ **同日已被 `a0cc901` 推翻**：当天稍后落地了"出站隧道 + SignalHub"单轮方案
> （手机本机信令 + Cloudflare Quick Tunnel），本条决策**作废留档**，分析见
> `docs/PIIK-ANALYSIS.md`、补丁见 `docs/cloudflared-android-patch.md`。

**2026-09-23 决策（同日已被推翻，留档）：确认零中继，二期中继暂缓。** 对照 Piik（其"发码即进"= 公网服务器跑 WebSocket 信令中继转发 SDP/ICE，官方 demo 与自托管都必须有一台公网机器）评估后确认：异地单轮物理上离不开一台双方可达的中继机，而当前既无自有公网机器（备用路由器性能不足、手机蜂窝网在 CGNAT 后不能入站），也不引入第三方服务 ⇒ **异地维持两轮链接交换不变**；同网/热点"手机自当中继"的单轮彩蛋也先不做，保持最简。未来候选方案：~150 行 WS 中继（`join`/`sdp`/`candidate` 转发 + 60s 空房回收），待具备自有公网机器（VPS，或家宽入站探测通过：公网 IPv4 / IPv6 防火墙放行 + 常开设备 + DDNS）后可启用，届时再落地上面 `SignalingChannel` 抽象、观众端"回传应答"屏随之退役。

</details>

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
- ~~信令格式跨语言字节级校验：`scripts/check-signaling-interop.sh`~~ ——
  已随两轮信令一并删除（Kotlin↔JS 双向 sha256 比对不再有存在意义）。


## 11. 效果图

`docs/mockup/index.html` —— 单文件零依赖，10 个屏（房主 6 / 观众 4），`#0`…`#9` 深链到指定屏。

本地看：`cd docs/mockup && python -m http.server 8791 --bind 127.0.0.1`，开 http://127.0.0.1:8791/index.html

可交互：点任意元素弹规格与 token 出处；可切 SurfaceView 采样限制、lens 折射四档、系统门槛（API 33+/31/≤30 降级预览）、玻璃/Material 兜底、明暗两态、0.25× 慢放、尺寸标注、弱网/失败注入、亮暗画面、显示比例。

`mockup/shots/s00–s09.png` 是逐屏出图记录（无头 Edge 生成），改版前后对比用。


## 12. 主流程接线（2026-09-22）：三处只有真跑真看才发现的缺陷

M0 那块朴素调试屏换成了玻璃化的正式路由：`MainActivity` 不再建导航栈，
**界面完全由 `CallSession.state` + `role` 推导**。理由不是审美 —— Activity 会被系统回收而
通话不会，一期又必然要中途切去聊天软件复制粘贴链接，返回栈记不住"该显示哪一屏"。

这一轮抓到的三个缺陷，共同点是**结构和日志全绿，只有看像素才露馅**：

1. **深色主题的"玻璃卡"其实是一块黑板。** `glassSurfaceColor` 在深色下把 alpha 乘 1.8 再封顶
   0.94 —— 那个倍数是给 0.16 这类薄表面补对比度的，卡片用的 0.58 走同一条路就被顶到 0.94。
   实测：卡内亮度均值比它所压的背景**暗 9.3**（0.58 档）vs **暗 1.8**（改成 0.26 之后），
   卡内点阵底纹从"完全消失"变成"透得出来"。度量脚本 `scripts/measure-glass.ps1`。
   没有改共享函数的封顶值：CallScreen 顶部条与控制岛是压在任意视频上的 **scrim**，那里就是要接近不透明。

2. **SurfaceView 在 Compose 里被父节点的不透明 background 填平。** 日志明明写着
   `first frame rendered` / `resolution 810x1800`，屏幕上却是一片纯黑，把 ×8 提亮后仍然全黑。
   根因是 `Box(Modifier.background(Ink.Video))` 包住了 `AndroidView { SurfaceViewRenderer }` ——
   SurfaceView 的合成面在窗口之下、靠"挖洞"显示，祖先铺的不透明底色会把洞重新填平。
   去掉那层底色后画面立刻出来。**这一条会伪装成"整条视频链路坏了"**，而实际坏的只有显示。

3. **房主的实时自预览必然是一片黑/残影，所以拿掉了。** 采集源就是当前这块屏，
   把它的画面画回这块屏 = 递归：洞里的内容是"上一帧的自己"。去掉底色后截到的是
   一帧延迟、缩放错位的上一屏残影。现在房主中间是"正在分享 + 会跟着你跨应用"的确认卡，
   观众侧才画远端视频。附带好处：房主端少一路解码和一块 SurfaceView，不跟编码器抢 GPU。
   ⇒ 真要"确认在播什么"，正确做法是抽**一帧静图**，记在 M4 的诊断页里。

另外两处是人称与排版：观众屏那句耳机提示原本是房主口吻（"我的声音会回到他手机"）；
`StatusChip` 的 `maxLines = 1` 会把长句**静默裁成半句话**（截图里裁在"耳机"上），改成 2 行。

### 顺带落地的两件小事

- **控制岛上的数字是真的**：`Peer.collectStats()` 走 `getStats(RTCStatsCollectorCallback)`，
  读选中 candidate-pair 的 `currentRoundTripTime`（单位是**秒**）与两端 candidate 的
  `candidateType`。模拟器↔宿主浏览器实测显示 2–3ms。解析逻辑是纯函数，7 条 JVM 单测覆盖，
  其中一条把"有 RTT 就当在用"的错优先级打了回来 —— `currentRoundTripTime` 在**所有** succeeded
  的 pair 上都有值，不能当证据，必须按 `selected` → `bytesSent` → 有 RTT 的顺序退让。
- `ensureMicTrack` 现在**没有 RECORD_AUDIO 就根本不建音频轨**：libwebrtc 的 AudioRecord 是
  连接时才打开，那时权限被拒会走 SDK 内部的 CHECK 失败路径（可能直接 abort），不是能 catch 的异常。
- 权限链从"固定连问三次"改成"查过再问、每条只单向走完"：重复弹已授予的权限是骚扰，
  而"回调里再检查一遍缺什么"会在用户点拒绝后陷入无限弹框。

## 13. 观众端已部署（2026-09-22）：朋友那条链接现在真的能点

> ⚠️ **legacy（记录于 2026-09-22，次日 `a0cc901` 后失效）**：主流程观众页已迁至
> APK `assets/viewer/`，由 SignalHub 经出站隧道（`*.trycloudflare.com`）现场发出；
> App 不再生成任何 qoder 邀请链接，仓库内 `viewer/public/` 与 `t2.inviteBase`
> 已删除。旧 `#t2=` 链接本就随会话失效。
> **2026-09-25 该站点已删除（用户确认，不可逆）。** 下线前实测线上那份仍是旧协议：
> 73926 字节、`WebSocket` 出现 0 次、STUN 还是 Twilio/Google 那组 —— 它连不上现在的
> 手机，公开挂着只会给出"看着能用、点了必失败"的入口。30 天 65 次 PV 全部集中在
> 09-22～09-24（本人和朋友那几次点）。下面正文仅作历史留档。

`viewer/public/` 发布为 Qoder Sites 静态站（历史）：

- 地址 **`https://ticket-for-two-h27zp47k2zl.qoder.website/`**
- 纯静态、零后端、无数据库；`invite` 信息在 URL 的 **`#` 片段**里，浏览器不会把片段
  发给服务器，所以"页面公开"不等于"分享公开" —— 没有那条链接的人打开只看到一个空页。
- 访问范围已从默认的 private 改成 **public**（用户确认过）。实测：匿名 `curl` 从
  `401 sites_gateway_login_required` 变成 `200`；`index.html` 与 `signaling.mjs` 都 200。
- App 侧用 `-Pt2.inviteBase=<该地址>` 出包，`BuildConfig.INVITE_BASE` 注入，
  邀请屏那条红色"网页版还没上线"提示会自动消失（`linkLive` 驱动）。

### 用真实域名跑通的那一遍

不是本地 dev 副本，是浏览器**直接打开线上地址**：

| 环节 | 结果 |
| --- | --- |
| 页面加载并解析 token | ✅ 截图停在「正在建立直连… 等网络候选收集完」 |
| 应答回灌到手机 | ✅ `收到对方应答，开始建立直连` |
| ICE | ✅ `ice=CONNECTED` |
| 下行视频 | ✅ `in:video bytes=225557` |
| 上行麦克风 | ✅ `out:audio bytes=37516` |

### 部署这一环踩到的两件事

1. **站点目录必须和调试产物分开。** `invite.txt` 里是真实 SDP 与候选地址，原先和
   `index.html` 同目录，直接打包就会把它上传出去。现在 `viewer/public/` 只放可部署的
   两个文件，一次性产物全部进 `.dev/`。
2. **公网页面 POST 本机 127.0.0.1 会被 Chrome 的 Local Network Access 保护掐掉**，
   无头模式没有授权 UI，表现就是"统计一条都没回来"，看起来像页面坏了。
   测试夹具里加 `--disable-features=LocalNetworkAccessChecks` 解决；
   页面本身改成 `?relay=` 可指定回收地址，同源时仍走相对 `/report`。

### 仍未验证

- **真实公网打洞成功率**：这一遍仍是同一台机器上的模拟器 ↔ 宿主浏览器，
  两端都在 `host` 候选上，等于同网测试。§8 那条"你和你朋友真实网络组合下各测 10 次"没做。
- **外放连麦的真实回声**：假媒体设备只能证明轨道双向协商通、包在流动。

## 14. 异地实测矩阵（待执行 — 媒体打洞路径的收尾闸门）

信令已改隧道单轮（`a0cc901`），本矩阵测的是**媒体打洞**：媒体路径在真实异地网络下
从未测过（§10/§13 全是同机回环）。用两台真机 + 微信跑
下表组合，**每格目标 10 次**，按 §10 的格式把数字回填本表：

| 场景 | 次数 | offer 含 srflx | ICE CONNECTED | 首帧时间 | 分辨率/fps | RTT | 端到端总耗时 | 邀请链接原样可达 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 同 WiFi 基线 | | | | | | | | | |
| 跨运营商（如电信↔移动） | | | | | | | | | |
| 一端蜂窝 + 一端 WiFi | | | | | | | | | |
| 微信全程（含卡片化风险） | | | | | | | | | 重点看 `?k=` 凭证与页面加载完好 |

**判定与动作（只修阻断级，不主动加功能）：**

- 微信折叠/截断邀请 → 新邀请是 `https://…trycloudflare.com/?k=…` 短链、无 fragment，风险已大降；若仍被折叠，界面加"请用纯文本发送"指引。
- offer 缺 srflx → 查 STUN 配置（a0cc901 起为国内节点 hitv/bilibili/miwifi，trickle 逐条发送，已无 `MAX_CANDIDATES` 裁剪）。
- 跨 CGNAT 打洞系统性失败 → 如实记录，**本期不上 TURN**；隧道只传信令不传媒体，打洞失败仍无媒体兜底——这组数据决定要不要上 TURN。
- 断连按现有 8s grace 文案真机复验一次（杀对端进程 / 切网）。
- **前置检查**：出包前确认 `app/src/main/jniLibs/<abi>/libcloudflared.so` 在位
  （隧道助手机制与打包条件见 `docs/cloudflared-android-patch.md` 与 build.gradle packaging 注释）。

### 执行打法（一台手机 + 一台电脑，蜂窝流量 ≤200MB）

**角色推荐：手机真机当分享端（装 APK），PC 浏览器当观众端。** 分享端在生产里本来
就是真机；若反过来用模拟器当分享端，QEMU 会再套一层 NAT，打洞失败时分不清是
"网络打不动"还是"模拟器不收入站包"，容易出**假阴性**——该路线只能当备用，失败结论
必须加对照（换向重测）才能采信。**代理/VPN 不能用于本测试**：它不改变两端各自 NAT
的拓扑，测出的 ICE 不是真实路径；全局 TUN 模式还可能把 UDP 吞进隧道，污染结果。

**流量预算—— 先把免费的做完，再花流量：**

1. 同 WiFi 基线 10 次（0 蜂窝流量）：练熟全流程，填矩阵第 1 行。
2. 邀请可达检查（0 蜂窝流量）：链接经微信发出后，电脑上打开**只要出现"正在接入
   房间"卡片且 WS 连上**即证明链接完好——不必等到出画面即可确认 `?k=` 存活。
3. 查家宽与手机卡的运营商是否不同（免费）：不同 ⇒ 下面的蜂窝场次顺带覆盖"跨运营商"行。
4. 蜂窝核心场次：每次**连上、记录约 30 秒即停止分享**（810×1800@30 约 2–4Mbps ≈
   5–15MB/次），跑 5–8 次 ≈ 50–100MB；**快递员用微信** ⇒ 同一轮同时覆盖
   "蜂窝+宽带"与"微信全程"两行。每轮跑完在手机设置里核对该应用的流量消耗。
5. 剩余流量留给失败重试与 8s grace 断连复验（杀观众端进程）。

**数据采集管道（数字不用手抄）：**

- PC 端：`python scripts/dev_viewer_server.py`（默认 `:8792`）作**统计落盘端**；
  观众页从隧道链接打开，夹具自动追加 `&report=1&relay=http://127.0.0.1:8792/report`
  （SignalHub 无 `/report`，统计必须 relay 回本机），每 2s 落 `.dev/reports.jsonl`。
  验"邀请可达"那一行时必须开微信收到的**原样隧道链接**，不能改地址。
- 手机端（插 USB）：`powershell -File scripts\field-logcat.ps1 -Run <n> -Seconds 90`
  → 抓 `Peer/CallSession/RtcEngine` 日志到 `.dev\field\run-<n>.log`，含候选分布
  （`typeSummary` 的 srflx）、wire 长度、`ice=CONNECTED` 时间线。
- 手机需 Android 13+（minSdk 33）；APK：`app/build/outputs/apk/debug/app-debug.apk`。

**覆盖限制（如实标注）：** 单机单卡测不了"双蜂窝跨运营商"最难档，该档标 N/A，
不代表实际网络下必失败，只代表本次测试组合未覆盖。

## 15. 分享设置与仅语音模式（2026-09-23）

需求来自 §14 蜂窝流量实测：想先降画质再开测，以及"不分享画面、只连麦对话"。
首页新增「分享设置」（效果图 `settings` 屏的落地）：

| 项 | 档位 | 默认 |
| --- | --- | --- |
| 分辨率 | 540p / 720p / 1080p（scale 0.5 / 0.75 / 1.0） | 720p（=0.75，810×1800） |
| 帧率 | 10 / 15 / 30 | 30 |
| 码率上限 | 0.3 / 0.8 / 2.0 / 5.0 Mbps | 2.0 Mbps |
| 分享内容 | 画面+语音 / 仅语音 | 画面+语音 |

- **开始分享时生效、场内不可改**：a0cc901 起虽已有隧道信令通道，但中途改分辨率/开关画面需要重新协商、尚未实现——做不到就不假装。持久化在 `SharedPreferences("t2")`，档位列表见 `ShareQuality`。
- **仅语音**：跳过投屏授权与采集，offer 不带视频 m-line；前台服务改用 `microphone` 类型（manifest `mediaProjection|microphone` + `FOREGROUND_SERVICE_MICROPHONE`），否则后台切出会被掐麦克风。两端补了"语音模式/语音对话中"呈现（网页观众页连上 2.5s 无视频轨即判定，声音挂到 `<video>` 上播）。
- 麦克风开关本就在控制岛，不进设置页 —— 重复语义只留一处。
- 流量估算按上限口径：Mbps × 7.5 ≈ MB/分钟；仅语音 Opus ~32 kbps ≈ 0.3 MB/分钟。
- 省流量测试建议档：**540p · 10 帧 · 0.3M ≈ 2–3 MB/分钟**（首页卡片实时显示当前组合与估算）。
- **单轮已落地**：`a0cc901`（SignalHub + 出站隧道，观众点一次链接即入）取代了本文其余部分描述的两轮流程；两轮代码与夹具已随之删除，分析见 `docs/PIIK-ANALYSIS.md`。

## 16. App 内观看从来没通过：观众侧也是"主线程写 socket"（2026-09-25）

异地实测里出现一个此前没解释过的组合：**同一条链接，浏览器观众端能出画面，
手机 App 内观看必失败**。当时界面甩的是旧版写死的那句"一方可能在严格的网络后
（对称 NAT）"，而这句话在 `48ab471` 就已经被基于证据的判定替掉了 —— 也就是说
手机上的包比仓库旧，光凭文案就能确认。

但**"包旧"解释不了这次失败**。旧包与 HEAD 之间只有四个提交，其中两个是文案与
失败屏改版，另两个（`e821174`、`8127d12`）修的是 `SignalHub`，那是**房主侧**才走的
代码。观众侧一条都没沾到。所以真原因在别处，且新版照样会犯。

### 根因

`8127d12` 那个坑在观众侧有一份镜像实现，而且当时漏修了：

- `Peer.onIceCandidate` 把候选 `handler.post` 回**主线程**再交给 listener（Peer.kt:283）；
- 观众端的 listener 就是 `ViewerSession.send()` → `WsClient.send()`；
- 而 `WsClient.send()` 是在**调用方线程上直接写 socket** 的，只用一把锁串行化。

于是每条候选都在主线程抛 `NetworkOnMainThreadException`，`message` 是 null，
旧日志只剩"发送失败：null"，被 `catch (Throwable)` 静静吞掉。

后果是单点致命：answer 能送到（见下），房主因此正常起 ICE，但它手里**一条对方的
地址都没有**，主动探测发不出去 —— 观众界面要么卡在"正在连接房主的手机…"，
要么过一会儿报"直连失败"。网页观众端一直好用，正是因为它绕开了这条 Java 路径。

### 顺手把我自己的一条假设测掉了

原本以为 `SdpObserver`（answer 那一路）也会被投回"创建 PeerConnection 的线程"，
而 `ViewerSession.ensurePeer()` 跑在 `Dispatchers.Main.immediate` 上，那样 answer
也会一起丢。加了 `Peer.logThread()` 打线程名，实测两局都是
`localDescription(Offer) 回调线程=signaling_threa` —— **answer 那一路本来就是安全的**，
丢的只有候选。结论按实测写进注释，不留在推测里。

### 修法与验收

`WsClient` 改成与 `SignalHub` 同一个形状：`send()` 只入队（有界 128），
专职线程 `t2-ws-write` 按 FIFO 落地；失败日志带上异常类名与线程名。

新增 `WsClientTest`（JVM，配一个最小假 WebSocket 服务端）锁两件事：
**写永远发生在专职写线程、不是调用方线程**；**握手完成前入队的消息不会被丢**。
第二条不是凑数 —— 第一版实现正是"先 `poll()` 再判 `out == null` 然后 continue"，
把还没法写的消息直接从队列摘走扔了，测试当场就红了。

为此加了 `app/src/test/java/android/util/Log.kt` 这个桩：
`run-unit-tests.sh` 是拿 JUnitCore 直接跑 class、classpath 上没有 android.jar，
`WsClient` 一进门就 `Log.i`，缺桩则任何碰到它的测试都 NoClassDefFoundError
（这也是 `IceProbe` 只用 `System.nanoTime()` 的同一个原因）。

**尚未验证的部分（如实标注）：** 上面是"读代码 + 单测 + 房主侧同源事故"三条证据
推出来的，**观众侧的端到端一次都没真跑过** —— 它需要第二台安卓设备，本机一台模拟器
做不到（同一个包不能开两份，`edgeai` 那台是别的项目的、不能碰）。房主侧回归照跑：
`t2test` 分享 → 浏览器入房 → `ice=CONNECTED`、收到视频与音频，20/20 单测绿。
判据留在 §14 那张矩阵里：**装新包后 App 内观看若能连上，本条即为定案**；
若仍失败，则候选不是唯一原因，要回看手机蜂窝侧的 srflx 分布。

## 17. 房主停止分享时，观众被误报成"连不上"（2026-09-25）

用户报的现象：分享端点「停止分享」，观看端提示"连接失败"。他的猜测是"停止前应该
先告知观众"——方向对，但那是三个问题的表层，逐个说清：

**① 房主从来不说再见。** `sendToViewer` 全仓库只有两个调用点（offer/answer 与候选），
`CallSession.stop()` 里没有任何一处发 `bye`。反向的通知是有的：观众断开时
`SignalHub.detach()` 会往内存通道塞 `{"t":"bye"}`（SignalHub.kt:383）——
**一个方向有、另一个方向没有**，这种不对称通常就是漏写。

**② 就算补上，原来的拆序也会把它扔掉。** `stop()` 第一件工作是 `teardownPeer()`，
随后 `TunnelManager.stop()` 杀 cloudflared，最后 `SignalHub.stop()` 里 `outbox.clear()`。
写入现在是排队的（§16 那轮改的），所以"再见"如果还压在队列里，就被 clear 一起丢，
或者随隧道一起没出口。必须**先把它写出去、再拆**。

**③ 真正画出错误文案的是观众端。** 网页端 `pc.onconnectionstatechange` 里
`s === 'failed'` 一律 `reportFailure('failed')`，标题「连不上房主的手机」＋
「纯直连在部分网络下会失败，而且这一版没有中继兜底」＋三条换网络建议；而
`reportFailure` 一进去就 `closedByUs = true`，把随后那句更准确的 `ws.onclose`
提示**静音**掉。App 端同理：ICE FAILED 直接 `fail()` → 红色失败屏。
一个正常动作被归类成"你的网络有问题"。

### 分诊规则（这轮真正的修法）

**信令通道还活着 ⇒ ICE 失败才是"连不上"；通道已经断了 ⇒ 那是"结束/中断"。**
据此把观众端拆成两类收场：`bye` / 通道断开 → 中性收场（不画红、不提 NAT、不放
"重试"，因为链接连同口令已经作废，刷新不会变回来）；只有通道仍在时的 ICE 失败
才走 §11 那套基于证据的判定。实现：`SignalHub.sayGoodbye()`（入队＋等 written 追平
offered，上限 400 ms，然后才拆）、`ViewerSession.State.Ended`、网页端 `reportEnded()`。

⚠ 顺手清了同一处残留的旧账：网页失败面板里还挂着「最稳：连他开的热点」——
`bedb2e9` 按"产品永远异地用"删过 App 侧那条，网页侧漏了。已换成异地能做的动作。

### 验收

新增 `scripts/check_bye_on_stop.py`：断言**观众端面板标题的文本**（光"页面有反应"不算），
且房主 logcat 必须有「已告知观众：分享结束」，否则那句"结束"只是靠通道断开猜的。
实测：t2test 分享 → 无头 Edge 入房出画面 → 房主点停止 → 观众标题
`房主结束了分享`，无失败话术，`said_bye=True`。**PASS。**

⚠ 这一轮又踩到"量具比代码先坏"，且是本轮第三次：`node()` 只读 `text`，而控制岛的
停止键是纯图标（文字在 `content-desc`，Kit.kt:453），于是"找不到停止按钮"；
`check_restart_roundtrip.py` 的判据等的是"信令就绪"，界面上那句早就改成
"邀请链接已就绪"（CallSession.kt:203），于是第二轮正常出链接也照样报 FAIL。
两处都已修，roundtrip 重跑 PASS。改 UI 文案时要回头扫 `scripts/`。

**未验证：** App 内观看那一侧的 `Ended` 分支需要第二台安卓设备，与 §16 同一笔欠账。

## 18. 观众端播放体验四处（2026-09-25，来自真机 5G 跨网实测）

这一轮是**第一次真正的跨网直连成功**：手机 5G ↔ 电脑，顶栏「直连」为绿、右上「已直连」，
不是中继。§14 矩阵里"异地能不能连上"从此有了一个真数据点（n=1，别当结论）。

| 现象 | 真因 | 处理 |
| --- | --- | --- |
| 点全屏没反应 | `document.documentElement.requestFullscreen().catch(()=>{})`。安卓上多数内核只把 Fullscreen API 开给 `<video>`，对文档调用直接 reject，而 reject 被空 catch 吞掉 ⇒ 用户看到的就是"点了没反应" | 目标改成 `#stage`，失败再退 `video.webkitEnterFullscreen()`，两条都不行就 **toast 说清楚**并指路画中画/横屏；resolve 了也要以 `fullscreenElement` 复核 |
| 画面上下/左右丢一块 | `body{height:100vh}`：移动端 100vh 量的是**不含地址栏的大视口**，框比看得见的高，`overflow:hidden` 又把这件事变得毫无痕迹 | 舞台 `position:fixed;inset:0`，并用 `visualViewport` 显式钉住尺寸（含 resize/scroll/orientationchange） |
| 收场屏"介绍不对" | 面板说"房主结束了分享"，顶栏还挂着绿色「● 直连」、右上还写"连接抖动，正在自愈"、底下控制岛还在跳 90ms/1fps —— 同一屏三种说法 | `reportEnded()` 一并把顶栏改灰「已结束」、收掉控制岛、关掉 pc |
| 「这一条链接」和「已随那次分享作废」隔很远 | `.kv{justify-content:space-between}` 在窄屏上把短标签推到两端 | 收场屏不再用 kv，改成两句正文；`.kv` 补 `flex-wrap` |
| 房主转屏后画面方向/裁切错 | 采集尺寸是 `startCapture()` 那一刻读的死值，`changeFormat()` **定义了但全仓库无人调用**，也没有任何转向监听 | `DisplayManager.DisplayListener.onDisplayChanged` → 重读几何 → `changeCaptureFormat`（不用 OrientationEventListener：它给连续角度，还得自己定死区） |

**转向的实测**（这是唯一能本机验的硬指标）：横屏时房主日志
`显示转向：810x1800 -> 1800x810，重设采集`，浏览器收到的帧从 `1800x810`
在转回竖屏后变成 `810x1800` —— 证明 `changeCaptureFormat` 真的重建了 VirtualDisplay
并传到对端，不是只改了个变量。

⚠ **一次"我复现了"其实是量具骗我**：用 `--window-size=412,900` 无头截图时卡片明显溢出右边，
我差点就此下结论。实测 `innerWidth=492` 而 PNG 只有 412 宽 —— **是截图被窗口裁掉，不是页面溢出**
（卡片 left=24 width=444，24+444<492 完全放得下）。⇒ 无头 `--screenshot` 的 PNG 宽度必须和
`innerWidth` 对上才能当证据；对不上时看到的"裁切"是假象。

**仍未确认：** 用户真机上"右边丢一块"没有本机复现。最像的解释是那个浏览器把**布局视口**
弄得比屏幕宽（页面缩放类设置），左对齐 ⇒ 右边出界。为此在收场/失败面板底部加了一行
「布局 WxH · 可见 WxH · 缩放」—— 下次那张截图自己就带答案，不用再猜。

## 19. 第二台模拟器 t2view：App 内观看第一次端到端跑通（2026-09-25）

§16/§18 两轮的欠账都是"观众侧没有第二台设备，验不了"。今天补上了：
`t2view`（从 t2test 的 config.ini 手工复制，API 36 / x86_64 / 2 核 / 3 GB），
`scripts/drive_app_viewer.py` 在 t2test 分享时把邀请喂给 t2view 的 App 观众端。
`t2device.py` 与 `run_t2test_emulator.sh` 都支持 `T2_AVD=<名字>` 或位置参数选机器。

**结果（第一次有证据）**：`answer 已发出=True`、`ice=CONNECTED=True`、
`收到远端视频轨=True`、`「发送失败」条数=0`，收场屏显示「房主结束了分享」。
⇒ §16 那个观众侧主线程写 socket 的修复，从"读代码推出来的结论"变成实测通过。

⚠ **这台机的局限必须写在前面**：两台模拟器在同一台 PC、同一张 NAT 后面，
所以它验的是**信令 + 渲染 + 状态机**，不是跨网打洞能力 —— 同机测试会掩盖候选投递类
bug，正是 §16 的教训。跨网结论仍然只能来自真机/异网。

### 这一轮被这台机抓出来的四件事

1. **键盘盖住主按钮（真缺陷）**：观众粘贴那一屏的「在 App 内观看」贴在底部，键盘一弹出来
   就整个被盖住 —— 用户看到的是"链接粘好了但按钮点不到"。`PageScaffold` 补 `imePadding()`。
2. **收场分档判错（我自己上一轮引入的）**：隧道注册慢时 Cloudflare 回 **530**，握手被拒，
   而 `onClosed` 只看"当前是 Connecting"就判成 Ended（"连接断了，对方可能已停止分享"）。
   那是**从没连上过**，该给失败与重试。加 `everOpened` 把"没连上"和"连上后断了"分开。
3. **收场屏两段文案重复、还说自家 UI 的闲话**：App 侧除了正文还留着一段
   "…所以这一屏没有「重试」按钮，点了也是白点" —— 那是写给评审看的，不是给用户看的。
   已删，卡片只留一句可执行动作。
4. **量具自己绊自己**：判"误报失败"用的是子串匹配"重试/连不上"，而收场文案里本来就写着
   这些词 ⇒ 明明显示的是收场屏却报"误报失败=True"。改成匹配失败屏独有的元素
   （「根据什么这么判断」「按这个顺序试」）。

### 脚本侧记的三条坑（都是这轮踩的）

- `am start -n 包名/包名/.MainActivity`（组件名拼重了）会报 Bad component name，
  而脚本只看"焦点没过来"，于是报成"App 没起来"。
- `dumpsys window` 里 `mCurrentFocus` 不止一处，`split()[-1]` 取到的是别的窗口 ——
  一个已经在前台的 App 会被判成没起来。取**那一行**，不要取最后一次出现。
- Windows 控制台默认 GBK，脚本里 print 一个 `⚠` 就整脚本崩在最后一步；
  脚本开头统一 `sys.stdout.reconfigure(encoding="utf-8", errors="replace")`。

**未定：** 观众画面比房主本机暗（35.5 vs 48.2 均值）—— 但两张不是同一时刻同一内容，
**不算证据**。要判是不是真发了暗，得同一帧对比。

## 20. 观众屏跟随对方横竖屏（2026-09-25）

用户问：房主横屏了，观众会不会跟着横？实测**不会**，而且原因很朴素 ——
清单里没有 `screenOrientation`，观众屏只跟随观众自己怎么拿手机；而唯一能感知内容方向的
回调 `VideoLayer.onFrameResolutionChanged(w,h,rot)` **只打了一行日志，没拿它做任何决定**。
于是内容 1800×810、屏幕 1080×2400，画面缩成中间一条。观众的物理朝向我们管不着，
所以想让两边一致只能主动改观众屏方向，没有第二条路。

链路：帧尺寸 → `ViewerSession.onContentResolution` → `contentLandscape` →
`MainActivity` 的 `LaunchedEffect` 设 `activity.requestedOrientation`。
决策抽成纯函数 `decideOrientation(mode, contentLandscape, hasVideo)`（5 条 JVM 测试钉死），
`Keep` 那一档是刻意的：首帧没到、或对方开的是仅语音时不该动屏幕方向。
底岛多一颗三态按钮（跟随 / 竖屏 / 横屏），用两个字而不是图标 —— core 图标集里没有
`ScreenRotation`，且旋转类图标容易和"刷新/重试"混。

**实测（t2test 分享 → t2view 收看，两台都先关掉自动旋转把物理方向钉死）：**

| 动作 | 结果 |
| --- | --- |
| 房主转横 | 帧 `1800x810` → 13 ms 后 `观众屏方向 → Landscape`，截图 1080×2400 变 **2400×1080** |
| 房主转回竖 | 观众屏回到 1080×2400 |
| 点按钮锁竖，再让房主转横 | 观众屏保持竖（锁定压过内容） |
| 点按钮锁横 | 观众屏立刻横 |
| 会话结束（隧道掉线） | `Keep` → 交还系统，**不留方向锁** |

⚠ 最后一条差点被我误判成 bug：日志出现 `观众屏方向 → Keep（内容横=true）`，看着像
"该横却 Keep"。查下去是 5 秒前会话已经断了，`watching=false` 走的是"退出观看就解锁"
那一支 —— 行为正确。教训：**看到可疑日志先确认它属于哪个状态分支**，别急着改代码。

**顺手修的一处语义**：控制岛那颗停止键的 `contentDescription` 原本恒为「停止分享」，
观众侧其实是"停止观看"。现在按角色分开（`stopDesc`）。

## 21. 观众端连麦一直是坏的，且麦克风改成真正默认关闭（2026-09-25）

两个问题同源。`ViewerSession.ensurePeer` 在连接时就 `addLocalTracks(null, ensureMicTrack())`，
注释写着"中途加轨需要重新协商，做不到" —— **这句是错的**，而它同时造成两件事：

1. libwebrtc 一建音频源就打开 `AudioRecord`，观众只是看画面，状态栏橙色麦克风灯却一直亮
   （静音 ≠ 关闭）。这套 AAR 里 `AudioSource` 连 `setEnabled` 都没有，
   `AudioDeviceModule` 干脆不在 classes.jar 里，没有"只关采集不拆轨"的口子。
2. 整条观众链路**从没申请过 `RECORD_AUDIO`**（只有房主那条链申请）。于是
   `ensureMicTrack` 返回 null、轨道根本不存在，而按钮走的是
   `setMicMuted → audioTrack?.setEnabled(...)`，打在 null 上 ——
   **图标翻了，什么都没发生**。观众以为自己在说话，房主一个字都收不到。
   网页端反而是好的（它调 getUserMedia，实测 out:audio 有字节）。

### 改法

连接时**不建音频轨**（answer 里音频退成 recvonly，观众照样听得到房主）；观众第一次点
「开麦」才：申请权限 → 建轨 → `addTrack` → **由观众主动发一轮 offer** → 房主
`acceptOffer` 回 answer。谁改媒体谁发起，房主侧只需要多认一条 `"offer"` 分支，
它的 `Peer` 本来就具备 acceptOffer 能力。复用同一条传输，不换 ICE 候选，所以画面不断。
兜底：8 秒等不到 answer 就明说"房主版本太旧"，别让人对着亮着的麦克风图标说话。

### 实测（t2test 分享 → t2view 收看，先 `pm revoke` 模拟新用户）

| 检查 | 结果 |
| --- | --- |
| 默认是否关 | 房主日志**没有**「收到对方音频轨」；`appops` 显示 `RECORD_AUDIO: ignore` |
| 点「开麦」 | 权限弹窗出现（以前从来不弹，这就是 bug 本身） |
| 授权后 | 观众 `正在把麦克风加进这条连接…` → `麦克风已接入`（0.8 秒）；房主 `观众要加麦克风，重新协商中` → **`收到对方音频轨`** |
| 按钮态 | 开麦后变「静音」（发言中），再点回「取消静音」 |
| 画面 | ICE 异常 0 条，亮度均值 35.4 → 35.3，协商没有打断视频 |

⚠ 中途自己埋的一个必现 bug 记下来：`enableMic` 先建轨、后 `_micMuted.value = false`，
而 `ensureMicTrack` 里写死 `setEnabled(false)` —— 顺序一错就留下"已加入连接但永远发静音"
的轨道，房主收得到、听不见。现在开关只有一处生效（`setMicMuted` 同时改状态与轨道）。
还有 `micLive` 原本是 `get() = audioTrack != null` 的普通字段，Compose 不为它重组，
于是麦克风已接入、按钮还停在「取消静音」—— 状态就得是 StateFlow，不能让组合去猜。

**仍未验：** 真机上的回声表现（外放时观众的声音会不会回到观众自己的麦克风）——
这属于 §10 那笔连麦外放的欠账，要真人耳朵或两台真机。

## 22. 真机跨网这一轮：连上了，并抓到两个只有真机才会暴露的缺陷（2026-09-25）

拓扑：**观众 = 魅族 20 Pro（LTE，用户自动旋转关闭）**，**房主 = t2test 模拟器（走这台 PC 的 WiFi）**。
两端网络类型由 `scripts/run_phone_round.py` 自己记进 `.dev/phone-round/<时间>/env.json`
（"必须记两端网络类型"这条硬规矩从此不再依赖人记得去问）。

| 检查 | 结果 |
| --- | --- |
| App 内观看跨网能否连上 | **PASS**（`ice=CONNECTED`，蜂窝↔家庭宽带，非中继） |
| 候选投递有无异常 | PASS（「发送失败」0 条） |
| 收到远端视频轨 | PASS |
| 观众开麦 → 房主收到 | **PASS**（房主日志「观众要加麦克风，重新协商中」→「收到对方音频轨」） |
| 房主转屏 → 观众跟随 | **PASS**（手机屏 1440×3200 → 3200×1440） |
| 房主停止 → 观众收场文案 | PASS（「房主结束了分享」，不是失败屏） |
| 画面是否被压暗 | **仍未定**（39.2 vs 35.7，但两边内容不同，不是同帧同内容，不算证据） |

### 两个只有真机才暴露的缺陷

**① 没有任何"保持屏幕常亮"的代码。** 手机 `screen_off_timeout = 120000`，会话跑到第 2 分钟
屏幕一锁，WebSocket 被系统掐掉，房主那边先「观众已离开」、8 秒后报
「直连中断且未能自愈」——**看起来完全像网络不稳**。模拟器上永远测不出来，
因为 t2test 的超时被我设成了 `2147483647`。修法：分享/观看期间给窗口加
`FLAG_KEEP_SCREEN_ON`，会话结束就撤（`DisposableEffect`，onDispose 里必须清，
否则用户退出 App 屏幕还常亮）。

**② `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` 在用户关掉自动旋转时会被系统忽略。**
所以 §20 那个方向跟随在 t2view 上一切正常（那台自动旋转是开的），到手机上**完全不转**。
而"跟随对方"本来就是要盖过用户的锁定才有意义 —— 对方横屏了，这边就该横过来。
改用不带 `SENSOR_` 的强制常量。代价：横屏只能锁一个方向（左/右横屏不分），可以接受。

### 这一轮也被自己的量具绊了三次

- **上一轮遗留的 Flyme 权限弹窗没关掉**，一直压在 App 上：既让"开麦"没走通，
  又让收场判定 dump 到的是弹窗文字 → 两项同时变假。现在开局先清遗留弹窗。
- **`drive_m0` 静默失败**，脚本照样读 `invite.txt`，读到的是上一轮**已作废的链接**，
  于是整轮"连不上"全是假的。现在加了三道闸：退出码、链接必须真的变了、页面必须真能打开。
- **判据不该用日志**：logcat 缓冲会被系统噪声冲掉（有一轮观众端的 App 日志整段没了）。
  "屏幕横没横"直接看截图宽高最硬。
- 还有一次"BUILD SUCCESSFUL in 1s"是 up-to-date 的假成功 —— 我的补丁脚本本身语法错了，
  什么都没写进去。**改完必须看产物时间戳，不能信构建的退出码。**

### 流量

按用户要求省着跑：房主钉在 **540p · 10 帧 · 300 kbps**（默认档是 720p/30帧/2Mbps，
按上限算 15 MB/分钟）。手机侧该 uid 在 LTE 上累计 `rb=8.4 MB / tb=3.6 MB`（含前几天的
浏览器测试）。装机走 USB 不耗流量，全程没有走应用商店或云盘。

**没验的：** 回声与音质 —— 观众外放 + 开麦时房主的声音会不会绕回观众自己的麦克风，
脚本判不了，需要真人听。

## 23. 一起看（同看）：播放器必须握在自己手里

用户诉求：房主开浏览器和对方一起看片，对方想快进只能靠嘴喊。

**能不能控制房主自己开的外部浏览器？不能。** 两条路都堵死：
注入按键要 `INJECT_EVENTS`（签名级权限，上架应用拿不到，`InputManager.injectInputEvent`
上写着 `@RequiresPermission`）；`MediaSessionManager.getActiveSessions` 要通知监听权限，
而且只给 play/pause 这类通用键，给不了"跳到 12:34"。
参考过的三家开源同看项目（Synctv / SyncWatch / couple-cinema）**没有一家做这件事** ——
它们全都让每台设备各播一份再对时（对时阈值：Synctv 自动 1.2s、手动 0.2s，
SyncWatch 1.25s 硬跳 + 0.12~1.25s 之间用 ±6% 变速微调，couple-cinema 1.8s）。
那套架构需要一台服务器和一个能拿到直链的播放器，和"我分享我这块屏"正好相反。

**所以反过来做**：房主在 App 里打开内置 WebView 播放，播放器在我们手里，
进度和指令都走已经建好的那条设备间 WebSocket。观众看到的画面本来就是这块屏，
控制它天经地义。实现要点：
- 只用 `evaluateJavascript` 问/命令，**不用 `addJavascriptInterface`**（后者把 Kotlin 对象
  挂到 window 上，页面里任意脚本都能调，是 WebView CVE 最密集的那类接口）。
- 选主 `<video>` 按渲染面积取最大，不是 `querySelector('video')` 取第一个（多视频页里第一个常是广告）。
- 权限闸门放在**收消息那一侧**（`CallSession`），不给 UI 留"忘了判"的机会。
- 观众侧不做本地乐观更新：按下之后等房主下一次广播带回真相，两边永远只有一个进度。
- 连点保护 250ms；`step` 幅度砍到 ±120s。

实测（t2test 房主 / t2view 观众，同机）：观众连按两次 -10 之后，房主页上那个大字时钟
从 0:52 变到 **0:41** —— 播放不会自己倒退，这就是控制真的生效的铁证。
顺带把 §17 结掉：控件收起 vs 展开，同一块内容区亮度 **95.9 → 95.9**，控件不压暗画面。

两个坑：
1. 验证页最初挂的是 googleapis 的示例片，这台机器上直接不通（`curl` 返回 000），
   于是 `duration=0`，看起来像"注入没生效"，其实是根本没数据。换成随包的 55KB 测试卡
   （ffmpeg 生成，60 秒彩条），零网络依赖。
2. `GlassTextButton` 的 "-10 / +10" 是画出来的，进不了 uiautomator 的无障碍树，
   `find()` 会报"没有 +10"而屏幕上明明画着 —— 脚本里退回坐标兜底，并且**把退回打印出来**。

## 24. 分享端最小化后的悬浮预览窗：判定为不做

用户提过"能不能让预览窗不被采集到，可以就做，不可以就算了"。答案是**不可以**：
- AOSP `SurfaceControl.SECURE` 的注释写得很直白：截图与非安全显示里
  "render black content instead of the surface content" —— 是**黑框**，不是透明穿透；
  `Display.FLAG_SECURE` 同样表述为 "blank region"。第三方分析（Ostorlab）的措辞是
  "窗口出现在帧里的任何地方都只是一块黑色矩形"。
- API 35 新增的 `View.setContentSensitivity(CONTENT_SENSITIVITY_SENSITIVE)` 是公开 API，
  但 javadoc 自己说 "equivalent to applying FLAG_SECURE"，实现（`ViewRootImpl
  .applySensitiveContentAppProtection`）是在投屏会话期间给整个窗口挂 secure —— 一样黑框。
- 真正"从截图/录制里跳过这一层"的 `Transaction.setSkipScreenshot` 是 `@hide`，
  android-34..37 的 api-versions.xml 里查不到。

所以悬浮预览要么套娃、要么在对方画面上留一块黑，两个都比不做更糟，**放弃**。
最小化时的存在感由前台服务通知承担（已带"1 人正在观看"和停止按钮）。
