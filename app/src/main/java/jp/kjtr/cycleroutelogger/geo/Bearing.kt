package jp.kjtr.cycleroutelogger.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 方位角と方角の名前、画面の端に出す矢印の位置（純ロジック。単体テスト: BearingTest）。 */
object Bearing {
    private const val DEG = PI / 180.0

    /** a から b への初期方位角（度、北 = 0、時計回り、[0, 360)）。 */
    fun initialDeg(a: LatLon, b: LatLon): Double {
        val phi1 = a.lat * DEG
        val phi2 = b.lat * DEG
        val dLon = (b.lon - a.lon) * DEG
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return normalize(atan2(y, x) / DEG)
    }

    fun normalize(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** -180..180 に寄せた差（a − b）。 */
    fun diff(a: Double, b: Double): Double {
        var d = normalize(a - b)
        if (d > 180.0) d -= 360.0
        return d
    }

    /** 8 方位の名前のインデックス（0 = 北, 1 = 北東, … 7 = 北西）。 */
    fun octant(deg: Double): Int = (normalize(deg) / 45.0).roundToInt() % 8

    /** 画面に描く矢印の回転（度、画面の上 = 0、時計回り）。地図が [mapBearingDeg] だけ回っているとき。 */
    fun screenRotation(targetBearingDeg: Double, mapBearingDeg: Double): Double =
        normalize(targetBearingDeg - mapBearingDeg)

    /** 画面端の矢印。[x],[y] は画面座標（px）、[rotationDeg] は画面の上 = 0 の時計回り。 */
    data class EdgeMarker(val x: Float, val y: Float, val rotationDeg: Float)

    /**
     * 目的地が画面外のとき、画面中央から目的地へ向かう線が「内側に [margin] 下げた枠」と交わる点。
     * 目的地が枠の内側（画面内）なら null。[dx],[dy] は画面中央から目的地への画面座標のずれ（px、y は下向き）。
     */
    fun edgeMarker(width: Float, height: Float, margin: Float, dx: Float, dy: Float): EdgeMarker? {
        val halfW = width / 2f - margin
        val halfH = height / 2f - margin
        if (halfW <= 0f || halfH <= 0f) return null
        if (abs(dx) <= halfW && abs(dy) <= halfH) return null
        if (dx == 0f && dy == 0f) return null
        // 枠に当たるまでの倍率（小さい方）
        val sx = if (dx != 0f) halfW / abs(dx) else Float.POSITIVE_INFINITY
        val sy = if (dy != 0f) halfH / abs(dy) else Float.POSITIVE_INFINITY
        val s = minOf(sx, sy)
        val rot = normalize(atan2(dx.toDouble(), -dy.toDouble()) / DEG).toFloat()
        return EdgeMarker(width / 2f + dx * s, height / 2f + dy * s, rot)
    }

    /** 画面座標の向き（[rotationDeg] は画面の上 = 0）を、中央からの単位ベクトル（y 下向き）に。 */
    fun screenVector(rotationDeg: Double): Pair<Float, Float> =
        sin(rotationDeg * DEG).toFloat() to (-cos(rotationDeg * DEG)).toFloat()
}
