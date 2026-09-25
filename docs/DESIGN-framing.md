# 取景直播 —— 从「分享屏幕」到「一起看片」

- 日期：2026-09-25　状态：**提案，等拍板**（未动代码）
- 效果图：`docs/mockup/live-room-framing.html`（取景框可拖、观众端浮层可点收起）
- 起因（用户原话拆出来的四条）：
  1. "APP 是分享端把整个屏幕都分享过去，观看端感觉有点割裂" → 观众不该看到"别人的手机桌面"
  2. "分享端屏幕上的 UI 控件和观看端上面的 UI 也有点重叠了，两边都显示" → 一层 UI 原则被破坏
  3. "我用浏览器看电影的时候基本都是使用其他浏览器打开的" → 内置 WebView 那条路在真实场景里用不上
  4. "观看端像是进入直播间一样，但是不用需要发弹幕效果"

---

## 0. 一句话方案

**在房主手机上做「取景」：逐帧把画面裁到视频那一块再编码推流。**
观众收到的不再是"房主的屏幕"，而是"一段干净的视频" —— 于是他自己那层控件就是屏幕上唯一的一层，
"割裂"和"双层 UI"同时消失；而房主爱用哪个浏览器就用哪个浏览器，不需要换成 App 内置的。

四家参考项目里**没有任何一家做这件事**，因为它们都不需要（见 §3）。这是"手机当房主 + 内容在第三方 App"
这个组合逼出来的解法。

---

## 1. 现状为什么会有这两层 UI

| 层 | 出处 | 位置 |
| --- | --- | --- |
| 房主的控件 | `CallScreen.kt` 的岛/顶栏，被 MediaProjection 一起采进流 | `ScreenShareController.kt:84-87` 采的是**整块默认显示** |
| 观众的控件 | `app/src/main/assets/viewer/index.html:104-118`（`.overlay.top` + `.overlay.bottom` + `#watchBar`） | 只能压在别人的画面上 |

采集侧目前没有任何"只传一部分"的概念：`displayGeometry()` 取的是 `currentWindowMetrics.bounds`
整屏乘 0.75（`ScreenShareController.kt:55-64`）。所以只要不裁，观众的浮层就注定是第二层。

---

## 2. 三层方案

### 2.1 画面层：取景（本方案的核心，也是唯一有技术风险的一层）

**管线**：`MediaProjection → ScreenCapturerAndroid → [VideoProcessor: crop+scale] → VideoSource → VideoTrack → 编码`

**API 依据（全部实测，javap 打在 `io.github.webrtc-sdk:android:150.7871.01` 的 `classes.jar` 上，
不是记忆也不是文档印象）**：

```
org.webrtc.VideoSource:            public void setVideoProcessor(org.webrtc.VideoProcessor)
                                   public org.webrtc.CapturerObserver getCapturerObserver()
org.webrtc.VideoProcessor:         extends CapturerObserver
                                   void setSink(VideoSink); onFrameCaptured(VideoFrame, FrameAdaptationParameters)
                                   static VideoFrame applyFrameAdaptationParameters(...)
org.webrtc.VideoProcessor$FrameAdaptationParameters:
                                   cropX cropY cropWidth cropHeight scaleWidth scaleHeight timestampNs drop
org.webrtc.VideoFrame$Buffer:      Buffer cropAndScale(int,int,int,int,int,int)
org.webrtc.TextureBufferImpl:      public Buffer cropAndScale(...)   ← 屏幕采集的帧就是这一类（GPU 纹理）
org.webrtc.JavaI420Buffer:         public Buffer cropAndScale(...) + static cropAndScaleI420(...)（CPU 兜底）
```

注意 `RtpSender` **没有** `setVideoProcessor`（这个 AAR 里确实没有，javap 已确认），
所以挂载点只能在 `VideoSource` 上 —— 恰好也是更合适的位置（在编码之前，编码器直接变小分辨率）。

**内容矩形从哪来（三个来源，按优先级）**

| 优先级 | 来源 | 覆盖场景 | 代价 |
| --- | --- | --- | --- |
| 1 | 自动·黑边检测：每 ~2s 抽一帧，降采样后统计亮度，找"四周全黑、中间有内容"的最大内接矩形 | 全屏播放（看电影的主场景） | 零操作、零权限；纯黑电影画面会误判 → 置信度门 |
| 2 | 手动·拖一次取景框，按横/竖屏各存一份 | 网页里嵌一块播放器、小窗播放 | 房主要动手一次 |
| 3 | 内置浏览器：直接问 DOM 里 `<video>` 的位置（探测已经在跑，见 `WatchSync.probeJs()`） | 用 App 内「一起看片」时 | 零误差，且顺带拿到片名/进度 |

置信度不够时**退回整屏**并在顶栏标"未取景" —— 宁可多传 UI，也不能把演员半张脸裁掉。

