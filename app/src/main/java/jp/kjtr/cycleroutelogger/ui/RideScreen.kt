package jp.kjtr.cycleroutelogger.ui

import android.Manifest
import android.location.Location
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationDisabled
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.widgets.ScaleBar
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.record.RecorderStatus
import jp.kjtr.cycleroutelogger.data.Destination
import jp.kjtr.cycleroutelogger.data.FollowMode
import jp.kjtr.cycleroutelogger.data.MapKind
import jp.kjtr.cycleroutelogger.data.TrackEntity
import jp.kjtr.cycleroutelogger.data.WaypointEntity
import jp.kjtr.cycleroutelogger.geo.Bearing
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.plan.WaypointType
import jp.kjtr.cycleroutelogger.sensor.rememberDeviceHeading
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** 地図の「現在地」レイヤーに、アプリの測位（LocationHub）をそのまま渡す。 */
private class HubLocationSource : LocationSource {
    private var listener: LocationSource.OnLocationChangedListener? = null
    private var last: Location? = null
    override fun activate(l: LocationSource.OnLocationChangedListener) {
        listener = l
        last?.let { l.onLocationChanged(it) }
    }

    override fun deactivate() {
        listener = null
    }

    fun push(loc: Location) {
        last = loc
        listener?.onLocationChanged(loc)
    }
}

