package jp.kjtr.cycleroutelogger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Card
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.TileOverlay
import jp.kjtr.cycleroutelogger.stats.MapTiles
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.key
import com.google.maps.android.compose.rememberCameraPositionState
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.MapKind
import jp.kjtr.cycleroutelogger.data.PhotoEntity
import jp.kjtr.cycleroutelogger.data.RideDigest
import jp.kjtr.cycleroutelogger.geo.Simplify
import jp.kjtr.cycleroutelogger.stats.CellRect
import jp.kjtr.cycleroutelogger.stats.CellShapes
import jp.kjtr.cycleroutelogger.stats.ModeFilter
import jp.kjtr.cycleroutelogger.stats.RideAnalysis
import jp.kjtr.cycleroutelogger.stats.RideSummaries
import jp.kjtr.cycleroutelogger.stats.RideSummary
import jp.kjtr.cycleroutelogger.stats.SummaryPeriod
import jp.kjtr.cycleroutelogger.stats.TimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/** 記録ごとの色（色分けのとき）。 */
private val RIDE_PALETTE = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA),
    Color(0xFF00ACC1), Color(0xFF6D4C41), Color(0xFFD81B60), Color(0xFF3949AB), Color(0xFF7CB342),
)

/** 地図に出す写真の数の上限（縮小版のマーカーが重くならないように、多ければ間引く）。 */
private const val MAX_PHOTO_MARKERS = 150
// 全走行の地図で軌跡をタップしたとみなす距離（dp。0.10.2〜 タイルなので点のデータから探す）
private const val TAP_TOLERANCE_DP = 14.0