**顺带白送的三件事**

1. **隐私是"不发送"而不是"到对端涂黑"**：房主的微信弹窗、验证码、地址栏历史根本不进流。
2. **码率花在片子上**：竖屏 1080×2400 里一部 16:9 的片子只占约 26% 面积，裁掉后同样 2 Mbps
   每像素多分约 3.8 倍比特 —— 更清楚且更省电（编码像素少了）。
3. **一键遮屏**：`FrameAdaptationParameters.drop` 是个布尔值，房主按一下就不推画面、声音继续，
   观众看到一张明确的"对方把画面遮住了"卡片，而不是黑屏发呆。

**风险（要 P0 spike 先证伪）**

- 纹理 `cropAndScale` 需要可用的 EGL 上下文和 `ScaleAndCropHelper` 池；若抛异常，退路是
  `toI420()` + `cropAndScaleI420`（CPU，慢但一定能跑）。
- 取景框中途变化 = 输出分辨率变化 → 编码器重启 + 一次关键帧，观众端会看到一次短暂停顿。
  对策：变化幅度 < 5% 不动；动的时候做 800ms 防抖，且只在房主主动切全屏/退出全屏时重算。
- 逐帧处理加在采集线程上，房主边打游戏边分享时是净开销 → spike 里要量 `systrace`/帧间隔。

### 2.2 观众层：直播间化（只做减法，不做弹幕）

画面里已经没有房主 UI 了，所以观众端**只留一层**浮层，元素收敛成：

- 顶栏一颗：房主身份（头像/昵称）+「直连」状态 + 观看时长 + 延迟
- 底岛一颗：音量 / 小窗(PiP) / 全屏 / 退出；横屏时多一条**只读**进度镜像
- 右侧三颗反应（点了飘一下即散，**没有弹幕输入、没有弹幕列表**）
- 片名/精确进度：**只在拿得到时显示**（内置浏览器模式）；拿不到就整条不出现，不用假数据填

复用已有能力，不重做：ambient 环境底（`viewer/index.html:29-36`，`#vbg` 同一条流放大模糊垫后）、
轻触收起、左亮度/右音量手势、方向跟随、画中画、信令心跳。
App 内观众端（`CallScreen.kt` + `ViewerGestureLayer.kt`）与网页观众端保持同一套元素，避免两套设计语言。

### 2.3 控制层：诚实分三档

| 路线 | 画质 | 流量 | 观众能直接控制进度 | 权限 | 能看什么内容 |
| --- | --- | --- | --- | --- | --- |
| **A 取景直播**（新，底座） | 重编码 720p/2Mbps | ~15 MB/min | ✗（只能"请求"） | 无新增 | **任何 App** |
| **B 内置浏览器**（现状，保留） | 同上（也可只传画面） | ~15 MB/min | ✓（注入 JS，精确到秒） | 无新增 | 只有本网页播放器 |
| **C 链接同播**（可选升级） | 观众本地原生播放 | ≈0（只传时间戳） | ✓（各自本地播，同步时间轴） | 无新增 | 观众也打得开同一个源才行；会员/DRM 直接废 |

- A 下观众按 ±10 秒 = 一条**请求**，房主手机弹提示 + 震动，房主自己按。
  这个交互 SyncWatch 叫「申请控制权」，是成熟做法，不是搪塞。
- **明确不做**：无障碍服务代按。技术上能读第三方 App 控件并派发手势，但代价是系统级权限、
  Flyme/MIUI 杀后台、重装掉权限、误点风险，以及"对方在我手机上点按钮"这件事本身让人不安。
  除非用户点名要，不做。
- C 不做主方案：它传的是一片源而不是画面，"我在看什么"必须能被对方独立复现；两人会员不在同一账号上时当场断。

---

## 3. 四家参考的取舍（都读了源码，不只是 README）