/** 走行画面（フリー / ルート）。地図 + 記録 + 案内 + 目的地。 */
@Composable
fun RideScreen(vm: MainViewModel, onBack: () -> Unit, onCamera: () -> Unit = {}, onShareCard: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ride by vm.ride.collectAsStateWithLifecycle()
    val rec by vm.session.state.collectAsStateWithLifecycle()
    val line by vm.session.line.collectAsStateWithLifecycle()
    val loc by vm.locationHub.last.collectAsStateWithLifecycle()
    val plan by vm.navigator.activePlan.collectAsStateWithLifecycle()
    val guide by vm.navigator.guide.collectAsStateWithLifecycle()
    val dest by vm.navigator.destination.collectAsStateWithLifecycle()
    val destination by vm.settingsStore.destination.collectAsStateWithLifecycle()
    val arrival by vm.navigator.arrival.collectAsStateWithLifecycle()
    val fitRequest by vm.fitRequest.collectAsStateWithLifecycle()
    val deviceHeading by rememberDeviceHeading()
    val planEntity by remember(ride?.planId) { ride?.planId?.let { vm.plans.plan(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)

    val isRouteMode = ride?.mode == TrackEntity.MODE_ROUTE && ride?.planId != null
    var hasPermission by remember { mutableStateOf(vm.locationHub.hasPermission()) }
    var showBig by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var longPressAt by remember { mutableStateOf<LatLon?>(null) }
    var tappedWaypoint by remember { mutableStateOf<WaypointEntity?>(null) }
    var editingWaypoint by remember { mutableStateOf<Pair<WaypointEntity, Boolean>?>(null) }
    var showWaypointList by remember { mutableStateOf(false) }
    var mapMenu by remember { mutableStateOf(false) }
    var needFineDialog by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var mapLoaded by remember { mutableStateOf(false) }
    val ridePhotos by remember(rec?.trackId) {
        rec?.trackId?.let { vm.photos.forTrack(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(emptyList())
    var viewingPhoto by remember { mutableStateOf<jp.kjtr.cycleroutelogger.data.PhotoEntity?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    // 測位（画面が見えている間）
    LifecycleStartEffect(hasPermission) {
        if (hasPermission) vm.locationHub.acquire("ui")
        onStopOrDispose { vm.locationHub.release("ui") }
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = vm.locationHub.hasPermission()
        if (hasPermission) {
            vm.locationHub.acquire("ui")
            vm.locationHub.restart()
        }
    }
    // 記録開始の前の確認: 位置（正確）→ 通知（Android 13+。拒否でも記録はできる）→ 初回だけ電池の最適化の案内
    var askNotif by remember { mutableStateOf(false) }
    var askBattery by remember { mutableStateOf(false) }
    val startError by vm.recordStartError.collectAsStateWithLifecycle()
    val serviceRunning by vm.recorder.serviceRunning.collectAsStateWithLifecycle()
    val locationOff by vm.recorder.locationOff.collectAsStateWithLifecycle()
    val noFix by vm.recorder.noFix.collectAsStateWithLifecycle()
    // 今の測位の方式（0.9.0〜。Google の融合測位を選んでいても使えなければ端末の GPS）
    val fusedActive by vm.locationHub.fusedActive.collectAsStateWithLifecycle()

    fun startStepBattery() {
        if (!settings.batteryHintShown && !RecorderStatus.ignoringBatteryOptimizations(context)) askBattery = true else vm.startRecording()
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startStepBattery()
    }

    fun startRecording() {
        if (!vm.locationHub.hasFinePermission()) {
            needFineDialog = true
            return
        }
        if (!RecorderStatus.notificationsAllowed(context)) askNotif = true else startStepBattery()
    }

    // 画面を点けたままにする（記録中）
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn, rec != null) {
        view.keepScreenOn = settings.keepScreenOn && rec != null
        onDispose { view.keepScreenOn = false }
    }

    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(35.681, 139.767), 5f)
    }
    val locationSource = remember { HubLocationSource() }
    LaunchedEffect(loc) { loc?.let { locationSource.push(it) } }

    // 地図の向き: 進行方向が上なら、動いているときは進行方向、止まっているときは端末の向き
    val heading: Float? = loc?.let { l ->
        if (l.hasBearing() && l.hasSpeed() && l.speed > 1.5f) l.bearing else deviceHeading
    } ?: deviceHeading
    val cameraBearingTarget = when (settings.followMode) {
        FollowMode.HEADING_UP -> ((heading ?: 0f) / 5f).roundToInt() * 5f % 360f
        else -> 0f
    }
    var firstFix by remember { mutableStateOf(true) }
    LaunchedEffect(loc, settings.followMode, cameraBearingTarget, mapLoaded) {
        val l = loc ?: return@LaunchedEffect
        if (!mapLoaded || settings.followMode == FollowMode.NONE) return@LaunchedEffect
        // 最初の追従と、広域（ズーム 10 未満）のままのときは自転車向けの縮尺にする
        val zoom = if (firstFix || cameraState.position.zoom < 10f) 16f else cameraState.position.zoom
        firstFix = false
        val update = CameraUpdateFactory.newCameraPosition(CameraPosition(LatLng(l.latitude, l.longitude), zoom, 0f, cameraBearingTarget))
        // 地図が描けない状態（キーの制限でタイルが読めないなど）だとアニメーションが終わらない・取り消されるので、
        // 時間内に終わらなければすぐ動かす
        val done = runCatching { withTimeoutOrNull(700) { cameraState.animate(update, 400); true } }.getOrNull()
        if (done != true && isActive) runCatching { cameraState.move(update) }
    }
    // 手で地図を動かしたら追従をやめる
    LaunchedEffect(cameraState.isMoving) {
        if (cameraState.isMoving && cameraState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE &&
            settings.followMode != FollowMode.NONE
        ) {
            vm.updateSettings { it.copy(followMode = FollowMode.NONE) }
        }
    }
    var topPanelPx by remember { mutableIntStateOf(0) }
    var bottomPanelPx by remember { mutableIntStateOf(0) }
    var mapW by remember { mutableIntStateOf(0) }
    var mapH by remember { mutableIntStateOf(0) }
    var rightColPx by remember { mutableIntStateOf(0) }
    // 範囲合わせの依頼（プランの［地図に合わせる］）
    LaunchedEffect(fitRequest, mapLoaded) {
        val b = fitRequest ?: return@LaunchedEffect
        if (!mapLoaded) return@LaunchedEffect
        if (settings.followMode != FollowMode.NONE) vm.updateSettings { it.copy(followMode = FollowMode.NONE) }
        cameraState.fitTo(b, with(density) { 48.dp.roundToPx() }, mapW, mapH - topPanelPx - bottomPanelPx)
        vm.consumeFit()
    }

    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val here = loc?.let { LatLon(it.latitude, it.longitude) }

    // ---- 場所名の検索（0.6.0。目的地を探す用。走行中は止まってから。右のボタンから開く） ----
    val search = rememberPlaceSearchState()
    var placeOrigin by remember { mutableStateOf<PlaceOrigin?>(null) }
    val placeColor = colorResource(R.color.place_result)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun runPlaceSearch() {
        val t = cameraState.position.target
        placeOrigin = here?.let { PlaceOrigin(it, true) } ?: PlaceOrigin(LatLon(t.latitude, t.longitude), false)
        search.submit(scope, vm, cameraState.visibleBounds())
    }
    fun pickPlace(p: jp.kjtr.cycleroutelogger.geo.PlaceResult) {
        search.picked = p
        search.listShown = false
        // 追従していると現在地へ戻されるので止める
        if (settings.followMode != FollowMode.NONE) vm.updateSettings { it.copy(followMode = FollowMode.NONE) }
        scope.launch {
            cameraState.showPlace(p, with(density) { 48.dp.roundToPx() }, mapW, (mapH - topPanelPx - bottomPanelPx).coerceAtLeast(1))
        }
    }
    val routeWidth = with(density) { 6.dp.toPx() }
    val trackColor = colorResource(R.color.track_line)
    val destColor = colorResource(R.color.destination)

    Box(Modifier.fillMaxSize().onSizeChanged { mapW = it.width; mapH = it.height }) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraState,
            properties = MapProperties(mapType = settings.mapKind.toMapType(), isMyLocationEnabled = hasPermission),
            uiSettings = MapUiSettings(
                compassEnabled = true, myLocationButtonEnabled = false, zoomControlsEnabled = false,
                mapToolbarEnabled = false, tiltGesturesEnabled = false, indoorLevelPickerEnabled = false,
            ),
            locationSource = locationSource,
            onMapLongClick = { longPressAt = LatLon(it.latitude, it.longitude) },
            contentPadding = PaddingValues(
                top = with(density) { topPanelPx.toDp() },
                bottom = with(density) { bottomPanelPx.toDp() },
            ),
        ) {
            OnMapReady { mapLoaded = true }
            PlanRoutes(plan?.shape, routeWidth)
            WaypointMarkers(
                waypoints = plan?.waypoints?.map { it.item }.orEmpty(),
                hiddenTypes = settings.hiddenTypes,
                highlightId = destination?.waypointId,
                draggable = false,
                onClick = { tappedWaypoint = it },
            )
            TrackLines(line, trackColor, with(density) { 5.dp.toPx() })
            PhotoMarkers(ridePhotos, onClick = { viewingPhoto = it })
            destination?.let { DestinationOverlay(it, here, destColor) }
            search.picked?.let { PlaceResultMarker(it, placeColor) }
        }

        // 画面外の目的地 → 端に矢印
        val d = destination
        if (d != null && mapLoaded && mapW > 0) {
            @Suppress("UNUSED_VARIABLE") val pos = cameraState.position // カメラが動いたら描き直す
            val proj = cameraState.projection
            if (proj != null) {
                val p = proj.toScreenLocation(LatLng(d.lat, d.lon))
                val top = topPanelPx.toFloat()
                val bottom = (mapH - bottomPanelPx).toFloat()
                val regionH = (bottom - top).coerceAtLeast(1f)
                // 右のボタンの列に隠れないよう、その左側を「画面」とみなす
                val regionW = (mapW - rightColPx).toFloat().coerceAtLeast(1f)
                val cx = regionW / 2f
                val cy = top + regionH / 2f
                val margin = with(density) { 36.dp.toPx() }
                val m = Bearing.edgeMarker(regionW, regionH, margin, p.x - cx, p.y - cy)
                if (m != null) {
                    val half = with(density) { 22.dp.toPx() }
                    EdgeArrow(
                        rotationDeg = m.rotationDeg,
                        distanceM = dest?.distanceM,
                        modifier = Modifier.offset { IntOffset((m.x - half).roundToInt(), (top + m.y - half).roundToInt()) },
                    )
                }
            }
        }

        // 上: 戻る・目的地・案内
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = insets.calculateTopPadding(), start = 8.dp, end = 8.dp)
                .onSizeChanged { topPanelPx = it.height + with(density) { insets.calculateTopPadding().roundToPx() } },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallFloatingActionButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Text(
                    if (isRouteMode) stringResource(R.string.mode_route_with, planEntity?.name ?: "") else stringResource(R.string.mode_free),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    maxLines = 1,
                )
            }
            dest?.let { DestinationPanel(it, cameraState.position.bearing, onClear = { vm.setDestination(null) }, modifier = Modifier.fillMaxWidth()) }
            if (isRouteMode) guide?.let { GuidePanel(it, Modifier.fillMaxWidth()) }
            if (!hasPermission) {
                PanelSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.perm_location_explain))
                        Button(onClick = {
                            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }) { Text(stringResource(R.string.perm_allow)) }
                    }
                }
            }
        }

        // 右: 地図の操作
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = with(density) { topPanelPx.toDp() } + 8.dp,
                    // 下は撮影ボタン（96dp）の分も空ける
                    bottom = with(density) { bottomPanelPx.toDp() } + 120.dp,
                    end = 8.dp + insets.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                )
                // 上下のパネルが大きいときは、ボタンの列を縦にスクロールできるようにする
                .verticalScroll(rememberScrollState())
                .onSizeChanged { rightColPx = it.width + with(density) { 8.dp.roundToPx() } },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallFloatingActionButton(onClick = { if (search.open) search.close() else search.open = true }) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(if (search.open) R.string.place_search_close else R.string.place_search_open),
                    tint = if (search.open) colorResource(R.color.place_result) else androidx.compose.material3.LocalContentColor.current,
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
            SmallFloatingActionButton(onClick = {
                val next = when (settings.followMode) {
                    FollowMode.NONE -> FollowMode.NORTH_UP
                    FollowMode.NORTH_UP -> FollowMode.HEADING_UP
                    FollowMode.HEADING_UP -> FollowMode.NONE
                }
                vm.updateSettings { it.copy(followMode = next) }
            }) {
                val (icon, cd) = when (settings.followMode) {
                    FollowMode.NONE -> Icons.Filled.LocationDisabled to R.string.follow_none
                    FollowMode.NORTH_UP -> Icons.Filled.MyLocation to R.string.follow_north
                    FollowMode.HEADING_UP -> Icons.Filled.Explore to R.string.follow_heading
                }
                Icon(icon, contentDescription = stringResource(cd))
            }
            SmallFloatingActionButton(onClick = { cameraState.move(CameraUpdateFactory.zoomIn()) }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.zoom_in)) }
            SmallFloatingActionButton(onClick = { cameraState.move(CameraUpdateFactory.zoomOut()) }) { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.zoom_out)) }
            if (isRouteMode) {
                SmallFloatingActionButton(onClick = { vm.requestFit(plan?.shape?.bounds) }) { Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.fit_plan)) }
                SmallFloatingActionButton(onClick = { showWaypointList = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.wp_list)) }
            }
            if (destination != null) {
                SmallFloatingActionButton(onClick = {
                    val b = listOfNotNull(here, destination?.let { LatLon(it.lat, it.lon) })
                    jp.kjtr.cycleroutelogger.geo.GeoBounds.of(b)?.let { vm.requestFit(it) }
                }) { Icon(Icons.Filled.CenterFocusStrong, contentDescription = stringResource(R.string.fit_dest)) }
            }
            // 記録中でも、ここまでの記録で SNS 用の画像を作れる（記録は止めない）
            rec?.let { r ->
                SmallFloatingActionButton(onClick = { onShareCard(r.trackId) }) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.card_while_recording))
                }
            }
        }

        // 右下: 撮影（大きめ。走行中は止まってから）。左下は Google のロゴなので避ける
        androidx.compose.material3.LargeFloatingActionButton(
            onClick = onCamera,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp + insets.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr), bottom = with(density) { bottomPanelPx.toDp() } + 12.dp),
        ) { Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.camera_open), modifier = Modifier.size(36.dp)) }

        // 左上: 縮尺
        ScaleBar(
            modifier = Modifier.padding(top = with(density) { topPanelPx.toDp() } + 8.dp, start = 12.dp),
            cameraPositionState = cameraState,
        )

        // 左上（上のパネルの下・右のボタン列の左）: 場所名の検索 → 結果 → 選んだ場所の［ここへ向かう］
        if (search.open) {
            val picked = search.picked
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        top = with(density) { topPanelPx.toDp() } + 8.dp,
                        start = 8.dp + insets.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                        end = with(density) { rightColPx.toDp() } + 8.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlaceSearchBar(
                    search, onSubmit = { runPlaceSearch() }, modifier = Modifier.fillMaxWidth(),
                    hint = stringResource(R.string.place_search_hint_ride), onCollapse = { search.close() },
                )
                PlaceResultsPanel(
                    vm, search, placeOrigin,
                    maxHeight = with(density) { (mapH - topPanelPx - bottomPanelPx).toDp() } - 72.dp,
                    onPick = { pickPlace(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (picked != null) {
                    PickedPlaceCard(vm, picked, placeOrigin, onClose = { search.picked = null }, modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = {
                            vm.setDestination(Destination(picked.lat, picked.lon, picked.name))
                            search.close()
                        }) { Text(stringResource(R.string.go_here)) }
                        val pid = ride?.planId
                        if (isRouteMode && pid != null) {
                            androidx.compose.material3.OutlinedButton(onClick = {
                                editingWaypoint = WaypointEntity(
                                    planId = pid, name = picked.name, type = jp.kjtr.cycleroutelogger.geo.NominatimSearch.guessType(picked).code,
                                    lat = picked.lat, lon = picked.lon, createdAt = System.currentTimeMillis(),
                                ) to true
                                search.close()
                            }) { Text(stringResource(R.string.register_and_go)) }
                        }
                        TextButton(onClick = { search.listShown = true; search.picked = null }) { Text(stringResource(R.string.place_back_to_results)) }
                    }
                }
            }
        }

        // 下: 記録
        RecordPanel(
            rec = rec,
            now = now,
            modeLabel = if (isRouteMode) stringResource(R.string.mode_route_with, planEntity?.name ?: "") else stringResource(R.string.mode_free),
            canStart = hasPermission,
            onStart = { startRecording() },
            onPause = { vm.pauseRecording() },
            onResume = { vm.resumeRecording() },
            onFinish = { confirmFinish = true },
            onBig = { showBig = true },
            statusLine = when {
                rec == null -> null
                !serviceRunning -> stringResource(R.string.bg_status_stopped)
                locationOff -> stringResource(R.string.bg_status_location_off)
                noFix -> stringResource(R.string.bg_status_no_fix)
                else -> stringResource(R.string.bg_status_running)
            },
            statusWarn = rec != null && (!serviceRunning || locationOff || noFix),
            statusActionLabel = when {
                rec != null && !serviceRunning -> stringResource(R.string.bg_status_restart)
                locationOff -> stringResource(R.string.bg_status_location_settings)
                else -> null
            },
            positioningLabel = stringResource(if (fusedActive) R.string.ride_positioning_google else R.string.ride_positioning_device),
            onStatusAction = {
                if (!serviceRunning) vm.restartRecordingService()
                else runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp)
                .onSizeChanged { bottomPanelPx = it.height + with(density) { (insets.calculateBottomPadding() + 8.dp).roundToPx() } },
        )

        // 戻るキーは大きな表示を閉じるだけ（走行画面からは出ない）
        // 検索は戻るキーで一覧・選んだ場所 → 検索欄の順に閉じる
        androidx.activity.compose.BackHandler(enabled = search.open && !showBig) {
            if (search.listShown || search.picked != null) {
                search.listShown = false
                search.picked = null
            } else {
                search.close()
            }
        }
        androidx.activity.compose.BackHandler(enabled = showBig) { showBig = false }
        if (showBig) {
            BigRideView(
                rec = rec, now = now, guide = if (isRouteMode) guide else null, dest = dest,
                // 地図が無いので、矢印は進行方向（止まっていれば端末の向き）を上として描く
                mapBearing = heading ?: 0f,
                onClearDest = { vm.setDestination(null) },
                onPause = { vm.pauseRecording() }, onResume = { vm.resumeRecording() },
                onClose = { showBig = false },
            )
        }
    }

    // ---- ダイアログ ----

    if (needFineDialog) {
        AlertDialog(
            onDismissRequest = { needFineDialog = false },
            title = { Text(stringResource(R.string.perm_fine_title)) },
            text = { Text(stringResource(R.string.perm_fine_explain)) },
            confirmButton = {
                TextButton(onClick = {
                    needFineDialog = false
                    locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text(stringResource(R.string.perm_allow)) }
            },
            dismissButton = { TextButton(onClick = { needFineDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (askNotif) {
        AlertDialog(
            onDismissRequest = { askNotif = false },
            title = { Text(stringResource(R.string.rec_notif_title)) },
            text = { Text(stringResource(R.string.rec_notif_explain)) },
            confirmButton = {
                TextButton(onClick = {
                    askNotif = false
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else {
                        BackgroundSettings.openNotificationSettings(context)
                        startStepBattery()
                    }
                }) { Text(stringResource(R.string.perm_allow)) }
            },
            dismissButton = { TextButton(onClick = { askNotif = false; startStepBattery() }) { Text(stringResource(R.string.rec_notif_skip)) } },
        )
    }

    if (askBattery) {
        AlertDialog(
            onDismissRequest = { askBattery = false },
            title = { Text(stringResource(R.string.rec_battery_title)) },
            text = { Text(stringResource(R.string.rec_battery_explain)) },
            confirmButton = {
                TextButton(onClick = {
                    askBattery = false
                    vm.updateSettings { it.copy(batteryHintShown = true) }
                    // 記録は前面のうちに始めてから、電池の設定の確認を出す
                    vm.startRecording()
                    BackgroundSettings.requestIgnoreBatteryOptimizations(context)
                }) { Text(stringResource(R.string.rec_battery_go)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askBattery = false
                    vm.updateSettings { it.copy(batteryHintShown = true) }
                    vm.startRecording()
                }) { Text(stringResource(R.string.rec_battery_later)) }
            },
        )
    }

    startError?.let { f -> RecordStartErrorDialog(f, onDismiss = { vm.dismissRecordStartError() }) }

    viewingPhoto?.let { p -> PhotoViewer(vm, ridePhotos, p, onDismiss = { viewingPhoto = null }) }

    if (confirmFinish) {
        ConfirmDialog(
            title = stringResource(R.string.finish_confirm),
            text = stringResource(R.string.finish_confirm_text),
            confirm = stringResource(R.string.finish),
            onConfirm = { vm.finishRecording() },
            onDismiss = { confirmFinish = false },
        )
    }

    longPressAt?.let { p ->
        AlertDialog(
            onDismissRequest = { longPressAt = null },
            title = { Text(stringResource(R.string.point_title)) },
            text = {
                Text(
                    if (here != null) directionText(Bearing.initialDeg(here, p), jp.kjtr.cycleroutelogger.geo.Geo.distanceM(here, p))
                    else stringResource(R.string.waiting_gps),
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        vm.setDestination(Destination(p.lat, p.lon, context.getString(R.string.dest_map_point)))
                        longPressAt = null
                    }) { Text(stringResource(R.string.go_here)) }
                    val pid = ride?.planId
                    if (isRouteMode && pid != null) {
                        TextButton(onClick = {
                            editingWaypoint = WaypointEntity(
                                planId = pid, name = "", type = WaypointType.OTHER.code, lat = p.lat, lon = p.lon,
                                createdAt = System.currentTimeMillis(),
                            ) to true
                            longPressAt = null
                        }) { Text(stringResource(R.string.register_and_go)) }
                    }
                    TextButton(onClick = { longPressAt = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }

    tappedWaypoint?.let { w ->
        val t = WaypointType.fromCode(w.type)
        val onRoute = plan?.waypoints?.firstOrNull { it.item.id == w.id }
        AlertDialog(
            onDismissRequest = { tappedWaypoint = null },
            icon = { WaypointBadge(t, size = 40.dp) },
            title = { Text(w.name.ifBlank { t.label() }) },
            text = {
                Column {
                    Text(t.label(), style = MaterialTheme.typography.labelLarge)
                    if (w.memo.isNotBlank()) Text(w.memo)
                    w.plannedAt?.let { Text(stringResource(R.string.wp_planned_at, Format.dateTime(it))) }
                    onRoute?.progressM?.let { Text(stringResource(R.string.wp_from_start, Format.km(it))) }
                    if (here != null) {
                        Text(directionText(Bearing.initialDeg(here, LatLon(w.lat, w.lon)), jp.kjtr.cycleroutelogger.geo.Geo.distanceM(here, LatLon(w.lat, w.lon))))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.setDestinationWaypoint(w); tappedWaypoint = null }) { Text(stringResource(R.string.go_here)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { editingWaypoint = w to false; tappedWaypoint = null }) { Text(stringResource(R.string.edit)) }
                    TextButton(onClick = { tappedWaypoint = null }) { Text(stringResource(R.string.close)) }
                }
            },
        )
    }

    editingWaypoint?.let { (w, isNew) ->
        WaypointDialog(
            initial = w,
            isNew = isNew,
            onSave = { if (isNew) vm.addWaypoint(it, thenGo = true) else vm.updateWaypoint(it) },
            onDismiss = { editingWaypoint = null },
            onDelete = if (isNew) null else ({ vm.deleteWaypoint(w) }),
            onGo = if (isNew) null else ({ vm.updateWaypoint(it); vm.setDestinationWaypoint(it) }),
        )
    }

    if (showWaypointList) {
        AlertDialog(
            onDismissRequest = { showWaypointList = false },
            title = { Text(stringResource(R.string.wp_list)) },
            text = {
                val list = plan?.waypoints.orEmpty().filter { it.item.type !in settings.hiddenTypes }
                if (list.isEmpty()) Text(stringResource(R.string.wp_none))
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(list, key = { it.item.id }) { o ->
                        val t = WaypointType.fromCode(o.item.type)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.setDestinationWaypoint(o.item); showWaypointList = false }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WaypointBadge(t, size = 30.dp)
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(o.item.name.ifBlank { t.label() })
                                Text(
                                    o.progressM?.let { stringResource(R.string.wp_from_start, Format.km(it)) } ?: t.label(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (destination?.waypointId == o.item.id) Text(stringResource(R.string.dest_current_mark), color = destColor)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showWaypointList = false }) { Text(stringResource(R.string.close)) } },
        )
    }

    arrival?.let { a ->
        val hasNext = isRouteMode && plan?.waypoints?.isNotEmpty() == true
        AlertDialog(
            onDismissRequest = { vm.navigator.dismissArrival() },
            title = { Text(stringResource(R.string.arrived_title)) },
            text = { Text(stringResource(R.string.arrived_text, a.name.ifBlank { stringResource(R.string.dest_unnamed) })) },
            confirmButton = {
                if (hasNext) TextButton(onClick = { vm.navigator.advanceToNextWaypoint() }) { Text(stringResource(R.string.go_next_wp)) }
                else TextButton(onClick = { vm.navigator.clearAfterArrival() }) { Text(stringResource(R.string.dest_clear)) }
            },
            dismissButton = {
                Row {
                    if (hasNext) TextButton(onClick = { vm.navigator.clearAfterArrival() }) { Text(stringResource(R.string.dest_clear)) }
                    TextButton(onClick = { vm.navigator.dismissArrival() }) { Text(stringResource(R.string.keep)) }
                }
            },
        )
    }
}

fun mapKindLabel(k: MapKind): Int = when (k) {
    MapKind.NORMAL -> R.string.map_normal
    MapKind.SATELLITE -> R.string.map_satellite
    MapKind.HYBRID -> R.string.map_hybrid
    MapKind.TERRAIN -> R.string.map_terrain
}
