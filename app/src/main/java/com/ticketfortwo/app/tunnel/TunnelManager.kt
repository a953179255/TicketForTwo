package com.ticketfortwo.app.tunnel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.concurrent.thread

/**
 * 用 cloudflared 给本机的「传话员」挂一块临时门牌。
 *
 * ## 为什么非要有这一环
 *
 * 手机在运营商网络里拿不到「外人能直接敲进来」的地址（CGNAT）。cloudflared 的做法是
 * **反过来主动连出去**：本机进程连到 Cloudflare 边缘，边缘把公网请求沿这条已建立的
 * 连接送回来。所以房主不需要公网 IP、不需要端口映射 —— 只需要能上网。
 *
 * 这块门牌是临时的（每次启动域名都不同），Cloudflare 也不承诺可用性。
 * 对「临时给朋友看一下屏幕」这个场景够用；要长期固定地址就得上自建站点。
 *
 * ## 关于可执行文件
 *
 * Android 10 起禁止从可写目录执行文件。绕法是打包进 `jniLibs`（只读）、
 * 文件名必须以 `lib` 开头且以 `.so` 结尾，系统会把它放到 [applicationInfo.nativeLibraryDir]
 * 并给上执行权限。所以打包的二进制叫 `libcloudflared.so`，
 * 而且 build.gradle 里必须开 `useLegacyPackaging`（否则不会解压到磁盘）。
 */
object TunnelManager {

    private const val TAG = "TunnelManager"

    /** 打包名。必须 lib 前缀 + .so 后缀，见类注释。 */
    private const val BINARY_NAME = "libcloudflared.so"

    /** 超过这个时间还没拿到地址就判失败。取值对齐 Piik 的做法。 */
    private const val START_TIMEOUT_MS = 30_000L

    /** Cloudflare 分配下来的临时域名长这样。 */
    private val originPattern =
        Regex("""https://[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.trycloudflare\.com""")

    /**
     * 这不是门牌，是 Cloudflare 的接口域名。
     *
     * cloudflared 在预检 / 注册阶段就会把 `api.trycloudflare.com` 写进日志，
     * 而它长得完全符合上面的正则 —— 于是"取第一条匹配"会把它当成分配到的隧道地址。
     * 实测后果：邀请链接变成 `https://api.trycloudflare.com/?k=…`，
     * 朋友点开得到 **HTTP 405**，页面根本加载不出来。
     * 所以匹配到它必须跳过，继续等真正那条 `xxx-yyy-zzz.trycloudflare.com`。
     */
    private val notATunnelOrigin = "https://api.trycloudflare.com"

    sealed interface State {
        data object Idle : State

        /** 正在申请门牌（通常几秒）。 */
        data object Starting : State

        /** 门牌可用。 */
        data class Ready(val origin: String) : State

        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var process: Process? = null

    @Volatile
    private var stopping = false

    /** 当前可用的公网 origin，没有就是 null。 */
    val origin: String?
        get() = (_state.value as? State.Ready)?.origin

    // ---- 二进制定位 ------------------------------------------------------

    fun binaryFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    /**
     * 打包是否带上了隧道程序。
     *
     * 单独抽出来是为了能在开分享**之前**就告诉用户「这版 APK 没带隧道」，
     * 而不是等他点了开始、等 30 秒、再得到一个看不懂的失败。
     */
    fun isAvailable(context: Context): Boolean = binaryFile(context).let { it.isFile && it.canExecute() }

    // ---- 启动 / 停止 -----------------------------------------------------