| 项目 | 拿画面的方式 | 抄什么 | 为什么不照搬 |
| --- | --- | --- | --- |
| **synctv-app**（Flutter，活跃，72★） | 服务端按站点解析真实播放地址（Rust `synctv-media-providers/src/bilibili/client.rs`），观众**本地播** | 整套漂移模型：`(position, generatedAt, rate)` + NTP 式对钟（`core/time/synced_clock.dart`）+ 偏差 >1.2s 才 seek + 单调 `version` 丢过期帧 + `_isSyncing` 800ms 回声抑制 | 要一个跑在服务端的 provider 代理（manifest 重写、签名 URL、切片缓存）。手机跑不了也不该跑 |
| **SyncWatch**（Node+Socket.IO，活跃，134★） | 桌面 `getDisplayMedia({preferCurrentTab:true, selfBrowserSurface:'exclude', systemAudio:'include'})` 选**单个标签页**；Android 退化成 MediaProjection→ImageReader→**JPEG 8–12fps 且无声** | 「申请控制权」交互；共享时把自己的播放控件全禁掉（`app.js:8711 suspendMediaForScreenShare`）；`setImmersiveMode` 去系统栏；直播间浮层清单（状态卡/延迟/偏差/反应层） | 它自己写了"Cloudflare 没有 Android 版 cloudflared"（`MobileServerService.java:98-101`）⇒ 手机当房主它做不到公网。我们的隧道 + 真 WebRTC 屏幕流正是它缺的那块 |
| **192_lab-couple-cinema**（Node，单次提交，像课程作业） | 只同步 `{paused,time,rate}`，双方各自播同一 URL（hls.js） | 回声抑制的具体写法：`applying` 计数器 + 700ms 衰减，跟随者 \|Δ\|>1.8s 才 seek | 片源是灰产采集站 m3u8；语音只有 Google STUN 没 TURN，对称 NAT 下必挂 |
| **star-syncplayer**（JavaFX+VLC，2023-11 后停更） | 双方各自打开本地同一文件，谁都不传画面 | 服务器把控制消息**同时回给两端** ⇒ 漂移是 \|t1−t2\| 而不是 t1+t2（一条很便宜的信令改进） | 桌面 VLC，要求两人有同一个文件；默认服务器 2024-09 已过期 |

**结论**：画面层四家都帮不上（他们的平台不需要裁剪）；观众层的浮层清单抄 synctv/SyncWatch；
控制层抄 SyncWatch 的「申请」+ 那两家的漂移/回声模型。

顺带发现一个**现有缺陷**：`WatchTogetherScreen.kt:144` 只有 250ms 防抖，
没有回声抑制、没有 `version`、没有阈值 seek（`CallSession.kt:351` 的 `wcmd` 分支同样裸处理）。
观众连点 +10 时房主侧会把自己的状态回灌给观众再触发一次，属于四家都已经踩过并修过的坑。这条无论选哪个方案都该补。

---

## 4. 实施顺序与验收判据

**P0 · 取景能不能跑（半天，纯 spike，不上 UI）**
在 `ScreenShareController.start()` 里挂一个固定裁剪（裁掉上 12% 下 18%）的 `VideoProcessor`。
判据（全部要量化，不接受"看起来可以"）：
1. 观众端 `video.videoWidth×videoHeight` 从 720×1600 变成 720×1120 级别 → 证明裁的是推流而不是本地显示；
2. 房主状态栏/控件不在观众截图里（用 `screencap` + 内容区像素比对，脚本已有同类工具 `scripts/drive_watch_together.py`）；
3. 观众端帧率不降（`getStats` inbound `framesPerSecond` 前后各采 10s）；
4. 房主侧 CPU 增量 < 8%（`top -e pidname` 采样 30s）；
5. 纹理路径失败时能自动退回 I420 路径并打日志。
不满足 3/4 就放弃逐帧裁剪，退到 §2.1 的"隐藏自己 UI"弱方案（SyncWatch 那套）。

**P1 · 取景产品化 + 观众端一层 UI**
黑边检测 + 手动兜底 + 记忆；观众端浮层收敛成 §2.2 那一层；房主侧共享中把自己的控件收进一颗胶囊。
验收：同一部片子，观众截图里 UI 像素占比从 X% 降到 0（用内容区检测量化），且"两层控件叠在一起"在截图里不再出现。

**P2 · 遮屏 + 请求式控制 + 补回声抑制**
`drop` 一键遮屏；观众端「请求」按钮 → 房主弹提示；`WatchSync` 加 `version` + 阈值 seek + `applying` 抑制。
验收：脚本连点 +10 二十次，房主侧只落一次最终态，观众侧不出现来回跳。

**P3 ·（可选）链接同播 C**
只有当同看内容是可直接打开的 URL 时，观众端出现"用我的账号看高清"。抄 synctv 的协议而不是它的代理。

---

## 5. 未决

- 黑边检测在"画面本身大片纯黑"（夜景电影）下的误判率 —— 需要拿 3~5 部真实片子各测一遍才能报数。
- 取景后**房主自己**的观感：他的 App 控件往哪放（胶囊？边缘滑动唤出？）—— 出图后再定。
- 观众端要不要显示"对方正在被电话打断"这类来自取景管线的状态（技术上能拿到，产品上可能太啰嗦）。
- 跨网真机复验仍欠着（手机在忙）；新加的心跳/重连/将来的裁剪，最该验的都在真机上。

## 6. 要用户拍的三个问题

1. **取景默认开还是默认关？**（推荐：自动判断 —— 识别到全屏视频就取景，否则整屏）
2. **观众用别的浏览器时，控制做到哪一档？**（推荐：请求式，零权限；不推荐无障碍代按）
3. **「链接同播」C 要不要一起做？**（推荐：先做 A，C 留下一轮）
