package jp.kjtr.cycleroutelogger.guide

import android.location.Location
import jp.kjtr.cycleroutelogger.data.Destination
import jp.kjtr.cycleroutelogger.data.PlanRepository
import jp.kjtr.cycleroutelogger.data.PlanShape
import jp.kjtr.cycleroutelogger.data.Settings
import jp.kjtr.cycleroutelogger.data.SettingsStore
import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.WaypointEntity
import jp.kjtr.cycleroutelogger.geo.Bearing
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.RouteMatcher
import jp.kjtr.cycleroutelogger.plan.OnRoute
import jp.kjtr.cycleroutelogger.plan.WaypointsOnRoute
import jp.kjtr.cycleroutelogger.sensor.LocationHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 走行中のプランの案内（外れ距離・進捗・次の経由地）。 */
data class GuideState(
    val match: RouteMatcher.Match?,
    val totalM: Double,
    val offRoute: Boolean,
    val next: OnRoute<WaypointEntity>?,
    val nextAlongM: Double?,
    val nextStraightM: Double?,
)

/** 目的地（今向かいたい地点）までの距離と方角。 */
data class DestinationState(
    val destination: Destination,
    /** 直線距離 [m]（現在地が無ければ null）。 */
    val distanceM: Double?,
    /** 現在地から目的地への方位角（度）。 */
    val bearingDeg: Double?,
    /** ルートサイクリングで目的地がルートの近くにあるときの、ルート沿いの残り [m]。 */
    val alongRouteM: Double?,
)

/** プラン（ルート + 経由地）と、ルート沿いに並べた経由地。 */
data class ActivePlan(
    val planId: Long,
    val shape: PlanShape?,
    val matcher: RouteMatcher?,
    val waypoints: List<OnRoute<WaypointEntity>>,
)

