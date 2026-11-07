package jp.kjtr.cycleroutelogger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.PlaceResult
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import jp.kjtr.cycleroutelogger.data.Destination
import jp.kjtr.cycleroutelogger.data.MapKind
import jp.kjtr.cycleroutelogger.data.PlanShape
import jp.kjtr.cycleroutelogger.data.WaypointEntity
import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.plan.WaypointType
import jp.kjtr.cycleroutelogger.sensor.LocationHub
import android.os.SystemClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

fun MapKind.toMapType(): MapType = when (this) {
    MapKind.NORMAL -> MapType.NORMAL
    MapKind.SATELLITE -> MapType.SATELLITE
    MapKind.HYBRID -> MapType.HYBRID
    MapKind.TERRAIN -> MapType.TERRAIN
}

fun LatLon.toLatLng() = LatLng(lat, lon)

/**
 * 地図を範囲に合わせる（[OnMapReady] の後に呼ぶこと）。地図の大きさ [widthPx]×[heightPx] を渡すと、
 * 地図のレイアウト前やタイルが読めない（キーの制限など）ときでも合わせられる。
 */
suspend fun CameraPositionState.fitTo(b: GeoBounds, paddingPx: Int, widthPx: Int = 0, heightPx: Int = 0) {
    val update = if (b.north - b.south < 1e-4 && b.east - b.west < 1e-4) {
        CameraUpdateFactory.newLatLngZoom(LatLng((b.north + b.south) / 2, (b.east + b.west) / 2), 16f)
    } else {
        val bounds = LatLngBounds(LatLng(b.south, b.west), LatLng(b.north, b.east))
        if (widthPx > 0 && heightPx > 0) {
            CameraUpdateFactory.newLatLngBounds(bounds, widthPx, heightPx, paddingPx.coerceAtMost(minOf(widthPx, heightPx) / 4))
        } else {
            CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)
        }
    }
    // animate は地図が描けない状態（キーの制限でタイルが読めないなど）だと取り消されるので、すぐ動かす。
    runCatching { move(update) }
}

/**
 * 地図を現在地へ動かす（ルートプラン・ルートを描く画面の［現在地へ］）。これらの画面では普段測位していないので、
 * 30 秒以内の位置が無ければその場で一度だけ測位する（最大 10 秒待つ。取れなければ最後に分かっている位置）。
 * 位置の許可が無い・位置が分からないときは false。
 */
suspend fun CameraPositionState.moveToCurrentLocation(hub: LocationHub, minZoom: Float = 15f): Boolean {
    if (!hub.hasPermission()) return false
    val maxAgeNs = 30_000_000_000L
    val fresh = hub.last.value?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < maxAgeNs }
    val loc = fresh ?: run {
        hub.acquire(LOCATE_OWNER)
        try {
            withTimeoutOrNull(10_000L) { hub.updates.first() } ?: hub.last.value
        } finally {
            hub.release(LOCATE_OWNER)
        }
    } ?: return false
    val zoom = position.zoom.coerceAtLeast(minZoom)
    runCatching { animate(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), zoom)) }
        .onFailure { runCatching { move(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), zoom)) } }
    return true
}

private const val LOCATE_OWNER = "locate"

/** いま地図に見えている範囲（場所名の検索で近い結果を優先するため）。地図がまだ無い・日付変更線をまたぐときは null。 */
fun CameraPositionState.visibleBounds(): GeoBounds? {
    val b = projection?.visibleRegion?.latLngBounds ?: return null
    if (b.southwest.longitude > b.northeast.longitude) return null
    return GeoBounds(b.southwest.latitude, b.southwest.longitude, b.northeast.latitude, b.northeast.longitude)
}

/**
 * 検索で選んだ場所へ地図を動かす。広がりのある場所（市・大きな公園など。対角 1.5 km 超）はその範囲に合わせ、
 * それ以外はズーム 16（もっと寄っていればそのまま）。描けない地図でアニメーションが終わらなければすぐ動かす。
 */
