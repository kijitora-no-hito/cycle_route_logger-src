package jp.kjtr.cycleroutelogger.data

import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.stats.MapTiles
import jp.kjtr.cycleroutelogger.geo.Simplify
import jp.kjtr.cycleroutelogger.stats.GridCells
import jp.kjtr.cycleroutelogger.stats.RideFacts
import jp.kjtr.cycleroutelogger.stats.RideSummaries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * まとめの地図に描く記録 1 本の線（ズームに応じて [coarse] / [fine] を使い分ける）。
 * 0.10.2〜 線は緯度・経度 × 1e6 の整数の並び（[MapTiles.encode]）。記録が 1000 本を超えてもメモリが膨らまないように（LatLon のリストの数分の 1）。
 */
class RideLines(val trackId: Long, val coarse: List<IntArray>, val fine: List<IntArray>) {
    val bounds: GeoBounds? = MapTiles.boundsOf(coarse)
    val coarsePoints: Int get() = coarse.sumOf { it.size / 2 }
    val finePoints: Int get() = fine.sumOf { it.size / 2 }
}

/** 記録 1 本分のまとめの材料。 */
data class RideDigest(val facts: RideFacts, val lines: RideLines)

/**
 * 全記録のまとめの材料（DESIGN.md §4.7）。記録ごとに点を読み、統計の材料と表示用に間引いた線を作ってメモリに覚える
 * （点の数・名前が変わらなければ作り直さない）。重い計算はバックグラウンドで。Room は変えない（読むだけ）。
 */
class SummaryRepository(private val db: AppDatabase) {
    /** 記録の id → (作ったときの鍵, 材料)。鍵は点の数・名前・統計（計算し直すと変わる）・なめらかにするか。 */
    private val cache = HashMap<Long, Pair<List<Any>, RideDigest>>()

    private fun keyOf(t: TrackEntity): List<Any> = listOf(t.pointCount, t.name, t.distanceM, t.ascentM, TrackRepository.smoothingEnabled)

    /** 計算は数本ずつ並べて（0.10.2。1000 本を超える記録でも待ち時間を短く。1 本分の点しか持たないのでメモリは並べた数だけ）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(PARALLEL)

    /** [keep] = キャッシュに残す記録（0.11.0〜。絞り込んだ記録だけのまとめでも、ほかの記録の材料を捨てない。既定は [tracks]）。 */
    suspend fun digests(tracks: List<TrackEntity>, keep: Set<Long>? = null): List<RideDigest> = withContext(Dispatchers.Default) {
        val misses = tracks.filter { t -> synchronized(cache) { cache[t.id] }?.first != keyOf(t) }
        coroutineScope {
            misses.map { t ->
                async(worker) {
                    val key = keyOf(t)
                    val d = build(t)
                    synchronized(cache) { cache[t.id] = key to d }
                }
            }.awaitAll()
        }
        val out = ArrayList<RideDigest>(tracks.size)
        synchronized(cache) {
            cache.keys.retainAll(keep ?: tracks.map { it.id }.toSet())
            for (t in tracks) cache[t.id]?.let { out.add(it.second) }
        }
        out
    }

    private suspend fun build(t: TrackEntity): RideDigest {
        val pts = withContext(Dispatchers.IO) { db.tracks().points(t.id) }
        // 0.9.0〜 線・マスはなめらかにした点（設定が ON のとき。記録の詳細と同じ）
        val smooth = TrackRepository.smoothConfig()
        val segs = TrackRepository.segmentsOf(TrackRepository.smoothed(pts, smooth))
        // 記録の詳細と同じく、保存した点を RideTracker に流し直す（跳びの除去 100 km/h・自動一時停止の扱いが記録と同じ）
        val steps = if (pts.isNotEmpty()) TrackRepository.analyze(pts, t.autoPause, smooth).steps else emptyList()
        val (binsMs, binsM) = RideSummaries.speedBins(steps)
        val cells = cellsOfSegments(segs)
        val lineSegs = segs.map { s -> s.map { LatLon(it.lat, it.lon) } }.filter { it.size >= 2 }
        val facts = RideFacts(
            id = t.id, name = t.name, startedAt = t.startedAt, endedAt = t.endedAt ?: pts.lastOrNull()?.time ?: t.startedAt,
            distanceM = t.distanceM, movingMs = t.movingMs, maxSpeedMps = t.maxSpeedMps, ascentM = t.ascentM,
            route = t.mode == TrackEntity.MODE_ROUTE,
            speedBinsMs = binsMs, speedBinsM = binsM, cells = cells, movingSpans = RideSummaries.movingSpans(steps),
        )
        // 自転車は車より遅く細かい道を走るので、drive_logger より少し細かく間引く
        val lines = RideLines(
            t.id,
            coarse = lineSegs.map { MapTiles.encode(Simplify.forDisplay(it, maxPoints = 300, toleranceM = 40.0)) },
            fine = lineSegs.map { MapTiles.encode(Simplify.forDisplay(it, maxPoints = 2000, toleranceM = 5.0)) },
        )
        return RideDigest(facts, lines)
    }

    /**
     * 記録 1 本の開拓したマス（まとめと同じ数え方。オンラインランキングの「開拓したマス」に使う。0.12.0）。
     * 線・マスはなめらかにした点（設定が ON のとき）を一時停止・跳びの区間ごとに。
     */
    suspend fun cellsOf(t: TrackEntity): Set<Long> {
        val pts = withContext(Dispatchers.IO) { db.tracks().points(t.id) }
        return cellsOfSegments(TrackRepository.segmentsOf(TrackRepository.smoothed(pts, TrackRepository.smoothConfig())))
    }

    companion object {
        const val PARALLEL = 3

        /** マスの数え方を変えたら上げる（ランキングのマスの覚え [cellKey] を作り直させる）。 */
        private const val CELLS_VERSION = 1

        private fun cellsOfSegments(segs: List<List<TrackPointEntity>>): HashSet<Long> {
            val cells = HashSet<Long>()
            for (s in segs) GridCells.cellsOf(s.map { it.lat to it.lon }, cells)
            return cells
        }

        /** [cellsOf] の結果が変わりうるものの鍵（点の数・距離（計算し直すと変わる）・なめらかにするか・数え方の版）。 */
        fun cellKey(t: TrackEntity): String = "$CELLS_VERSION|${t.pointCount}|${t.distanceM}|${TrackRepository.smoothingEnabled}"
    }
}