/** 全記録のまとめ（統計・全走行の地図・グラフ）。drive_logger の「まとめ」と同じ作り（DESIGN.md §4.7）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SummaryScreen(vm: MainViewModel, onBack: () -> Unit, onOpenTrack: (Long) -> Unit, filtered: Boolean = false) {
    // 0.11.0〜 一覧で絞り込んだ記録のまとめ（［この条件でまとめを見る］）。期間・フリー/ルートは一覧の条件で選ぶので、この画面の切り替えは出さない
    var useFilter by rememberSaveable { mutableStateOf(filtered) }
    val allTracksOrNull by remember { vm.tracks.finished() }.collectAsState(null)
    val allTracks = allTracksOrNull.orEmpty()
    val browse by vm.app.trackBrowser.result.collectAsState()
    val browseFilter by vm.app.trackBrowser.filter.collectAsState()
    val filteredIds = remember(browse) { browse?.items?.mapTo(HashSet()) { it.id } }
    // まだ読めていなければ null（空の一覧で一度計算してキャッシュを捨てないように）
    val tracksOrNull = remember(allTracksOrNull, filteredIds, useFilter) {
        val a = allTracksOrNull
        when {
            a == null -> null
            useFilter -> filteredIds?.let { ids -> a.filter { it.id in ids } }
            else -> a
        }
    }
    val tracks = tracksOrNull.orEmpty()
    val photos by remember { vm.photos.linked() }.collectAsState(emptyList())
    val settings by vm.settings.collectAsState()
    val digests by produceState<List<RideDigest>?>(null, tracksOrNull) {
        val t = tracksOrNull ?: return@produceState
        // 絞り込んだときも、他の記録の材料はキャッシュに残す（すべての記録に戻したときに作り直さない）
        value = vm.app.summaries.digests(t, keep = allTracks.mapTo(HashSet()) { it.id })
    }
    var period by rememberSaveable { mutableStateOf(SummaryPeriod.ALL) }
    var mode by rememberSaveable { mutableStateOf(ModeFilter.ALL) }
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var heat by rememberSaveable { mutableStateOf(false) }
    var showPhotos by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var viewing by remember { mutableStateOf<Pair<List<PhotoEntity>, PhotoEntity>?>(null) }

    val photosByTrack = remember(photos) { photos.groupBy { it.trackId } }
    val range = remember(period, customFrom, customTo, useFilter) {
        if (useFilter) TimeRange(null, null)
        else RideSummaries.range(period, System.currentTimeMillis(), custom = TimeRange(customFrom, customTo?.let { it + 86_400_000L }))
    }
    val inRange = remember(digests, range, mode, photosByTrack, useFilter) {
        val ids = RideSummaries.filter(digests.orEmpty().map { it.facts }, range, if (useFilter) ModeFilter.ALL else mode).map { it.id }.toSet()
        digests.orEmpty().filter { it.facts.id in ids }
            .map { d -> d.copy(facts = d.facts.copy(photos = photosByTrack[d.facts.id]?.size ?: 0)) }
    }
    val summary = remember(inRange) { RideSummaries.summarize(inRange.map { it.facts }) }
    val mapPhotos = remember(inRange, photosByTrack) {
        Simplify.stride(inRange.flatMap { photosByTrack[it.facts.id].orEmpty() }.filter { it.lat != null && it.lon != null }, MAX_PHOTO_MARKERS)
    }

    // 開拓したマスの表示（0.8.0〜。drive_logger と同じ。軌跡だけ / マスだけ / 両方。期間・フリー/ルートの絞り込みに追従）。
    // 「初めて」は全記録（フリーもルートも）で見て、初めて走った記録の開始が期間に入るマス。全期間なら全部が初めてなので分けない。
    var cellMode by rememberSaveable { mutableStateOf(CellMode.TRACKS) }
    val firstSeen = remember(digests) { CellShapes.firstSeen(digests.orEmpty().map { it.facts }) }
    val allTime = range.fromMs == null && range.toMs == null
    val cellRects by produceState(emptyList<CellRect>() to emptyList<CellRect>(), inRange, range, firstSeen) {
        value = withContext(Dispatchers.Default) {
            val cells = inRange.flatMapTo(HashSet()) { it.facts.cells }
            val fresh = if (allTime) emptySet() else CellShapes.newIn(cells, firstSeen, range)
            CellShapes.merge(cells - fresh) to CellShapes.merge(fresh)
        }
    }
    val newCellCount = remember(inRange, range, firstSeen) {
        if (allTime) 0 else CellShapes.newIn(inRange.flatMapTo(HashSet()) { it.facts.cells }, firstSeen, range).size
    }

    BackHandler(enabled = fullscreen) { fullscreen = false }

    @Composable
    fun MapPane(modifier: Modifier) {
        AllRidesMap(
            inRange, heat = heat, mapKind = settings.mapKind, selected = selected, onSelect = { selected = it },
            photos = if (showPhotos) mapPhotos else emptyList(),
            onPhoto = { p -> viewing = photosByTrack[p.trackId].orEmpty() to p },
            fullscreen = fullscreen, onFullscreen = { fullscreen = !fullscreen },
            onToggleHeat = { heat = !heat },
            showPhotos = showPhotos, onTogglePhotos = { showPhotos = !showPhotos },
            onMapKind = { k -> vm.updateSettings { it.copy(mapKind = k) } },
            cellMode = cellMode, onCellMode = { cellMode = CellMode.entries[(cellMode.ordinal + 1) % CellMode.entries.size] },
            cells = cellRects.first, newCells = cellRects.second, newCellCount = newCellCount,
            modifier = modifier,
        )
        val sel = inRange.firstOrNull { it.facts.id == selected }?.facts
        if (sel != null) {
            Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sel.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            Format.dateTime(sel.startedAt) + "  " + Format.km(sel.distanceM) + "  " + Format.duration(sel.movingMs),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onOpenTrack(sel.id) }) { Text(stringResource(R.string.summary_open_track)) }
                }
            }
        }
    }

    if (fullscreen) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            MapPane(Modifier.fillMaxWidth().weight(1f))
        }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.summary_title)) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (useFilter) {
                    Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.summary_filtered_title, allTracks.size, tracks.size),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (browseFilter.isActive) {
                                Text(filterSummaryText(browseFilter), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(stringResource(R.string.summary_filtered_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { useFilter = false }) { Text(stringResource(R.string.summary_filtered_all)) }
                        }
                    }
                }
                if (!useFilter) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SummaryPeriod.entries.forEach { p ->
                        FilterChip(
                            selected = period == p,
                            onClick = { if (p == SummaryPeriod.CUSTOM) picking = true else period = p },
                            label = {
                                Text(
                                    if (p == SummaryPeriod.CUSTOM && period == p && customFrom != null) {
                                        Format.dayStamp(customFrom!!) + "〜" + (customTo?.let { Format.dayStamp(it) } ?: "")
                                    } else periodLabel(p),
                                )
                            },
                        )
                    }
                }
                if (!useFilter) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeFilter.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(modeLabel(m)) })
                    }
                }
                if (digests == null) {
                    Text(stringResource(R.string.analysis_loading), Modifier.padding(vertical = 16.dp))
                    return@Column
                }
                StatsGrid(summary, onOpenTrack, newCellCount, onCells = { cellMode = CellMode.BOTH })
                Text(stringResource(R.string.summary_map), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                // 右のボタン 6 個（0.8.0 で［マス］が増えた）がズームの ± にかからない高さ
                MapPane(Modifier.fillMaxWidth().height(420.dp))
                Text(stringResource(R.string.summary_map_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                ChartTitle(stringResource(R.string.summary_monthly))
                BarChart(
                    values = summary.monthlyM.map { it.second / 1000.0 },
                    labels = monthLabels(summary.monthlyM.map { it.first }),
                    unit = "km", modifier = Modifier.fillMaxWidth().height(170.dp),
                )
                // 少ないうちは「0.3 時間」ではなく分で
                val inHours = (summary.weekdayMs.maxOrNull() ?: 0L) >= 3_600_000L
                val timeDiv = if (inHours) 3_600_000.0 else 60_000.0
                val timeUnit = stringResource(if (inHours) R.string.summary_hours_unit else R.string.hist_axis_min)
                ChartTitle(stringResource(R.string.summary_weekday))
                BarChart(
                    values = summary.weekdayMs.map { it / timeDiv },
                    labels = listOf("日", "月", "火", "水", "木", "金", "土"),
                    unit = timeUnit, modifier = Modifier.fillMaxWidth().height(150.dp),
                )
                ChartTitle(stringResource(R.string.summary_hour))
                BarChart(
                    values = summary.hourMs.map { it / timeDiv },
                    labels = (0..23).map { if (it % 3 == 0) it.toString() else "" },
                    unit = timeUnit, modifier = Modifier.fillMaxWidth().height(150.dp),
                )
                Text(stringResource(R.string.summary_time_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChartTitle(stringResource(R.string.summary_speed))
                SpeedHistogramView(summary.histogram, Modifier.fillMaxWidth().padding(bottom = 24.dp))
            }
        }
    }

    if (picking) {
        val state = rememberDateRangePickerState(initialSelectedStartDateMillis = customFrom, initialSelectedEndDateMillis = customTo)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    val s = state.selectedStartDateMillis
                    if (s != null) {
                        // DatePicker は UTC の 0 時。端末の日付の 0 時に直す
                        val off = java.util.TimeZone.getDefault().getOffset(s)
                        customFrom = s - off
                        customTo = (state.selectedEndDateMillis ?: s) - off
                        period = SummaryPeriod.CUSTOM
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.height(500.dp))
        }
    }
    viewing?.let { (list, p) -> PhotoViewer(vm, list, p, onDismiss = { viewing = null }) }
}

/** 月のラベル（"26/10"。多いときは間引く）。 */
private fun monthLabels(keys: List<String>): List<String> {
    val every = when {
        keys.size <= 12 -> 1
        keys.size <= 24 -> 3
        else -> 6
    }
    return keys.mapIndexed { i, k -> if (i % every == 0 || i == keys.size - 1) k.substring(2).replace('-', '/') else "" }
}

