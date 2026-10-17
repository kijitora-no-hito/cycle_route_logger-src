package jp.kjtr.cycleroutelogger.guide

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** ルート外れ・経由地への接近・到着の知らせ（音とバイブ。どちらも設定で ON/OFF）。 */
class Alerter(private val context: Context) {

    enum class Kind { OFF_ROUTE, BACK_ON_ROUTE, APPROACH, ARRIVAL }

    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()

    fun alert(kind: Kind, sound: Boolean, vibrate: Boolean) {
        if (sound) {
            val (t, ms) = when (kind) {
                Kind.OFF_ROUTE -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD to 600
                Kind.BACK_ON_ROUTE -> ToneGenerator.TONE_PROP_ACK to 200
                Kind.APPROACH -> ToneGenerator.TONE_PROP_BEEP2 to 400
                Kind.ARRIVAL -> ToneGenerator.TONE_CDMA_ALERT_INCALL_LITE to 600
            }
            runCatching { tone?.startTone(t, ms) }
        }
        if (vibrate) {
            val pattern = when (kind) {
                Kind.OFF_ROUTE -> longArrayOf(0, 400, 150, 400, 150, 400)
                Kind.BACK_ON_ROUTE -> longArrayOf(0, 120)
                Kind.APPROACH -> longArrayOf(0, 250, 120, 250)
                Kind.ARRIVAL -> longArrayOf(0, 600, 200, 600)
            }
            vibrator()?.let { v ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= 26) {
                        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(pattern, -1)
                    }
                }
            }
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
