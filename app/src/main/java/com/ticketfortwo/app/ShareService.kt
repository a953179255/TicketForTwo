package com.ticketfortwo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ticketfortwo.app.signaling.SignalHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 分享期间的前台服务。
 *
 * 存在的核心理由不是保活，而是**顺序**：Android 14+ 要求
 * "先拿到授权 → 再启动 mediaProjection 类型的前台服务 → 才能 getMediaProjection()"，
 * 顺序错了直接抛异常。所以这个服务必须在 CallSession.startHost 之前起来。
 *
 * 通知上的"停止"是系统要求的可见停止入口，用户随时可以从这里切断。
 */
class ShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var viewerJob: Job? = null

    /** 建通知时要按"当前有没有人在看"写文案，所以得记住服务类型。 */
    private var fgsType: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

    /**
     * 盯着观众在线状态刷新通知。
     *
     * 为什么放通知里而不是只放界面里：用户分享时多半已经把 App 最小化了，
     * 通知是那一刻唯一看得见的地方。之前房主侧的 peerLabel 是写死的「已直连」，
     * 于是"到底有没有人进来"这个问题在 App 里根本无从回答。
     */
    private fun watchViewer() {
        viewerJob?.cancel()
        viewerJob = uiScope.launch {
            SignalHub.viewerConnected.collect { repost() }
        }
    }

    private fun repost() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(fgsType))
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i(
            "ShareService",
            "onStartCommand action=${intent?.action ?: "null(intent)"} startId=$startId",
        )
        /* START_STICKY 的 null-intent 重启只会在进程死后发生 —— 而 CallSession 与
           本服务同进程，那时会话也没了，继续跑只会立着一条没人认领的前台通知
           （实测出现过 id=1001 孤儿通知，REVIEW-2026-09-27 P2）。直接退出。 */
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP) {
            CallSession.stop(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }
        // 类型由启动方给：带画面走 mediaProjection，仅语音走 microphone ——
        // 两种都必须在 manifest 里声明，传了没声明的类型直接 SecurityException。
        val type = intent?.getIntExtra(
            EXTRA_FGS_TYPE,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        ) ?: ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        fgsType = type
        val notification = buildNotification(type)
        // 三参重载要求 API 29；minSdk 33，原来那个不可达的 else 分支已删。
        startForeground(NOTIF_ID, notification, type)
        // 关键：startForegroundService() 是异步的，光"启动了服务"不够。
        // Android 14+ 要求在 getMediaProjection() 之前服务**已经进入前台**，
        // 否则系统抛 SecurityException: Media projections require a foreground
        // service of type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION（实测踩过）。
        // 所以上层必须 awaitReady() 之后才能开始采集。
        _foregroundReady.value = true
        watchViewer()
        // NOT_STICKY：唯一会走到这里的"重启"是进程死后的 null intent（上面已拦）。
        // 会话与本服务同进程，进程死了会话也没了 —— 没有任何值得重启后继续的状态。
        return START_NOT_STICKY
    }

    private fun buildNotification(type: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        /* 必须 getForegroundService 直指本服务：原来写的 getBroadcast 发的是
           广播 Intent（只有 setPackage），而全仓没有任何 receiver 接它 ——
           onStartCommand 的 ACTION_STOP 分支永不可达，通知上那颗"停止"是死的
           （REVIEW-2026-09-27 P1：类注释和 PLAN 都承诺"随时可切断"）。 */
        val stop = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val voiceOnly = type == ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        /* 标题跟着"这次到底在分享什么"走：厅先开、只连麦起的都是 microphone 服务，
           根本没有投屏，顶着「正在分享给朋友」就是谎话 —— 用户实测反馈正是
           "选择了一起放一部片，进去放映厅以后，它就默认开始分享了"（他没开过投屏，
           看到的却是分享中）。渠道也分开：设置页里叫「屏幕分享」的渠道挂着一条
           语音通知，同样会误导人以为在投屏。 */
        return NotificationCompat.Builder(this, if (voiceOnly) CHANNEL_VOICE_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(
                getString(if (voiceOnly) R.string.notif_voice_title else R.string.notif_sharing_title)
            )
            .setContentText(
                when {
                    voiceOnly && SignalHub.viewerConnected.value -> getString(R.string.notif_voice_joined)
                    voiceOnly -> getString(R.string.notif_voice_text)
                    SignalHub.viewerConnected.value -> "1 人正在观看"
                    else -> getString(R.string.notif_sharing_text)
                }
            )
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        if (nm.getNotificationChannel(CHANNEL_VOICE_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_VOICE_ID,
                    getString(R.string.notif_channel_voice),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    override fun onDestroy() {
        android.util.Log.i("ShareService", "onDestroy（诊断：谁停了我）")
        viewerJob?.cancel()
        uiScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        _foregroundReady.value = false
        /* 撤通知不能只指望框架：实测「停止分享」后出现过 id=1001 的孤儿 ONGOING
           （ServiceRecord=0、通知还在，force-stop 才被系统清掉）—— 疑似
           watchViewer 的 repost 与 onDestroy 的竞态。服务已死，通知必须跟着死，
           这里显式 cancel 兜底（正常路径框架已经撤过，重复 cancel 无害）。 */
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        if (!CallSession.isActive) CallSession.stop(applicationContext)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "share"
        /** 仅语音（放映厅 / 只连麦）那类通知单独一个渠道，理由见 buildNotification。 */
        private const val CHANNEL_VOICE_ID = "voice"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.ticketfortwo.app.action.STOP_SHARE"
        const val EXTRA_FGS_TYPE = "com.ticketfortwo.app.extra.FGS_TYPE"

        private val _foregroundReady = MutableStateFlow(false)

        /**
         * 等服务**真正进入前台**再返回。
         *
         * 这条等待是必需的，不是保险：startForegroundService() 是异步的，
         * 若在 startForeground() 完成前调 getMediaProjection()，
         * Android 14+ 直接抛 SecurityException（实测崩溃过）。
         *
         * 超时 3s → 30s（2026-10-01）：系统日志抓到 startForegroundDelayMs=12725/14505 ——
         * 进厅时浏览 tab 的 WebView 自动加载上次的重页面（视频自动播），主线程被挤，
         * startForeground 稳定晚到 12-15s。3s/15s 在这个场景必失败，且失败后 UI 还挂着
         * "厅已开"（隧道没建，观众永远进不来）。等待本身是协程挂起，放宽不卡界面；
         * 正常负载下 1s 内就 ready，只有重页面抢主线程时才会等到十几秒。
         */
        suspend fun awaitReady(timeoutMs: Long = 30_000): Boolean =
            withTimeoutOrNull(timeoutMs) { _foregroundReady.first { it } } != null

        /** 按 Android 14+ 要求的顺序启动：授权之后、getMediaProjection 之前。
         *  仅语音模式传 [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE]。 */
        fun start(
            context: Context,
            type: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        ) {
            _foregroundReady.value = false
            val i = Intent(context, ShareService::class.java).putExtra(EXTRA_FGS_TYPE, type)
            context.startForegroundService(i)
        }
    }
}