suspend fun CameraPositionState.showPlace(place: PlaceResult, paddingPx: Int, widthPx: Int, heightPx: Int) {
    val b = place.bounds
    if (b != null && Geo.distanceM(b.south, b.west, b.north, b.east) > 1500.0) {
        fitTo(b, paddingPx, widthPx, heightPx)
        return
    }
    val update = CameraUpdateFactory.newLatLngZoom(LatLng(place.lat, place.lon), position.zoom.coerceAtLeast(16f))
    val done = runCatching { withTimeoutOrNull(800L) { animate(update, 500); true } }.getOrNull()
    if (done != true) runCatching { move(update) }
}

/** 検索で選んだ場所の印（紫のピン）。 */
@Composable
@GoogleMapComposable
fun PlaceResultMarker(place: PlaceResult, color: Color) {
    val state = remember(place.lat, place.lon) { MarkerState(LatLng(place.lat, place.lon)) }
    MarkerComposable(place.lat, place.lon, "place", state = state, zIndex = 6f, anchor = Offset(0.5f, 1f), title = place.name) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(40.dp).background(color, CircleShape).border(3.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Box(Modifier.size(width = 4.dp, height = 10.dp).background(color))
        }
    }
}

/** 地図（GoogleMap オブジェクト）が使えるようになったら呼ぶ。タイルの読み込み完了（onMapLoaded）は待たない。 */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
@GoogleMapComposable
fun OnMapReady(onReady: () -> Unit) {
    MapEffect(Unit) { onReady() }
}

/** プランのルート（表示中のもの）と始点・終点。 */
@Composable
@GoogleMapComposable
fun PlanRoutes(shape: PlanShape?, widthPx: Float) {
    shape ?: return
    for ((route, geo) in shape.visible) {
        val color = Color(route.color)
        for (seg in geo.display) {
            if (seg.size < 2) continue
            Polyline(
                points = remember(seg) { seg.map { it.toLatLng() } },
                color = color.copy(alpha = 0.85f),
                width = widthPx,
                jointType = JointType.ROUND,
                startCap = RoundCap(),
                endCap = RoundCap(),
            )
        }
        val first = geo.segments.firstOrNull()?.firstOrNull()
        val last = geo.segments.lastOrNull()?.lastOrNull()
        if (first != null) EndpointMarker(route.id * 2, first, "S", Color(0xFF2E7D32))
        if (last != null && last != first) EndpointMarker(route.id * 2 + 1, last, "G", Color(0xFFC62828))
    }
}

