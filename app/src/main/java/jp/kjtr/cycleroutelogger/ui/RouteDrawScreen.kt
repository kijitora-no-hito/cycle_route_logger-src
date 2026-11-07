package jp.kjtr.cycleroutelogger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.PlanRepository
import jp.kjtr.cycleroutelogger.draw.AnchorKind
import jp.kjtr.cycleroutelogger.draw.DrawnGeometry
import jp.kjtr.cycleroutelogger.draw.DrawnShape
import jp.kjtr.cycleroutelogger.draw.RouteEditor
import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.LatLon
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.pow

/**
 * ルートを描く（フリーハンド）。地図をタップして点を順に打つと直線でつながる。線の近くをタップすると、その位置で区間を分けて点を入れる
 * （曲線でも形は変わらない）。点はドラッグで移動、選んだ点のハンドル（前後の制御点）をドラッグすると曲線（3 次ベジエ）になる。
 * 選んだ点をもう一度タップすると「角 ⇔ なめらか」。下のボタンで削除・種類・全体をなめらかに・折り返し・閉じる・元に戻す / やり直し。
 * ［終わり］でプランのルートとして保存（名前・色）。[routeId] があればそのルートを編集する（GPX のルートは間引いて角の点にする）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDrawScreen(vm: MainViewModel, planId: Long, routeId: Long?, onDone: () -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsState()
    val planShape by remember(planId) { vm.plans.shape(planId) }.collectAsState(null)

    var editor by remember { mutableStateOf(RouteEditor()) }
    var version by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(routeId == null) }
    var convertedFrom by remember { mutableStateOf<Int?>(null) }
    var routeName by remember { mutableStateOf("") }
    var routeColor by remember { mutableIntStateOf(PlanRepository.ROUTE_PALETTE[0]) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var mapReady by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }
    val cameraState = rememberCameraPositionState {
        val l = vm.locationHub.last.value
        position = if (l != null) CameraPosition.fromLatLngZoom(LatLng(l.latitude, l.longitude), 14f)
        else CameraPosition.fromLatLngZoom(LatLng(35.681, 139.767), 12f)
    }

    // 既存のルートを読み込む（描いたルートは制御点、GPX のルートは点を間引いて角の点に）
    LaunchedEffect(routeId) {
        if (routeId == null) {
            val n = vm.plans.routesNow(planId).size
            routeName = vm.app.getString(R.string.draw_default_name, n + 1)
            routeColor = PlanRepository.ROUTE_PALETTE[n % PlanRepository.ROUTE_PALETTE.size]
            return@LaunchedEffect
        }
        val r = vm.plans.routeNow(routeId) ?: return@LaunchedEffect
        routeName = r.name
        routeColor = r.color
        val shape = DrawnShape.fromJson(r.editShape) ?: run {
            val pts = vm.plans.routePoints(routeId).map { LatLon(it.lat, it.lon) }
            convertedFrom = pts.size
            DrawnGeometry.fromPoints(pts)
        }
        editor = RouteEditor(shape)
        version++
        loaded = true
    }

    @Suppress("UNUSED_EXPRESSION") version
    val shape = editor.shape
    val path = remember(version, editor) { DrawnGeometry.path(shape) }
    val lengths = remember(version, editor) { DrawnGeometry.segmentLengths(shape) }
    val total = lengths.sum()
    val changed = editor.canUndo || convertedFrom != null

    LaunchedEffect(loaded, mapReady, mapSize) {
        if (fitted || !loaded || !mapReady || mapSize.width == 0) return@LaunchedEffect
        val b = GeoBounds.of(path) ?: planShape?.bounds ?: return@LaunchedEffect
        fitted = true
        cameraState.fitTo(b, with(density) { 48.dp.roundToPx() }, mapSize.width, mapSize.height)
    }

    fun bump() { version++ }

    fun onTap(p: LatLon) {
        val near = DrawnGeometry.nearest(editor.shape, p)
        val zoom = cameraState.position.zoom.toDouble()
        val metersPerDp = 156543.03392 * cos(Math.toRadians(p.lat)) / 2.0.pow(zoom)
        selected = if (near != null && near.third <= 20 * metersPerDp) {
            editor.insertAt(near.first, near.second)
        } else {
            editor.add(p)
            editor.shape.anchors.size - 1
        }
        bump()
    }

    BackHandler { if (changed) confirmDiscard = true else onDone() }

    // ---- 場所名の検索（0.6.0）: 結果をタップ → 地図がそこへ →［ここに点を打つ］ ----
    val search = rememberPlaceSearchState()
    val lastLoc by vm.locationHub.last.collectAsState()
    val placeColor = colorResource(R.color.place_result)
    var placeOrigin by remember { mutableStateOf<PlaceOrigin?>(null) }
    var placeCardPx by remember { mutableIntStateOf(0) }
    val picked = search.picked
    BackHandler(enabled = search.listShown || picked != null) {
        search.listShown = false
        search.picked = null
    }

    fun runPlaceSearch() {
        val t = cameraState.position.target
        placeOrigin = lastLoc?.let { PlaceOrigin(LatLon(it.latitude, it.longitude), true) } ?: PlaceOrigin(LatLon(t.latitude, t.longitude), false)
        search.submit(scope, vm, cameraState.visibleBounds())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (routeId == null) R.string.draw_title_new else R.string.draw_title_edit)) },
                navigationIcon = {
                    IconButton(onClick = { if (changed) confirmDiscard = true else onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { if (editor.undo()) { selected = null; bump() } }, enabled = editor.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.draw_undo))
                    }
                    IconButton(onClick = { if (editor.redo()) { selected = null; bump() } }, enabled = editor.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.draw_redo))
                    }
                    TextButton(onClick = { saving = true }, enabled = shape.anchors.size >= 2) { Text(stringResource(R.string.draw_done)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).onSizeChanged { mapSize = it }) {
                val paneHeight = maxHeight
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraState,
                    properties = MapProperties(mapType = settings.mapKind.toMapType(), isMyLocationEnabled = vm.locationHub.hasPermission()),
                    uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false, tiltGesturesEnabled = false, rotationGesturesEnabled = false, myLocationButtonEnabled = false),
                    // 選んだ場所のカードの分だけ下を空ける（ズームのボタンと Google のロゴを隠さない）
                    contentPadding = PaddingValues(bottom = if (picked != null) with(density) { placeCardPx.toDp() } + 8.dp else 0.dp),
                    onMapClick = { onTap(LatLon(it.latitude, it.longitude)) },
                ) {
                    OnMapReady { mapReady = true }
                    picked?.let { PlaceResultMarker(it, placeColor) }
                    // プランの他のルート（薄く）
                    planShape?.visible?.filter { it.first.id != routeId }?.forEach { (_, g) ->
                        g.display.forEach { seg ->
                            if (seg.size >= 2) Polyline(points = remember(seg) { seg.map { it.toLatLng() } }, color = Color(0x66607D8B), width = with(density) { 4.dp.toPx() })
                        }
                    }
                    if (path.size >= 2) {
                        Polyline(
                            points = remember(path) { path.map { it.toLatLng() } },
                            color = Color(routeColor), width = with(density) { 5.dp.toPx() }, jointType = JointType.ROUND, zIndex = 2f,
                        )
                    }
                    shape.anchors.forEachIndexed { i, a ->
                        AnchorMarker(
                            index = i, pos = a.latLon, kind = a.kind, selected = selected == i,
                            onClick = {
                                if (selected == i) { editor.toggleSmooth(i); bump() } else selected = i
                            },
                            onDragStart = { editor.beginChange(); selected = i },
                            onDrag = { p -> editor.move(i, p, record = false); bump() },
                        )
                    }
                    // 選んだ点のハンドル
                    selected?.takeIf { it in shape.anchors.indices }?.let { i ->
                        val a = shape.anchors[i]
                        val enu = DrawnGeometry.enu(shape)
                        val base = enu.toVec(a)
                        listOf(true to a.outH, false to a.inH).forEach { (isOut, h) ->
                            if (h != null) {
                                val hp = enu.toLatLon(base + h)
                                Polyline(points = listOf(a.latLon.toLatLng(), hp.toLatLng()), color = Color(0xFF455A64), width = 3f, zIndex = 3f)
                                HandleMarker(
                                    key = i * 2 + if (isOut) 1 else 0, pos = hp,
                                    onDragStart = { editor.beginChange() },
                                    onDrag = { p ->
                                        val e2 = DrawnGeometry.enu(editor.shape)
                                        val anchorVec = e2.toVec(editor.shape.anchors[i])
                                        editor.moveHandle(i, isOut, e2.toVec(p) - anchorVec, record = false)
                                        bump()
                                    },
                                )
                            }
                        }
                    }
                }
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (!cameraState.moveToCurrentLocation(vm.locationHub)) vm.toast(vm.app, vm.app.getString(R.string.my_location_unavailable))
                        }
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) { Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.go_my_location)) }
                Column(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 64.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PlaceSearchBar(search, onSubmit = { runPlaceSearch() }, modifier = Modifier.fillMaxWidth())
                    PlaceResultsPanel(
                        vm, search, placeOrigin,
                        maxHeight = paneHeight - 72.dp,
                        onPick = { p ->
                            search.picked = p
                            search.listShown = false
                            scope.launch { cameraState.showPlace(p, with(density) { 48.dp.roundToPx() }, mapSize.width, mapSize.height) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!search.listShown && picked == null) {
                        Surface(color = Color(0xCC000000), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                stringResource(if (shape.anchors.isEmpty()) R.string.draw_hint_empty else R.string.draw_hint),
                                color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
                if (picked != null) {
                    PickedPlaceCard(
                        vm, picked, placeOrigin,
                        onClose = { search.picked = null },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).onSizeChanged { placeCardPx = it.height },
                    ) {
                        FilledTonalButton(onClick = {
                            editor.add(picked.latLon)
                            selected = editor.shape.anchors.size - 1
                            bump()
                            search.picked = null
                        }) { Text(stringResource(R.string.place_add_anchor)) }
                        TextButton(onClick = { search.listShown = true; search.picked = null }) { Text(stringResource(R.string.place_back_to_results)) }
                    }
                }
            }

            // 距離と操作
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 6.dp)) {
                convertedFrom?.let { Text(stringResource(R.string.draw_converted, it, shape.anchors.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                val sel = selected?.takeIf { it in shape.anchors.indices }
                Text(
                    stringResource(R.string.draw_total, Format.km(total), shape.anchors.size) + (sel?.let { i ->
                        val before = lengths.getOrNull(i - 1)?.let { Format.distance(it) } ?: "—"
                        val after = lengths.getOrNull(i)?.let { Format.distance(it) } ?: "—"
                        "\n" + stringResource(R.string.draw_selected, i + 1, kindLabel(shape.anchors[i].kind), before, after)
                    } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (sel != null) {
                        FilledTonalButton(onClick = { editor.delete(sel); selected = null; bump() }) { Text(stringResource(R.string.draw_delete_point)) }
                        AnchorKind.entries.forEach { k ->
                            OutlinedButton(
                                onClick = { editor.setKind(sel, k); bump() },
                                border = if (shape.anchors[sel].kind == k) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            ) { Text(kindLabel(k)) }
                        }
                        OutlinedButton(onClick = { editor.clearHandles(sel); bump() }) { Text(stringResource(R.string.draw_clear_handles)) }
                    }
                    OutlinedButton(onClick = { editor.smoothAll(); bump() }, enabled = shape.anchors.size >= 3) { Text(stringResource(R.string.draw_smooth_all)) }
                    OutlinedButton(onClick = { editor.outAndBack(); bump() }, enabled = shape.anchors.size >= 2) { Text(stringResource(R.string.draw_out_and_back)) }
                    OutlinedButton(onClick = { editor.close(); bump() }, enabled = shape.anchors.size >= 3) { Text(stringResource(R.string.draw_close)) }
                }
            }
        }
    }

    if (saving) {
        var name by remember { mutableStateOf(routeName) }
        var color by remember { mutableIntStateOf(routeColor) }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text(stringResource(R.string.draw_save_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.route_name)) }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlanRepository.ROUTE_PALETTE.forEach { c ->
                            Box(
                                Modifier.size(28.dp).background(Color(c), CircleShape)
                                    .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { color = c },
                            )
                        }
                    }
                    Text(stringResource(R.string.draw_save_text, Format.km(total)), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saving = false
                    scope.launch {
                        vm.plans.saveDrawnRoute(planId, routeId, name.ifBlank { routeName }, color, editor.shape)
                        vm.toast(vm.app, vm.app.getString(R.string.draw_saved))
                        onDone()
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.draw_discard_title), text = null, confirm = stringResource(R.string.draw_discard),
            onConfirm = onDone, onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun kindLabel(k: AnchorKind): String = stringResource(
    when (k) {
        AnchorKind.CORNER -> R.string.draw_kind_corner
        AnchorKind.SMOOTH -> R.string.draw_kind_smooth
        AnchorKind.SYMMETRIC -> R.string.draw_kind_symmetric
    },
)

/** 点（アンカー）のマーカー。角 = 四角、なめらか = 丸、対称 = ひし形。ドラッグ中は [onDrag] を続けて呼ぶ。 */
@Composable
@GoogleMapComposable
private fun AnchorMarker(
    index: Int,
    pos: LatLon,
    kind: AnchorKind,
    selected: Boolean,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (LatLon) -> Unit,
) {
    val state = remember(index) { MarkerState(pos.toLatLng()) }
    val start = rememberUpdatedState(onDragStart)
    val drag = rememberUpdatedState(onDrag)
    LaunchedEffect(pos.lat, pos.lon) { if (!state.isDragging) state.position = pos.toLatLng() }
    LaunchedEffect(state) {
        var was = false
        snapshotFlow { state.isDragging to state.position }.collect { (dragging, p) ->
            if (dragging && !was) start.value()
            if (dragging || was) drag.value(LatLon(p.latitude, p.longitude))
            was = dragging
        }
    }
    val sizeDp = if (selected) 22.dp else 16.dp
    val fill = if (selected) Color(0xFFE65100) else Color.White
    MarkerComposable(index, kind, selected, state = state, draggable = true, anchor = Offset(0.5f, 0.5f), zIndex = 5f, onClick = { onClick(); true }) {
        Box(Modifier.size(sizeDp + 8.dp), contentAlignment = Alignment.Center) {
            val shape = if (kind == AnchorKind.CORNER || kind == AnchorKind.SYMMETRIC) RoundedCornerShape(2.dp) else CircleShape
            Box(
                Modifier.size(sizeDp).rotate(if (kind == AnchorKind.SYMMETRIC) 45f else 0f)
                    .background(fill, shape).border(3.dp, Color(0xFF263238), shape),
            )
        }
    }
}

/** ハンドル（制御点）のマーカー。 */
@Composable
@GoogleMapComposable
private fun HandleMarker(key: Int, pos: LatLon, onDragStart: () -> Unit, onDrag: (LatLon) -> Unit) {
    val state = remember(key) { MarkerState(pos.toLatLng()) }
    val start = rememberUpdatedState(onDragStart)
    val drag = rememberUpdatedState(onDrag)
    LaunchedEffect(pos.lat, pos.lon) { if (!state.isDragging) state.position = pos.toLatLng() }
    LaunchedEffect(state) {
        var was = false
        snapshotFlow { state.isDragging to state.position }.collect { (dragging, p) ->
            if (dragging && !was) start.value()
            if (dragging || was) drag.value(LatLon(p.latitude, p.longitude))
            was = dragging
        }
    }
    MarkerComposable(key, "h", state = state, draggable = true, anchor = Offset(0.5f, 0.5f), zIndex = 6f) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(14.dp).background(Color(0xFF1E88E5), CircleShape).border(2.dp, Color.White, CircleShape))
        }
    }
}
