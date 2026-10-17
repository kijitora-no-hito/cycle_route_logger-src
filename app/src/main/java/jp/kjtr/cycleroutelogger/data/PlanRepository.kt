package jp.kjtr.cycleroutelogger.data

import jp.kjtr.cycleroutelogger.geo.AscentCounter
import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.RouteMatcher
import jp.kjtr.cycleroutelogger.geo.Simplify
import jp.kjtr.cycleroutelogger.gpx.GpxData
import jp.kjtr.cycleroutelogger.gpx.GpxPoint
import jp.kjtr.cycleroutelogger.gpx.GpxTrack
import jp.kjtr.cycleroutelogger.gpx.GpxWaypoint
import jp.kjtr.cycleroutelogger.plan.WaypointType
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** ルート 1 本の形（地図表示・案内用）。点は変わらないので routeId ごとにキャッシュする。 */
class RouteGeometry(
    val routeId: Long,
    /** 区間ごとの全点。 */
    val segments: List<List<LatLon>>,
    /** 地図に描く間引き後の線。 */
    val display: List<List<LatLon>>,
    /** 標高グラフ（始点からの距離 m, 標高 m）。標高が無ければ空。 */
    val profile: List<Pair<Double, Double>>,
    val bounds: GeoBounds?,
)

/** プランの中身（並び順どおりのルートと、その形）。 */
data class PlanShape(
    val routes: List<Pair<RouteEntity, RouteGeometry>>,
) {
    val visible: List<Pair<RouteEntity, RouteGeometry>> get() = routes.filter { it.first.visible }

    /** 表示中のルートを並び順につないだ案内用の線。 */
    fun matcher(): RouteMatcher = RouteMatcher(visible.flatMap { it.second.segments })

    val bounds: GeoBounds? get() = GeoBounds.union(visible.mapNotNull { it.second.bounds })
}

data class ReplaceResult(val routes: Int, val waypoints: Int, val removedWaypointIds: List<Long>)

data class ImportResult(
    val planId: Long,
    val routes: Int,
    val waypoints: Int,
    val decimated: Boolean,
    val partial: Boolean,
)

