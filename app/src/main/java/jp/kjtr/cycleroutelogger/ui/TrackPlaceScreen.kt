package jp.kjtr.cycleroutelogger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.rememberCameraPositionState
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.RideLines
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.Simplify
import jp.kjtr.cycleroutelogger.stats.MapTiles
import jp.kjtr.cycleroutelogger.stats.PlaceArea
import jp.kjtr.cycleroutelogger.stats.PlaceMatch
import jp.kjtr.cycleroutelogger.stats.PlaceShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.pow

/** 半径のスライダー（0〜1 → 100 m〜100 km の対数）。 */
private fun radiusOf(p: Float): Double = 100.0 * 10.0.pow(3.0 * p.coerceIn(0f, 1f))
private fun sliderOf(r: Double): Float = (log10((r / 100.0).coerceIn(1.0, 1000.0)) / 3.0).toFloat()

/** 場所の名前を残すとみなす中心のずれ [m]（検索で選んだ場所から地図を少し動かしただけなら名前のまま）。 */
private fun labelTolerance(radius: Double) = maxOf(200.0, radius * 0.25)

/**
 * 場所で絞り込む範囲を地図で選ぶ（0.11.0。DESIGN.md §4.11.2）。円 = 地図の中心（＋）と半径、見えている範囲 = 今の地図の表示範囲。
 * 判定は 通った / ここから出発 / ここで終了。地図には全記録の軌跡を薄く、この範囲に当てはまる記録を濃く描く（まとめと同じタイル）。
 * 場所名の検索は検索ボタンを押したときだけ（Nominatim。出典を出す）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrackPlaceScreen(vm: MainViewModel, onBack: () -> Unit) {
    val browser = vm.app.trackBrowser
    val geo by vm.app.geoIndex.state.collectAsState()
    val settings by vm.settings.collectAsState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val init = remember { browser.filter.value.place }
    var shape by rememberSaveable { mutableStateOf(init?.shape ?: PlaceShape.CIRCLE) }
    var radius by rememberSaveable { mutableDoubleStateOf(init?.radiusM?.takeIf { it > 0 } ?: 3000.0) }
    var match by rememberSaveable { mutableStateOf(init?.match ?: PlaceMatch.PASS) }
    var label by rememberSaveable { mutableStateOf(init?.label) }
    var labelLat by rememberSaveable { mutableStateOf(init?.let { if (it.shape == PlaceShape.CIRCLE) it.lat else (it.south + it.north) / 2 }) }
    var labelLon by rememberSaveable { mutableStateOf(init?.let { if (it.shape == PlaceShape.CIRCLE) it.lon else (it.west + it.east) / 2 }) }
    var fitted by rememberSaveable { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(35.681, 139.767), 9f) }
    val search = rememberPlaceSearchState()
    val padPx = with(density) { 32.dp.roundToPx() }

    BackHandler(enabled = search.listShown) { search.listShown = false }

    val allBounds = remember(geo.entries.size) { MapTiles.union(geo.entries.values.map { it.bounds }) }
    LaunchedEffect(ready, size, allBounds) {
        if (fitted || !ready || size.width == 0) return@LaunchedEffect
        val b: GeoBounds? = init?.let { p ->
            if (p.shape == PlaceShape.CIRCLE) PlaceArea(lat = p.lat, lon = p.lon, radiusM = p.radiusM).bounds() else GeoBounds(p.south, p.west, p.north, p.east)
        } ?: allBounds
        if (b != null) {
            camera.fitTo(b, padPx, size.width, size.height)
            fitted = true
        }
    }

    /** いまの地図で選んでいる範囲（名前は、検索で選んだ場所から離れていなければ残す）。 */
    fun currentArea(): PlaceArea? {
        val c = camera.position.target
        return when (shape) {
            PlaceShape.CIRCLE -> {
                val keep = label != null && labelLat != null && labelLon != null &&
                    Geo.distanceM(c.latitude, c.longitude, labelLat!!, labelLon!!) <= labelTolerance(radius)
                PlaceArea(PlaceShape.CIRCLE, lat = c.latitude, lon = c.longitude, radiusM = radius, match = match, label = if (keep) label else null)
            }
            PlaceShape.RECT -> {
                val b = camera.visibleBounds() ?: return null
                val keep = label != null && labelLat != null && labelLon != null &&
                    labelLat!! in b.south..b.north && labelLon!! in b.west..b.east
                PlaceArea(PlaceShape.RECT, south = b.south, west = b.west, north = b.north, east = b.east, match = match, label = if (keep) label else null)
            }
        }
    }

    // 地図が止まったとき・条件を変えたときに、当てはまる記録を数える（索引はメモリにあるので数十 ms）
    var matched by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var matchCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(camera.isMoving, camera.position.target, shape, radius, match, geo.entries, ready, size) {
        if (camera.isMoving || !ready) return@LaunchedEffect
        val area = currentArea() ?: return@LaunchedEffect
        val entries = geo.entries
        val ids = withContext(Dispatchers.Default) { entries.values.filter { it.matches(area) }.mapTo(HashSet()) { it.trackId } }
        matched = ids
        matchCount = ids.size
    }

    // 全記録の軌跡（索引の線。引いたときは 40 m で間引いた線）。当てはまる記録を濃く
    val lines by produceState<List<RideLines>>(emptyList(), geo.entries) {
        value = withContext(Dispatchers.Default) {
            geo.entries.values.map { g ->
                val coarse = g.lines.map { MapTiles.encode(Simplify.forDisplay(MapTiles.decode(it), maxPoints = 300, toleranceM = 40.0)) }
                RideLines(g.trackId, coarse, g.lines)
            }
        }
    }
    // 当てはまらない記録は灰色で薄く、当てはまる記録は赤
    val dim = Color(0xFF546E7A).copy(alpha = 0.30f).toArgb()
    val hit = Color(0xFFE53935).copy(alpha = 0.9f).toArgb()
    val provider = remember(lines, matched) {
        // 当てはまる記録は後ろ（上）に描く
        val ordered = lines.sortedBy { it.trackId in matched }
        RideTileProvider(
            ordered, IntArray(ordered.size) { if (ordered[it].trackId in matched) hit else dim }, widthDp = 3f,
            cells = emptyList(), cellFill = 0, newCells = emptyList(), newCellFill = 0,
        )
    }
    val circleColor = colorResource(R.color.place_result)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.place_pick_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        @Composable
        fun MapArea(modifier: Modifier) {
            BoxWithConstraints(modifier.onSizeChanged { size = it }) {
                val mapHeight = maxHeight
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    properties = MapProperties(mapType = settings.mapKind.toMapType()),
                    uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false, tiltGesturesEnabled = false, rotationGesturesEnabled = false),
                ) {
                    OnMapReady { ready = true }
                    key(provider) { TileOverlay(tileProvider = provider, fadeIn = false, zIndex = 1f) }
                    if (shape == PlaceShape.CIRCLE) {
                        Circle(
                            center = camera.position.target, radius = radius.coerceIn(PlaceArea.MIN_RADIUS_M, PlaceArea.MAX_RADIUS_M),
                            strokeColor = circleColor, strokeWidth = with(density) { 2.dp.toPx() }, fillColor = circleColor.copy(alpha = 0.12f), zIndex = 3f,
                        )
                    }
                    search.picked?.let { PlaceResultMarker(it, circleColor) }
                }
                if (shape == PlaceShape.CIRCLE) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.place_center), tint = circleColor, modifier = Modifier.align(Alignment.Center).size(28.dp))
                }
                Column(Modifier.align(Alignment.TopStart).padding(8.dp).fillMaxWidth(0.82f)) {
                    PlaceSearchBar(search, onSubmit = { search.submit(scope, vm, camera.visibleBounds()) })
                    PlaceResultsPanel(
                        vm, search, origin = PlaceOrigin(jp.kjtr.cycleroutelogger.geo.LatLon(camera.position.target.latitude, camera.position.target.longitude), false),
                        maxHeight = mapHeight * 0.6f,
                        onPick = { p ->
                            search.picked = p
                            search.listShown = false
                            // 名前は、その場所（の点）から地図を大きく動かさない間だけ残す
                            label = p.name
                            labelLat = p.lat
                            labelLon = p.lon
                            scope.launch {
                                if (shape == PlaceShape.CIRCLE) {
                                    // 広がりのある場所（市・大きな公園など）は半径をその大きさに。円が見えるように、場所を中心にして円に合わせる
                                    p.bounds?.let { b ->
                                        val half = Geo.distanceM(b.south, b.west, b.north, b.east) / 2
                                        if (half > 1500.0) radius = half.coerceIn(500.0, 50_000.0)
                                    }
                                    camera.fitTo(PlaceArea(lat = p.lat, lon = p.lon, radiusM = radius).bounds(), padPx, size.width, size.height)
                                } else {
                                    camera.showPlace(p, padPx, size.width, size.height)
                                }
                            }
                        },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                SmallFloatingActionButton(
                    onClick = { val b = allBounds ?: return@SmallFloatingActionButton; if (ready) scope.launch { camera.fitTo(b, padPx, size.width, size.height) } },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) { Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.summary_fit)) }
            }
        }

        @Composable
        fun Panel(modifier: Modifier) {
            Surface(modifier, tonalElevation = 3.dp, shadowElevation = 6.dp) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = shape == PlaceShape.CIRCLE, onClick = { shape = PlaceShape.CIRCLE }, label = { Text(stringResource(R.string.place_shape_circle)) })
                        FilterChip(selected = shape == PlaceShape.RECT, onClick = { shape = PlaceShape.RECT }, label = { Text(stringResource(R.string.place_shape_rect)) })
                    }
                    if (shape == PlaceShape.CIRCLE) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.place_radius, Format.distance(radius)), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(110.dp))
                            Slider(value = sliderOf(radius), onValueChange = { radius = radiusOf(it) }, modifier = Modifier.weight(1f))
                        }
                    } else {
                        Text(stringResource(R.string.place_rect_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in PlaceMatch.entries) FilterChip(selected = match == m, onClick = { match = m }, label = { Text(placeMatchLabel(m)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                geo.building -> stringResource(R.string.place_pick_building, geo.built, geo.toBuild)
                                matchCount >= 0 -> stringResource(R.string.place_pick_count, matchCount)
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
                        Spacer(Modifier.width(4.dp))
                        Button(onClick = {
                            val a = currentArea()
                            if (a != null) browser.update { it.copy(place = a) }
                            onBack()
                        }) { Text(stringResource(R.string.place_pick_apply)) }
                    }
                    if (search.picked != null || label != null) PlaceAttribution(vm)
                }
            }
        }

        if (landscape) {
            Row(Modifier.fillMaxSize().padding(pad)) {
                MapArea(Modifier.weight(1f).fillMaxHeight())
                Panel(Modifier.width(360.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize().padding(pad)) {
                MapArea(Modifier.fillMaxWidth().weight(1f))
                Panel(Modifier.fillMaxWidth())
            }
        }
    }
}
