package com.ticketfortwo.app.ui.app

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticketfortwo.app.cinema.CinemaSync
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 双击的落点分区：左 = 快退、中 = 播放/暂停、右 = 快进（雨见同款玩法）。 */
enum class TapZone { Left, Center, Right }

/** 拖动此刻在干什么 —— 方向首决，之后锁定。 */
private enum class DragMode { Undetermined, Seek, Vertical, Boost }

/** 竖滑落在哪一侧：左 = 亮度、右 = 音量。 */
private enum class Side { Bright, Volume }

/**
 * 放映画面的**手势层 + HUD**（方案 C「极简 HUD」，2026-10-01 定稿，雨见化）。
 *
 * 三态（回显轨 / 自播 / 网页兜底）共用这一层，挂在三态之上；**只在放映态挂**
 * （浏览态网页要收点击）。全屏态的"轻点唤回甲板"与单击出 HUD 合并成一个手势。
 *
 * 手势全景（与 docs/mockups/player-deck-revamp.html 的规格一致）：
 *  - 单击（250ms 内无第二击才回调）= HUD 浮现 1.5 秒；全屏态顺带唤回甲板
 *  - 双击三区：左 = 快退 [stepSec] · 中 = 暂停/继续 · 右 = 快进 [stepSec]
 *  - 按住横滑 = 拖动进度（中央时间气泡，松手提交）；按住底边发丝线 = 绝对定位
 *  - 左半屏竖滑 = 亮度（只影响本窗口）；右半屏竖滑 = 音量
 *  - 长按 = 倍速起步（档位第一档）；按住上滑逐档升、下滑降（1x → 0.5x 封底），
 *    **松手保持**所选档；右上角小倍速标，点它回 1x
 *
 * 架构：tap 与 drag 分两个 pointerInput —— 拖动会 cancel 掉 tap 的
 * waitForUpOrCancellation（拖动中不误出单击）；竖滑全部**锚定式**（按下时的
 * 起始值 + 累计位移换算），避免"每像素增量取整为 0"的死区。
 */
