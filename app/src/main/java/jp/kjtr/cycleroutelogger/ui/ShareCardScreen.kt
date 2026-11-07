package jp.kjtr.cycleroutelogger.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.android.gms.maps.GoogleMap as RawMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import jp.kjtr.cycleroutelogger.R
import jp.kjtr.cycleroutelogger.data.PhotoEntity
import jp.kjtr.cycleroutelogger.data.TrackRepository
import jp.kjtr.cycleroutelogger.geo.GeoBounds
import jp.kjtr.cycleroutelogger.geo.LatLon
import jp.kjtr.cycleroutelogger.geo.Simplify
import jp.kjtr.cycleroutelogger.photo.CardContent
import jp.kjtr.cycleroutelogger.photo.CardSize
import jp.kjtr.cycleroutelogger.photo.ShareCardLayout
import jp.kjtr.cycleroutelogger.photo.ShareCardRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * SNS 用の画像を作る。上の地図（Google マップ）を画像の地図の枠と同じ縦横比で表示し、［画像を作る］でスナップショット
 * （ロゴ・著作権表示ごと）を撮って、写真・統計・タイトルと 1 枚にする。地図が読み込めない（キーが通らない等）・
 * ［地図を使わない］ときは、軌跡だけを白地に描く。
 */
@OptIn(ExperimentalMaterial3Api::class, MapsComposeExperimentalApi::class)
@Composable
fun ShareCardScreen(vm: MainViewModel, trackId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val track by remember(trackId) { vm.tracks.track(trackId) }.collectAsState(null)
    val photos by remember(trackId) { vm.photos.forTrack(trackId) }.collectAsState(emptyList())
    // 記録中の記録なら、統計と軌跡は記録中の最新の値を使う（記録は止めない。画像は「ここまで」の内容）
    val rec by vm.session.state.collectAsState()
    val live = rec?.takeIf { it.trackId == trackId }
    val liveLine by vm.session.line.collectAsState()
    val savedSegments by produceState(emptyList<List<LatLon>>(), trackId) {
        value = withContext(Dispatchers.Default) {
            // 0.9.0〜 なめらかにした点（設定が ON のとき。記録の詳細の地図と同じ線）
            TrackRepository.segmentsOf(vm.tracks.displayPoints(trackId)).map { s -> Simplify.forDisplay(s.map { LatLon(it.lat, it.lon) }, 3000) }
        }
    }
    // 記録中は軌跡が伸び続けるので、画面を開いた時点の軌跡で固定する（地図の範囲合わせが動き続けないように）
    val liveSegments = remember(trackId, live != null) {
        if (live != null) liveLine.filter { it.size >= 2 }.map { Simplify.forDisplay(it, 3000) } else null
    }
    val segments = liveSegments?.takeIf { it.isNotEmpty() } ?: savedSegments

    var size by remember { mutableStateOf(CardSize.SQUARE) }
    var mapType by remember { mutableStateOf(MapType.NORMAL) }
    var useMap by remember { mutableStateOf(true) }
    var showApp by remember { mutableStateOf(true) }
    var title by remember(track?.id) { mutableStateOf(track?.name ?: "") }
    var selected by remember { mutableStateOf<List<Long>>(emptyList()) }
    var initSelected by remember { mutableStateOf(false) }
    LaunchedEffect(photos) {
        if (!initSelected && photos.isNotEmpty()) {
            selected = photos.filter { it.lat != null }.ifEmpty { photos }.take(ShareCardLayout.MAX_PHOTOS).map { it.id }
            initSelected = true
        }
        selected = selected.filter { id -> photos.any { it.id == id } }
    }
    val chosen = selected.mapNotNull { id -> photos.firstOrNull { it.id == id } }
    val layout = ShareCardLayout.compute(size, chosen.size, showApp)

    var rawMap by remember { mutableStateOf<RawMap?>(null) }
    var mapLoadedOnce by remember { mutableStateOf(false) }
    var mapViewSize by remember { mutableStateOf(IntSize.Zero) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf(false) }
    var usedFallback by remember { mutableStateOf(false) }
    val cameraState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(35.68, 139.76), 5f) }

    fun bounds(): GeoBounds? = GeoBounds.of(segments.flatten() + chosen.mapNotNull { p -> p.lat?.let { LatLon(it, p.lon!!) } })
    LaunchedEffect(rawMap, segments, mapViewSize, size, selected) {
        val b = bounds() ?: return@LaunchedEffect
        if (rawMap == null || mapViewSize.width == 0) return@LaunchedEffect
        // 枠の縦横比が変わった直後は地図の大きさがまだ前のままのことがあるので、少し待ってから合わせる
        kotlinx.coroutines.delay(200)
        cameraState.fitTo(b, with(density) { 28.dp.roundToPx() }, mapViewSize.width, mapViewSize.height)
    }

    fun generate() {
        val t = track ?: return
        working = true
        result = null
        scope.launch {
            val map = rawMap
            // 地図のスナップショット（読み込み完了を待つ。キーが通らないと完了しないので 8 秒で諦めて代替表示）
            suspend fun shoot(m: RawMap, waitLoaded: Boolean): Pair<Bitmap, List<PointF?>>? = suspendCancellableCoroutine { cont ->
                val take = {
                    m.snapshot { bmp ->
                        if (bmp == null) {
                            if (cont.isActive) cont.resume(null)
                        } else {
                            val proj = m.projection
                            val pins = chosen.map { p ->
                                if (p.lat == null || p.lon == null) null else {
                                    val pt = proj.toScreenLocation(LatLng(p.lat, p.lon))
                                    PointF(pt.x.toFloat() / bmp.width, pt.y.toFloat() / bmp.height)
                                }
                            }
                            if (cont.isActive) cont.resume(bmp to pins)
                        }
                    }
                }
                if (waitLoaded) m.setOnMapLoadedCallback { take() } else take()
            }
            val snap: Pair<Bitmap, List<PointF?>>? = if (useMap && map != null) {
                // 読み込み完了を待って撮る。すでに読み込み済みで知らせが来ないときは、一度でも読み込めていればそのまま撮る。
                // キーが通らないと読み込みが終わらないので、そのときは代替表示。
                withTimeoutOrNull(5_000) { shoot(map, waitLoaded = true) }
                    ?: if (mapLoadedOnce) withTimeoutOrNull(3_000) { shoot(map, waitLoaded = false) } else null
            } else null
            usedFallback = useMap && snap == null
            val bmp = withContext(Dispatchers.Default) {
                val photoBmps = chosen.map { p ->
                    val cell = layout.photos.getOrNull(chosen.indexOf(p))
                    vm.photos.loadPhoto(p, ((cell?.let { maxOf(it.width, it.height) } ?: 400f) * 1.2f).toInt())
                }
                val distanceM = live?.distanceM ?: t.distanceM
                val movingMs = live?.movingMs ?: t.movingMs
                val ascentM = live?.ascentM ?: t.ascentM
                val stats = listOf(
                    context.getString(R.string.stat_distance) to Format.km(distanceM),
                    context.getString(R.string.stat_moving) to Format.duration(movingMs),
                    context.getString(R.string.stat_avg) to Format.speed(if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0) + " km/h",
                    context.getString(R.string.stat_ascent) to Format.meters(ascentM),
                )
                val sub = Format.dateTime(t.startedAt) + (t.planName?.let { " ・ " + it } ?: "") +
                    (if (live != null) " ・ " + context.getString(R.string.card_so_far) else "")
                ShareCardRenderer.render(
                    layout,
                    CardContent(
                        title = title.ifBlank { t.name }, subtitle = sub, stats = stats,
                        appName = if (showApp) context.getString(R.string.app_name) else null,
                        mapBitmap = snap?.first, pinsOnSnapshot = snap?.second ?: chosen.map { null },
                        route = segments, photoLocations = chosen.map { p -> p.lat?.let { LatLon(it, p.lon!!) } },
                        photos = photoBmps,
                    ),
                )
            }
            result = bmp
            working = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.card_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (live != null) {
                Text(stringResource(R.string.card_recording_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            // 画像の地図の枠と同じ縦横比の地図（動かして範囲を調整できる）
            Box(
                Modifier.fillMaxWidth().aspectRatio(layout.map.width / layout.map.height).clip(RoundedCornerShape(10.dp))
                    .onSizeChanged { mapViewSize = it },
            ) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraState,
                    properties = MapProperties(mapType = mapType),
                    uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, tiltGesturesEnabled = false, rotationGesturesEnabled = false),
                    onMapLoaded = { mapLoadedOnce = true },
                ) {
                    MapEffect(Unit) { m -> rawMap = m }
                    for (seg in segments) {
                        if (seg.size >= 2) Polyline(points = remember(seg) { seg.map { it.toLatLng() } }, color = Color(0xFFD84315), width = with(density) { 5.dp.toPx() })
                    }
                }
            }
            Text(stringResource(R.string.card_map_hint), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.card_size), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardSize.entries.forEach { s ->
                    FilterChip(selected = size == s, onClick = { size = s; result = null }, label = {
                        Text(stringResource(when (s) { CardSize.SQUARE -> R.string.card_square; CardSize.PORTRAIT -> R.string.card_portrait; CardSize.LANDSCAPE -> R.string.card_landscape }))
                    })
                }
            }
            Text(stringResource(R.string.card_map_type), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(MapType.NORMAL to R.string.map_normal, MapType.SATELLITE to R.string.map_satellite, MapType.TERRAIN to R.string.map_terrain).forEach { (t, label) ->
                    FilterChip(selected = useMap && mapType == t, onClick = { useMap = true; mapType = t; result = null }, label = { Text(stringResource(label)) })
                }
                FilterChip(selected = !useMap, onClick = { useMap = false; result = null }, label = { Text(stringResource(R.string.card_no_map)) })
            }
            OutlinedTextField(value = title, onValueChange = { title = it; result = null }, label = { Text(stringResource(R.string.card_title_field)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.card_show_app), modifier = Modifier.weight(1f))
                Switch(checked = showApp, onCheckedChange = { showApp = it; result = null })
            }

            Text(stringResource(R.string.card_photos, ShareCardLayout.MAX_PHOTOS), style = MaterialTheme.typography.labelLarge)
            if (photos.isEmpty()) Text(stringResource(R.string.card_no_photos), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                photos.forEach { p ->
                    val idx = selected.indexOf(p.id)
                    val thumb = rememberThumb(p.thumbPath)
                    Box(
                        Modifier.size(76.dp).clip(RoundedCornerShape(6.dp))
                            .border(if (idx >= 0) 3.dp else 1.dp, if (idx >= 0) Color(0xFFE65100) else Color(0x33000000), RoundedCornerShape(6.dp))
                            .clickable {
                                selected = if (idx >= 0) selected - p.id else if (selected.size < ShareCardLayout.MAX_PHOTOS) selected + p.id else selected
                                result = null
                            },
                    ) {
                        if (thumb != null) Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (idx >= 0) {
                            Box(Modifier.padding(4.dp).size(22.dp).background(Color(0xFFE65100), CircleShape), contentAlignment = Alignment.Center) {
                                Text("${idx + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            if (photos.isNotEmpty()) Text(stringResource(R.string.card_photos_hint), style = MaterialTheme.typography.bodySmall)

            Button(onClick = { generate() }, enabled = !working && track != null, modifier = Modifier.fillMaxWidth()) {
                if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Text(stringResource(R.string.card_generate))
            }
            result?.let { bmp ->
                if (usedFallback) Text(stringResource(R.string.card_fallback_used), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Image(
                    bmp.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).border(1.dp, Color(0x33000000)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val uri = vm.photos.saveToLibrary(bmp, cardFileName(track?.name ?: "card"))
                            vm.toast(context, context.getString(if (uri != null) R.string.card_saved else R.string.card_save_failed))
                        }
                    }) { Text(stringResource(R.string.save)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                runCatching {
                                    val dir = File(context.cacheDir, "share").apply { mkdirs() }
                                    dir.listFiles()?.forEach { it.delete() }
                                    val f = File(dir, cardFileName(track?.name ?: "card") + ".jpg")
                                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                                    FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                                }.getOrNull()
                            } ?: return@launch
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
                        }
                    }) { Text(stringResource(R.string.share)) }
                }
            }
        }
    }
}

private fun cardFileName(name: String) = "CRL_card_" + Format.safeFileName(name).replace(' ', '_') + "_" + System.currentTimeMillis() / 1000
