# 雨见浏览器「智能播放器」逆向分析

来源：`G:\工作台\yujian\雨见v8.0.3.9_3.apk`（123,576,087 字节，versionName 8.0.3.9，
包名 `com.yujian.ResideMenuDemo`，targetSdk 36）。方法：APK 当 zip 解 + jadx 1.5.3
反编译 8 个 dex + aapt 读资源表。反编译产物与本笔记同步留在 `G:\工作台\yujian\`
（`out/`、`ext/`，均不入库）。

## 一、这是什么内核

- `lib/arm64-v8a/libxul.so`（152MB）+ `assets/omni.ja` + `mozac_*` 错误页 = **GeckoView
  （Firefox 移动内核）**。注意：这与 v8.0.3.1 那份（libchrome.so，Chromium）不是同一内核
  ——雨见对不同渠道/位数发包不同内核。
- App 侧多模块：`moduleplayer`（播放器）、`modulewebgecko`（GeckoView 集成）、
  `modulewebbase/modulewebsys`（双内核抽象 `md.u` 接口）、`custom`（JSInterface 桥）等。

## 二、智能播放器的四个部件

### 1. 嗅探：内置 WebExtension（assets/videosniff）

manifest 名就叫「**浏览器播放器替换拓展**」（id `openvideo@yjllq.com`，v1.3）。
background.js 的嗅探逻辑（猫抓同款思路）：

- `webRequest.onResponseStarted` 触发 → `findMedia()`：
  - **扩展名白名单**（localStorage['Ext']，带每类最小体积阈值 MB）；
  - **MIME 通配符表**（localStorage['Type']，wildcard 匹配，`video/mp2t`、DLNA-tts 恒命中）；
  - `Content-Disposition` 里的 filename 回退识别；
  - 命中后连同 `content-length`、页标题、tabid 记进 `mediaurls[tabid]`。
- **按内容抓 m3u8**：`webRequest.filterResponseData()`（GeckoView/Firefox 独有 API，
  Chromium 系 WebView 没有）流式截获响应体，<3MB 且含 `#EXTM3U`（排除恰好是 JSON 字符串
  的误报）就记为播放列表——URL 长得不像 m3u8 也能抓到。
- **保留请求头**：`onSendHeaders` 把每个 request 的请求头按 requestId 存下，和媒体 URL
  一起上报 —— 播放/下载时回放 Referer/UA 过防盗链的关键。
- tab 关闭/重载即清记录。
- 上报：`browser.runtime.sendNativeMessage("browser", {type:"YJVIDEO", value:info})`。

### 2. 通道：GeckoView native messaging

- `MainBaseActivity` 里 `ensureBuiltIn("resource://android/assets/videosniff/", …)` 注册
  内置扩展；会话侧（modulewebgecko/p.java）对扩展 id 注册 `MessageDelegate`，端口名
  `"browser"`；每个 TabSession 一条 Port，断线重连，未连接期间 outgoing 队列最多 64 条。
- 有趣的细节：**这个构建里连 `evaluateJavascript` 都是走扩展端口实现的** —— LOADJS +
  requestId + 8 秒超时，结果从 LOADJS_RESULT 消息回来（GeckoView 的 session.evaluateJS
  不好用时的自家桥）。
- 另有一个大而全的 `JSInterface`（custom/q.java，约百个方法，消息按 name/parms 反射分发），
  页面 JS 可以直接调 App：`sysaddTagToResource(url, "video")`（页面主动上报视频）、
  `addMusic`、`download`、`fullscreen` 等。

### 3. 替换策略：服务端下发规则（api.yjllq.com/api/Initv3/settlev8）

- 启动时拉配置（SettleBean），失败用 dex 里的内置兜底 JSON，成功存 localStorage。
- 配置内容：
  - **`whitevideo` 大站白名单**：优酷/爱奇艺/腾讯/芒果/ACFun/搜狐……这些站**不替换**，
    避免和站点自家播放器打架；
  - **`replacejs` 站点补丁**：按 URL 匹配后用 `filterResponseData` 在响应流里做字符串
    替换/注入（例：YouTube 从 `ytInitialPlayerResponse.streamingData` 抠出直链后
    `JSInterface.sysaddTagToResource(...)`；某网课播放器取 video_urls 后上报）；
  - **`pagejs` 站点增强**：普通注入 JS（pornhub 防暂停、知乎输入框宽度、gitee 下载）；
  - **`originua` UA 策略**：一长串保持原 UA 的域名列表。
