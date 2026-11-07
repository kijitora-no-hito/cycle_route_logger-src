package jp.kjtr.cycleroutelogger.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import jp.kjtr.cycleroutelogger.CycleApp
import jp.kjtr.cycleroutelogger.MainActivity
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.TrackEventEntity
import jp.kjtr.cycleroutelogger.ui.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * 記録用のフォアグラウンドサービス（type = location）。**記録の点を取り込むのはこのサービスだけ**
 * （画面の測位（owner "ui"）が止まっても、ここが LocationHub を "record" で持ち続ける）。画面を消しても・
 * ホームで裏に回しても・他のアプリを開いても記録を続ける。
 *
 * - 開始はアプリが前面にあるとき（走行画面の［記録開始］）。「アプリの使用中のみ」の位置の許可で、裏に回っても位置が届く。
 *   始められなかったら [RecorderStatus.startFailure] に理由を入れて止める（画面がダイアログで知らせる）。
 * - プロセスごと落とされてシステムが再起動したとき（START_STICKY、intent = null）は、位置の「常に許可」があれば
 *   location 型で始め直して記録を続ける（出来事 restarted を残す）。無い・始められないときは「記録が止まりました」の
 *   通知を出して止め、次に開いたときの「記録の途中で終わっています」から続けてもらう。
 * - 記録中に位置情報が OFF にされた・使える位置が [NO_FIX_MS] 届かないときは、通知と記録の出来事に残す。
 *   位置情報が ON に戻れば（LocationHub は無効なプロバイダにも登録してあるので）自動で続きが記録される。
 */
class RecordingService : LifecycleService() {

    private val app get() = application as CycleApp
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var tickJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE -> app.session.pause()
            ACTION_RESUME -> app.session.resume()
            else -> {
                if (!started) {
                    val restartedBySystem = intent == null
                    if (restartedBySystem && !app.session.isActive && !RecorderStatus.hasBackgroundLocation(this)) {
                        // 「使用中のみ」の許可では、裏から location 型で始め直せない（Android 14+ は例外になる）
                        notifyStopped()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    val failure = startForegroundSafely()
                    if (failure != null) {
                        if (restartedBySystem) notifyStopped() else app.recorder.setFailure(failure)
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    begin(restartedBySystem)
                }
            }
        }
        return START_STICKY
    }

