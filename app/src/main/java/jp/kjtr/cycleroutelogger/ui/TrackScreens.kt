package jp.kjtr.cycleroutelogger.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.SettingsStore
import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.TrackPointEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.geo.Geo
import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.Simplify
import jp.kjtr.cycleroutelogger.geo.SmoothConfig
import jp.kjtr.cycleroutelogger.stats.RideAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrackListScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onSummary: () -> Unit,
    onImport: () -> Unit,
    onFilter: () -> Unit = {},
    onSummaryFiltered: () -> Unit = onSummary,
) {
    // 0.11.0〜 絞り込み・並べ替えた一覧（TrackBrowser。条件は保存して次回も残す）
    val browse by vm.app.trackBrowser.result.collectAsState()
    val filter by vm.app.trackBrowser.filter.collectAsState()
    val all = browse?.all.orEmpty()
    val list = browse?.items.orEmpty()
    var nameOpen by rememberSaveable { mutableStateOf(false) }
    // ファイルから記録を取り込む（0.10.0）
    val importState by vm.app.activityImport.state.collectAsState()
    val pickImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            vm.app.activityImport.start(uris)
            onImport()
        }
    }
    var action by remember { mutableStateOf<Pair<Long, TrackAction>?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tracks_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                actions = {
                    if (all.isNotEmpty()) {
                        IconButton(onClick = { nameOpen = !nameOpen }) { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.filter_name_search)) }
                        TextButton(onClick = onSummary) { Text(stringResource(R.string.summary_title)) }
                    }
                    IconButton(onClick = { pickImport.launch(ACTIVITY_IMPORT_MIME) }) {
                        Icon(Icons.Filled.UploadFile, contentDescription = stringResource(R.string.act_import_menu))
                    }
                },
            )
        },
    ) { pad ->
        if (browse != null && all.isEmpty() && importState is jp.kjtr.cycleroutelogger.importer.ActivityImporter.State.Idle) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.tracks_empty))
                androidx.compose.material3.OutlinedButton(onClick = { pickImport.launch(ACTIVITY_IMPORT_MIME) }) { Text(stringResource(R.string.act_import_menu)) }
            }
        }
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (all.isNotEmpty()) {
                TrackFilterBar(vm, browse, onOpenFilter = onFilter, modifier = Modifier.padding(top = 4.dp))
                if (nameOpen || filter.name.isNotBlank()) {
                    TrackNameSearchField(vm, Modifier.padding(bottom = 4.dp), onClose = { nameOpen = false })
                }
            }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 取り込みの途中（読み込み中・確認待ち・保存中）なら帯から画面に戻れる
            val banner = when (val st = importState) {
                is jp.kjtr.cycleroutelogger.importer.ActivityImporter.State.Reading,
                is jp.kjtr.cycleroutelogger.importer.ActivityImporter.State.Planning -> R.string.act_import_banner_reading to 0
                is jp.kjtr.cycleroutelogger.importer.ActivityImporter.State.Review -> R.string.act_import_banner_review to st.analysis.days.size
                is jp.kjtr.cycleroutelogger.importer.ActivityImporter.State.Importing -> R.string.act_import_banner_saving to 0
                else -> null
            }
            if (banner != null) {
                item(key = "import") {
                    Card(
                        Modifier.fillMaxWidth().clickable(onClick = onImport),
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(banner.first, banner.second), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.UploadFile, contentDescription = null)
                        }
                    }
                }
            }
            if (list.isNotEmpty()) {
                // 全記録のまとめ（0.7.0。drive_logger と同じ入口）。絞り込んでいれば、その記録のまとめ（0.11.0）
                item(key = "summary") {
                    Card(Modifier.fillMaxWidth().clickable(onClick = if (filter.isActive) onSummaryFiltered else onSummary)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(if (filter.isActive) R.string.filter_summary else R.string.summary_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    stringResource(
                                        if (filter.isActive) R.string.filter_summary_card else R.string.summary_card,
                                        list.size, Format.km(list.sumOf { it.distanceM }), Format.duration(list.sumOf { it.movingMs }),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Icon(Icons.Filled.ZoomOutMap, contentDescription = null)
                        }
                    }
                }
            } else if (all.isNotEmpty()) {
                item(key = "none") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.filter_none))
                        TextButton(onClick = { vm.app.trackBrowser.clear() }) { Text(stringResource(R.string.filter_clear_all)) }
                    }
                }
            }
            items(list, key = { it.id }) { t ->
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(t.id) }, onLongClick = { menu = true })) {
                    Column(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.track_save_route)) }, onClick = { menu = false; action = t.id to TrackAction.ROUTE })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.export_gpx)) }, onClick = { menu = false; action = t.id to TrackAction.GPX })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { menu = false; action = t.id to TrackAction.SHARE })
                                }
                            }
                        }
                        Text(Format.dateTime(t.startedAt) + "  " + modeText(t), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.padding(top = 4.dp)) {
                            StatCell(stringResource(R.string.stat_distance), Format.kmNumber(t.distanceM), Modifier.weight(1f), unit = "km")
                            StatCell(stringResource(R.string.stat_moving), Format.duration(t.movingMs), Modifier.weight(1f))
                            StatCell(stringResource(R.string.stat_avg), Format.speed(avg(t)), Modifier.weight(1f), unit = "km/h")
                        }
                    }
                }
            }
        }
        }
    }
    TrackActionHost(vm, action?.first, action?.second, onDone = { action = null })
}