- 总开关：用户设置 `UserPreference_instead`（默认开）——即引导页第三步的
  `sw_smart_player`；不在白名单时 GeckoView 的 `MediaSession`（onFullscreen/onMetadata）
  和嗅探结果会把播放接回原生播放器。

### 4. 播放引擎：dkplayer + IJK（moduleplayer）

- `sysplayer/IjkVideoView`、`ResizeSurfaceView/ResizeTextureView`、
  `videocontroller/StandardVideoController` + 组件（VodControlView/GestureView/
  PrepareView/CompleteView/ErrorView/LiveControlView）= 开源 **dkplayer**（dueeeke）
  架构，内核 **ijkplayer**（FFmpeg）。
- 附加：`MyDanmakuView`（bilibili 弹幕库）、`TCControllerFloat`/`*FloatVodControlView`
  （WindowManager 悬浮窗播放，"各播一份"形态的配套）、`btn_pip` 画中画、
  `TogetherControlView/TitleTogetherView`（一起看房间制 UI，配合 ToogetherPlayActivity）。
- 嗅探到的 URL 进 `VideoActivity`（`StandardVideoController` + `setUrl`），播放时带
  嗅探阶段存下的请求头过防盗链。

## 三、对我们的可借鉴点（TicketForTwo 放映厅）

我们已经有的：MediaSniffer（WebView 侧嗅探）、CinemaProbe（挑最优流）、WatchSync（注入
JS 控制 `<video>`）。雨见验证了这条路线，另给四个可吸收的增强：

1. **Content-Disposition 文件名回退**识别（我们目前只看 URL/Content-Type）。
2. **扩展名+最小体积阈值白名单**：比纯 MIME 更稳，广告分片（小 ts）会被体积阈值滤掉。
3. **按内容识别 m3u8**：URL 不带 .m3u8 的伪装播放列表。WebView 上可用
   `shouldInterceptRequest` 对小响应体 peek 一段判断后再放行（成本高于 GeckoView 的
   filterResponseData，要控制 peek 量）。
4. **嗅探时保留请求头**，播放 HLS 时回放 Referer/UA —— 防盗链站点的直链没有这层会 403。
   （我们若做"把嗅到的流交给观众端直接播"就需要它。）
5. **大站白名单**思想：嗅探结果按站点信誉分级，别把广告分片当正片递给对方。

不借鉴的：悬浮窗/画中画（"各播一份"形态才需要，我们是"一台播一台看"）；服务端下发站点
规则（要养服务端+审核风险，单人项目不划算）；桌面 UA 大列表（运维成本高）。

## 四、"逆向能不能分解出源码"——结论

- **Java/Kotlin 层**：jadx 出的代码可读性不错，架构、字符串、调用关系完整可还原；但
  该 App 有混淆（类名 `a/b/c/p`、字段 `f16583a`），且部分方法反编译失败（jadx 报
  "Method dump skipped"）。得到的是"可读的等价实现"，**不是原始源码**，变量名/注释/
  资源语义全部丢失，不能直接编译回 APK。
- **资源层**：完整可解（清单、布局、字符串、图标）——aapt/jadx 都能还原。
- **assets 里的 JS**：基本明文，是最有价值的部分（嗅探/替换/站点规则全在这）。
- **native so**：libxul（Firefox 本体，开源）、ijkplayer（开源）、mmkv/bugly 等皆为
  第三方开源或 SDK，无需逆向；真正的私有 native 逻辑极少。
- **版权边界**：反编译产物与笔记只能用于学习与互操作分析；代码不能搬进 TicketForTwo。
  可行路径是：它用的组件（GeckoView、dkplayer、ijkplayer、bilibili 弹幕库）全是开源
  项目，想要同款能力直接引上游，思路自己重写。

原始命中/工具：`G:\工作台\yujian\{out,ext,scan.py,ctx.py,arsc.py,aapt-res.txt,jadx*.log}`。
