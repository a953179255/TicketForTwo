#!/usr/bin/env python3
"""观众页的**每一块兜底屏**都量一遍：文案是否被切、主按钮是否还在屏内。

为什么专挑这些屏：它们只在出事时出现，所以最容易没人管 —— 而朋友遇到问题的第一眼恰恰是它们。
App 侧已经因为"横屏下主按钮整个在屏外"翻过一次车（观众加入页提交不了链接），
网页侧的这几块卡此前只有肉眼看过一次。

不依赖房主与隧道：直接调页面自己的 `reportFailure / reportEnded / askToPlay / show / cineDown`。
两种视口各量一遍：竖屏 390x844、横屏 840x396（"放不下"就是横屏的事）。

判据：
  OFFSCREEN  卡片或它里面第一个按钮的矩形超出视口（按钮在屏外 = 这个屏没法走下去）
  CLIPPED    卡里某个文本块 scrollHeight 明显大于 clientHeight（被切成半行）
  EMPTY      卡没显示出来
"""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from drive_phone_cdp import Cdp  # noqa: E402

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
ASSETS = os.path.join(os.path.dirname(HERE), "app", "src", "main", "assets")
PORT = str(9600 + int(time.time()) % 60)
HTTP_PORT = str(8990 + int(time.time()) % 9)
URL = f"http://127.0.0.1:{HTTP_PORT}/viewer/index.html?demo=canvas"
OUT = os.path.join(os.path.dirname(HERE), ".dev")

# (名字, 进入这块屏的调用, 该不该有出口按钮, 是卡还是自动消失的提示)
# 「房主结束」故意**没有**按钮：链接连同口令已经作废、刷新也不会恢复，
# 这时候摆一个「重试」是在骗人；它该做的是把"接下来会发生什么"说清楚。
CASES = [
    ("连不上分诊", "reportFailure('failed')", True, "card"),
    ("房主结束", "reportEnded('房主结束了分享')", False, "card"),
    ("自动播放被拦", "askToPlay('厅已经进来了', '浏览器的自动播放被拦住了，点一下这里就能看原画')", True, "card"),
    ("链接不完整", "show('<h1>这条链接不完整</h1><p>请让房主重新发一条带接入凭证的链接。</p>"
                  "<button class=\"pill\">知道了</button>')", True, "card"),
    ("收厅退回", "cineDown('对方收了厅', false, 'closed')", False, "hint"),
]

MEASURE_JS = r"""
() => {
  const panel = document.getElementById('panel');
  const card = panel ? panel.querySelector('.card') || panel.firstElementChild : null;
  const vw = innerWidth, vh = innerHeight;
  const out = {vw, vh, panelShown: !!panel && !panel.classList.contains('hide'), problems: []};
  if (!out.panelShown) { out.problems.push('EMPTY    这块屏根本没显示出来'); return out; }
  const cr = card ? card.getBoundingClientRect() : null;
  if (cr) {
    out.card = {y: Math.round(cr.y), h: Math.round(cr.height), bottom: Math.round(cr.bottom)};
    if (cr.bottom > vh + 1 || cr.y < -1) out.problems.push('OFFSCREEN 卡片超出视口（要滚动才看得全）');
  }
  const btn = panel.querySelector('button, .pill, a.btn');
  if (btn) {
    const b = btn.getBoundingClientRect();
    out.btn = {text: (btn.textContent||'').trim().slice(0,10), y: Math.round(b.y), bottom: Math.round(b.bottom)};
    if (b.bottom > vh + 1 || b.y < -1) out.problems.push('OFFSCREEN 主按钮在屏外：' + out.btn.text);
  } else {
    out.problems.push('NOBTN    这块屏没有任何可点的出口（观众只能干等）');
  }
  for (const el of panel.querySelectorAll('h1,h2,p,li,div')) {
    if (el.scrollHeight > el.clientHeight + 3 && el.clientHeight > 8) {
      out.problems.push('CLIPPED  文字被切成半行：' + (el.textContent||'').trim().slice(0,18));
    }
  }
  return out;
}
"""


