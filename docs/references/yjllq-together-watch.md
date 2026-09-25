# 雨见浏览器「一起看电影 / 情侣同步看」—— 从 APK 里读到的事实

来源：官方 CDN 的公开安装包 `【64位】红雨见 v8.0.3.1.apk`（175,830,013 字节，
`http://cdn.rainsee.top/upload/static/apk/...apk`，16 个 dex，Chromium 内核 `libchrome.so` 188MB）。
方法：不装、不跑，只把 apk 当 zip 读，在 dex 与 resources.arsc 里做字节级字符串检索
（`strings`/正则，CJK 同时试 UTF-8 与 UTF-16LE）。原始命中留在 `.dev/yj-report.txt`、
`.dev/yj-net.txt`、`.dev/yj-js.txt`（`.dev/` 不入库，安装包用完移进回收站）。

## 看到了什么

1. **确实是"房间号"制**：dex 里有一串连在一起的中文提示
   `启动QQ失败，建议您重启应用` / `悬浮失败` / `悬浮播放失败2` / `悬浮播放失败4` /
   `房间号` / `打开视频失败` / `重启`。
   读法：房间号是加入凭证，邀请靠**拉起 QQ 分享**，观看时视频挂在**悬浮窗**里播。
2. **有服务端**：自家接口里能看到 `https://icon2.yjllq.com/redisiframev2.php?...`
   （Redis 中转）与一整片 `admin.yujianpay.com/api/...`、`api.yjllq.com/index.php/api`，
   另有落地页 `https://club.yujianpay.com/index.php/viptogetherdetail.html`
   （标题就是「雨见浏览器一起看电影功能介绍」，正文是空的）。
3. **控制网页播放器靠注入 JS**：dex 里 `querySelectorAll('video')` 命中 14 次
   （classes8 ×6、classes13 ×5、classes10/11/12 各 1），`.currentTime =` 命中 4 次，
   `.paused` 命中 8 次，`document.title` 也有。
   也就是：**挑出页面上的 `<video>`，直接读写 currentTime / paused**。
4. **还有一条本地 WebSocket 桥 + CDP**：注入脚本里有
   `new WebSocket("ws://127.0.0.1:9863")`，上报被点元素的
   `tag_name/id/class_name/href/src/input/position/text`；另有
   `https://devtoolcdn.yjllq.com/cdp.js`。这是"浏览器自己驱动自己渲染的页面"那条路
   （DevTools 协议 / 本地回环），第三方 App 拿不到。
5. **没有跨 App 控制的能力**：全量 dex 里 `INJECT_EVENTS`、`injectInputEvent`
   **0 命中**（`AccessibilityService` 有 31 处，但那是网页侧的通用能力，不是媒体控制）。

## 对我们意味着什么

- 我们的判断被独立证据支持了：**"控制对方手机上别的应用的播放器"这条路他们也没走**，
  因为系统不给（签名级权限）。
- 他们的形态是**每台设备各播一份 + 服务器对时**（房间号 + Redis 中转），
  和 Synctv / SyncWatch / couple-cinema 三家开源项目一致。
  我们的形态是**一台播、另一台看屏幕 + 把指令回传**，因为我们的前提就是"分享这块屏"，
  而且没有服务器。两条路各自成立，不必对齐。
- 值得借鉴的两点，都已落在自己的实现里或记成待办：
  · 注入 JS 挑主 `<video>` 再写 `currentTime` —— 我们已经这么做（还按渲染面积挑，避免选中广告位）；
  · **悬浮窗播视频** —— 那是他们"各播一份"才需要的；我们是看对方屏幕，
    §24 已经判定过悬浮预览在采集里会变成黑框，不通用。
