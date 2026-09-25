package com.ticketfortwo.app.ui.app

import android.app.Activity
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticketfortwo.app.ui.theme.Ink
import kotlinx.coroutines.delay

/**
 * 观众端的播放器手势层：点一下切换控件、左半屏上下滑调亮度、右半屏上下滑调音量。
 *
 * 为什么控件默认要收起来：这屏的内容是**对方的手机画面**，两块玻璃常驻就等于
 * 永远在别人视频上盖两条横幅。播放器惯例是几秒后自己收起、点一下再出来。
 *
 * 亮度与音量都刻意做成"只影响我们"：音量走 libwebrtc 的 `AudioTrack.setVolume`
 * （本机 jar 里确认过有这个方法），只改这一条远端流，不去动系统音量；
 * 亮度走 `Window.attributes.screenBrightness`，只作用于本窗口，
 * 退出观看时必须用 [resetActivityBrightness] 还原成 `BRIGHTNESS_OVERRIDE_NONE` ——
 * 忘了还原的话用户会觉得"用了这个 App 手机亮度坏了"。
 */
@Composable
fun ViewerGestureLayer(
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    /** 观众侧音量，0f..1f。 */
    onVolume: (Float) -> Unit,
) {
    val context = LocalContext.current
    val screenH = LocalConfiguration.current.screenHeightDp.toFloat()

    // 谁改的谁还原。挂在卸载上而不是挂在"观看中=false"上：
    // 实测过状态机停在 Ended 时 watching 仍算 true，于是亮度一直留在 10%，
    // 直到用户按 HOME 离开 App 才被系统收回（DisplayPowerController 那条
    // "reason changing from override(...)" 的日志就是证据）。卸载时机没有这个歧义。
    DisposableEffect(Unit) {
        onDispose { resetActivityBrightness(context as? Activity) }
    }

    var volume by remember { mutableFloatStateOf(1f) }
    // 系统没给覆盖值时 screenBrightness 是 -1，从 0.5 起步，避免第一次滑动就跳变
    var brightness by remember {
        mutableFloatStateOf(
            runCatching {
                (context as? Activity)?.window?.attributes?.screenBrightness
                    ?.takeIf { it > 0.01f } ?: 0.5f
            }.getOrDefault(0.5f)
        )
    }

    var hudKind by remember { mutableIntStateOf(0) }      // 0=亮度 1=音量
    var hudValue by remember { mutableFloatStateOf(0f) }
    // 用自增 tick 驱动淡出：直接拿 nanoTime 比大小不会触发重组，HUD 会永远停在那儿
    var hudTick by remember { mutableIntStateOf(0) }
    var hudVisible by remember { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(true) }

    LaunchedEffect(hudTick) {
        if (hudTick == 0) return@LaunchedEffect
        hudVisible = true
        delay(800)
        hudVisible = false
    }
    // 首次提示只出现一次，不打扰第二次
    LaunchedEffect(Unit) {
        delay(3_000)
        hintVisible = false
    }

    fun applyBrightness(v: Float) {
        brightness = v
        val a = context as? Activity ?: return
        a.window.decorView.post {
            val p = a.window.attributes
            p.screenBrightness = v
            a.window.attributes = p
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // 注册顺序要紧：拖拽先、点击后。Compose 的指针链是"先注册的先消费"，
            // 反过来时一次轻微上滑会被判成点击，调音量的同时控件来回闪。
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    val delta = -dy / screenH / 1.6f          // 划满一屏 ≈ 62% 行程
                    if (change.position.x < size.width * 0.5f) {
                        val v = (brightness + delta).coerceIn(0.02f, 1f)
                        applyBrightness(v)
                        hudKind = 0; hudValue = v; hudTick++
                    } else {
                        val v = (volume + delta).coerceIn(0f, 1f)
                        volume = v
                        onVolume(v)
                        hudKind = 1; hudValue = v; hudTick++
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleChrome() })
            }
    ) {
        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                Modifier
                    .width(128.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    (if (hudKind == 0) "亮度 " else "音量 ") + "${(hudValue * 100).toInt()}%",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White,
                )
            }
        }
        AnimatedVisibility(
            visible = hintVisible && !chromeVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp),
        ) {
            Text(
                "点一下屏幕唤出控件",
                fontSize = 11.5.sp, color = Ink.TextLow,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 退出观看时把亮度交还系统。
 *
 * 这里分两步写，不是洁癖，是实测逼出来的：
 * 直接把 `screenBrightness` 改回 `BRIGHTNESS_OVERRIDE_NONE` 之后，
 * 值确实传到了 WindowManager（按 HOME 触发焦点变化时会立刻回到 manual），
 * 但**当前这个窗口还聚焦着**，系统不会因此重算一次亮度 ——
 * 用户看到的就是"退出观看了，屏幕还停在 10%"。
 * 而写一个**真实数值**是会触发重算的（拖动过程中每一帧都在生效，就是这个证据）。
 * 所以：先写系统当前档位（触发重算，屏幕先回到正确亮度，视觉上无跳变），
 * 再把覆盖清掉（这一步不触发重算，但显示已经停在正确的档位上了）。
 */
fun resetActivityBrightness(activity: Activity?) {
    val a = activity ?: return
    runCatching {
        // 0..255 是 Settings.System 的刻度；取不到就按 40% 兜底，反正下一步就清覆盖
        val raw = runCatching {
            Settings.System.getInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 102)
        }.getOrDefault(102)
        val step1 = a.window.attributes
        step1.screenBrightness = (raw / 255f).coerceIn(0.05f, 1f)
        a.window.attributes = step1
        val step2 = a.window.attributes
        step2.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        a.window.attributes = step2
        a.window.decorView.requestLayout()
        Log.i("ViewerGesture", "窗口亮度已交还系统（两步写：$raw → 清覆盖）")
    }
}
