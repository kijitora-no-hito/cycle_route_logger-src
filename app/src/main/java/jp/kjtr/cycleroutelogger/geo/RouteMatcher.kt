package jp.kjtr.cycleroutelogger.geo

import kotlin.math.sqrt

/**
 * ルート（複数の折れ線）に対する現在地の「外れ距離」と「進捗」を求める。
 *
 * - 外れ距離: 現在地からルートの線（全区間の線分）までの最短距離。
 * - 進捗: 最寄りの線分上の足（垂線の足）までの、始点からの道のり。区間（trkseg）の間の空白は数えない。
 *
 * 最寄りの線分は毎回全点を調べて決める（10 万点でも 1 回数 ms）。同じ道を往復するルートでは
 * 往路・復路のどちらに当たるかは距離だけで決まる（近い方）。
 */
class RouteMatcher(segments: List<List<LatLon>>) {
    private val lines: List<List<LatLon>> = segments.filter { it.isNotEmpty() }

    /** 各区間の各点までの道のり [m]（区間をまたいで通しで数える）。 */
    private val cumulative: List<DoubleArray>

    /** ルートの全長 [m]。 */
    val totalM: Double

    init {
        var acc = 0.0
        cumulative = lines.map { line ->
            val arr = DoubleArray(line.size)
            for (i in line.indices) {
                if (i > 0) acc += Geo.distanceM(line[i - 1], line[i])
                arr[i] = acc
            }
            arr
        }
        totalM = acc
    }

    val isEmpty: Boolean get() = lines.isEmpty()

    data class Match(
        /** 線までの最短距離 [m]。 */
        val deviationM: Double,
        /** 始点からの道のり [m]。 */
        val progressM: Double,
        /** 残りの道のり [m]。 */
        val remainingM: Double,
        /** 線上の最寄り点。 */
        val nearest: LatLon,
    )

    fun match(p: LatLon): Match? {
        if (lines.isEmpty()) return null
        // 現在地を原点にした局所の平面座標（m）で、各線分までの距離を測る。
        val kx = Geo.metersPerDegLon(p.lat)
        val ky = Geo.METERS_PER_DEG_LAT
        var bestD2 = Double.POSITIVE_INFINITY
        var bestProgress = 0.0
        var bestPoint = lines[0][0]
        for ((li, line) in lines.withIndex()) {
            val cum = cumulative[li]
            if (line.size == 1) {
                val x = (line[0].lon - p.lon) * kx
                val y = (line[0].lat - p.lat) * ky
                val d2 = x * x + y * y
                if (d2 < bestD2) {
                    bestD2 = d2; bestProgress = cum[0]; bestPoint = line[0]
                }
                continue
            }
            var ax = (line[0].lon - p.lon) * kx
            var ay = (line[0].lat - p.lat) * ky
            for (i in 1 until line.size) {
                val bx = (line[i].lon - p.lon) * kx
                val by = (line[i].lat - p.lat) * ky
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                // 原点 (0,0) から線分 AB への垂線の足のパラメータ t（0..1 に丸める）
                val t = if (len2 <= 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val fx = ax + t * dx
                val fy = ay + t * dy
                val d2 = fx * fx + fy * fy
                if (d2 < bestD2) {
                    bestD2 = d2
                    bestProgress = cum[i - 1] + t * (cum[i] - cum[i - 1])
                    val a = line[i - 1]
                    val b = line[i]
                    bestPoint = LatLon(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
                }
                ax = bx
                ay = by
            }
        }
        return Match(
            deviationM = sqrt(bestD2),
            progressM = bestProgress,
            remainingM = (totalM - bestProgress).coerceAtLeast(0.0),
            nearest = bestPoint,
        )
    }
}
