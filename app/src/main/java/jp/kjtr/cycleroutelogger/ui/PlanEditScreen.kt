package jp.kjtr.cycleroutelogger.ui

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.MapKind
import jp.kjtr.cycleroutelogger.data.RouteEntity
import jp.kjtr.cycleroutelogger.data.SettingsStore
import jp.kjtr.cycleroutelogger.data.WaypointEntity
import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.plan.OnRoute
import jp.kjtr.cycleroutelogger.plan.WaypointType
import jp.kjtr.cycleroutelogger.plan.WaypointsOnRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * プランの編集（ルートの並び・色・表示、経由地の追加・移動・編集、メモ、GPX の読み書き）。
 *
 * 地図と下の一覧の境目のつまみで地図の大きさを変えられる（割合は設定に保存。横長の画面では地図が左・一覧が右）。
 * ［全画面］では地図だけを出し、ルート・経由地の一覧は下から引き出すシートで見る（戻るで元に戻る）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditScreen(vm: MainViewModel, planId: Long, onBack: () -> Unit, onRide: () -> Unit, onDraw: (routeId: Long?) -> Unit = {}) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val plan by remember(planId) { vm.plans.plan(planId) }.collectAsState(null)
    val routes by remember(planId) { vm.plans.routes(planId) }.collectAsState(emptyList())
    val shape by remember(planId) { vm.plans.shape(planId) }.collectAsState(null)
    val waypoints by remember(planId) { vm.plans.waypoints(planId) }.collectAsState(emptyList())
    val sourceUrls by remember(planId) { vm.plans.sourceUrls(planId) }.collectAsState(emptyList())
    val rec by vm.session.state.collectAsStateWithLifecycle()
    val sorted by produceState(emptyList<OnRoute<WaypointEntity>>(), shape, waypoints) {
        value = withContext(Dispatchers.Default) {
            WaypointsOnRoute.sort(shape?.matcher()?.takeIf { !it.isEmpty }, waypoints, { LatLon(it.lat, it.lon) }, { it.createdAt })
        }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var topMenu by remember { mutableStateOf(false) }
    var renamingPlan by remember { mutableStateOf(false) }
    var deletingPlan by remember { mutableStateOf(false) }
    var choosingReload by remember { mutableStateOf(false) }
    var convertRoute by remember { mutableStateOf<RouteEntity?>(null) }
    var editing by remember { mutableStateOf<Pair<WaypointEntity, Boolean>?>(null) }
    var colorFor by remember { mutableStateOf<RouteEntity?>(null) }
    var renamingRoute by remember { mutableStateOf<RouteEntity?>(null) }
    var deletingRoute by remember { mutableStateOf<RouteEntity?>(null) }
    var expandedRoute by remember { mutableStateOf<Long?>(null) }
    var mapLoaded by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf(false) }
    var mapSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // つまみで動かしている間の割合（離したら設定に保存）
    var ratio by remember { mutableFloatStateOf(settings.planMapRatio) }
    LaunchedEffect(settings.planMapRatio) { ratio = settings.planMapRatio }

    val cameraState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(35.681, 139.767), 5f) }
    val padPx = with(density) { 40.dp.roundToPx() }

    fun planBounds() = GeoBounds.union(listOfNotNull(shape?.bounds, GeoBounds.of(waypoints.map { LatLon(it.lat, it.lon) })))

    fun fit(b: GeoBounds?) {
        if (b == null || !mapLoaded) return
        scope.launch { cameraState.fitTo(b, padPx, mapSize.width, mapSize.height) }
    }

    LaunchedEffect(shape, waypoints, mapLoaded, mapSize) {
        if (fitted || !mapLoaded || mapSize.width == 0) return@LaunchedEffect
        val b = planBounds()
        if (b != null) {
            fitted = true
            cameraState.fitTo(b, padPx, mapSize.width, mapSize.height)
        }
    }

    val openGpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importUris(uris, planId)
    }
    val exportGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) vm.exportPlan(planId, uri)
    }
    val scanQr = rememberQrScan(vm)

    fun newWaypointAt(p: LatLon, name: String = "", type: WaypointType = WaypointType.OTHER) {
        editing = WaypointEntity(planId = planId, name = name, type = type.code, lat = p.lat, lon = p.lon, createdAt = System.currentTimeMillis()) to true
    }

    // ---- 場所名の検索（0.6.0） ----
    val search = rememberPlaceSearchState()
    val lastLoc by vm.locationHub.last.collectAsStateWithLifecycle()
    val placeColor = colorResource(R.color.place_result)
    /** 一覧・カードの距離の起点（検索したときの現在地、無ければ地図の中心）。 */
    var placeOrigin by remember { mutableStateOf<PlaceOrigin?>(null) }
    var placeCardPx by remember { mutableIntStateOf(0) }

    fun runPlaceSearch() {
        val t = cameraState.position.target
        placeOrigin = lastLoc?.let { PlaceOrigin(LatLon(it.latitude, it.longitude), true) } ?: PlaceOrigin(LatLon(t.latitude, t.longitude), false)
        search.submit(scope, vm, cameraState.visibleBounds())
    }

    fun pickPlace(p: jp.kjtr.cycleroutelogger.geo.PlaceResult) {
        search.picked = p
        search.listShown = false
        scope.launch { cameraState.showPlace(p, padPx, mapSize.width, mapSize.height) }
    }

    /** 経由地の一覧で選んだ → 地図をそこへ。 */
    fun focusWaypoint(w: WaypointEntity) {
        scope.launch {
            val zoom = cameraState.position.zoom.coerceAtLeast(15f)
            runCatching { cameraState.move(CameraUpdateFactory.newLatLngZoom(LatLng(w.lat, w.lon), zoom)) }
        }
    }

    // ---- 部品 ----

    /** 地図と右上のボタン列。[topInset] は全画面のときにステータスバーを避ける分。 */
    @Composable
    fun MapPane(modifier: Modifier, topInset: Dp, bottomPad: Dp) {
        var mapMenu by remember { mutableStateOf(false) }
        val picked = search.picked
        val cardDp = if (picked != null) with(density) { placeCardPx.toDp() } + 8.dp else 0.dp
        BoxWithConstraints(modifier.onSizeChanged { mapSize = it }) {
            val paneHeight = maxHeight
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraState,
                properties = MapProperties(mapType = settings.mapKind.toMapType(), isMyLocationEnabled = vm.locationHub.hasPermission()),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, tiltGesturesEnabled = false, myLocationButtonEnabled = false),
                // 選んだ場所のカードの分も下を空ける（Google のロゴを隠さない）
                contentPadding = PaddingValues(top = topInset, bottom = bottomPad + cardDp),
                onMapLongClick = { newWaypointAt(LatLon(it.latitude, it.longitude)) },
            ) {
                OnMapReady { mapLoaded = true }
                picked?.let { PlaceResultMarker(it, placeColor) }
                PlanRoutes(shape, with(density) { 5.dp.toPx() })
                WaypointMarkers(
                    waypoints = waypoints,
                    hiddenTypes = settings.hiddenTypes,
                    highlightId = null,
                    draggable = true,
                    onClick = { editing = it to false },
                    onMoved = { w, p -> vm.updateWaypoint(w.copy(lat = p.lat, lon = p.lon)) },
                )
            }
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = topInset + 8.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallFloatingActionButton(onClick = { fullscreen = !fullscreen }) {
                    Icon(
                        if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = stringResource(if (fullscreen) R.string.map_fullscreen_exit else R.string.map_fullscreen),
                    )
                }
                Box {
                    SmallFloatingActionButton(onClick = { mapMenu = true }) { Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.map_type)) }
                    DropdownMenu(expanded = mapMenu, onDismissRequest = { mapMenu = false }) {
                        MapKind.entries.forEach { k ->
                            DropdownMenuItem(
                                text = { Text(stringResource(mapKindLabel(k)) + if (k == settings.mapKind) " ✓" else "") },
                                onClick = { vm.updateSettings { it.copy(mapKind = k) }; mapMenu = false },
                            )
                        }
                    }
                }
                SmallFloatingActionButton(onClick = { fit(planBounds()) }) { Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.fit_plan)) }
                SmallFloatingActionButton(onClick = {
                    scope.launch {
                        if (!cameraState.moveToCurrentLocation(vm.locationHub)) vm.toast(vm.app, vm.app.getString(R.string.my_location_unavailable))
                    }
                }) { Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.go_my_location)) }
                SmallFloatingActionButton(onClick = { onDraw(null) }) { Icon(Icons.Filled.Gesture, contentDescription = stringResource(R.string.draw_route)) }
                SmallFloatingActionButton(onClick = {
                    val t = cameraState.position.target
                    newWaypointAt(LatLon(t.latitude, t.longitude))
                }) { Icon(Icons.Filled.AddLocationAlt, contentDescription = stringResource(R.string.wp_add_center)) }
            }
            // 地図の中心（［＋経由地］の位置）
            Box(Modifier.align(Alignment.Center).size(10.dp).background(Color(0x99000000), CircleShape))
            if (picked == null) {
                Text(
                    stringResource(R.string.wp_add_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad + 28.dp)
                        .background(Color(0x88000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // 左上: 場所名の検索（右のボタン列は避ける）と結果の一覧
            Column(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(top = topInset + 8.dp, start = 8.dp, end = 56.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlaceSearchBar(search, onSubmit = { runPlaceSearch() }, modifier = Modifier.fillMaxWidth())
                PlaceResultsPanel(
                    vm, search, placeOrigin,
                    maxHeight = paneHeight - topInset - bottomPad - 72.dp,
                    onPick = { pickPlace(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 下: 選んだ場所 →［経由地として追加］（種類を選ぶいつものダイアログを、名前を入れて開く）
            if (picked != null) {
                PickedPlaceCard(
                    vm, picked, placeOrigin,
                    onClose = { search.picked = null },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = bottomPad + 8.dp)
                        .onSizeChanged { placeCardPx = it.height },
                ) {
                    Button(onClick = {
                        newWaypointAt(picked.latLon, picked.name, jp.kjtr.cycleroutelogger.geo.NominatimSearch.guessType(picked))
                        search.picked = null
                    }) { Text(stringResource(R.string.place_add_waypoint)) }
                    TextButton(onClick = { search.listShown = true; search.picked = null }) { Text(stringResource(R.string.place_back_to_results)) }
                }
            }
        }
    }

    @Composable
    fun Totals() {
        var filterMenu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.plan_totals,
                    Format.km(routes.sumOf { it.distanceM }), Format.meters(routes.sumOf { it.ascentM }), routes.size, waypoints.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            // 経由地の種類のフィルタ（地図と一覧から隠す）。一覧の場所を広く取るためメニューにまとめる
            Box {
                IconButton(onClick = { filterMenu = true }) {
                    Icon(
                        Icons.Filled.FilterList,
                        contentDescription = stringResource(R.string.wp_filter),
                        tint = if (settings.hiddenTypes.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    WaypointType.entries.forEach { t ->
                        val shown = t.code !in settings.hiddenTypes
                        DropdownMenuItem(
                            text = { Text(t.label()) },
                            leadingIcon = { WaypointBadge(t, size = 22.dp) },
                            trailingIcon = { androidx.compose.material3.Checkbox(checked = shown, onCheckedChange = null) },
                            onClick = { vm.updateSettings { s -> s.copy(hiddenTypes = if (shown) s.hiddenTypes + t.code else s.hiddenTypes - t.code) } },
                        )
                    }
                }
            }
        }
    }
    /** タブ（ルート / 経由地 / メモ）と中身。[onPickWaypoint] は経由地をタップしたとき（地図を動かした後）。 */
    @Composable
    fun Tabs(modifier: Modifier, withMemo: Boolean, onPickWaypoint: () -> Unit) {
        Column(modifier) {
            val t = if (!withMemo && tab == 2) 0 else tab
            TabRow(selectedTabIndex = t) {
                Tab(selected = t == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_routes, routes.size)) })
                Tab(selected = t == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_waypoints, waypoints.size)) })
                if (withMemo) Tab(selected = t == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_memo)) })
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (t) {
                    0 -> LazyColumn(Modifier.fillMaxSize()) {
                        if (routes.isEmpty()) item { Text(stringResource(R.string.routes_empty), Modifier.padding(16.dp)) }
                        items(routes, key = { it.id }) { r ->
                            RouteRow(
                                r = r,
                                index = routes.indexOf(r),
                                count = routes.size,
                                expanded = expandedRoute == r.id,
                                profile = shape?.routes?.firstOrNull { it.first.id == r.id }?.second?.profile.orEmpty(),
                                onToggleExpand = { expandedRoute = if (expandedRoute == r.id) null else r.id },
                                onColor = { colorFor = r },
                                onVisible = { vm.updateRoute(r.copy(visible = !r.visible)) },
                                onMove = { vm.moveRoute(r, it) },
                                onRename = { renamingRoute = r },
                                onFit = { fit(shape?.routes?.firstOrNull { it.first.id == r.id }?.second?.bounds) },
                                onEditShape = { if (r.editShape != null) onDraw(r.id) else convertRoute = r },
                                onDelete = { deletingRoute = r },
                            )
                            HorizontalDivider()
                        }
                    }
                    1 -> LazyColumn(Modifier.fillMaxSize()) {
                        if (waypoints.isEmpty()) item { Text(stringResource(R.string.wp_none_hint), Modifier.padding(16.dp)) }
                        items(sorted.filter { it.item.type !in settings.hiddenTypes }, key = { it.item.id }) { o ->
                            val type = WaypointType.fromCode(o.item.type)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { focusWaypoint(o.item); onPickWaypoint() }
                                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                WaypointBadge(type, size = 30.dp)
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text(o.item.name.ifBlank { type.label() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val along = when {
                                        o.progressM == null -> type.label()
                                        (o.offsetM ?: 0.0) > WaypointsOnRoute.MAX_OFFSET_FOR_NEXT_M ->
                                            stringResource(R.string.wp_off_route, type.label(), Format.distance(o.offsetM ?: 0.0))
                                        else -> stringResource(R.string.wp_line, type.label(), Format.km(o.progressM))
                                    }
                                    Text(along, style = MaterialTheme.typography.bodySmall)
                                }
                                o.item.plannedAt?.let { Text(Format.time(it), style = MaterialTheme.typography.labelLarge) }
                                IconButton(onClick = { editing = o.item to false }) {
                                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    else -> {
                        var memo by remember(plan?.id) { mutableStateOf(plan?.memo ?: "") }
                        LaunchedEffect(memo) {
                            delay(600)
                            if (plan != null && memo != plan?.memo) vm.updatePlan(planId, memo = memo)
                        }
                        OutlinedTextField(
                            value = memo, onValueChange = { memo = it },
                            label = { Text(stringResource(R.string.tab_memo)) },
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun RideButton(modifier: Modifier) {
        Button(
            onClick = { vm.chooseRoute(planId); onRide() },
            enabled = rec == null || rec?.planId == planId,
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(48.dp),
        ) { Text(stringResource(R.string.ride_this_plan)) }
    }

    /** 地図と一覧の境目のつまみ（ドラッグで割合を変える）。[totalPx] は地図 + 一覧の長さ。 */
    @Composable
    fun Handle(vertical: Boolean, totalPx: Float) {
        val dragState = rememberDraggableState { delta ->
            if (totalPx > 0f) ratio = (ratio + delta / totalPx).coerceIn(SettingsStore.PLAN_MAP_RATIO_MIN, SettingsStore.PLAN_MAP_RATIO_MAX)
        }
        val desc = stringResource(R.string.map_resize_handle)
        Box(
            (if (vertical) Modifier.fillMaxWidth().height(22.dp) else Modifier.fillMaxHeight().width(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .draggable(
                    state = dragState,
                    orientation = if (vertical) Orientation.Vertical else Orientation.Horizontal,
                    onDragStopped = { vm.updateSettings { it.copy(planMapRatio = ratio) } },
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

    // ---- 画面 ----

    BackHandler(enabled = fullscreen) { fullscreen = false }
    // 検索の一覧・選んだ場所は、戻るキーでまず閉じる
    BackHandler(enabled = search.listShown || search.picked != null) {
        search.listShown = false
        search.picked = null
    }

    if (fullscreen) {
        val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true)
        val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val peek = 72.dp + navBottom
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sheetMax = maxHeight * 0.6f
            BottomSheetScaffold(
                scaffoldState = scaffoldState,
                sheetPeekHeight = peek,
                sheetContent = {
                    Column(Modifier.fillMaxWidth().height(sheetMax).padding(bottom = navBottom)) {
                        Text(
                            stringResource(R.string.plan_sheet_title, routes.size, waypoints.size, Format.km(routes.sumOf { it.distanceM })),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                        )
                        Tabs(Modifier.fillMaxWidth().weight(1f), withMemo = false, onPickWaypoint = {
                            scope.launch { runCatching { sheetState.partialExpand() } }
                        })
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
                    title = {
                        Text(
                            plan?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { renamingPlan = true },
                        )
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                    actions = {
                        ImportMenuButton(
                            onFile = { openGpx.launch(GPX_MIME_TYPES) },
                            onQr = { scanQr(planId) },
                            onUrl = { vm.openUrlInput(planId) },
                        )
                        Box {
                            IconButton(onClick = { topMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                            DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.draw_route)) }, onClick = { topMenu = false; onDraw(null) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { topMenu = false; renamingPlan = true })
                                if (sourceUrls.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.reload_from_url)) }, onClick = {
                                        topMenu = false
                                        if (sourceUrls.size == 1) vm.reloadFromUrl(planId, sourceUrls.first()) else choosingReload = true
                                    })
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.export_gpx)) }, onClick = {
                                    topMenu = false
                                    exportGpx.launch(Format.safeFileName(plan?.name ?: "plan") + ".gpx")
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = {
                                    topMenu = false
                                    scope.launch {
                                        val data = vm.plans.toGpx(planId) ?: return@launch
                                        vm.shareFile(data, plan?.name ?: "plan", null)?.let { shareGpx(context, it) }
                                    }
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { topMenu = false; vm.duplicatePlan(planId, plan?.name ?: "") })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { topMenu = false; deletingPlan = true })
                            }
                        }
                    },
                )
            },
        ) { pad ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
                if (landscape) {
                    // 横長: 地図が左、一覧が右
                    val full = maxWidth
                    val total = with(density) { full.toPx() }
                    Row(Modifier.fillMaxSize()) {
                        MapPane(Modifier.fillMaxHeight().width(full * ratio), topInset = 0.dp, bottomPad = 0.dp)
                        Handle(vertical = false, totalPx = total)
                        Column(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.navigationBars)) {
                            Totals()
                            Tabs(Modifier.fillMaxWidth().weight(1f), withMemo = true, onPickWaypoint = {})
                            RideButton(Modifier)
                        }
                    }
                } else {
                    val full = maxHeight
                    val total = with(density) { full.toPx() }
                    Column(Modifier.fillMaxSize()) {
                        MapPane(Modifier.fillMaxWidth().height(full * ratio), topInset = 0.dp, bottomPad = 0.dp)
                        Handle(vertical = true, totalPx = total)
                        Totals()
                        Tabs(Modifier.fillMaxWidth().weight(1f), withMemo = true, onPickWaypoint = {})
                        RideButton(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }

    // ---- ダイアログ ----
    editing?.let { (w, isNew) ->
        val o = sorted.firstOrNull { it.item.id == w.id }
        WaypointDialog(
            initial = w,
            isNew = isNew,
            onSave = { if (isNew) vm.addWaypoint(it) else vm.updateWaypoint(it) },
            onDismiss = { editing = null },
            onDelete = if (isNew) null else ({ vm.deleteWaypoint(w) }),
            alongText = o?.progressM?.let { stringResource(R.string.wp_from_start, Format.km(it)) },
        )
    }
    if (renamingPlan) {
        TextInputDialog(stringResource(R.string.rename), plan?.name ?: "", onDone = { if (it.isNotBlank()) vm.updatePlan(planId, name = it) }, onDismiss = { renamingPlan = false })
    }
    if (deletingPlan) {
        ConfirmDialog(
            title = stringResource(R.string.plan_delete_confirm, plan?.name ?: ""),
            text = stringResource(R.string.plan_delete_text),
            confirm = stringResource(R.string.delete),
            onConfirm = { vm.deletePlan(planId); onBack() },
            onDismiss = { deletingPlan = false },
        )
    }
    if (choosingReload) {
        AlertDialog(
            onDismissRequest = { choosingReload = false },
            title = { Text(stringResource(R.string.reload_choose_url)) },
            text = {
                LazyColumn {
                    items(sourceUrls) { u ->
                        Text(
                            u, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { choosingReload = false; vm.reloadFromUrl(planId, u) }.padding(vertical = 10.dp),
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingReload = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    convertRoute?.let { r ->
        ConfirmDialog(
            title = stringResource(R.string.draw_convert_title),
            text = stringResource(R.string.draw_convert_text, r.pointCount, jp.kjtr.cycleroutelogger.draw.DrawnGeometry.MAX_EDIT_ANCHORS),
            confirm = stringResource(R.string.draw_convert_go),
            onConfirm = { onDraw(r.id) },
            onDismiss = { convertRoute = null },
        )
    }
    colorFor?.let { r -> ColorPickerDialog(r.color, onPick = { vm.updateRoute(r.copy(color = it)) }, onDismiss = { colorFor = null }) }
    renamingRoute?.let { r ->
        TextInputDialog(stringResource(R.string.rename), r.name, onDone = { if (it.isNotBlank()) vm.updateRoute(r.copy(name = it)) }, onDismiss = { renamingRoute = null })
    }
    deletingRoute?.let { r ->
        ConfirmDialog(
            title = stringResource(R.string.route_delete_confirm, r.name), text = null, confirm = stringResource(R.string.delete),
            onConfirm = { vm.deleteRoute(r) }, onDismiss = { deletingRoute = null },
        )
    }
}

@Composable
private fun RouteRow(
    r: RouteEntity,
    index: Int,
    count: Int,
    expanded: Boolean,
    profile: List<Pair<Double, Double>>,
    onToggleExpand: () -> Unit,
    onColor: () -> Unit,
    onVisible: () -> Unit,
    onMove: (Int) -> Unit,
    onRename: () -> Unit,
    onFit: () -> Unit,
    onEditShape: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { onToggleExpand() }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(Color(r.color), CircleShape).clickable { onColor() })
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.route_line, Format.km(r.distanceM), Format.meters(r.ascentM), r.pointCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onVisible) {
                Icon(
                    if (r.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = stringResource(if (r.visible) R.string.hide else R.string.show),
                )
            }
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) { Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.move_up)) }
            IconButton(onClick = { onMove(1) }, enabled = index < count - 1) { Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.move_down)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.fit_route)) }, onClick = { menu = false; onFit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.draw_edit_route)) }, onClick = { menu = false; onEditShape() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        if (expanded) {
            ElevationChart(profile, Color(r.color), Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp))
        }
    }
}

fun shareGpx(context: android.content.Context, uri: android.net.Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.share)).apply {
        // 自分自身（GPX の受け取り先）は共有先に出さない
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(context, jp.kjtr.cycleroutelogger.MainActivity::class.java)))
    }
    context.startActivity(chooser)
}
