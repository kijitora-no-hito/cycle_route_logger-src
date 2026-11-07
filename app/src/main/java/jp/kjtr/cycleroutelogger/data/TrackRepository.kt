package jp.kjtr.cycleroutelogger.data

import androidx.room.withTransaction
import jp.kjtr.cycleroutelogger.geo.SmoothConfig
import jp.kjtr.cycleroutelogger.geo.TrackSmoother
import jp.kjtr.cycleroutelogger.gpx.GpxData
import jp.kjtr.cycleroutelogger.gpx.GpxPoint
import jp.kjtr.cycleroutelogger.gpx.GpxTrack
import jp.kjtr.cycleroutelogger.importer.BuiltDay
import jp.kjtr.cycleroutelogger.importer.PointBlock
import jp.kjtr.cycleroutelogger.record.RideTracker
import jp.kjtr.cycleroutelogger.record.Sample
import jp.kjtr.cycleroutelogger.record.TrackerConfig
import jp.kjtr.cycleroutelogger.stats.ElevationGain
import jp.kjtr.cycleroutelogger.stats.RideAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** GPX に書き出す位置（0.9.0〜）。 */
enum class GpxSource {
    /** なめらかにした位置（地図の線と同じ。[TrackRepository.smoothed]）。 */
    SMOOTHED,
    /** 記録した位置（測位そのまま）。 */
    RECORDED,
}

/** 点から計算し直した統計（[TrackRepository.statsOf]）。 */
data class TrackStats(val distanceM: Double, val movingMs: Long, val maxSpeedMps: Double, val ascentM: Double)

class TrackRepository(private val db: AppDatabase) {
    private val dao = db.tracks()

    fun finished(): Flow<List<TrackEntity>> = dao.finished()
    fun track(id: Long): Flow<TrackEntity?> = dao.track(id)
    /** 記録中の出来事（位置情報 OFF・位置が届かない・アプリが落ちた）。 */
    fun events(id: Long): Flow<List<TrackEventEntity>> = dao.events(id)
    suspend fun trackNow(id: Long) = dao.trackNow(id)
    /** 記録した点（測位そのまま。比較表示・バックアップ・GPX の「記録した位置」）。 */
    suspend fun points(id: Long): List<TrackPointEntity> = withContext(Dispatchers.IO) { dao.points(id) }
    /** 表示用の点（設定「軌跡をなめらかにする」が ON ならなめらかにした点。OFF なら記録した点）。 */
    suspend fun displayPoints(id: Long): List<TrackPointEntity> {
        val pts = points(id)
        return withContext(Dispatchers.Default) { smoothed(pts, smoothConfig()) }
    }
    suspend fun unfinished(): TrackEntity? = dao.unfinished()
    suspend fun rename(id: Long, name: String) = dao.rename(id, name)
    suspend fun delete(id: Long) = dao.deleteTrack(id)
    suspend fun allFinished() = dao.allFinished()
    /** すべての記録（終わっていないものも。取り込みの重複の判定）。 */
    suspend fun allTracks() = withContext(Dispatchers.IO) { dao.all() }

    /**
     * ファイルから取り込んだ 1 日分を保存する（記録と点と取り込みの印 [keys] を 1 つのトランザクションで。0.10.0、印は 0.10.1）。
     * [BuiltDay.replacesPoints] なら前に取り込んだ記録（[BuiltDay.track] の id）の点を入れ替える（その日の新しい走行を区間として足したもの）。
     */
    suspend fun insertImported(day: BuiltDay, keys: List<String> = emptyList()): Long = withContext(Dispatchers.IO) {
        db.withTransaction {
            val id = if (day.replacesPoints) {
                dao.updateTrack(day.track)
                dao.deletePoints(day.track.id)
                day.track.id
            } else {
                dao.insertTrack(day.track)
            }
            day.points.chunked(5_000).forEach { chunk -> dao.insertPoints(chunk.map { it.copy(id = 0, trackId = id) }) }
            if (keys.isNotEmpty()) {
                val now = System.currentTimeMillis()
                dao.insertImportSources(keys.map { ImportSourceEntity(it, id, now) })
            }
            id
        }
    }