    /**
     * 起隧道并把 [localPort] 暴露出去。返回是否成功；失败原因在 [state] 里。
     *
     * 用 `--protocol auto`：Quick Tunnel 默认强制 QUIC，而部分网络会拦 UDP，
     * 这时让它自己回退到 TCP 才连得上。
     */
    suspend fun start(context: Context, localPort: Int): Boolean = withContext(Dispatchers.IO) {
        stop()
        stopping = false

        val bin = binaryFile(context)
        if (!bin.isFile) {
            _state.value = State.Failed("这版 App 没带隧道程序，请重新完整安装一次")
            return@withContext false
        }
        if (!bin.canExecute()) {
            _state.value = State.Failed("隧道程序无法执行，请卸载后重新安装")
            return@withContext false
        }

        _state.value = State.Starting
        val ready = CompletableDeferred<String>()

        try {
            val pb = ProcessBuilder(
                bin.absolutePath,
                "tunnel",
                "--url", "http://127.0.0.1:$localPort",
                "--no-autoupdate",
                "--protocol", "auto",
            )
            pb.directory(context.cacheDir)
            // cloudflared 想读写自己的配置与临时文件，指到 App 自己的目录里，
            // 否则它会去碰 /data 下没有权限的位置并留下噪声日志。
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.redirectErrorStream(true)

            val p = pb.start()
            process = p
            thread(name = "t2-tunnel-log", isDaemon = true) { pump(p, ready) }

            val origin = withTimeoutOrNull(START_TIMEOUT_MS) {
                runCatching { ready.await() }.getOrNull()
            }

            if (origin == null) {
                /* 用户在启动窗口里点了停止（stop() 先把进程杀了，ready 随之异常返回）：
                   这不是失败，写 Failed 会把"取消"显示成红色网络故障页
                   （REVIEW-2026-09-27 P1）。进程已被 stop() 收走，直接回 false。 */
                if (stopping) return@withContext false
                val exited = process?.isAlive != true
                stop()
                _state.value = State.Failed(
                    if (exited) "隧道程序退出了：当前网络可能连不上 Cloudflare"
                    else "30 秒内没能拿到临时地址，请检查网络后重试",
                )
                return@withContext false
            }

            // await 与 Ready 之间被 stop() 插入的窄窗口：别把 Ready 写给一个已死进程。
            if (stopping) return@withContext false
            _state.value = State.Ready(origin)
            Log.i(TAG, "门牌已就绪 $origin → 127.0.0.1:$localPort")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（CallSession.stop 取消了启动 Job）不是"隧道启动异常"：
            // 照 catch (Throwable) 写 Failed 会给上层递一条假失败。原样抛出去。
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "隧道启动异常：${t.message}")
            stop()
            _state.value = State.Failed("隧道启动失败：${t.message}")
            false
        }
    }

    fun stop() {
        stopping = true
        val p = process
        process = null
        if (p != null) {
            runCatching { p.destroy() }
            // 给它 1.5 秒走完有序退出；超时就强杀，别让一个坏进程拖着分享不放。
            runCatching {
                val done = thread(name = "t2-tunnel-wait", isDaemon = true) { p.waitFor() }
                done.join(1_500)
                if (p.isAlive) p.destroyForcibly()
            }
        }
        if (_state.value !is State.Failed) _state.value = State.Idle
    }

    /** 主动清掉失败态（重新申请门牌前调用）。 */
    fun resetState() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    // ---- 日志泵 ----------------------------------------------------------

    /**
     * 持续读子进程输出。
     *
     * 两件事一起做：**抽门牌地址**、**排空管道**。
     * 后者不能省 —— 不读的话子进程写满管道缓冲后会卡死。
     */
    private fun pump(p: Process, ready: CompletableDeferred<String>) {
        try {
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("trycloudflare", ignoreCase = true)) {
                        Log.i(TAG, "cloudflared: $line")
                    } else {
                        Log.v(TAG, "cloudflared: $line")
                    }
                    if (!ready.isCompleted) {
                        originPattern.find(line.lowercase())?.value
                            // api.trycloudflare.com 是 Cloudflare 接口域名，不是门牌，跳过继续等
                            ?.takeIf { it != notATunnelOrigin }
                            ?.let { ready.complete(it) }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "隧道日志流结束：${t.message}")
        } finally {
            if (!ready.isCompleted) {
                ready.completeExceptionally(IllegalStateException("隧道进程已退出"))
            }
            if (!stopping) {
                Log.w(TAG, "隧道进程意外结束")
                _state.value = State.Failed("门牌失效：隧道连接中断，请重新开始分享")
            }
        }
    }
}
