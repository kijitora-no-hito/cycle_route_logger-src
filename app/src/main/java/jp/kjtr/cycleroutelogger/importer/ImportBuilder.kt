package jp.kjtr.cycleroutelogger.importer

import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.SmoothConfig
import jp.kjtr.cycleroutelogger.record.RideTracker
import jp.kjtr.cycleroutelogger.record.Sample
import jp.kjtr.cycleroutelogger.record.TrackerConfig

/**
 * 1 日分の取り込みの中身（点は trackId = 0。保存するときに入れる）。
 * [replacesPoints] なら [track] は前に取り込んだ記録（id あり）で、[points] はその記録の点と新しい点を合わせたもの（0.10.1）。
 * [added] はこの取り込みで増える点・距離・走行時間（確認画面の表示）。
 */
class BuiltDay(
    val track: TrackEntity,
    val points: List<TrackPointEntity>,
    val replacesPoints: Boolean = false,
    val addedDistanceM: Double = track.distanceM,
    val addedMovingMs: Long = track.movingMs,
    val addedPoints: Int = points.size,
)

/**
 * 1 日分（[DayPlan]）から記録と点を作る（純ロジック。単体テスト: ActivityImportTest）。
 *
 * - 点は記録中と同じ [RideTracker] に通し、受け付けた点だけを残す（100 km/h 超の跳び・同じ時刻は捨てる。跳びが続いて測り直したら区間を分ける）。
 *   色分けの速さ（`speed`）は記録中と同じ直近 3 回の中央値。端末の速度（`deviceSpeed`）は FIT / TCX の速度。精度は無い（null）。
 * - 区間: 片（ファイルの境目・10 分以上の空き・重複で切れたところ）ごと。さらに **[STOP_GAP_MS]（30 秒）を超えてあいて、その間に 3 km/h 未満しか
 *   動いていない**ところでも分ける（機器が止まっている間の記録を止めていた＝自動一時停止。そのまま流すと、動き出した最初の点の速さで
 *   止まっていた時間を走行時間に数えてしまう）。あいていても動いていれば（トンネル・間引いた記録）同じ区間のまま数える。
 * - 統計（距離・走行時間・最高速度・獲得標高）は記録を終えたときと同じ [TrackRepository.statsOf]（設定が ON ならなめらかにした点で）。
 * - provider は [TrackPointEntity.PROVIDER_IMPORT]。記録は「フリー」、`importFormat` に元の形式（"fit,gpx"）。
 * - 0.10.1〜 前に取り込んだその日の記録があれば [appendTo] で区間として足す（既存の記録との重複は [DayPlanner] が活動単位で見る）。
 */
object ImportBuilder {
    /** 止まっていたかを見る空きの長さ [ms]。 */
    const val STOP_GAP_MS = 30_000L

    fun points(day: DayPlan, store: PointStore, autoPause: Boolean): List<TrackPointEntity> {
        val config = TrackerConfig(autoPause = autoPause)
        val tracker = RideTracker(config)
        val out = ArrayList<TrackPointEntity>(day.points)
        var seg = -1
        var segHasPoints = false
        fun newSegment() {
            if (segHasPoints || seg < 0) {
                seg++
                segHasPoints = false
            }
        }
        for (piece in day.pieces) {
            tracker.breakSegment()
            newSegment()
            val b = store.read(piece.first, piece.count)
            var prev = -1
            for (i in 0 until b.size) {
                if (prev >= 0) {
                    val gap = b.time[i] - b.time[prev]
                    if (gap > STOP_GAP_MS) {
                        val v = Geo.distanceM(b.lat[prev], b.lon[prev], b.lat[i], b.lon[i]) / (gap / 1000.0)
                        if (v < config.stillSpeedMps) {
                            tracker.breakSegment()
                            newSegment()
                        }
                    }
                }
                val speed = b.speedAt(i)
                val s = Sample(b.time[i], b.lat[i], b.lon[i], null, b.eleAt(i), speed, null, TrackPointEntity.PROVIDER_IMPORT)
                val r = tracker.feed(s)
                if (r !is RideTracker.Result.Accepted) continue
                if (r.newSegment && segHasPoints) newSegment()
                segHasPoints = true
                prev = i
                out.add(
                    TrackPointEntity(
                        trackId = 0, segment = seg, time = s.timeMs, lat = s.lat, lon = s.lon, ele = s.altitudeM, accuracy = null,
                        deviceSpeed = speed, speed = r.smoothedSpeedMps.toFloat(), provider = TrackPointEntity.PROVIDER_IMPORT,
                    ),
                )
            }
        }
        return out
    }

    /** 1 日分の記録（[name] は「2026-05-03 取り込み」など）。点が 2 つ未満なら null。 */
    fun build(day: DayPlan, store: PointStore, autoPause: Boolean, smooth: SmoothConfig?, name: String): BuiltDay? {
        val pts = points(day, store, autoPause)
        if (pts.size < 2) return null
        val st = TrackRepository.statsOf(pts, autoPause, smooth)
        val t = TrackEntity(
            name = name,
            startedAt = pts.first().time,
            endedAt = pts.last().time,
            status = TrackEntity.STATUS_FINISHED,
            mode = TrackEntity.MODE_FREE,
            autoPause = autoPause,
            distanceM = st.distanceM,
            movingMs = st.movingMs,
            maxSpeedMps = st.maxSpeedMps,
            ascentM = st.ascentM,
            pointCount = pts.size,
            importFormat = ActivityFormat.encode(day.formats),
        )
        return BuiltDay(t, pts)
    }

    /**
     * 前に取り込んだ記録 [target]（点 [existing]）に、その日の新しい走行 [day] を区間として足す（0.10.1）。
     * 点は時刻の順に並べ直し、区間の番号を振り直す（時間は重ならない。重なる走行は [DayPlanner] が別の記録にしている）。統計は合わせた点から計算し直す。
     */
    fun appendTo(
        target: TrackEntity,
        existing: List<TrackPointEntity>,
        day: DayPlan,
        store: PointStore,
        autoPause: Boolean,
        smooth: SmoothConfig?,
    ): BuiltDay? {
        val added = points(day, store, autoPause)
        if (added.size < 2) return null
        val addedStats = TrackRepository.statsOf(added, autoPause, smooth)
        // 区間ごとにまとめ、始まりの時刻の順に並べて番号を振り直す
        val segs = TrackRepository.segmentsOf(existing) + TrackRepository.segmentsOf(added)
        val merged = ArrayList<TrackPointEntity>(existing.size + added.size)
        segs.filter { it.isNotEmpty() }.sortedBy { it.first().time }.forEachIndexed { i, seg ->
            for (p in seg) merged.add(p.copy(id = 0, trackId = 0, segment = i))
        }
        val st = TrackRepository.statsOf(merged, target.autoPause, smooth)
        val formats = ActivityFormat.decode(target.importFormat) + day.formats
        val t = target.copy(
            startedAt = minOf(target.startedAt, merged.first().time),
            endedAt = maxOf(target.endedAt ?: 0L, merged.last().time),
            distanceM = st.distanceM,
            movingMs = st.movingMs,
            maxSpeedMps = st.maxSpeedMps,
            ascentM = st.ascentM,
            pointCount = merged.size,
            importFormat = ActivityFormat.encode(formats),
        )
        return BuiltDay(t, merged, replacesPoints = true, addedDistanceM = addedStats.distanceM, addedMovingMs = addedStats.movingMs, addedPoints = added.size)
    }
}