    /** 記録の点を比べる用の詰めた形で（取り込みの重複の判定。0.10.1）。 */
    suspend fun pointBlock(id: Long): PointBlock = withContext(Dispatchers.IO) {
        val pts = dao.points(id).sortedBy { it.time }
        PointBlock(
            LongArray(pts.size) { pts[it].time }, DoubleArray(pts.size) { pts[it].lat }, DoubleArray(pts.size) { pts[it].lon },
            FloatArray(pts.size) { pts[it].ele?.toFloat() ?: Float.NaN }, FloatArray(pts.size) { pts[it].deviceSpeed ?: Float.NaN },
        )
    }

    /** 取り込みの印すべて（同じものの再取り込みを外す。0.10.1）。 */
    suspend fun importedKeys(): Set<String> = withContext(Dispatchers.IO) { dao.importSourceKeys().toHashSet() }

    /**
     * 途中で終わった記録を「終了して保存」する。保存済みの点から統計を計算し直す。
     * 点が 1 つも無ければ記録ごと消して null。
     */
    suspend fun finalizeUnfinished(id: Long): TrackEntity? = withContext(Dispatchers.IO) {
        val t = dao.trackNow(id) ?: return@withContext null
        val pts = dao.points(id)
        if (pts.isEmpty()) {
            dao.deleteTrack(id)
            return@withContext null
        }
        val st = statsOf(pts, t.autoPause, smoothConfig())
        val done = t.copy(
            status = TrackEntity.STATUS_FINISHED,
            endedAt = pts.last().time,
            distanceM = st.distanceM,
            movingMs = st.movingMs,
            maxSpeedMps = st.maxSpeedMps,
            ascentM = st.ascentM,
            pointCount = pts.size,
        )
        dao.updateTrack(done)
        done
    }

    /**
     * 終えた記録の統計（距離・走行時間・最高速度・獲得標高）を点から計算し直す（0.9.0〜。記録を終えたとき・設定を切り替えたとき・
     * 0.8.0 から上書きした最初の起動）。距離はなめらかにした点（設定が ON のとき）、獲得標高は [ElevationGain]。
     */
    suspend fun recompute(id: Long) = withContext(Dispatchers.IO) {
        val t = dao.trackNow(id) ?: return@withContext
        if (t.status != TrackEntity.STATUS_FINISHED) return@withContext
        val pts = dao.points(id)
        if (pts.isEmpty()) return@withContext
        val st = withContext(Dispatchers.Default) { statsOf(pts, t.autoPause, smoothConfig()) }
        dao.updateTrack(t.copy(distanceM = st.distanceM, movingMs = st.movingMs, maxSpeedMps = st.maxSpeedMps, ascentM = st.ascentM, pointCount = pts.size))
    }

    /** 終えた記録すべての統計を計算し直す。 */
    suspend fun recomputeAllFinished() = withContext(Dispatchers.IO) {
        for (t in dao.allFinished()) recompute(t.id)
    }

    /** 記録の GPX（[source] = なめらかにした位置 / 記録した位置。バックアップは記録した位置）。 */
    suspend fun toGpx(id: Long, source: GpxSource = GpxSource.RECORDED): GpxData? = withContext(Dispatchers.IO) {
        val t = dao.trackNow(id) ?: return@withContext null
        trackToGpx(t, pointsFor(dao.points(id), source))
    }