def main():
    srv = subprocess.Popen(
        [sys.executable, "-m", "http.server", HTTP_PORT, "--directory", ASSETS],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.2)
    udd = os.path.join(os.environ.get("TEMP", "."), f"t2-web-panels-{int(time.time())}")
    proc = subprocess.Popen(
        [EDGE, "--headless=new", f"--remote-debugging-port={PORT}",
         f"--user-data-dir={udd}", "--window-size=900,800",
         "--autoplay-policy=no-user-gesture-required", "--disable-gpu", "--no-first-run",
         "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    bad = 0
    try:
        pages = []
        for _ in range(20):
            time.sleep(1)
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json", timeout=5) as r:
                    pages = [p for p in json.loads(r.read().decode()) if p.get("type") == "page"]
                if pages:
                    break
            except Exception:
                pass
        if not pages:
            print("FAIL  Edge 没起来")
            return 1
        cdp = Cdp(pages[0]["webSocketDebuggerUrl"])
        cdp.call("Page.enable")
        cdp.call("Runtime.enable")

        def ev(expr):
            r = cdp.eval(expr, await_promise=True)
            return r.get("value") if isinstance(r, dict) else r

        for w, h in ((390, 844), (840, 396)):
            print(f"===== 视口 {w}x{h} =====")
            cdp.call("Emulation.setDeviceMetricsOverride",
                     {"width": w, "height": h, "deviceScaleFactor": 2, "mobile": True})
            for label, js, need_btn, kind in CASES:
                # 每块屏都从"干净的一页"开始：重新导航，避免上一块的状态串味
                cdp.call("Page.navigate", {"url": URL})
                time.sleep(1.4)
                ev(js)
                time.sleep(0.5)
                if kind == "hint":
                    hint = ev("(() => { const h = document.querySelector('.hint');"
                              " if (!h) return null;"
                              " const r = h.getBoundingClientRect();"
                              " return JSON.stringify([Math.round(r.y), Math.round(r.bottom),"
                              " innerHeight, (h.textContent||'').trim().slice(0,24)]); })()")
                    if not hint:
                        bad += 1
                        print(f"   {label:6} FAIL 收厅没有留下任何提示")
                    else:
                        y, bot, vh, txt = json.loads(hint)
                        print(f"   {label:6} 提示={txt!r} y={y}..{bot}/vh={vh}")
                        if bot > vh + 1:
                            bad += 1
                            print("     OFFSCREEN 提示超出视口")
                    continue
                m = ev(f"({MEASURE_JS})()") or {}
                probs = m.get("problems") or []
                card = m.get("card") or {}
                btn = m.get("btn") or {}
                print(f"   {label:6} 卡高={card.get('h')}底={card.get('bottom')}/{m.get('vh')} "
                      f"按钮={btn.get('text')!r}@{btn.get('bottom')} "
                      f"{'OK' if not probs else ''}")
                # 按钮：只在该有出口时才判缺；不该有时反过来盯一眼别摆出假按钮
                probs = [x for x in probs if not (x.startswith("NOBTN") and not need_btn)]
                if not need_btn and btn:
                    probs.append(f"FAKEBTN  这块屏没有可用的下一步，却摆了按钮：{btn.get('text')!r}")
                for p in probs:
                    bad += 1
                    print("     " + p)
                with open(os.path.join(OUT, f"panel-{w}x{h}-{label}.png"), "wb") as f:
                    f.write(base64.b64decode(
                        cdp.call("Page.captureScreenshot", {"format": "png"})["data"]))
        cdp.close()
    finally:
        proc.terminate()
        srv.terminate()
    print("== 结论 ==", "五块兜底屏在两种视口下都完整可读、按钮在屏内" if not bad else f"{bad} 处有问题")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