@Composable
private fun modeText(t: TrackEntity): String = when {
    // ファイルから取り込んだ記録（0.10.0〜）は元の形式を出す
    t.imported -> stringResource(R.string.mode_imported, importFormatsLabel(t.importFormat))
    t.mode == TrackEntity.MODE_ROUTE -> stringResource(R.string.mode_route_with, t.planName ?: "")
    else -> stringResource(R.string.mode_free)
}

private fun avg(t: TrackEntity) = if (t.movingMs > 0) t.distanceM / (t.movingMs / 1000.0) else 0.0

/** 生の GPS の点と精度の円を出すズーム（これより引くと線だけ）・点の半径 [dp]・一度に描く点の上限（ドライブロガーと同じ）。 */
private const val RAW_DOTS_MIN_ZOOM = 16f
private const val RAW_DOT_DP = 3.5f
private const val MAX_RAW_DOTS = 400

/** 速度の区分（km/h）と色。 */
private val SPEED_BANDS = listOf(10.0 to Color(0xFF1E88E5), 15.0 to Color(0xFF00ACC1), 20.0 to Color(0xFF43A047), 25.0 to Color(0xFFFDD835), Double.MAX_VALUE to Color(0xFFE53935))

private fun bandOf(kmh: Double): Int = SPEED_BANDS.indexOfFirst { kmh < it.first }.coerceAtLeast(0)

/** 速度で色分けした線（同じ区分が続く間を 1 本にまとめる）。 */
private data class ColoredRun(val points: List<LatLng>, val color: Color)

private fun coloredRuns(points: List<TrackPointEntity>): List<ColoredRun> {
    val out = ArrayList<ColoredRun>()
    for (seg in TrackRepository.segmentsOf(points)) {
        if (seg.size < 2) continue
        val pts = Simplify.stride(seg, 8000)
        var cur = ArrayList<LatLng>()
        var band = -1
        for (i in pts.indices) {
            val p = pts[i]
            val kmh = (p.speed?.toDouble() ?: if (i > 0) {
                val q = pts[i - 1]
                val dt = (p.time - q.time) / 1000.0
                if (dt > 0) Geo.distanceM(q.lat, q.lon, p.lat, p.lon) / dt else 0.0
            } else 0.0) * 3.6
            val b = bandOf(kmh)
            val ll = LatLng(p.lat, p.lon)
            if (band == -1) band = b
            if (b != band && cur.size >= 2) {
                out.add(ColoredRun(cur, SPEED_BANDS[band].second))
                cur = arrayListOf(cur.last())
                band = b
            }
            cur.add(ll)
        }
        if (cur.size >= 2) out.add(ColoredRun(cur, SPEED_BANDS[band.coerceAtLeast(0)].second))
    }
    return out
}