@Composable
@GoogleMapComposable
private fun EndpointMarker(key: Long, p: LatLon, label: String, color: Color) {
    val state = remember(key, p) { MarkerState(p.toLatLng()) }
    MarkerComposable(key, label, state = state, anchor = Offset(0.5f, 0.5f), zIndex = 1f) {
        Box(
            Modifier.size(22.dp).background(color, CircleShape).border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 経由地のマーカー。[draggable] ならドラッグで動かせ、離したところで [onMoved]。
 * [hiddenTypes] の種類は出さない。
 */
@Composable
@GoogleMapComposable
fun WaypointMarkers(
    waypoints: List<WaypointEntity>,
    hiddenTypes: Set<String>,
    highlightId: Long?,
    draggable: Boolean,
    onClick: (WaypointEntity) -> Unit,
    onMoved: (WaypointEntity, LatLon) -> Unit = { _, _ -> },
) {
    for (w in waypoints) {
        if (w.type in hiddenTypes) continue
        WaypointMarker(w, w.id == highlightId, draggable, onClick, onMoved)
    }
}

@Composable
@GoogleMapComposable
private fun WaypointMarker(
    w: WaypointEntity,
    highlighted: Boolean,
    draggable: Boolean,
    onClick: (WaypointEntity) -> Unit,
    onMoved: (WaypointEntity, LatLon) -> Unit,
) {
    val state = remember(w.id) { MarkerState(LatLng(w.lat, w.lon)) }
    val current = rememberUpdatedState(w)
    val moved = rememberUpdatedState(onMoved)
    LaunchedEffect(w.lat, w.lon) {
        if (!state.isDragging) state.position = LatLng(w.lat, w.lon)
    }
    LaunchedEffect(state) {
        var was = false
        snapshotFlow { state.isDragging }.collect { dragging ->
            if (was && !dragging) moved.value(current.value, LatLon(state.position.latitude, state.position.longitude))
            was = dragging
        }
    }
    val type = WaypointType.fromCode(w.type)
    MarkerComposable(
        w.id, w.type, highlighted,
        state = state,
        draggable = draggable,
        anchor = Offset(0.5f, 0.5f),
        zIndex = if (highlighted) 3f else 2f,
        onClick = { onClick(current.value); true },
    ) {
        WaypointBadge(type, size = if (highlighted) 40.dp else 32.dp, highlighted = highlighted)
    }
}

/** 目的地のマーカー（大きい旗）と、現在地からの破線。 */
@Composable
@GoogleMapComposable
fun DestinationOverlay(dest: Destination, here: LatLon?, color: Color) {
    if (here != null) {
        Polyline(
            points = listOf(here.toLatLng(), LatLng(dest.lat, dest.lon)),
            color = color,
            width = 10f,
            pattern = listOf(Dash(36f), Gap(24f)),
            zIndex = 4f,
        )
    }
    val state = remember(dest.lat, dest.lon) { MarkerState(LatLng(dest.lat, dest.lon)) }
    MarkerComposable(dest.lat, dest.lon, dest.type ?: "", state = state, zIndex = 5f, anchor = Offset(0.5f, 1f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(46.dp).background(color, CircleShape).border(3.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Flag, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Box(Modifier.size(width = 4.dp, height = 12.dp).background(color))
        }
    }
}

/** 記録中・記録の軌跡。 */
@Composable
@GoogleMapComposable
fun TrackLines(segments: List<List<LatLon>>, color: Color, widthPx: Float) {
    for (seg in segments) {
        if (seg.size < 2) continue
        val pts = remember(seg) { jp.kjtr.cycleroutelogger.geo.Simplify.stride(seg, 5000).map { it.toLatLng() } }
        Polyline(points = pts, color = color, width = widthPx, jointType = JointType.ROUND, zIndex = 3f)
    }
}

/**
 * 開拓したマス（1 km 四方。隣り合うマスをまとめた矩形）を半透明で塗る（drive_logger と同じ）。[camera] を渡すと、
 * 地図が止まっているときの表示範囲に入るものだけ描く（マスが多くても重くならないように。上限 CellShapes.MAX_SHAPES）。
 */
@Composable
@GoogleMapComposable
fun CellPolygons(rects: List<jp.kjtr.cycleroutelogger.stats.CellRect>, color: Color, camera: CameraPositionState? = null, zIndex: Float = 0.5f) {
    val moving = camera?.isMoving == true
    var view by remember { mutableStateOf<jp.kjtr.cycleroutelogger.stats.CellRect?>(null) }
    LaunchedEffect(camera, moving) {
        if (camera == null || moving) return@LaunchedEffect
        val b = camera.projection?.visibleRegion?.latLngBounds ?: return@LaunchedEffect
        view = jp.kjtr.cycleroutelogger.stats.CellRect(b.southwest.latitude, b.southwest.longitude, b.northeast.latitude, b.northeast.longitude)
    }
    val shown = remember(rects, view) {
        val v = view
        if (camera == null || v == null) rects.take(jp.kjtr.cycleroutelogger.stats.CellShapes.MAX_SHAPES)
        else jp.kjtr.cycleroutelogger.stats.CellShapes.visible(rects, v)
    }
    val stroke = color.copy(alpha = (color.alpha * 1.8f).coerceAtMost(1f))
    for (r in shown) {
        androidx.compose.runtime.key(r) {
            com.google.maps.android.compose.Polygon(
                points = remember(r) { listOf(LatLng(r.south, r.west), LatLng(r.south, r.east), LatLng(r.north, r.east), LatLng(r.north, r.west)) },
                fillColor = color, strokeColor = stroke, strokeWidth = 1.5f, zIndex = zIndex,
            )
        }
    }
}
