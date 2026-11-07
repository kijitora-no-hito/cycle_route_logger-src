package jp.kjtr.cycleroutelogger.record

import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.Simplify
import jp.kjtr.cycleroutelogger.gpx.GpxData
import jp.kjtr.cycleroutelogger.gpx.GpxPoint
import jp.kjtr.cycleroutelogger.gpx.GpxTrack
import jp.kjtr.cycleroutelogger.gpx.GpxWaypoint

/**
 * 記録（走った軌跡）をルートや GPX にするときの加工（純ロジック）。
 *
 * - 区間（segment）= 一時停止（手動）や跳びで切れたところ。区間ごとに trkseg / ルートの区間にする。
 * - [dropStops]: 止まっていた間の点（平滑化した速度が [STOP_MPS] 未満。自動一時停止中もこれに当たる）を除く。
 *   各区間の始点・終点は残す。
 * - [toleranceM]: Douglas–Peucker の許容誤差（m）。null なら間引かない。区間ごとに行い、始点・終点は残す。
 */
object TrackExport {
    /** これより遅ければ「止まっていた」（自動一時停止と同じ 3 km/h）。 */
    const val STOP_MPS = 3.0 / 3.6

    /** 選べる間引きの許容誤差（m）。null = 間引かない。 */
    val TOLERANCES: List<Double?> = listOf(null, 3.0, 5.0, 10.0)

    /** 経由地を「通った」とみなす距離（軌跡のどこかからこれ以内）。 */
    const val PASSED_WAYPOINT_M = 100.0

    fun segments(points: List<TrackPointEntity>, toleranceM: Double? = null, dropStops: Boolean = false): List<List<GpxPoint>> =
        points.groupBy { it.segment }.toSortedMap().values.mapNotNull { seg ->
            var s = seg
            if (dropStops && s.size > 2) {
                s = s.filterIndexed { i, p -> i == 0 || i == s.size - 1 || (p.speed ?: Float.MAX_VALUE) >= STOP_MPS }
            }
            if (toleranceM != null && s.size > 2) {
                val keep = Simplify.douglasPeuckerKeep(s.map { LatLon(it.lat, it.lon) }, toleranceM)
                s = s.filterIndexed { i, _ -> keep[i] }
            }
            s.map { GpxPoint(it.lat, it.lon, it.ele, it.time) }.takeIf { it.isNotEmpty() }
        }

    /** 記録の GPX（trk 1 本、区間 = trkseg）。[waypoints] はルートサイクリングで通った経由地など。 */
    fun gpx(t: TrackEntity, points: List<TrackPointEntity>, waypoints: List<GpxWaypoint> = emptyList()): GpxData =
        GpxData(
            name = t.name,
            desc = t.planName?.let { "Plan: $it" },
            tracks = listOf(GpxTrack(t.name, segments(points))),
            waypoints = waypoints,
            version = "1.1",
        )

    /**
     * 軌跡の近く（[PASSED_WAYPOINT_M] 以内）を通った経由地だけ、通った時刻の順に。
     * [pick] は経由地の位置。戻り値は（経由地, いちばん近づいた時刻）。
     */
    fun <W> passed(points: List<TrackPointEntity>, waypoints: List<W>, pick: (W) -> LatLon): List<Pair<W, Long>> {
        if (points.isEmpty()) return emptyList()
        // 点が多いときは間を抜いて調べる（100 m に対して 1 秒ごとの点なら十分細かい）
        val sample = Simplify.stride(points, 20_000)
        return waypoints.mapNotNull { w ->
            val p = pick(w)
            var best = Double.MAX_VALUE
            var at = 0L
            for (q in sample) {
                val d = Geo.distanceM(p.lat, p.lon, q.lat, q.lon)
                if (d < best) {
                    best = d; at = q.time
                }
            }
            if (best <= PASSED_WAYPOINT_M) w to at else null
        }.sortedBy { it.second }
    }

    /** 書き出しのファイル名（日付 + 名前。名前の先頭の日付は重ねない）。 */
    fun fileBaseName(stamp: String, nameDate: String, name: String): String {
        val rest = name.removePrefix(nameDate).trim()
        return if (rest.isEmpty()) stamp else "${stamp}_$rest"
    }

    /** 区間ごとの長さの合計（m）。 */
    fun lengthM(segments: List<List<GpxPoint>>): Double = segments.sumOf { s -> Geo.lengthM(s.map { it.latLon() }) }
}