@Composable
private fun periodLabel(p: SummaryPeriod) = stringResource(
    when (p) {
        SummaryPeriod.ALL -> R.string.period_all
        SummaryPeriod.THIS_YEAR -> R.string.period_year
        SummaryPeriod.THIS_MONTH -> R.string.period_month
        SummaryPeriod.LAST_30_DAYS -> R.string.period_30days
        SummaryPeriod.CUSTOM -> R.string.period_custom
    },
)

@Composable
private fun modeLabel(m: ModeFilter) = stringResource(
    when (m) {
        ModeFilter.ALL -> R.string.summary_mode_all
        ModeFilter.FREE -> R.string.summary_mode_free
        ModeFilter.ROUTE -> R.string.summary_mode_route
    },
)

@Composable
private fun ChartTitle(text: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 6.dp))
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun StatsGrid(s: RideSummary, onOpenTrack: (Long) -> Unit, newCells: Int = 0, onCells: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row {
                StatCell(stringResource(R.string.summary_distance), String.format(Locale.ROOT, "%.1f", s.distanceM / 1000.0), Modifier.weight(1f), unit = "km")
                StatCell(stringResource(R.string.summary_time), Format.duration(s.movingMs), Modifier.weight(1f))
                StatCell(stringResource(R.string.summary_rides), s.rideCount.toString(), Modifier.weight(1f), unit = stringResource(R.string.summary_rides_unit))
            }
            Row {
                StatCell(stringResource(R.string.stat_avg), Format.speed(s.avgSpeedMps), Modifier.weight(1f), unit = "km/h")
                StatCell(stringResource(R.string.summary_days), s.days.toString(), Modifier.weight(1f), unit = stringResource(R.string.summary_days_unit))
                // タップで地図をマスの表示に（期間の中で初めて開拓したマスがあれば "(+n)"）
                StatCell(
                    stringResource(R.string.summary_cells), s.cells.toString() + if (newCells > 0) " (+$newCells)" else "",
                    Modifier.weight(1f).clickable(onClick = onCells),
                )
            }
            Row {
                StatCell(stringResource(R.string.summary_ascent), s.ascentM.roundToInt().toString(), Modifier.weight(1f), unit = "m")
                StatCell(stringResource(R.string.summary_photos), s.photos.toString(), Modifier.weight(1f), unit = stringResource(R.string.summary_photos_unit))
                StatCell(
                    stringResource(R.string.summary_avg_distance),
                    if (s.rideCount > 0) String.format(Locale.ROOT, "%.1f", s.distanceM / 1000.0 / s.rideCount) else "0.0",
                    Modifier.weight(1f), unit = "km",
                )
            }
            s.maxSpeed?.let { (v, t) -> RecordLine(stringResource(R.string.summary_max_speed, Format.speed(v)), t.name) { onOpenTrack(t.id) } }
            s.longestDistance?.let { t -> RecordLine(stringResource(R.string.summary_longest_distance, Format.km(t.distanceM)), t.name) { onOpenTrack(t.id) } }
            s.longestTime?.let { t -> RecordLine(stringResource(R.string.summary_longest_time, Format.duration(t.endedAt - t.startedAt)), t.name) { onOpenTrack(t.id) } }
            Text(stringResource(R.string.summary_cells_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RecordLine(title: String, track: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(track, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onClick) { Text(stringResource(R.string.summary_open_track)) }
    }
}

/** 全記録の軌跡を重ねた地図。軌跡をタップでその記録を選ぶ。写真の位置も出せる。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllRidesMap(
    rides: List<RideDigest>, heat: Boolean, mapKind: MapKind, selected: Long?, onSelect: (Long?) -> Unit,
    photos: List<PhotoEntity>, onPhoto: (PhotoEntity) -> Unit,
    fullscreen: Boolean, onFullscreen: () -> Unit, onToggleHeat: () -> Unit,
    showPhotos: Boolean, onTogglePhotos: () -> Unit, onMapKind: (MapKind) -> Unit,
    cellMode: CellMode, onCellMode: () -> Unit, cells: List<CellRect>, newCells: List<CellRect>, newCellCount: Int,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(35.681, 139.767), 5f) }
    var ready by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var kindMenu by remember { mutableStateOf(false) }
    val bounds = remember(rides) { MapTiles.union(rides.map { it.lines.bounds }) }
    val padPx = with(density) { 24.dp.roundToPx() }
    LaunchedEffect(bounds, ready, size) {
        val b = bounds ?: return@LaunchedEffect
        if (ready && size.width > 0) camera.fitTo(b, padPx, size.width, size.height)
    }
    // ズーム 13 以上で細かい線
    val fine = camera.position.zoom >= 13f
    val heatColor = colorResource(R.color.track_line)
    val cellColor = colorResource(R.color.cell_explored)
    val newCellColor = colorResource(R.color.cell_new)
    // 0.10.2〜 軌跡とマスはタイルの画像に描く（RideTileProvider）。記録が 1000 本を超えると Polyline を足すだけで Maps のメモリが尽きて落ちたため。
    // 選んだ記録だけは Polyline で上に重ねる。タップは点のデータから近い記録を探す（MapTiles.nearest）
    val showTracks = cellMode != CellMode.CELLS
    val showCells = cellMode != CellMode.TRACKS
    val provider = remember(rides, heat, showTracks, showCells, cells, newCells, heatColor, cellColor, newCellColor) {
        val tr = if (showTracks) rides.map { it.lines } else emptyList()
        val colors = IntArray(tr.size) { i -> (if (heat) heatColor.copy(alpha = 0.28f) else RIDE_PALETTE[i % RIDE_PALETTE.size].copy(alpha = 0.85f)).toArgb() }
        RideTileProvider(
            tr, colors, widthDp = if (heat) 4.5f else 3f,
            cells = if (showCells) cells else emptyList(), cellFill = cellColor.toArgb(),
            newCells = if (showCells) newCells else emptyList(), newCellFill = newCellColor.toArgb(),
        )
    }
    val hitLines = remember(rides) { rides.map { MapTiles.HitLine(it.facts.id, it.lines.bounds, it.lines.coarse) } }
    val hitLinesFine = remember(rides) { rides.map { MapTiles.HitLine(it.facts.id, it.lines.bounds, it.lines.fine) } }
    val selLines = remember(rides, selected) { rides.firstOrNull { it.facts.id == selected }?.lines }
    Box(modifier.onSizeChanged { size = it }) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(mapType = mapKind.toMapType()),
            uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false, tiltGesturesEnabled = false),
            onMapClick = { ll ->
                onSelect(
                    if (!showTracks) null
                    else MapTiles.nearest(if (fine) hitLinesFine else hitLines, ll.latitude, ll.longitude, camera.position.zoom, TAP_TOLERANCE_DP),
                )
            },
        ) {
            OnMapReady { ready = true }
            key(provider) { TileOverlay(tileProvider = provider, fadeIn = false, zIndex = 1f) }
            if (showTracks && selLines != null) {
                val w = with(density) { 7.dp.toPx() }
                for (seg in if (fine) selLines.fine else selLines.coarse) {
                    if (seg.size < 4) continue
                    Polyline(
                        points = remember(seg) { MapTiles.decode(seg).map { it.toLatLng() } }, color = Color(0xFF212121), width = w, jointType = JointType.ROUND,
                        zIndex = 5f,
                    )
                }
            }
            if (photos.isNotEmpty()) PhotoMarkers(photos, onClick = onPhoto)
        }
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallFloatingActionButton(onClick = onFullscreen) {
                Icon(
                    if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = stringResource(if (fullscreen) R.string.map_fullscreen_exit else R.string.map_fullscreen),
                )
            }
            SmallFloatingActionButton(onClick = { val b = bounds ?: return@SmallFloatingActionButton; if (ready) scope.launch { camera.fitTo(b, padPx, size.width, size.height) } }) {
                Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.summary_fit))
            }
            SmallFloatingActionButton(onClick = onToggleHeat) { Icon(Icons.Filled.Palette, contentDescription = stringResource(R.string.summary_color_mode)) }
            SmallFloatingActionButton(
                onClick = onTogglePhotos,
                containerColor = if (showPhotos) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (showPhotos) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) { Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.summary_photos_toggle)) }
            SmallFloatingActionButton(
                onClick = onCellMode,
                containerColor = if (cellMode != CellMode.TRACKS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (cellMode != CellMode.TRACKS) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) { Icon(Icons.Filled.GridOn, contentDescription = stringResource(R.string.summary_cells_toggle)) }
            Box {
                SmallFloatingActionButton(onClick = { kindMenu = true }) { Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.map_type)) }
                DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                    MapKind.entries.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(stringResource(mapKindLabel(k)) + if (k == mapKind) " ✓" else "") },
                            onClick = { onMapKind(k); kindMenu = false },
                        )
                    }
                }
            }
        }
        // 左上の凡例（右のボタン列 56dp にかからないように）
        val panel = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp)
        Column(Modifier.align(Alignment.TopStart).padding(top = 8.dp, start = 8.dp, end = 64.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    when {
                        cellMode == CellMode.CELLS -> R.string.summary_cells_only
                        heat -> R.string.summary_color_heat
                        else -> R.string.summary_color_ride
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                modifier = panel,
            )
            if (cellMode != CellMode.TRACKS) {
                FlowRow(panel, verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(cellColor))
                        Text(" " + stringResource(R.string.summary_cells_legend), style = MaterialTheme.typography.labelSmall)
                    }
                    if (newCellCount > 0) {
                        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(newCellColor))
                            Text(" " + stringResource(R.string.summary_cells_new_legend, newCellCount), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** まとめの地図の開拓したマスの表示（drive_logger と同じ）。 */
enum class CellMode { TRACKS, CELLS, BOTH }

/** 簡単な棒グラフ（値・下のラベル・左上の単位）。 */
@Composable
private fun BarChart(values: List<Double>, labels: List<String>, unit: String, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val bar = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (values.isEmpty() || values.all { it <= 0 }) {
        Text(stringResource(R.string.summary_no_data), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val maxV = values.maxOrNull()!!.coerceAtLeast(1e-6)
    val step = RideAnalysis.niceStep(maxV)
    val top = kotlin.math.ceil(maxV / step - 1e-9) * step
    Canvas(modifier.padding(top = 4.dp)) {
        val left = 34.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val topY = 24.dp.toPx()
        val plotH = bottom - topY
        val barW = ((size.width - left) / values.size).coerceAtMost(56.dp.toPx())
        fun y(v: Double) = (bottom - v / top * plotH).toFloat()
        var v = 0.0
        while (v <= top + 1e-9) {
            val yy = y(v)
            drawLine(grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
            val lab = if (step >= 1) v.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", v)
            val r = tm.measure(lab, labelStyle)
            drawText(r, topLeft = Offset(left - r.size.width - 3.dp.toPx(), yy - r.size.height / 2f))
            v += step
        }
        drawText(tm.measure(unit, labelStyle), topLeft = Offset(0f, 0f))
        val gap = (barW * 0.15f).coerceAtMost(4.dp.toPx())
        values.forEachIndexed { i, value ->
            if (value > 0) {
                val yy = y(value)
                drawRect(bar, topLeft = Offset(left + i * barW + gap / 2, yy), size = Size((barW - gap).coerceAtLeast(1f), bottom - yy))
            }
            val l = labels.getOrNull(i).orEmpty()
            if (l.isNotEmpty()) {
                val r = tm.measure(l, labelStyle)
                drawText(r, topLeft = Offset(left + i * barW + barW / 2 - r.size.width / 2f, bottom + 2.dp.toPx()))
            }
        }
    }
}
