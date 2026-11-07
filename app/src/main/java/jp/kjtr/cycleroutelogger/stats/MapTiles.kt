package jp.kjtr.cycleroutelogger.stats

import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.LatLon
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * まとめの「全走行の地図」をタイル（TileOverlay）で描くための純ロジック（0.10.2。DESIGN.md §4.7.1。単体テスト: MapTilesTest）。
 *
 * 0.10.1 までは記録ごと・区間ごとに Compose の Polyline を足していたので、記録が 1000 本を超えると（Strava の書き出しを取り込んだ後など）
 * Maps の内部オブジェクトでヒープ（256 MB）が尽きて落ちた。タイルなら本数に関係なく、見えている範囲の数枚を描くだけで済む。
 *
 * - 線は緯度・経度 × 1e6 の整数を交互に並べた [IntArray]（[encode]。LatLon のリストの数分の 1 のメモリ）。
 * - 座標は Web メルカトルの「世界座標」（0〜1。[worldX] / [worldY]）。ズーム z のタイルは 2^z × 2^z 枚で、1 枚 = 256 単位（dp）。
 * - タップした所に近い記録は [nearest]（画面上の距離 = 世界座標 × 256 × 2^z）。
 */
object MapTiles {
    const val TILE_UNITS = 256.0
    private const val MAX_LAT = 85.05112878

    fun worldX(lon: Double): Double = (lon + 180.0) / 360.0

    fun worldY(lat: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    /** 線を緯度・経度 × 1e6 の整数の並びに。 */
    fun encode(points: List<LatLon>): IntArray {
        val a = IntArray(points.size * 2)
        points.forEachIndexed { i, p ->
            a[2 * i] = (p.lat * 1e6).roundToInt()
            a[2 * i + 1] = (p.lon * 1e6).roundToInt()
        }
        return a
    }

    fun decode(a: IntArray): List<LatLon> = List(a.size / 2) { LatLon(a[2 * it] / 1e6, a[2 * it + 1] / 1e6) }

    /** 線の集まりの範囲（無ければ null）。 */
    fun boundsOf(lines: List<IntArray>): GeoBounds? {
        var s = Int.MAX_VALUE
        var w = Int.MAX_VALUE
        var n = Int.MIN_VALUE
        var e = Int.MIN_VALUE
        for (l in lines) {
            var i = 0
            while (i + 1 < l.size) {
                val la = l[i]
                val lo = l[i + 1]
                if (la < s) s = la
                if (la > n) n = la
                if (lo < w) w = lo
                if (lo > e) e = lo
                i += 2
            }
        }
        return if (s == Int.MAX_VALUE) null else GeoBounds(s / 1e6, w / 1e6, n / 1e6, e / 1e6)
    }

    fun union(list: List<GeoBounds?>): GeoBounds? {
        var r: GeoBounds? = null
        for (b in list) {
            if (b == null) continue
            r = r?.let { GeoBounds(minOf(it.south, b.south), minOf(it.west, b.west), maxOf(it.north, b.north), maxOf(it.east, b.east)) } ?: b
        }
        return r
    }

    /** タイル (x, y, zoom) の世界座標の範囲 [x0, y0, x1, y1]。 */
    fun tileWorld(x: Int, y: Int, zoom: Int): DoubleArray {
        val n = 2.0.pow(zoom)
        return doubleArrayOf(x / n, y / n, (x + 1) / n, (y + 1) / n)
    }

    /** 緯度経度の範囲 [b] が、世界座標の範囲 [w]（[tileWorld]）を [marginWorld] だけ広げたものにかかるか。 */
    fun intersects(b: GeoBounds, w: DoubleArray, marginWorld: Double): Boolean {
        val x0 = worldX(b.west)
        val x1 = worldX(b.east)
        val y0 = worldY(b.north)
        val y1 = worldY(b.south)
        return x1 >= w[0] - marginWorld && x0 <= w[2] + marginWorld && y1 >= w[1] - marginWorld && y0 <= w[3] + marginWorld
    }

    /** 1 本の記録の線（タップの判定用）。 */
    class HitLine(val id: Long, val bounds: GeoBounds?, val lines: List<IntArray>)

    /**
     * ([lat], [lon]) をタップしたとき、画面上で [tolUnits]（dp）以内にある記録のうちいちばん近いもの（無ければ null）。
     * 距離はズーム [zoom] での画面上の dp（世界座標 × 256 × 2^zoom）。
     */
    fun nearest(rides: List<HitLine>, lat: Double, lon: Double, zoom: Float, tolUnits: Double): Long? {
        val scale = TILE_UNITS * 2.0.pow(zoom.toDouble())
        val px = worldX(lon) * scale
        val py = worldY(lat) * scale
        val tolWorld = tolUnits / scale
        val wx = worldX(lon)
        val wy = worldY(lat)
        var best: Long? = null
        var bestD = tolUnits
        for (r in rides) {
            val b = r.bounds ?: continue
            if (!intersects(b, doubleArrayOf(wx, wy, wx, wy), tolWorld)) continue
            for (l in r.lines) {
                if (l.size < 4) continue
                var ax = worldX(l[1] / 1e6) * scale
                var ay = worldY(l[0] / 1e6) * scale
                var i = 2
                while (i + 1 < l.size) {
                    val bx = worldX(l[i + 1] / 1e6) * scale
                    val by = worldY(l[i] / 1e6) * scale
                    val d = segDist(px, py, ax, ay, bx, by)
                    if (d <= bestD) {
                        bestD = d
                        best = r.id
                    }
                    ax = bx
                    ay = by
                    i += 2
                }
            }
        }
        return best
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }
}
