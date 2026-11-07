package jp.kjtr.cycleroutelogger.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 端末の向き（0 = 北, 時計回り）。ROTATION_VECTOR から求める（gps_camera の OrientationProvider をもとにした）。
 *
 * ハンドルのホルダーに付けた端末は、寝かせても立てても使われるので、
 * 「画面の上方向」と「背面カメラの向き」を足したベクトルの水平成分の方位を使う
 * （寝かせれば画面の上、立てれば背面の向きが効く）。
 */
class HeadingProvider(private val context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private var listener: ((Float?) -> Unit)? = null
    private var smoothed = Float.NaN

    fun start(onChange: (Float?) -> Unit) {
        listener = onChange
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        listener = null
    }

    private fun displayRotation(): Int =
        try {
            ContextCompat.getDisplayOrDefault(context).rotation
        } catch (_: Exception) {
            Surface.ROTATION_0
        }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // R は端末座標 → 世界座標（東, 北, 上）。列 0/1/2 が端末の X/Y/Z 軸。
        // 画面の上方向（回転に応じて端末の ±X / ±Y）
        val (ux, uy) = when (displayRotation()) {
            Surface.ROTATION_90 -> r[0] to r[3]
            Surface.ROTATION_180 -> -r[1] to -r[4]
            Surface.ROTATION_270 -> -r[0] to -r[3]
            else -> r[1] to r[4]
        }
        // 背面カメラの向き = 端末の −Z
        val bx = -r[2]
        val by = -r[5]
        val e = ux + bx
        val n = uy + by
        if (hypot(e, n) < 0.2f) return
        val az = ((Math.toDegrees(atan2(e.toDouble(), n.toDouble())) + 360.0) % 360.0).toFloat()
        smoothed = lowPass(smoothed, az)
        listener?.invoke(smoothed)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun lowPass(prev: Float, next: Float, alpha: Float = 0.15f): Float {
        if (prev.isNaN()) return next
        var d = next - prev
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return ((prev + alpha * d) + 360f) % 360f
    }
}

/** 端末の向き（度）。センサーが無ければ null。画面が見えている間だけ動かす。 */
@Composable
fun rememberDeviceHeading(): State<Float?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Float?>(null) }
    val provider = remember { HeadingProvider(context) }
    LifecycleStartEffect(provider) {
        provider.start { state.value = it }
        onStopOrDispose { provider.stop() }
    }
    return state
}