    companion object {
        fun sampleOf(p: TrackPointEntity) = Sample(p.time, p.lat, p.lon, p.accuracy, p.ele, p.deviceSpeed, p.altAccuracy, p.provider)

        /** 保存した点を流し直して統計を作る（区間が変わるところで切る）。[smooth] があれば、なめらかにした点で流す。 */
        fun replay(points: List<TrackPointEntity>, autoPause: Boolean, smooth: SmoothConfig? = null): RideTracker {
            val tr = RideTracker(TrackerConfig(autoPause = autoPause))
            var seg = Int.MIN_VALUE
            for (p in smoothed(points, smooth)) {
                if (p.segment != seg) {
                    if (seg != Int.MIN_VALUE) tr.breakSegment()
                    seg = p.segment
                }
                tr.feed(sampleOf(p))
            }
            return tr
        }

        /** 記録の統計（距離・走行時間・最高速度は [replay]、獲得標高は記録した点の [ElevationGain]）。 */
        fun statsOf(points: List<TrackPointEntity>, autoPause: Boolean, smooth: SmoothConfig?): TrackStats {
            val tr = replay(points, autoPause, smooth)
            return TrackStats(tr.distanceM, tr.movingMs, tr.maxSpeedMps, ElevationGain.of(points))
        }

        /**
         * 記録の詳細の区間の表・速度の分布。[replay] と同じ順・同じ切れ目で流す。獲得標高は記録した点の [ElevationGain]
         * （区間の登りの合計 = [statsOf] の獲得標高）。
         */
        fun analyze(points: List<TrackPointEntity>, autoPause: Boolean, smooth: SmoothConfig? = null): RideAnalysis {
            val runs = segmentsOf(smoothed(points, smooth)).map { s -> s.map { sampleOf(it) } }
            return RideAnalysis.from(runs, autoPause, ElevationGain.gainsOf(points))
        }

        fun segmentsOf(points: List<TrackPointEntity>): List<List<TrackPointEntity>> =
            points.groupBy { it.segment }.toSortedMap().values.toList()

        /**
         * 軌跡をなめらかにする（0.9.0〜 設定「軌跡をなめらかにする」、既定 ON。[jp.kjtr.cycleroutelogger.CycleApp] が設定から入れる。
         * 単体テストでは false＝記録のまま）。表示（詳細・まとめ・SNS 用の画像・記録 → ルート）と統計の距離に使う。記録（DB の点）は変えない。
         */
        @Volatile var smoothingEnabled: Boolean = false

        /** いまの設定のなめらかさ（OFF なら null。[force] = 設定によらず）。 */
        fun smoothConfig(force: Boolean = false): SmoothConfig? = if (smoothingEnabled || force) SmoothConfig.BICYCLE else null

        /**
         * なめらかにした点（位置だけ置き換える。数・順・時刻・高度・速度はそのまま）。区間ごとに別々に流す。[smooth] が null なら記録のまま。
         */
        fun smoothed(points: List<TrackPointEntity>, smooth: SmoothConfig?): List<TrackPointEntity> {
            if (smooth == null || points.size < 3) return points
            val out = points.toMutableList()
            val groups = points.indices.groupBy { points[it].segment }
            for ((_, idx) in groups) {
                if (idx.size < 3) continue
                val sorted = idx.sortedBy { points[it].time }
                val r = TrackSmoother.smooth(sorted.map { val p = points[it]; TrackSmoother.In(p.time, p.lat, p.lon, p.accuracy) }, smooth)
                sorted.forEachIndexed { k, i -> out[i] = points[i].copy(lat = r.lat[k], lon = r.lon[k]) }
            }
            return out
        }

        /** GPX に書き出す点。 */
        fun pointsFor(points: List<TrackPointEntity>, source: GpxSource): List<TrackPointEntity> = when (source) {
            GpxSource.SMOOTHED -> smoothed(points, smoothConfig(force = true))
            GpxSource.RECORDED -> points
        }

        fun trackToGpx(t: TrackEntity, points: List<TrackPointEntity>): GpxData =
            GpxData(
                name = t.name,
                desc = null,
                tracks = listOf(
                    GpxTrack(
                        t.name,
                        segmentsOf(points).map { s -> s.map { GpxPoint(it.lat, it.lon, it.ele, it.time) } },
                    ),
                ),
                waypoints = emptyList(),
                version = "1.1",
            )
    }
}