    private fun begin(restartedBySystem: Boolean) {
        started = true
        app.recorder.setFailure(null)
        app.recorder.setRunning(true)
        cancelStoppedNotice()
        app.locationHub.acquire(OWNER)
        acquireWakeLock()
        lifecycleScope.launch {
            if (restartedBySystem && !app.session.isActive) {
                // プロセスごと落ちた後の再起動: 途中の記録を続ける
                val t = app.tracks.unfinished()
                if (t == null || !app.session.resumeUnfinished(t.id, TrackEventEntity.TYPE_RESTARTED)) {
                    stopSelf()
                    return@launch
                }
            }
            app.locationHub.updates.collect { app.session.onLocation(it) }
        }
        lifecycleScope.launch {
            var seenActive = false
            app.session.state.distinctUntilChangedBy { it?.paused to (it == null) }.collect { s ->
                if (s == null) {
                    // 記録を終えた（始まる前の null は無視する）
                    if (seenActive) stopSelf()
                } else {
                    seenActive = true
                    updateNotification()
                }
            }
        }
        // 位置情報の ON / OFF
        lifecycleScope.launch {
            app.locationHub.locationEnabled.collect { on ->
                app.recorder.setLocationOff(!on)
                if (app.session.isActive) app.session.setCondition(TrackEventEntity.TYPE_LOCATION_OFF, !on)
                updateNotification()
                updateAlert()
            }
        }
        tickJob = lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            while (true) {
                delay(NOTIFY_INTERVAL_MS)
                if (!app.session.isActive) continue
                app.locationHub.refreshEnabled()
                // 使える位置が届かない（一時停止中・位置情報 OFF のときは数えない）
                val s = app.session.state.value
                val since = maxOf(app.locationHub.lastGoodFixElapsedMs, startedAt)
                val noFix = s != null && !s.paused && !app.recorder.locationOff.value &&
                    SystemClock.elapsedRealtime() - since > NO_FIX_MS
                if (noFix != app.recorder.noFix.value) {
                    app.recorder.setNoFix(noFix)
                    app.session.setCondition(TrackEventEntity.TYPE_NO_FIX, noFix)
                    updateAlert()
                }
                updateNotification()
            }
        }
    }

    override fun onDestroy() {
        tickJob?.cancel()
        if (started) {
            app.locationHub.release(OWNER)
            app.recorder.setRunning(false)
        }
        cancelAlert()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CycleRouteLogger:record").apply {
            setReferenceCounted(false)
            // 念のための上限（24 時間）。記録を終えれば onDestroy で外す。
            acquire(24L * 60 * 60 * 1000)
        }
    }

    /** フォアグラウンドにする。できなければ理由。 */
    private fun startForegroundSafely(): StartFailure? {
        createChannels()
        return try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            null
        } catch (e: RuntimeException) {
            // 前面でない（Android 12+ の ForegroundServiceStartNotAllowedException）・位置の権限が無い（SecurityException）など
            RecorderStatus.classify(e)
        }
    }

    private fun updateNotification() {
        if (!started) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(): Notification {
        val s = app.session.state.value
        val title = when {
            s == null -> getString(R.string.notif_title_recording)
            s.paused -> getString(R.string.notif_title_paused)
            app.recorder.locationOff.value -> getString(R.string.notif_title_location_off)
            app.recorder.noFix.value -> getString(R.string.notif_title_no_fix)
            s.autoPaused -> getString(R.string.notif_title_auto_paused)
            else -> getString(R.string.notif_title_recording)
        }
        val text = if (s == null) "" else getString(
            R.string.notif_text,
            Format.duration(System.currentTimeMillis() - s.startedAt),
            Format.duration(s.movingMs),
            Format.km(s.distanceM),
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (s != null) {
            val (action, label) = if (s.paused) ACTION_RESUME to R.string.resume else ACTION_PAUSE to R.string.pause
            val pi = PendingIntent.getService(
                this, if (s.paused) 2 else 1, Intent(this, RecordingService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, getString(label), pi)
        }
        return b.build()
    }

    /** 位置情報 OFF・位置が届かないときの知らせ（音の出るチャンネル。戻ったら消す）。 */
    private fun updateAlert() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = when {
            !app.session.isActive -> null
            app.recorder.locationOff.value -> getString(R.string.notif_alert_location_off)
            app.recorder.noFix.value -> getString(R.string.notif_alert_no_fix)
            else -> null
        }
        if (text == null) {
            nm.cancel(ALERT_ID)
            return
        }
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(R.string.notif_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(ALERT_ID, n) }
    }

    private fun cancelAlert() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_ID)
    }

    /** 「記録が止まりました。開いて続ける」（裏から始め直せなかったとき）。 */
    private fun notifyStopped() {
        createChannels()
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(getString(R.string.notif_stopped_title))
            .setContentText(getString(R.string.notif_stopped_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_stopped_text)))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(STOPPED_ID, n) }
    }

    private fun cancelStoppedNotice() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(STOPPED_ID)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            ch.description = getString(R.string.notif_channel_desc)
            nm.createNotificationChannel(ch)
            val alert = NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.notif_alert_channel), NotificationManager.IMPORTANCE_DEFAULT)
            alert.description = getString(R.string.notif_alert_channel_desc)
            nm.createNotificationChannel(alert)
        }
    }

    companion object {
        private const val OWNER = "record"
        private const val CHANNEL_ID = "recording"
        private const val ALERT_CHANNEL_ID = "recording_alert"
        private const val NOTIF_ID = 7
        private const val ALERT_ID = 8
        private const val STOPPED_ID = 9
        private const val NOTIFY_INTERVAL_MS = 5_000L
        /** これだけ使える位置が届かなければ「GPS を受信できていません」。 */
        const val NO_FIX_MS = 60_000L
        const val ACTION_START = "jp.kjtr.cycleroutelogger.record.START"
        const val ACTION_PAUSE = "jp.kjtr.cycleroutelogger.record.PAUSE"
        const val ACTION_RESUME = "jp.kjtr.cycleroutelogger.record.RESUME"

        /** 前面から呼ぶ。startForegroundService 自体が断られたら例外（呼ぶ側で [RecorderStatus.classify]）。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