@Composable
fun PlayerGestureOverlay(
    playing: Boolean,
    posMs: Long,
    durMs: Long,
    rate: Double,
    ladder: List<Double>,
    stepSec: Int,
    isFullScreen: Boolean,
    onSingleTap: () -> Unit,
    onDoubleTap: (TapZone) -> Unit,
    onSeek: (frac: Float) -> Unit,
    onRate: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /* ── 视觉状态 ─────────────────────────────────────────── */
    var hudVisible by remember { mutableStateOf(false) }
    var hudJob by remember { mutableStateOf<Job?>(null) }
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }   // 拖动中：目标时间
    var side by remember { mutableStateOf<Side?>(null) }            // 竖滑中：哪一侧
    var briNow by remember { mutableStateOf(0.5f) }                 // 当前亮度比（画条）
    var volNow by remember { mutableStateOf(0.5f) }                 // 当前音量比（画条）
    var boostActive by remember { mutableStateOf(false) }           // 长按倍速手势进行中
    var boostStep by remember { mutableStateOf(0) }                 // 手势中的相对档位步
    var feedback by remember { mutableStateOf<String?>(null) }      // 单/双击操作的短反馈（-10s 等）
    var feedbackJob by remember { mutableStateOf<Job?>(null) }

    fun showHud() {
        hudJob?.cancel()
        hudVisible = true
        hudJob = scope.launch {
            delay(1_500)
            hudVisible = false
        }
    }

    fun flash(text: String) {
        feedbackJob?.cancel()
        feedback = text
        showHud()
        feedbackJob = scope.launch {
            delay(1_200)
            feedback = null
        }
    }

    // 退出放映（rate 复位 1.0）时收掉倍速手势残影
    LaunchedEffect(rate) {
        if (rate == 1.0) {
            boostActive = false
            boostStep = 0
        }
    }

    val audio = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /* ── 倍速档位：索引 >= size 封顶最大档；-1 = 1x；<= -2 = 0.5x 封底 ── */
    fun rateAt(idx: Int): Double = when {
        idx >= ladder.size -> ladder.max()
        idx >= 0 -> ladder[idx]
        idx == -1 -> 1.0
        else -> 0.5
    }

    Box(
        modifier
            .fillMaxSize()
            // ── 拖动：横滑 seek / 竖滑亮度音量 / 倍速升档 ──
            .pointerInput(ladder, durMs) {
                var mode = DragMode.Undetermined
                var startX = 0f
                var startFrac = 0f
                var accX = 0f
                var accY = 0f
                var briStart = 0f
                var volStart = 0
                var ladIdx = 0      // 本段拖动的档位锚（从按下时的 rate 换算）

                fun windowBrightness(): Float =
                    (context as? Activity)?.window?.attributes?.screenBrightness
                        ?.takeIf { it >= 0f } ?: 0.5f

                detectDragGestures(
                    onDragStart = { at ->
                        mode = DragMode.Undetermined
                        startX = at.x
                        startFrac = if (durMs > 0) posMs.toFloat() / durMs else 0f
                        accX = 0f
                        accY = 0f
                        briStart = windowBrightness()
                        volStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        ladIdx = ladder.indexOfFirst { it == rate }
                            .let { if (it >= 0) it else if (rate == 1.0) -1 else 0 }
                        boostStep = 0
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        if (mode == DragMode.Undetermined) {
                            if (abs(drag.x) < 6f && abs(drag.y) < 6f) return@detectDragGestures
                            mode = when {
                                abs(drag.x) > abs(drag.y) -> DragMode.Seek
                                boostActive -> DragMode.Boost
                                else -> DragMode.Vertical
                            }
                            if (mode == DragMode.Vertical) {
                                val left = startX < size.width / 2f
                                side = if (left) Side.Bright else Side.Volume
                                val maxV = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                volNow = if (maxV > 0) volStart.toFloat() / maxV else 0f
                                briNow = briStart
                            }
                        }
                        when (mode) {
                            DragMode.Seek -> {
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                // dragAmount 是**每帧增量**，必须累计 —— 只拿单帧
                                // 会让落点变成"最后一帧的几像素"（实测横滑 400px
                                // 只 seek 出 +5s，正是最后一帧 ~8px 的换算）。
                                accX += drag.x
                                val frac = (startFrac + accX / w).coerceIn(0f, 1f)
                                seekPreviewMs = (frac * durMs).toLong()
                                showHud()
                            }
                            DragMode.Vertical -> {
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                accY += drag.y
                                // **上滑为正**（播放器惯例：上滑加音量/加亮度）——
                                // accY 向下为正，取负号。锚定式：按下起点 ± 一屏位移。
                                val fracDelta = (-accY / h).coerceIn(-1f, 1f)
                                if (side == Side.Bright) {
                                    // 锚定式：按下时亮度 ± 一屏 0.8 的位移，无取整死区
                                    val next = (briStart + fracDelta * 0.8f).coerceIn(0.03f, 1f)
                                    (context as? Activity)?.let { a ->
                                        a.window.attributes = a.window.attributes
                                            .apply { screenBrightness = next }
                                    }
                                    briNow = next
                                } else {
                                    val maxV = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val target = (volStart + (fracDelta * 0.9f * maxV).roundToInt())
                                        .coerceIn(0, maxV)
                                    if (target != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                                    }
                                    volNow = if (maxV > 0) target.toFloat() / maxV else 0f
                                }
                                showHud()
                            }
                            DragMode.Boost -> {
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                accY += drag.y
                                // 一屏的 22% 换一档；上滑（accY<0）升
                                val step = (-accY / (h * 0.22f)).roundToInt()
                                if (step != boostStep) {
                                    boostStep = step
                                    onRate(rateAt(ladIdx + step))
                                    showHud()
                                }
                            }
                            else -> Unit
                        }
                    },
                    onDragEnd = {
                        when (mode) {
                            DragMode.Seek -> {
                                val target = seekPreviewMs
                                if (target != null && durMs > 0) {
                                    onSeek((target.toFloat() / durMs).coerceIn(0f, 1f))
                                    flash("→ " + CinemaSync.formatTime(target))
                                }
                                seekPreviewMs = null
                            }
                            DragMode.Vertical -> side = null
                            DragMode.Boost -> boostStep = 0   // 松手保持当前档（角标留在右上角）
                            else -> Unit
                        }
                        mode = DragMode.Undetermined
                    },
                    onDragCancel = {
                        seekPreviewMs = null
                        boostStep = 0
                        side = null
                        mode = DragMode.Undetermined
                    },
                )
            }
            // ── 抬手观察层（只读不消费）：长按倍速的抬手钩子 ──
            // detectTapGestures 的 onLongPress 之后**没有"长按结束"回调**，
            // 不复位的话 boostActive 残留 —— 下一次普通竖滑会被误判成升倍速。
            // 这层盯着所有指头抬起，把手势态收干净（对 tap/drag 两层零干扰）。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val allUp = ev.changes.none { it.pressed }
                        if (allUp) {
                            boostActive = false
                            boostStep = 0
                        }
                    }
                }
            }
            // ── 点按：单击 / 双击三区 / 长按 ──
            .pointerInput(stepSec, ladder) {
                detectTapGestures(
                    onTap = {
                        if (isFullScreen) onSingleTap()   // 全屏态：唤回甲板 + HUD 一起给
                        showHud()
                    },
                    onDoubleTap = { at ->
                        val zone = when {
                            at.x < size.width * 0.33f -> TapZone.Left
                            at.x > size.width * 0.67f -> TapZone.Right
                            else -> TapZone.Center
                        }
                        when (zone) {
                            TapZone.Left -> flash("⟲ $stepSec 秒")
                            TapZone.Right -> flash("⟳ $stepSec 秒")
                            TapZone.Center -> flash(if (playing) "已暂停" else "继续播放")
                        }
                        onDoubleTap(zone)
                    },
                    onLongPress = {
                        boostActive = true
                        boostStep = 0
                        onRate(ladder.first())
                        flash("×" + ladder.first() + " 倍速 · 上滑升档")
                    },
                )
            },
    ) {
        /* ── 发丝进度线：常驻画面底边，横拖 = 绝对定位 seek ── */
        val hairFrac = if (durMs > 0) (seekPreviewMs ?: posMs).toFloat() / durMs else 0f
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(14.dp)
                .pointerInput(durMs) {
                    detectDragGestures(
                        onDragStart = { },
                        onDrag = { change, _ ->
                            change.consume()
                            if (durMs > 0) {
                                val frac = (change.position.x / size.width.toFloat())
                                    .coerceIn(0f, 1f)
                                seekPreviewMs = (frac * durMs).toLong()
                                showHud()
                            }
                        },
                        onDragEnd = {
                            val target = seekPreviewMs
                            if (target != null && durMs > 0) {
                                onSeek((target.toFloat() / durMs).coerceIn(0f, 1f))
                                flash("→ " + CinemaSync.formatTime(target))
                            }
                            seekPreviewMs = null
                        },
                        onDragCancel = { seekPreviewMs = null },
                    )
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(99))
                    .background(Color(0x59FFFFFF)),
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(hairFrac.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(99))
                    .background(
                        Brush.horizontalGradient(listOf(Color(0xFF7AD8C3), Color(0xFFBFE7FF))),
                    ),
            )
        }

        /* ── HUD：单击/操作时浮现 1.5 秒；倍速手势期间常驻 ── */
        if (hudVisible || boostActive) {
            val show = seekPreviewMs ?: posMs
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xB30B0B14))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        feedback ?: (CinemaSync.formatTime(show) + " / " +
                            CinemaSync.formatTime(durMs)),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val pv = seekPreviewMs
                    if (pv != null) {
                        val delta = (pv - posMs) / 1000
                        Text(
                            "  " + (if (delta >= 0) "+" else "−") + "${abs(delta)}s",
                            color = Color(0xFF7AD8C3),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        /* ── 倍速角标：rate≠1 常驻，点它回 1x ── */
        if (rate != 1.0 && !boostActive) {
            val ind = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = 14.dp)
                    .clip(RoundedCornerShape(99))
                    .background(Color(0xB30B0B14))
                    .clickable(interactionSource = ind, indication = null) { onRate(1.0) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    "×" + ("%.1f".format(rate).trimEnd('0').trimEnd('.')),
                    color = Color(0xFFFFD08A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        /* ── 档位条：倍速手势中显示当前倍数与操作提示 ── */
        if (boostActive) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
                    .width(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xB30B0B14))
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "×" + ("%.1f".format(rate).trimEnd('0').trimEnd('.')),
                    color = Color(0xFFFFD08A),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (boostStep >= 0) "上滑升档" else "下滑降档",
                    color = Color(0xCCFFFFFF),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        /* ── 亮度 / 音量竖条：对应侧竖滑时浮现 ── */
        val s = side
        if (s != null) {
            val left = s == Side.Bright
            val frac = if (left) briNow else volNow
            Box(
                Modifier
                    .align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 18.dp)
                    .width(34.dp)
                    .height(170.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x800B0B14)),
            ) {
                // 轨道
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .width(6.dp)
                        .fillMaxSize(0.72f)
                        .clip(RoundedCornerShape(99))
                        .background(Color(0x4DFFFFFF)),
                )
                // 已量（自下而上）
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .width(6.dp)
                        .height((170.dp * 0.72f * frac).coerceAtLeast(4.dp))
                        .clip(RoundedCornerShape(99))
                        .background(if (left) Color(0xFFFFE08A) else Color(0xFF8ECDF7)),
                )
                Text(
                    if (left) "☀" else "🔊",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                )
                Text(
                    "${(frac * 100).roundToInt()}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}