/** 速度の凡例（地図の左上に重ねる。地図が狭いときは折り返す）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SpeedLegend(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.FlowRow(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(stringResource(R.string.speed_legend), style = MaterialTheme.typography.labelSmall)
        listOf("<10", "10-15", "15-20", "20-25", "25+").forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(start = 5.dp).size(10.dp).background(SPEED_BANDS[i].second))
                Text(" $label", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 地図と情報の境目のつまみ（ルートプラン画面と同じ形）。[totalPx] は地図 + 情報の長さ。 */
@Composable
private fun RatioHandle(vertical: Boolean, totalPx: Float, ratio: Float, onRatio: (Float) -> Unit, onStopped: () -> Unit) {
    val dragState = rememberDraggableState { delta ->
        if (totalPx > 0f) onRatio((ratio + delta / totalPx).coerceIn(SettingsStore.PLAN_MAP_RATIO_MIN, SettingsStore.PLAN_MAP_RATIO_MAX))
    }
    val desc = stringResource(R.string.map_resize_handle)
    Box(
        (if (vertical) Modifier.fillMaxWidth().height(22.dp) else Modifier.fillMaxHeight().width(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .draggable(
                state = dragState,
                orientation = if (vertical) Orientation.Vertical else Orientation.Horizontal,
                onDragStopped = { onStopped() },
            )
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            (if (vertical) Modifier.width(40.dp).height(5.dp) else Modifier.width(5.dp).height(40.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(3.dp)),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    vm: MainViewModel,
    trackId: Long,
    justFinished: Boolean = false,
    onCard: () -> Unit = {},
    onBack: () -> Unit,
    onOpenPlan: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val track by remember(trackId) { vm.tracks.track(trackId) }.collectAsState(null)
    val events by remember(trackId) { vm.tracks.events(trackId) }.collectAsState(emptyList())
    val photos by remember(trackId) { vm.photos.forTrack(trackId) }.collectAsState(emptyList())
    var viewingPhoto by remember { mutableStateOf<jp.kjtr.cycleroutelogger.data.PhotoEntity?>(null) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) vm.importPhotos(trackId, uris)
    }
    val settings by vm.settings.collectAsState()
    // 記録した点（測位そのまま。比較表示に使う）と、表示する点（0.9.0〜 設定「軌跡をなめらかにする」が ON ならなめらかにした点）
    val points by produceState(emptyList<TrackPointEntity>(), trackId) { value = vm.tracks.points(trackId) }
    val smoothCfg = if (settings.trackSmoothing) SmoothConfig.BICYCLE else null
    val shown by produceState(emptyList<TrackPointEntity>(), points, smoothCfg) {
        value = withContext(Dispatchers.Default) { TrackRepository.smoothed(points, smoothCfg) }
    }
    val runs by produceState(emptyList<ColoredRun>(), shown) { value = withContext(Dispatchers.Default) { coloredRuns(shown) } }
    val profile by produceState(emptyList<Pair<Double, Double>>(), shown) {
        value = withContext(Dispatchers.Default) {
            val out = ArrayList<Pair<Double, Double>>()
            var d = 0.0
            for (s in TrackRepository.segmentsOf(shown)) for (i in s.indices) {
                if (i > 0) d += Geo.distanceM(s[i - 1].lat, s[i - 1].lon, s[i].lat, s[i].lon)
                s[i].ele?.let { out.add(d to it) }
            }
            Simplify.stride(out, 600)
        }
    }
    // 区間の表・速度の分布（保存した点を流し直す。記録の統計と同じ扱い）
    val autoPause = track?.autoPause
    val analysis by produceState<RideAnalysis?>(null, points, autoPause, smoothCfg) {
        if (autoPause == null || points.isEmpty()) return@produceState
        value = withContext(Dispatchers.Default) { TrackRepository.analyze(points, autoPause, smoothCfg) }
    }
    // 生の GPS の点（0.9.0〜 比較表示）: 記録した点の細い線と、寄ったとき（ズーム 16 以上）に見えている範囲の点（最大 400）と精度の円
    var showRaw by rememberSaveable { mutableStateOf(false) }
    val rawLines = remember(points) {
        TrackRepository.segmentsOf(points).filter { it.size >= 2 }.map { s -> Simplify.stride(s, 8000).map { LatLng(it.lat, it.lon) } }
    }
    var rawDots by remember { mutableStateOf(emptyList<TrackPointEntity>()) }
    var dotRadiusM by remember { mutableStateOf(2.0) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // この記録で初めて走ったマス（0.8.0〜。全記録のまとめの材料から。終わった記録だけ）
    var showNewCells by rememberSaveable { mutableStateOf(false) }
    // (まとめた矩形, マスの数)。矩形は隣り合うマスをまとめるので、数はマスで数える
    val newCellsInfo by produceState(emptyList<jp.kjtr.cycleroutelogger.stats.CellRect>() to 0, track?.id, track?.pointCount, track?.status) {
        val t = track ?: return@produceState
        if (t.status != TrackEntity.STATUS_FINISHED) return@produceState
        val all = vm.app.summaries.digests(vm.tracks.allFinished())
        val mine = all.firstOrNull { it.facts.id == t.id }?.facts?.cells ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val first = jp.kjtr.cycleroutelogger.stats.CellShapes.firstSeen(all.map { it.facts })
            val fresh = jp.kjtr.cycleroutelogger.stats.CellShapes.newInRide(mine, first, t.startedAt)
            jp.kjtr.cycleroutelogger.stats.CellShapes.merge(fresh) to fresh.size
        }
    }
    val newCells = newCellsInfo.first
    val newCellColor = colorResource(R.color.cell_new)

    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<TrackAction?>(null) }
    var showFinished by rememberSaveable { mutableStateOf(justFinished) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var mapLoaded by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }
    var mapSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // つまみで動かしている間の割合（離したら設定に保存）
    var ratio by remember { mutableFloatStateOf(settings.trackMapRatio) }
    LaunchedEffect(settings.trackMapRatio) { ratio = settings.trackMapRatio }
    val cameraState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(35.681, 139.767), 5f) }
    val padPx = with(density) { 32.dp.roundToPx() }

    fun bounds() = GeoBounds.of(shown.ifEmpty { points }.map { LatLon(it.lat, it.lon) })

    LaunchedEffect(shown, mapLoaded, mapSize) {
        if (fitted || !mapLoaded || shown.isEmpty() || mapSize.width == 0) return@LaunchedEffect
        bounds()?.let {
            fitted = true
            cameraState.fitTo(it, padPx, mapSize.width, mapSize.height)
        }
    }

    // 地図が止まったら、見えている範囲の生の点を選び直す
    LaunchedEffect(showRaw, points, cameraState.isMoving, mapLoaded) {
        if (!showRaw || !mapLoaded || cameraState.isMoving) return@LaunchedEffect
        val zoom = cameraState.position.zoom
        val b = cameraState.projection?.visibleRegion?.latLngBounds
        if (zoom < RAW_DOTS_MIN_ZOOM || b == null) {
            rawDots = emptyList()
            return@LaunchedEffect
        }
        val lat = cameraState.position.target.latitude
        // 1 dp の長さ [m]（Web メルカトル。Google マップのズームは 256 dp のタイルで数える）
        val mPerDp = 156_543.03392 * kotlin.math.cos(Math.toRadians(lat)) / Math.pow(2.0, zoom.toDouble())
        dotRadiusM = mPerDp * RAW_DOT_DP
        rawDots = withContext(Dispatchers.Default) { Simplify.stride(points.filter { b.contains(LatLng(it.lat, it.lon)) }, MAX_RAW_DOTS) }
    }

    // ---- 部品 ----

    /** 地図（速度で色分けした軌跡・写真のマーカー・凡例・右上のボタン）。[topInset] は全画面のときにステータスバーを避ける分。 */
    @Composable
    fun MapPane(modifier: Modifier, topInset: Dp, bottomPad: Dp) {
        Box(modifier.onSizeChanged { mapSize = it }) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraState,
                properties = MapProperties(mapType = settings.mapKind.toMapType()),
                uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false, tiltGesturesEnabled = false),
                contentPadding = PaddingValues(top = topInset, bottom = bottomPad),
            ) {
                OnMapReady { mapLoaded = true }
                // この記録で初めて走ったマス（切り替え）
                if (showNewCells) CellPolygons(newCells, newCellColor, zIndex = 0.5f)
                runs.forEach { r ->
                    Polyline(points = r.points, color = r.color, width = with(density) { 5.dp.toPx() }, jointType = JointType.ROUND, zIndex = 2f)
                }
                // 生の GPS（0.9.0〜 比較表示）: 細い線＋寄ったときは点と精度の円
                if (showRaw) {
                    val rawColor = colorResource(R.color.raw_gps)
                    rawLines.forEach { l ->
                        Polyline(points = l, color = rawColor.copy(alpha = 0.75f), width = with(density) { 1.5.dp.toPx() }, zIndex = 2.5f)
                    }
                    rawDots.forEach { p ->
                        val c = LatLng(p.lat, p.lon)
                        p.accuracy?.takeIf { it > 0f }?.let { acc ->
                            Circle(
                                center = c, radius = acc.toDouble(), strokeColor = rawColor.copy(alpha = 0.45f), strokeWidth = with(density) { 1.dp.toPx() },
                                fillColor = rawColor.copy(alpha = 0.05f), zIndex = 2.4f,
                            )
                        }
                        Circle(center = c, radius = dotRadiusM, fillColor = rawColor, strokeColor = Color.White, strokeWidth = with(density) { 1.dp.toPx() }, zIndex = 2.6f)
                    }
                }
                PhotoMarkers(photos, onClick = { viewingPhoto = it })
            }
            // 右のボタン列（幅 56dp）にかからないように
            SpeedLegend(Modifier.align(Alignment.TopStart).padding(top = topInset + 8.dp, start = 8.dp, end = 64.dp))
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = topInset + 40.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallFloatingActionButton(onClick = { fullscreen = !fullscreen }) {
                    Icon(
                        if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = stringResource(if (fullscreen) R.string.map_fullscreen_exit else R.string.map_fullscreen),
                    )
                }
                SmallFloatingActionButton(onClick = {
                    val b = bounds() ?: return@SmallFloatingActionButton
                    if (mapLoaded) scope.launch { cameraState.fitTo(b, padPx, mapSize.width, mapSize.height) }
                }) { Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.fit_track)) }
            }
            // ［生の GPS］［マス］は左（凡例の下）に置く。地図を小さくしたとき右の列だとズームの ± にかかるため
            Column(
                Modifier.align(Alignment.TopStart).padding(top = topInset + 48.dp, start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (rawLines.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = { showRaw = !showRaw },
                        containerColor = if (showRaw) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (showRaw) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(
                            Icons.Filled.ScatterPlot,
                            contentDescription = stringResource(if (track?.imported == true) R.string.track_raw_toggle_imported else R.string.track_raw_toggle),
                        )
                    }
                }
                if (newCells.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = { showNewCells = !showNewCells },
                        containerColor = if (showNewCells) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (showNewCells) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                    ) { Icon(Icons.Filled.GridOn, contentDescription = stringResource(R.string.track_new_cells_toggle)) }
                }
            }
            if (showRaw && rawLines.isNotEmpty()) {
                val zoomedIn = cameraState.position.zoom >= RAW_DOTS_MIN_ZOOM
                Row(
                    // 初めて走ったマスの表示と重ならないよう、その上（end = 112dp は右下のズームの −/+ の分）
                    Modifier.align(Alignment.BottomStart)
                        .padding(start = 8.dp, end = 112.dp, bottom = bottomPad + if (showNewCells && newCells.isNotEmpty()) 52.dp else 28.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(9.dp).background(colorResource(R.color.raw_gps), androidx.compose.foundation.shape.CircleShape))
                    Text(
                        " " + stringResource(
                            when {
                                // 取り込んだ記録は生の GPS ではないので「取り込んだ元の点」（精度の円は無い）
                                track?.imported == true -> if (zoomedIn) R.string.track_raw_legend_imported else R.string.track_raw_legend_imported_zoom
                                zoomedIn -> R.string.track_raw_legend
                                else -> R.string.track_raw_legend_zoom
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (showNewCells && newCells.isNotEmpty()) {
                Text(
                    stringResource(R.string.track_new_cells, newCellsInfo.second), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = bottomPad + 28.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }

    /** 概要（ボタン・写真・統計・出来事・標高）。 */
    @Composable
    fun Overview(t: TrackEntity) {
        TrackActionButtons(onAction = { action = it }, modifier = Modifier.padding(bottom = 6.dp))
        // 写真と SNS 用の画像
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = {
                pickPhotos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(stringResource(R.string.photo_add_from_library)) }
            androidx.compose.material3.Button(onClick = onCard) { Text(stringResource(R.string.card_make)) }
        }
        if (photos.isNotEmpty()) {
            Text(stringResource(R.string.photos_title, photos.size), style = MaterialTheme.typography.titleSmall)
            PhotoStrip(photos, onClick = { viewingPhoto = it }, modifier = Modifier.padding(vertical = 6.dp))
        }
        Text(Format.dateTime(t.startedAt) + (t.endedAt?.let { " – " + Format.time(it) } ?: ""), style = MaterialTheme.typography.bodyMedium)
        Text(modeText(t), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(top = 8.dp)) {
            StatCell(stringResource(R.string.stat_distance), Format.kmNumber(t.distanceM), Modifier.weight(1f), unit = "km")
            StatCell(stringResource(R.string.stat_moving), Format.duration(t.movingMs), Modifier.weight(1f))
            StatCell(stringResource(R.string.stat_elapsed), Format.duration((t.endedAt ?: t.startedAt) - t.startedAt), Modifier.weight(1f))
        }
        Row {
            StatCell(stringResource(R.string.stat_avg), Format.speed(avg(t)), Modifier.weight(1f), unit = "km/h")
            StatCell(stringResource(R.string.stat_max), Format.speed(t.maxSpeedMps), Modifier.weight(1f), unit = "km/h")
            StatCell(stringResource(R.string.stat_ascent), Format.meters(t.ascentM), Modifier.weight(1f))
        }
        Text(stringResource(R.string.point_count, t.pointCount), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (events.isNotEmpty()) {
            Text(stringResource(R.string.events_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            events.forEach { e ->
                val label = stringResource(
                    when (e.type) {
                        jp.kjtr.cycleroutelogger.data.TrackEventEntity.TYPE_LOCATION_OFF -> R.string.event_location_off
                        jp.kjtr.cycleroutelogger.data.TrackEventEntity.TYPE_NO_FIX -> R.string.event_no_fix
                        jp.kjtr.cycleroutelogger.data.TrackEventEntity.TYPE_RESTARTED -> R.string.event_restarted
                        else -> R.string.event_interrupted
                    },
                )
                val span = Format.time(e.startedAt) + " – " + (e.endedAt?.let { Format.time(it) } ?: "")
                Text("$span  $label", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(R.string.elevation_graph), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        ElevationChart(profile, colorResource(R.color.track_line), Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }

    /** タブ（概要 / 区間 / 速度の分布）と中身（縦にスクロール）。 */
    @Composable
    fun InfoPane(modifier: Modifier) {
        val t = track
        Column(modifier) {
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.track_tab_overview), maxLines = 1) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.track_tab_splits), maxLines = 1) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.track_tab_speed), maxLines = 1) })
            }
            key(tab) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (t != null) {
                        when (tab) {
                            0 -> Overview(t)
                            1 -> SplitsSection(analysis, Modifier.fillMaxWidth())
                            else -> SpeedHistogramSection(analysis, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    // ---- 画面 ----

    BackHandler(enabled = fullscreen) { fullscreen = false }

    if (fullscreen) {
        val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
        val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // つまみ・題名・タブまで見せる
        val peek = 124.dp + navBottom
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sheetMax = maxHeight * 0.7f
            BottomSheetScaffold(
                scaffoldState = scaffoldState,
                sheetPeekHeight = peek,
                sheetContent = {
                    Column(Modifier.fillMaxWidth().height(sheetMax).padding(bottom = navBottom)) {
                        val t = track
                        Text(
                            if (t == null) "" else stringResource(R.string.track_sheet_title, t.name, Format.km(t.distanceM), Format.duration(t.movingMs)),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                        )
                        InfoPane(Modifier.fillMaxWidth().weight(1f))
                    }
                },
            ) { _ ->
                MapPane(
                    Modifier.fillMaxSize(),
                    topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding(),
                    bottomPad = peek,
                )
            }
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(track?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                    actions = {
                        IconButton(onClick = { action = TrackAction.SHARE }) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share)) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.export_gpx)) }, onClick = { menu = false; action = TrackAction.GPX })
                                DropdownMenuItem(text = { Text(stringResource(R.string.track_save_route)) }, onClick = { menu = false; action = TrackAction.ROUTE })
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; deleting = true })
                            }
                        }
                    },
                )
            },
        ) { pad ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
                val onStopped = { vm.updateSettings { it.copy(trackMapRatio = ratio) } }
                if (landscape) {
                    // 横長: 地図が左、情報が右
                    val full = maxWidth
                    val total = with(density) { full.toPx() }
                    Row(Modifier.fillMaxSize()) {
                        MapPane(Modifier.fillMaxHeight().width(full * ratio), topInset = 0.dp, bottomPad = 0.dp)
                        RatioHandle(vertical = false, totalPx = total, ratio = ratio, onRatio = { ratio = it }, onStopped = onStopped)
                        InfoPane(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    val full = maxHeight
                    val total = with(density) { full.toPx() }
                    Column(Modifier.fillMaxSize()) {
                        MapPane(Modifier.fillMaxWidth().height(full * ratio), topInset = 0.dp, bottomPad = 0.dp)
                        RatioHandle(vertical = true, totalPx = total, ratio = ratio, onRatio = { ratio = it }, onStopped = onStopped)
                        InfoPane(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }

    if (renaming) {
        TextInputDialog(stringResource(R.string.rename), track?.name ?: "", onDone = { if (it.isNotBlank()) vm.renameTrack(trackId, it) }, onDismiss = { renaming = false })
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.track_delete_confirm, track?.name ?: ""), text = null, confirm = stringResource(R.string.delete),
            onConfirm = { vm.deleteTrack(trackId); onBack() }, onDismiss = { deleting = false },
        )
    }
    TrackActionHost(vm, trackId, action, onDone = { action = null })
    viewingPhoto?.let { p -> PhotoViewer(vm, photos, p, onDismiss = { viewingPhoto = null }) }
    if (showFinished && track != null) {
        TrackFinishedDialog(track?.name ?: "", onAction = { showFinished = false; action = it }, onLater = { showFinished = false })
    }
}