class PlanRepository(private val db: AppDatabase) {
    private val dao = db.plans()
    private val cache = object : LinkedHashMap<Long, RouteGeometry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RouteGeometry>?): Boolean = size > 24
    }

    fun summaries(): Flow<List<PlanSummary>> = dao.summaries()
    fun plan(id: Long): Flow<PlanEntity?> = dao.plan(id)
    fun routes(planId: Long): Flow<List<RouteEntity>> = dao.routes(planId)
    fun waypoints(planId: Long): Flow<List<WaypointEntity>> = dao.waypoints(planId)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun shape(planId: Long?): Flow<PlanShape?> =
        if (planId == null) flowOf(null)
        else dao.routes(planId).mapLatest { list -> PlanShape(list.map { it to geometry(it) }) }.flowOn(Dispatchers.Default)

    suspend fun geometry(route: RouteEntity): RouteGeometry {
        synchronized(cache) { cache[route.id] }?.let { return it }
        val pts = dao.routePoints(route.id)
        val g = buildGeometry(route.id, pts)
        synchronized(cache) { cache[route.id] = g }
        return g
    }

    suspend fun createPlan(name: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertPlan(PlanEntity(name = name, createdAt = now, updatedAt = now))
    }

    suspend fun updatePlan(id: Long, name: String? = null, memo: String? = null) {
        val p = dao.planNow(id) ?: return
        dao.updatePlan(p.copy(name = name ?: p.name, memo = memo ?: p.memo, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deletePlan(id: Long) = dao.deletePlan(id)

    /** 更新日時だけ今にする（一覧の並びの先頭へ）。 */
    suspend fun touchPlan(id: Long) = dao.touch(id, System.currentTimeMillis())

    /** プランを複製（ルートの点・経由地もすべて）。 */
    suspend fun duplicatePlan(id: Long, newName: String): Long = withContext(Dispatchers.IO) {
        val p = dao.planNow(id) ?: return@withContext 0L
        val now = System.currentTimeMillis()
        val newId = dao.insertPlan(p.copy(id = 0, name = newName, createdAt = now, updatedAt = now))
        for (r in dao.routesNow(id)) {
            val pts = dao.routePoints(r.id)
            val segs = pts.groupBy { it.segment }.toSortedMap().values.toList()
            dao.insertRouteWithPoints(r.copy(id = 0, planId = newId, createdAt = now), segs)
        }
        dao.insertWaypoints(dao.waypointsNow(id).map { it.copy(id = 0, planId = newId) })
        newId
    }

    /** GPX をプランに取り込む。trk / rte 1 本 = ルート 1 本、wpt = 経由地。[sourceUrl] は URL から取り込んだときの URL。 */
    suspend fun importGpx(planId: Long, data: GpxData, fileName: String, sourceUrl: String? = null): ImportResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var colorIndex = dao.maxSortOrder(planId) + 1
        data.tracks.forEachIndexed { i, t ->
            insertRoute(planId, routeName(data, i, fileName), ROUTE_PALETTE[colorIndex++ % ROUTE_PALETTE.size], t.segments, now, null, sourceUrl = sourceUrl)
        }
        dao.insertWaypoints(data.waypoints.mapIndexed { i, w -> waypointFromGpx(planId, w, now + i, sourceUrl) })
        dao.touch(planId, now)
        ImportResult(planId, data.tracks.size, data.waypoints.size, data.decimated, data.partial)
    }

    private fun routeName(data: GpxData, i: Int, fileName: String): String {
        val base = fileName.substringBeforeLast('.').ifBlank { fileName }
        val t = data.tracks[i]
        return t.name ?: data.name?.let { if (data.tracks.size > 1) "$it (${i + 1})" else it }
            ?: if (data.tracks.size > 1) "$base (${i + 1})" else base
    }

    /** プランの中で URL から取り込んだもの（再読み込みの候補）。 */
    fun sourceUrls(planId: Long): Flow<List<String>> = dao.sourceUrls(planId)

    /** その URL から取り込んだルートと経由地の数。 */
    suspend fun countFromSource(planId: Long, url: String): Pair<Int, Int> =
        dao.routesFromSource(planId, url).size to dao.waypointIdsFromSource(planId, url).size

    /**
     * URL から取り直した GPX で、その URL から取り込んだルートと経由地を置き換える（再読み込み）。
     * ルートの色・表示・並びの位置は前のものを順に引き継ぐ。手で足した経由地・他のルートはそのまま。
     * 戻り値の [ReplaceResult.removedWaypointIds] は消した経由地（目的地にしていたら外すため）。
     */
    suspend fun replaceFromSource(planId: Long, url: String, data: GpxData, fileName: String): ReplaceResult = withContext(Dispatchers.IO) {
        val removedWps = dao.waypointIdsFromSource(planId, url)
        db.withTransaction {
            val now = System.currentTimeMillis()
            val old = dao.routesFromSource(planId, url)
            for (r in old) {
                dao.deleteRoute(r.id)
                synchronized(cache) { cache.remove(r.id) }
            }
            dao.deleteWaypointsFromSource(planId, url)
            var colorIndex = dao.maxSortOrder(planId) + 1
            val newIds = data.tracks.mapIndexed { i, t ->
                val prev = old.getOrNull(i)
                insertRoute(
                    planId, routeName(data, i, fileName), prev?.color ?: ROUTE_PALETTE[colorIndex++ % ROUTE_PALETTE.size],
                    t.segments, now, null, prev?.visible ?: true, url,
                )
            }
            // 並びの位置を前のルートのところへ（足りない分は末尾のまま）→ 0 から振り直す
            val inserted = dao.routesNow(planId).filter { it.id in newIds }.associateBy { it.id }
            val moved = newIds.mapIndexedNotNull { i, id -> old.getOrNull(i)?.let { o -> inserted[id]?.copy(sortOrder = o.sortOrder) } }
            if (moved.isNotEmpty()) dao.updateRoutes(moved)
            val all = dao.routesNow(planId)
            dao.updateRoutes(all.mapIndexed { idx, e -> e.copy(sortOrder = idx) })
            dao.insertWaypoints(data.waypoints.mapIndexed { i, w -> waypointFromGpx(planId, w, now + i, url) })
            dao.touch(planId, now)
        }
        ReplaceResult(data.tracks.size, data.waypoints.size, removedWps)
    }

    suspend fun insertRoute(
        planId: Long,
        name: String,
        color: Int,
        segments: List<List<GpxPoint>>,
        now: Long,
        sourceTrackId: Long?,
        visible: Boolean = true,
        sourceUrl: String? = null,
        editShape: String? = null,
    ): Long {
        val segs = segments.filter { it.isNotEmpty() }
        val distance = segs.sumOf { s -> Geo.lengthM(s.map { it.latLon() }) }
        val ascent = AscentCounter.forRoute(segs.map { s -> s.map { it.ele } })
        val route = RouteEntity(
            planId = planId, sortOrder = 0, name = name, color = color, visible = visible, createdAt = now,
            distanceM = distance, ascentM = ascent, pointCount = segs.sumOf { it.size },
            hasElevation = segs.any { s -> s.any { it.ele != null } }, sourceTrackId = sourceTrackId, sourceUrl = sourceUrl,
            editShape = editShape,
        )
        return dao.insertRouteWithPoints(route, segs.map { s -> s.map { RoutePointEntity(0, 0, 0, it.lat, it.lon, it.ele) } })
    }

    suspend fun routeNow(id: Long): RouteEntity? = dao.routeNow(id)
    suspend fun routePoints(id: Long): List<RoutePointEntity> = dao.routePoints(id)

    /**
     * 地図に描いたルートを保存する。点（route_point）は [shape] をサンプリングしたもの（標高なし）、
     * [RouteEntity.editShape] に制御点の JSON。[routeId] があればそのルートを置き換える（色・表示・並びはそのまま）。
     */
    suspend fun saveDrawnRoute(planId: Long, routeId: Long?, name: String, color: Int, shape: jp.kjtr.cycleroutelogger.draw.DrawnShape): Long =
        withContext(Dispatchers.IO) {
            val pts = jp.kjtr.cycleroutelogger.draw.DrawnGeometry.path(shape)
            val segs = listOf(pts.map { GpxPoint(it.lat, it.lon) })
            val now = System.currentTimeMillis()
            val old = routeId?.let { dao.routeNow(it) }
            val id = if (old != null) {
                val r = old.copy(
                    name = name, color = color, distanceM = Geo.lengthM(pts), ascentM = 0.0, pointCount = pts.size,
                    hasElevation = false, editShape = shape.toJson(), sourceUrl = null,
                )
                dao.replaceRoutePoints(r, segs.map { s -> s.map { RoutePointEntity(0, 0, 0, it.lat, it.lon, null) } })
                synchronized(cache) { cache.remove(r.id) }
                r.id
            } else {
                insertRoute(planId, name, color, segs, now, null, editShape = shape.toJson())
            }
            dao.touch(planId, now)
            id
        }

    suspend fun updateRoute(r: RouteEntity) {
        dao.updateRoute(r)
        dao.touch(r.planId, System.currentTimeMillis())
    }

    suspend fun deleteRoute(r: RouteEntity) {
        dao.deleteRoute(r.id)
        synchronized(cache) { cache.remove(r.id) }
        dao.touch(r.planId, System.currentTimeMillis())
    }

    /** 並び順を 1 つ上（-1）/ 下（+1）へ。 */
    suspend fun moveRoute(r: RouteEntity, delta: Int) {
        val list = dao.routesNow(r.planId).toMutableList()
        val i = list.indexOfFirst { it.id == r.id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        dao.updateRoutes(list.mapIndexed { idx, e -> e.copy(sortOrder = idx) })
        dao.touch(r.planId, System.currentTimeMillis())
    }

    suspend fun addWaypoint(w: WaypointEntity): Long {
        val id = dao.insertWaypoint(w)
        dao.touch(w.planId, System.currentTimeMillis())
        return id
    }

    suspend fun updateWaypoint(w: WaypointEntity) {
        dao.updateWaypoint(w)
        dao.touch(w.planId, System.currentTimeMillis())
    }

    suspend fun deleteWaypoint(w: WaypointEntity) {
        dao.deleteWaypoint(w.id)
        dao.touch(w.planId, System.currentTimeMillis())
    }

    suspend fun planNow(id: Long) = dao.planNow(id)
    suspend fun routesNow(planId: Long) = dao.routesNow(planId)
    suspend fun waypointsNow(planId: Long) = dao.waypointsNow(planId)
    suspend fun allPlans() = dao.allPlans()

    /** プランを GPX のデータに（ルート = trk、経由地 = wpt）。 */
    suspend fun toGpx(planId: Long): GpxData? = withContext(Dispatchers.IO) {
        val p = dao.planNow(planId) ?: return@withContext null
        val tracks = dao.routesNow(planId).map { r ->
            val pts = dao.routePoints(r.id)
            val segs = pts.groupBy { it.segment }.toSortedMap().values.map { s -> s.map { GpxPoint(it.lat, it.lon, it.ele) } }
            GpxTrack(r.name, segs, isRoute = false)
        }
        GpxData(
            name = p.name,
            desc = p.memo.ifBlank { null },
            tracks = tracks,
            waypoints = dao.waypointsNow(planId).map { waypointToGpx(it) },
            version = "1.1",
        )
    }

    companion object {
        /** 新しいルートの色（読み込んだ順に回す）。 */
        val ROUTE_PALETTE = intArrayOf(
            0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF00897B.toInt(), 0xFFF4511E.toInt(),
            0xFF6D4C41.toInt(), 0xFFD81B60.toInt(), 0xFF43A047.toInt(), 0xFF3949AB.toInt(),
        )

        fun waypointFromGpx(planId: Long, w: GpxWaypoint, createdAt: Long, sourceUrl: String? = null): WaypointEntity =
            WaypointEntity(
                sourceUrl = sourceUrl,
                planId = planId,
                name = w.name ?: "",
                type = WaypointType.infer(w.type, w.sym, w.name).code,
                memo = w.desc ?: "",
                plannedAt = w.timeMs,
                lat = w.lat,
                lon = w.lon,
                ele = w.ele,
                createdAt = createdAt,
            )

        fun waypointToGpx(w: WaypointEntity): GpxWaypoint {
            val type = WaypointType.fromCode(w.type)
            return GpxWaypoint(
                lat = w.lat, lon = w.lon, ele = w.ele, timeMs = w.plannedAt,
                name = w.name.ifBlank { null }, desc = w.memo.ifBlank { null },
                type = type.code, sym = type.gpxSym,
            )
        }

        fun buildGeometry(routeId: Long, pts: List<RoutePointEntity>): RouteGeometry {
            val bySeg = pts.groupBy { it.segment }.toSortedMap().values.toList()
            val segments = bySeg.map { s -> s.map { LatLon(it.lat, it.lon) } }
            val perSegMax = (MAX_DISPLAY_POINTS / segments.size.coerceAtLeast(1)).coerceAtLeast(50)
            val display = segments.map { Simplify.forDisplay(it, perSegMax) }
            // 標高グラフ（区間の間の空白は距離に数えない）
            val profile = ArrayList<Pair<Double, Double>>()
            var dist = 0.0
            for (s in bySeg) {
                for (i in s.indices) {
                    if (i > 0) dist += Geo.distanceM(s[i - 1].lat, s[i - 1].lon, s[i].lat, s[i].lon)
                    s[i].ele?.let { profile.add(dist to it) }
                }
            }
            return RouteGeometry(
                routeId = routeId,
                segments = segments,
                display = display,
                profile = Simplify.stride(profile, MAX_PROFILE_POINTS),
                bounds = GeoBounds.of(segments.flatten()),
            )
        }

        const val MAX_DISPLAY_POINTS = 6_000
        const val MAX_PROFILE_POINTS = 600
    }
}
