package jp.kjtr.cycleroutelogger.data

import android.util.Log
import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.stats.TrackGeo
import jp.kjtr.cycleroutelogger.stats.TrackGeoCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 場所の索引の様子。[entries] = 記録 id → 索引（終えた記録のうち作り終えたもの）。 */
data class GeoIndexState(
    val loaded: Boolean = false,
    /** 作り直しが要る記録のうち、作り終えた数 / 全体（作っていなければ 0 / 0）。 */
    val built: Int = 0,
    val toBuild: Int = 0,
    val entries: Map<Long, TrackGeo> = emptyMap(),
) {
    val building: Boolean get() = built < toBuild
}

/**
 * 記録の場所の索引（0.11.0。DESIGN.md §4.11.2。Room 版 7 の track_geo）。終えた記録ごとに外接矩形・始点・終点・10 m で間引いた線を前もって作って保存し、
 * メモリにも持つ（線は 1 本あたり数百点。全点は持たない）。起動したとき（0.10.2 からの上書きの最初の起動を含む）と記録が増えた・変わったときに、
 * 無い・古い記録の分だけバックグラウンドで作る。作る中身は記録の点（DB）から決まるので、作り直しても同じになる。
 */
class TrackGeoIndex(private val db: AppDatabase, private val scope: CoroutineScope) {
    private val dao = db.tracks()
    private val _state = MutableStateFlow(GeoIndexState())
    val state: StateFlow<GeoIndexState> = _state
    private val mutex = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(PARALLEL)

    /** 終えた記録の一覧を見続けて、足りない索引を作る（アプリの起動時に 1 回呼ぶ）。 */
    fun start() {
        scope.launch(Dispatchers.IO) {
            dao.finished()
                // 記録中の統計の保存でも track 表は変わるので、id と点の数が変わったときだけ
                .map { list -> list.map { it.id to it.pointCount } }
                .distinctUntilChanged()
                .conflate()
                .collect { list ->
                    runCatching { sync(list) }.onFailure { Log.w(TAG, "geo index failed: ${it.message}") }
                }
        }
    }

    /** [tracks]（id と点の数）のうち、索引が無い・古いものを作る。消えた記録の分はメモリから外す。 */
    suspend fun sync(tracks: List<Pair<Long, Int>>) = mutex.withLock {
        val t0 = System.nanoTime()
        val entries = HashMap(_state.value.entries)
        if (!_state.value.loaded) {
            for (row in dao.geoAll()) if (row.version == TrackGeo.VERSION) entries[row.trackId] = toGeo(row)
        }
        val ids = tracks.mapTo(HashSet()) { it.first }
        entries.keys.retainAll(ids)
        val stale = staleOf(tracks, entries.mapValues { it.value.sourcePoints })
        _state.value = GeoIndexState(loaded = true, built = 0, toBuild = stale.size, entries = HashMap(entries))
        if (stale.isEmpty()) return@withLock
        val t1 = System.nanoTime()
        var done = 0
        var lastPublish = 0L
        coroutineScope {
            stale.chunked(CHUNK).forEach { chunk ->
                val built = chunk.map { (id, count) -> async(worker) { build(id, count) } }.awaitAll()
                synchronized(entries) {
                    for (g in built) if (g != null) entries[g.trackId] = g
                }
                done += chunk.size
                val now = System.nanoTime()
                // 画面の絞り込みを何度もやり直させないよう、0.5 秒ごとと最後にだけ知らせる
                if (now - lastPublish > 500_000_000L || done == stale.size) {
                    lastPublish = now
                    _state.value = GeoIndexState(loaded = true, built = done, toBuild = stale.size, entries = HashMap(entries))
                }
            }
        }
        Log.i(
            TAG,
            "geo index: built ${stale.size} of ${tracks.size} in ${(System.nanoTime() - t1) / 1_000_000} ms (load ${(t1 - t0) / 1_000_000} ms, " +
                "line points ${entries.values.sumOf { it.linePoints }})",
        )
    }

    private suspend fun build(id: Long, count: Int): TrackGeo? = withContext(Dispatchers.IO) {
        val pts = dao.pointsLL(id)
        val geo = withContext(Dispatchers.Default) { buildFromPoints(id, count, pts) }
        // 作っている間に記録が消された（外部キーで入らない）なら捨てる
        runCatching { dao.upsertGeo(toEntity(geo)) }.getOrElse { return@withContext null }
        geo
    }

    companion object {
        private const val TAG = "CRL-Geo"
        const val PARALLEL = 3
        private const val CHUNK = 24

        /** 索引が無い・古い記録（id と点の数）。[have] = 今ある索引の id → 作ったときの点の数（版の違う行は入れない）。 */
        fun staleOf(tracks: List<Pair<Long, Int>>, have: Map<Long, Int>): List<Pair<Long, Int>> =
            tracks.filter { (id, count) -> have[id] != count }

        /** 記録の点（記録の順・区間つき）から索引を作る。 */
        fun buildFromPoints(trackId: Long, sourcePoints: Int, pts: List<PointLL>): TrackGeo {
            val segs = ArrayList<List<LatLon>>()
            var cur = ArrayList<LatLon>()
            var seg = Int.MIN_VALUE
            for (p in pts) {
                if (p.segment != seg && cur.isNotEmpty()) {
                    segs.add(cur)
                    cur = ArrayList()
                }
                seg = p.segment
                cur.add(LatLon(p.lat, p.lon))
            }
            if (cur.isNotEmpty()) segs.add(cur)
            return TrackGeo.build(trackId, sourcePoints, segs)
        }

        fun toEntity(g: TrackGeo) = TrackGeoEntity(
            trackId = g.trackId, version = TrackGeo.VERSION, sourcePoints = g.sourcePoints,
            south = g.bounds?.south, west = g.bounds?.west, north = g.bounds?.north, east = g.bounds?.east,
            startLat = g.start?.lat, startLon = g.start?.lon, endLat = g.end?.lat, endLon = g.end?.lon,
            line = TrackGeoCodec.encode(g.lines),
        )

        fun toGeo(e: TrackGeoEntity): TrackGeo {
            val b = if (e.south != null && e.west != null && e.north != null && e.east != null) GeoBounds(e.south, e.west, e.north, e.east) else null
            val s = if (e.startLat != null && e.startLon != null) LatLon(e.startLat, e.startLon) else null
            val en = if (e.endLat != null && e.endLon != null) LatLon(e.endLat, e.endLon) else null
            return TrackGeo(e.trackId, e.sourcePoints, b, s, en, TrackGeoCodec.decode(e.line))
        }
    }
}