/**
 * 走行画面の案内をアプリ全体で 1 つ計算する（画面を消して記録中でも、外れ・接近・到着を知らせるため）。
 * 位置は [LocationHub.last]。誰も測位していなければ止まったまま。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Navigator(
    private val scope: CoroutineScope,
    private val hub: LocationHub,
    private val settingsStore: SettingsStore,
    private val plans: PlanRepository,
    private val alerter: Alerter,
) {
    /** ルートサイクリング中のプラン（フリーなら null）。 */
    val activePlan: StateFlow<ActivePlan?> = settingsStore.ride
        .map { r -> if (r?.mode == TrackEntity.MODE_ROUTE) r.planId else null }
        .distinctUntilChanged()
        .flatMapLatest { planId ->
            if (planId == null) flowOf(null)
            else combine(plans.shape(planId), plans.waypoints(planId)) { shape, wps ->
                val matcher = shape?.matcher()?.takeIf { !it.isEmpty }
                ActivePlan(planId, shape, matcher, WaypointsOnRoute.sort(matcher, wps, { LatLon(it.lat, it.lon) }, { it.createdAt }))
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _guide = MutableStateFlow<GuideState?>(null)
    val guide: StateFlow<GuideState?> = _guide

    private val _destination = MutableStateFlow<DestinationState?>(null)
    val destination: StateFlow<DestinationState?> = _destination

    private val _arrival = MutableStateFlow<Destination?>(null)
    /** 目的地に着いた（画面で「解除 / 次の経由地へ」を聞く）。 */
    val arrival: StateFlow<Destination?> = _arrival

    private var offRoute = false
    private val approached = HashSet<Long>()
    /** この走行で着いた経由地（自動で次の目的地を選ぶときに飛ばす）。 */
    private val arrivedIds = HashSet<Long>()
    private var arrivedFor: Destination? = null
    private var lastAutoNextId: Long? = null
    private var destProgressCache: Pair<Pair<Destination, RouteMatcher>, RouteMatcher.Match?>? = null

    init {
        scope.launch(Dispatchers.Default) {
            combine(hub.last, activePlan, settingsStore.destination, settingsStore.settings) { loc, plan, dest, s ->
                Quad(loc, plan, dest, s)
            }.collect { (loc, plan, dest, s) -> update(loc, plan, dest, s) }
        }
    }

    private data class Quad(val loc: Location?, val plan: ActivePlan?, val dest: Destination?, val s: Settings)

    private fun update(loc: Location?, plan: ActivePlan?, dest: Destination?, s: Settings) {
        val here = loc?.let { LatLon(it.latitude, it.longitude) }
        val fresh = loc != null && (loc.accuracy <= 50f || !loc.hasAccuracy())

        // ルートの案内
        val matcher = plan?.matcher
        val match = if (here != null && matcher != null) matcher.match(here) else null
        var next: OnRoute<WaypointEntity>? = null
        if (plan != null) {
            next = if (match != null) {
                WaypointsOnRoute.next(plan.waypoints.filter { it.item.id !in arrivedIds }, match.progressM)
            } else {
                null
            }
            _guide.value = GuideState(
                match = match,
                totalM = matcher?.totalM ?: 0.0,
                offRoute = updateOffRoute(match, s, fresh),
                next = next,
                nextAlongM = if (next != null && match != null) WaypointsOnRoute.alongDistanceM(next, match.progressM) else null,
                nextStraightM = if (next != null && here != null) Geo.distanceM(here, LatLon(next.item.lat, next.item.lon)) else null,
            )
            // 経由地への接近（直線 100 m 以内、1 か所 1 回）
            if (here != null && fresh && s.approachAlert) {
                for (w in plan.waypoints) {
                    val id = w.item.id
                    if (id in approached) continue
                    if (Geo.distanceM(here, LatLon(w.item.lat, w.item.lon)) <= APPROACH_M) {
                        approached.add(id)
                        alerter.alert(Alerter.Kind.APPROACH, s.alertSound, s.alertVibrate)
                        break
                    }
                }
            }
            // 次の経由地を自動で目的地に
            if (s.autoNextDestination && next != null && next.item.id != lastAutoNextId) {
                lastAutoNextId = next.item.id
                if (dest?.waypointId != next.item.id) {
                    settingsStore.setDestination(destinationOf(next.item))
                    return // 目的地が変わったので次の更新で計算し直す
                }
            }
        } else {
            _guide.value = null
            offRoute = false
        }

        // 目的地
        if (dest == null) {
            _destination.value = null
            arrivedFor = null
            return
        }
        val distance = here?.let { Geo.distanceM(it, LatLon(dest.lat, dest.lon)) }
        val bearing = here?.let { Bearing.initialDeg(it, LatLon(dest.lat, dest.lon)) }
        var along: Double? = null
        if (matcher != null && match != null) {
            val cache = destProgressCache
            val key = dest to matcher
            val dm = if (cache != null && cache.first == key) cache.second else matcher.match(LatLon(dest.lat, dest.lon)).also { destProgressCache = key to it }
            if (dm != null && dm.deviationM <= DEST_NEAR_ROUTE_M && dm.progressM >= match.progressM) {
                along = dm.progressM - match.progressM
            }
        }
        _destination.value = DestinationState(dest, distance, bearing, along)
        if (distance != null && fresh && distance <= ARRIVAL_M && arrivedFor != dest) {
            arrivedFor = dest
            dest.waypointId?.let { arrivedIds.add(it) }
            _arrival.value = dest
            alerter.alert(Alerter.Kind.ARRIVAL, s.alertSound, s.alertVibrate)
        }
    }

    private fun updateOffRoute(match: RouteMatcher.Match?, s: Settings, fresh: Boolean): Boolean {
        if (match == null || !fresh) return offRoute
        val th = s.offRouteThresholdM.toDouble()
        if (!offRoute && match.deviationM > th) {
            offRoute = true
            if (s.offRouteAlert) alerter.alert(Alerter.Kind.OFF_ROUTE, s.alertSound, s.alertVibrate)
        } else if (offRoute && match.deviationM < th * 0.6) {
            offRoute = false
            if (s.offRouteAlert) alerter.alert(Alerter.Kind.BACK_ON_ROUTE, s.alertSound, s.alertVibrate)
        }
        return offRoute
    }

    fun setDestination(d: Destination?) {
        settingsStore.setDestination(d)
        _arrival.value = null
    }

    fun dismissArrival() {
        _arrival.value = null
    }

    /** 着いた目的地を解除する。 */
    fun clearAfterArrival() {
        setDestination(null)
    }

    /** 着いた経由地の次（ルート沿いの順）を目的地にする。無ければ解除。 */
    fun advanceToNextWaypoint() {
        val plan = activePlan.value
        val cur = settingsStore.destination.value
        cur?.waypointId?.let { arrivedIds.add(it) }
        val list = plan?.waypoints.orEmpty()
        val idx = list.indexOfFirst { it.item.id == cur?.waypointId }
        val candidate = if (idx >= 0) {
            list.drop(idx + 1).firstOrNull { it.item.id !in arrivedIds }
        } else {
            val progress = _guide.value?.match?.progressM
            if (progress != null) WaypointsOnRoute.next(list.filter { it.item.id !in arrivedIds }, progress) else null
        }
        setDestination(candidate?.let { destinationOf(it.item) })
        candidate?.let { lastAutoNextId = it.item.id }
    }

    /** 新しい走行を始めるとき（接近・到着の記憶を消す）。 */
    fun resetRideMemory() {
        approached.clear()
        arrivedIds.clear()
        arrivedFor = null
        lastAutoNextId = null
    }

    companion object {
        const val APPROACH_M = 100.0
        const val ARRIVAL_M = 30.0
        /** 目的地がこの距離以内でルートに近ければ「ルート沿い」を出す [m]。 */
        const val DEST_NEAR_ROUTE_M = 200.0

        fun destinationOf(w: WaypointEntity) = Destination(w.lat, w.lon, w.name, w.id, w.type)
    }
}
