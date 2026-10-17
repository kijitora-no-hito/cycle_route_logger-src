package jp.kjtr.cycleroutelogger.record

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 記録サービスを始められなかった理由。 */
enum class StartFailure {
    /** 位置の権限が無い・外された（SecurityException）。 */
    LOCATION_PERMISSION,
    /** アプリが前面にないので始められない（ForegroundServiceStartNotAllowedException）。 */
    NOT_FOREGROUND,
    /** 4 秒待ってもサービスが始まらない（端末の電池の制限など）。 */
    TIMEOUT,
    OTHER,
}

/**
 * 記録サービスの様子（アプリ全体で 1 つ。サービスが書き、画面が読む）。
 * 記録の点を取り込むのはサービスだけ（[RecordingService]）。画面はこれを見て「バックグラウンドでも記録中」かを出す。
 */
class RecorderStatus {
    private val _serviceRunning = MutableStateFlow(false)
    /** サービスがフォアグラウンドで動いている（通知を出して位置を受けている）。 */
    val serviceRunning: StateFlow<Boolean> = _serviceRunning

    private val _startFailure = MutableStateFlow<StartFailure?>(null)
    val startFailure: StateFlow<StartFailure?> = _startFailure

    private val _locationOff = MutableStateFlow(false)
    /** 記録中に端末の位置情報（GPS）が OFF。 */
    val locationOff: StateFlow<Boolean> = _locationOff

    private val _noFix = MutableStateFlow(false)
    /** 記録中に一定時間、使える位置が届かない。 */
    val noFix: StateFlow<Boolean> = _noFix

    fun setRunning(v: Boolean) {
        _serviceRunning.value = v
        if (!v) {
            _locationOff.value = false
            _noFix.value = false
        }
    }

    fun setFailure(f: StartFailure?) {
        _startFailure.value = f
    }

    fun setLocationOff(v: Boolean) {
        _locationOff.value = v
    }

    fun setNoFix(v: Boolean) {
        _noFix.value = v
    }

    companion object {
        fun classify(e: Throwable): StartFailure = when {
            e is SecurityException -> StartFailure.LOCATION_PERMISSION
            Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException -> StartFailure.NOT_FOREGROUND
            else -> StartFailure.OTHER
        }

        /** 通知が出せる（Android 13+ は POST_NOTIFICATIONS の許可、かつ通知がオフにされていない）。 */
        fun notificationsAllowed(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

        /** 位置の「常に許可」（Android 9 以下は正確な位置の許可があれば同じ）。 */
        fun hasBackgroundLocation(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT < 29) return fine
            return fine && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        /** 電池の最適化の対象外（「制限なし」）。 */
        fun ignoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
        }
    }
}
