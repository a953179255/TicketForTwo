package com.ticketfortwo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
        if (intent?.action == ACTION_STOP) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                type,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        // 关键：startForegroundService() 是异步的，光"启动了服务"不够。
        // Android 14+ 要求在 getMediaProjection() 之前服务**已经进入前台**，
        // 否则系统抛 SecurityException: Media projections require a foreground
        // service of type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION（实测踩过）。
        // 所以上层必须 awaitReady() 之后才能开始采集。
        _foregroundReady.value = true
        watchViewer()
        return START_STICKY
    }

    private fun buildNotification(type: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val voiceOnly = type == ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_sharing_title))
            .setContentText(
                if (SignalHub.viewerConnected.value) "1 人正在观看"
                else getString(if (voiceOnly) R.string.notif_voice_text else R.string.notif_sharing_text)
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
    }

    override fun onDestroy() {
        viewerJob?.cancel()
        uiScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        _foregroundReady.value = false
        if (!CallSession.isActive) CallSession.stop(applicationContext)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "share"
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
         */
        suspend fun awaitReady(timeoutMs: Long = 3_000): Boolean =
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

        fun stop(context: Context) {
            _foregroundReady.value = false
            context.stopService(Intent(context, ShareService::class.java))
        }
    }
}
